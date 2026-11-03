package com.follow.clash

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagnosticLog {
    private const val MAX_BYTES = 2L * 1024L * 1024L
    private const val KEEP_BYTES = 1024 * 1024
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var file: File

    fun init(context: Context) {
        if (::file.isInitialized) return
        val directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        file = File(directory, "clash-next.log")
        record("APP", "diagnostic logging initialized")
    }

    fun record(category: String, message: String) {
        if (!::file.isInitialized) return
        val line = "${timestamp()} [${category.uppercase(Locale.ROOT)}] " +
            DiagnosticSanitizer.redact(message.replace('\n', ' ').replace('\r', ' '))
        worker.execute { append(line) }
    }

    fun recordCoreEvent(event: JSONObject) {
        when (event.optString("type")) {
            "log" -> {
                val data = event.optJSONObject("data") ?: JSONObject()
                val level = data.optString("LogLevel", "info")
                val payload = data.optString("Payload", data.toString())
                record("CORE/$level", payload)
            }
            "crash" -> record("CORE/CRASH", event.opt("data")?.toString().orEmpty())
            "loaded" -> record("CORE/LOADED", event.opt("data")?.toString().orEmpty())
            "geoUpdate" -> record("CORE/GEO", event.opt("data")?.toString().orEmpty())
        }
    }

    fun sizeBytes(): Long = if (::file.isInitialized) file.length() else 0L

    fun readRecent(maxLines: Int = 120, callback: (String) -> Unit) {
        worker.execute {
            val text = runCatching {
                if (!file.isFile) "暂无诊断日志"
                else file.readLines().takeLast(maxLines).joinToString("\n")
            }.getOrElse { it.message ?: "日志读取失败" }
            mainHandler.post { callback(text) }
        }
    }

    fun exportTo(
        output: OutputStream,
        header: String,
        callback: (Boolean, String) -> Unit,
    ) {
        worker.execute {
            runCatching {
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine("Clash Next diagnostic report")
                    writer.appendLine("Sensitive URLs and common credential fields are redacted.")
                    writer.appendLine()
                    writer.appendLine(header.trim())
                    writer.appendLine()
                    writer.appendLine("--- LOGS ---")
                    if (file.isFile) {
                        file.forEachLine(Charsets.UTF_8) { line -> writer.appendLine(line) }
                    }
                }
            }.onSuccess {
                mainHandler.post { callback(true, "诊断日志已导出") }
            }.onFailure {
                runCatching { output.close() }
                mainHandler.post { callback(false, it.message ?: "日志导出失败") }
            }
        }
    }

    fun clear(callback: () -> Unit = {}) {
        worker.execute {
            runCatching { file.delete() }
            mainHandler.post(callback)
        }
    }

    private fun append(line: String) {
        runCatching {
            if (file.length() >= MAX_BYTES) trim()
            file.appendText("$line\n", Charsets.UTF_8)
        }
    }

    private fun trim() {
        val bytes = file.readBytes()
        var start = (bytes.size - KEEP_BYTES).coerceAtLeast(0)
        while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
        if (start < bytes.size) start++
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
