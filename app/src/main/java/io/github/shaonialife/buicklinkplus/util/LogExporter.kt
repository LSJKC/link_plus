package io.github.shaonialife.buicklinkplus.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.shaonialife.buicklinkplus.session.SessionController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将日志导出到 MediaStore.Downloads，无需存储权限
 * 优先读取文件日志，失败时使用界面缓冲
 */
object LogExporter {
    /** 日志导出范围，默认本次启动 */
    enum class Scope(
        val label: String,
        val fileNameTag: String,
    ) {
        CURRENT_SESSION("仅本次启动", ""),
        ALL("全部历史日志", "-all"),
    }

    data class Result(
        val uri: Uri,
        val fileName: String,
        /** 给用户看的绝对路径（文件管理器里按这个找）。 */
        val displayPath: String,
        val bytes: Long,
        val scope: Scope,
    )

    /** 导出目录（相对外部存储根）：下载/Buick Link＋。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/Buick Link＋"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 后台导出日志，写入失败返回 null */
    fun export(
        context: Context,
        fallbackText: String,
        scope: Scope = Scope.CURRENT_SESSION,
    ): Result? {
        val body = LogRedactor.redact(buildContent(context, fallbackText, scope))
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "Buick Link＋-log-$stamp${scope.fileNameTag}.txt"
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器/相册扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Result(uri, fileName, "$DISPLAY_ROOT/$RELATIVE_DIR/$fileName", bytes.size.toLong(), scope)
        } catch (_: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    // ---- 日志包（ZIP + 分享）：一个压缩包带应用/设备/CarLife 引擎/设置/日志 ----

    /**
     * 生成完整日志包 ZIP（cacheDir/log-bundles/carlife-logs-<yyyyMMdd-HHmmss>.zip），
     * 并另存一份到「下载/Buick Link＋」（用户不用去 cache 里翻，也兼容无 FileProvider 的分享兜底）。
     *
     * 内含 app.txt / device.txt / carlife-engine.txt / settings.txt / README.txt、
     * logs/current.log（本次启动历史）与 logs/ 下 *.log（历史文件日志），详见 [LogBundle]。
     * 生成失败返回 null。含本机端口探测（最多约 1.5s），建议后台线程调用。
     */
    fun exportBundle(context: Context): File? = LogBundle.export(context)

    /** 分享已生成的日志包（FileProvider content URI；未声明 provider 时退化为 Downloads URI）。 */
    fun shareBundle(
        context: Context,
        file: File,
    ): Boolean = LogBundle.share(context, file)

    /** 最近一次生成的日志包；没有返回 null。 */
    fun latestBundle(context: Context): File? = LogBundle.latestBundle(context)

    /** 分享最近一次日志包（没有会先导出一个）；返回是否已发起分享。 */
    fun shareLastBundle(context: Context): Boolean = LogBundle.shareLast(context)

    /** 一键「导出并分享」：生成 ZIP + 另存 Downloads + 尽力拉起分享。返回是否导出成功。 */
    fun exportAndShare(context: Context): Boolean = LogBundle.exportAndShare(context)

    /** 最近一次导出到「下载/Buick Link＋」的展示路径；没有返回 null。 */
    fun lastBundleDownloadPath(): String? = LogBundle.lastDownloadDisplayPath()

    /** Manifest 是否已声明 FileProvider（未声明时分享退化用 Downloads URI，导出不受影响）。 */
    fun isBundleShareSupported(context: Context): Boolean = LogBundle.isShareSupported(context)

    private fun buildContent(
        context: Context,
        fallbackText: String,
        scope: Scope,
    ): String =
        buildString {
            appendLine("Buick Link＋ 运行日志")
            appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("应用版本: " + versionName(context))
            appendLine("内部版本码: ${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_CODE}")
            appendLine("构建标识: ${io.github.shaonialife.buicklinkplus.BuildConfig.BUILD_ID}")
            appendLine("会话快照: ${io.github.shaonialife.buicklinkplus.session.SessionController.diagnosticSnapshot()}")
            appendLine("导出范围: " + scope.label)
            appendLine(
                "设备: ${Build.MANUFACTURER} ${Build.MODEL} | " +
                    "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            )
            appendLine("==================================================")
            val fileLog = DebugFileLog.read(context, currentSessionOnly = scope == Scope.CURRENT_SESSION)
            if (!fileLog.isNullOrBlank()) {
                if (scope == Scope.CURRENT_SESSION) {
                    appendLine("（仅本次进程启动之后的日志；需要历史请点「导出全部」）")
                } else {
                    appendLine("（全部文件日志；文件最多 2MB，超出时系统只保留最近一段）")
                }
                append(fileLog)
                if (!fileLog.endsWith('\n')) appendLine()
            } else {
                // 启动标记刚写、其余日志还没落盘（或文件不可读）时，界面缓冲仍是有效来源
                appendLine("（文件日志不可用——导出界面缓冲，可能不完整）")
                append(fallbackText)
            }
        }

    @Suppress("DEPRECATION")
    private fun versionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
                .orEmpty()
        }.getOrDefault("?")
}
