package io.github.shaonialife.buicklinkplus.launcher.settings

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.shaonialife.buicklinkplus.compat.Shell
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.util.RequestEpoch
import io.github.shaonialife.buicklinkplus.util.shellQuote
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一管理应用横屏设置，仅在连接期间写入系统，会话结束后恢复
 * 写入和恢复共用单线程队列，避免顺序交叉，也不阻塞连接流程
 * 使用 OVERRIDE_UNDEFINED_ORIENTATION_TO_NOSENSOR 和 OVERRIDE_ANY_ORIENTATION
 */
object AppOrientationCompat {
    const val CHANGE_UNDEFINED_TO_NOSENSOR = 265451093
    const val CHANGE_ANY_ORIENTATION = 265464455

    /** 强制横屏最低支持 Android 14，更早版本没有对应的标准 ChangeId */
    const val MIN_SUPPORTED_SDK = 34
    private const val PREFS = "app_orientation"
    private val requestSequence = AtomicLong()
    private val latestRequests = ConcurrentHashMap<String, Long>()
    private val touchedPackages = ConcurrentHashMap.newKeySet<String>()
    private val sessions = RequestEpoch()

    /** 按显示屏和会话代次隔离后台任务，避免重连复用屏号时误写 */
    @ConsistentCopyVisibility
    data class SessionToken internal constructor(
        val generation: Long,
        val displayId: Int,
    )

    @Volatile
    private var activeSession: SessionToken? = null

    @Volatile
    private var worker: Thread? = null

