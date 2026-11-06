/* 日志打包：把一个压缩包做全——应用/设备/CarLife 引擎/设置/本次启动历史日志/历史文件日志。
 *
 * 用户要求（旧软件习惯）：导出日志不要一堆散文件——一个
 * `carlife-logs-<时间戳>.zip` 里带齐排查所需信息。
 *
 * 语义（2026-11-06 用户反馈修正）：
 * - 分享/导出 ZIP 只在 `cacheDir/log-bundles/` 生成，**不落下载目录**；
 * - 只有用户显式点「保存日志」才通过 MediaStore 写一份到「下载/Buick Link＋」；
 * - 分享走 FileProvider + ACTION_SEND，不产生任何 Downloads 文件。
 *
 * 由 [io.github.shaonialife.buicklinkplus.util.LogExporter] 对外暴露稳定 API：
 * exportBundle / shareBundle / shareLastBundle / saveBundleToDownloads / saveLastBundle /
 * latestBundle。
 *
 * ZIP 内容：
 *   README.txt           反馈说明
 *   app.txt              应用版本/构建/安装/进程/会话快照
 *   device.txt           设备与系统（机型/Android/补丁/屏幕/语言时区/内存存储）
 *   carlife-engine.txt   CarLife 相关包 + 本机引擎端口监听探测
 *   settings.txt         本应用 SharedPreferences 快照（敏感键值隐藏）
 *   logs/current.log     本次启动历史（SessionController 会话缓冲）
 *   logs/ 下 *.log       历史日志（如 main_debug.log，已脱敏）
 *
 * 分享依赖 Manifest 里的 FileProvider（authority = `<包名>.fileprovider`，
 * paths = `@xml/file_paths`，`res/xml/file_paths.xml` 已提供 cache-path）。
 * 未声明 provider 时分享返回 false（不再用 Downloads 兜底，避免用户没点保存却落盘）。
 *
 * 注意：端口探测最多约 7×250ms，建议在后台线程调用（UI 入口由 activation-ui 负责）。
 * 日志/设置内容全部经过 [LogRedactor] 脱敏；设置里的敏感键值额外隐藏。
 */
package io.github.shaonialife.buicklinkplus.util

import android.app.Activity
import android.app.ActivityManager
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import io.github.shaonialife.buicklinkplus.BuildConfig
import io.github.shaonialife.buicklinkplus.session.SessionController
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 日志打包与分享（一个 ZIP 带齐应用/设备/CarLife/设置/日志）。 */
object LogBundle {
    private const val TAG = "LogBundle"

    /** 压缩包放在 cacheDir/log-bundles/，对应 res/xml/file_paths.xml 的 cache-path。 */
    private const val BUNDLE_DIR = "log-bundles"
    private const val BUNDLE_PREFIX = "carlife-logs-"

    /** 缓存里最多保留最近几个日志包。 */
    private const val MAX_KEEP_BUNDLES = 8

    /** 单个历史日志文件打包上限（DebugFileLog 上限 2MB，留足余量防止 OOM）。 */
    private const val MAX_LOG_FILE_BYTES = 8L * 1024 * 1024

    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    /** 显式「保存日志」时写入的下载目录（MediaStore，无需存储权限）。 */
    private val RELATIVE_DIR = "${Environment.DIRECTORY_DOWNLOADS}/Buick Link＋"

    /** 文件管理器里展示的绝对路径前缀（主用户外部存储；分区存储下即 /sdcard）。 */
    private const val DISPLAY_ROOT = "/storage/emulated/0"

    /** 最近一次「保存日志」写入 Downloads 的展示路径（仅展示/日志用；分享不写）。 */
    @Volatile
    private var lastDownloadsDisplayPath: String? = null

    /** CarLife 引擎本机 7 个端口（7240 控制 / 8240 视频 / 9240 媒体 / 9241 TTS / 9242 VR / 9340 触摸 / 9440 数据）。 */
    private val ENGINE_PORTS = intArrayOf(7240, 8240, 9240, 9241, 9242, 9340, 9440)

