package com.phototrans.format

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.*

/**
 * 格式检测器 - 识别照片的品牌、格式、动态照片、HDR
 *
 * 检测策略 (4 层):
 *   1. XMP 命名空间: 检测品牌私有命名空间
 *   2. 二进制签名: 检测文件头/尾的特定标记
 *   3. HEIC Box: 检测 ftyp box 中的品牌标记
 *   4. EXIF 设备信息: 检测设备制造商
 */
class FormatDetector(private val context: Context) {

    data class DetectionResult(
        val brandName: String,        // 品牌名 (中文)
        val brandId: String,          // 品牌 ID (英文小写)
        val container: String,        // 容器类型: jpeg, heic, heif
        val hasMotionPhoto: Boolean,  // 是否有动态照片
        val hasHdr: Boolean,          // 是否有 HDR
        val format: String,           // 完整格式名
        val confidence: Double,       // 置信度 0.0-1.0
        val xmpNamespaces: Map<String, String>,  // XMP 命名空间
        val essentialTags: Map<String, String>,  // 核心标签
        val details: Map<String, Any>            // 详细检测信息
    )

    // 品牌私有命名空间映射
    private val brandNamespaces = mapOf(
        "http://ns.oplus.com/photos/1.0/camera/" to "OPPO",
        "http://ns.xiaomi.com/photos/1.0/camera/" to "小米",
        "http://ns.vivo.com/photos/1.0/camera/" to "vivo",
        "http://ns.huawei.com/photos/1.0/camera/" to "华为",
        "http://ns.apple.com/HDRGainMap/1.0/" to "苹果",
        "http://ns.samsung.com/photos/1.0/camera/" to "三星"
    )

    // 品牌二进制签名
    private val brandSignatures = listOf(
        BrandSignature("OPPO", "LIVE_".toByteArray(), 0),
        BrandSignature("华为", "HUAWEI".toByteArray(), 0),
        BrandSignature("华为", "Huawei".toByteArray(), 0),
        BrandSignature("小米", "XIAOMI".toByteArray(), 0),
        BrandSignature("小米", "MiCamera".toByteArray(), 0)
    )

    data class BrandSignature(
        val brand: String,
        val signature: ByteArray,
        val offset: Int
    )

    // HEIC 品牌标识
    private val heicBrands = mapOf(
        "heic" to "苹果",
        "heix" to "苹果",
        "mif1" to "小米",
        "msf1" to "三星"
    )

    fun detect(filePath: String): DetectionResult {
        val file = File(filePath)
        if (!file.exists()) {
            return DetectionResult("未知", "unknown", "unknown", false, false,
                "unknown", 0.0, emptyMap(), emptyMap(), emptyMap())
        }

        // 只读前 8MB 避免大文件 OOM
        val bytes = try {
            val fis = FileInputStream(file)
            fis.use { it.readBytes().take(8 * 1024 * 1024).toByteArray() }
        } catch (e: Exception) {
            return DetectionResult("未知", "unknown", "unknown", false, false,
                "unknown", 0.0, emptyMap(), emptyMap(), emptyMap())
        }
        return detectFromBytes(bytes)
    }

