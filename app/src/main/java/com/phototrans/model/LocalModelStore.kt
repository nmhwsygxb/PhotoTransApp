package com.phototrans.model

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 本地模型存储 - 管理格式学习模型
 *
 * 功能:
 *   - 版本管理 (创建、回滚、删除)
 *   - 模型存储 (JSON)
 *   - 导入导出
 *   - 增量学习
 */
class LocalModelStore(private val context: Context) {

    private val modelDir: File
    private val versionsDir: File
    private val fingerprintsDir: File
    private val currentFile: File

    init {
        modelDir = File(context.filesDir, "format_model")
        versionsDir = File(modelDir, "versions")
        fingerprintsDir = File(modelDir, "fingerprints")
        currentFile = File(modelDir, "current.json")
        ensureDirs()
    }

    private fun ensureDirs() {
        modelDir.mkdirs()
        versionsDir.mkdirs()
        fingerprintsDir.mkdirs()
    }

    // ─── 版本管理 ───────────────────────────────────

    fun getCurrentVersion(): Int? {
        if (!currentFile.exists()) return null
        return try {
            val json = JSONObject(currentFile.readText())
            json.getInt("model_version")
        } catch (e: Exception) {
            null
        }
    }

    fun listVersions(): List<VersionInfo> {
        val versions = mutableListOf<VersionInfo>()
        val current = getCurrentVersion()
        val files = versionsDir.listFiles()?.sortedByDescending { it.name } ?: return versions

        for (file in files) {
            try {
                val json = JSONObject(file.readText())
                val version = json.getInt("model_version")
                versions.add(VersionInfo(
                    version = version,
                    createdAt = json.optString("created_at", ""),
                    totalSamples = json.optInt("total_samples", 0),
                    brands = json.optJSONObject("brands")?.keySet()?.toList() ?: emptyList(),
                    isCurrent = version == current
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse version file: ${file.name}", e)
            }
        }
        return versions
    }

    fun saveModel(model: JSONObject): Int {
        val current = getCurrentVersion() ?: 0
        val version = current + 1
        model.put("model_version", version)
        model.put("saved_at", getCurrentTimestamp())

        val filename = "v${version}_${getCurrentTimestamp()}.json"
        val file = File(versionsDir, filename)
        file.writeText(model.toString(2))

        // 更新当前版本链接
        val currentJson = JSONObject()
        currentJson.put("model_version", version)
        currentJson.put("filename", filename)
        currentJson.put("updated_at", getCurrentTimestamp())
        currentFile.writeText(currentJson.toString(2))

        Log.d(TAG, "Saved model v$version")
        return version
    }

    fun loadModel(version: Int? = null): JSONObject? {
        val targetVersion = version ?: getCurrentVersion() ?: return null
        val files = versionsDir.listFiles() ?: return null
        val match = files.find { it.name.startsWith("v${targetVersion}_") } ?: return null
        return try {
            JSONObject(match.readText())
        } catch (e: Exception) {
            null
        }
    }

    fun rollback(version: Int): Boolean {
        val files = versionsDir.listFiles() ?: return false
        val match = files.find { it.name.startsWith("v${version}_") } ?: return false
        val currentJson = JSONObject()
        currentJson.put("model_version", version)
        currentJson.put("filename", match.name)
        currentJson.put("updated_at", getCurrentTimestamp())
        currentFile.writeText(currentJson.toString(2))
        Log.d(TAG, "Rolled back to v$version")
        return true
    }

    fun deleteVersion(version: Int): Boolean {
        if (version == getCurrentVersion()) return false
        val files = versionsDir.listFiles() ?: return false
        val match = files.find { it.name.startsWith("v${version}_") } ?: return false
        match.delete()
        return true
    }

    // ─── 指纹管理 ───────────────────────────────────

    fun saveFingerprint(brand: String, fingerprint: JSONObject) {
        val brandDir = File(fingerprintsDir, brand)
        brandDir.mkdirs()
        val fpId = fingerprint.optString("fingerprint_id", UUID.randomUUID().toString())
        val file = File(brandDir, "${fpId}.json")
        if (!file.exists()) {
            file.writeText(fingerprint.toString(2))
        }
    }

    fun loadFingerprints(brand: String): List<JSONObject> {
        val brandDir = File(fingerprintsDir, brand)
        if (!brandDir.exists()) return emptyList()
        return brandDir.listFiles()?.mapNotNull { file ->
            try {
                JSONObject(file.readText())
            } catch (e: Exception) { null }
        } ?: emptyList()
    }

    fun getFingerprintStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val brands = fingerprintsDir.listFiles() ?: return stats
        for (brandDir in brands) {
            if (brandDir.isDirectory) {
                val count = brandDir.listFiles()?.size ?: 0
                if (count > 0) stats[brandDir.name] = count
            }
        }
        return stats
    }

    // ─── 模型导入导出 ───────────────────────────────

    fun exportModel(version: Int? = null): String? {
        val model = loadModel(version) ?: return null
        val exportFile = File(modelDir, "exported_model.json")
        exportFile.writeText(model.toString(2))
        return exportFile.absolutePath
    }

    fun importModel(importPath: String): Int {
        val imported = JSONObject(File(importPath).readText())
        val current = loadModel()

        if (current == null) {
            return saveModel(imported)
        }

        // 合并模型
        val merged = JSONObject()
        merged.put("created_at", getCurrentTimestamp())
        merged.put("total_samples",
            current.optInt("total_samples", 0) + imported.optInt("total_samples", 0))

        // 合并品牌
        val mergedBrands = JSONObject()
        val localBrands = current.optJSONObject("brands") ?: JSONObject()
        val remoteBrands = imported.optJSONObject("brands") ?: JSONObject()

        val allBrands = mutableSetOf<String>()
        allBrands.addAll(localBrands.keySet())
        allBrands.addAll(remoteBrands.keySet())

        for (brand in allBrands) {
            val local = localBrands.optJSONObject(brand) ?: JSONObject()
            val remote = remoteBrands.optJSONObject(brand) ?: JSONObject()
            val mergedBrand = JSONObject()

            mergedBrand.put("sample_count",
                local.optInt("sample_count", 0) + remote.optInt("sample_count", 0))
            mergedBrand.put("common_ns", mergeJsonObjects(
                local.optJSONObject("common_ns") ?: JSONObject(),
                remote.optJSONObject("common_ns") ?: JSONObject()))
            mergedBrand.put("essential_tags", mergeJsonObjects(
                local.optJSONObject("essential_tags") ?: JSONObject(),
                remote.optJSONObject("essential_tags") ?: JSONObject()))

            mergedBrands.put(brand, mergedBrand)
        }
        merged.put("brands", mergedBrands)

        // 合并转换规则
        merged.put("conversion_rules", mergeJsonObjects(
            current.optJSONObject("conversion_rules") ?: JSONObject(),
            imported.optJSONObject("conversion_rules") ?: JSONObject()))

        return saveModel(merged)
    }

    // ─── 工具 ───────────────────────────────────────

    data class VersionInfo(
        val version: Int,
        val createdAt: String,
        val totalSamples: Int,
        val brands: List<String>,
        val isCurrent: Boolean
    )

    private fun getCurrentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun mergeJsonObjects(a: JSONObject, b: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in a.keySet()) result.put(key, a.get(key))
        for (key in b.keySet()) {
            if (!result.has(key)) result.put(key, b.get(key))
        }
        return result
    }

    companion object {
        private const val TAG = "LocalModelStore"
    }
}