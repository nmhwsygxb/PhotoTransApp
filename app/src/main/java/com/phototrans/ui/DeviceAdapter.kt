package com.phototrans.ui

import android.graphics.Color
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.phototrans.R

/**
 * 设备列表适配器
 */
class DeviceAdapter(
    private val onDeviceClick: (WifiP2pDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<WifiP2pDevice>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onDeviceClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    fun updateDevices(newDevices: List<WifiP2pDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.deviceName)
        private val statusText: TextView = itemView.findViewById(R.id.deviceStatus)
        private val statusDot: View = itemView.findViewById(R.id.statusDot)
        private val iconView: ImageView = itemView.findViewById(R.id.deviceIcon)

        fun bind(device: WifiP2pDevice) {
            nameText.text = device.deviceName ?: "未知设备"
            val ctx = itemView.context
            val (statusLabel, colorRes, iconTint) = when (device.status) {
                WifiP2pDevice.AVAILABLE -> Triple("可用 · 点击发送", R.color.transfer_success, R.color.primary)
                WifiP2pDevice.INVITED -> Triple("邀请中…", R.color.transfer_progress, R.color.primary)
                WifiP2pDevice.CONNECTED -> Triple("已连接", R.color.primary, R.color.primary)
                WifiP2pDevice.FAILED -> Triple("连接失败", R.color.transfer_failed, R.color.text_secondary)
                WifiP2pDevice.UNAVAILABLE -> Triple("不可用", R.color.text_secondary, R.color.text_secondary)
                else -> Triple("未知", R.color.text_secondary, R.color.text_secondary)
            }
            statusText.text = statusLabel
            statusDot.background.setTint(ContextCompat.getColor(ctx, colorRes))
            iconView.setColorFilter(ContextCompat.getColor(ctx, iconTint))
        }
    }
}