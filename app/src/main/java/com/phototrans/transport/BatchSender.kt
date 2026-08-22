package com.phototrans.transport

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 批量文件发送器
 *
 * 逐个发送文件，每个文件独立连接，不使用 WifiDirectTransport.sendJob
 * 从而避免单文件发送时 sendJob 被取消的问题。
 */
object BatchSender {

    private const val TAG = "BatchSender"

    /**
     * 批量发送文件，逐个发送，每个文件独立 TCP 连接
     *
     * @param filePaths 文件路径列表
     * @param host 目标主机地址
     * @param port 目标端口
     * @param deviceModelName 本机机型名（握手用）
     * @param onProgress 进度回调 (bytesTransferred, totalBytes)
     * @param onFileComplete 单个文件完成回调 (fileName)
     * @param onError 错误回调 (errorMessage)
     */
    fun sendFiles(
        filePaths: List<String>,
        host: String,
        port: Int = 47808,
        deviceModelName: String = getDeviceModelName(),
        onProgress: ((Long, Long) -> Unit)? = null,
        onFileComplete: ((String) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        if (host.isBlank()) {
            onError?.invoke("缺少目标地址")
            return
        }

        for ((index, filePath) in filePaths.withIndex()) {
            val file = File(filePath)
            if (!file.exists()) {
                onError?.invoke("文件不存在: $filePath")
                continue
            }

            Log.d(TAG, "Sending file ${index + 1}/${filePaths.size}: ${file.name}")

            var socket: Socket? = null
            try {
                // 连接（带重试，最多3次）
                for (attempt in 1..3) {
                    try {
                        socket = Socket()
                        socket.connect(InetSocketAddress(host, port), 15000)
                        break
                    } catch (e: java.net.ConnectException) {
                        Log.d(TAG, "Connect attempt $attempt failed, retrying...")
                        if (attempt < 3) Thread.sleep(1000)
                        else throw e
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.d(TAG, "Connect timeout attempt $attempt, retrying...")
                        if (attempt < 3) Thread.sleep(1000)
                        else throw e
                    }
                }

                val sock = socket ?: throw Exception("Failed to connect after 3 attempts")
                val outputStream = sock.getOutputStream()
                val inputStream = sock.getInputStream()
                val fileSize = file.length()

                // 发送 PT-HI 握手
                outputStream.write("PT-HI $deviceModelName\n".toByteArray())
                outputStream.flush()

                // 读取对方 PT-HI 握手响应
                val peerHandshake = readLineBytes(inputStream)
                if (peerHandshake != null && peerHandshake.startsWith("PT-HI")) {
                    Log.d(TAG, "Handshake with: ${peerHandshake.removePrefix("PT-HI").trim()}")
                }

                // HTTP PUT 请求头
                val encodedName = java.net.URLEncoder.encode(file.name, "UTF-8")
                    .replace("+", "%20")
                val header = "PUT /$encodedName HTTP/1.1\r\n" +
                    "Content-Length: $fileSize\r\n" +
                    "\r\n"
                outputStream.write(header.toByteArray())
                outputStream.flush()

                // 发送文件数据
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
                            onProgress?.invoke(totalSent, fileSize)
                        }
                    }
                }
                outputStream.flush()

                // 读取 HTTP 响应
                val httpResponse = readLineBytes(inputStream)
                Log.d(TAG, "HTTP response for ${file.name}: $httpResponse")

                // 可选：继续读取直到空行
                if (httpResponse != null && httpResponse.startsWith("HTTP/")) {
                    while (true) {
                        val line = readLineBytes(inputStream) ?: break
                        if (line.isEmpty()) break
                    }
                }

                sock.close()
                socket = null

                onFileComplete?.invoke(file.name)
                Log.d(TAG, "File ${file.name} sent successfully")

            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Timeout sending ${file.name}", e)
                try { socket?.close() } catch (_: Exception) {}
                onError?.invoke("发送 ${file.name} 超时: $host:$port")
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused for ${file.name}", e)
                try { socket?.close() } catch (_: Exception) {}
                onError?.invoke("发送 ${file.name} 连接被拒绝")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ${file.name}", e)
                try { socket?.close() } catch (_: Exception) {}
                onError?.invoke("发送 ${file.name} 失败: ${e.message}")
            }
        }
    }

    /** 读取一行（不含换行符） */
    private fun readLineBytes(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) sb.append(b.toChar())
            b = input.read()
        }
        return sb.toString()
    }

    /** 获取本机机型名 */
    private fun getDeviceModelName(): String {
        return try {
            val manufacturer = Build.MANUFACTURER.ifBlank { "Android" }
            val model = Build.MODEL.ifBlank { "" }
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        } catch (e: Exception) {
            "Android"
        }
    }
}