    /** 端口 connect 超时：现场没开 CarLife 时也要快速出包。 */
    private const val PORT_PROBE_TIMEOUT_MS = 250

    /** 已知 CarLife 包名候选（部分车机/定制版用不同后缀）。 */
    private val CARLIFE_PACKAGE_CANDIDATES =
        listOf(
            "com.baidu.carlife",
            "com.baidu.carlife.customware",
            "com.baidu.carlife.pro",
            "com.baidu.carlife.pre",
        )

    private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val HUMAN = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 敏感设置键（完全隐藏值，只保留键名）。 */
    private val SENSITIVE_KEY =
        Regex("(?i)(password|passphrase|secret|token|private|psk|credential|cookie|auth|key|mac)")

    /** 看起来像密钥/原始标识的长值（十六进制/长串）也隐藏。 */
    private val SECRET_VALUE = Regex("^(?:[0-9a-fA-F]{16,}|[A-Za-z0-9+/=_-]{40,})$")

    // ---- 对外 API ----

    /**
     * 生成日志包 ZIP 到 `cacheDir/log-bundles/carlife-logs-<yyyyMMdd-HHmmss>.zip`。
     *
     * 只写应用缓存目录（分享/导出都不落下载目录；需要保存请调 [saveToDownloads]/[saveLast]）。
     * 失败（无存储/写失败）返回 null；成功返回文件。建议后台线程调用。
     */
    fun export(context: Context): File? {
        val appContext = context.applicationContext ?: context
        val dir = File(appContext.cacheDir, BUNDLE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "无法创建日志包目录: ${dir.absolutePath}")
            return null
        }
        val file = File(dir, "$BUNDLE_PREFIX${STAMP.format(Date())}.zip")
        return runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
                putText(zip, "README.txt", readme(appContext))
                putText(zip, "app.txt", appInfo(appContext))
                putText(zip, "device.txt", deviceInfo(appContext))
                putText(zip, "carlife-engine.txt", engineInfo(appContext))
                putText(zip, "settings.txt", settingsInfo(appContext))
                putText(zip, "logs/current.log", currentLog(appContext))
                appendFileLogs(appContext, zip)
            }
            trimOldBundles(dir)
            file
        }.getOrElse { error ->
            Log.w(TAG, "生成日志包失败", error)
            runCatching { file.delete() }
            null
        }
    }

    /** 找最近一次生成的日志包（cacheDir/log-bundles/ 下 *.zip，按修改时间倒序）。 */
    fun latestBundle(context: Context): File? {
        val appContext = context.applicationContext ?: context
        val dir = File(appContext.cacheDir, BUNDLE_DIR)
        return dir
            .listFiles { f -> f.isFile && f.name.startsWith(BUNDLE_PREFIX) && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
    }

    /**
     * 分享最近一次日志包；没有现成包会先 [export] 一个（分享按钮一次操作即可）。
     *
     * **只分享，不落盘**：ZIP 留在应用缓存目录，通过 FileProvider content URI 交给系统 chooser；
     * Manifest 未声明 provider 时返回 false（不再用 Downloads 兜底）。
     */
    fun shareLast(context: Context): Boolean {
        val file = latestBundle(context) ?: export(context) ?: return false
        return share(context, file)
    }

    /**
     * 兼容保留：生成 cache ZIP 后直接分享（不写下载目录）。新 UI 请优先用
     * `LogExporter.shareLastBundle`（分享）与 [saveLast]（保存）两个入口。
     *
     * @return true = 已发起分享
     */
    fun exportAndShare(context: Context): Boolean {
        val file = export(context) ?: return false
        return share(context, file)
    }

    /** 最近一次「保存日志」写入「下载/Buick Link＋」的展示路径；没保存过返回 null。 */
    fun lastDownloadDisplayPath(): String? = lastDownloadsDisplayPath

    /**
     * 把缓存里的日志包**显式保存**到「下载/Buick Link＋」（MediaStore，无需存储权限）。
     *
     * 只在用户点「保存日志」时调用；分享路径不会调用本方法。
     *
     * @return 展示路径（如 `/storage/emulated/0/Download/Buick Link＋/xxx.zip`）；失败 null
     */
    fun saveToDownloads(
        context: Context,
        file: File,
    ): String? {
        if (!file.isFile || file.length() <= 0L) return null
        val appContext = context.applicationContext ?: context
        return runCatching { copyToDownloads(appContext, file) }.getOrNull()?.let { lastDownloadsDisplayPath }
    }

    /**
     * 保存最近一次日志包到「下载/Buick Link＋」；没有现成包会先 [export] 一个。
     *
     * @return true = 已保存到 Downloads
     */
    fun saveLast(context: Context): Boolean {
        val file = latestBundle(context) ?: export(context) ?: return false
        return saveToDownloads(context, file) != null
    }

    /**
     * 系统分享日志包（ACTION_SEND + FileProvider content URI + 临时读权限）。
     *
     * 只分享缓存文件，不写下载目录。
     *
     * @return true = 已发起分享 chooser；false = 文件不存在/未声明 FileProvider/无分享目标
     */
    fun share(
        context: Context,
        file: File,
    ): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val appContext = context.applicationContext ?: context
        val authority = appContext.packageName + FILE_PROVIDER_SUFFIX
        if (!isShareSupported(appContext)) {
            Log.w(TAG, "无法分享：Manifest 未声明 FileProvider（authorities=$authority）")
            return false
        }
        val uri = runCatching { FileProvider.getUriForFile(appContext, authority, file) }.getOrNull()
        if (uri == null) {
            Log.w(TAG, "FileProvider.getUriForFile 失败（检查 res/xml/file_paths.xml 是否覆盖 $BUNDLE_DIR）")
            return false
        }
        return dispatchShare(context, uri, file.name)
    }

    /** Manifest 是否已声明本应用的 FileProvider（分享必需；未声明时保存仍可用）。 */
    fun isShareSupported(context: Context): Boolean =
        runCatching {
            val appContext = context.applicationContext ?: context
            appContext.packageManager.resolveContentProvider(appContext.packageName + FILE_PROVIDER_SUFFIX, 0) != null
        }.getOrDefault(false)

    // ---- 分享/另存实现 ----

    /** 拉起系统分享 chooser；后台线程调用时投递主线程并短暂等待结果。 */
    private fun dispatchShare(
        context: Context,
        uri: Uri,
        displayName: String,
    ): Boolean {
        val startShare = {
            runCatching {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "CarLife 日志包 $displayName")
                        clipData = ClipData.newUri(context.contentResolver, displayName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                val chooser = Intent.createChooser(send, "分享 CarLife 日志")
                // 从 Application/Service 上下文发起需要 NEW_TASK；Activity 上下文保持默认
                if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }.isSuccess
        }
        // startActivity 最好在主线程；后台线程调用时投递到主线程并短暂等待结果
        if (Looper.myLooper() == Looper.getMainLooper()) return startShare()
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            result.set(startShare())
            latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        return result.get()
    }

    /** 「保存日志」用：把日志包写入 MediaStore Downloads/Buick Link＋（无需存储权限）；失败返回 null。 */
    private fun copyToDownloads(
        context: Context,
        source: File,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                // 先标 pending 再写：避免文件管理器扫描到半截文件
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: throw IllegalStateException("openOutputStream 返回 null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            lastDownloadsDisplayPath = "$DISPLAY_ROOT/$RELATIVE_DIR/${source.name}"
            Log.i(TAG, "日志包已保存: $lastDownloadsDisplayPath")
            uri
        } catch (error: Exception) {
            // 半截文件（IS_PENDING=1）对用户不可见，但会占空间——清掉
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "日志包另存 Downloads 失败", error)
            null
        }
    }

    // ---- ZIP 内容 ----

    private fun readme(context: Context): String =
        buildString {
            appendLine("Buick Link＋ CarLife 日志包")
            appendLine("导出时间: ${HUMAN.format(Date())}")
            appendLine("应用: ${appName(context)} ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}，${BuildConfig.BUILD_ID}）")
            appendLine()
            appendLine("包含内容:")
            appendLine("  app.txt              应用版本/构建/安装/进程/会话快照")
            appendLine("  device.txt           手机型号、Android 版本、屏幕、语言时区、内存存储")
            appendLine("  carlife-engine.txt   CarLife 相关应用安装信息 + 本机引擎端口监听探测")
            appendLine("  settings.txt         本应用设置快照（密码/密钥/令牌等敏感值已隐藏）")
            appendLine("  logs/current.log     本次 App 启动以来的历史日志（会话缓冲）")
            appendLine("  logs/*.log           历史文件日志（如 main_debug.log，已脱敏）")
            appendLine()
            appendLine("保存位置: 点「保存日志」后写入 下载/Buick Link＋/<本文件名>；分享不落盘。")
            appendLine("分享方式: 设置 → 日志与关于 →「分享日志（ZIP）」，选微信/QQ/邮件直接发送本压缩包。")
            appendLine()
            appendLine("反馈方式:")
            appendLine("  1. 把整个 zip 直接发送给开发者（微信/QQ/邮件均可）；")
            appendLine("  2. 附上现象描述、发生时间（尽量精确到分钟）、手机型号与车机型号；")
            appendLine("  3. 若是连接/画面问题，请说明当时 CarLife+ 是否已「无线连接」、车机是否已连上。")
            appendLine()
            appendLine("隐私说明:")
            appendLine("  日志与设置已做脱敏（去除了密码/密钥/令牌等敏感值），设备与 CarLife 信息仅用于定位兼容性问题。")
            appendLine("  发送前仍建议自行解压浏览一遍。")
        }

    private fun appInfo(context: Context): String {
        val pm = context.packageManager
        val packageInfo = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            appendLine("== 应用信息 ==")
            appendLine("导出时间: ${HUMAN.format(Date())}")
            appendLine("应用名: ${appName(context)}")
            appendLine("版本名: ${packageInfo?.versionName ?: "?"}")
            appendLine("版本码: ${packageInfo?.longVersionCode ?: -1L}")
            appendLine("BUILD_ID: ${BuildConfig.BUILD_ID}")
            appendLine("构建类型: ${if (BuildConfig.DEBUG) "debug" else "release"}（buildType=${BuildConfig.BUILD_TYPE}）")
            appendLine("包名: ${context.packageName}")
            appendLine("进程名: ${runCatching { android.app.Application.getProcessName() }.getOrDefault("?")}")
            appendLine("APK 路径: ${context.applicationInfo.sourceDir}")
            appendLine("安装时间: ${formatTime(packageInfo?.firstInstallTime)}")
            appendLine("更新时间: ${formatTime(packageInfo?.lastUpdateTime)}")
            appendLine("APK 修改时间: ${formatTime(runCatching { File(context.applicationInfo.sourceDir).lastModified() }.getOrDefault(0L))}")
            appendLine("支持 ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine(
                "进程启动: ${formatTime(processStartWallClockMs())}" +
                    "（PID ${android.os.Process.myPid()}，已运行 ${formatDuration(SystemClock.elapsedRealtime())}）",
            )
            appendLine()
            appendLine("== 会话快照 ==")
            appendLine(runCatching { SessionController.diagnosticSnapshot() }.getOrDefault("不可用"))
        }
    }

    private fun deviceInfo(context: Context): String {
        val metrics = context.resources.displayMetrics
        val configuration = context.resources.configuration
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memoryInfo) }
        val runtime = Runtime.getRuntime()
        val timeZone = TimeZone.getDefault()
        return buildString {
            appendLine("== 设备/系统信息 ==")
            appendLine("导出时间: ${HUMAN.format(Date())}")
            appendLine("厂商: ${Build.MANUFACTURER}")
            appendLine("品牌: ${Build.BRAND}")
            appendLine("型号: ${Build.MODEL}")
            appendLine("设备代号: ${Build.DEVICE} / product=${Build.PRODUCT} / board=${Build.BOARD} / hardware=${Build.HARDWARE}")
            appendLine("Android: ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}，codename=${Build.VERSION.CODENAME}）")
            appendLine("安全补丁: ${Build.VERSION.SECURITY_PATCH}")
            appendLine("系统构建: ${Build.DISPLAY} / id=${Build.ID} / type=${Build.TYPE} / tags=${Build.TAGS}")
            appendLine("Fingerprint: ${Build.FINGERPRINT}")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}（64位=${Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()}）")
            appendLine("CPU 核心数: ${runtime.availableProcessors()}")
            appendLine(
                "屏幕: ${metrics.widthPixels}x${metrics.heightPixels}px " +
                    "density=${metrics.density} dpi=${metrics.densityDpi} " +
                    "xdpi=${"%.2f".format(metrics.xdpi)} ydpi=${"%.2f".format(metrics.ydpi)} " +
                    "smallestWidthDp=${configuration.smallestScreenWidthDp} " +
                    "orientation=${configuration.orientation}",
            )
            appendLine("语言: ${Locale.getDefault().toLanguageTag()}（${Locale.getDefault()}）")
            appendLine("时区: ${timeZone.id}（UTC${utcOffsetText(timeZone.getOffset(System.currentTimeMillis()))}）")
            appendLine(
                "内存: 系统 total=${formatBytes(memoryInfo.totalMem)} " +
                    "avail=${formatBytes(memoryInfo.availMem)} threshold=${formatBytes(memoryInfo.threshold)} " +
                    "lowMemory=${memoryInfo.lowMemory}；" +
                    "Java max=${formatBytes(runtime.maxMemory())} " +
                    "total=${formatBytes(runtime.totalMemory())} free=${formatBytes(runtime.freeMemory())}",
            )
            appendLine(
                "存储: dataDir 可用=${formatBytes(runCatching { context.dataDir.usableSpace }.getOrDefault(0L))} " +
                    "cache 可用=${formatBytes(runCatching { context.cacheDir.usableSpace }.getOrDefault(0L))} " +
                    "filesDir 可用=${formatBytes(runCatching { context.filesDir.usableSpace }.getOrDefault(0L))}",
            )
            appendLine("进程: pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        }
    }

    @android.annotation.SuppressLint("QueryPermissionsNeeded")
    private fun engineInfo(context: Context): String {
        val pm = context.packageManager
        val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val visibleCarlife =
            installed
                .filter { it.packageName.contains("carlife", ignoreCase = true) || it.packageName in CARLIFE_PACKAGE_CANDIDATES }
                .sortedBy { it.packageName }
        return buildString {
            appendLine("== CarLife 引擎信息 ==")
            appendLine("导出时间: ${HUMAN.format(Date())}")
            appendLine("可见安装包总数: ${installed.size}（Android 11+ 受 Manifest <queries> 包可见性限制）")
            appendLine()
            appendLine("-- CarLife 相关应用 --")
            if (visibleCarlife.isEmpty()) {
                appendLine("（当前可见范围内未发现 CarLife 包）")
            } else {
                visibleCarlife.forEach { appendLine(packageInfoLine(pm, it)) }
            }
            val missingCandidates = CARLIFE_PACKAGE_CANDIDATES.filter { candidate -> visibleCarlife.none { it.packageName == candidate } }
            if (missingCandidates.isNotEmpty()) {
                appendLine()
                appendLine("-- 已知候选包探测（未在可见列表中的逐个查一次）--")
                missingCandidates.forEach { candidate ->
                    val found = runCatching { pm.getPackageInfo(candidate, 0) }.getOrNull()
                    if (found != null) {
                        appendLine(packageInfoLine(pm, found))
                    } else {
                        appendLine("$candidate: 未安装或不可见（如需准确检测，Manifest <queries> 增加该包名）")
                    }
                }
            }
            appendLine()
            appendLine("-- 本机 CarLife 引擎端口监听（127.0.0.1，connect 超时 ${PORT_PROBE_TIMEOUT_MS}ms）--")
            appendLine("说明: CarLife+ 进入「无线连接」后这些端口才会监听；CLOSED 不代表应用有问题，")
            appendLine("      只表示导出时 CarLife 引擎未在无线连接状态。")
            ENGINE_PORTS.forEach { port ->
                appendLine("  $port ${portLabel(port)}: ${if (probePort(port)) "OPEN（监听中）" else "CLOSED（未监听）"}")
            }
            appendLine()
            appendLine("-- CarLife 会话状态（本应用视角）--")
            appendLine(runCatching { SessionController.diagnosticSnapshot() }.getOrDefault("不可用"))
            appendLine()
            appendLine("-- 进程说明 --")
            appendLine("Android 10+ 普通应用无法枚举其它进程；CarLife 引擎进程状态以端口探测为准。")
        }
    }

    private fun settingsInfo(context: Context): String {
        val prefsDir = File(context.dataDir, "shared_prefs")
        val prefFiles =
            prefsDir
                .listFiles { file -> file.isFile && file.name.endsWith(".xml") }
                ?.sortedBy { it.name }
                .orEmpty()
        val text =
            buildString {
                appendLine("== 应用设置快照 ==")
                appendLine("导出时间: ${HUMAN.format(Date())}")
                appendLine("说明: 密码/密钥/令牌/MAC 等敏感值已隐藏；其余键值用于排查。")
                appendLine("目录: ${prefsDir.absolutePath}")
                appendLine()
                if (prefFiles.isEmpty()) {
                    appendLine("（未找到 SharedPreferences 文件）")
                }
                prefFiles.forEach { file ->
                    val name = file.name.removeSuffix(".xml")
                    appendLine("## $name（${formatBytes(file.length())}，修改于 ${formatTime(file.lastModified())}）")
                    val values = runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).all }.getOrNull()
                    when {
                        values == null -> appendLine("  （读取失败）")
                        values.isEmpty() -> appendLine("  （空）")
                        else ->
                            values.toSortedMap().forEach { (key, value) ->
                                val hidden = SENSITIVE_KEY.containsMatchIn(key) || looksSecretValue(value)
                                appendLine("  $key = ${if (hidden) "[已隐藏]" else renderPrefValue(value)}")
                            }
                    }
                    appendLine()
                }
            }
        return LogRedactor.redact(text)
    }

    private fun currentLog(context: Context): String {
        val sessionText = runCatching { SessionController.logText() }.getOrNull()
        val snapshot = runCatching { SessionController.diagnosticSnapshot() }.getOrDefault("不可用")
        val text =
            buildString {
                appendLine("== 本次启动历史日志（会话缓冲，滚动窗口约 40KB）==")
                appendLine("导出时间: ${HUMAN.format(Date())}")
                appendLine("会话快照: $snapshot")
                appendLine("--------------------------------------------------")
                if (sessionText.isNullOrBlank()) {
                    appendLine("（会话日志缓冲为空：可能应用刚启动或「监听日志」开关已关闭）")
                } else {
                    append(sessionText)
                    if (!sessionText.endsWith('\n')) appendLine()
                }
            }
        return LogRedactor.redact(text)
    }

    /** 把 filesDir 下的历史 *.log（DebugFileLog 等）全部打包；缺失时回退 DebugFileLog 读取内容。 */
    private fun appendFileLogs(
        context: Context,
        zip: ZipOutputStream,
    ) {
        val logFiles =
            context.filesDir
                .listFiles { file -> file.isFile && file.name.endsWith(".log") }
                ?.sortedBy { it.name }
                .orEmpty()
        val included = ArrayList<String>()
        logFiles.forEach { file ->
            if (file.length() > MAX_LOG_FILE_BYTES) {
                included += "${file.name}（${formatBytes(file.length())} 超过上限，跳过）"
            } else {
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                if (text != null) {
                    putText(zip, "logs/${file.name}", LogRedactor.redact(text))
                    included += "${file.name}（${formatBytes(file.length())}）"
                }
            }
        }
        if (logFiles.none { it.name == DebugFileLog.LOG_FILE_NAME }) {
            // 历史文件被删/未建立时，仍把 DebugFileLog 能读到的内容带上
            DebugFileLog.read(context, currentSessionOnly = false)?.takeIf { it.isNotBlank() }?.let { content ->
                putText(
                    zip,
                    "logs/${DebugFileLog.LOG_FILE_NAME}",
                    "== DebugFileLog 历史日志（回退读取）==\n" + LogRedactor.redact(content),
                )
                included += "${DebugFileLog.LOG_FILE_NAME}（回退读取）"
            }
        }
        val summary =
            buildString {
                appendLine("== 历史文件日志清单 ==")
                appendLine("导出时间: ${HUMAN.format(Date())}")
                appendLine("目录: ${context.filesDir.absolutePath}")
                if (included.isEmpty()) {
                    appendLine("（无历史日志文件）")
                } else {
                    included.forEach { appendLine("  logs/$it") }
                }
            }
        putText(zip, "logs/README.txt", summary)
    }

    // ---- 工具 ----

    private fun putText(
        zip: ZipOutputStream,
        name: String,
        text: String,
    ) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun trimOldBundles(dir: File) {
        val bundles =
            dir
                .listFiles { file -> file.isFile && file.name.startsWith(BUNDLE_PREFIX) && file.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        bundles.drop(MAX_KEEP_BUNDLES).forEach { old -> runCatching { old.delete() } }
    }

    private fun packageInfoLine(
        pm: android.content.pm.PackageManager,
        packageInfo: android.content.pm.PackageInfo,
    ): String {
        val enabled = runCatching { packageInfo.applicationInfo?.enabled }.getOrNull()
        val label = runCatching { packageInfo.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
        return "  ${packageInfo.packageName}: ${packageInfo.versionName ?: "?"}" +
            " (versionCode ${packageInfo.longVersionCode})" +
            " 安装=${formatTime(packageInfo.firstInstallTime)}" +
            " 更新=${formatTime(packageInfo.lastUpdateTime)}" +
            " 名称=${label ?: "?"} 启用=$enabled"
    }

    private fun probePort(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), PORT_PROBE_TIMEOUT_MS)
            }
            true
        }.getOrDefault(false)

    private fun portLabel(port: Int): String =
        when (port) {
            7240 -> "控制"
            8240 -> "视频"
            9240 -> "媒体"
            9241 -> "TTS"
            9242 -> "VR"
            9340 -> "触摸"
            9440 -> "数据"
            else -> "未知"
        }

    private fun renderPrefValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> if (value.length > 400) value.take(400) + "…（共 ${value.length} 字符）" else value
            is Set<*> ->
                value
                    .filterIsInstance<String>()
                    .sorted()
                    .joinToString(", ", "[", "]")
                    .let { if (it.length > 400) it.take(400) + "…" else it }
            else -> value.toString()
        }

    /** 值是否看起来像密钥/原始标识（长十六进制、长 base64 串）。 */
    private fun looksSecretValue(value: Any?): Boolean {
        val text = value as? String ?: return false
        return text.length >= 16 && SECRET_VALUE.matches(text)
    }

    private fun appName(context: Context): String =
        runCatching { context.applicationInfo.loadLabel(context.packageManager).toString() }.getOrDefault("Buick Link＋")

    private fun processStartWallClockMs(): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime())

    private fun formatTime(millis: Long?): String {
        if (millis == null || millis <= 0L) return "未知"
        return HUMAN.format(Date(millis))
    }

    private fun formatBytes(bytes: Long): String =
        when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = totalSeconds % 86_400 / 3_600
        val minutes = totalSeconds % 3_600 / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (days > 0) append("${days}天")
            if (days > 0 || hours > 0) append("${hours}小时")
            append("${minutes}分${seconds}秒")
        }
    }

    private fun utcOffsetText(offsetMillis: Int): String {
        val totalMinutes = offsetMillis / 60_000
        val sign = if (totalMinutes < 0) "-" else "+"
        val absMinutes = kotlin.math.abs(totalMinutes)
        return "%s%02d:%02d".format(sign, absMinutes / 60, absMinutes % 60)
    }
}
