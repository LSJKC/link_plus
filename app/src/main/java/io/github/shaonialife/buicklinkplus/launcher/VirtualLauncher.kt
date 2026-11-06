package io.github.shaonialife.buicklinkplus.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.content.edit
import io.github.shaonialife.buicklinkplus.PhoneSettingsActivity
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.compat.CarInputClient
import io.github.shaonialife.buicklinkplus.compat.Shell
import io.github.shaonialife.buicklinkplus.compat.ShellServiceGate
import io.github.shaonialife.buicklinkplus.launcher.settings.AppOrientationCompat
import io.github.shaonialife.buicklinkplus.media.NotificationAccess
import io.github.shaonialife.buicklinkplus.render.CARD_MARGIN_PX
import io.github.shaonialife.buicklinkplus.render.DASH_GAP_PX
import io.github.shaonialife.buicklinkplus.render.DASH_HANDLE_TOUCH_PX
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.MapFrameMark
import io.github.shaonialife.buicklinkplus.render.MapResizeCoordinator
import io.github.shaonialife.buicklinkplus.render.OverlayCard
import io.github.shaonialife.buicklinkplus.render.RuntimeDisplayGeometry
import io.github.shaonialife.buicklinkplus.ui.ScreenDimmer
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.shellQuote
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 管理 Dock、应用和地图虚拟屏，由 DualDisplayComposer 合成到编码器
 * 启动应用时优先复用任务，回桌面保留后台应用，触摸交给 TouchRouter
 */
object VirtualLauncher {
    private const val TAG = "VirtualLauncher"
    private val mapLayoutGeneration = AtomicLong()
    private val displayState = DockDisplayState()
    private val appLaunchLock = Any()
    private val mapLaunchLock = Any()
    private val mapStateLock = Any()
    private val runtimeGeometry = java.util.concurrent.ConcurrentHashMap<Int, RuntimeDisplayGeometry>()
    private val confirmedAppTasks = java.util.concurrent.ConcurrentHashMap<Int, ConfirmedAppTask>()
    private val interactionDiagnosticAt = AtomicLong()
    private val interactionDiagnosticRunning =
        java.util.concurrent.atomic
            .AtomicBoolean()

    /** 组件点击前记录帧边界，后续页面只接续本次点击所在屏 */
    private data class WidgetInteraction(
        val packageName: String,
        val displayId: Int,
        val request: DockDisplayState.Request,
        val boundary: MapFrameMark,
        val expiresAt: Long,
    )

    /** 前台检查后固定任务 ID，不再按图标偏好另选旧实例 */
    private data class ObservedWidgetApp(
        val task: AppLaunchRecovery.Move,
        val boundary: MapFrameMark,
    )

    @Volatile private var widgetInteraction: WidgetInteraction? = null

    /**
     * 已合成的任务身份和逻辑尺寸，仅用于同任务仍在前台时复用静止画面
     */
    private data class ConfirmedAppTask(
        val composer: DualDisplayComposer,
        val packageName: String,
        val taskId: Int,
        val geometry: RuntimeDisplayGeometry,
        val qqCarPage: Boolean? = null,
    )

    /**
     * 应用屏触控使用后台回读的实际逻辑尺寸，不在触摸线程进行系统查询
     */
    internal fun actualDisplayGeometry(displayId: Int): RuntimeDisplayGeometry? = runtimeGeometry[displayId]

    /**
     * 查询有界且按合成器隔离，旧会话不能回写新屏幕
     */
    private fun refreshRuntimeGeometry(expected: DualDisplayComposer) {
        val service = CarInputClient.service ?: return
        for (id in listOf(appDisplayId, appHiDisplayId).filter { it >= 0 }) {
            val geometry = RuntimeDisplayGeometry.from(runCatching { service.displayGeometry(id) }.getOrNull()) ?: continue
            if (composer !== expected) return
            if (runtimeGeometry[id] != geometry &&
                !expected.updateAppGeometry(id == appHiDisplayId, geometry.width, geometry.height) {
                    composer === expected && id in listOf(appDisplayId, appHiDisplayId)
                }
            ) {
                continue
            }
            val previous =
                synchronized(mapStateLock) {
                    if (composer !== expected) return
                    runtimeGeometry.put(id, geometry)
                }
            if (previous != geometry) logSink?.invoke("DISPLAY-STATE: display=$id 实际=$geometry 创建=${hintDisplaySize(id)}")
        }
    }

    /** 异常时限量保存本地系统摘要，后台串行执行并绑定原会话 */
    fun recordInteractionDiagnostics(reason: String) {
        val expected = composer ?: return
        val context = appContextRef ?: return
        if (!interactionDiagnosticRunning.compareAndSet(false, true)) return
        val now = SystemClock.uptimeMillis()
        val previous = interactionDiagnosticAt.get()
        if (previous != 0L && now - previous < 30_000) {
            interactionDiagnosticRunning.set(false)
            return
        }
        interactionDiagnosticAt.set(now)
        Bg.run("interaction-diagnostics") {
            try {
                if (composer !== expected) return@run
                val tasks = Shell.exec("dumpsys activity activities", timeoutMs = 2_000)
                val audio = Shell.exec("dumpsys audio", timeoutMs = 2_000)
                if (composer !== expected) return@run
                val power = context.getSystemService(android.os.PowerManager::class.java)
                val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                val focus =
                    audio.output
                        .lineSequence()
                        .filter {
                            it.contains(
                                "focus",
                                true,
                            ) ||
                                it.contains("pack:", true)
                        }.take(16)
                        .joinToString(" | ") { it.trim().take(240) }
                logSink?.invoke(
                    "INTERACTION: 原因=${reason.take(240)} 亮屏=${power?.isInteractive} 锁屏=${keyguard?.isKeyguardLocked} " +
                        "手机前台=${MapLaunchResolver.activeComponent(tasks.output, 0)} 应用屏=${activeAppDisplayId()} " +
                        "地图布局=${presentedMapLayout()} 音频焦点=$focus",
                )
                val manager = context.getSystemService(android.media.AudioManager::class.java)
                val route =
                    runCatching {
                        "mode=${manager?.mode} 通信设备类型=${manager?.communicationDevice?.type} " +
                            "可用通信类型=${manager?.availableCommunicationDevices?.map { it.type }} " +
                            "媒体音量=${manager?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)} " +
                            "媒体静音=${manager?.isStreamMute(android.media.AudioManager.STREAM_MUSIC)}"
                    }.getOrElse { "音频状态读取失败=${it.javaClass.simpleName}" }
                val display = runCatching { CarInputClient.service?.diag() }.getOrNull()
                if (composer !== expected) return@run
                logSink?.invoke("INTERACTION-ROUTE: $route 显示=$display")
                for ((service, keywords) in listOf(
                    "audio" to
                        listOf(
                            "actual mode",
                            "requested mode",
                            "mode owner",
                            "communication device",
                            "communication route",
                            "mbluetoot",
                            "sco",
                            "audio focus",
                            "pack:",
                            "silenced",
                        ),
                    "power" to listOf("wakefulness", "wake locks:", "screen_bright", "display group", "powergroup", "buick"),
                    "window displays" to listOf("display:", "displayid=", "mcurrentfocus", "mfocusedapp", "keyguard", "msleeping"),
                    "input" to listOf("dispatchenabled", "dispatchfrozen", "focused", "touchstates", "paused="),
                )) {
                    if (composer !== expected) return@run
                    val result = if (service == "audio") audio else Shell.exec("dumpsys $service", timeoutMs = 2_000)
                    if (composer !== expected) return@run
                    val candidates =
                        result.output
                            .lineSequence()
                            .filterNot {
                                val line = it.trim()
                                line.startsWith("android.media.audio.") || line.startsWith("com.android.media.audio.")
                            }.toList()
                    val excerpt =
                        keywords
                            .asSequence()
                            .flatMap { keyword ->
                                candidates.asSequence().filter { it.contains(keyword, ignoreCase = true) }.take(4)
                            }.distinct()
                            .take(20)
                            .joinToString(" | ") { it.trim().take(240) }
                    logSink?.invoke("INTERACTION-SYSTEM: 类型=$service 成功=${result.ok} 摘要=${excerpt.ifBlank { "未命中，不能据此判定正常" }}")
                }
            } finally {
                interactionDiagnosticRunning.set(false)
            }
        }
    }

    @Volatile private var readyMapComponent: ComponentName? = null

    @Volatile private var readyMapTask: Pair<Int, Int>? = null

    private val mapHandoff = MapHandoffState()

    /**
     * 手机接管后所有布局入口保持隐藏，不能由仪表盘恢复逻辑抢回导航
     */
    private fun DualDisplayComposer.setSafeMapLayout(
        layout: DualDisplayComposer.TileLayout,
        onDisplayed: (() -> Unit)? = null,
    ) {
        setTileLayout(if (mapHandoff.paused || mapHandoff.busy) DualDisplayComposer.TileLayout.HIDDEN else layout, onDisplayed)
    }

    /** 手机和车机共用任务交接状态，显示成功后才更新 */
    fun mapHandoffStatus(): String =
        mapHandoff.message +
            (readyMapTask?.let { "\n任务=${it.second} · 显示屏=${it.first} · ${readyMapComponent?.packageName.orEmpty()}" } ?: "")

    /** 后台迁移原任务，再检查屏幕、前台和新帧；失败时保留暂停 */
    fun handoffMap(toPhone: Boolean): String {
        val expected = composer ?: return "请先连接车机"
        val operation = mapHandoff.begin() ?: return "地图正在交接，请稍候"
        mapLayoutGeneration.incrementAndGet()
        try {
            synchronized(mapLaunchLock) {
                val target = readyMapTask ?: return "地图任务尚未确认，请先打开地图"
                val component = readyMapComponent ?: return "地图任务尚未确认"
                if (component.packageName == MapLaunchResolver.AMAP && amapCarPage) return "高德车载页面仅恢复原车载任务，不与手机普通导航同步"
                val service = CarInputClient.service ?: return "输入服务已移除，地图交接不可用"
                if (runCatching { service.capabilities() and 2 == 0 }.getOrDefault(true)) return "当前服务不支持地图交接，暂无可用输入服务"
                val destination = if (toPhone) 0 else mapDisplayId

                fun current() = mapHandoff.owns(operation) && composer === expected && mapDisplayId >= 0 && readyMapTask == target
                if (!current()) return "原会话已结束"
                if (!mapHandoff.pause(operation)) return "原交接已失效"
                expected.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
                val before = expected.mapFrameMark()
                val dump = Shell.exec("dumpsys activity activities", timeoutMs = 2_500)
                if (!dump.ok || !current()) return "无法读取原任务，保留暂停状态"
                val source =
                    listOf(
                        0,
                        mapDisplayId,
                    ).flatMap { MapLaunchResolver.taskStates(dump.output, "", it, component.packageName) }.firstOrNull {
                        it.taskId ==
                            target.second
                    }
                        ?: return "原地图任务已不存在，请重新选择地图"
                if (!current()) return "原会话已结束"
                if (source.displayId != destination &&
                    !service.moveTaskToDisplay(target.second, source.displayId, destination)
                ) {
                    return "系统未接受任务迁移，保留原任务并暂停展示"
                }
                if (!current()) return "原会话已结束"
                Shell.exec("am task focus ${target.second} 2>&1", timeoutMs = 3_000)
                val deadline = SystemClock.uptimeMillis() + 8_000
                while (current() && SystemClock.uptimeMillis() < deadline) {
                    val activities = Shell.exec("dumpsys activity activities", timeoutMs = 2_000)
                    val windows = Shell.exec("dumpsys window windows", timeoutMs = 2_000)
                    if (!current()) return "原会话已结束"
                    val verified =
                        activities.ok &&
                            windows.ok &&
                            MapLaunchResolver.taskStates(activities.output, windows.output, destination, component.packageName).any {
                                it.taskId ==
                                    target.second &&
                                    it.ready
                            }
                    if (verified && (toPhone || expected.awaitMapFrame(before, ::current))) {
                        if (!current()) return "原会话已结束"
                        synchronized(mapStateLock) {
                            if (!current() || !mapHandoff.verified(operation, toPhone)) return "原交接已失效"
                            readyMapTask = destination to target.second
                        }
                        if (!toPhone) {
                            expected.setTileLayout(
                                if (mapFullscreen) {
                                    DualDisplayComposer.TileLayout.FULLSCREEN
                                } else if (dashboardMode) {
                                    DualDisplayComposer.TileLayout.SPLIT
                                } else {
                                    DualDisplayComposer.TileLayout.HIDDEN
                                },
                            )
                        }
                        logSink?.invoke("MAP-HANDOFF: task=${target.second} display=$destination 前台绘制已核验 手机=$toPhone")
                        return mapHandoff.message
                    }
                    Thread.sleep(200)
                }
                return "未确认目标屏绘制，已保留任务并暂停车机展示，可重试交接"
            }
        } catch (error: Exception) {
            return "地图交接失败：${error.cause?.message ?: error.message}；保留任务供重试"
        } finally {
            mapHandoff.finish(operation)
        }
    }

    /**
     * 普通地图在手机恢复前台即暂停，不迁移任务也不自动启动车端副本
     */
    private fun observePhoneMap(
        dump: String,
        expected: DualDisplayComposer,
        component: ComponentName,
    ): Boolean {
        if (mapHandoff.busy || composer !== expected || (component.packageName == MapLaunchResolver.AMAP && amapCarPage)) return false
        val phone =
            MapLaunchResolver.taskStates(dump, "", 0, component.packageName).firstOrNull { it.resumed && it.visible != false }
                ?: return false
        synchronized(mapStateLock) {
            if (composer !== expected || !mapHandoff.observePhone()) return false
            // 手机可能主动创建了新的导航任务，后续用户确认应交回当前手机任务
            readyMapTask = 0 to phone.taskId
            readyMapComponent = component
        }
        expected.setTileLayout(DualDisplayComposer.TileLayout.HIDDEN)
        return true
    }

    @Volatile private var lastMapTaskSnapshot = "尚未查询"

    /**
     * 合成器只读取低频任务快照，不在 GL 线程调用系统查询
     */
    fun mapDiagnostics(): String = "display=$mapDisplayId 实际=$mapDisplaySize 快照=$lastMapTaskSnapshot"

    private val mapDiagnosticRunning =
        java.util.concurrent.atomic
            .AtomicBoolean()

