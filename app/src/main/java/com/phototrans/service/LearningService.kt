package com.phototrans.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.phototrans.R
import com.phototrans.format.FormatDetector
import com.phototrans.model.LocalModelStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 文件级 TAG - 供顶层函数 (learnFromPhotos/buildModel) 使用 */
private const val TAG = "PhotoTransLearning"

/**
 * 后台学习服务
 *
 * 在手机空闲时扫描照片格式并更新模型
 * 使用 WorkManager 调度，避免耗电
 */
class LearningService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LEARNING -> {
                scheduleLearning(this)
            }
            ACTION_LEARN_NOW -> {
                learnFromPhotos(this)
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_START_LEARNING = "com.phototrans.START_LEARNING"
        const val ACTION_LEARN_NOW = "com.phototrans.LEARN_NOW"
        const val WORK_NAME = "photo_trans_learning"

        fun startLearning(context: Context) {
            val intent = Intent(context, LearningService::class.java).apply {
                action = ACTION_START_LEARNING
            }
            context.startService(intent)
        }

        fun learnNow(context: Context) {
            val intent = Intent(context, LearningService::class.java).apply {
                action = ACTION_LEARN_NOW
            }
            context.startService(intent)
        }

        fun scheduleLearning(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)        // 充电时
                .setRequiresDeviceIdle(true)      // 设备空闲时
                .build()

            val work = PeriodicWorkRequestBuilder<LearningWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    work
                )
        }
    }
}

/**
 * 后台学习 Worker
 */
class LearningWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d(TAG, "Starting background learning...")
        return try {
            learnFromPhotos(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Learning failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LearningWorker"
    }
}

/**
 * 学习入口 - 扫描照片并更新模型
 */
fun learnFromPhotos(context: Context) {
    val detector = FormatDetector(context)
    val modelStore = LocalModelStore(context)

    // 使用 ContentResolver 查询 (兼容 Android 10+ scoped storage)
    val projection = arrayOf(
        android.provider.MediaStore.Images.Media._ID,
        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
        android.provider.MediaStore.Images.Media.SIZE
    )
    // 只处理 50MB 以内的照片
    val selection = "${android.provider.MediaStore.Images.Media.SIZE} > 0 AND " +
        "${android.provider.MediaStore.Images.Media.SIZE} < 52428800"

    // 通过 Content URI 逐张检测
    val uri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val cursor = context.contentResolver.query(
        uri, projection, selection, null,
        android.provider.MediaStore.Images.Media.DATE_TAKEN + " DESC"
    )

    var newSamples = 0
    cursor?.use { c ->
        while (c.moveToNext()) {
            val id = c.getLong(0)
            val name = c.getString(1) ?: "unknown_${id}"
            // SIZE 列已在 selection 中过滤

            try {
                // 通过 Content URI 读取 (兼容 scoped storage)
                val photoUri = android.net.Uri.withAppendedPath(uri, id.toString())
                val result = detector.detect(photoUri, context)
                if (result.brandId == "unknown") {
                    Log.d(TAG, "Skip unknown: $name")
                    continue
                }

                // 构建指纹
                val fingerprint = JSONObject().apply {
                    put("fingerprint_id", "${result.brandId}_${name}_${id}")
                    put("brand", result.brandName)
                    put("brand_id", result.brandId)
                    put("container", result.container)
                    put("has_motion_photo", result.hasMotionPhoto)
                    put("has_hdr", result.hasHdr)
                    put("format", result.format)
                    put("confidence", result.confidence)

                    val ns = JSONObject(result.xmpNamespaces)
                    put("xmp_namespaces", ns)

                    val tags = JSONObject(result.essentialTags)
                    put("essential_tags", tags)

                    put("device_model", android.os.Build.MODEL)
                }

                modelStore.saveFingerprint(result.brandId, fingerprint)
                newSamples++

                // 每次最多处理 50 张
                if (newSamples >= 50) break
            } catch (e: Exception) {
                Log.w(TAG, "Failed to analyze $name", e)
            }
        }
    }

    Log.d(TAG, "Learned from $newSamples new photos")

    // 如果有新样本，构建并保存模型
    if (newSamples > 0) {
        val model = buildModel(modelStore)
        if (model != null) {
            modelStore.saveModel(model)
            Log.d(TAG, "Model updated with $newSamples new samples")
        }
    }
}

/**
 * 从本地指纹构建模型
 */
private fun buildModel(modelStore: LocalModelStore): JSONObject? {
    val stats = modelStore.getFingerprintStats()
    if (stats.isEmpty()) return null

    val model = JSONObject()
    model.put("created_at", java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
    model.put("total_samples", stats.values.sum())

    val brands = JSONObject()
    for ((brandId, count) in stats) {
        val fingerprints = modelStore.loadFingerprints(brandId)
        if (fingerprints.isEmpty()) continue

        val brandProfile = JSONObject()
        brandProfile.put("sample_count", count)

        // 聚合命名空间
        val commonNs = JSONObject()
        for (fp in fingerprints) {
            val ns = fp.optJSONObject("xmp_namespaces")
            ns?.let {
                for (key in it.keySet()) {
                    if (!commonNs.has(key)) {
                        commonNs.put(key, it.get(key))
                    }
                }
            }
        }
        brandProfile.put("common_ns", commonNs)

        // 聚合标签
        val commonTags = JSONObject()
        for (fp in fingerprints) {
            val tags = fp.optJSONObject("essential_tags")
            tags?.let {
                for (key in it.keySet()) {
                    if (!commonTags.has(key) && key.contains(":")) {
                        commonTags.put(key, it.get(key))
                    }
                }
            }
        }
        brandProfile.put("essential_tags", commonTags)

        brands.put(brandId, brandProfile)
    }
    model.put("brands", brands)

    return model
}