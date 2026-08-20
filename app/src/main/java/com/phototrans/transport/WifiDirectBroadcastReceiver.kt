package com.phototrans.transport

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * Wi-Fi Direct 广播接收器
 *
 * 动态注册 (API 33+ 静态注册 P2P 广播无效, 且本类无默认构造器)
 * 持有 channel 以便 requestPeers 正常回调
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiP2pManager?
) : android.content.BroadcastReceiver() {

    private var listener: WifiDirectTransport.TransferListener? = null
    private var channel: WifiP2pManager.Channel? = null
    /** 群组状态回调 (由 Transport 设置, 用于连接超时判断) */
    var groupStatusListener: ((Boolean) -> Unit)? = null
    private val peers = mutableListOf<WifiP2pDevice>()

    fun setListener(l: WifiDirectTransport.TransferListener?) {
        listener = l
    }

    fun setChannel(ch: WifiP2pManager.Channel?) {
        channel = ch
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                Log.d(TAG, "P2P state changed: $state")
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    peers.clear()
                    listener?.onPeersRefresh(0)
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                val ch = channel
                val mgr = manager
                if (ch == null || mgr == null) {
                    Log.w(TAG, "requestPeers: channel/manager 未就绪")
                    return
                }
                mgr.requestPeers(ch) { peerList ->
                    peers.clear()
                    peers.addAll(peerList.deviceList)
                    Log.d(TAG, "Peers refreshed: ${peers.size}")
                    listener?.onPeersRefresh(peers.size)
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                }

                if (info?.groupFormed == true) {
                    val host = info.groupOwnerAddress?.hostAddress ?: ""
                    val name = if (info.isGroupOwner) "本机 (群主)" else "群组已连接"
                    groupStatusListener?.invoke(true)
                    listener?.onConnected(name, host, info.isGroupOwner)
                    Log.d(TAG, "Group formed, isOwner=${info.isGroupOwner}, host=$host")
                } else {
                    groupStatusListener?.invoke(false)
                    listener?.onDisconnected()
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                Log.d(TAG, "This device: ${device?.deviceName}")
            }
        }
    }

    fun updatePeers(devices: Collection<WifiP2pDevice>) {
        peers.clear()
        peers.addAll(devices)
        listener?.onPeersRefresh(peers.size)
    }

    fun getPeers(): List<WifiP2pDevice> = peers.toList()

    companion object {
        private const val TAG = "WifiDirectReceiver"

        fun getIntentFilter(): IntentFilter {
            return IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
        }
    }
}