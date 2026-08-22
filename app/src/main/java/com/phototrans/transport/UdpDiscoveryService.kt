package com.phototrans.transport

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * UDP 局域网设备发现 —— 与 iOS / HarmonyOS 版本兼容。
 *
 * 协议（与 HarmonyOS DiscoveryService 一致）：
 *   Beacon 格式: PT-BEACON|<deviceId>|<name>|<port>|<proto>|<brand>
 *   端口: 47809
 *   发送间隔: 2 秒
 *   超时清除: 8 秒
 */
class UdpDiscoveryService {

    data class DiscoveredDevice(
        val deviceId: String,
        val deviceName: String,
        val ip: String,
        val port: Int,
        val brand: String,
        var lastSeen: Long
    )

    interface Listener {
        fun onDevicesChanged(devices: List<DiscoveredDevice>)
    }

    companion object {
        private const val TAG = "UdpDiscovery"
        const val DISCOVERY_PORT = 47809
        const val BEACON_INTERVAL_MS = 2000L
        const val PRUNE_TIMEOUT_MS = 8000L
        const val BEACON_PREFIX = "PT-BEACON"
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var listener: Listener? = null
    private var beaconThread: Thread? = null
    private var listenerThread: Thread? = null

    private val devices = mutableMapOf<String, DiscoveredDevice>()
    private val deviceLock = Any()

    // 本机身份
    private var myDeviceId = "PH-${System.currentTimeMillis().toString(36)}"
    private var myDeviceName = "Android"
    private var myBrand = "android"
    private var tcpPort = 47808

    fun setupIdentity(deviceId: String? = null, deviceName: String, brand: String = "android", tcpPort: Int = 47808) {
        if (deviceId != null) myDeviceId = deviceId
        myDeviceName = deviceName
        myBrand = brand
        this.tcpPort = tcpPort
    }

    fun setListener(l: Listener?) { listener = l }

    fun start() {
        if (running.getAndSet(true)) return
        Log.d(TAG, "Starting UDP discovery on port $DISCOVERY_PORT")

        try {
            socket = DatagramSocket(DISCOVERY_PORT).apply {
                broadcast = true
                soTimeout = 3000
            }
        } catch (e: Exception) {
            Log.w(TAG, "bind $DISCOVERY_PORT failed, try ephemeral: ${e.message}")
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3000
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot bind UDP socket", e2)
                running.set(false)
                return
            }
        }
        val sock = socket ?: run { running.set(false); return }

        // 接收线程
        listenerThread = Thread {
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    val fromIp = pkt.address.hostAddress ?: "unknown"
                    val dev = parseBeacon(text, fromIp) ?: continue
                    if (dev.deviceId == myDeviceId) continue // 跳过自己
                    synchronized(deviceLock) {
                        dev.lastSeen = System.currentTimeMillis()
                        devices[dev.deviceId] = dev
                    }
                    notifyListener()
                } catch (e: java.net.SocketTimeoutException) {
                    // 正常超时，继续循环
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "receive error: ${e.message}")
                }
            }
        }.apply { isDaemon = true; start() }

        // 广播线程
        beaconThread = Thread {
            while (running.get()) {
                sendBeacon(sock)
                try { Thread.sleep(BEACON_INTERVAL_MS) } catch (_: InterruptedException) { break }
                pruneDevices()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        Log.d(TAG, "Stopping UDP discovery")
        socket?.close()
        socket = null
        beaconThread = null
        listenerThread = null
        synchronized(deviceLock) { devices.clear() }
        notifyListener()
    }

    fun getDevices(): List<DiscoveredDevice> = synchronized(deviceLock) { devices.values.toList() }

    // ─── 内部 ───────────────────────────────────────

    private fun sendBeacon(sock: DatagramSocket) {
        try {
            val beacon = "$BEACON_PREFIX|$myDeviceId|$myDeviceName|$tcpPort|1.0|$myBrand"
            val data = beacon.toByteArray(Charsets.UTF_8)
            // 向所有网络接口的广播地址发送
            val broadcastAddresses = getBroadcastAddresses()
            for (addr in broadcastAddresses) {
                try {
                    val pkt = DatagramPacket(data, data.size, addr, DISCOVERY_PORT)
                    sock.send(pkt)
                } catch (_: Exception) { /* 跳过无法发送的接口 */ }
            }
            // 也向全局广播地址发送
            try {
                val global = InetAddress.getByName("255.255.255.255")
                val pkt = DatagramPacket(data, data.size, global, DISCOVERY_PORT)
                sock.send(pkt)
            } catch (_: Exception) { /* ignore */ }
        } catch (e: Exception) {
            Log.w(TAG, "sendBeacon error: ${e.message}")
        }
    }

    private fun pruneDevices() {
        val now = System.currentTimeMillis()
        var changed = false
        synchronized(deviceLock) {
            val it = devices.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeen > PRUNE_TIMEOUT_MS) {
                    it.remove()
                    changed = true
                }
            }
        }
        if (changed) notifyListener()
    }

    private fun parseBeacon(text: String, fromIp: String): DiscoveredDevice? {
        val parts = text.split('|')
        if (parts.size < 6 || parts[0] != BEACON_PREFIX) return null
        return DiscoveredDevice(
            deviceId = parts[1],
            deviceName = parts[2],
            ip = fromIp,
            port = parts[3].toIntOrNull() ?: 47808,
            brand = parts[5],
            lastSeen = System.currentTimeMillis()
        )
    }

    private fun getBroadcastAddresses(): Set<InetAddress> {
        val result = mutableSetOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    result.add(broadcast)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getBroadcastAddresses error: ${e.message}")
        }
        return result
    }

    private fun notifyListener() {
        val snapshot = getDevices()
        listener?.onDevicesChanged(snapshot)
    }
}