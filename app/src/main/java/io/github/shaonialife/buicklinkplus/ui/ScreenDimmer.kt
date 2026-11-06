package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.shaonialife.buicklinkplus.shizuku.Shell

/**
 * 可选的常亮和暗幕模式，连接中保持亮屏，断开后恢复超时
 * 无操作 45 秒后盖近黑浮层，触摸后移除；1px 探针只观察触摸，不消费事件
 */
// 进程级浮窗只使用 applicationContext，detach 移除探针和暗幕并清空引用
@android.annotation.SuppressLint("StaticFieldLeak")
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val TIMEOUT_MAX = 2_147_483_647
    private val timeoutGeneration =
        java.util.concurrent.atomic
            .AtomicLong()
    private val timeoutExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { work ->
            Thread(work, "dimmer-timeout").apply { isDaemon = true }
        }

    /** 新用户默认跟随系统真实息屏，保留既有明确偏好 */
    @Volatile
    var enabled: Boolean = false

    private var appContext: Context? = null
    private var windowManager: WindowManager? = null
    private var handler: Handler? = null
    private var dimView: View? = null
    private var watchView: View? = null

    @Volatile
    private var dimShown = false

    /** 自动暗幕和常亮只在会话活跃时生效，手动 [enterDimNow] 不受限制 */
    @Volatile
    private var sessionActive = false

    private val showRunnable = Runnable { showDim() }

    /** 会话开始/结束（由 SessionController 在推流起停时调用）。 */
    fun setSessionActive(active: Boolean) {
        if (sessionActive == active) return
        sessionActive = active
        val ctx = appContext ?: return
        val h = handler
        if (active) {
            if (!enabled) return
            enableStayAwake(ctx)
            h?.post { schedule() }
            Log.i("CarPlaySender", "DIMMER: 会话开始——常亮/自动暗幕生效（${IDLE_TIMEOUT_MS / 1000}s 无操作后变暗）")
        } else {
            h?.removeCallbacks(showRunnable)
            // 会话结束时如果暗幕正盖着（车机自己断开），立即撤掉，让用户看到手机正常界面
            h?.post { hideDim() }
            restoreTimeout(ctx)
            Log.i("CarPlaySender", "DIMMER: 会话结束——自动暗幕已停、屏幕超时已恢复")
        }
    }

    fun attach(context: Context) {
        if (!enabled) restoreTimeout(context.applicationContext)
        if (appContext != null) {
            // 已附着：本次若为「启用」切换，补齐常亮与自动计时（触摸探针此前已挂）
            if (enabled && sessionActive) {
                enableStayAwake(context)
                handler?.post { schedule() }
            }
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "缺少悬浮窗权限，暗幕/触摸探针不可用（常亮不可用）")
            appContext = context.applicationContext
            windowManager = null
            handler = Handler(Looper.getMainLooper())
            return
        }
        appContext = context.applicationContext
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        // 常亮只在会话期间打开：应用启动时 attach 不把屏幕超时改成「永不」，
        // 否则未连接时手机也永不熄屏（车机断开后同样收不回来）。
        if (enabled && sessionActive) enableStayAwake(context)
        handler?.post {
            addWatcher()
            if (enabled && sessionActive) schedule()
        }
        if (!enabled) {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（常亮/暗幕关闭）")
        } else if (sessionActive) {
            Log.i("CarPlaySender", "DIMMER: 已启动（常亮 + ${IDLE_TIMEOUT_MS / 1000}s 无操作后暗幕，点击恢复）")
        } else {
            Log.i("CarPlaySender", "DIMMER: 触摸探针已启动（未在投屏——常亮/自动暗幕待会话开始后生效）")
        }
    }

    /** 关闭常亮/暗幕但保留触摸探针（重新开启时无需重建窗口）。 */
    fun disableDim() {
        enabled = false
        val ctx = appContext ?: return
        restoreTimeout(ctx)
        handler?.removeCallbacks(showRunnable)
        handler?.post { hideDim() }
        Log.i("CarPlaySender", "DIMMER: 常亮/暗幕已关闭（触摸探针保留）")
    }

    /** 根据开关、会话和挂载状态判断是否启用自动暗幕 */
    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
    ): Boolean = enabled && sessionActive && attached

    fun detach() {
        val h = handler
        handler = null
        // 会话标记必须一并清零：否则下次 attach（未连接时启动 App）会沿用上一轮的
        // true 而立即排程，又变回「没会话也自动变暗」的老问题。
        sessionActive = false
        h?.removeCallbacksAndMessages(null)
        val wm = windowManager
        val dim = dimView
        val watch = watchView
        dimView = null
        watchView = null
        dimShown = false
        UiAppearance.refreshWindows()
        windowManager = null
        h?.post {
            dim?.let { v -> runCatching { wm?.removeView(v) } }
            watch?.let { v -> runCatching { wm?.removeView(v) } }
        }
        val ctx = appContext
        appContext = null
        if (ctx != null) restoreTimeout(ctx)
        Log.i("CarPlaySender", "DIMMER: 已停止（屏幕超时恢复）")
    }

    /** 暗幕是否已就绪（attach 完成且具备悬浮窗能力）。 */
    fun isAttached(): Boolean = handler != null && windowManager != null

    /**
     * 兼容暗屏显示时，自有手机窗口的系统栏同步保持黑色
     */
    fun isShowing(): Boolean = dimShown

    /** 手动进入假息屏（主页面按钮触发；点击暗幕或手机屏幕任意处恢复）。 */
    fun enterDimNow() {
        val h = handler ?: return
        // attach 时缺权限、之后才授予的：此处补建窗口管理器与触摸探针
        if (windowManager == null) {
            val ctx = appContext ?: return
            if (!android.provider.Settings.canDrawOverlays(ctx)) return
            windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            h.post { addWatcher() }
        }
        // 手动假息屏要有意义，屏幕必须保持常亮（否则真睡下去就没法「点击恢复」）
        appContext?.let { if (enabled) enableStayAwake(it) }
        h.post { showDim(force = true) }
    }

    /** 物理触摸（任意 app/界面）或暗幕点击时调用：重置自动暗幕计时。 */
    fun onUserInteraction() {
        val h = handler ?: return
        if (dimShown) hideDim()
        if (enabled && sessionActive) schedule()
    }

    private fun schedule() {
        if (!sessionActive) return
        handler?.removeCallbacks(showRunnable)
        handler?.postDelayed(showRunnable, IDLE_TIMEOUT_MS)
    }

    private fun showDim(force: Boolean = false) {
        // 自动路径必须有活跃会话：会话已结束（尤其车机侧断开）时绝不弹暗幕
        if (!force && !shouldAutoDim(enabled, sessionActive, isAttached())) return
        val ctx = appContext
        if (ctx == null) {
            android.util.Log.w("ScreenDimmer", "showDim: appContext 为空（attach 未执行）")
            return
        }
        val wm = windowManager
        if (wm == null) {
            android.util.Log.w("ScreenDimmer", "showDim: windowManager 为空（悬浮窗权限缺失？）")
            return
        }
        if (dimShown) return
        val view =
            View(ctx).apply {
                setBackgroundColor(Color.argb(0xF8, 0, 0, 0))
                isClickable = true
                setOnClickListener { onUserInteraction() }
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                dimView = view
                dimShown = true
                UiAppearance.refreshWindows()
                Log.i("CarPlaySender", "DIMMER: 暗幕已显示")
            }.onFailure { Log.w(TAG, "暗幕添加失败：${it.message}") }
    }

    private fun hideDim() {
        val view = dimView ?: return
        dimView = null
        dimShown = false
        UiAppearance.refreshWindows()
        runCatching { windowManager?.removeView(view) }
    }

    /** 1px 触摸探针：捕获屏幕上任意处的 DOWN 事件用于重置计时，不消费事件。 */
    // 仅观察 DOWN 和 OUTSIDE 并返回 false，不消费手势或产生点击动作
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun addWatcher() {
        val ctx = appContext ?: return
        val wm = windowManager ?: return
        val view =
            View(ctx).apply {
                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN ||
                        event.action == MotionEvent.ACTION_OUTSIDE
                    ) {
                        onUserInteraction()
                    }
                    false
                }
            }
        val params =
            WindowManager
                .LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
        runCatching { wm.addView(view, params) }
            .onSuccess { watchView = view }
            .onFailure { Log.w(TAG, "触摸探针添加失败：${it.message}") }
    }

    // ---- 屏幕常亮：改系统屏幕超时为永不，断开时恢复 ----

    // 后台保存屏幕超时备份并确认落盘后才写系统超时
    @android.annotation.SuppressLint("UseKtx")
    private fun enableStayAwake(ctx: Context) {
        val generation = timeoutGeneration.incrementAndGet()
        timeoutExecutor.execute {
            if (generation != timeoutGeneration.get()) return@execute
            val current =
                Shell
                    .exec("settings get system screen_off_timeout")
                    .output
                    .trim()
                    .toIntOrNull()
            if (current == null) {
                Log.w(TAG, "读取屏幕超时失败（内置服务未就绪？）")
                return@execute
            }
            if (generation != timeoutGeneration.get()) return@execute
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (current < TIMEOUT_MAX && !prefs.contains("screen_off_timeout_backup")) {
                if (!prefs.edit().putInt("screen_off_timeout_backup", current).commit()) {
                    Log.w(TAG, "保存屏幕超时失败，不修改系统超时")
                    return@execute
                }
            }
            Shell.exec("settings put system screen_off_timeout $TIMEOUT_MAX")
            Log.i("CarPlaySender", "DIMMER: 屏幕超时设为永不（原值 $current）")
        }
    }

    /** 进程重新启动时恢复上次未完成的屏幕超时修改 */
    fun recoverTimeout(context: Context) {
        if (!sessionActive) restoreTimeout(context.applicationContext)
    }

    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    private fun restoreTimeout(ctx: Context) {
        val generation = timeoutGeneration.incrementAndGet()
        timeoutExecutor.execute {
            if (generation != timeoutGeneration.get()) return@execute
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (!prefs.contains("screen_off_timeout_backup")) return@execute
            val backup = prefs.getInt("screen_off_timeout_backup", 60_000)
            val result = Shell.exec("settings put system screen_off_timeout $backup")
            val actual =
                Shell
                    .exec("settings get system screen_off_timeout")
                    .output
                    .trim()
                    .toIntOrNull()
            if (result.ok && actual == backup) {
                prefs.edit().remove("screen_off_timeout_backup").commit()
                Log.i("CarPlaySender", "DIMMER: 屏幕超时已恢复")
            } else {
                Log.w(TAG, "屏幕超时恢复未通过回读，保留备份")
            }
        }
    }
}