    /** 地图无新帧时只触发一次后台检查，有同屏遮挡证据才恢复原导航任务 */
    fun inspectStalledMap(
        expected: DualDisplayComposer,
        boundary: io.github.shaonialife.buicklinkplus.render.MapFrameMark,
    ) {
        val target = readyMapTask ?: return
        val generation = mapLayoutGeneration.get()
        if (mapHandoff.paused || mapHandoff.busy || composer !== expected || !mapDiagnosticRunning.compareAndSet(false, true)) return
        Bg.run("map-stall-diagnostics") {
            try {
                synchronized(mapLaunchLock) {
                    fun current() =
                        composer === expected &&
                            readyMapTask == target &&
                            mapLayoutGeneration.get() == generation &&
                            !displayState.hasPending &&
                            expected.presentedMapLayout != DualDisplayComposer.TileLayout.HIDDEN &&
                            expected.mapFrameMark() == boundary
                    if (!current()) return@run
                    val context = appContextRef ?: return@run
                    val tasks = Shell.exec("dumpsys activity activities", timeoutMs = 2_500)
                    if (!current()) return@run
                    val states = if (tasks.ok) MapLaunchResolver.taskStates(tasks.output, "", target.first) else emptyList()
                    val power = context.getSystemService(android.os.PowerManager::class.java)
                    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(target.first)
                    lastMapTaskSnapshot = "时间=${SystemClock.uptimeMillis()} 状态=$states"
                    logSink?.invoke(
                        "MAP-STALL: 十秒无新输入，亮屏=${power?.isInteractive} 显示电源=${display?.state} " +
                            "输入服务=${CarInputClient.ready} 任务=$states 帧=$boundary；静止画面可能正常，帧内3D变化未知",
                    )
                    val map = states.firstOrNull { it.taskId == target.second }
                    if (map != null && map.obscuredBy(states) && current()) {
                        val result = Shell.exec("am task focus ${map.taskId} 2>&1", timeoutMs = 3_000)
                        if (current()) logSink?.invoke("MAP-STALL: 恢复被遮挡原任务=${map.taskId} exit=${result.exitCode}")
                    }
                }
            } finally {
                mapDiagnosticRunning.set(false)
            }
        }
    }

    private val displayListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val displayQueryRunning =
        java.util.concurrent.atomic
            .AtomicBoolean()
    internal val displayedContent: DockDisplayState.Content get() = displayState.content
    internal val pendingComponent: String? get() = displayState.pendingComponent()
    val launchInProgress: Boolean get() = displayState.hasPending

    /** 触摸使用已提交的地图布局，不让空地图或待提交布局拦截桌面 */
    fun presentedMapLayout(): DualDisplayComposer.TileLayout = composer?.presentedMapLayout ?: DualDisplayComposer.TileLayout.HIDDEN

    /**
     * 订阅当前显示内容，图标列表无需随选中变化重建
     */
    fun addDisplayListener(listener: () -> Unit) {
        displayListeners.add(listener)
    }

    /**
     * 页面销毁时释放显示状态监听
     */
    fun removeDisplayListener(listener: () -> Unit) {
        displayListeners.remove(listener)
    }

    /**
     * 成功提交后通知侧栏原位更新
     */
    private fun publishDisplay(
        request: DockDisplayState.Request,
        page: DockDisplayState.Page,
        component: ComponentName? = null,
    ) {
        if (displayState.commit(request, DockDisplayState.Content(page, component?.flattenToString()))) {
            displayListeners.forEach { runCatching { it() } }
        }
    }

    /** 失败只结束原请求，通知启动反馈消失而不提前提交显示成功 */
    private fun failDisplay(request: DockDisplayState.Request) {
        displayState.fail(request)
        displayListeners.forEach { runCatching { it() } }
    }

    /** 发起通用页面请求时同步发布等待状态 */
    private fun beginDisplay(): DockDisplayState.Request =
        displayState.begin().also {
            displayListeners.forEach { listener -> runCatching { listener() } }
        }

    /**
     * 自有设置或桌面恢复时按实际应用屏对账，地图覆盖时不误认下层页面
     */
    fun onOwnPageResumed(
        displayId: Int,
        settings: Boolean,
    ) {
        confirmedAppTasks.remove(displayId)
        if (composer == null || displayId != activeAppDisplayId() || mapFullscreen || displayState.hasPending) return
        val page =
            when {
                settings -> DockDisplayState.Page.SETTINGS
                dashboardMode -> DockDisplayState.Page.DASHBOARD
                else -> DockDisplayState.Page.DESKTOP
            }
        val component = if (!settings && dashboardMode) readyMapComponent else null
        if (displayState.observe(DockDisplayState.Content(page, component?.flattenToString()))) {
            displayListeners.forEach { runCatching { it() } }
        }
    }

