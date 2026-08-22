package com.phototrans.format

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.*

/**
 * 格式转换器 - 跨品牌照片格式转换
 *
 * 转换策略: 容器重打包 + 元数据重写，不做重编码
 *   1. 检测源文件格式
 *   2. 拆解容器 (解出 XMP、视频、GainMap)
 *   3. 映射标签 (品牌私有标签 → 目标品牌标签)
 *   4. 重组容器 (注入目标品牌标签)
 */
class FormatConverter(private val context: Context) {

    data class ConversionResult(
        val success: Boolean,
        val outputPath: String? = null,
        val error: String? = null,
        val method: String? = null
    )

    // 品牌命名空间映射
    private val brandNsMap = mapOf(
        "oppo" to "http://ns.oplus.com/photos/1.0/camera/",
        "xiaomi" to "http://ns.xiaomi.com/photos/1.0/camera/",
        "samsung" to "http://ns.samsung.com/photos/1.0/camera/",
        "huawei" to "http://ns.huawei.com/photos/1.0/camera/",
        "vivo" to "http://ns.vivo.com/photos/1.0/camera/",
        "apple" to "http://ns.apple.com/HDRGainMap/1.0/"
    )

    // 品牌前缀映射
    private val brandPrefixMap = mapOf(
        "oppo" to "OpCamera",
        "xiaomi" to "MiCamera",
        "samsung" to "SCamera",
        "huawei" to "HCamera",
        "vivo" to "VCamera",
        "apple" to "HDRGainMap"
    )

