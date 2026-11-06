package io.github.shaonialife.buicklinkplus.carlife

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.capture.CarLifeScreenSource
import io.github.shaonialife.buicklinkplus.launcher.settings.CarLifePrefs
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.session.SessionController
import java.util.concurrent.Executors

/**
 * CarLife **自动接入**（Auto Attach）：CarLife+ 里点「无线连接」后，用户切回本应用即自动接管，
 * 不需要再回应用里点「启动连接」。
 *
 * ## 触发方式（无需改 MainActivity）
 *
 * 由 [SessionController.ensure] 调用 [install] 注册 `Application.ActivityLifecycleCallbacks`：
 * - 任意 Activity 回到前台（含冷启动、从 CarLife+ 切回来）→ 立刻探测一次 `127.0.0.1:7240`；
 * - 前台期间每 [POLL_INTERVAL_MS]（1.5s）轮询一次，直到会话启动或应用退到后台；
 * - 应用退到后台自动暂停轮询，回前台恢复（并清掉「手动断开后不自动接入」的抑制）。
 *
 * ## 为什么要轮询端口（而不是监听 CarLife 进程）
 *
 * 无 ADB/root 的普通应用在 Android 10+ 无法枚举/监听其它应用进程，只能探测本机回环端口；
 * CarLife+ 进入「无线连接」后 7240 才监听——端口一开即触发自动接入（与参考实现「提前等待 7240」
 * 的行为一致，但用无 ADB 的端口探测实现）。
 *
 * ## 约束
 *
 * - 受 [CarLifePrefs.autoAttachEnabled] 开关控制（默认开）；关闭后只手动点「启动连接」。
 * - 用户**手动断开**后调用 [suppress]：不会立刻又自动连；直到用户再点「启动连接」或
 *   应用真正退到后台一段时间再回到前台，才重新允许一次自动接入。
 * - 自动接入仍走正常 [SessionController.startConnection]（含权限 preflight），不绕过权限检查；
 *   准备未就绪时只写日志并停止轮询，等用户手动启动。
 * - 日志前缀：[自动接入] `CARLIFE-AUTO:`；成功接入的关键行是
 *   `CARLIFE: 检测到 CarLife+ 无线连接，自动接入车机`。
 */
object CarLifeAutoAttach {
    /** 前台轮询间隔（毫秒）。 */
    private const val POLL_INTERVAL_MS = 1_500L

    /** 单次端口探测超时（毫秒）：只探 7240，快速失败。 */
    private const val PORT_PROBE_TIMEOUT_MS = 350

    /** 「等待 CarLife+ 无线连接」日志限流（毫秒）。 */
    private const val WAIT_LOG_INTERVAL_MS = 10_000L

    /** 「暂不自动接入」日志限流（毫秒）。 */
    private const val SKIP_LOG_INTERVAL_MS = 10_000L

    /** 判定「用户确实离开过应用」的最短后台时长（毫秒）：避免 Activity 切换误清手动断开抑制。 */
    private const val MIN_BACKGROUND_MS = 800L

