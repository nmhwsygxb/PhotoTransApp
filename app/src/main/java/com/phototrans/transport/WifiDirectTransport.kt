package com.phototrans.transport

import android.content.Context
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct 传输层
 *
 * 兼容所有品牌互传协议:
 *   - 小米/OPPO/vivo/真我/魅族 互传联盟: Wi-Fi Direct + HTTP PUT
 *   - 三星 Quick Share: Wi-Fi Direct + HTTP
 *   - 华为 Share: Wi-Fi Direct + TCP
 *   - Google Nearby Share: Wi-Fi + BLE
 *
 * 统一使用 Wi-Fi Direct + HTTP PUT 传输文件
 *
 * 使用流程:
 *   1. 接收方: startServer(saveDir) → 创建 P2P 群组并监听端口
 *   2. 发送方: connect(device) → 加入群组 → 群组形成后回调 onConnected
 *             携带 GO 地址 (接收方), 用 sendFile 发送
 */
class WifiDirectTransport private constructor(context: Context) {

    interface TransferListener {
        fun onDeviceFound(device: WifiP2pDevice) {}
        fun onDeviceLost(device: WifiP2pDevice) {}
        /** 群组已形成, host 为群主(GO)地址, 接收方参考 */
        fun onConnected(deviceName: String, host: String, isGroupOwner: Boolean) {}
        /** 对方通过握手/数据连接被识别 (机型 + 地址) */
        fun onPeerIdentified(deviceName: String, host: String) {}
        fun onDisconnected() {}
        /** 附近的设备数量刷新 */
        fun onPeersRefresh(count: Int) {}
        fun onTransferProgress(bytesTransferred: Long, totalBytes: Long) {}
        fun onTransferComplete(filePath: String) {}
        /** 收到对方发送的模型学习成果文件 (需要导入) */
        fun onModelFileReceived(filePath: String) {}
        fun onTransferFailed(error: String) {}
    }

    private val appContext: Context = context.applicationContext
    private val manager: WifiP2pManager?
    private var channel: WifiP2pManager.Channel? = null
    private var listener: TransferListener? = null
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    // 当前角色 (发送方/接收方), 用于 onConnected 分流
    enum class Role { NONE, SENDER, RECEIVER }
    private var role: Role = Role.NONE

    // 广播接收器 (动态注册, 避免 Manifest 无默认构造崩溃)
    private var receiver: WifiDirectBroadcastReceiver? = null
    private var registered = false
    private var discoveryJob: Job? = null
    private var connectTimeoutJob: Job? = null
    /** 当前发送协程，stopServer 时取消 */
    private var sendJob: Job? = null

    /** P2P 群组是否已形成 (用于连接超时判断) */
    @Volatile
    private var p2pGroupFormed = false

    // 传输端口 (与互传联盟兼容)
    companion object {
        const val TRANSFER_PORT = 47808
        private const val TAG = "WifiDirectTransport"

        @Volatile
        private var instance: WifiDirectTransport? = null

        fun getInstance(context: Context): WifiDirectTransport {
            return instance ?: synchronized(this) {
                instance ?: WifiDirectTransport(context).also { instance = it }
            }
        }
    }