    /** 串行执行器：复原与写入严格按调用顺序执行，且不占用连接/UI 线程。 */
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "orientation-compat").apply { isDaemon = true }.also { worker = it }
        }

    /** 后台同步入口也排入同一队列，清理和补写不能绕过异步设置的执行顺序 */
    private fun <T> runSerial(action: () -> T): T =
        if (Thread.currentThread() === worker) action() else executor.submit(java.util.concurrent.Callable { action() }).get()

    /** 捕获当前显示会话，延迟探测与设置任务均使用同一凭据 */
    fun captureSession(): SessionToken = activeSession ?: SessionToken(-1, -1)

    /** 释放虚拟屏前先撤销后台写入与探测的所有权，不等待正在执行的系统命令 */
    @Synchronized
    fun invalidateSession() {
        activeSession = null
        sessions.advance()
    }

    /** 会话关闭、重连或释放显示屏后，旧任务立即失去写入资格 */
    fun isCurrent(session: SessionToken): Boolean =
        session.displayId >= 0 &&
            activeSession == session &&
            sessions.owns(session.generation) &&
            VirtualLauncher.appDisplayId == session.displayId

    /** 按包名存取偏好，读取时兼容旧组件键 */
    private fun key(pkg: String) = "force_$pkg"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(
        context: Context,
        pkg: String,
    ): Boolean = prefs(context).getBoolean(key(pkg), false)

    /** 用户配置与系统执行结果分开，单个目标失败不代表整机不支持 */
    enum class WriteResult {
        /** 偏好已保存，当前不在原车机会话中，不修改手机上的目标应用 */
        SAVED,

        /** 两个 ChangeId 都写入成功。 */
        OK,

        /** 当前目标的系统覆盖被拒绝，配置保留，不据此禁用其他应用 */
        UNSUPPORTED,

        /** 临时失败（shell 能力不可用/超时/命令异常）——可重试。 */
        FAILED,
    }

    /** UI 使用的异步设置入口，按点击顺序串行写入，结果回主线程 */
    fun setEnabledAsync(
        context: Context,
        pkg: String,
        enable: Boolean,
        onResult: (WriteResult) -> Unit,
    ) {
        val app = context.applicationContext
        val session = captureSession()
        val request = requestSequence.incrementAndGet()
        latestRequests[pkg] = request
        // 先保存意愿，列表回收或退出页面不影响配置；未连接时不要求 shell 能力在线
        prefs(app).edit {
            putBoolean(key(pkg), enable)
        }
        executor.execute {
            if (latestRequests[pkg] != request) return@execute
            val result =
                if (!isCurrent(session)) {
                    WriteResult.SAVED
                } else {
                    runCatching {
                        setSystemEnabled(pkg, enable) {
                            isCurrent(session) && latestRequests[pkg] == request && isEnabled(app, pkg) == enable
                        }
                    }.getOrElse { WriteResult.FAILED }
                }
            android.os
                .Handler(android.os.Looper.getMainLooper())
                .post {
                    if (latestRequests[pkg] == request) onResult(if (isCurrent(session)) result else WriteResult.SAVED)
                }
        }
    }

    /** 按仍有效的偏好补写，不重新保存已关闭的开关 */
    fun applySavedForSession(
        context: Context,
        pkg: String,
        session: SessionToken,
    ): WriteResult {
        val app = context.applicationContext
        return runSerial { setSystemEnabled(pkg, true) { isCurrent(session) && isEnabled(app, pkg) } }
    }

    /** 仅串行队列调用，每次阻塞命令前复核会话与最新偏好，部分写入仍保留清理记录 */
    private fun setSystemEnabled(
        pkg: String,
        enable: Boolean,
        current: () -> Boolean,
    ): WriteResult {
        if (!current()) return WriteResult.SAVED
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) return WriteResult.UNSUPPORTED
        val action = if (enable) "enable" else "disable"
        val argument = shellQuote(pkg)
        val first = Shell.exec("am compat $action $CHANGE_UNDEFINED_TO_NOSENSOR $argument 2>&1", timeoutMs = 4000)
        if (first.ok) touchedPackages.add(pkg)
        if (!current()) return WriteResult.SAVED
        val second =
            if (first.ok && !isPermanentRejection(first.output)) {
                Shell.exec("am compat $action $CHANGE_ANY_ORIENTATION $argument 2>&1", timeoutMs = 4000)
            } else {
                // 第一条就被拒时不再写第二条（同一限制，省一次无谓的 shell 往返）
                first
            }
        if (!current()) return WriteResult.SAVED
        val unsupported = isPermanentRejection(first.output) || isPermanentRejection(second.output)
        if (first.ok && second.ok && !unsupported) {
            if (!enable) touchedPackages.remove(pkg)
            return WriteResult.OK
        }
        val failed = if (first.ok) second else first
        diagSink?.invoke(
            "ORIENTATION: compat $action " +
                (if (unsupported) "该应用的系统覆盖被拒绝" else "暂未生效") +
                " pkg=$pkg exit=${failed.exitCode} out=${failed.output.take(240)}（用户配置已保存）",
        )
        return if (unsupported) WriteResult.UNSUPPORTED else WriteResult.FAILED
    }

    /** 投屏布局仅使用已确认支持的能力，未知时等待 shell 能力恢复后重新检查 */
    fun changeIdsRegistered(): Boolean = capability() == OrientationCapability.SUPPORTED

    /** 查询保留退出码，仅明确支持时缓存，权限错误可重试 */
    fun capability(): OrientationCapability {
        if (Build.VERSION.SDK_INT < MIN_SUPPORTED_SDK) return OrientationCapability.UNSUPPORTED
        if (cachedSupported) return OrientationCapability.SUPPORTED
        val result = Shell.exec("dumpsys platform_compat 2>&1", timeoutMs = 6_000L)
        return classifyOrientationCapability(Build.VERSION.SDK_INT, result.ok, result.output).also {
            if (it == OrientationCapability.SUPPORTED) cachedSupported = true
        }
    }

    @Volatile
    private var cachedSupported = false

    /** 判断 ChangeId 未注册或 user build 禁止覆盖等永久失败，命中后不再重试 */
    private fun isPermanentRejection(output: String): Boolean = classifyCompatFailure(output) == WriteResult.UNSUPPORTED

    /** 诊断日志出口（SessionController.appendLog 接到文件日志）。 */
    @Volatile
    var diagSink: ((String) -> Unit)? = null

    /** 异步写入会话横屏设置，完成后回调日志 */
    @Synchronized
    fun applyAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        val session = SessionToken(sessions.advance(), VirtualLauncher.appDisplayId)
        activeSession = session
        executor.execute {
            val count = runCatching { applyAll(app, session) }.getOrDefault(0)
            if (count > 0 && isCurrent(session)) log("ORIENTATION: 已为 $count 个应用启用强制横屏（会话期间生效）")
        }
    }

    /** 异步恢复横屏设置，不阻塞后续配对 */
    @Synchronized
    fun resetAllAsync(
        context: Context,
        log: (String) -> Unit,
    ) {
        val app = context.applicationContext
        // 排队之前先使旧任务失效，正在执行的任务也会在下一次命令前停止
        invalidateSession()
        executor.execute {
            val count = runCatching { resetAllNow(app) }.getOrDefault(0)
            if (count > 0) log("ORIENTATION: 已复原 $count 个应用的显示设置")
        }
    }

    /**
     * 等待方向设置队列清空，最多 [timeoutMs]
     * 启动应用前调用，避免刚启动就被 am compat 写入杀掉
     */
    fun awaitPending(timeoutMs: Long = 6_000L) {
        val done = java.util.concurrent.CountDownLatch(1)
        executor.execute { done.countDown() }
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 逐个写入名单内应用的横屏覆盖，返回成功数量 */
    private fun applyAll(
        context: Context,
        session: SessionToken,
    ): Int {
        if (!isCurrent(session)) return 0
        val pkgs = enabledPkgs(context)
        if (pkgs.isEmpty()) return 0
        // 先缓存平台能力检查结果，避免对不支持的系统逐个执行 shell 命令
        val capability = capability()
        if (capability != OrientationCapability.SUPPORTED) {
            diagSink?.invoke(
                "ORIENTATION: " + (if (capability == OrientationCapability.UNKNOWN) "横屏能力尚未确认，shell 能力恢复后可重试" else "当前平台缺少横屏兼容能力") +
                    "（名单 ${pkgs.size} 个应用，配置保留）",
            )
            return 0
        }
        var count = 0
        for (pkg in pkgs) {
            if (!isCurrent(session)) break
            // 单个目标被系统拒绝不能推断整台手机或其他应用不支持
            if (setSystemEnabled(pkg, true) { isCurrent(session) && isEnabled(context, pkg) } == WriteResult.OK) count++
        }
        return count
    }

    /** 恢复会话横屏覆盖并保留偏好，返回处理数量 */
    fun resetAll(context: Context): Int {
        val app = context.applicationContext
        // 启动残留检查可能晚于新连接完成，不复原当前正常会话的覆盖
        return runSerial { if (VirtualLauncher.appDisplayId >= 0) 0 else resetAllNow(app) }
    }

    /** 串行队列中的会话清理，不改用户偏好，也不遗漏只写入一个兼容项的目标 */
    private fun resetAllNow(context: Context): Int {
        var count = 0
        (enabledPkgs(context) + touchedPackages).distinct().forEach { pkg ->
            if (clearSystemNow(pkg)) count++
        }
        return count
    }

    /**
     * 读取系统实际开启的覆盖键，包括 packageOverrides 和卸载后残留的 rawOverrides
     * 任一 ChangeId 开启即计入；面板打开时后台查询一次，列表行只读缓存
     */
    fun systemEnabled(): Set<String> = readSystemEnabled().orEmpty()

    /** 查询失败返回未知，避免把未授权误认为没有系统残留 */
    fun readSystemEnabled(): Set<String>? {
        val result = Shell.exec("dumpsys platform_compat 2>&1", timeoutMs = 6_000L)
        if (!result.ok) return null
        if (classifyOrientationCapability(Build.VERSION.SDK_INT, result.ok, result.output) == OrientationCapability.UNKNOWN) return null
        val out = result.output
        return out
            .lineSequence()
            .filter { it.contains("ChangeId($CHANGE_UNDEFINED_TO_NOSENSOR;") || it.contains("ChangeId($CHANGE_ANY_ORIENTATION;") }
            .flatMap { line ->
                // packageOverrides={com.a=true, ...}；rawOverrides 里还可能有 包/组件 键
                Regex("(?:packageOverrides|rawOverrides)=\\{([^}]*)\\}")
                    .findAll(line)
                    .flatMap { m ->
                        // 键容许多数可打印字符（含 / 的组件键）；只取 =true
                        Regex("([^=,{}\\s]+)=true").findAll(m.groupValues[1]).map { it.groupValues[1] }
                    }
            }.toSet()
    }

    /** 检查系统是否真的开启方向覆盖，不能只看用户偏好 */
    fun isSystemEnabledFor(pkg: String): Boolean = pkg in systemEnabled()

    /**
     * 关闭指定键的两个系统覆盖，保留用户偏好；am compat 会停止目标应用
     * 修改名单内应用时使用 [setEnabledAsync]
     */
    fun clearSystemOnly(
        key: String,
        stillAllowed: () -> Boolean = { true },
    ): Boolean = runSerial { if (stillAllowed()) clearSystemNow(key) else false }

    /** 仅在串行队列中撤销系统覆盖，系统返回拒绝文案时不能当作成功 */
    private fun clearSystemNow(key: String): Boolean {
        val argument = shellQuote(key)
        val first = Shell.exec("am compat disable $CHANGE_UNDEFINED_TO_NOSENSOR $argument 2>&1", timeoutMs = 4000)
        val second = Shell.exec("am compat disable $CHANGE_ANY_ORIENTATION $argument 2>&1", timeoutMs = 4000)
        val cleared = first.ok && second.ok && !isPermanentRejection(first.output) && !isPermanentRejection(second.output)
        return cleared.also { if (it) touchedPackages.remove(key) }
    }

    private fun enabledPkgs(context: Context): List<String> =
        prefs(context)
            .all
            .filterValues { it == true }
            .keys
            .map { it.removePrefix("force_") }

    /** 「应用横屏」名单（包名集合）：letterbox 提示探测用，只读。 */
    fun enabledPackages(context: Context): Set<String> = enabledPkgs(context).toSet()
}

/** 将 am compat 失败分为临时和永久错误；ChangeId 不存在或 user build 限制均不可重试 */
internal fun classifyCompatFailure(output: String): AppOrientationCompat.WriteResult =
    if (output.contains("non-debuggable app and user build") ||
        output.contains("default enabled/disabled change on a user build") ||
        output.contains("is not known yet")
    ) {
        AppOrientationCompat.WriteResult.UNSUPPORTED
    } else {
        AppOrientationCompat.WriteResult.FAILED
    }