    private val lock = Any()

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "carlife-auto-attach").apply { isDaemon = true }
        }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var foreground = false

    @Volatile
    private var polling = false

    /** 手动断开后的抑制：置位后不再自动接入，直到手动启动或真正回到前台一次。 */
    @Volatile
    private var suppressed = false

    /** 当前 startConnection 是否由自动接入发起（用于区分手动/自动启动）。 */
    @Volatile
    private var autoStarting = false

    @Volatile
    private var pollToken = 0

    private var resumedActivities = 0
    private var backgroundAt = 0L
    private var lastWaitLogAt = 0L
    private var lastSkipLogAt = 0L

    /** 注册 Activity 生命周期回调（幂等；[SessionController.ensure] 已自动调用）。 */
    fun install(context: Context) {
        val app = context.applicationContext ?: context
        synchronized(lock) {
            appContext = app
            if (installed) return
            installed = true
        }
        val application = app as? Application
        if (application == null) {
            log("CARLIFE-AUTO: 无法注册 Activity 生命周期（上下文不是 Application），自动接入仅在手动触发时可用")
            return
        }
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityResumed(activity: Activity) {
                    val returning =
                        synchronized(lock) {
                            val wasBackground = resumedActivities == 0
                            resumedActivities += 1
                            wasBackground
                        }
                    onAppForeground(activity.applicationContext, returningFromBackground = returning)
                }

                override fun onActivityPaused(activity: Activity) {
                    val background =
                        synchronized(lock) {
                            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
                            resumedActivities == 0
                        }
                    if (background) onAppBackground()
                }

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        log("CARLIFE-AUTO: 自动接入已安装（回前台检测 127.0.0.1:${CarLifeProtocol.PORT_CMD}，开关=${isEnabled(app)}）")
    }

    /**
     * Activity 回到前台（冷启动/从 CarLife+ 切回来）：立刻探测一次，之后前台每 1.5s 轮询。
     *
     * @param returningFromBackground true = 用户刚离开过应用再回来（会解除手动断开后的抑制）。
     */
    fun onAppForeground(
        context: Context,
        returningFromBackground: Boolean = true,
    ) {
        install(context)
        val app = context.applicationContext ?: context
        appContext = app
        foreground = true
        val now = SystemClock.uptimeMillis()
        val awayMs =
            synchronized(lock) {
                val since = backgroundAt
                backgroundAt = 0L
                if (since <= 0L) Long.MAX_VALUE else now - since
            }
        if (returningFromBackground && awayMs >= MIN_BACKGROUND_MS && suppressed) {
            suppressed = false
            log("CARLIFE-AUTO: 用户回到应用，解除手动断开后的自动接入抑制（允许再自动接入一次）")
        }
        if (!isEnabled(app)) {
            logSkip("自动接入开关已关闭（可在 CarLife 设置里打开）")
            return
        }
        if (SessionController.isSessionRunning()) {
            // 会话已运行但 CarLife 引擎还没接上（常见：先点了「启动连接」、之后才去 CarLife+ 点无线连接）：
            // 回到前台立刻打断等待重建一轮，避免用户以为「不会自动接入」。
            val carLifeSource = SessionController.currentScreenSource as? CarLifeScreenSource
            if (carLifeSource != null && (carLifeSource.isWaitingEngine || !carLifeSource.isEngineConnected)) {
                log("CARLIFE-AUTO: 会话已运行但 CarLife 引擎未连接——回到前台立即触发一次重试")
                SessionController.retryCarLifeSource()
            } else {
                log("CARLIFE-AUTO: 会话已在运行，无需自动接入")
            }
            return
        }
        if (SessionController.isSessionStarting()) {
            log("CARLIFE-AUTO: 会话正在建立，跳过本轮自动接入")
            return
        }
        startPolling()
    }

    /** 应用退到后台：暂停自动接入轮询（回前台自动恢复）。 */
    fun onAppBackground() {
        foreground = false
        synchronized(lock) {
            if (backgroundAt <= 0L) backgroundAt = SystemClock.uptimeMillis()
        }
        stopPolling("应用退到后台，暂停自动接入轮询")
    }

    /** 主动开启自动接入（可选；正常由 Activity 生命周期自动触发）。 */
    fun start(context: Context) {
        install(context)
        onAppForeground(context, returningFromBackground = false)
    }

    /** 主动停止自动接入轮询。 */
    fun stop(reason: String = "调用方停止自动接入") {
        foreground = false
        stopPolling(reason)
    }

    /** 自动接入开关（CarLifePrefs，默认开）。 */
    fun isEnabled(context: Context): Boolean = CarLifePrefs.autoAttachEnabled(context)

    /** 是否正在前台轮询等待 CarLife+ 端口。 */
    fun isPolling(): Boolean = polling

    /** 本次 startConnection 是否由自动接入发起（SessionController 用来区分手动/自动）。 */
    fun isAutoStarting(): Boolean = autoStarting

    /** 用户手动断开：抑制自动接入，避免断开后立刻又连上。 */
    fun suppress() {
        suppressed = true
        stopPolling("用户手动断开，抑制自动接入避免立刻重连")
        log("CARLIFE-AUTO: 已抑制自动接入（点「启动连接」或从后台切回前台后恢复）")
    }

    /** 用户手动点「启动连接」：清除抑制并停止轮询（由 SessionController.startConnection 调用）。 */
    fun onManualStart() {
        suppressed = false
        stopPolling("用户手动启动连接")
    }

    /**
     * 由 UI/其它入口手动触发一次自动接入检测。
     *
     * 返回 true = 已排入探测（不代表一定接入成功，结果看日志）；false = 开关关闭/已连接/
     * 正在建立/刚手动断开抑制中。
     */
    fun tryAutoAttachNow(context: Context): Boolean {
        install(context)
        val app = context.applicationContext ?: context
        appContext = app
        if (!isEnabled(app)) {
            logSkip("自动接入开关已关闭，跳过手动触发")
            return false
        }
        if (suppressed) {
            logSkip("刚手动断开过，暂不自动接入（点「启动连接」或切回前台后恢复）")
            return false
        }
        if (SessionController.isSessionRunning()) {
            log("CARLIFE-AUTO: 会话已在运行，跳过自动接入")
            return false
        }
        if (SessionController.isSessionStarting()) {
            log("CARLIFE-AUTO: 正在建立连接，跳过重复自动接入")
            return false
        }
        foreground = true
        executor.execute {
            if (!probeAndMaybeAttach(app, attempt = 1)) {
                startPolling()
            }
        }
        return true
    }

    // ---- 内部 ----

    private fun startPolling() {
        val context = appContext ?: return
        val token: Int
        synchronized(lock) {
            if (polling) return
            polling = true
            token = ++pollToken
        }
        log("CARLIFE-AUTO: 开始轮询本机 CarLife 端口（每 ${POLL_INTERVAL_MS}ms，等待 CarLife+「无线连接」）")
        executor.execute {
            try {
                var attempt = 0
                while (isPollActive(token)) {
                    attempt++
                    if (probeAndMaybeAttach(context, attempt)) return@execute
                    if (!isPollActive(token)) break
                    try {
                        Thread.sleep(POLL_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            } finally {
                val current =
                    synchronized(lock) {
                        if (token == pollToken) {
                            polling = false
                            true
                        } else {
                            false
                        }
                    }
                if (current) log("CARLIFE-AUTO: 停止轮询（会话已启动/退到后台/自动接入关闭）")
            }
        }
    }

    private fun stopPolling(reason: String) {
        val wasPolling: Boolean
        synchronized(lock) {
            wasPolling = polling
            polling = false
            pollToken += 1
        }
        if (wasPolling) log("CARLIFE-AUTO: 停止轮询——$reason")
    }

    private fun isPollActive(token: Int): Boolean =
        token == pollToken &&
            foreground &&
            !suppressed &&
            !SessionController.isSessionRunning()

    /**
     * 探测 7240 并尝试自动接入。
     *
     * @return true = 本轮已结束（已触发接入/准备未就绪/开关关闭），false = 继续轮询等待。
     */
    private fun probeAndMaybeAttach(
        context: Context,
        attempt: Int,
    ): Boolean {
        if (!isEnabled(context)) {
            logSkip("自动接入开关已关闭")
            stopPolling("自动接入开关已关闭")
            return true
        }
        if (suppressed) return true
        if (SessionController.isSessionRunning()) return true
        if (SessionController.isSessionStarting()) return false
        val cmdListening = CarLifeEngineDetector.isPortOpen(CarLifeProtocol.PORT_CMD, PORT_PROBE_TIMEOUT_MS)
        if (!cmdListening) {
            logWait(attempt)
            return false
        }
        val report =
            try {
                ConnectionPreflight.inspect(context, requireAudio = false)
            } catch (error: Throwable) {
                log("CARLIFE-AUTO: 连接准备检查异常 - ${error.message}")
                null
            }
        if (report == null) {
            stopPolling("连接准备检查异常")
            return true
        }
        if (!report.ready) {
            val missing = report.missing.joinToString("、") { it.requirement.title }
            logSkip("检测到 CarLife+ 端口已监听，但连接准备未就绪（缺少：$missing）——请先完成应用内权限/车机设置，或手动点「启动连接」")
            stopPolling("连接准备未就绪")
            return true
        }
        log("CARLIFE: 检测到 CarLife+ 无线连接，自动接入车机")
        log("CARLIFE-AUTO: 自动接入开始（7240 已监听，启动车机会话）")
        stopPolling("已触发自动接入")
        autoStarting = true
        mainHandler.post {
            try {
                SessionController.startConnection(context)
            } finally {
                autoStarting = false
            }
        }
        return true
    }

    private fun logWait(attempt: Int) {
        val now = SystemClock.uptimeMillis()
        if (now - lastWaitLogAt < WAIT_LOG_INTERVAL_MS) return
        lastWaitLogAt = now
        log(
            "CARLIFE-AUTO: 等待 CarLife+ 无线连接（第 $attempt 次探测，127.0.0.1:${CarLifeProtocol.PORT_CMD}；" +
                "在手机 CarLife+ 点「无线连接」后切回本应用会自动接入）",
        )
    }

    private fun logSkip(reason: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSkipLogAt < SKIP_LOG_INTERVAL_MS) return
        lastSkipLogAt = now
        log("CARLIFE-AUTO: 暂不自动接入——$reason")
    }

    private fun log(message: String) {
        SessionController.appendLog(message)
    }
}
