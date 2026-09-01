package com.phototrans

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志记录器
 *
 * 与 CrashHandler（只记崩溃）不同，这里记录所有运行期关键事件：
 * 会话/握手/发送/接收/错误/连接等，并可从设置页一键导出。
 *
 * 所有调用显式传 context（内部 lazy，不依赖 Activity 初始化顺序）；
 * 日志文件位于 getExternalFilesDir("logs")，可用 USB 文件传输直接访问。
 */
object AppLogger {

    private const val TAG = "AppLogger"
    /** 单文件超过此大小则轮转（旧日志保留为 .old） */
    private const val MAX_LOG_BYTES = 1024L * 1024L // 1MB

    fun d(context: Context, tag: String, msg: String) =
        write(context, "D", tag, msg, null)

    fun e(context: Context, tag: String, msg: String, t: Throwable? = null) =
        write(context, "E", tag, msg, t)

    private fun logDir(context: Context): File {
        val dir = context.getExternalFilesDir("logs")
        return dir ?: File(context.filesDir, "logs")
    }

    @Synchronized
    private fun write(context: Context, level: String, tag: String, msg: String, t: Throwable?) {
        try {
            val dir = logDir(context)
            dir.mkdirs()
            val file = File(dir, "phototrans.log")
            // 轮转: 超过上限时旧文件改名为 .old
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                val old = File(dir, "phototrans.log.old")
                try { if (old.exists()) old.delete() } catch (_: Exception) {}
                try { file.copyTo(old, overwrite = true) } catch (_: Exception) {}
                try { file.delete() } catch (_: Exception) {}
            }
            FileWriter(file, true).use { writer ->
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                writer.write("$time [$level] [$tag] $msg\n")
                if (t != null) {
                    val sw = StringWriter()
                    val pw = PrintWriter(sw)
                    t.printStackTrace(pw)
                    writer.write(sw.toString())
                    writer.write("\n")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
        }
    }

    /** 导出日志: 合并运行日志 + 崩溃日志为单个 txt，返回 FileProvider Uri；无内容返回 null */
    fun export(context: Context): android.net.Uri? {
        return try {
            val dir = logDir(context)
            dir.mkdirs()
            val exportFile = File(dir, "phototrans_export_export.txt")
            exportFile.delete()

            val sb = StringBuilder()
            sb.append("PhotoTrans 日志导出\n")
            sb.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            sb.append("版本: ${BuildConfig.VERSION_NAME}\n")
            sb.append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n")
            sb.append("=".repeat(60)).append("\n\n")

            for (name in listOf("phototrans.log", "phototrans.log.old")) {
                val f = File(dir, name)
                if (f.exists()) {
                    sb.append("────── 运行日志: $name ──────\n")
                    f.useLines { lines -> lines.forEach { sb.append(it).append('\n') } }
                    sb.append('\n')
                }
            }

            sb.append("────── 崩溃日志 ──────\n")
            val crashes = CrashHandler.getCrashLogFiles(context)
            if (crashes.isEmpty()) {
                sb.append("(无崩溃记录)\n")
            } else {
                for (f in crashes) {
                    sb.append("--- ${f.name} ---\n")
                    f.useLines { lines -> lines.forEach { sb.append(it).append('\n') } }
                    sb.append('\n')
                }
            }

            FileWriter(exportFile).use { it.write(sb.toString()) }

            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )
        } catch (e: Exception) {
            Log.w(TAG, "export failed: ${e.message}")
            null
        }
    }

    /** 分享导出的日志（无内容时提示） */
    fun shareExport(context: Context) {
        val uri = export(context)
        if (uri == null) {
            android.widget.Toast.makeText(context, "暂无日志可导出", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "PhotoTrans 日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }
}