    fun convert(
        inputPath: String,
        targetBrand: String,
        outputDir: String
    ): ConversionResult {
        return try {
            val inputFile = File(inputPath)
            if (!inputFile.exists()) {
                return ConversionResult(false, error = "文件不存在: $inputPath")
            }

            // 检测源文件
            val detector = FormatDetector(context)
            val sourceResult = detector.detect(inputPath)

            if (sourceResult.brandId == "unknown") {
                return ConversionResult(false, error = "无法识别文件格式")
            }

            val sourceBrand = sourceResult.brandId

            // 大文件不做全内存加载（>50MB 用流式复制）
            val maxMemoryBytes = 50L * 1024 * 1024
            if (inputFile.length() > maxMemoryBytes) {
                return copyFile(inputFile, sourceResult, targetBrand, outputDir, "stream_copy")
            }

            val bytes = inputFile.readBytes()

            // 根据容器类型选择转换方法
            val result = when (sourceResult.container) {
                "jpeg" -> convertJpeg(bytes, sourceResult, targetBrand, outputDir, inputFile.name)
                "heic", "heif" -> convertHeic(bytes, sourceResult, targetBrand, outputDir, inputFile.name)
                else -> ConversionResult(false, error = "不支持的容器: ${sourceResult.container}")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            ConversionResult(false, error = "转换失败: ${e.message}")
        }
    }

    private fun convertJpeg(
        bytes: ByteArray,
        source: FormatDetector.DetectionResult,
        targetBrand: String,
        outputDir: String,
        fileName: String
    ): ConversionResult {
        // 1. 提取 XMP 数据
        val xmpStart = bytes.indexOfBytes("<?xpacket".toByteArray())
        val xmpEnd = bytes.lastIndexOfBytes("<?xpacket".toByteArray())

        if (xmpStart < 0 || xmpEnd <= xmpStart) {
            // 没有 XMP → 简单复制
            return copyFile(bytes, outputDir, fileName, "simple_copy")
        }

        // XMP 结束标记 "<?xpacket end" 之后保留 100 字节的尾部声明
        val tailLen = minOf(100, bytes.size - (xmpEnd + 100))
        val xmpLen = (xmpEnd - xmpStart + 100).coerceAtMost(bytes.size - xmpStart)
        val xmpData = String(bytes, xmpStart, xmpLen, Charsets.UTF_8)

        // 2. 替换品牌命名空间和标签
        val convertedXmp = convertXmpNamespace(xmpData, source.brandId, targetBrand)

        // 3. 重组文件 (计算实际尾部偏移, 避免越界)
        val tailStart = (xmpEnd + 100).coerceAtMost(bytes.size)
        val outputBytes = ByteArrayOutputStream()
        outputBytes.write(bytes, 0, xmpStart)
        outputBytes.write(convertedXmp.toByteArray())
        outputBytes.write(bytes, tailStart, bytes.size - tailStart)

        // 4. 写入输出
        val outputName = "${fileName.substringBeforeLast('.')}_to_${targetBrand}.${fileName.substringAfterLast('.')}"
        val outputFile = File(outputDir, outputName)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(outputBytes.toByteArray())

        return ConversionResult(true, outputFile.absolutePath, method = "xmp_convert")
    }

    private fun convertHeic(
        bytes: ByteArray,
        source: FormatDetector.DetectionResult,
        targetBrand: String,
        outputDir: String,
        fileName: String
    ): ConversionResult {
        // HEIC 转换: 复制文件 + 更新 XMP (HEIC 的 XMP 在 'mime' box 中)
        // 简化版: 先复制文件，后续用 JNI libheif 处理
        val outputName = "${fileName.substringBeforeLast('.')}_to_${targetBrand}.${fileName.substringAfterLast('.')}"
        return copyFile(bytes, outputDir, outputName, "heic_copy")
    }

    private fun convertXmpNamespace(xmp: String, sourceBrand: String, targetBrand: String): String {
        var result = xmp

        // 替换命名空间声明
        val sourceNs = brandNsMap[sourceBrand]
        val targetNs = brandNsMap[targetBrand]

        if (sourceNs != null && targetNs != null) {
            result = result.replace(sourceNs, targetNs)
        }

        // 替换品牌前缀
        val sourcePrefix = brandPrefixMap[sourceBrand]
        val targetPrefix = brandPrefixMap[targetBrand]

        if (sourcePrefix != null && targetPrefix != null) {
            result = result.replace("${sourcePrefix}:", "${targetPrefix}:")
            result = result.replace("xmlns:$sourcePrefix", "xmlns:$targetPrefix")
        }

        // 移除 Google 容器标签 (如果目标不需要)
        if (targetBrand == "apple") {
            result = result.replace(Regex("<GCamera:[^>]+>[^<]*</GCamera:[^>]+>"), "")
            result = result.replace(Regex("<Item:[^>]+>[^<]*</Item:[^>]+>"), "")
            result = result.replace(Regex("<Container:[^>]+>[^<]*</Container:[^>]+>"), "")
            result = result.replace("xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"", "")
            result = result.replace("xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"", "")
            result = result.replace("xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"", "")
        }

        // 添加苹果 HDRGainMap 命名空间 (如果目标为苹果)
        if (targetBrand == "apple" && !result.contains("xmlns:HDRGainMap")) {
            result = result.replace(
                "<x:xmpmeta",
                "<x:xmpmeta xmlns:HDRGainMap=\"http://ns.apple.com/HDRGainMap/1.0/\""
            )
        }

        return result
    }

    private fun copyFile(bytes: ByteArray, outputDir: String, fileName: String, method: String): ConversionResult {
        val outputFile = File(outputDir, fileName)
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(bytes)
        return ConversionResult(true, outputFile.absolutePath, method = method)
    }

    /** 流式复制大文件，避免 OOM */
    private fun copyFile(input: File, source: FormatDetector.DetectionResult, targetBrand: String, outputDir: String, method: String): ConversionResult {
        val outputName = "${input.nameWithoutExtension}_to_${targetBrand}.${input.extension}"
        val outputFile = File(outputDir, outputName)
        outputFile.parentFile?.mkdirs()
        try {
            input.inputStream().use { `in` ->
                outputFile.outputStream().use { out ->
                    `in`.copyTo(out, bufferSize = 65536)
                }
            }
            return ConversionResult(true, outputFile.absolutePath, method = method)
        } catch (e: Exception) {
            Log.e(TAG, "Stream copy failed", e)
            return ConversionResult(false, error = "流式复制失败: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "FormatConverter"
    }
}