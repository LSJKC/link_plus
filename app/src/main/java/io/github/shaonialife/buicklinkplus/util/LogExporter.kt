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

    // ---- 日志包（ZIP + 分享/保存）：一个压缩包带应用/设备/CarLife 引擎/设置/日志 ----

    /**
     * 生成完整日志包 ZIP（cacheDir/log-bundles/carlife-logs-<yyyyMMdd-HHmmss>.zip）。
     *
     * **只写应用缓存目录，不落下载目录**；分享/导出都不会自动保存文件，
     * 需要保存时调用 [saveBundleToDownloads] 或 [saveLastBundle]。
     * 内含 app.txt / device.txt / carlife-engine.txt / settings.txt / README.txt、
     * logs/current.log（本次启动历史）与 logs/ 下 *.log（历史文件日志），详见 [LogBundle]。
     * 生成失败返回 null。含本机端口探测（最多约 1.5s），建议后台线程调用。
     */
    fun exportBundle(context: Context): File? = LogBundle.export(context)

    /** 系统分享已生成的缓存日志包（FileProvider content URI）；**不保存**到下载目录。 */
    fun shareBundle(
        context: Context,
        file: File,
    ): Boolean = LogBundle.share(context, file)

    /** 最近一次生成的缓存日志包；没有返回 null。 */
    fun latestBundle(context: Context): File? = LogBundle.latestBundle(context)

    /** 分享最近一次日志包（没有会先生成缓存 ZIP）；**不保存**到下载目录。返回是否已发起分享。 */
    fun shareLastBundle(context: Context): Boolean = LogBundle.shareLast(context)

    /** 兼容保留：生成缓存 ZIP 后直接分享（不写下载目录）；新 UI 请用 shareLastBundle + saveLastBundle。 */
    fun exportAndShare(context: Context): Boolean = LogBundle.exportAndShare(context)

    /**
     * 把缓存里的日志包**显式保存**到「下载/Buick Link＋」（MediaStore，无需存储权限）。
     * 供 UI「保存日志」按钮使用；分享路径不会调用本方法。
     *
     * @return 展示路径（如 `/storage/emulated/0/Download/Buick Link＋/xxx.zip`）；失败 null
     */
    fun saveBundleToDownloads(
        context: Context,
        file: File,
    ): String? = LogBundle.saveToDownloads(context, file)

    /** 保存最近一次日志包到「下载/Buick Link＋」（没有会先生成缓存 ZIP）。返回是否已保存。 */
    fun saveLastBundle(context: Context): Boolean = LogBundle.saveLast(context)

    /** 最近一次「保存日志」写入「下载/Buick Link＋」的展示路径；没保存过返回 null。 */
    fun lastBundleDownloadPath(): String? = LogBundle.lastDownloadDisplayPath()

    /** Manifest 是否已声明 FileProvider（分享必需；未声明时保存仍可用）。 */
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
