package com.phototrans

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局未捕获异常处理器
 * 将崩溃堆栈写入缓存目录，以便下次启动时查看或分享
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private lateinit var appContext: Context
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    fun init(context: Context) {
        appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    private fun getCrashDir(context: Context): File {
        // 优先使用外部文件目录（Android/data/com.phototrans/files/crash_logs/，可通过 USB 文件传输访问）
        val externalDir = context.getExternalFilesDir("crash_logs")
        if (externalDir != null) {
            return externalDir
        }
        // 回退到缓存目录
        return File(context.cacheDir, "crash_logs")
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            // 优先使用外部存储（用户可通过 USB 文件传输直接访问）
            val crashDir = getCrashDir(appContext)
            crashDir.mkdirs()
            val fileName = "crash_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"
            val file = File(crashDir, fileName)
            FileWriter(file).use { writer ->
                writer.write("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
                writer.write("Thread: ${t.name}\n")
                writer.write("Exception: ${e.javaClass.name}\n")
                writer.write("Message: ${e.message}\n")
                writer.write("Stack:\n")
                e.stackTrace.forEach { writer.write("\t${it.toString()}\n") }
                var cause = e.cause
                while (cause != null) {
                    writer.write("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                    cause.stackTrace.forEach { writer.write("\t${it.toString()}\n") }
                    cause = cause.cause
                }
            }
        } catch (_: Exception) {
            // 若无法写入日志，忽略
        }

        // 继续默认的崩溃处理（系统会弹出"应用已停止"）
        defaultHandler?.uncaughtException(t, e)
    }

    /**
     * 获取所有崩溃日志文件列表
     */
    fun getCrashLogFiles(context: Context): List<File> {
        val crashDir = getCrashDir(context)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.toList() ?: emptyList()
    }

    /**
     * 分享崩溃日志（在下次启动时调用）
     */
    fun shareCrashLog(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享崩溃日志"))
    }
}