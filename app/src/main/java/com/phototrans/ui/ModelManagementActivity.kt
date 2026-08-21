package com.phototrans.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.phototrans.R
import com.phototrans.databinding.ActivityModelManagementBinding
import com.phototrans.model.LocalModelStore

/**
 * 模型管理页面 — 显示所有模型版本，支持切换和删除
 */
class ModelManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelManagementBinding
    private lateinit var modelStore: LocalModelStore
    private lateinit var adapter: ModelVersionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelStore = LocalModelStore(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = ModelVersionAdapter(
            onSwitch = { version -> switchToVersion(version) },
            onDelete = { version -> deleteVersion(version) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnLearnNow.setOnClickListener {
            startLearningService()
            Toast.makeText(this, "后台学习已启动", Toast.LENGTH_SHORT).show()
        }

        refreshData()
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    private fun refreshData() {
        val versions = modelStore.listVersions()
        val currentVersion = modelStore.getCurrentVersion() ?: 0
        val stats = modelStore.getFingerprintStats()
        adapter.submitList(versions, currentVersion, stats)

        // 空状态
        if (versions.isEmpty()) {
            binding.emptyState.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.emptyState.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
        }
    }

    private fun switchToVersion(version: Int) {
        val current = modelStore.getCurrentVersion() ?: 0
        if (version == current) {
            Toast.makeText(this, "已是当前版本 v$version", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = modelStore.rollback(version)
        if (ok) {
            Toast.makeText(this, "已切换到 v$version", Toast.LENGTH_SHORT).show()
            refreshData()
        } else {
            Toast.makeText(this, "切换失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteVersion(version: Int) {
        val current = modelStore.getCurrentVersion() ?: 0
        if (version == current) {
            Toast.makeText(this, "不能删除当前使用的版本", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("删除模型版本")
            .setMessage("确定要删除 v$version 吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                val ok = modelStore.deleteVersion(version)
                if (ok) {
                    Toast.makeText(this, "v$version 已删除", Toast.LENGTH_SHORT).show()
                    refreshData()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startLearningService() {
        val intent = Intent(this, com.phototrans.service.LearningService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "ModelManagement"

        fun start(context: Context) {
            context.startActivity(Intent(context, ModelManagementActivity::class.java))
        }
    }
}

/**
 * 模型版本列表适配器
 */
class ModelVersionAdapter(
    private val onSwitch: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ModelVersionAdapter.VersionViewHolder>() {

    private var items: List<LocalModelStore.VersionInfo> = emptyList()
    private var currentVersion = 0
    private var stats: Map<String, Int> = emptyMap()

    fun submitList(versions: List<LocalModelStore.VersionInfo>, current: Int, fpStats: Map<String, Int>) {
        items = versions.sortedByDescending { it.version }
        currentVersion = current
        stats = fpStats
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VersionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_version, parent, false)
        return VersionViewHolder(view)
    }

    override fun onBindViewHolder(holder: VersionViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, currentVersion, onSwitch, onDelete)
    }

    class VersionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val versionLabel: TextView = itemView.findViewById(R.id.versionLabel)
        private val versionTime: TextView = itemView.findViewById(R.id.versionTime)
        private val versionInfo: TextView = itemView.findViewById(R.id.versionInfo)
        private val versionBadge: TextView = itemView.findViewById(R.id.versionBadge)

        fun bind(
            item: LocalModelStore.VersionInfo,
            currentVersion: Int,
            onSwitch: (Int) -> Unit,
            onDelete: (Int) -> Unit
        ) {
            val ctx = itemView.context
            versionLabel.text = "v${item.version}"
            versionTime.text = "创建: ${item.createdAt}"
            versionInfo.text = "${item.totalSamples} 样本 · ${item.brands.size} 品牌: ${item.brands.joinToString(", ").ifEmpty { "无" }}"

            if (item.isCurrent) {
                versionBadge.text = "当前"
                versionBadge.setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.transfer_success))
            } else {
                versionBadge.text = "v${item.version}"
                versionBadge.setBackgroundColor(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
            }

            // 点击切换
            itemView.setOnClickListener {
                if (!item.isCurrent) onSwitch(item.version)
            }

            // 长按删除
            itemView.setOnLongClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("模型操作")
                    .setItems(arrayOf(
                        if (item.isCurrent) "当前版本" else "切换到此版本",
                        if (item.isCurrent) "（不能删除当前版本）" else "删除此版本"
                    )) { _, which ->
                        when (which) {
                            0 -> if (!item.isCurrent) onSwitch(item.version)
                            1 -> if (!item.isCurrent) onDelete(item.version)
                        }
                    }
                    .show()
                true
            }
        }
    }
}