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
import com.phototrans.transport.UdpDiscoveryService

/**
 * 设备列表适配器 —— 支持 Wi-Fi Direct 设备和 UDP 发现设备。
 */
class DeviceAdapter(
    private val onDeviceClick: (DeviceItem) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    data class DeviceItem(
        val name: String,
        val ip: String? = null,
        val port: Int = 0,
        val isUdp: Boolean = false,
        val status: Int = WifiP2pDevice.AVAILABLE
    )

    private val devices = mutableListOf<DeviceItem>()

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

    /** 更新 Wi-Fi Direct 设备 */
    fun updateDevices(newDevices: List<WifiP2pDevice>) {
        // 保留已有的 UDP 设备
        val udpDevices = devices.filter { it.isUdp }
        devices.clear()
        devices.addAll(newDevices.map { DeviceItem(
            name = it.deviceName ?: "未知设备",
            status = it.status
        ) })
        devices.addAll(udpDevices)
        notifyDataSetChanged()
    }

    /** 添加/更新 UDP 发现设备 */
    fun updateUdpDevices(udpDevices: List<UdpDiscoveryService.DiscoveredDevice>) {
        // 保留已有的 Wi-Fi Direct 设备
        val wifiDevices = devices.filter { !it.isUdp }
        devices.clear()
        devices.addAll(wifiDevices)
        devices.addAll(udpDevices.map { DeviceItem(
            name = "${it.deviceName} (${it.brand})",
            ip = it.ip,
            port = it.port,
            isUdp = true
        ) })
        notifyDataSetChanged()
    }

    class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.deviceName)
        private val statusText: TextView = itemView.findViewById(R.id.deviceStatus)
        private val statusDot: View = itemView.findViewById(R.id.statusDot)
        private val iconView: ImageView = itemView.findViewById(R.id.deviceIcon)

        fun bind(device: DeviceItem) {
            nameText.text = device.name
            val ctx = itemView.context
            if (device.isUdp) {
                statusText.text = "局域网 · ${device.ip}"
                statusDot.background.setTint(ContextCompat.getColor(ctx, R.color.transfer_success))
                iconView.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
            } else {
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
}