    /**
     * 通过 Content URI 检测 (Android 10+ scoped storage 兼容)
     * 读取文件前 8MB 用于检测, 避免大文件 OOM
     */
    fun detect(uri: Uri, context: Context): DetectionResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return DetectionResult(
                "未知", "unknown", "unknown", false, false,
                "unknown", 0.0, emptyMap(), emptyMap(), emptyMap())
            // 只读前 8MB 用于检测
            val bytes = inputStream.use { it.readBytes().take(8 * 1024 * 1024).toByteArray() }
            detectFromBytes(bytes)
        } catch (e: Exception) {
            DetectionResult("未知", "unknown", "unknown", false, false,
                "unknown", 0.0, emptyMap(), emptyMap(), emptyMap())
        }
    }

    private fun detectFromBytes(bytes: ByteArray): DetectionResult {
        val xmpData = extractXmp(bytes)
        val namespaces = xmpData.first
        val tags = xmpData.second
        val container = detectContainer(bytes)
        val heicBrand = detectHeicBrand(bytes, container)
        val binaryBrand = detectBinarySignature(bytes)
        val nsBrand = detectNamespaceBrand(namespaces)
        val deviceBrand = detectDeviceBrand(tags)
        val motionPhoto = detectMotionPhoto(bytes, tags, namespaces)
        val hdr = detectHdr(bytes, tags, namespaces)

        // 综合判断品牌
        val brand = resolveBrand(nsBrand, binaryBrand, heicBrand, deviceBrand)
        val confidence = calculateConfidence(brand, nsBrand, binaryBrand, heicBrand, deviceBrand)

        return DetectionResult(
            brandName = brand.name,
            brandId = brand.id,
            container = container,
            hasMotionPhoto = motionPhoto,
            hasHdr = hdr,
            format = buildFormatName(brand.name, motionPhoto, hdr),
            confidence = confidence,
            xmpNamespaces = namespaces,
            essentialTags = tags,
            details = mapOf(
                "detected_by" to brand.source,
                "container" to container,
                "has_motion_photo" to motionPhoto,
                "has_hdr" to hdr
            )
        )
    }

    private fun detectContainer(bytes: ByteArray): String {
        if (bytes.size < 12) return "unknown"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpeg"
            bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&  // 'ft'
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() -> { // 'yp'
                val ftyp = String(bytes, 8, 4, Charsets.US_ASCII)
                if (ftyp.startsWith("he")) "heic" else "heif"
            }
            else -> "unknown"
        }
    }

    private fun extractXmp(bytes: ByteArray): Pair<Map<String, String>, Map<String, String>> {
        val namespaces = mutableMapOf<String, String>()
        val tags = mutableMapOf<String, String>()

        // 查找 XMP 数据 (JPEG APP1 标记)
        val xmpStr = findXmpString(bytes) ?: return Pair(namespaces, tags)

        // 提取命名空间
        val nsRegex = Regex("xmlns:(\\w+)=\"([^\"]+)\"")
        for (match in nsRegex.findAll(xmpStr)) {
            namespaces[match.groupValues[1]] = match.groupValues[2]
        }

        // 提取标签
        val tagRegex = Regex("<(\\w+:\\w+)>([^<]*)</\\1>")
        for (match in tagRegex.findAll(xmpStr)) {
            tags[match.groupValues[1]] = match.groupValues[2].trim()
        }

        return Pair(namespaces, tags)
    }

    private fun findXmpString(bytes: ByteArray): String? {
        // JPEG XMP 在 APP1 标记中
        val xmpId = bytes.indexOfBytes("http://ns.adobe.com/xap/1.0/".toByteArray())
        if (xmpId >= 0) {
            val start = bytes.indexOfBytes("<?xpacket".toByteArray(), xmpId)
            if (start >= 0) {
                val end = bytes.indexOfBytes("<?xpacket".toByteArray(), start + 1)
                if (end >= 0) {
                    return String(bytes, start, end - start + 100, Charsets.UTF_8)
                }
            }
        }
        // HEIC 中的 XMP
        val xmpUtf8 = bytes.indexOfBytes("http://ns.adobe.com/xap/1.0/".toByteArray())
        if (xmpUtf8 >= 0) {
            val start = bytes.lastIndexOfByte('<'.code.toByte(), xmpUtf8 - 1)
            if (start >= 0) {
                val end = bytes.indexOfByte('>'.code.toByte(), xmpUtf8) + 1
                if (end > start) {
                    return String(bytes, start, end - start, Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun detectHeicBrand(bytes: ByteArray, container: String): String? {
        if (container != "heic" && container != "heif") return null
        if (bytes.size < 12) return null
        val brand = String(bytes, 8, 4, Charsets.US_ASCII)
        return heicBrands[brand]
    }

    private fun detectBinarySignature(bytes: ByteArray): String? {
        for (sig in brandSignatures) {
            val pos = bytes.indexOfBytes(sig.signature, sig.offset)
            if (pos >= 0) return sig.brand
        }
        return null
    }

    private fun detectNamespaceBrand(namespaces: Map<String, String>): String? {
        for ((uri, brand) in brandNamespaces) {
            if (namespaces.containsValue(uri)) return brand
        }
        return null
    }

    private fun detectDeviceBrand(tags: Map<String, String>): String? {
        val maker = tags["tiff:Make"] ?: tags["exif:Make"] ?: return null
        return when {
            maker.contains("OPPO", ignoreCase = true) -> "OPPO"
            maker.contains("Xiaomi", ignoreCase = true) -> "小米"
            maker.contains("Samsung", ignoreCase = true) -> "三星"
            maker.contains("HUAWEI", ignoreCase = true) -> "华为"
            maker.contains("vivo", ignoreCase = true) -> "vivo"
            maker.contains("Apple", ignoreCase = true) -> "苹果"
            maker.contains("Google", ignoreCase = true) -> "Google"
            else -> null
        }
    }

    private fun detectMotionPhoto(bytes: ByteArray, tags: Map<String, String>,
                                   namespaces: Map<String, String>): Boolean {
        // MotionPhoto 标记
        if (tags["GCamera:MotionPhoto"] == "1") return true
        if (tags["OpCamera:MotionPhotoOwner"] != null) return true
        if (tags["MiCamera:MicroVideo"] == "1") return true
        
        // 检查嵌入式视频 (ftyp 标记)
        if (bytes.indexOfBytes("ftypmp4".toByteArray()) >= 0) return true
        if (bytes.indexOfBytes("ftypisom".toByteArray()) >= 0) return true
        
        // 检查 MP4 分离器
        if (bytes.indexOfBytes(byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x66, 0x74, 0x79, 0x70)) >= 0) return true
        
        return false
    }

    private fun detectHdr(bytes: ByteArray, tags: Map<String, String>,
                           namespaces: Map<String, String>): Boolean {
        if (namespaces.containsKey("hdrgm")) return true
        if (namespaces.containsKey("HDRGainMap")) return true
        if (tags.containsKey("hdrgm:Version")) return true
        
        // 检查 UltraHDR GainMap
        if (bytes.indexOfBytes("http://ns.adobe.com/hdr-gain-map/1.0/".toByteArray()) >= 0) return true
        if (bytes.indexOfBytes("http://ns.apple.com/HDRGainMap/1.0/".toByteArray()) >= 0) return true
        
        return false
    }

    private data class BrandResult(val name: String, val id: String, val source: String)

    private fun resolveBrand(
        nsBrand: String?,
        binaryBrand: String?,
        heicBrand: String?,
        deviceBrand: String?
    ): BrandResult {
        // 优先级: 命名空间 > 二进制签名 > HEIC > 设备
        val brand = nsBrand ?: binaryBrand ?: heicBrand ?: deviceBrand ?: "未知"
        val id = brandToId(brand)
        val source = when {
            nsBrand != null -> "namespace"
            binaryBrand != null -> "binary_signature"
            heicBrand != null -> "heic_brand"
            deviceBrand != null -> "device_maker"
            else -> "unknown"
        }
        return BrandResult(brand, id, source)
    }

    private fun calculateConfidence(
        brand: BrandResult,
        nsBrand: String?,
        binaryBrand: String?,
        heicBrand: String?,
        deviceBrand: String?
    ): Double {
        var sources = 0
        if (nsBrand != null) sources++
        if (binaryBrand != null) sources++
        if (heicBrand != null) sources++
        if (deviceBrand != null) sources++
        return when (sources) {
            0 -> 0.0
            1 -> 0.33
            2 -> 0.66
            3 -> 0.85
            else -> 1.0
        }
    }

    private fun buildFormatName(brand: String, mp: Boolean, hdr: Boolean): String {
        val parts = mutableListOf(brand.lowercase())
        if (mp) parts.add("motion_photo")
        if (hdr) parts.add("hdr")
        return parts.joinToString("_")
    }

    companion object {
        fun brandToId(name: String): String = when (name) {
            "OPPO" -> "oppo"
            "小米" -> "xiaomi"
            "三星" -> "samsung"
            "华为" -> "huawei"
            "苹果" -> "apple"
            "vivo" -> "vivo"
            "Google" -> "google"
            else -> name.lowercase()
        }
    }
}