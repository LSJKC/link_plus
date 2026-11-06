package io.github.shaonialife.buicklinkplus.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 写入 files/main_debug.log，界面和服务共用写锁，供 logcat 不可读时排查 */
object DebugFileLog {
    private val lock = Any()
    private val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 启动标记写入文件，默认只导出最后一次启动后的日志，截断后仍可识别 */
    const val SESSION_MARKER = "===== Buick Link＋ 进程启动（本次日志起点）====="

    /** 进程启动时写入日志标记，重复调用时以最后一次为准 */
    fun markSessionStart(context: Context) {
        append(context, SESSION_MARKER)
    }

    /** 读取日志，[currentSessionOnly] 为 true 时只取最后一次启动后的内容，无标记则取全文 */
    fun read(
        context: Context,
        currentSessionOnly: Boolean,
    ): String? =
        runCatching {
            val text = File(context.filesDir, "main_debug.log").readText(Charsets.UTF_8)
            LogRedactor.redact(if (currentSessionOnly) currentSessionSlice(text) else text)
        }.getOrNull()

    /** 提取最后一次启动后的日志，无启动标记时返回全文 */
    internal fun currentSessionSlice(text: String): String {
        val markerAt = text.lastIndexOf(SESSION_MARKER)
        return if (markerAt < 0) text else text.substring(markerAt + SESSION_MARKER.length)
    }

    fun append(
        context: Context,
        message: String,
    ) {
        try {
            synchronized(lock) {
                val file = File(context.filesDir, "main_debug.log")
                // 日志截断后补写启动标记，导出时仍能区分本次启动
                if (file.length() > 2_000_000) {
                    file.delete()
                    FileWriter(
                        file,
                        true,
                    ).use {
                        it
                            .append('[')
                            .append(timestamp.format(Date()))
                            .append("] ")
                            .append(SESSION_MARKER)
                            .append('\n')
                    }
                }
                FileWriter(file, true).use { writer ->
                    writer
                        .append('[')
                        .append(timestamp.format(Date()))
                        .append("] ")
                        .append(LogRedactor.redact(message))
                        .append('\n')
                }
            }
        } catch (_: Throwable) {
        }
    }
}