    init {
        @Suppress("DEPRECATION")
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            Log.e(TAG, "设备不支持 Wi-Fi Direct (WIFI_P2P_SERVICE 不可用)")
        }
        receiver = WifiDirectBroadcastReceiver(manager)
    }

    /** 设备是否支持 Wi-Fi Direct */
    fun isAvailable(): Boolean = manager != null

    /** 当前角色 (发送方/接收方) */
    fun currentRole(): Role = role

    /** 动态注册广播接收器, 返回是否成功 */
    fun register(): Boolean {
        if (registered) return true
        val mgr = manager ?: run {
            listener?.onTransferFailed("设备不支持 Wi-Fi Direct")
            return false
        }
        try {
            val ch = mgr.initialize(appContext, appContext.mainLooper) {
                Log.e(TAG, "P2P channel lost")
            }
            if (ch == null) {
                Log.e(TAG, "Wi-Fi Direct 不可用 (initialize 返回 null)")
                listener?.onTransferFailed("Wi-Fi Direct 未就绪, 请开启 Wi-Fi")
                return false
            }
            channel = ch
            receiver?.setChannel(ch)
            receiver?.groupStatusListener = { formed ->
                p2pGroupFormed = formed
                if (formed) connectTimeoutJob?.cancel()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Wi-Fi Direct 权限不足", e)
            listener?.onTransferFailed("需要位置权限才能使用 Wi-Fi Direct")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "初始化 Wi-Fi Direct 失败", e)
            listener?.onTransferFailed("Wi-Fi Direct 初始化失败: ${e.message}")
            return false
        }
        try {
            // 使用 RECEIVER_EXPORTED 以确保能接收系统 P2P 广播
            appContext.registerReceiver(receiver, WifiDirectBroadcastReceiver.getIntentFilter(),
                Context.RECEIVER_EXPORTED)
            registered = true
        } catch (e: Exception) {
            Log.e(TAG, "注册广播失败", e)
            return false
        }
        return true
    }

    fun unregister() {
        if (registered) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // 忽略未注册
            }
            registered = false
        }
        channel = null
        receiver?.setChannel(null)
    }

    fun setListener(l: TransferListener) {
        listener = l
        receiver?.setListener(l)
    }

    /** 清除监听器 (Activity onDestroy 调用, 防止泄漏) */
    fun clearListener() {
        listener = null
        receiver?.setListener(null)
    }

    fun getReceiver(): WifiDirectBroadcastReceiver? = receiver
    fun isRegistered(): Boolean = registered

    // ─── 发现设备 ───────────────────────────────────

    fun startDiscovery(showError: Boolean = true) {
        if (isRunning) return
        if (!register()) {
            if (showError) {
                listener?.onTransferFailed("设备不支持 Wi-Fi Direct")
            }
            return
        }
        val mgr = manager ?: return
        val ch = channel ?: return
        isRunning = true

        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery started")
                // 立即请求一次设备列表
                refreshPeersOnce()
                // 启动定期刷新 (某些设备不发送 PEERS_CHANGED_ACTION)
                discoveryJob = CoroutineScope(Dispatchers.IO).launch {
                    while (isActive) {
                        delay(3000)
                        refreshPeersOnce()
                    }
                }
            }

            override fun onFailure(reason: Int) {
                val msg = when (reason) {
                    WifiP2pManager.BUSY -> "Wi-Fi Direct 忙, 请稍后重试"
                    WifiP2pManager.ERROR -> "Wi-Fi Direct 错误"
                    WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
                    else -> "搜索失败 ($reason)"
                }
                Log.e(TAG, "Discovery failed: $reason ($msg)")
                listener?.onTransferFailed(msg)
                isRunning = false
            }
        })
    }

    /** 请求一次附近的设备列表并更新缓存 + 通知监听器 */
    fun refreshPeersOnce() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestPeers(ch) { peerList ->
                val devices = peerList.deviceList
                Log.d(TAG, "Peers refreshed: ${devices.size}")
                receiver?.updatePeers(devices)
                listener?.onPeersRefresh(devices.size)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "requestPeers 权限不足", e)
            listener?.onTransferFailed("需要位置权限才能搜索设备")
        } catch (e: Exception) {
            Log.e(TAG, "requestPeers 失败", e)
        }
    }

    fun stopDiscovery() {
        isRunning = false
        discoveryJob?.cancel()
        discoveryJob = null
        val mgr = manager ?: return
        channel?.let { mgr.stopPeerDiscovery(it, null) }
    }

    fun getPeers(): List<WifiP2pDevice> = receiver?.getPeers() ?: emptyList()

    // ─── 连接设备 ───────────────────────────────────

    fun connect(device: WifiP2pDevice) {
        val ch = channel ?: run {
            listener?.onTransferFailed("Wi-Fi Direct 未就绪, 请先搜索设备")
            return
        }
        val mgr = manager ?: run {
            listener?.onTransferFailed("设备不支持 Wi-Fi Direct")
            return
        }
        role = Role.SENDER
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }

        p2pGroupFormed = false
        // 超时保护: 25 秒未形成群组则取消连接并清理, 避免卡在"邀请中"
        connectTimeoutJob?.cancel()
        connectTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(25000)
            if (!p2pGroupFormed) {
                try { mgr.cancelConnect(ch, null) } catch (_: Exception) {}
                try { mgr.removeGroup(ch, null) } catch (_: Exception) {}
                role = Role.NONE
                listener?.onTransferFailed("连接超时: 对方未响应邀请。\n请确认对方 App 已在『近距离连接』模式并开启 Wi-Fi;\n或改用『远距离连接』扫码/输入 IP。")
            }
        }

        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connecting to ${device.deviceName}")
                listener?.onTransferFailed("正在邀请对方… 请在对方手机上确认连接邀请 (如有系统提示)")
            }

            override fun onFailure(reason: Int) {
                connectTimeoutJob?.cancel()
                Log.e(TAG, "Connection failed: $reason")
                listener?.onTransferFailed("连接失败: ${reasonToText(reason)}")
            }
        })
    }

    fun disconnect() {
        val mgr = manager ?: return
        channel?.let { mgr.removeGroup(it, null) }
    }

    /** 创建 P2P 群组 (接收方使用, 使本机成为 GO 从而被发送方寻址) */
    fun createGroup() {
        val ch = channel ?: return
        val mgr = manager ?: return
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group created, waiting for peers...")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "createGroup failed: $reason")
                listener?.onTransferFailed("创建接收群组失败: ${reasonToText(reason)}")
            }
        })
    }

    /**
     * 心跳检测: 轻量连接对方端口, 检查是否在线
     * 成功返回 true, 失败 (超时/拒绝) 返回 false
     */
    fun ping(host: String, port: Int = TRANSFER_PORT): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 本机机型名 (握手用) */
    private val deviceModelName: String by lazy {
        try {
            val manufacturer = Build.MANUFACTURER.ifBlank { "Android" }
            val model = Build.MODEL.ifBlank { "" }
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        } catch (e: Exception) {
            "Android"
        }
    }

    /**
     * 握手: 连接对方端口, 发送本机机型, 读取对方机型
     * 成功返回对方机型名; 失败返回 null
     */
    fun handshake(host: String, port: Int = TRANSFER_PORT): String? {
        return handshakeEx(host, port).first
    }

    /**
     * 握手并返回详细错误信息
     * @return Pair(对方机型, 错误描述) — 机型为 null 表示失败
     */
    fun handshakeEx(host: String, port: Int = TRANSFER_PORT): Pair<String?, String?> {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(host, port), 15000)
            socket.soTimeout = 8000
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            // 发送本机机型
            out.write("PT-HI $deviceModelName\n".toByteArray())
            out.flush()
            // 读取对方回复
            val line = readLineBytes(inp)
            socket.close()
            if (line != null && line.startsWith("PT-HI")) {
                line.removePrefix("PT-HI").trim().ifBlank { null } to null
            } else {
                null to "对方响应异常 (不是 PhotoTrans 或版本过旧)"
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(TAG, "Handshake timeout: $host")
            null to "连接超时: 请确认对方已开启『远距离连接』且在同一网络"
        } catch (e: java.net.ConnectException) {
            Log.d(TAG, "Handshake refused: $host")
            null to "连接被拒绝: 对方可能未开启接收或地址错误"
        } catch (e: java.net.UnknownHostException) {
            Log.d(TAG, "Handshake unknown host: $host")
            null to "无法解析地址: $host (请检查 IP 是否正确)"
        } catch (e: java.net.NoRouteToHostException) {
            Log.d(TAG, "Handshake no route: $host")
            null to "网络不可达: 两台设备不在同一网络"
        } catch (e: Exception) {
            Log.d(TAG, "Handshake failed: $host -> ${e.message}")
            null to "连接失败: ${e.message}"
        }
    }

    /**
     * 发送文件到群主地址 (HTTP PUT, 兼容互传联盟)
     */
    fun sendFile(filePath: String, host: String, port: Int = TRANSFER_PORT) {
        if (host.isBlank()) {
            listener?.onTransferFailed("缺少目标地址, 请先连接设备")
            return
        }
        sendJob?.cancel() // 取消之前的发送
        sendJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val file = File(filePath)
            if (!file.exists()) {
                listener?.onTransferFailed("文件不存在: $filePath")
                return@launch
            }

            // 自动重试: 对方接收服务可能尚未就绪, 最多重试 3 次
            var lastError: Exception? = null
            var socket: Socket? = null
            try {
                for (attempt in 1..3) {
                    try {
                        socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 15000)
                        break // 连接成功, 跳出重试
                    } catch (e: java.net.ConnectException) {
                        lastError = e
                        Log.d(TAG, "sendFile 第 $attempt 次重试 ($host:$port)")
                        delay(1000)
                    } catch (e: java.net.SocketTimeoutException) {
                        lastError = e
                        Log.d(TAG, "sendFile 第 $attempt 次超时 ($host:$port)")
                        delay(1000)
                    }
                }

                if (socket == null || !socket.isConnected) {
                    val msg = if (lastError != null) {
                        "连接被拒绝: $host:$port (对方未开启接收服务)"
                    } else {
                        "连接超时: $host:$port (对方未开启接收服务)"
                    }
                    withContext(Dispatchers.Main) {
                        listener?.onTransferFailed(msg)
                    }
                    return@launch
                }

                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()
                val fileSize = file.length()

                // 发送 PT-HI 握手 (与 iOS/HarmonyOS 兼容：同一连接上先握手再发文件)
                outputStream.write("PT-HI $deviceModelName\n".toByteArray())
                outputStream.flush()

                // HTTP PUT 请求头 (兼容互传联盟)
                val header = buildHttpPutHeader(file.name, fileSize)
                outputStream.write(header.toByteArray())
                outputStream.flush()

                // 发送文件数据 (节流进度回调)
                val buffer = ByteArray(65536)
                var totalSent = 0L
                var lastPct = -1L
                FileInputStream(file).use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalSent += bytesRead
                        val pct = if (fileSize > 0) totalSent * 100 / fileSize else 100L
                        if (pct != lastPct) {
                            lastPct = pct
                            withContext(Dispatchers.Main) {
                                listener?.onTransferProgress(totalSent, fileSize)
                            }
                        }
                    }
                }
                outputStream.flush()

                // 读取响应 (逐字节读, 不关闭底层流)
                val response = readLineBytes(inputStream)
                Log.d(TAG, "Server response: $response")

                // 校验响应状态码是否为 2xx
                if (response != null && !response.startsWith("HTTP/1.1 2")) {
                    Log.w(TAG, "非成功响应: $response")
                    // 仍继续，不中断（兼容 202 等非标准响应）
                }

                socket.close()
                socket = null

                withContext(Dispatchers.Main) {
                    listener?.onTransferComplete(file.name)
                }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Send timeout", e)
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener?.onTransferFailed("连接超时: $host:$port (请确认对方已点击『接收』)")
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Send connection refused", e)
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener?.onTransferFailed("连接被拒绝: $host:$port (对方可能未启动接收服务)")
                }
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "Send unknown host", e)
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener?.onTransferFailed("无法解析主机: $host (请检查 IP 地址)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                try { socket?.close() } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    listener?.onTransferFailed("发送失败: ${e.message}")
                }
            }
        }
    }

    // ─── 接收文件 ───────────────────────────────────

    fun startServer(saveDir: String) {
        // 关闭旧服务 (关闭 ServerSocket 释放端口)
        stopServer()
        role = Role.RECEIVER
        // 启动 TCP 接收服务器 (绑定所有网络接口, 同时支持 IPv4/IPv6)
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val sSocket = ServerSocket()
                sSocket.setReuseAddress(true)
                sSocket.bind(java.net.InetSocketAddress(TRANSFER_PORT))
                serverSocket = sSocket
                Log.d(TAG, "Server started on port $TRANSFER_PORT")

                while (isActive) {
                    val socket = sSocket.accept()
                    Log.d(TAG, "Client connected: ${socket.inetAddress}")

                    launch {
                        handleClient(socket, saveDir)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error", e)
                    listener?.onTransferFailed("接收服务异常: ${e.message}")
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    fun stopServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        sendJob?.cancel()
        sendJob = null
    }

    private suspend fun handleClient(socket: Socket, saveDir: String) {
        try {
            val inputStream = socket.getInputStream()
            val outputStream = socket.getOutputStream()

            // 解析 HTTP PUT 请求 (逐字节读行, 避免缓冲预读 body)
            var requestLine = readLineBytes(inputStream)

            // 支持 PT-HI 握手行: 先交换机型信息, 再继续读 PUT
            if (requestLine != null && requestLine.startsWith("PT-HI")) {
                val peerModel = requestLine.removePrefix("PT-HI").trim()
                val remoteIp = try { socket.inetAddress?.hostAddress ?: "" } catch (_: Exception) { "" }
                val cleanIp = if (remoteIp.contains("%")) remoteIp.substring(0, remoteIp.indexOf("%")) else remoteIp
                // 回复本机机型
                outputStream.write("PT-HI $deviceModelName\n".toByteArray())
                outputStream.flush()
                Log.d(TAG, "握手: 对方=$peerModel @$cleanIp")
                // 通知上层: 对端已识别 (机型 + 地址)
                listener?.onPeerIdentified(peerModel, cleanIp)
                // 继续读文件请求
                requestLine = readLineBytes(inputStream)
            }

            if (requestLine == null || !requestLine.startsWith("PUT")) {
                socket.close()
                return
            }

            // 解析文件名 (URL 解码)
            var fileName = requestLine.substringAfter("PUT ").substringBefore(" HTTP").trim()
            fileName = java.net.URLDecoder.decode(fileName, "UTF-8")
            // 去路径分隔符防目录穿越
            fileName = fileName.replace("\\", "_").replace("/", "_")

            // 解析头部 (Content-Length 等)
            var contentLength = 0L
            while (true) {
                val line = readLineBytes(inputStream) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
            }

            if (fileName.isEmpty() || contentLength <= 0) {
                outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                outputStream.flush()
                socket.close()
                return
            }

            val safeDir = File(saveDir)
            safeDir.mkdirs()
            // 接收时先写入缓存目录 (保证有写入权限), 完成后转存到相册/下载
            val tempDir = File(appContext.cacheDir, "PhotoTransRecv")
            tempDir.mkdirs()
            val tempFile = File(tempDir, fileName)

            // 接收文件数据 (节流进度回调)
            val buffer = ByteArray(65536)
            var totalReceived = 0L
            var lastPct = -1L
            FileOutputStream(tempFile).use { fos ->
                while (totalReceived < contentLength) {
                    val toRead = minOf(buffer.size.toLong(), contentLength - totalReceived).toInt()
                    val bytesRead = inputStream.read(buffer, 0, toRead)
                    if (bytesRead == -1) break
                    fos.write(buffer, 0, bytesRead)
                    totalReceived += bytesRead
                    val pct = totalReceived * 100 / contentLength
                    if (pct != lastPct) {
                        lastPct = pct
                        withContext(Dispatchers.Main) {
                            listener?.onTransferProgress(totalReceived, contentLength)
                        }
                    }
                }
            }

            // 发送响应
            outputStream.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
            outputStream.flush()

            socket.close()

            if (totalReceived < contentLength) {
                withContext(Dispatchers.Main) {
                    listener?.onTransferFailed("接收不完整: ${totalReceived}/${contentLength} 字节")
                }
                return
            }

            // 模型学习成果文件: 不转存, 直接交给上层导入
            if (fileName == "exported_model.json") {
                listener?.onModelFileReceived(tempFile.absolutePath)
                withContext(Dispatchers.Main) {
                    listener?.onTransferComplete("模型学习成果")
                }
                return
            }

            // 转存: 图片 → 系统相册 (失败则回退下载目录)
            var savedTo: String? = null
            if (isMediaFile(tempFile.name) || isMediaFileByMagic(tempFile.absolutePath)) {
                savedTo = addToGallery(tempFile.absolutePath)
                if (savedTo == null) {
                    Log.w(TAG, "相册转存失败, 回退到下载目录")
                    savedTo = addToDownloads(tempFile.absolutePath)
                }
            } else {
                savedTo = addToDownloads(tempFile.absolutePath)
            }

            if (savedTo == null) {
                // 全部转存失败: 尝试写入公共 Pictures/PhotoTrans 目录 (用户可见)
                Log.w(TAG, "媒体库转存全部失败, 尝试写入公共目录")
                savedTo = try {
                    val picsDir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES)
                    val ptDir = File(picsDir, "PhotoTrans")
                    ptDir.mkdirs()
                    val dest = File(ptDir, tempFile.name)
                    tempFile.copyTo(dest, overwrite = true)
                    "Pictures/PhotoTrans (相册目录)"
                } catch (e: Exception) {
                    Log.e(TAG, "公共目录写入也失败", e)
                    // 终极兜底: 保留在 app 缓存目录
                    val kept = File(safeDir, tempFile.name)
                    try { tempFile.copyTo(kept, overwrite = true) } catch (_: Exception) {}
                    "保存失败 (应用缓存: ${kept.absolutePath})"
                }
            }

            // 尝试在用户选择的目录写一份副本 (失败不影响)
            try {
                if (savedTo != null && tempFile.exists() && !savedTo!!.startsWith("保存")) {
                    val userCopy = File(safeDir, tempFile.name)
                    tempFile.copyTo(userCopy, overwrite = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "复制到用户目录失败: ${e.message}")
            }
            // 清理缓存副本 (保存失败时保留, 避免数据丢失)
            if (savedTo == null || !savedTo!!.startsWith("保存失败")) {
                try { tempFile.delete() } catch (_: Exception) {}
            }

            withContext(Dispatchers.Main) {
                listener?.onTransferComplete(savedTo ?: "完成")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handle client failed", e)
            try { socket.close() } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                listener?.onTransferFailed("接收失败: ${e.message}")
            }
        }
    }

    // ─── 内部方法 ───────────────────────────────────

    /** 判断是否是图片/视频文件 */
    private fun isMediaFile(name: String): Boolean {
        return name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) ||
            name.endsWith(".png", ignoreCase = true) || name.endsWith(".gif", ignoreCase = true) ||
            name.endsWith(".webp", ignoreCase = true) || name.endsWith(".heic", ignoreCase = true) ||
            name.endsWith(".heif", ignoreCase = true) || name.endsWith(".bmp", ignoreCase = true) ||
            name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".3gp", ignoreCase = true) ||
            name.endsWith(".mkv", ignoreCase = true) || name.endsWith(".mov", ignoreCase = true)
    }

    /** 检查文件头魔数是否为已知媒体格式（补充扩展名判断） */
    private fun isMediaFileByMagic(filePath: String): Boolean {
        return try {
            val stream = java.io.FileInputStream(filePath)
            stream.use { `in` ->
                val header = ByteArray(12)
                val read = `in`.read(header)
                if (read < 4) return false
                when {
                    // JPEG: FF D8 FF
                    header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> true
                    // PNG: 89 50 4E 47
                    header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte() -> true
                    // GIF: 47 49 46 38
                    header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && (header[3] == 0x38.toByte()) -> true
                    // WebP: 52 49 46 46 .... 57 45 42 50
                    read >= 12 && header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
                        header[8] == 0x57.toByte() && header[9] == 0x45.toByte() && header[10] == 0x42.toByte() && header[11] == 0x50.toByte() -> true
                    // HEIC/HEIF: ftyp (ftypmif1 / ftypheic)
                    read >= 12 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte() -> true
                    // BMP: 42 4D
                    header[0] == 0x42.toByte() && header[1] == 0x4D.toByte() -> true
                    // MP4: ftyp (ftypisom / ftypmp42)
                    read >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte() -> true
                    else -> false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 获取媒体 MIME 类型 */
    private fun getMediaMime(name: String): String = when {
        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".gif", ignoreCase = true) -> "image/gif"
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        name.endsWith(".heic", ignoreCase = true) || name.endsWith(".heif", ignoreCase = true) -> "image/heic"
        name.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".3gp", ignoreCase = true) -> "video/3gp"
        name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        else -> "application/octet-stream"
    }

    /** 将文件添加到系统相册 (图片/视频), 成功返回保存位置描述, 失败返回 null */
    private fun addToGallery(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        val mimeType = getMediaMime(file.name)
        if (!mimeType.startsWith("image") && !mimeType.startsWith("video")) {
            // 不是媒体: 交给下载目录
            return addToDownloads(filePath)
        }

        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(android.provider.MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            // 放入相册的 PhotoTrans 文件夹
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "DCIM/PhotoTrans")
            }
        }

        val collectionUri = if (mimeType.startsWith("video")) {
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        return try {
            val uri = appContext.contentResolver.insert(collectionUri, contentValues)
            if (uri == null) {
                Log.w(TAG, "相册 insert 返回 null: ${file.name}")
                null
            } else {
                appContext.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.w(TAG, "相册 openOutputStream 返回 null: ${file.name}")
                    appContext.contentResolver.delete(uri, null, null)
                    null
                }
                "相册 (DCIM/PhotoTrans)"
            }
        } catch (e: Exception) {
            Log.w(TAG, "添加文件到相册失败: ${e.message}", e)
            null
        }
    }

    /** 将文件添加到下载目录 (非媒体文件), 成功返回保存位置描述, 失败返回 null */
    private fun addToDownloads(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, getMediaMime(file.name))
                put(android.provider.MediaStore.Downloads.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/PhotoTrans")
            }
            return try {
                val uri = appContext.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri == null) {
                    Log.w(TAG, "下载 insert 返回 null: ${file.name}")
                    null
                } else {
                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        file.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    "下载 (Download/PhotoTrans)"
                }
            } catch (e: Exception) {
                Log.w(TAG, "添加文件到下载失败: ${e.message}", e)
                null
            }
        } else {
            // API < 29: 直接写入公共下载目录 (已授予 WRITE_EXTERNAL_STORAGE)
            return try {
                val dir = android.os.Environment
                    .getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val photoTransDir = File(dir, "PhotoTrans")
                photoTransDir.mkdirs()
                val dest = File(photoTransDir, file.name)
                file.copyTo(dest, overwrite = true)
                "下载 (Download/PhotoTrans)"
            } catch (e: Exception) {
                Log.w(TAG, "写入下载目录失败: ${e.message}", e)
                null
            }
        }
    }

    private fun buildHttpPutHeader(fileName: String, fileSize: Long): String {
        val encodedName = java.net.URLEncoder.encode(fileName, "UTF-8")
            .replace("+", "%20")
        return "PUT /$encodedName HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Length: $fileSize\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Connection: close\r\n\r\n"
    }

    /** 逐字节读取一行 (不含换行符), 不预读后续字节; 流结束返回 null */
    private fun readLineBytes(input: InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    private fun reasonToText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "设备不支持 Wi-Fi Direct"
        WifiP2pManager.BUSY -> "系统繁忙, 请稍后重试"
        WifiP2pManager.ERROR -> "发生未知错误"
        else -> "错误码 $reason"
    }
}