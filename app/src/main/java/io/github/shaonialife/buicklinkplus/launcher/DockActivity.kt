package io.github.shaonialife.buicklinkplus.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/**
 * 独立虚拟屏中的 Dock，显示时钟、电量和运行应用
 * 屏高大于宽时使用左侧竖栏，否则使用底部横栏
 */
class DockActivity : GlassActivity() {
    override val glassBackdropEnabled = false

    /**
     * 原位更新侧栏遮罩，保持透明度和模糊偏好
     */
    override fun onAppearanceChanged() {
        setupWallpaper()
        applyDockPrefs()
        refreshSelection()
    }

    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var signalGroup: LinearLayout? = null
    private var signalIcon: ImageView? = null
    private var signalText: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBar: View? = null
    private var batteryFill: View? = null
    private var barForm = false
    private val signalRefreshing =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /**
     * 相对 420dpi 设计基准的倍率，随画布短边缩放
     * 布局用 dp，代码中的字号和模糊半径按设计像素乘此倍率
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }
    private val displayListener: () -> Unit = { runOnUiThread { if (!isDestroyed) refreshSelection() } }
    private val appIcons = linkedMapOf<android.content.ComponentName, ImageView>()

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                VirtualLauncher.refreshDisplayedContent()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // 无秒显示（用户要求）：对齐到下一分钟边界刷新，秒内不做无用重绘
                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** 壁纸背景（全流画布切片，与桌面同源同路径——交界处逐像素连续）。 */
    private var wallpaperView: ImageView? = null
    private val wallpaperRequests =
        io.github.shaonialife.buicklinkplus.ui
            .WallpaperRequestState()
    private val dashboardListener: (Boolean) -> Unit = { dashboard ->
        runOnUiThread {
            findViewById<ImageView>(R.id.dockHome)?.setAppearanceImageResource(
                if (dashboard) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }
    }

    /** 壁纸变更监听（WallpaperStore 主线程回调；解码在本类自行切后台线程）。 */
    private val wallpaperListener = { _: Long -> refreshWallpaperIfChanged() }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)
        clockView = findViewById(R.id.dockClock)
        signalGroup = findViewById(R.id.dockSignal)
        signalIcon = findViewById(R.id.dockSignalIcon)
        signalText = findViewById(R.id.dockSignalText)
        batteryText = findViewById(R.id.dockBatteryText)
        batteryBar = findViewById(R.id.dockBatteryBar)
        batteryFill = findViewById(R.id.dockBatteryFill)
        appsContainer = findViewById(R.id.dockApps)
        wallpaperView = findViewById(R.id.dockWallpaper)
        findViewById<ImageView>(R.id.dockHome).apply {
            // 菜单入口只保留布局中的点击反馈，不参与当前应用选中
            setOnClickListener { Bg.run("dock-home") { VirtualLauncher.onHomePressed() } }
            // 图标随模式切换：仪表盘激活=房屋（点按回桌面）；否则=田字格（点按进仪表盘）。
            // 应用中与桌面同为田字格——两种场景下的动作都是进仪表盘，语义一致。
            VirtualLauncher.addDashboardListener(dashboardListener)
            setAppearanceImageResource(
                if (VirtualLauncher.dashboardMode) R.drawable.ic_dock_home else R.drawable.ic_dock_dashboard,
            )
        }

