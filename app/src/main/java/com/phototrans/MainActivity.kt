package com.phototrans

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.phototrans.databinding.ActivityMainBinding
import com.phototrans.model.LocalModelStore
import com.phototrans.service.LearningService
import com.phototrans.service.TransferService
import com.phototrans.transport.WifiDirectTransport
import com.phototrans.ui.BrandAdapter
import com.phototrans.ui.DeviceAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PhotoTrans 主界面
 *
 * 预览版功能:
 *   1. 发现附近设备 (Wi-Fi Direct)
 *   2. 发送/接收文件
 *   3. 后台格式学习
 *   4. 模型版本管理
 *
 * 传输流程:
 *   接收方: 点击"接收" → 创建 P2P 群组 + 启动接收服务
 *   发送方: 点击设备 → 加入群组 → 自动弹出文件选择 → 发送
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var transport: WifiDirectTransport
    private lateinit var modelStore: LocalModelStore
    private lateinit var deviceAdapter: DeviceAdapter
    private var isFirstLaunch = true

    // 连接模式
    private enum class ConnectMode { NEAR, FAR }
    private var connectMode: ConnectMode = ConnectMode.NEAR

    // 对端信息 (连接建立后)
    private var peerHost: String? = null   // 对端地址 (IPv4/IPv6 或 P2P GO 地址)
    private var peerName: String? = null   // 对端机型
    private var connected = false
    /** 心跳保活: 连接后定期检测对方是否在线 (每 30 秒) */
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    /** 与 Activity 生命周期绑定的协程作用域, onDestroy 自动取消 */
    private val activityScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob())
    /** 传输速度追踪: 上次更新时间戳和字节数 */
    private var transferLastBytes = 0L
    private var transferLastTime = 0L
    private var transferSpeed = 0.0  // MB/s 平滑值
    private var permissionsJustRequested = false
    private var filePickerInFlight = false

    // 二维码扫描启动器
    private val scanQrLauncher = registerForActivityResult(
        com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        if (result.contents != null) {
            val ipText = result.contents.trim()
            binding.remoteIpInput.setText(ipText)
            Snackbar.make(binding.root, "识别成功, 正在连接 $ipText …", Snackbar.LENGTH_SHORT).show()
            // 自动建立远程连接
            val parsed = parseHostPort(ipText)
            if (parsed != null) remoteHandshake(parsed.first, parsed.second)
            else Snackbar.make(binding.root, "二维码内容不是有效地址", Snackbar.LENGTH_LONG).show()
        }
    }

    // 相册选二维码图片启动器
    private val galleryQrLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val result = decodeQrFromUri(uri)
            if (result != null) {
                val ipText = result.trim()
                binding.remoteIpInput.setText(ipText)
                Snackbar.make(binding.root, "识别成功, 正在连接 $ipText …", Snackbar.LENGTH_SHORT).show()
                val parsed = parseHostPort(ipText)
                if (parsed != null) remoteHandshake(parsed.first, parsed.second)
                else Snackbar.make(binding.root, "二维码内容不是有效地址", Snackbar.LENGTH_LONG).show()
            } else {
                Snackbar.make(binding.root, "未能识别图片中的二维码", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /** 启动相机扫码 */
    private fun startCameraScan() {
        try {
            scanQrLauncher.launch(
                com.journeyapps.barcodescanner.ScanOptions().apply {
                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    setPrompt("扫描对方二维码获取 IP 地址")
                    setBeepEnabled(false)
                    setOrientationLocked(true)
                }
            )
        } catch (e: Exception) {
            Snackbar.make(binding.root, "启动扫描仪失败: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    /** 从图片 URI 解码二维码 */
    private fun decodeQrFromUri(uri: android.net.Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) return null

            // 使用 ZXing 解码二维码
            val source = com.google.zxing.RGBLuminanceSource(
                bitmap.width, bitmap.height,
                IntArray(bitmap.width * bitmap.height).also { pixels ->
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            )
            val binaryBitmap = com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
            val reader = com.google.zxing.MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "二维码解码失败", e)
            null
        }
    }

    // 保存目录选择器
    private var pendingSaveDir: String? = null
    private val saveDirPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 获取目录路径 (失败则用默认目录)
            val saveDir = getPathFromUri(uri) ?: defaultSaveDir()
            startReceiveWithDir(saveDir, "自定义目录")
        } else {
            // 用户取消了目录选择 → 仍然启动接收, 使用默认目录
            Snackbar.make(binding.root, "未选择目录, 已使用默认目录 (照片入相册, 其他入下载)", Snackbar.LENGTH_LONG).show()
            startReceiveWithDir(defaultSaveDir(), "默认目录")
        }
    }

    private fun defaultSaveDir(): String {
        return getExternalFilesDir("PhotoTrans")?.absolutePath ?: filesDir.absolutePath
    }

    /** 用一个具体目录启动接收服务 */
    private fun startReceiveWithDir(saveDir: String, sourceDesc: String) {
        pendingSaveDir = saveDir
        // 启动接收服务 (TCP 直连, 发送方通过该端口传文件)
        TransferService.startServer(this, saveDir)
        val ip = getLocalIpAddress()
        if (ip != null) {
            binding.statusChip.text = "接收中: $ip"
        } else {
            binding.statusChip.text = "接收模式已启动"
        }
        binding.statusChip.visibility = android.view.View.VISIBLE
    }

    /** 展示本机二维码 (远距离模式: 让对方扫码连接) */
    private fun showMyQrDialog() {
        val ip = getLocalIpAddress()
        if (ip != null) {
            showQrCodeDialog(ip)
        } else {
            Snackbar.make(binding.root, "无法获取本机 IP, 请检查网络", Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全局异常捕获 (崩溃日志写入缓存目录)
        CrashHandler.init(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transport = WifiDirectTransport.getInstance(this)
        modelStore = LocalModelStore(this)

        initViews()
        checkPermissions()
        checkFirstLaunch()
        // 检查是否存在之前的崩溃日志，并提供分享入口
        checkCrashLogs()
    }

    override fun onResume() {
        super.onResume()
        if (hasPermissions() && connectMode == ConnectMode.NEAR) {
            binding.searchingIndicator.visibility = android.view.View.VISIBLE
            transport.startDiscovery(showError = false)
            // 强制立即刷新一次设备列表
            transport.refreshPeersOnce()
        }
    }

    override fun onPause() {
        super.onPause()
        transport.stopDiscovery()
    }

    override fun onDestroy() {
        transport.clearListener()
        activityScope.cancel()
        heartbeatJob?.cancel()
        super.onDestroy()
    }

    private fun initViews() {
        // 工具栏
        setSupportActionBar(binding.toolbar)

        // 设备列表
        deviceAdapter = DeviceAdapter { device ->
            onDeviceClicked(device)
        }
        binding.deviceList.layoutManager = LinearLayoutManager(this)
        binding.deviceList.adapter = deviceAdapter

        // 近距离模式
        binding.btnNearMode.setOnClickListener {
            switchMode(ConnectMode.NEAR)
        }

        // 远距离模式
        binding.btnFarMode.setOnClickListener {
            switchMode(ConnectMode.FAR)
        }

        // 刷新按钮
        binding.btnRefresh.setOnClickListener {
            when (connectMode) {
                ConnectMode.NEAR -> {
                    if (!transport.isAvailable()) {
                        Snackbar.make(binding.root, "此设备不支持 Wi-Fi Direct，请切换到『远距离连接』", Snackbar.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    if (!hasPermissions()) {
                        requestPermissions()
                        return@setOnClickListener
                    }
                    binding.peerCount.text = "正在搜索…"
                    binding.searchingIndicator.visibility = android.view.View.VISIBLE
                    transport.stopDiscovery()
                    transport.startDiscovery()
                    Snackbar.make(binding.root, "正在搜索附近设备…", Snackbar.LENGTH_SHORT).show()
                }
                ConnectMode.FAR -> {
                    Snackbar.make(binding.root, "远距离模式: 请输入对方 IP 或点击 QR 扫码连接", Snackbar.LENGTH_LONG).show()
                }
            }
        }

        // 远程直连: 输入 IP 后建立连接 (握手验证)
        binding.btnRemoteConnect.setOnClickListener {
            val ipText = binding.remoteIpInput.text.toString().trim()
            if (ipText.isEmpty()) {
                Snackbar.make(binding.root, "请输入对方 IP 地址 (或点击 QR 扫码)", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val parsed = parseHostPort(ipText)
            if (parsed == null) {
                Snackbar.make(binding.root, "地址格式错误, 例: 192.168.1.100:47808", Snackbar.LENGTH_LONG).show()
                return@setOnClickListener
            }
            remoteHandshake(parsed.first, parsed.second)
        }

        // 扫描二维码按钮 - 提供相机/相册两个选项
        binding.btnScanQr.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("扫描二维码")
                .setItems(arrayOf("相机扫描", "从相册选择二维码图片")) { _, which ->
                    when (which) {
                        0 -> startCameraScan()
                        1 -> galleryQrLauncher.launch("image/*")
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 显示本机二维码 (远距离模式下让对方扫码)
        binding.btnShowMyQr.setOnClickListener {
            ensureReceiveStarted()
        }

        // 状态芯片可点击: 连接后点击弹出操作菜单 (发送照片/文件/断开)
        binding.statusChip.setOnClickListener {
            if (connected) {
                onPeerMenu(peerName ?: peerHost ?: "对方设备")
            } else {
                Snackbar.make(binding.root, "尚未连接设备", Snackbar.LENGTH_SHORT).show()
            }
        }

        // 设置传输监听器
        transport.setListener(object : WifiDirectTransport.TransferListener {
            override fun onDeviceFound(device: android.net.wifi.p2p.WifiP2pDevice) {
                runOnUiThread {
                    deviceAdapter.updateDevices(transport.getPeers())
                    updateEmptyState()
                }
            }

            override fun onPeersRefresh(count: Int) {
                runOnUiThread {
                    deviceAdapter.updateDevices(transport.getPeers())
                    updateEmptyState()
                    binding.peerCount.text = if (count > 0) "发现 $count 台设备" else "周围暂无设备"
                }
            }

            override fun onConnected(deviceName: String, host: String, isGroupOwner: Boolean) {
                runOnUiThread {
                    // 近距离 (Wi-Fi Direct) 群组形成: 双方都启动接收, 等待/获取对方地址
                    startReceiveWithDir(defaultSaveDir(), "连接后目录")
                    if (host.isNotBlank()) {
                        onPeerConnected(name = deviceName, host = host)
                    } else {
                        // 本机成为 GO, 对端地址暂未知, 等对方握手推送
                        connected = true
                        peerName = deviceName
                        binding.statusChip.text = "已连接: $deviceName (接收已开启)"
                        binding.statusChip.visibility = android.view.View.VISIBLE
                        Snackbar.make(binding.root, "已连接: $deviceName\n接收服务已自动开启, 等待对方同步", Snackbar.LENGTH_LONG).show()
                    }
                }
            }

            override fun onPeerIdentified(deviceName: String, host: String) {
                runOnUiThread {
                    // 对方通过握手被识别 (含对端地址) → 建立完整连接
                    if (peerHost.isNullOrBlank()) {
                        onPeerConnected(name = deviceName, host = host)
                    }
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    stopHeartbeat()
                    connected = false
                    peerHost = null
                    peerName = null
                    binding.statusChip.visibility = android.view.View.GONE
                    Snackbar.make(binding.root, "已断开连接", Snackbar.LENGTH_SHORT).show()
                }
            }

            override fun onTransferProgress(bytesTransferred: Long, totalBytes: Long) {
                runOnUiThread {
                    val pct = if (totalBytes > 0) bytesTransferred * 100 / totalBytes else 0
                    val now = System.currentTimeMillis()

                    // 每 1 秒更新一次速度 (避免抖动)
                    if (now - transferLastTime > 1000) {
                        val elapsed = (now - transferLastTime) / 1000.0
                        if (elapsed > 0 && transferLastBytes > 0) {
                            val deltaBytes = bytesTransferred - transferLastBytes
                            val instantSpeed = deltaBytes / elapsed / (1024.0 * 1024.0) // MB/s
                            // 指数平滑
                            transferSpeed = if (transferSpeed > 0) {
                                transferSpeed * 0.7 + instantSpeed * 0.3
                            } else {
                                instantSpeed
                            }
                        }
                        transferLastBytes = bytesTransferred
                        transferLastTime = now
                    }

                    // 大文件 (>500MB) 或已有速度时显示速度
                    val showSpeed = totalBytes > 500L * 1024 * 1024 || transferSpeed > 0
                    val text = if (showSpeed && transferSpeed > 0) {
                        val eta = if (transferSpeed > 0.01) {
                            val remaining = (totalBytes - bytesTransferred) / (transferSpeed * 1024.0 * 1024.0)
                            if (remaining < 60) "剩余 ${remaining.toInt()}秒"
                            else if (remaining < 3600) "剩余 ${(remaining / 60).toInt()}分${(remaining % 60).toInt()}秒"
                            else "剩余 ${(remaining / 3600).toInt()}时"
                        } else ""
                        "传输 $pct% · ${"%.1f".format(transferSpeed)}MB/s$eta"
                    } else {
                        "传输中: $pct%"
                    }
                    binding.statusChip.text = text
                    binding.statusChip.visibility = android.view.View.VISIBLE
                }
            }

            override fun onTransferComplete(fileName: String) {
                runOnUiThread {
                    transferSpeed = 0.0
                    transferLastBytes = 0
                    transferLastTime = 0
                    binding.statusChip.text = "传输完成"
                    Snackbar.make(binding.root, "传输完成: $fileName", Snackbar.LENGTH_LONG).show()
                }
            }

            override fun onModelFileReceived(filePath: String) {
                // 收到对方模型学习成果 → 导入合并
                activityScope.launch(Dispatchers.IO) {
                    try {
                        val newVersion = modelStore.importModel(filePath)
                        File(filePath).delete()
                        withContext(Dispatchers.Main) {
                            Snackbar.make(binding.root, "已同步对方模型学习成果 (合并为 v$newVersion)", Snackbar.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "导入对方模型失败", e)
                    }
                }
            }

            override fun onTransferFailed(error: String) {
                runOnUiThread {
                    binding.statusChip.text = "传输失败"
                    binding.statusChip.visibility = android.view.View.VISIBLE
                    Snackbar.make(binding.root, "传输失败: $error", Snackbar.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun updateEmptyState() {
        val peers = transport.getPeers()
        binding.emptyState.visibility =
            if (peers.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ─── 设备点击 → 操作菜单 ──────────────────────

    private fun onDeviceClicked(device: android.net.wifi.p2p.WifiP2pDevice) {
        if (!hasPermissions()) {
            requestPermissions()
            return
        }
        if (connected) {
            // 已连接 → 操作菜单 (发送照片/发送文件/断开连接)
            onPeerMenu(device.deviceName)
            return
        }
        // 未连接 → 确认后连接
        AlertDialog.Builder(this)
            .setTitle("连接设备")
            .setMessage("连接 ${device.deviceName} ?\n连接成功后可互相发送照片/文件, 并自动同步模型")
            .setPositiveButton("连接") { _, _ ->
                Snackbar.make(binding.root, "正在连接 ${device.deviceName}…", Snackbar.LENGTH_SHORT).show()
                transport.connect(device)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 已连接后的操作菜单 */
    private fun onPeerMenu(deviceDisplayName: String) {
        val options = arrayOf("发送照片", "发送文件", "断开连接")
        AlertDialog.Builder(this)
            .setTitle(deviceDisplayName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openPhotoPicker()
                    1 -> selectFilesToSend()
                    2 -> disconnectPeer()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开相册选择照片 (自动识别并发送配套视频: 动态照片/实况照片) */
    private fun openPhotoPicker() {
        val host = peerHost
        if (host.isNullOrBlank()) {
            Snackbar.make(binding.root, "尚未连接设备, 请先建立连接", Snackbar.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerInFlight = true
        startActivityForResult(intent, FILE_SELECT_REQUEST_CODE)
    }

    /** 查找照片的配套视频 (动态照片: 同文件名但扩展名 .mov/.mp4) */
    private fun findCompanionVideo(photoUri: Uri): Uri? {
        return try {
            // 获取文件名
            val cursor = contentResolver.query(photoUri, null, null, null, null)
            val photoName = cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) c.getString(idx) else null
                } else null
            } ?: return null
            // 去掉扩展名, 尝试 .mov 和 .mp4
            val baseName = photoName.substringBeforeLast(".")
            if (baseName == photoName) return null
            val videoExtensions = arrayOf("mov", "mp4", "3gp")
            for (ext in videoExtensions) {
                val videoName = "$baseName.$ext"
                // 查询 MediaStore 中匹配的文件
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.provider.MediaStore.Video.Media.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL)
                } else {
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
                val where = "${android.provider.MediaStore.Video.Media.DISPLAY_NAME} = ?"
                val videoCursor = contentResolver.query(collection, arrayOf(
                    android.provider.MediaStore.Video.Media._ID), where, arrayOf(videoName), null)
                val id = videoCursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.MediaStore.Video.Media._ID)
                        if (idx >= 0) c.getLong(idx) else -1L
                    } else -1L
                } ?: -1L
                videoCursor?.close()
                if (id > 0) {
                    return android.net.Uri.withAppendedPath(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "查找配套视频失败", e)
            null
        }
    }

    /** 断开当前连接 */
    private fun disconnectPeer() {
        stopHeartbeat()
        peerHost = null
        peerName = null
        connected = false
        binding.statusChip.visibility = android.view.View.GONE
        // 近距离: 离开群组; 远距离: 停止接收
        if (connectMode == ConnectMode.NEAR) {
            transport.disconnect()
        }
        Snackbar.make(binding.root, "已断开连接", Snackbar.LENGTH_SHORT).show()
    }

    /** 启动心跳保活: 每 30 秒检测对方是否在线 */
    private fun startHeartbeat(host: String) {
        stopHeartbeat()
        heartbeatJob = activityScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30000)
                val alive = transport.ping(host)
                if (!alive) {
                    withContext(Dispatchers.Main) {
                        if (connected) {
                            Log.w(TAG, "心跳检测失败: 对方可能已离线")
                            Snackbar.make(binding.root, "连接已断开 (对方离线)", Snackbar.LENGTH_LONG).show()
                            disconnectPeer()
                        }
                    }
                    break
                }
            }
        }
    }

    /** 停止心跳保活 */
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 选择任意文件发送 (已连接状态) */
    private fun selectFilesToSend() {
        val host = peerHost
        if (host.isNullOrBlank()) {
            Snackbar.make(binding.root, "尚未连接设备, 请先建立连接", Snackbar.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        filePickerInFlight = true
        startActivityForResult(intent, FILE_SELECT_REQUEST_CODE)
    }

    @Deprecated("Use registerForActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_SELECT_REQUEST_CODE) return
        filePickerInFlight = false

        // 如果用户取消选择, 直接返回
        if (resultCode != RESULT_OK) return

        val host = peerHost
        if (host.isNullOrBlank()) {
            Snackbar.make(binding.root, "没有目标地址, 请先建立连接", Snackbar.LENGTH_SHORT).show()
            return
        }

        // 收集所有选中的 URI (单文件 + 多文件)
        val uris = mutableListOf<Uri>()
        data?.data?.let { uris.add(it) }
        if (data?.clipData != null) {
            for (i in 0 until data.clipData!!.itemCount) {
                data.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
            }
        }

        if (uris.isEmpty()) return

        // 如果是照片选择, 自动查找配套视频 (动态照片/实况照片)
        val isPhotoSelection = uris.any { uri ->
            val type = contentResolver.getType(uri) ?: ""
            type.startsWith("image/")
        }
        if (isPhotoSelection) {
            val companionUris = uris.mapNotNull { findCompanionVideo(it) }
            // 过滤掉已存在的 (避免重复)
            val existingSet = uris.map { it.toString() }.toSet()
            for (comp in companionUris) {
                if (comp.toString() !in existingSet) {
                    uris.add(comp)
                }
            }
            if (companionUris.isNotEmpty()) {
                Log.d(TAG, "发现 ${companionUris.size} 个配套视频, 自动加入发送队列")
            }
        }

        val target = host
        Snackbar.make(binding.root, "已选择 ${uris.size} 个文件, 发送到 $target …", Snackbar.LENGTH_SHORT).show()

        // 在 IO 线程复制 URI 到本地缓存 (避免大文件复制阻塞主线程导致 ANR)
        activityScope.launch(Dispatchers.IO) {
            var sentCount = 0
            for (uri in uris) {
                try {
                    val cacheFile = copyUriToCache(uri) ?: continue
                    // 统一通过前台服务走 TCP 直连发送
                    TransferService.sendFile(this@MainActivity, cacheFile.absolutePath, target)
                    sentCount++
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Snackbar.make(binding.root, "处理文件失败: ${e.message}", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }
            val finalCount = sentCount
            withContext(Dispatchers.Main) {
                if (finalCount == 0) {
                    Snackbar.make(binding.root, "没有可发送的文件", Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(binding.root, "已提交 $finalCount 个文件到传输队列", Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 复制 URI 内容到缓存目录, 返回本地文件 */
    private fun copyUriToCache(uri: Uri): File? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val name = getFileName(uri)
        val cleanName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val cacheFile = File(cacheDir, "to_send_${System.currentTimeMillis()}_$cleanName")
        inputStream.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    private fun getFileName(uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && !c.isNull(idx)) name = c.getString(idx)
                }
            }
        } catch (_: Exception) {
        }
        return name
    }

    // ─── 接收文件 ─────────────────────────────────

    // ─── 连接模式核心逻辑 ───────────────────────────

    /** 切换连接模式 (近距离/远距离) */
    private fun switchMode(mode: ConnectMode) {
        connectMode = mode
        // 二维码扫码 + IP 输入在两种模式下都可用
        binding.farConnectArea.visibility = android.view.View.VISIBLE
        if (mode == ConnectMode.NEAR) {
            // 近距离: 搜索附近设备 (静默开启接收, 保证随时可被对方发送)
            startReceiveWithDir(defaultSaveDir(), "模式启动")
            binding.modeHint.text = "近距离模式: 搜索附近设备, 也可扫码/输入 IP 直连"
            if (!hasPermissions()) {
                requestPermissions()
                return
            }
            if (!transport.isAvailable()) {
                binding.searchingIndicator.visibility = android.view.View.GONE
                Snackbar.make(binding.root, "此设备不支持 Wi-Fi Direct, 请使用下方扫码/输入 IP 连接", Snackbar.LENGTH_LONG).show()
                return
            }
            binding.searchingIndicator.visibility = android.view.View.VISIBLE
            transport.stopDiscovery()
            transport.startDiscovery(showError = false)
            Snackbar.make(binding.root, "近距离模式: 正在搜索附近设备… (也可扫码直连)", Snackbar.LENGTH_SHORT).show()
        } else {
            // 远距离: 停止搜索, 显示连接输入区
            transport.stopDiscovery()
            binding.searchingIndicator.visibility = android.view.View.GONE
            binding.modeHint.text = "远距离模式: 输入对方 IP 或扫码连接"
            binding.peerCount.text = "远距离模式: 输入对方 IP 或扫码建立连接"
            // 自动开启本机接收并展示二维码
            ensureReceiveStarted()
        }
    }

    private fun parseHostPort(ipText: String): Pair<String, Int>? {
        return try {
            if (ipText.contains("]:") && ipText.startsWith("[")) {
                val endBracket = ipText.indexOf("]:")
                val host = ipText.substring(1, endBracket)
                val port = ipText.substring(endBracket + 2).toIntOrNull()
                    ?: WifiDirectTransport.TRANSFER_PORT
                host to port
            } else if (ipText.contains(":")) {
                val lastColon = ipText.lastIndexOf(":")
                val possiblePort = ipText.substring(lastColon + 1).toIntOrNull()
                if (possiblePort != null && possiblePort in 1..65535 && lastColon == ipText.indexOf(":")) {
                    ipText.substring(0, lastColon) to possiblePort
                } else {
                    ipText to WifiDirectTransport.TRANSFER_PORT
                }
            } else {
                ipText to WifiDirectTransport.TRANSFER_PORT
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 远程握手: 连接对方端口, 交换机型信息, 验证对方在线 */
    private fun remoteHandshake(host: String, port: Int) {
        Snackbar.make(binding.root, "正在连接 $host …", Snackbar.LENGTH_SHORT).show()
        activityScope.launch(Dispatchers.IO) {
            val (peerName, error) = transport.handshakeEx(host, port)
            withContext(Dispatchers.Main) {
                if (peerName != null) {
                    onPeerConnected(name = peerName, host = host)
                } else {
                    Snackbar.make(binding.root,
                        "无法连接 $host\n${error ?: "未知错误"}", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 连接建立后的统一处理:
     * - 记录对端信息
     * - 启动本机接收服务 (各自选择保存目录)
     * - 自动同步模型学习成果
     */
    private fun onPeerConnected(name: String, host: String) {
        connected = true
        peerHost = host
        peerName = name
        val display = if (name.isNotBlank()) name else host
        binding.statusChip.text = "已连接: $display"
        binding.statusChip.visibility = android.view.View.VISIBLE
        Snackbar.make(binding.root, "连接成功: $display\n接收已自动开启 (照片→相册), 点击本机顶部状态可操作", Snackbar.LENGTH_LONG).show()

        // 双方连接后自动开启接收服务 (照片→相册, 其他→下载)
        startReceiveWithDir(defaultSaveDir(), "连接后目录")

        // 弹窗让用户选择保存目录 (可选)
        AlertDialog.Builder(this)
            .setTitle("保存目录")
            .setMessage("已启用接收:\n照片 → 相册 (DCIM/PhotoTrans)\n其他文件 → 下载 (Download/PhotoTrans)\n\n也可选择其他目录存放非图片文件。")
            .setPositiveButton("使用默认") { _, _ -> }
            .setNeutralButton("选择其他目录") { _, _ -> saveDirPickerLauncher.launch(null) }
            .setNegativeButton("关闭", null)
            .show()

        // 自动同步模型学习成果给对方
        sendModelToPeer(host)

        // 启动心跳保活 (每 30 秒检测对方是否在线)
        startHeartbeat(host)

        // 近距离模式下主动握手一次: 让对方识别本机并记录本机地址
        if (connectMode == ConnectMode.NEAR) {
            activityScope.launch(Dispatchers.IO) {
                transport.handshake(host)
            }
        }
    }

    /** 确保本机接收服务已启动, 并展示本机二维码 + IP */
    private fun ensureReceiveStarted() {
        startReceiveWithDir(defaultSaveDir(), "默认目录")
        showMyQrDialog()
    }

    /** 导出本地模型学习成果并发送给对端 */
    private fun sendModelToPeer(host: String) {
        activityScope.launch(Dispatchers.IO) {
            val modelPath = modelStore.exportModel()
            withContext(Dispatchers.Main) {
                if (modelPath != null) {
                    Snackbar.make(binding.root, "正在发送模型学习成果给对方…", Snackbar.LENGTH_SHORT).show()
                    TransferService.sendFile(this@MainActivity, modelPath, host)
                } else {
                    Log.d(TAG, "暂无模型学习成果可发送 (学习后会生成)")
                }
            }
        }
    }

    /** 显示二维码对话框, 包含 IP 地址和复制按钮 */
    private fun showQrCodeDialog(ip: String) {
        try {
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = writer.encode(ip, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }

            val imageView = android.widget.ImageView(this)
            imageView.setImageBitmap(bitmap)
            imageView.setPadding(32, 32, 32, 32)

            AlertDialog.Builder(this)
                .setTitle("对方扫码连接")
                .setMessage(ip)
                .setView(imageView)
                .setPositiveButton("复制 IP") { _, _ ->
                    val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IP", ip))
                    Snackbar.make(binding.root, "IP 已复制到剪贴板", Snackbar.LENGTH_SHORT).show()
                }
                .setNeutralButton("关闭", null)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "生成二维码失败", e)
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return "${addr.hostAddress}:${WifiDirectTransport.TRANSFER_PORT}"
                    }
                }
            }
            // 如果没有 IPv4 地址, 取第一个非回环 IPv6 地址
            val interfaces2 = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val iface = interfaces2.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet6Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        // 如果包含 % 接口名, 去掉
                        val clean = if (ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                        return "[$clean]:${WifiDirectTransport.TRANSFER_PORT}"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "获取 IP 失败", e)
        }
        return null
    }

    /** 从 Document URI 转换为实际路径 */
    private fun getPathFromUri(uri: android.net.Uri): String? {
        return try {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
            // tree URI 形如 content://.../tree/primary:Download/PhotoTrans
            val path = uri.path ?: return null
            // 去掉 /tree/ 或 /document/ 前缀, 把 primary: 换成 /storage/emulated/0/
            val clean = path
                .removePrefix("/tree/")
                .removePrefix("/document/")
                .replace("primary:", "/storage/emulated/0/")
            Log.d(TAG, "目录路径解析: $path → $clean")
            if (clean.startsWith("/storage/")) clean else null
        } catch (e: Exception) {
            Log.w(TAG, "目录解析失败", e)
            null
        }
    }

    // ─── 权限管理 ───────────────────────────────────

    // 关键权限: Wi-Fi Direct 搜索/传输必需
    private val criticalPermissions = mutableListOf<String>()
    // 可选权限: 相册/通知等, 不影响传输功能
    private val optionalPermissions = mutableListOf<String>()

    private fun checkPermissions() {
        // Wi-Fi Direct 需要 NEARBY_WIFI_DEVICES (13+) 和/或 ACCESS_FINE_LOCATION
        // 部分厂商 (OPPO/小米等) 在 Android 13+ 仍依赖位置权限, 两个都请求最稳妥
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addCriticalPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        addCriticalPermission(Manifest.permission.ACCESS_FINE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addOptionalPermission(Manifest.permission.READ_MEDIA_IMAGES)
            addOptionalPermission(Manifest.permission.READ_MEDIA_VIDEO)
            addOptionalPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            addOptionalPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            addOptionalPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun addCriticalPermission(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED) {
            criticalPermissions.add(permission)
        }
    }

    private fun addOptionalPermission(permission: String) {
        if (ContextCompat.checkSelfPermission(this, permission)
            != PackageManager.PERMISSION_GRANTED) {
            optionalPermissions.add(permission)
        }
    }

    /** 关键权限是否全部授予 (传输功能依赖这些) */
    private fun hasPermissions(): Boolean {
        for (p in criticalPermissions) {
            if (ContextCompat.checkSelfPermission(this, p)
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }

    private fun requestPermissions() {
        val all = (criticalPermissions + optionalPermissions).toTypedArray()
        if (all.isNotEmpty()) {
            permissionsJustRequested = true
            ActivityCompat.requestPermissions(this, all, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        permissionsJustRequested = false

        // 找出被拒绝的关键权限 (关键权限缺失会阻断 Wi-Fi Direct)
        val deniedCritical = mutableListOf<String>()
        permissions.forEachIndexed { i, perm ->
            if (i < grantResults.size &&
                grantResults[i] != PackageManager.PERMISSION_GRANTED &&
                criticalPermissions.contains(perm)) {
                deniedCritical.add(perm)
            }
        }

        if (deniedCritical.isEmpty()) {
            // 关键权限已齐 (可选权限缺失不影响)
            transport.startDiscovery()
            binding.searchingIndicator.visibility = android.view.View.VISIBLE
            Snackbar.make(binding.root, "权限已授予, 开始搜索设备", Snackbar.LENGTH_SHORT).show()
        } else {
            // 显示被拒绝的关键权限名, 并提供去设置入口
            val names = deniedCritical.joinToString(", ") {
                if (it == Manifest.permission.ACCESS_FINE_LOCATION) "位置信息" else "附近设备"
            }
            Snackbar.make(binding.root, "缺少权限: $names (Wi‑Fi Direct 需要)", Snackbar.LENGTH_INDEFINITE)
                .setAction("去设置") { openAppSettings() }
                .show()
        }
    }

    /** 打开系统应用详情设置页 */
    private fun openAppSettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            Snackbar.make(binding.root, "无法打开设置", Snackbar.LENGTH_SHORT).show()
        }
    }

    // ─── 首次启动 ───────────────────────────────────

    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isFirstLaunch = prefs.getBoolean(PREF_FIRST_LAUNCH, true)

        if (isFirstLaunch) {
            showDisclaimerDialog()
        } else {
            // 非首次: 无权限时自动请求
            if (!hasPermissions() && !permissionsJustRequested) {
                requestPermissions()
            }
        }
    }

    private fun checkCrashLogs() {
        val logs = CrashHandler.getCrashLogFiles(this)
        if (logs.isEmpty()) return
        // 只显示最近一次崩溃日志的分享对话框
        val latest = logs.first()
        AlertDialog.Builder(this)
            .setTitle("检测到崩溃日志")
            .setMessage("应用上次运行时发生了崩溃。是否分享日志以帮助修复？")
            .setPositiveButton("分享日志") { _, _ ->
                CrashHandler.shareCrashLog(this, latest)
            }
            .setNegativeButton("忽略") { _, _ ->
                // 删除日志文件，避免重复提示
                latest.delete()
            }
            .show()
    }

    private fun showDisclaimerDialog() {
        // 改用 AlertDialog.Builder 避免 MaterialAlertDialog 样式崩溃
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_text)
            .setCancelable(false)
            .setPositiveButton(R.string.agree) { _, _ ->
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putBoolean(PREF_FIRST_LAUNCH, false).apply()
                isFirstLaunch = false
                LearningService.startLearning(this)
                Snackbar.make(binding.root, "欢迎使用 PhotoTrans!", Snackbar.LENGTH_LONG).show()
                // 同意后自动请求权限并开始搜索
                if (!hasPermissions()) {
                    requestPermissions()
                } else {
                    transport.startDiscovery()
                    binding.searchingIndicator.visibility = android.view.View.VISIBLE
                }
            }
            .setNegativeButton(R.string.disagree) { _, _ ->
                finish()
            }
            .show()
    }

    // ─── 菜单 ───────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_model -> {
                com.phototrans.ui.ModelManagementActivity.start(this)
                true
            }
            R.id.menu_settings -> {
                com.phototrans.ui.SettingsActivity.start(this)
                true
            }
            R.id.menu_about -> {
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showModelManagement() {
        val versions = modelStore.listVersions()
        val currentVersion = modelStore.getCurrentVersion() ?: 0
        val stats = modelStore.getFingerprintStats()

        // 前 4 行是统计信息, 不可点击切换; 之后每行对应一个版本
        val infoLineCount = 4
        val items = mutableListOf(
            "当前版本: v$currentVersion",
            "已学习样本: ${stats.values.sum()} 张",
            "支持品牌: ${if (stats.isEmpty()) "暂无" else stats.keys.joinToString(", ")}",
            "─────────────────",
        )
        if (versions.isEmpty()) {
            items.add("暂无模型版本，请点击下方『立即学习』")
        } else {
            for (v in versions) {
                val cur = if (v.isCurrent) "  ★ 当前" else ""
                items.add("v${v.version} · ${v.createdAt} · ${v.brands.joinToString(", ")} · ${v.totalSamples}样本$cur")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("模型版本管理")
            .setMessage("点击下方版本可切换 / 回滚。共 ${versions.size} 个版本。")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < infoLineCount) return@setItems  // 统计行不响应
                if (versions.isEmpty()) return@setItems
                val v = versions[which - infoLineCount]
                if (v.isCurrent) {
                    Snackbar.make(binding.root, "v${v.version} 已是当前版本", Snackbar.LENGTH_SHORT).show()
                    return@setItems
                }
                // 确认切换到该版本
                AlertDialog.Builder(this)
                    .setTitle("切换模型版本")
                    .setMessage("确定切换到 v${v.version}?\n\n该版本: ${v.brands.joinToString(", ")}\n样本数: ${v.totalSamples}\n时间: ${v.createdAt}")
                    .setPositiveButton("切换") { _, _ ->
                        if (modelStore.rollback(v.version)) {
                            Snackbar.make(binding.root, "已切换到 v${v.version}", Snackbar.LENGTH_LONG).show()
                        } else {
                            Snackbar.make(binding.root, "切换失败", Snackbar.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("删除") { _, _ ->
                        if (modelStore.deleteVersion(v.version)) {
                            Snackbar.make(binding.root, "已删除 v${v.version}", Snackbar.LENGTH_SHORT).show()
                        } else {
                            Snackbar.make(binding.root, "删除失败 (当前版本不可删除)", Snackbar.LENGTH_LONG).show()
                        }
                    }
                    .setNeutralButton("取消", null)
                    .show()
            }
            .setPositiveButton("导出当前模型") { _, _ ->
                val path = modelStore.exportModel()
                if (path != null) {
                    Snackbar.make(binding.root, "模型已导出: $path", Snackbar.LENGTH_LONG).show()
                } else {
                    Snackbar.make(binding.root, "导出失败 (无可用模型)", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("立即学习") { _, _ ->
                LearningService.learnNow(this)
                Snackbar.make(binding.root, "开始学习照片格式...", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("关于 PhotoTrans")
            .setMessage("""
                PhotoTrans v${BuildConfig.VERSION_NAME}
                
                跨品牌文件无缝传输工具
                
                所有照片处理在本地完成
                格式模型自动学习，无需服务器
                """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setItems(arrayOf(
                "自动学习: ${if (modelStore.getCurrentVersion() != null) "已开启" else "未开启"}",
                "版本: ${BuildConfig.VERSION_NAME}",
                "格式模型: ${modelStore.listVersions().size} 个版本"
            ), null)
            .setPositiveButton("立即学习", null)
            .setNeutralButton("关于", null)
            .setNegativeButton("关闭", null)
            .show()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val FILE_SELECT_REQUEST_CODE = 200
        private const val PREFS_NAME = "phototrans_prefs"
        private const val PREF_FIRST_LAUNCH = "first_launch"
    }
}