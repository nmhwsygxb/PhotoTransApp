package com.phototrans.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.phototrans.BuildConfig
import com.phototrans.R
import com.phototrans.databinding.ActivitySettingsBinding
import com.phototrans.model.LocalModelStore
import java.io.File

/**
 * 设置页面 — 学习控制、存储管理、关于
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var modelStore: LocalModelStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelStore = LocalModelStore(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadSettings()
        setupClickListeners()
    }

    private fun loadSettings() {
        val currentVersion = modelStore.getCurrentVersion()
        val versions = modelStore.listVersions()
        val stats = modelStore.getFingerprintStats()
        val totalSamples = stats.values.sum()
        val cacheSize = getCacheSize()

        binding.settingsCurrentVersion.text = "v${currentVersion ?: 0}"
        binding.settingsAutoLearn.text = if (currentVersion != null) "已开启" else "未开启(尚无模型)"
        binding.settingsModelCount.text = "${versions.size} 个版本"
        binding.settingsSampleCount.text = "$totalSamples 张样本"
        binding.settingsSupportedBrands.text = if (stats.isEmpty()) "暂无" else stats.keys.joinToString(", ")
        binding.settingsCacheSize.text = cacheSize
        binding.settingsVersionName.text = BuildConfig.VERSION_NAME
    }

    private fun getCacheSize(): String {
        val cacheDir = File(cacheDir, "PhotoTransRecv")
        if (!cacheDir.exists()) return "0 KB"
        val size = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            else -> "${size / (1024 * 1024)} MB"
        }
    }

    private fun setupClickListeners() {
        // 模型管理
        binding.btnModelManagement.setOnClickListener {
            ModelManagementActivity.start(this)
        }

        // 立即学习
        binding.btnStartLearning.setOnClickListener {
            val intent = Intent(this, com.phototrans.service.LearningService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "后台学习已启动", Toast.LENGTH_SHORT).show()
        }

        // 清除缓存
        binding.btnClearCache.setOnClickListener {
            val cacheDir = File(cacheDir, "PhotoTransRecv")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
                loadSettings()
            } else {
                Toast.makeText(this, "暂无缓存", Toast.LENGTH_SHORT).show()
            }
        }

        // 清除学习数据
        binding.btnClearLearningData.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("清除学习数据")
                .setMessage("将删除所有模型版本和指纹数据，此操作不可撤销！")
                .setPositiveButton("清除") { _, _ ->
                    val modelDir = File(filesDir, "format_model")
                    if (modelDir.exists()) modelDir.deleteRecursively()
                    Toast.makeText(this, "学习数据已清除", Toast.LENGTH_SHORT).show()
                    loadSettings()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 关于
        binding.btnAbout.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("关于 PhotoTrans")
                .setMessage("PhotoTrans v${BuildConfig.VERSION_NAME}\n\n" +
                    "跨品牌照片/文件传输工具\n" +
                    "支持近距离(Wi-Fi Direct)和远距离(TCP直连)传输\n" +
                    "自动学习各品牌照片格式，实现格式转译\n\n" +
                    "开发: AI Assistant")
                .setPositiveButton("确定", null)
                .show()
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}