        VirtualLauncher.addRunningAppsListener(refreshListener)
        VirtualLauncher.addDisplayListener(displayListener)
        // 字号按设计像素随密度缩放，窄 Dock 的时钟再由 fitClockText 缩小
        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22f * designScale)
        signalText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 9f * designScale)
        signalIcon?.let { icon ->
            // 图标与网络类型使用相同设计像素，不能把 12dp 与 9px 混用
            val size = (9f * designScale).toInt().coerceAtLeast(1)
            icon.layoutParams =
                icon.layoutParams.apply {
                    width = size
                    height = size
                }
        }
        signalIcon?.setAppearanceImageTint(R.color.m3_on_var)
        signalText?.setAppearanceTextColor(R.color.m3_on_var)
        batteryText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * designScale)
        clockView?.post { fitClockText(clockView!!) }
        refreshSignal()
        setupWallpaper()
        // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // 侧栏外观应用当前遮罩和模糊偏好，并监听设置页调整
        applyDockPrefs()
        // 系统受保护广播：ACTION_BATTERY_CHANGED 为 sticky，注册即回填当前电量
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        wallpaperRequests.cancel()
        wallpaperView?.setImageDrawable(null)
        VirtualLauncher.removeDashboardListener(dashboardListener)
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        VirtualLauncher.removeDisplayListener(displayListener)
        appIcons.clear()
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** 侧栏可见时才刷新任务和分钟时钟 */
    override fun onStart() {
        super.onStart()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.post(clockTicker)
    }

    /**
     * 仅停止窗口轮询，不停止导航、媒体或连接保活
     */
    override fun onStop() {
        pollHandler.removeCallbacks(pollRunnable)
        clockHandler.removeCallbacks(clockTicker)
        super.onStop()
    }

    /** 只缓存侧栏占用的壁纸条带；窗口保持不透明，避免混入手机系统壁纸 */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        params.leftMargin = 0
        params.topMargin = 0
        params.width = launcher.dockWidthPx
        params.height = launcher.dockHeightPx
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /** 应用侧栏遮罩和模糊，模糊半径按 designScale 缩放 */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val wallpaper = wallpaperView ?: return
        CarAppearancePrefs.applyBackdrop(wallpaper, scrim, designScale)
    }

    /** 壁纸版本变化时后台重解码并换图（监听器模式下与桌面同步更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        if (isDestroyed || isFinishing) return
        val current = WallpaperStore.version
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val targetWidth = params.width
        val targetHeight = params.height
        if (targetWidth <= 0 || targetHeight <= 0) return
        val geometry = VirtualLauncher.wallpaperGeometry
        val outline = CarAppearancePrefs.panelOutlineVisible(this)
        val area =
            geometry?.let {
                WallpaperStore.panelArea(
                    it,
                    VirtualLauncher.dockWidthPx,
                    VirtualLauncher.dockHeightPx,
                    VirtualLauncher.dockAtBottom,
                    true,
                    outline,
                )
            }
        val key = "$current:${targetWidth}x$targetHeight:$geometry:$area"
        val request = wallpaperRequests.begin(key, android.os.SystemClock.elapsedRealtime()) ?: return
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.windowBackdrop(
                        applicationContext,
                        targetWidth,
                        targetHeight,
                        targetWidth,
                        targetHeight,
                        0,
                        0,
                        geometry,
                        area,
                        false,
                    )
                }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing || !wallpaperRequests.owns(request)) return@runOnUiThread
                if (WallpaperStore.version != current ||
                    VirtualLauncher.wallpaperGeometry != geometry ||
                    CarAppearancePrefs.panelOutlineVisible(this) != outline ||
                    view.layoutParams.width != targetWidth ||
                    view.layoutParams.height != targetHeight
                ) {
                    wallpaperRequests.cancel()
                    refreshWallpaperIfChanged()
                    return@runOnUiThread
                }
                val applied = bitmap != null && runCatching { view.setImageBitmap(bitmap) }.isSuccess
                val retry = wallpaperRequests.finish(request, applied, android.os.SystemClock.elapsedRealtime())
                VirtualLauncher.logSink?.invoke("WALLPAPER-DOCK: 版本=$current 应用=$applied 重试=$retry 尺寸=${targetWidth}x$targetHeight")
                if (retry) pollHandler.postDelayed({ refreshWallpaperIfChanged() }, 500L)
            }
        }
    }

    /** 按设计倍率逐级缩小字号，直到时钟文字放得下 */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    /**
     * 后台读取蜂窝类型并合并重复刷新，无权限或未知时隐藏，不阻塞侧栏主线程
     */
    private fun refreshSignal() {
        val group = signalGroup ?: return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            group.visibility = View.GONE
            return
        }
        if (!signalRefreshing.compareAndSet(false, true)) return
        Bg.run("dock-network-type") {
            val label =
                runCatching {
                    val tm = getSystemService(TelephonyManager::class.java)
                    networkLabel(tm.dataNetworkType).ifEmpty { networkLabel(tm.voiceNetworkType) }
                }.getOrDefault("")
            runOnUiThread {
                signalRefreshing.set(false)
                if (isDestroyed || isFinishing) return@runOnUiThread
                val visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
                if (group.visibility != visibility) group.visibility = visibility
                if (signalText?.text?.toString() != label) signalText?.text = label
            }
        }
    }

    /**
     * 蜂窝网络类型 → 展示标签，未识别返回空串
     */
    private fun networkLabel(type: Int): String =
        when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            -> "3G"
            else -> ""
        }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = getString(R.string.battery_percent, if (charging) "⚡" else "", percent)
            val bar = batteryBar
            val fill = batteryFill
            if (bar != null && fill != null) {
                bar.post {
                    val width = bar.width
                    if (width > 0) {
                        fill.layoutParams =
                            (fill.layoutParams as FrameLayout.LayoutParams).apply {
                                this.width =
                                    (width * percent / 100)
                                        .coerceAtLeast((3 * resources.displayMetrics.density).toInt())
                            }
                    }
                }
                fill.setAppearanceBackground(if (percent <= 20) R.color.m3_error else R.color.m3_ok_dot)
            }
        }
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        refreshSignal()
        val container = appsContainer ?: return
        val current =
            synchronized(VirtualLauncher.runningApps) {
                VirtualLauncher.runningApps.map { it.flattenToString() }
            }
        if (current == lastRendered) {
            refreshSelection()
            return
        }
        lastRendered = current
        container.removeAllViews()
        appIcons.clear()
        val density = resources.displayMetrics.density
        val iconPx = ((if (barForm) 32 else 30) * density).toInt()
        val pad = (6 * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val drawable = iconCache[component] ?: packageManager.defaultActivityIcon
            // 位图化渲染（与桌面网格同一路径）：直接 setImageDrawable 会让 ImageView
            // 长期持有共享 Drawable 实例，其 bounds 被网格重绑改写后渲染错位
            // （实机故障：点 home 后 dock 图标变小且偏左）
            container.addView(
                ImageView(this).apply {
                    appIcons[component] = this
                    setAppearanceBackground(R.drawable.bg_dock_selection)
                    AppIcons.bind(
                        this,
                        VirtualLauncher.AppEntry(component, component.shortClassName, drawable),
                        iconPx,
                        this@DockActivity,
                    )
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component) }
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2),
            )
        }
        refreshSelection()
    }

    /** 已显示项用实线选中，等待项用虚线反馈，别名只匹配一个图标 */
    private fun refreshSelection() {
        val content = VirtualLauncher.displayedContent
        val component = content.component?.let(android.content.ComponentName::unflattenFromString)
        val pending = VirtualLauncher.pendingComponent?.let(android.content.ComponentName::unflattenFromString)
        val pendingIcon =
            pending?.let { target ->
                appIcons.keys.firstOrNull { it == target } ?: appIcons.keys.firstOrNull { it.packageName == target.packageName }
            }
        val map = content.page == DockDisplayState.Page.MAP || content.page == DockDisplayState.Page.DASHBOARD
        val selectedIcon =
            if (map && component != null) {
                appIcons.keys.firstOrNull { it == component } ?: appIcons.keys.firstOrNull { it.packageName == component.packageName }
            } else if (content.page == DockDisplayState.Page.APP) {
                component
            } else {
                null
            }
        appIcons.forEach { (icon, view) ->
            view.isSelected = icon == selectedIcon
            view.isActivated = icon == pendingIcon
        }
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}