    /** 保留官方广播行为，页面恢复并出帧后才退出仪表盘 */
    fun onWidgetInteraction(
        packageName: String,
        displayId: Int,
    ) {
        val expected = composer ?: return
        if (!dashboardMode || displayId != gridDisplayId || OfficialCarApps.find(packageName) == null) return
        val request = displayState.snapshot()
        widgetInteraction =
            WidgetInteraction(
                packageName,
                displayId,
                request,
                expected.appFrameMark(displayId == appHiDisplayId),
                SystemClock.uptimeMillis() + 8000,
            )
        logSink?.invoke("WIDGET: 记录页面接续 package=$packageName display=$displayId 请求=$request")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in listOf(0L, 300L, 1000L)) {
            handler.postDelayed({
                if (composer === expected && dashboardMode && displayState.owns(request)) refreshDisplayedContent()
            }, delay)
        }
    }

    /** 低频检查前台，跟踪第三方返回和内部跳转 */
    fun refreshDisplayedContent() {
        val expected = composer ?: return
        if (displayState.hasPending || !displayQueryRunning.compareAndSet(false, true)) return
        val request = displayState.snapshot()
        val mapRevision = mapLayoutGeneration.get()
        val fullscreen = mapFullscreen
        val dashboard = dashboardMode
        val id = if (fullscreen) mapDisplayId else activeAppDisplayId()
        Bg.run("dock-display-state") {
            try {
                if (composer !== expected || !displayState.owns(request)) return@run
                refreshRuntimeGeometry(expected)
                val result = Shell.exec("dumpsys activity activities", timeoutMs = 2500)
                if (!result.ok || composer !== expected || fullscreen != mapFullscreen || dashboard != dashboardMode) return@run
                if (mapLayoutGeneration.get() == mapRevision) readyMapComponent?.let { observePhoneMap(result.output, expected, it) }
                val component = MapLaunchResolver.activeComponent(result.output, id)?.let(ComponentName::unflattenFromString) ?: return@run
                val own = component.packageName == appContextRef?.packageName
                // 组件通过官方 PendingIntent 打开的完整页面可能复用桌面屏，确认真实前台后
                // 交回统一启动流程复用同包任务，隐藏地图覆盖并核验输入，不重建导航任务
                if (dashboard && !fullscreen && !own && displayState.owns(request) && !displayState.hasPending) {
                    val context = appContextRef ?: return@run
                    val bound =
                        android.appwidget.AppWidgetManager
                            .getInstance(context)
                            .getAppWidgetInfo(DashboardWidgets.selected(context))
                    if (bound?.provider?.packageName == component.packageName && OfficialCarApps.find(component.packageName) != null) {
                        val interaction = widgetInteraction
                        val actualTask = AppLaunchRecovery.reusableTask(result.output, component.packageName, id, -1)
                        val launcher = context.packageManager.getLaunchIntentForPackage(component.packageName)?.component
                        if (launcher != null &&
                            composer === expected &&
                            interaction != null &&
                            interaction.packageName == component.packageName &&
                            interaction.displayId == id &&
                            interaction.request == request &&
                            SystemClock.uptimeMillis() < interaction.expiresAt &&
                            actualTask != null &&
                            MapLaunchResolver.taskStates(result.output, "", id, component.packageName, detailedOnly = true).any {
                                it.taskId == actualTask.taskId && it.page == component.className && it.resumed && it.visible != false
                            }
                        ) {
                            launchApp(launcher, request, ObservedWidgetApp(actualTask, interaction.boundary))
                        }
                        return@run
                    }
                }
                val settingsPages =
                    setOf(
                        SettingsActivity::class.java.name,
                        PhoneSettingsActivity::class.java.name,
                        SettingsContentActivity::class.java.name,
                    )
                val page =
                    when {
                        own && component.className == GridActivity::class.java.name ->
                            if (dashboard) DockDisplayState.Page.DASHBOARD else DockDisplayState.Page.DESKTOP
                        own && component.className in settingsPages -> DockDisplayState.Page.SETTINGS
                        own -> DockDisplayState.Page.OTHER
                        fullscreen && component.packageName == readyMapComponent?.packageName -> DockDisplayState.Page.MAP
                        fullscreen -> DockDisplayState.Page.OTHER
                        else -> DockDisplayState.Page.APP
                    }
                val selected =
                    when (page) {
                        DockDisplayState.Page.MAP, DockDisplayState.Page.DASHBOARD -> readyMapComponent
                        DockDisplayState.Page.APP ->
                            synchronized(runningApps) {
                                runningApps.firstOrNull { it.packageName == component.packageName }
                            }
                        else -> null
                    }
                if (displayState.observe(request, DockDisplayState.Content(page, selected?.flattenToString()))) {
                    displayListeners.forEach { runCatching { it() } }
                }
            } finally {
                displayQueryRunning.set(false)
            }
        }
    }

    /** 完整编码画布及安全区的会话快照，壁纸切片与合成器共用 */
    @Volatile
    var wallpaperGeometry: DisplayGeometry? = null
        private set

    private var amapCarPage = true
    private val mapResizeRequests =
        io.github.shaonialife.buicklinkplus.render
            .MapResizeCoordinator()

    @Volatile private var confirmedMapResize: MapResizeCoordinator.Request? = null

    @Volatile private var pendingMapResize: Pair<MapResizeCoordinator.Request, java.util.concurrent.Future<Boolean>>? = null
    private val mapResizeExecutor =
        Executors.newSingleThreadExecutor { work ->
            Thread(work, "map-display-resize").apply { isDaemon = true }
        }

    /** 系统窗口样式的设置与恢复按会话顺序串行，断开时不阻塞界面 */
    private val windowPolicyExecutor =
        Executors.newSingleThreadExecutor { work ->
            Thread(work, "launcher-window-policy").apply { isDaemon = true }
        }

    /** Dock 基准宽度，屏幕密度随画布短边缩放，保持侧栏占比 */
    const val DOCK_WIDTH_DP = 48

    /** 竖屏应用的 letterbox 宽高比，连接时写入系统 */
    private const val LETTERBOX_ASPECT_RATIO = 1.05f

    /** letterbox 目标宽度，单位 dp，接近手机宽度以使用紧凑布局 */
    private const val LETTERBOX_TARGET_DP = 410f

    /** 应用屏超采样倍率，1.5 倍尺寸对应 2.25 倍像素开销 */
    private const val APP_SUPERSAMPLE = 1.5f

    /** 超采样后的应用屏高度上限（px；控制 GPU/内存开销）。 */
    private const val SUPERSAMPLE_MAX_HEIGHT = 1620

    // ---- 仪表盘分屏（拖拽把手）----
    // 比例 = tile 主轴长占「可用长度」（应用区主轴 − 两端边距 − 把手宽）的比例。

    /** 默认比例：0.5 恰使把手中心落在应用区正中（见 dashSplitGeom 注释）。 */
    const val DASH_SPLIT_DEFAULT_FRACTION = 0.5f

    /** 分屏比例限制为 20%～80%，避免卡片过窄 */
    const val DASH_SPLIT_MIN_FRACTION = 0.2f

    const val DASH_SPLIT_MAX_FRACTION = 0.8f

    private const val PREF_KEY_DASH_SPLIT = "dashSplitFraction"

    fun clampDashSplitFraction(fraction: Float): Float = fraction.coerceIn(DASH_SPLIT_MIN_FRACTION, DASH_SPLIT_MAX_FRACTION)

    /** 分屏几何（应用区坐标系：横屏原点=dock 右缘，竖屏原点=流顶缘）。 */
    data class DashSplitGeom(
        val fraction: Float,
        /** tile 主轴长（横=mapTileWidth、竖=mapTileHeight）。 */
        val tileLen: Int,
        /** tile 把手侧边缘（composer override 同语义）。 */
        val tileEdge: Float,
        /** 把手中心。 */
        val splitCenter: Float,
        /** 对侧卡（音乐卡）内容侧起始。 */
        val otherStart: Float,
    )

    /** 计算分屏位置：主轴减两侧边距和把手宽度后，按比例分配地图与音乐区域 */
    fun dashSplitGeom(
        appMainLen: Int,
        fraction: Float,
    ): DashSplitGeom {
        val usable = (appMainLen - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()).coerceAtLeast(1)
        val clamped = clampDashSplitFraction(fraction)
        val tileLen = (clamped * usable).toInt()
        val edge = CARD_MARGIN_PX + tileLen
        return DashSplitGeom(
            fraction = clamped,
            tileLen = tileLen,
            tileEdge = edge.toFloat(),
            splitCenter = edge + DASH_GAP_PX / 2f,
            otherStart = edge + DASH_GAP_PX,
        )
    }

    /** 分屏主轴长度（应用区坐标系）：横=appAreaWidth、竖=appAreaHeight。 */
    private fun splitMainLen(): Int = if (dockAtBottom) appAreaHeight else appAreaWidth

    val runningApps = LinkedHashSet<ComponentName>()

    private val runningAppsListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Dock 的运行应用列表变化回调，独立屏常驻时不依赖 onResume 刷新 */
    fun addRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.add(listener)
    }

    fun removeRunningAppsListener(listener: () -> Unit) {
        runningAppsListeners.remove(listener)
    }

    private fun notifyRunningAppsChanged() {
        runningAppsListeners.forEach { runCatching { it() } }
    }

    /** 图标缓存整表替换，后台扫描和 Dock 读取不修改同一张表 */
    @Volatile
    private var iconCacheRef: Map<ComponentName, Drawable> = emptyMap()

    val iconCache: Map<ComponentName, Drawable>
        get() = iconCacheRef

    /** 桌面扫描完成后整表替换图标缓存。 */
    fun replaceIconCache(fresh: Map<ComponentName, Drawable>) {
        iconCacheRef = fresh
    }

    @Volatile
    var dockDisplayId: Int = -1
        private set

    @Volatile
    var appDisplayId: Int = -1
        private set

    /** dock 在主合成画面中占据的像素宽度（TouchRouter 分流阈值；横屏=左侧竖条宽）。 */
    @Volatile
    var dockWidthPx: Int = 0
        private set

    /** 竖屏车机（屏幕高>宽）：dock 改为底部横条。 */
    @Volatile
    var dockAtBottom: Boolean = false
        private set

    /** dock 横条高度（竖屏；TouchRouter 按 y 分流）。 */
    @Volatile
    var dockHeightPx: Int = 0
        private set

    /** 应用区高度（竖屏；y < appHeightPx 属于应用屏）。 */
    @Volatile
    var appHeightPx: Int = 0
        private set

    /** 超采样屏倍率，未创建时为 1；触摸按当前活动屏倍率换算 */
    @Volatile
    var hiScale: Float = 1f
        private set

    /** 超采样应用屏（横屏车机时存在；竖屏车机为 -1，全部走原生）。 */
    @Volatile
    var appHiDisplayId: Int = -1
        private set

    /** 主区桌面（GridActivity）所在屏：横屏=超采样屏（保持既有观感），竖屏=原生屏。 */
    @Volatile
    var gridDisplayId: Int = -1
        private set

    /** 安全区局部画布尺寸，桌面壁纸、分屏与触控共用此坐标基准 */
    @Volatile
    var streamWidthPx: Int = 0
        private set

    @Volatile
    var streamHeightPx: Int = 0
        private set

    // ---- 地图屏（仪表盘分屏）：地图应用专属屏，分屏=tile 尺寸，全屏=应用区尺寸 ----

    /** 地图屏 displayId（横屏车机且受信屏模式创建；-1 = 无）。 */
    @Volatile
    var mapDisplayId: Int = -1
        private set

    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出）：滑动期间抑制 tile 事件注入。 */
    @Volatile
    var tileSlideFraction: Float = 0f
        private set

    /** 由 GridActivity 随 pager 滑动驱动 tile 视觉位移。 */
    fun setTileSlide(fraction: Float) {
        tileSlideFraction = fraction
        composer?.tileSlideFraction = fraction
    }

    /** 「显示」设置实时切换目标帧率（合成器未创建时静默——attach 时会从偏好读取）。 */
    @Volatile
    private var streamFpsLimit = 30

    fun setStreamFps(fps: Int): Int {
        val applied = fps.coerceIn(1, streamFpsLimit)
        composer?.targetFps = applied
        return applied
    }

    /** 地图分屏尺寸，与合成器 SPLIT 区域一致 */
    @Volatile
    var mapTileWidth: Int = 0
        private set

    @Volatile
    var mapTileHeight: Int = 0
        private set

    /**
     * 分屏把手中心，流像素、顶部原点，未挂载时小于 0
     * 拖动时预览，松手后提交并更新地图尺寸
     */
    @Volatile
    var dashSplitPos: Float = -1f
        private set

    /** 当前生效的分屏比例（attach 从偏好恢复，commit 写回）。 */
    @Volatile
    private var dashSplitFraction = DASH_SPLIT_DEFAULT_FRACTION

    /** 应用区尺寸（全屏形态的地图屏目标尺寸）。 */
    @Volatile
    var appAreaWidth: Int = 0
        private set

    @Volatile
    var appAreaHeight: Int = 0
        private set

    @Volatile
    var mapFullscreen: Boolean = false
        private set

    /** 一起发布地图实际宽高，供触摸换算和尺寸比较；null 表示无有效屏 */
    @Volatile
    private var mapDisplaySize: Pair<Int, Int>? = null

    /** 原生应用屏逻辑尺寸，供 letterbox 计算，创建后不变 */
    @Volatile
    private var appDisplayW = 0

    @Volatile
    private var appDisplayH = 0

    /** 超采样应用屏逻辑尺寸（同上）。 */
    @Volatile
    private var appHiDisplayW = 0

    @Volatile
    private var appHiDisplayH = 0

    /** 地图密度随分辨率等比，分屏切换保持相同密度 */
    private var mapDensityDpi = 200

    /** 仪表盘（分屏）模式是否激活。 */
    @Volatile
    var dashboardMode: Boolean = false
        private set

    /** 仪表盘模式变化监听（GridActivity 切换布局）。 */
    private val dashboardListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.add(listener)
    }

    fun removeDashboardListener(listener: (Boolean) -> Unit) {
        dashboardListeners.remove(listener)
    }

    private fun notifyDashboard() {
        dashboardListeners.forEach { runCatching { it(dashboardMode) } }
    }

    /** 地图 tile 布局变化监听（隐藏/分屏/全屏）。 */
    private val mapLayoutListeners = java.util.concurrent.CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.add(listener)
    }

    fun removeMapLayoutListener(listener: (Boolean) -> Unit) {
        mapLayoutListeners.remove(listener)
    }

    private fun notifyMapLayout() {
        mapLayoutListeners.forEach { runCatching { it(mapFullscreen) } }
    }

    /** 地图应用白名单（优先序：车机版高德 → 手机高德 → 百度 → 腾讯）。 */
    val MAP_PACKAGES_PRIORITY =
        listOf(
            "com.autonavi.amapauto",
            "com.autonavi.minimap",
            "com.baidu.BaiduMap",
            "com.tencent.map",
        )

    fun isMapPackage(packageName: String): Boolean = packageName in MAP_PACKAGES_PRIORITY

    /**
     * 转调 [DefaultMapPrefs.resolve] 获取默认地图，无可用地图返回 null
     * 解析内部不能回调本方法，避免递归
     */
    fun defaultMapComponent(context: Context): ComponentName? = DefaultMapPrefs.resolve(context)

    /** 桌面屏相对视频流的倍率，用于壁纸切片的尺寸和偏移换算 */
    fun gridScale(): Float = if (gridDisplayId >= 0 && gridDisplayId == appHiDisplayId) hiScale else 1f

    private var dockDisplay: VirtualDisplay? = null
    private var appDisplay: VirtualDisplay? = null
    private var appHiDisplay: VirtualDisplay? = null

    /** 回退形态的地图屏句柄（受信形态经 shell 创建，无本进程句柄）。 */
    private var mapDisplay: VirtualDisplay? = null

    @Volatile
    // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
    @android.annotation.SuppressLint("StaticFieldLeak")
    private var composer: DualDisplayComposer? = null

    /**
     * 绘制与触摸共用合成器发布的悬浮面板几何，断开后不保留旧边界
     */
    fun floatingPanelLayout() = composer?.floatingPanelLayout

    /** 当前活动应用屏 ID（TouchRouter 注入目标）。 */
    fun activeAppDisplayId(): Int = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) appHiDisplayId else appDisplayId

    /** 当前活动应用屏的坐标倍率（超采样屏=hiScale，原生屏=1）。 */
    fun activeAppScale(): Float = if (composer?.isActiveAppHi() == true && appHiDisplayId >= 0) hiScale else 1f

    /** 应用区在安全区局部画布中的矩形（顶部原点），叠加卡片与触控使用同一原点 */
    fun appAreaRect(): RectF? {
        if (streamWidthPx <= 0 || streamHeightPx <= 0) return null
        return if (dockAtBottom) {
            RectF(0f, 0f, streamWidthPx.toFloat(), appHeightPx.toFloat())
        } else {
            RectF(dockWidthPx.toFloat(), 0f, streamWidthPx.toFloat(), streamHeightPx.toFloat())
        }
    }

    /** 画布密度倍率为 dpi/160，基准短边 540px 时为 420dpi */
    private var layoutDensityDpi = 420

    fun uiDensity(): Float = layoutDensityDpi / 160f

    /** 读取指定屏的逻辑尺寸，供缺少 mMaxBounds 时使用 */
    fun hintDisplaySize(displayId: Int): Pair<Int, Int>? =
        when (displayId) {
            appDisplayId -> if (appDisplayW > 0) appDisplayW to appDisplayH else null
            appHiDisplayId -> if (appHiDisplayW > 0) appHiDisplayW to appHiDisplayH else null
            mapDisplayId -> mapDisplaySize
            else -> null
        }

    /** 挂/摘合成器上的叠加卡片。 */
    fun setOverlayCard(card: OverlayCard?) {
        composer?.overlayCard = card
    }

    /** 会话上下文（letterbox 重启后复探要用；未 attach 时为 null）。 */
    fun appContext(): Context? = appContextRef

    /** 创建合成器和虚拟屏并启动 Dock 与桌面，[encoderSurface] 为输出目标 */
    @SuppressLint("WrongConstant")
    fun attach(
        context: Context,
        encoderSurface: Surface,
        totalWidth: Int,
        totalHeight: Int,
        densityDpi: Int,
        geometry: DisplayGeometry,
        targetFps: Int,
        log: (String) -> Unit,
    ): Boolean {
        require(totalWidth == geometry.canvasWidth && totalHeight == geometry.canvasHeight)
        detach()
        interactionDiagnosticAt.set(0)
        wallpaperGeometry = geometry
        amapCarPage = DefaultMapPrefs.amapCarPage(context)
        layoutDensityDpi = densityDpi
        // 新会话：「本次会话不再提示」等用户级决定作废
        LetterboxHintController.resetSession()
        val contentWidth = geometry.content.width
        val contentHeight = geometry.content.height
        val resolutionScale = totalHeight.toFloat() / geometry.viewHeight
        mapDensityDpi = (200f * resolutionScale).toInt().coerceAtLeast(1)
        val density = densityDpi / 160f
        // dock 栏宽度缩放（「显示」面板 50%–100%，下次连接起效）：厚度像素与 dock 屏
        // 密度**同乘**比例——内容 dp 布局不变、渲染进更少像素，整条 dock（图标/时钟/
        // 文字）等比变窄；只缩厚度不缩密度会让内容溢出或留白。下限 80px 同乘缩放防
        // 极端画布下压没，分辨率缩放后不再用固定密度下限改变逻辑字号
        val dockScale = DockPrefs.widthPercent(context) / 100f
        val thicknessPx =
            (DOCK_WIDTH_DP * density * dockScale)
                .toInt()
                .coerceAtLeast((80 * dockScale * resolutionScale).toInt().coerceAtLeast(1))
                .coerceAtMost((minOf(contentWidth, contentHeight) - 64).coerceAtLeast(1))
        val dockDensityDpi = (densityDpi * dockScale).toInt().coerceAtLeast(1)
        // 横屏 Dock 在左侧，竖屏 Dock 在底部
        dockAtBottom = contentHeight > contentWidth
        streamWidthPx = contentWidth
        streamHeightPx = contentHeight
        val appWidth: Int
        if (dockAtBottom) {
            dockWidthPx = contentWidth
            dockHeightPx = thicknessPx
            appHeightPx = (contentHeight - thicknessPx).coerceAtLeast(1)
            appWidth = contentWidth
        } else {
            dockWidthPx = thicknessPx
            dockHeightPx = contentHeight
            appHeightPx = contentHeight
            appWidth = (contentWidth - thicknessPx).coerceAtLeast(1)
        }
        appAreaWidth = appWidth
        appAreaHeight = appHeightPx
        // 应用屏密度按目标手机宽度计算，不能复用随画布缩放的 Dock 密度
        val appPortraitDensity =
            ((appWidth / resolutionScale / LETTERBOX_TARGET_DP * 160f).coerceIn(160f, 640f) * resolutionScale).toInt().coerceAtLeast(1)
        // 地图屏在分屏和全屏间 resize，尺寸统一由 dashSplitGeom 计算
        dashSplitFraction =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getFloat(PREF_KEY_DASH_SPLIT, DASH_SPLIT_DEFAULT_FRACTION)
                .let(::clampDashSplitFraction)
        if (dockAtBottom) {
            val geom = dashSplitGeom(appHeightPx, dashSplitFraction)
            mapTileWidth = appWidth - CARD_MARGIN_PX * 2
            mapTileHeight = geom.tileLen
            dashSplitPos = geom.splitCenter
        } else {
            val geom = dashSplitGeom(appWidth, dashSplitFraction)
            mapTileWidth = geom.tileLen
            mapTileHeight = appHeightPx - CARD_MARGIN_PX * 2
            // 横屏应用区从 dock 右缘起（竖屏 x 原点即流原点，无偏移）
            dashSplitPos = geom.splitCenter + dockWidthPx
        }
        mapDensityDpi = MapContentScale.density(appWidth, appHeightPx, mapTileWidth, mapTileHeight, DefaultMapPrefs.contentScale(context))
        // 密度按 LETTERBOX_TARGET_DP 计算，避免竖屏应用在横屏上显示成大字窄窗口
        val landscapeApp = appWidth > appHeightPx && !dockAtBottom
        // 部分应用按固定像素留状态栏空白，超采样后缩回能减小它在画面中的占比
        val appScaleFactor =
            if (landscapeApp) {
                minOf(APP_SUPERSAMPLE, SUPERSAMPLE_MAX_HEIGHT.toFloat() / appHeightPx)
            } else {
                1f
            }
        val appHiWidth = (appWidth * appScaleFactor).toInt()
        val appHiHeight = (appHeightPx * appScaleFactor).toInt()
        this.hiScale = appScaleFactor
        val appHiDensity =
            if (landscapeApp) {
                val letterboxWindowPx = appHiHeight / LETTERBOX_ASPECT_RATIO
                val baseHiDensity = (letterboxWindowPx / resolutionScale / LETTERBOX_TARGET_DP * 160f).coerceIn(180f, 640f)
                (baseHiDensity * resolutionScale).toInt().coerceAtLeast(1)
            } else {
                appPortraitDensity
            }
        // 原生应用屏密度：与超采样屏保持**相同逻辑尺寸**（应用布局不受所在屏影响，
        // 超采样只是渲染分辨率差异）。横屏：原生 1034×540 @~200dpi = 827×432dp，
        // 与超采样屏（1551×810 @301dpi）的 824×431dp 等值；竖屏车机：直接用流密度。
        val appNativeDensity =
            if (landscapeApp) {
                val hiLogicalHeightDp = appHiHeight * 160f / appHiDensity
                (appHeightPx * 160f / hiLogicalHeightDp).toInt().coerceAtLeast(1)
            } else {
                appPortraitDensity
            }

        // 等待内置输入服务就绪，受信虚拟屏需要 shell 权限创建
        var svc = CarInputClient.service
        if (svc == null &&
            ShellServiceGate
                .state(context) ==
            ShellServiceGate.State.READY
        ) {
            val deadline = SystemClock.uptimeMillis() + 2_500L
            while (svc == null && SystemClock.uptimeMillis() < deadline) {
                CarInputClient
                    .ensure(context)
                Thread.sleep(150)
                svc = CarInputClient.service
            }
        }
        val useTrusted = svc != null
        log("LAUNCHER: 虚拟屏模式=${if (useTrusted) "受信屏(shell)" else "应用屏(回退)"}")

        val composer = DualDisplayComposer(encoderSurface, geometry, dockAtBottom, context.applicationContext)
        streamFpsLimit = targetFps
        composer.targetFps = targetFps
        this.composer = composer
        lastMapTaskSnapshot = "新会话尚未查询"
        val displaysCreated = arrayOf(false)
        composer.onSurfacesReady = { dockSurface, appSurface, appHiSurface, mapSurface ->
            // 优先：内置服务 shell 创建受信虚拟屏（VIRTUAL_DISPLAY_FLAG_TRUSTED）——
            // 允许第三方应用的新任务落在该屏；解决「应用内跳转的任务被重定向到手机主屏」。
            var viaService = false
            if (useTrusted) {
                runCatching {
                    // 先清理上次遗留的虚拟屏，避免同名屏继续占用旧 Surface
                    val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    dm.displays
                        .filter {
                            it.name == "CarPlayDock" ||
                                it.name == "CarPlayApp" ||
                                it.name == "CarPlayAppHi" ||
                                it.name == "CarPlayMap"
                        }.forEach { stale ->
                            runCatching { svc.releaseDisplay(stale.displayId) }
                                .onSuccess { log("LAUNCHER: 已释放残留虚拟屏 ${stale.name}#${stale.displayId}") }
                        }
                    Thread.sleep(150) // 等系统完成注销
                    // dock 保持私有访问边界；独立锁屏和焦点所需的受信标记由服务按系统能力补齐
                    val dockId =
                        svc.createDisplay(
                            "CarPlayDock",
                            dockWidthPx,
                            dockHeightPx,
                            dockDensityDpi,
                            dockSurface,
                            false,
                        )
                    val appId =
                        svc.createDisplay(
                            "CarPlayApp",
                            appWidth,
                            appHeightPx,
                            appNativeDensity,
                            appSurface,
                            true,
                        )
                    // 超采样屏（竖屏车机无、应用侧回退时也无）
                    val appHiId =
                        if (appHiSurface != null) {
                            svc.createDisplay(
                                "CarPlayAppHi",
                                appHiWidth,
                                appHiHeight,
                                appHiDensity,
                                appHiSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    // 地图屏（横竖屏都创建；按应用区全尺寸创建，分屏/全屏间动态 resize）
                    val mapId =
                        if (mapSurface != null) {
                            svc.createDisplay(
                                "CarPlayMap",
                                appWidth,
                                appHeightPx,
                                mapDensityDpi,
                                mapSurface,
                                true,
                            )
                        } else {
                            -1
                        }
                    if (dockId >= 0 && appId >= 0) {
                        dockDisplayId = dockId
                        appDisplayId = appId
                        appDisplayW = appWidth
                        appDisplayH = appHeightPx
                        if (appHiId >= 0) {
                            appHiDisplayId = appHiId
                            appHiDisplayW = appHiWidth
                            appHiDisplayH = appHiHeight
                        } else if (appHiSurface != null) {
                            log("LAUNCHER: 超采样屏创建失败——本次会话全部走原生屏")
                        }
                        if (mapId >= 0) {
                            mapDisplayId = mapId
                            // 创建尺寸=应用区全尺寸（resizeMapDisplay 的幂等基线）
                            mapDisplaySize = appWidth to appHeightPx
                        } else if (mapSurface != null) {
                            log("LAUNCHER: 地图屏创建失败——仪表盘无地图 tile")
                        }
                        viaService = true
                    } else {
                        // 部分 ROM 创建失败只返回 null，逐屏记录才能看出失败位置
                        if (dockId < 0) {
                            log("LAUNCHER: 受信屏 CarPlayDock 创建失败（createVirtualDisplay 返回 null——ROM 限制 shell 侧虚拟屏？）")
                        }
                        if (appId < 0) {
                            log("LAUNCHER: 受信屏 CarPlayApp 创建失败（同上）")
                        }
                        // 半成功：释放已建的
                        if (dockId >= 0) runCatching { svc.releaseDisplay(dockId) }
                        if (appId >= 0) runCatching { svc.releaseDisplay(appId) }
                        if (appHiId >= 0) runCatching { svc.releaseDisplay(appHiId) }
                        if (mapId >= 0) runCatching { svc.releaseDisplay(mapId) }
                    }
                }.onFailure { log("LAUNCHER: 受信屏创建异常 ${it.message}") }
            }
            if (viaService) {
                displaysViaService = true
                runCatching { log("LAUNCHER: 显示策略 ${svc!!.diag()}") }
                log(
                    "LAUNCHER: 三屏创建(受信) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor)",
                )
                // 音乐卡数据源前置：会话建立是「内置服务一定可用」的可靠时机，
                // 补一次通知使用权（幂等；已授权直接返回）。
                // 覆盖「App 启动时内置服务未就绪、之后才就绪」的整条路径。
                Bg.run("notification-access-session") {
                    NotificationAccess.ensureGranted(context.applicationContext, log)
                }
            } else {
                // 回退：应用侧 PUBLIC 屏（应用内跳转任务会逃逸到手机主屏，尽量可用的降级形态）
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dockDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayDock",
                        dockWidthPx,
                        dockHeightPx,
                        dockDensityDpi,
                        dockSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                appDisplay =
                    dm.createVirtualDisplay(
                        "CarPlayApp",
                        appWidth,
                        appHeightPx,
                        appNativeDensity,
                        appSurface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        null,
                        null,
                    )
                if (appHiSurface != null) {
                    appHiDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayAppHi",
                            appHiWidth,
                            appHiHeight,
                            appHiDensity,
                            appHiSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    appHiDisplayId = appHiDisplay?.display?.displayId ?: -1
                    appHiDisplayW = appHiWidth
                    appHiDisplayH = appHiHeight
                }
                // 回退模式也创建地图屏，由 shell 启动地图，应用侧 API 负责 resize
                if (mapSurface != null) {
                    mapDisplay =
                        dm.createVirtualDisplay(
                            "CarPlayMap",
                            appWidth,
                            appHeightPx,
                            mapDensityDpi,
                            mapSurface,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                            null,
                            null,
                        )
                    val fallbackMapId = mapDisplay?.display?.displayId ?: -1
                    if (fallbackMapId >= 0) {
                        mapDisplayId = fallbackMapId
                        mapDisplaySize = appWidth to appHeightPx
                    } else {
                        log("LAUNCHER: 地图屏（回退）创建失败——仪表盘无地图 tile")
                    }
                }
                dockDisplayId = dockDisplay?.display?.displayId ?: -1
                appDisplayId = appDisplay?.display?.displayId ?: -1
                appDisplayW = appWidth
                appDisplayH = appHeightPx
                log(
                    "LAUNCHER: 三屏创建(回退) dock=$dockDisplayId(${dockWidthPx}px) " +
                        "app=$appDisplayId(${appWidth}x$appHeightPx @${appNativeDensity}dpi) " +
                        "appHi=$appHiDisplayId(${appHiWidth}x$appHiHeight @${appHiDensity}dpi scale=x$appScaleFactor) " +
                        "map=$mapDisplayId($mapDisplaySize)——受信屏不可用，已回退应用侧 PUBLIC 屏",
                )
            }
            displaysCreated[0] = dockDisplayId >= 0 && appDisplayId >= 0
        }
        return try {
            composer.start(
                dockWidthPx,
                dockHeightPx,
                appWidth,
                appHeightPx,
                if (landscapeApp) appHiWidth else 0,
                if (landscapeApp) appHiHeight else 0,
                appWidth,
                appHeightPx,
            )
            // 分屏把手位置推给合成器（覆盖默认中线公式；拖动时 previewDashSplit 持续更新）
            composer.setTileEdgeOverride(dashSplitPos - DASH_GAP_PX / 2f)
            if (!displaysCreated[0]) {
                log("LAUNCHER: 虚拟屏创建失败")
                detach()
                return false
            }
            // 两种建屏路径都登记横屏会话，写入仍在后台串行完成，不阻塞首帧
            AppOrientationCompat.applyAllAsync(context, log)
            LetterboxHintController.scheduleProbe(context, "会话建立")
            // 忽略应用请求的方向，保持虚拟屏尺寸，避免整屏旋转后触摸坐标错位
            applyIgnoreOrientationRequest(appDisplayId, appHiDisplayId, mapDisplayId)
            refreshRuntimeGeometry(composer)
            // 放宽竖屏应用的 letterbox 窗口（默认窄条 → 手机宽度）；断开时还原（detach）
            windowPolicyExecutor
                .submit {
                    Shell.exec("cmd window set-letterbox-style --aspectRatio $LETTERBOX_ASPECT_RATIO")
                }.get()
            log(
                "LAUNCHER: letterbox 宽高比=$LETTERBOX_ASPECT_RATIO " +
                    "appDensity=${appNativeDensity}dpi appHiDensity=${appHiDensity}dpi",
            )
            // 组件使用 applicationId/绝对类名，避免带包名后缀时相对类名展开错误
            val selfPkg = context.packageName
            val dockOk = startOn(dockDisplayId, "$selfPkg/${DockActivity::class.java.name}")
            gridDisplayId = if (appHiDisplayId >= 0) appHiDisplayId else appDisplayId
            val gridOk = startOn(gridDisplayId, "$selfPkg/${GridActivity::class.java.name}")
            appContextRef = context.applicationContext
            log("LAUNCHER: dock=$dockOk grid=$gridOk gridDisplay=$gridDisplayId")
            // 桌面在超采样屏时默认活动屏 = 超采样屏（启动即显示桌面）
            if (appHiDisplayId >= 0) composer.requestActiveApp(true)
            // 暗幕组件就绪（常亮+暗幕功能；主界面开关控制启停）
            ScreenDimmer
                .attach(context)
            dockOk && gridOk
        } catch (error: IllegalStateException) {
            log("LAUNCHER: GL 合成器启动失败 - ${error.message}")
            detach()
            false
        }
    }

    /** 受信屏标记：true 时虚拟屏由 shell UserService 持有，须经服务释放。 */
    @Volatile
    private var displaysViaService = false

    fun detach() {
        val retiringComposer =
            synchronized(mapStateLock) {
                val current = composer
                composer = null
                mapResizeRequests.invalidate()
                confirmedMapResize = null
                readyMapComponent = null
                readyMapTask = null
                mapHandoff.reset()
                confirmedAppTasks.clear()
                lastMapTaskSnapshot = "已断开"
                current
            }
        displayState.disconnect()
        widgetInteraction = null
        runtimeGeometry.clear()
        displayListeners.forEach { runCatching { it() } }
        AppOrientationCompat.invalidateSession()
        mapLayoutGeneration.incrementAndGet()
        pendingMapResize = null
        wallpaperGeometry = null
        ScreenDimmer
            .detach()
        // 保存本轮屏幕 ID，在后台恢复方向策略，避免阻塞界面
        // 清理与释放并发，屏幕已释放时允许命令失败；创建时再设置并读回确认
        val orientationIds = intArrayOf(appDisplayId, appHiDisplayId, mapDisplayId).filter { it >= 0 }
        if (orientationIds.isNotEmpty()) {
            Bg.run("orientation-cleanup") {
                orientationIds.forEach { runCatching { setIgnoreOrientationRequest(it, false, verify = false) } }
            }
        }
        if (displaysViaService) {
            val svc = CarInputClient.service
            if (svc != null) {
                if (dockDisplayId >= 0) runCatching { svc.releaseDisplay(dockDisplayId) }
                if (appDisplayId >= 0) runCatching { svc.releaseDisplay(appDisplayId) }
                if (appHiDisplayId >= 0) runCatching { svc.releaseDisplay(appHiDisplayId) }
                // 地图屏同样由 shell 持有：不释放会在断开后残留（重装后由启动检测兜底发现）
                if (mapDisplayId >= 0) runCatching { svc.releaseDisplay(mapDisplayId) }
            }
            displaysViaService = false
        }
        appContextRef = null
        // 还原 letterbox 宽高比为系统默认（不把会话调参留在系统里）
        windowPolicyExecutor.execute {
            runCatching { Shell.exec("cmd window reset-letterbox-style aspectRatio") }
        }
        runCatching { retiringComposer?.stop() }
        runCatching { dockDisplay?.release() }
        dockDisplay = null
        runCatching { appDisplay?.release() }
        appDisplay = null
        runCatching { appHiDisplay?.release() }
        appHiDisplay = null
        runCatching { mapDisplay?.release() }
        mapDisplay = null
        dockDisplayId = -1
        appDisplayId = -1
        appHiDisplayId = -1
        gridDisplayId = -1
        mapDisplayId = -1
        mapTileWidth = 0
        mapTileHeight = 0
        dashSplitPos = -1f
        mapDisplaySize = null
        appDisplayW = 0
        appDisplayH = 0
        appHiDisplayW = 0
        appHiDisplayH = 0
        appAreaWidth = 0
        appAreaHeight = 0
        mapFullscreen = false
        // 提示卡片属于会话状态：拆屏时一并清掉（合成器已停，卡片纹理随 GL 上下文销毁）
        LetterboxHintController.clear()
        if (dashboardMode) {
            dashboardMode = false
            notifyDashboard()
        }
        dockWidthPx = 0
        dockHeightPx = 0
        appHeightPx = 0
        dockAtBottom = false
        hiScale = 1f
        streamWidthPx = 0
        streamHeightPx = 0
    }

    private fun startOn(
        displayId: Int,
        component: String,
    ): Boolean {
        if (displayId < 0) return false
        val result =
            Shell.exec(
                "am start -n $component --display $displayId --windowingMode 1",
                timeoutMs = 8000,
            )
        return result.ok && result.output.contains("Starting:", ignoreCase = true)
    }

    /** 异步把桌面任务带回前台并切换合成屏，保留后台应用 */
    fun goGrid(
        restoreDashboard: Boolean = dashboardMode,
        restoreFullscreen: Boolean = mapFullscreen,
    ) {
        val currentComposer = composer ?: return
        val grid = if (gridDisplayId >= 0) gridDisplayId else appDisplayId
        val pkg = appContextRef?.packageName ?: return
        val request = beginDisplay()
        Bg.run("go-grid") {
            synchronized(appLaunchLock) {
                if (composer !== currentComposer || !displayState.owns(request)) return@run
                val targetHi = appHiDisplayId >= 0 && grid == appHiDisplayId
                val before = currentComposer.appFrameMark(targetHi)
                val started = runCatching { startOn(grid, "$pkg/${GridActivity::class.java.name}") }.getOrDefault(false)
                if (started && composer === currentComposer && displayState.owns(request)) {
                    val gridVisible = displayedContent.page in setOf(DockDisplayState.Page.DESKTOP, DockDisplayState.Page.DASHBOARD)
                    currentComposer.requestActiveApp(targetHi, if (gridVisible) null else before) {
                        publishDisplay(
                            request,
                            if (dashboardMode) DockDisplayState.Page.DASHBOARD else DockDisplayState.Page.DESKTOP,
                            if (dashboardMode) readyMapComponent else null,
                        )
                    }
                } else {
                    if (composer === currentComposer && displayState.owns(request)) {
                        mapLayoutGeneration.incrementAndGet()
                        dashboardMode = restoreDashboard
                        mapFullscreen = restoreFullscreen
                        resizeMapDisplay(restoreFullscreen)
                        currentComposer.setSafeMapLayout(
                            if (restoreFullscreen) {
                                DualDisplayComposer.TileLayout.FULLSCREEN
                            } else if (restoreDashboard) {
                                DualDisplayComposer.TileLayout.SPLIT
                            } else {
                                DualDisplayComposer.TileLayout.HIDDEN
                            },
                        )
                        notifyDashboard()
                        notifyMapLayout()
                    }
                    failDisplay(request)
                }
            }
        }
        // 桌面上的提示没有意义（顶层不是第三方应用），直接收起
        LetterboxHintController.clear()
    }

    // Home 键在应用或桌面时回仪表盘，在仪表盘时回桌面
    // 地图一直留在地图屏，分屏和全屏只调整尺寸

    /**
     * Dock 首页键切换仪表盘和桌面；应用或全屏地图先回仪表盘
     * 目标模式和地图层一起更新，触摸读取已提交布局
     */
    fun onHomePressed() {
        when {
            mapFullscreen -> {
                showDashboard()
            }

            dashboardMode -> showDesktop()
            else -> showDashboard()
        }
    }

    /** 进入仪表盘：地图屏缩回 tile、默认地图占屏（缺则启动）、桌面切分屏布局。 */
    fun showDashboard() {
        val previousDashboard = dashboardMode
        val previousFullscreen = mapFullscreen
        syncDashboard(true)
        goGrid(previousDashboard, previousFullscreen)
        logSink?.invoke("LAUNCHER: 仪表盘模式（地图 tile + 音乐卡）")
    }

    /** 统一更新仪表盘状态、通知监听和地图显示，翻页只在落定后提交 */
    fun syncDashboard(
        dashboard: Boolean,
        fromPager: Boolean = false,
    ) {
        if (fromPager && (mapFullscreen || displayState.hasPending)) return
        if (mapFullscreen) setMapFullscreen(false, updateSelection = false)
        val generation = mapLayoutGeneration.incrementAndGet()
        val changed = dashboardMode != dashboard
        dashboardMode = dashboard
        if (changed) notifyDashboard()
        if (dashboard) {
            val enteringComposer = composer ?: return
            Bg.run("dashboard-enter") {
                fun current() = composer === enteringComposer && dashboardMode && mapLayoutGeneration.get() == generation
                if (!current()) return@run
                ensureMapTask(enteringComposer, ::current)
                if (!current()) return@run
                // 期间用户可能已点开普通应用（launchApp 会置 HIDDEN 并退出仪表盘）：
                // 复查模式，别把地图层又盖回去
                if (composer === enteringComposer && dashboardMode && !mapFullscreen) {
                    resizeMapDisplay(fullscreen = false)
                    if (composer === enteringComposer && dashboardMode && !mapFullscreen) {
                        enteringComposer.setSafeMapLayout(DualDisplayComposer.TileLayout.SPLIT) {
                            if (current()) onOwnPageResumed(activeAppDisplayId(), settings = false)
                        }
                    }
                }
            }
        } else {
            composer?.setSafeMapLayout(DualDisplayComposer.TileLayout.HIDDEN)
            onOwnPageResumed(activeAppDisplayId(), settings = false)
        }
    }

    /** 翻页时只预览地图可见性，落定后由 syncDashboard 更新状态 */
    fun showMapTile() {
        if (mapFullscreen ||
            displayState.hasPending ||
            displayedContent.page !in
            setOf(DockDisplayState.Page.DESKTOP, DockDisplayState.Page.DASHBOARD)
        ) {
            return
        }
        composer?.setSafeMapLayout(DualDisplayComposer.TileLayout.SPLIT)
    }

    /** 回普通桌面：隐藏地图层并退出仪表盘，桌面恢复全宽图标网格（地图任务保留）。 */
    fun showDesktop() {
        val previousDashboard = dashboardMode
        val previousFullscreen = mapFullscreen
        syncDashboard(false)
        goGrid(previousDashboard, previousFullscreen)
        logSink?.invoke("LAUNCHER: 桌面模式（图标网格）")
    }

    /**
     * 地图全屏与分屏共用同一任务和密度，切换时调整显示尺寸
     * 后台 resize 完成前，画面和触摸仍按实际屏尺寸换算
     */
    fun setMapFullscreen(
        fullscreen: Boolean,
        updateSelection: Boolean = true,
    ) {
        if (mapDisplayId < 0 || mapFullscreen == fullscreen) return
        mapLayoutGeneration.incrementAndGet()
        mapFullscreen = fullscreen
        resizeMapDisplay(fullscreen)
        composer?.setSafeMapLayout(
            if (fullscreen) {
                DualDisplayComposer.TileLayout.FULLSCREEN
            } else {
                DualDisplayComposer.TileLayout.SPLIT
            },
        )
        if (updateSelection) {
            val request = beginDisplay()
            val component = readyMapComponent
            composer?.setSafeMapLayout(
                if (fullscreen) DualDisplayComposer.TileLayout.FULLSCREEN else DualDisplayComposer.TileLayout.SPLIT,
            ) {
                publishDisplay(request, if (fullscreen) DockDisplayState.Page.MAP else DockDisplayState.Page.DASHBOARD, component)
            }
        }
        notifyMapLayout()
        logSink?.invoke("LAUNCHER: 地图屏 ${if (fullscreen) "全屏" else "分屏"}")
        // 地图屏 resize 后 letterbox 内外框变了：全屏时重探，分屏 tile 太小不提示
        if (fullscreen) {
            appContextRef?.let {
                // 目标固定为地图屏，探测器按该屏当时的实际尺寸检查
                LetterboxHintController.scheduleProbe(it, "地图全屏", displayIdHint = mapDisplayId)
            }
        } else {
            LetterboxHintController.clear()
        }
    }

    /**
     * 拖动时只调整合成矩形，松手后再 resize，避免反复触发地图重布局
     *
     * @param streamPos 把手中心的流像素坐标，横屏为 x、竖屏为 y
     */
    fun previewDashSplit(streamPos: Float) {
        val clamped = clampDashSplitStream(streamPos) ?: return
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
    }

    /**
     * 松手后调整地图屏和 buffer，保存比例并通知界面
     *
     * @return 限制在有效范围内的把手中心，单位为流像素
     */
    fun commitDashSplit(streamPos: Float): Float {
        val clamped = clampDashSplitStream(streamPos) ?: return dashSplitPos
        val geom =
            dashSplitGeom(
                splitMainLen(),
                (clamped - splitMainOffset() - CARD_MARGIN_PX - DASH_GAP_PX / 2f) / splitUsableLen(),
            )
        dashSplitPos = clamped
        dashSplitFraction = geom.fraction
        if (dockAtBottom) mapTileHeight = geom.tileLen else mapTileWidth = geom.tileLen
        composer?.setTileEdgeOverride(clamped - DASH_GAP_PX / 2f)
        if (!mapFullscreen) resizeMapDisplay(false)
        notifyMapLayout()
        appContextRef
            ?.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ?.edit {
                putFloat(PREF_KEY_DASH_SPLIT, dashSplitFraction)
            }
        logSink?.invoke(
            "LAUNCHER: 仪表盘分屏落定 ${if (dockAtBottom) "地图高" else "地图宽"}=" +
                "${if (dockAtBottom) mapTileHeight else mapTileWidth}px（比例 " +
                String.format(Locale.ROOT, "%.2f", dashSplitFraction) + "）",
        )
        return clamped
    }

    /** 当前把手中心换算到应用屏像素（GridActivity 布局用；未 attach 返回 -1）。 */
    fun dashSplitScreenPos(): Float = if (dashSplitPos < 0) -1f else dashSplitPos * gridScale()

    /** 把手中心·应用屏像素（应用区坐标系，已扣 dock 偏移；把手 DOWN 起点，<0=未 attach）。 */
    fun dashHandleCenterScreen(): Float = if (dashSplitPos < 0) -1f else (dashSplitPos - splitMainOffset()) * gridScale()

    /** 音乐卡内容侧起始·应用屏像素（横=左缘、竖=顶缘；<0=未 attach）。 */
    fun dashMusicStartScreen(): Int =
        if (dashSplitPos < 0) {
            -1
        } else {
            ((dashSplitPos - splitMainOffset() + DASH_GAP_PX / 2f) * gridScale()).toInt()
        }

    /** [previewDashSplit] 的应用屏像素入参版（把手拖动直调，内部换回流像素）。 */
    fun previewDashSplitScreen(centerScreen: Float) {
        previewDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f))
    }

    /** [commitDashSplit] 的应用屏像素入参版；返回钳制后的把手中心（应用屏像素）。 */
    fun commitDashSplitScreen(centerScreen: Float): Float =
        (
            commitDashSplit(splitMainOffset() + centerScreen / gridScale().coerceAtLeast(1e-6f)) -
                splitMainOffset()
        ) * gridScale()

    /** 判断触点是否命中分屏把手；命中后整段手势固定交给应用屏，避免跨屏误触 */
    fun dashHandleHit(
        x: Float,
        y: Float,
    ): Boolean {
        if (dashSplitPos < 0 || !dashboardMode || mapFullscreen) return false
        val half = DASH_HANDLE_TOUCH_PX / 2f
        return if (dockAtBottom) {
            y in dashSplitPos - half..dashSplitPos + half
        } else {
            x in dashSplitPos - half..dashSplitPos + half
        }
    }

    /** 流坐标把手中心 → 应用区坐标系偏移（横屏减 dock 宽；竖屏 y 原点即流原点）。 */
    private fun splitMainOffset(): Float = if (dockAtBottom) 0f else dockWidthPx.toFloat()

    /** 主轴可用长度 = 应用区主轴 − 两端边距 − 中缝宽（与 dashSplitGeom 同式）。 */
    private fun splitUsableLen(): Int = (if (dockAtBottom) appAreaHeight else appAreaWidth) - CARD_MARGIN_PX * 2 - DASH_GAP_PX.toInt()

    /** 将把手中心限制在 20%～80% 比例内，未挂载时返回 null */
    private fun clampDashSplitStream(streamPos: Float): Float? {
        val usable = splitUsableLen()
        if (usable <= 0) return null
        val base = splitMainOffset() + CARD_MARGIN_PX + DASH_GAP_PX / 2f
        val min = base + DASH_SPLIT_MIN_FRACTION * usable
        val max = base + DASH_SPLIT_MAX_FRACTION * usable
        return streamPos.coerceIn(min, max)
    }

    /** 按目标布局调整地图屏，尺寸未变则跳过；服务不可用或失败时保留当前尺寸 */
    @Synchronized
    private fun resizeMapDisplay(fullscreen: Boolean): java.util.concurrent.Future<Boolean>? {
        val id = mapDisplayId
        val resizingComposer = composer ?: return null
        if (id < 0) return null
        val w = if (fullscreen) appAreaWidth else mapTileWidth
        val h = if (fullscreen) appAreaHeight else mapTileHeight
        if (w <= 0 || h <= 0) return null
        val context = appContextRef ?: return null
        // 已核对的高德 17.02 UCAR 未接管密度变更，随全屏改变密度会重建地图页
        // 两种布局统一以分屏容器计算，避免普通布局切换附带额外的密度配置变化
        // 分屏比例与用户主动设置的内容比例仍参与计算，宽高和缓冲继续按实际目标调整
        val density =
            MapContentScale.density(appAreaWidth, appAreaHeight, mapTileWidth, mapTileHeight, DefaultMapPrefs.contentScale(context))
        val viaService = displaysViaService
        val display = mapDisplay
        val request = mapResizeRequests.request(w, h, density)
        pendingMapResize?.takeIf { it.first == request && !it.second.isDone }?.let { return it.second }
        val task =
            java.util.concurrent.FutureTask<Boolean> {
                fun sameSession() = composer === resizingComposer && mapDisplayId == id
                if (!sameSession() || !mapResizeRequests.owns(request)) return@FutureTask false
                if (confirmedMapResize == request) return@FutureTask true
                val buffer = resizingComposer.prepareMapInput(request) ?: return@FutureTask false
                var applied = false
                var bufferConfirmed = false
                try {
                    if (!sameSession() || !mapResizeRequests.owns(request)) return@FutureTask false
                    applied =
                        if (viaService) {
                            CarInputClient.service?.resizeDisplay(id, w, h, density) == true
                        } else {
                            display?.let {
                                it.resize(w, h, density)
                                true
                            } ?: false
                        }
                    // 即使目标已被下一请求替代，也先保持本会话缓冲与实际屏尺寸一致
                    synchronized(mapStateLock) {
                        if (applied && sameSession()) mapDisplaySize = w to h
                    }
                } catch (error: Exception) {
                    logSink?.invoke("LAUNCHER: 地图尺寸调整异常：${error.message}")
                } finally {
                    bufferConfirmed = resizingComposer.finishMapInput(buffer, applied)
                }
                val result = MapResizeCoordinator.Result(request, applied, bufferConfirmed)
                synchronized(mapStateLock) {
                    if (sameSession() && mapResizeRequests.owns(request) && result.ready) confirmedMapResize = request
                }
                logSink?.invoke("LAUNCHER: 地图尺寸事务 display=$id 结果=$result 实际=$mapDisplaySize 当前=${mapResizeRequests.owns(request)}")
                sameSession() && mapResizeRequests.owns(request) && result.ready
            }
        pendingMapResize = request to task
        mapResizeExecutor.execute(task)
        return task
    }

    /** 松手后串行调整尺寸，快速拖动只处理最新请求 */
    fun applyMapContentScale() {
        resizeMapDisplay(mapFullscreen)
    }

    /**
     * 状态页由用户手动重试当前默认地图，绑定触发时的会话与地图布局
     */
    fun retryMap() {
        val context = appContextRef ?: return
        val target = defaultMapComponent(context) ?: return
        val expected = composer ?: return
        val layout = mapLayoutGeneration.get()
        val request = beginDisplay()
        Bg.run("map-manual-retry") {
            fun current() = composer === expected && mapLayoutGeneration.get() == layout && displayState.owns(request)
            val ok =
                runCatching { startMapComponent(target, expected, ::current, userRequested = true) }
                    .onFailure { logSink?.invoke("LAUNCHER: 手动重试地图异常：${it.message}") }
                    .getOrDefault(false)
            if (ok && current() && (mapFullscreen || dashboardMode)) {
                synchronized(runningApps) { runningApps.add(target) }
                notifyRunningAppsChanged()
                val tile = if (mapFullscreen) DualDisplayComposer.TileLayout.FULLSCREEN else DualDisplayComposer.TileLayout.SPLIT
                expected.setSafeMapLayout(tile) {
                    publishDisplay(request, if (mapFullscreen) DockDisplayState.Page.MAP else DockDisplayState.Page.DASHBOARD, target)
                }
            } else {
                failDisplay(request)
            }
        }
    }

    /** 在地图屏切换地图应用，保留已有任务，后台调整全屏尺寸 */
    private fun launchMapOnMapDisplay(
        component: ComponentName,
        launchingComposer: DualDisplayComposer,
        request: DockDisplayState.Request,
    ): Boolean {
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        val displayId = mapDisplayId
        val previousDashboard = dashboardMode
        val previousFullscreen = mapFullscreen
        val generation = mapLayoutGeneration.incrementAndGet()

        fun current() =
            composer === launchingComposer &&
                mapDisplayId == displayId &&
                mapLayoutGeneration.get() == generation &&
                displayState.owns(request)
        var committed = false
        try {
            // 尺寸可提前准备，显示和触控仍保留原页面，只有地图核验成功才提交全屏
            val ok = startMapComponent(component, launchingComposer, ::current, fullscreen = true, userRequested = true)
            if (!current()) return false
            logSink?.invoke("LAUNCH(${component.packageName}): → 地图屏全屏 ok=$ok")
            if (!ok) return false
            dashboardMode = false
            mapFullscreen = true
            launchingComposer.setSafeMapLayout(DualDisplayComposer.TileLayout.FULLSCREEN) {
                if (current()) publishDisplay(request, DockDisplayState.Page.MAP, component)
            }
            committed = true
            notifyDashboard()
            notifyMapLayout()
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            appContextRef?.let {
                LetterboxHintController.scheduleProbe(it, "地图启动", displayIdHint = mapDisplayId)
            }
            return true
        } finally {
            // 返回失败和抛异常共用恢复路径，旧请求及断开会话不能回滚后来的页面
            if (!committed && current()) {
                dashboardMode = previousDashboard
                mapFullscreen = previousFullscreen
                resizeMapDisplay(previousFullscreen)
                launchingComposer.setSafeMapLayout(
                    when {
                        previousFullscreen -> DualDisplayComposer.TileLayout.FULLSCREEN
                        previousDashboard -> DualDisplayComposer.TileLayout.SPLIT
                        else -> DualDisplayComposer.TileLayout.HIDDEN
                    },
                )
                notifyDashboard()
                notifyMapLayout()
                logSink?.invoke("LAUNCHER: 地图启动未提交，保留原页面并请求恢复地图尺寸")
            }
        }
    }

    /** 仪表盘、全屏地图与桌面入口共用车载解析和验证，已有导航任务优先复用 */
    private fun startMapComponent(
        component: ComponentName,
        launchingComposer: DualDisplayComposer,
        isAllowed: () -> Boolean = { true },
        fullscreen: Boolean = mapFullscreen,
        userRequested: Boolean = false,
    ): Boolean {
        synchronized(mapLaunchLock) {
            if (mapHandoff.paused || mapHandoff.busy) return false
            val ctx = appContextRef ?: return false
            val displayId = mapDisplayId
            if (displayId < 0) return false

            fun current() =
                !mapHandoff.paused && !mapHandoff.busy && mapDisplayId == displayId && composer === launchingComposer && isAllowed()
            if (!current()) return false
            val previousSizeRequest = confirmedMapResize
            val resize = resizeMapDisplay(fullscreen)
            val sizeReady = runCatching { resize?.get(5, java.util.concurrent.TimeUnit.SECONDS) == true }.getOrDefault(false)
            if (!current()) return false
            if (!sizeReady) {
                logSink?.invoke("LAUNCHER: 地图尺寸尚未就绪，本次保留已有导航任务，请稍后手动重试")
                return false
            }
            AppOrientationCompat.awaitPending()
            if (!current()) return false

            val frameBeforeLaunch = launchingComposer.mapFrameMark()
            var frameBeforeResume = frameBeforeLaunch
            val useCarPage = component.packageName == MapLaunchResolver.AMAP && amapCarPage
            var snapshotKnown = false
            var recoveryAttempted = false

            fun snapshots(): List<MapLaunchResolver.TaskState> {
                if (!current()) return emptyList()
                val activityResult = Shell.exec("dumpsys activity activities", timeoutMs = 2500)
                val activities = activityResult.output
                if (activityResult.ok && observePhoneMap(activities, launchingComposer, component)) return emptyList()
                snapshotKnown = activityResult.ok && MapLaunchResolver.hasDisplay(activities, displayId)
                if (!current()) return emptyList()
                if (!snapshotKnown) return emptyList()
                var states = MapLaunchResolver.taskStates(activities, "", displayId)
                // 已有绘制记录时不再查询全部窗口，减少系统服务调用
                if (states.any { it.packageName == component.packageName && it.drawn == null }) {
                    val windows = Shell.exec("dumpsys window windows", timeoutMs = 2500)
                    if (!current()) return emptyList()
                    if (windows.ok) states = MapLaunchResolver.taskStates(activities, windows.output, displayId)
                }
                synchronized(mapStateLock) {
                    if (current()) lastMapTaskSnapshot = "时间=${SystemClock.uptimeMillis()} 状态=$states"
                }
                return states
            }

            fun correctPage(
                state: MapLaunchResolver.TaskState,
                car: Boolean,
            ): Boolean =
                state.packageName == component.packageName &&
                    if (car) {
                        state.page == "com.autonavi.map.activity.UcarMapActivity"
                    } else {
                        component.packageName != MapLaunchResolver.AMAP || !MapLaunchResolver.isCarPage(state.page)
                    }

            fun verify(
                car: Boolean,
                foregroundTask: Int? = null,
                initialSnapshot: List<MapLaunchResolver.TaskState>? = null,
            ): Boolean {
                var firstSnapshot = initialSnapshot
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (current() && SystemClock.uptimeMillis() < deadline) {
                    val states = firstSnapshot?.also { firstSnapshot = null } ?: snapshots()
                    val ready = states.firstOrNull { correctPage(it, car) && it.ready }
                    val obscured = states.firstOrNull { correctPage(it, car) && it.obscuredBy(states, userRequested) }
                    if (ready == null && obscured != null && !recoveryAttempted) {
                        recoveryAttempted = true
                        if (!current()) return false
                        // 切回高德后只认新的输入帧，不能沿用百度留下的图像
                        frameBeforeResume = launchingComposer.mapFrameMark()
                        val focused = Shell.exec("am task focus ${obscured.taskId} 2>&1", timeoutMs = 3000)
                        logSink?.invoke(
                            "MAP-STATE: 恢复被遮挡任务 display=$displayId task=${obscured.taskId} " +
                                "用户选择=$userRequested 生命周期已知=${obscured.lifecycleKnown} " +
                                "exit=${focused.exitCode} 结果=${focused.output.take(250)}",
                        )
                        if (!current()) return false
                    }
                    logSink?.invoke("MAP-STATE: display=$displayId 尺寸=$mapDisplaySize 事务=$confirmedMapResize 任务=$states")
                    if (ready != null) {
                        // 静止地图可能不主动出帧，不能据此强停或重新启动已有导航
                        val alreadyShown =
                            foregroundTask == ready.taskId &&
                                readyMapTask == (displayId to ready.taskId) &&
                                previousSizeRequest == confirmedMapResize &&
                                frameBeforeLaunch.sequence > 0
                        val fresh = !alreadyShown && launchingComposer.awaitMapFrame(frameBeforeResume, ::current)
                        logSink?.invoke(
                            "MAP-FRAME: display=$displayId task=${ready.taskId} 切换后新帧=$fresh 复用已确认画面=$alreadyShown " +
                                "基线=$frameBeforeResume 当前=${launchingComposer.mapFrameMark()}；帧内3D场景仍须实车确认",
                        )
                        if (current() && (fresh || alreadyShown)) {
                            synchronized(mapStateLock) {
                                if (!current()) return false
                                readyMapTask = displayId to ready.taskId
                                readyMapComponent = component
                            }
                            return true
                        }
                        return false
                    }
                    Thread.sleep(200L)
                }
                return false
            }

            val existing = snapshots()
            if (!current() || !snapshotKnown) return false
            val sameMode = existing.firstOrNull { correctPage(it, useCarPage) }
            val existingCar = existing.firstOrNull { it.packageName == component.packageName && MapLaunchResolver.isCarPage(it.page) }
            if (!useCarPage && existingCar != null) {
                logSink?.invoke("LAUNCHER: 高德车载任务仍在运行，保留导航，请手动选择所需地图模式")
                return false
            }
            if (sameMode != null || (useCarPage && existingCar != null)) {
                val task = sameMode ?: existingCar!!
                // 同屏其他任务已恢复且没有明确不可见时，按目标生命周期与用户选择恢复原任务
                // 恢复在核验循环中按实时快照执行，每个请求最多一次
                // 只有核验前就已处于前台的同一任务可复用，刚恢复的后台任务必须等待新帧
                return verify(useCarPage, task.taskId.takeIf { task.ready }, existing)
            }

            var commandAccepted = false

            fun startAndVerify(
                target: ComponentName,
                car: Boolean,
            ): Boolean {
                if (!current()) return false
                val result = Shell.exec(MapLaunchResolver.command(target, displayId, car, reuse = false), timeoutMs = 8000)
                logSink?.invoke(
                    "LAUNCHER: 地图启动 车载=$car 目标=${target.flattenToString()} display=$displayId " +
                        "exit=${result.exitCode} 结果=${result.output.take(700)}",
                )
                if (!current() ||
                    !result.ok ||
                    result.output.contains("Error:", true) ||
                    result.output.contains("Exception", true)
                ) {
                    return false
                }
                commandAccepted = true
                return verify(car)
            }
            var candidates = if (useCarPage) MapLaunchResolver.carCandidates(ctx, component) else emptyList()
            if (useCarPage && candidates.isEmpty()) {
                val resolved =
                    Shell.exec(
                        "cmd package resolve-activity --brief --user current -a ${MapLaunchResolver.ACTION} " +
                            "-c ${MapLaunchResolver.CATEGORY} -p ${MapLaunchResolver.AMAP} 2>&1",
                        timeoutMs = 4000,
                    )
                if (!current()) return false
                logSink?.invoke("LAUNCHER: UCAR 入口解析 exit=${resolved.exitCode} ${resolved.output.take(350)}")
                if (resolved.ok) candidates = listOfNotNull(MapLaunchResolver.resolvedCarComponent(resolved.output))
            }
            val ok =
                launchPreferredMap(candidates, component, allowNormalFallback = !useCarPage) { target, car ->
                    if (current() && !commandAccepted) {
                        startAndVerify(target, car)
                    } else {
                        false
                    }
                }
            if (!ok && current() && useCarPage && !commandAccepted) {
                logSink?.invoke("LAUNCHER: 高德车载页面未就绪，请检查高德授权与版本，或在默认地图设置选择普通地图后重连")
                showMapStatus(
                    ctx,
                    displayId,
                    "高德车载页面暂未打开\n请先在手机完成高德授权或登录\n若当前版本不支持车载页面，可在默认地图设置选择普通地图后重新连接",
                )
            }
            return ok
        }
    }

    /**
     * 地图启动失败时只更新当前地图屏，保留侧栏与其他应用的操作入口
     */
    private fun showMapStatus(
        context: Context,
        displayId: Int,
        message: String,
    ) {
        val component = ComponentName(context, MapStatusActivity::class.java).flattenToString()
        val result =
            Shell.exec(
                "am start -n ${shellQuote(component)} --display $displayId --windowingMode 1 " +
                    "-f 0x30000000 --es message ${shellQuote(message)} 2>&1",
                timeoutMs = 4000,
            )
        logSink?.invoke("LAUNCHER: 地图状态提示 display=$displayId exit=${result.exitCode} ${result.output.take(200)}")
    }

    /** 后台检查默认地图任务，有遮挡才恢复、无任务才启动，不因普通切页重发 UCAR 入口 */
    private fun ensureMapTask(
        enteringComposer: DualDisplayComposer,
        isAllowed: () -> Boolean,
    ) {
        if (composer !== enteringComposer || !isAllowed()) return
        if (mapDisplayId < 0) {
            // 单独记录地图屏是否存在，方便区分创建失败和应用未启动
            logSink?.invoke("LAUNCHER: 地图屏未创建（mapDisplayId=-1）——跳过默认地图启动")
            return
        }
        val ctx =
            appContextRef ?: run {
                logSink?.invoke("LAUNCHER: appContext 未就绪——跳过默认地图启动")
                return
            }
        val component =
            readyMapComponent ?: defaultMapComponent(ctx) ?: run {
                logSink?.invoke("LAUNCHER: 未找到任何地图应用（白名单内）——地图 tile 空")
                return
            }
        val ok =
            runCatching { startMapComponent(component, enteringComposer, isAllowed) }
                .onFailure { logSink?.invoke("LAUNCHER: 默认地图启动异常：${it.message}") }
                .getOrDefault(false)
        if (composer !== enteringComposer || !isAllowed()) return
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
        } else {
            synchronized(mapStateLock) {
                if (composer === enteringComposer && isAllowed()) readyMapComponent = null
            }
        }
        logSink?.invoke("LAUNCHER: 默认地图启动结果=" + ok + " display=" + mapDisplayId)
    }

    /**
     * 按超采样偏好启动或复用应用任务
     * 已有任务优先复用，仅归属明确的独占根任务可按显式偏好迁屏
     * 确认任务前台、新帧及合成提交后再切画面；误落手机时只做一次恢复，不清除手机任务
     */
    fun launchApp(component: ComponentName): Boolean = launchApp(component, null)

    /** 用户点击和组件回调共用任务检查，后台结果提交前核对请求代次 */
    private fun launchApp(
        component: ComponentName,
        observed: DockDisplayState.Request?,
        widgetApp: ObservedWidgetApp? = null,
    ): Boolean {
        val request =
            if (observed == null) {
                displayState.beginTarget(component.flattenToString())
            } else {
                displayState.beginObservedTarget(observed, component.flattenToString())
            }
        if (request == null) return false
        val startedAt = SystemClock.elapsedRealtime()
        displayListeners.forEach { runCatching { it() } }
        mapLayoutGeneration.incrementAndGet()
        val ok =
            runCatching {
                synchronized(appLaunchLock) {
                    logSink?.invoke(
                        "LAUNCH-TIME: 目标=${component.flattenToShortString()} 请求=$request 排队=${SystemClock.elapsedRealtime() - startedAt}ms",
                    )
                    launchAppNow(component, request, widgetApp)
                }
            }.onFailure { logSink?.invoke("LAUNCHER: 应用切换失败：${it.message}") }
                .getOrDefault(false)
        if (!ok) failDisplay(request)
        logSink?.invoke("LAUNCH-TIME: 请求=$request 核验结果=$ok 总耗时=${SystemClock.elapsedRealtime() - startedAt}ms")
        return ok
    }

    /**
     * 串行启动并在每次阻塞后核对选择凭据，避免旧启动结果覆盖新选择
     */
    private fun launchAppNow(
        component: ComponentName,
        request: DockDisplayState.Request,
        widgetApp: ObservedWidgetApp?,
    ): Boolean {
        if (appDisplayId < 0) return false
        val launchingComposer = composer ?: return false
        val startedAt = SystemClock.elapsedRealtime()
        if (!displayState.owns(request)) return false
        // 会话建立时的「强制横屏」覆盖是异步写的，而 am compat 会立即杀掉目标应用：
        // 必须等队列排空再启动，否则应用刚起来就被覆盖写入杀掉（表现为闪退/落回手机屏）。
        // 执行器单线程 FIFO，正常情况下覆盖早已写完，这里立即返回。
        AppOrientationCompat.awaitPending()
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        // 地图应用 → 地图屏全屏（任务常驻地图屏；从桌面 grid 点击同样去地图屏，
        // 与仪表盘共用同一任务，导航状态不丢）
        if (isMapPackage(component.packageName) && mapDisplayId >= 0) {
            return launchMapOnMapDisplay(component, launchingComposer, request)
        }
        val ctx = appContextRef
        val qqCarRequested =
            widgetApp == null &&
                ctx != null &&
                component.packageName == PhoneCarPages.QQ_MUSIC &&
                PhoneCarPages.qqCarEnabled(ctx)
        val qqCarTarget = if (qqCarRequested) ctx.let { PhoneCarPages.qqCarComponent(it) } else null
        if (qqCarRequested && qqCarTarget == null) {
            logSink?.invoke("LAUNCHER: QQ 音乐当前安装包没有可用的 UCAR 车载页面，请在设置中选择普通页面")
            return false
        }
        val qqCarSelection = if (widgetApp == null && component.packageName == PhoneCarPages.QQ_MUSIC) qqCarTarget != null else null

        fun matchesSelectedPage(page: String): Boolean = qqCarSelection == null || PhoneCarPages.isQqCarPage(page) == qqCarSelection

        var wantHi =
            if (widgetApp != null) {
                widgetApp.task.sourceDisplay == appHiDisplayId
            } else if (qqCarTarget != null && ctx != null && SupersamplePrefs.mode(ctx, component) == SupersamplePrefs.MODE_AUTO) {
                false
            } else {
                ctx != null &&
                    appHiDisplayId >= 0 &&
                    SupersamplePrefs.shouldSupersample(ctx, component)
            }
        var targetDisplay = if (wantHi) appHiDisplayId else appDisplayId
        val preferredDisplay = targetDisplay
        // 只在任务快照成功且目标屏存在时决定新建，查询失败不能当作没有任务
        val tasks = Shell.exec("dumpsys activity activities", timeoutMs = 4000)
        logSink?.invoke("LAUNCH-TIME: 请求=$request 方向准备与任务查询=${SystemClock.elapsedRealtime() - startedAt}ms")
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        if (!tasks.ok || !MapLaunchResolver.hasDisplay(tasks.output, targetDisplay)) return false
        val otherDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
        val reusable =
            if (widgetApp != null) {
                AppLaunchRecovery
                    .reusableTask(tasks.output, component.packageName, targetDisplay, -1)
                    ?.takeIf { it == widgetApp.task }
                    ?: return false
            } else {
                AppLaunchRecovery.reusableTask(tasks.output, component.packageName, targetDisplay, otherDisplay) {
                    matchesSelectedPage(it)
                }
            }
        val onTarget = AppLaunchRecovery.hasTask(tasks.output, component.packageName, targetDisplay)
        if (reusable != null) {
            targetDisplay = reusable.sourceDisplay
            wantHi = targetDisplay == appHiDisplayId
        } else if (listOf(targetDisplay, otherDisplay).filter { it >= 0 }.any { display ->
                if (qqCarSelection == null) {
                    AppLaunchRecovery.hasTask(tasks.output, component.packageName, display)
                } else {
                    MapLaunchResolver.taskStates(tasks.output, "", display, component.packageName, detailedOnly = true).any {
                        matchesSelectedPage(it.page)
                    }
                }
            }
        ) {
            val evidence =
                listOf(targetDisplay, otherDisplay).filter { it >= 0 }.associateWith {
                    MapLaunchResolver.taskStates(tasks.output, "", it, component.packageName, detailedOnly = true)
                }
            logSink?.invoke("APP-REUSE: 请求=$request 已有任务归属不明确，保留任务且不重复新建，实际活动=$evidence")
            return false
        }
        val nativeBeforeLaunch = launchingComposer.appFrameMark(false)
        val hiBeforeLaunch = launchingComposer.appFrameMark(true)
        val before = if (wantHi) hiBeforeLaunch else nativeBeforeLaunch
        var frameBoundary = widgetApp?.boundary ?: before
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        val initialGeometry = runtimeGeometry[targetDisplay]
        val sameConfirmedApp =
            reusable != null &&
                initialGeometry != null &&
                confirmedAppTasks[targetDisplay] ==
                ConfirmedAppTask(launchingComposer, component.packageName, reusable.taskId, initialGeometry, qqCarSelection) &&
                before.sequence > 0 &&
                !dashboardMode &&
                !mapFullscreen &&
                displayedContent == DockDisplayState.Content(DockDisplayState.Page.APP, component.flattenToString()) &&
                launchingComposer.isActiveAppHi() == wantHi &&
                MapLaunchResolver.taskStates(tasks.output, "", targetDisplay, component.packageName).any {
                    it.taskId == reusable.taskId && it.resumed && it.visible != false && matchesSelectedPage(it.page)
                }
        val explicitDisplay = widgetApp == null && ctx != null && SupersamplePrefs.mode(ctx, component) != SupersamplePrefs.MODE_AUTO
        if (sameConfirmedApp && (!explicitDisplay || targetDisplay == preferredDisplay)) {
            publishDisplay(request, DockDisplayState.Page.APP, component)
            logSink?.invoke("APP-REUSE: 请求=$request 当前任务已确认显示，跳过重复启动与切屏")
            return true
        }

        /** 复用时保留任务身份，仅归属明确的独占根任务可按显式偏好迁屏 */
        fun focusExisting(
            task: AppLaunchRecovery.Move,
            dump: String,
            coldStart: Boolean = false,
        ): Shell.Result {
            targetDisplay = task.sourceDisplay
            wantHi = targetDisplay == appHiDisplayId
            frameBoundary =
                if (coldStart) {
                    if (wantHi) hiBeforeLaunch else nativeBeforeLaunch
                } else {
                    launchingComposer.appFrameMark(wantHi)
                }
            if (explicitDisplay && targetDisplay != preferredDisplay) {
                val move =
                    AppLaunchRecovery.candidate(
                        dump,
                        component.packageName,
                        component.className,
                        "",
                        preferredDisplay,
                        setOf(task.sourceDisplay),
                    ) ?: return Shell.Result(-1, "已有任务无法按显式屏幕偏好安全迁移")
                frameBoundary = launchingComposer.appFrameMark(preferredDisplay == appHiDisplayId)
                if (composer !== launchingComposer || !displayState.owns(request)) return Shell.Result(-1, "切换请求已过期")
                val moved = Shell.exec("am display move-stack ${move.rootTaskId} $preferredDisplay 2>&1", timeoutMs = 3000)
                if (composer !== launchingComposer || !displayState.owns(request)) return Shell.Result(-1, "切换请求已过期")
                if (!AppLaunchRecovery.operationAccepted(moved.ok, moved.output)) return moved
                val readback = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                if (composer !== launchingComposer || !displayState.owns(request)) return Shell.Result(-1, "切换请求已过期")
                if (!readback.ok ||
                    !MapLaunchResolver.taskStates(readback.output, "", preferredDisplay, component.packageName).any {
                        it.taskId == task.taskId
                    }
                ) {
                    return Shell.Result(-1, "任务迁移未通过目标屏回读")
                }
                targetDisplay = preferredDisplay
                wantHi = targetDisplay == appHiDisplayId
            }
            if (composer !== launchingComposer || !displayState.owns(request)) return Shell.Result(-1, "切换请求已过期")
            return Shell.exec("am task focus ${task.taskId} 2>&1", timeoutMs = 3000)
        }
        val targetComponent = qqCarTarget ?: component
        val target =
            if (component.packageName == "com.autonavi.amapauto") {
                "${shellQuote("com.autonavi.amapauto/.MainMapActivity")} --windowingMode 1"
            } else {
                shellQuote(targetComponent.flattenToShortString())
            }
        val result =
            if (widgetApp != null) {
                // 原 PendingIntent 已打开页面，直接核验原任务，不重跑入口或聚焦另一屏
                Shell.Result(0, "官方组件已打开任务 ${widgetApp.task.taskId}，核验实际屏 $targetDisplay")
            } else if (reusable != null) {
                // 恢复原任务而非重新执行启动入口，不清空播放页面或创建另一个任务
                focusExisting(reusable, tasks.output)
            } else {
                // MULTIPLE_TASK：即使该应用在其他屏有任务，也强制在目标屏新建实例
                val carArguments = if (qqCarTarget != null) PhoneCarPages.qqCarArguments() else ""
                Shell.exec("am start -W -n $target$carArguments --display $targetDisplay -f 0x18000000 2>&1", timeoutMs = 8000)
            }
        var ok =
            if (reusable != null) {
                AppLaunchRecovery.operationAccepted(result.ok, result.output)
            } else {
                MapLaunchResolver.commandAccepted(result.ok, result.output)
            }
        logSink?.invoke(
            "APP-REUSE: 请求=$request 偏好屏=$preferredDisplay 实际屏=$targetDisplay 复用任务=${reusable?.taskId} 命令接受=$ok",
        )
        logSink?.invoke("LAUNCH-TIME: 请求=$request 启动命令累计=${SystemClock.elapsedRealtime() - startedAt}ms 接受=$ok")
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        var lastSnapshot = "命令未被接受"
        var recoveredTask: Int? = reusable?.taskId
        var confirmedTaskId: Int? = null
        if (ok) {
            ok = false
            var deadline = SystemClock.uptimeMillis() + 3_000L
            for (attempt in 0 until 12) {
                val foreground = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                if (composer !== launchingComposer || !displayState.owns(request)) return false
                val targetStates = MapLaunchResolver.taskStates(foreground.output, "", targetDisplay, component.packageName)
                lastSnapshot = "查询退出=${foreground.exitCode} 任务=$targetStates"
                val recoveredReady =
                    recoveredTask == null ||
                        targetStates.any {
                            it.taskId == recoveredTask && it.resumed && it.visible != false
                        }
                val foregroundTask =
                    targetStates
                        .filter {
                            it.resumed &&
                                it.visible != false &&
                                (recoveredTask == null || it.taskId == recoveredTask) &&
                                matchesSelectedPage(it.page)
                        }.distinctBy { it.taskId }
                        .singleOrNull()
                if (foreground.ok &&
                    recoveredReady &&
                    foregroundTask != null &&
                    MapLaunchResolver.activePage(foreground.output, targetDisplay, component.packageName)?.let {
                        matchesSelectedPage(it)
                    } == true
                ) {
                    confirmedTaskId = foregroundTask.taskId
                    ok = true
                    break
                }
                // 冷启动入口若转到另一块应用屏，先恢复该屏真实任务，不再向空目标反复新建
                if (attempt == 1 && qqCarSelection == null && reusable == null && recoveredTask == null && foreground.ok) {
                    val actual = AppLaunchRecovery.reusableTask(foreground.output, component.packageName, targetDisplay, otherDisplay)
                    if (actual != null) {
                        val focused = focusExisting(actual, foreground.output, coldStart = true)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        logSink?.invoke(
                            "APP-REUSE: 请求=$request 启动后实际屏=$targetDisplay 任务=${actual.taskId} 聚焦退出=${focused.exitCode}",
                        )
                        if (!AppLaunchRecovery.operationAccepted(focused.ok, focused.output)) break
                        recoveredTask = actual.taskId
                    }
                }
                // 正常启动不增加命令，短暂等待后只对本次明确落错屏的独占根任务恢复一次
                if (attempt == 2 && qqCarSelection == null && recoveredTask == null && foreground.ok) {
                    val sources = setOf(0, appDisplayId, appHiDisplayId) - targetDisplay
                    val move =
                        AppLaunchRecovery.candidate(
                            foreground.output,
                            component.packageName,
                            component.className,
                            result.output,
                            targetDisplay,
                            sources,
                        )
                    if (move != null) {
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        frameBoundary = launchingComposer.appFrameMark(wantHi)
                        val moved = Shell.exec("am display move-stack ${move.rootTaskId} $targetDisplay 2>&1", timeoutMs = 3000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        logSink?.invoke(
                            "APP-RECOVERY: 请求=$request 目标=${component.packageName} 来源屏=${move.sourceDisplay} " +
                                "根任务=${move.rootTaskId} 活动任务=${move.taskId} 目标屏=$targetDisplay " +
                                "迁移退出=${moved.exitCode} 输出=${moved.output.take(300)}",
                        )
                        if (!AppLaunchRecovery.operationAccepted(moved.ok, moved.output)) break
                        val readback = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        val arrived =
                            readback.ok &&
                                MapLaunchResolver
                                    .taskStates(readback.output, "", targetDisplay, component.packageName)
                                    .any { it.taskId == move.taskId }
                        logSink?.invoke("APP-RECOVERY: 请求=$request 迁移目标屏回读=$arrived 查询退出=${readback.exitCode}")
                        if (!arrived) break
                        val focused = Shell.exec("am task focus ${move.taskId} 2>&1", timeoutMs = 3000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        logSink?.invoke(
                            "APP-RECOVERY: 请求=$request 聚焦退出=${focused.exitCode} 输出=${focused.output.take(300)}，继续核验前台与新图像",
                        )
                        if (!AppLaunchRecovery.operationAccepted(focused.ok, focused.output)) break
                        recoveredTask = move.taskId
                        deadline = SystemClock.uptimeMillis() + 3_000L
                    } else {
                        val observed =
                            sources.filter { it >= 0 }.associateWith {
                                MapLaunchResolver.taskStates(foreground.output, "", it, component.packageName)
                            }
                        logSink?.invoke("APP-RECOVERY: 请求=$request 未找到可核验的独占根任务，其他屏任务=$observed")
                    }
                }
                if (SystemClock.uptimeMillis() >= deadline) break
                Thread.sleep(150L)
            }
        }
        var reuseConfirmedFrame = false
        if (ok) {
            refreshRuntimeGeometry(launchingComposer)
            val geometry = runtimeGeometry[targetDisplay]
            reuseConfirmedFrame = reusable != null &&
                reusable.sourceDisplay == targetDisplay &&
                geometry != null &&
                confirmedAppTasks[targetDisplay] ==
                ConfirmedAppTask(launchingComposer, component.packageName, reusable.taskId, geometry, qqCarSelection) &&
                MapLaunchResolver.taskStates(tasks.output, "", targetDisplay, component.packageName).any {
                    it.taskId == reusable.taskId && it.resumed && it.visible != false && matchesSelectedPage(it.page)
                } &&
                launchingComposer.appFrameMark(wantHi).sequence > 0
            ok = reuseConfirmedFrame ||
                launchingComposer.awaitAppFrame(wantHi, frameBoundary) {
                    composer === launchingComposer && displayState.owns(request)
                }
            // 实车存在任务已恢复但原生输入始终为零的情况，自动模式仅安全迁移一次到已有图像的另一应用屏
            // 不推断厂商未出帧的根因，不放宽首帧门槛，也不迁移混合包或系统组织器管理的根任务
            val fallbackDisplay = if (targetDisplay == appHiDisplayId) appDisplayId else appHiDisplayId
            if (!ok &&
                qqCarSelection == null &&
                widgetApp == null &&
                !explicitDisplay &&
                fallbackDisplay > 0 &&
                launchingComposer.appFrameMark(wantHi).sequence == 0L &&
                launchingComposer.appFrameMark(fallbackDisplay == appHiDisplayId).sequence > 0L &&
                composer === launchingComposer &&
                displayState.owns(request)
            ) {
                val fresh = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                if (composer !== launchingComposer || !displayState.owns(request)) return false
                val move =
                    if (fresh.ok) {
                        AppLaunchRecovery
                            .candidate(
                                fresh.output,
                                component.packageName,
                                component.className,
                                result.output,
                                fallbackDisplay,
                                setOf(targetDisplay),
                            )?.takeIf { it.taskId == confirmedTaskId }
                    } else {
                        null
                    }
                if (move != null) {
                    frameBoundary = launchingComposer.appFrameMark(fallbackDisplay == appHiDisplayId)
                    val moved = Shell.exec("am display move-stack ${move.rootTaskId} $fallbackDisplay 2>&1", timeoutMs = 3000)
                    if (composer !== launchingComposer || !displayState.owns(request)) return false
                    var recovered = AppLaunchRecovery.operationAccepted(moved.ok, moved.output)
                    if (recovered) {
                        val readback = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        recovered = readback.ok &&
                            MapLaunchResolver
                                .taskStates(
                                    readback.output,
                                    "",
                                    fallbackDisplay,
                                    component.packageName,
                                    detailedOnly = true,
                                ).any { it.taskId == move.taskId }
                    }
                    if (recovered) {
                        val focused = Shell.exec("am task focus ${move.taskId} 2>&1", timeoutMs = 3000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        val readback = Shell.exec("dumpsys activity activities", timeoutMs = 2000)
                        if (composer !== launchingComposer || !displayState.owns(request)) return false
                        val states =
                            MapLaunchResolver.taskStates(
                                readback.output,
                                "",
                                fallbackDisplay,
                                component.packageName,
                                detailedOnly = true,
                            )
                        recovered = AppLaunchRecovery.operationAccepted(focused.ok, focused.output) &&
                            readback.ok &&
                            states.any { it.taskId == move.taskId && it.resumed && it.visible != false }
                        if (recovered) {
                            targetDisplay = fallbackDisplay
                            wantHi = targetDisplay == appHiDisplayId
                            recoveredTask = move.taskId
                            lastSnapshot = "零输入恢复，任务=$states"
                            ok =
                                launchingComposer.awaitAppFrame(wantHi, frameBoundary) {
                                    composer === launchingComposer && displayState.owns(request)
                                }
                        }
                    }
                    logSink?.invoke(
                        "APP-RECOVERY: 请求=$request 零输入迁移 ${move.sourceDisplay}→$fallbackDisplay 任务=${move.taskId} 回读=$recovered 新图像=$ok",
                    )
                }
            }
        }
        if (composer !== launchingComposer || !displayState.owns(request)) return false
        logSink?.invoke(
            "LAUNCH(${component.packageName}): hi=$wantHi disp=$targetDisplay onTarget=$onTarget ok=$ok " +
                "命令退出=${result.exitCode} 前台快照=$lastSnapshot 输入帧=${launchingComposer.appFrameMark(wantHi)} " +
                "启动前帧=$before 核验基线=$frameBoundary 恢复任务=$recoveredTask 复用已确认画面=$reuseConfirmedFrame out=${result.output.take(4096)}",
        )
        if (ok) {
            synchronized(runningApps) { runningApps.add(component) }
            notifyRunningAppsChanged()
            var exitedDashboard = false
            // 与退役共用锁，旧请求只能向自己捕获的合成器提交，不能改写重连后的布局
            synchronized(mapStateLock) {
                if (composer !== launchingComposer || !displayState.owns(request)) return false
                // 普通应用确认后才退出仪表盘并隐藏地图层，保留原来的成功提交顺序
                if (dashboardMode || mapFullscreen) {
                    dashboardMode = false
                    mapFullscreen = false
                    exitedDashboard = true
                    launchingComposer.setSafeMapLayout(DualDisplayComposer.TileLayout.HIDDEN)
                }
                launchingComposer.requestActiveApp(
                    targetDisplay == appHiDisplayId,
                    if (reuseConfirmedFrame) null else frameBoundary,
                    reuseConfirmedFrame = reuseConfirmedFrame,
                ) {
                    val geometry = runtimeGeometry[targetDisplay]
                    val taskId = confirmedTaskId
                    synchronized(mapStateLock) {
                        if (composer === launchingComposer && displayState.owns(request) && geometry != null && taskId != null) {
                            confirmedAppTasks[targetDisplay] =
                                ConfirmedAppTask(launchingComposer, component.packageName, taskId, geometry, qqCarSelection)
                        }
                    }
                    publishDisplay(request, DockDisplayState.Page.APP, component)
                }
            }
            if (exitedDashboard) {
                notifyDashboard()
                logSink?.invoke("LAUNCHER: 退出仪表盘（普通应用已打开）")
            }
            // 应用已在运行、被系统按旧配置带回前台的情况：启动后探测一次是否仍
            // letterbox（提示卡只对「应用横屏」名单内的应用生效，其它应用不计）
            val ctx = appContextRef
            if (ctx != null) {
                LetterboxHintController.scheduleProbe(ctx, "应用启动", displayIdHint = targetDisplay)
            }
        } else {
            Log.w(TAG, "启动失败：${result.output.take(120)}")
            recordInteractionDiagnostics("应用未确认显示：${component.flattenToShortString()}")
        }
        return ok
    }

    // ICU 汉字→拼音转写（Android 内置，无需第三方拼音库）。
    // "Han-Latin" 输出带声调，追加 Latin-ASCII 去音调（gāo → gao）。
    // Transliterator 非线程安全，统一加锁访问。
    private val hanLatinLock = Any()
    private val hanLatin by lazy {
        android.icu.text.Transliterator
            .getInstance("Han-Latin; Latin-ASCII")
    }

    private fun toLatin(text: String): String =
        synchronized(hanLatinLock) {
            runCatching { hanLatin.transliterate(text) }.getOrDefault(text)
        }

    /** 排序键：全名转拼音小写、仅保留字母数字（数字 < 字母，即 # 组自然在前）。 */
    private fun pinyinKey(label: String): String = toLatin(label).lowercase().filter { it.isLetterOrDigit() }

    /** 拼音排序键（设置页等外部列表按字母分组排序用）。 */
    fun sortKeyOf(label: String): String = pinyinKey(label)

    @Volatile
    private var appsCache: List<AppEntry>? = null

    private val appsRevision = AtomicLong()
    val appsCacheVersion: Long get() = appsRevision.get()

    /** 应用清单缓存（桌面与车机设置共享；安装/卸载广播时失效）。 */
    private val appsLock = Any()
    private val appScanLock = Any()
    private val appsListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /**
     * 后台扫描串行，主线程失效通知只持有短暂的发布锁
     */
    fun cachedApps(context: Context): List<AppEntry> =
        synchronized(appScanLock) {
            appsCache?.let { return@synchronized it }
            val revision = appsRevision.get()
            val scanned = scanLaunchableApps(context)
            synchronized(appsLock) { if (revision == appsRevision.get()) appsCache = scanned }
            scanned
        }

    /**
     * 可见界面订阅共享应用缓存变化
     */
    fun addAppsListener(listener: () -> Unit) {
        appsListeners += listener
    }

    /**
     * 隐藏界面停止接收包变化通知
     */
    fun removeAppsListener(listener: () -> Unit) {
        appsListeners -= listener
    }

    /** 只读应用缓存，不触发扫描；缓存缺失时由调用方异步加载 */
    fun peekCachedApps(): List<AppEntry>? = appsCache

    /** 包变化后清缓存并通知可见页面，旧扫描不发布过期结果 */
    fun invalidateAppsCache() {
        synchronized(appsLock) {
            appsCache = null
            appsRevision.incrementAndGet()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post { appsListeners.forEach { it() } }
    }

    private fun scanLaunchableApps(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent =
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
        // 拼音键每应用只算一次（此前在比较器里逐对重复转写，150 应用 ≈ 上千次 ICU 调用）
        return pm
            .queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .map { info ->
                val label = OfficialCarApps.displayLabel(info.packageName, info.loadLabel(pm).toString())
                AppEntry(
                    component = ComponentName(info.packageName, info.name),
                    label = label,
                    icon = info.loadIcon(pm),
                ) to pinyinKey(label)
            }.take(200)
            .toList()
            .sortedBy { it.second }
            .map { it.first }
    }

    /** 字母导航条分组键：数字→"#"，ASCII 字母→大写字母，汉字→拼音首字母。 */
    fun indexLetterOf(label: String): String {
        val first = label.firstOrNull() ?: return "#"
        return when {
            first.isDigit() -> "#"
            first.isLetter() && first.code < 128 -> first.uppercaseChar().toString()
            else -> {
                val latin = toLatin(first.toString()).lowercase()
                latin.firstOrNull { it in 'a'..'z' }?.uppercaseChar()?.toString() ?: "#"
            }
        }
    }

    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    /** 应用上下文（诊断与回退链路需要）。 */
    @Volatile
    private var appContextRef: android.content.Context? = null

    /** 设置虚拟屏忽略应用方向请求，写入 true 或 false 后读回确认 */
    private fun applyIgnoreOrientationRequest(vararg ids: Int) {
        val targets = ids.filter { it >= 0 }
        if (targets.isEmpty()) return
        val failed = targets.filter { id -> !setIgnoreOrientationRequest(id, true) }
        if (failed.isEmpty()) {
            logSink?.invoke("LAUNCHER: 应用屏方向策略已设置 ${targets.joinToString("/")}（读回不可用时仅表示窗口服务接受）")
        } else {
            logSink?.invoke(
                "LAUNCHER: ⚠ 应用屏忽略方向请求失败 $failed（读回非 true）" +
                    "——竖屏应用可能旋转虚拟屏，导致画面比例异常与触摸漂移",
            )
        }
    }

    /**
     * 设置单屏的忽略方向策略
     *
     * @param verify 建立会话时读回确认，清理时跳过读取
     * @return 未检查时返回 true，只表示命令已发送
     */
    private fun setIgnoreOrientationRequest(
        displayId: Int,
        enabled: Boolean,
        verify: Boolean = true,
    ): Boolean {
        if (displayId < 0) return true
        val value = if (enabled) "true" else "false"
        val accepted =
            runCatching { CarInputClient.service?.setDisplayIgnoreOrientation(displayId, enabled) == true }
                .getOrDefault(false)
        val written =
            if (accepted) {
                null
            } else {
                Shell.exec(
                    "cmd window set-ignore-orientation-request -d $displayId $value 2>&1",
                    timeoutMs = 3_000,
                )
            }
        if (!verify) return true
        var readBack = Shell.exec("cmd window get-ignore-orientation-request -d $displayId 2>&1", timeoutMs = 3_000)
        var actual = OrientationReadback.parse(readBack.output)
        // 厂商窗口服务可能接受调用却未落地，明确读回相反值时再走一次系统命令并复核
        val repaired =
            if (accepted && actual != null && actual != enabled) {
                val result = Shell.exec("cmd window set-ignore-orientation-request -d $displayId $value 2>&1", timeoutMs = 3_000)
                readBack = Shell.exec("cmd window get-ignore-orientation-request -d $displayId 2>&1", timeoutMs = 3_000)
                actual = OrientationReadback.parse(readBack.output)
                result
            } else {
                null
            }
        val unsupported =
            !accepted && Regex("(?i)unknown command|not supported|unsupported|no shell command").containsMatchIn(written?.output.orEmpty())
        val callAccepted = accepted || (written?.ok == true && !unsupported)
        logSink?.invoke(
            "LAUNCHER: 方向策略 display=$displayId 窗口服务接受=$accepted " +
                "结论=${OrientationReadback.describe(callAccepted, enabled, actual, unsupported)} " +
                "实际=$actual 命令结果=${written?.output?.take(180)} 补写=${repaired?.output?.take(180)} " +
                "读回=${readBack.output.take(180)}",
        )
        return actual?.let { it == enabled } ?: (callAccepted && repaired == null)
    }

    data class AppEntry(
        val component: ComponentName,
        val label: String,
        val icon: Drawable,
    )
}
