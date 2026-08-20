package com.phototrans.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phototrans.R
import com.phototrans.transport.WifiDirectTransport
import kotlinx.coroutines.*

/**
 * 传输服务 - 前台服务，确保传输不被系统杀死
 */
class TransferService : Service() {

    private var transport: WifiDirectTransport? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        transport = WifiDirectTransport.getInstance(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVER -> {
                val saveDir = intent.getStringExtra(EXTRA_SAVE_DIR)
                    ?: getExternalFilesDir("PhotoTrans")?.absolutePath
                    ?: filesDir.absolutePath
                try {
                    val notification = createNotification("等待接收文件...")
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: SecurityException) {
                    // 无 POST_NOTIFICATIONS 权限时跳过前台服务, 直接启动服务器
                    Log.w(TAG, "无法启动前台服务: ${e.message}")
                }
                // 启动接收服务 (TCP 直连不依赖 Wi-Fi Direct, 无需 register)
                transport?.startServer(saveDir)
                Log.d(TAG, "Receive server started, saveDir=$saveDir")
            }
            ACTION_STOP_SERVER -> {
                transport?.stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SEND_FILE -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, WifiDirectTransport.TRANSFER_PORT)
                try {
                    val notification = createNotification("正在发送文件...")
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e: SecurityException) {
                    Log.w(TAG, "无法启动前台服务: ${e.message}")
                }
                transport?.sendFile(filePath, host, port)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        transport?.stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "文件传输",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件传输进度"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhotoTrans")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "photo_trans_transfer"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "TransferService"

        const val ACTION_START_SERVER = "com.phototrans.START_SERVER"
        const val ACTION_STOP_SERVER = "com.phototrans.STOP_SERVER"
        const val ACTION_SEND_FILE = "com.phototrans.SEND_FILE"
        const val EXTRA_SAVE_DIR = "save_dir"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"

        fun startServer(context: Context, saveDir: String? = null) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_START_SERVER
                saveDir?.let { putExtra(EXTRA_SAVE_DIR, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopServer(context: Context) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_STOP_SERVER
            }
            context.startService(intent)
        }

        fun sendFile(context: Context, filePath: String, host: String, port: Int = WifiDirectTransport.TRANSFER_PORT) {
            val intent = Intent(context, TransferService::class.java).apply {
                action = ACTION_SEND_FILE
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}