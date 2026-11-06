package io.github.shaonialife.buicklinkplus.input

import android.content.Context
import io.github.shaonialife.buicklinkplus.input.TouchReports.Contact
import io.github.shaonialife.buicklinkplus.launcher.CardGesture
import io.github.shaonialife.buicklinkplus.launcher.LetterboxHintController
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.resolveCardGesture
import io.github.shaonialife.buicklinkplus.launcher.settings.DisplayPrefs
import io.github.shaonialife.buicklinkplus.render.CARD_MARGIN_PX
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.LeftWidgetColumn
import io.github.shaonialife.buicklinkplus.render.MusicIsland
import io.github.shaonialife.buicklinkplus.shizuku.CarInputClient

/** 按提交画面的区域把车机触摸路由到 Dock、应用或地图虚拟屏，经 shell 服务注入 */
object TouchRouter {
    /** 诊断日志出口（由 MainActivity 接到文件日志）。 */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    private var diagAt = 0L

    private fun diag(message: String) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - diagAt < 1000L) return
        diagAt = now
        logSink?.invoke("TOUCH: $message")
    }

    /** 岛内滑动切换内容的最小横向位移（内容区像素） */
    private const val ISLAND_SWIPE_PX = 40f

    /** 滑动判定：横向位移达到阈值且大于纵向 */
    private fun islandSwipe(
        x: Float,
        y: Float,
    ): Boolean {
        val dx = x - islandPressX
        val dy = y - islandPressY
        return kotlin.math.abs(dx) >= ISLAND_SWIPE_PX && kotlin.math.abs(dx) > kotlin.math.abs(dy)
    }

    /** 上滑隐藏判定：纵向向上位移达到阈值且大于横向 */
    private fun islandSwipeUp(
        x: Float,
        y: Float,
    ): Boolean {
        val dx = x - islandPressX
        val dy = y - islandPressY
        return dy <= -ISLAND_SWIPE_PX && -dy > kotlin.math.abs(dx)
    }

    private val gesture = PointerGesture()
    private val pressed: Boolean get() = gesture.activeTarget != null
    private val contactTracker = TouchReports.ContactTracker()
    private val pressedDisplay: Int get() = gesture.activeTarget?.displayId ?: -1
    private var gestureBinder: android.os.IBinder? = null
    private var failStreak = 0

    /** 叠加卡片手势状态（卡片命中后独占触摸，不再注入应用）。 */
    private var overlayPressed = false

    /** 音乐岛手势状态（岛命中后独占触摸，模式同叠加卡片）。 */
    private var islandPressed = false

    /** 左侧小组件列手势状态（命中后独占触摸）。 */
    private var widgetPressed = false

    /** 岛内按下起点（内容区像素），抬手时判定滑动切换内容 */
    private var islandPressX = 0f

    private var islandPressY = 0f

    /** 分屏把手粘性路由状态（命中把手条后整条触摸流固定路由应用屏）。 */
    private var splitHandlePressed = false

    /** MOVE 节流间隔，0 表示关闭；DOWN、UP 和跨屏切换不节流，UP 保留最终坐标 */
    @Volatile
    var moveThrottleMs: Long = 24L

    @Volatile
    private var throttleInited = false

    private var lastInjectAt = 0L
    private var throttledCount = 0
    private var lastThrottleLogAt = 0L
    private var lastSlowInjectLogAt = 0L

    @Volatile
    private var geometry: DisplayGeometry? = null
    private val safeGesture = SafeGestureGate()

    /** 使用与合成器相同的视图、流和安全区几何 */
    @Synchronized
    fun setGeometry(value: DisplayGeometry) {
        geometry = value
        safeGesture.reset()
    }

    @Synchronized
    fun onContact(
        context: Context,
        contact: Contact,
    ) {
        // 按活动槽区分占位和释放，不能因坐标为原点就丢掉真实抬起
        if (!contactTracker.accept(contact)) return
        if (!contact.down) gesture.deferRelease()
        val g = geometry ?: return
        val sx = g.streamX(contact.xPixels)
        val sy = g.streamY(contact.yPixels)
        // 先反算悬浮面板，再走原地图、把手与超采样路由，圆角和间隙不能穿透点击
        val panels = VirtualLauncher.floatingPanelLayout()
        // 音乐岛命中域用未钳位的内容区坐标（面板间隙也要能点到岛），并入安全手势的内点判定
        val island = MusicIsland.active
        val islandX = (sx - g.content.left).toFloat()
        val islandY = (sy - g.content.top).toFloat()
        val islandHit = island != null && island.contains(islandX, islandY)
        // 左侧小组件列（安全区外留白）用流像素命中，可点按板块在此分发
        val widgets = LeftWidgetColumn.active
        val widgetsHit = widgets != null && widgets.contains(sx.toFloat(), sy.toFloat())
        // 几何切换时先释放旧注入，并等待本次物理抬起，禁止拖动中途跳坐标或补发点击
        val logical = panels?.logicalPoint((sx - g.content.left).toFloat(), (sy - g.content.top).toFloat())
        if (!safeGesture.accept(contact.down, widgetsHit || islandHit || (g.content.contains(sx, sy) && logical != null), panels)) {
            gesture.deferRelease()
            gesture.activeTarget?.let { target ->
                runCatching {
                    gesture.dispatch(false, target) { _, previous ->
                        CarInputClient.service?.pointerUp(previous.displayId, previous.x, previous.y) == true
                    }
                }
            }
            overlayPressed = false
            islandPressed = false
            widgetPressed = false
            splitHandlePressed = false
            cancelMapTileTap()
            return
        }
        // 左侧小组件列优先消费触摸：模式同叠加卡片手势独占
        run {
            when (resolveCardGesture(widgets != null, widgetPressed, contact.down, widgetsHit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> widgetPressed = false
                CardGesture.CONSUME -> {
                    widgetPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    widgetPressed = false
                    widgets?.onTap(sx.toFloat(), sy.toFloat(), context)
                    return
                }
            }
        }
        // 音乐岛（最上层浮层）优先消费触摸：模式同叠加卡片手势独占
        run {
            when (resolveCardGesture(island != null, islandPressed, contact.down, islandHit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> islandPressed = false
                CardGesture.CONSUME -> {
                    if (contact.down) {
                        islandPressX = islandX
                        islandPressY = islandY
                    } else if (island != null) {
                        // 滑出岛外才抬手的手势同样生效：上滑隐藏优先于横滑切换
                        if (islandSwipeUp(islandX, islandY)) {
                            island.dismiss()
                        } else if (islandSwipe(islandX, islandY)) {
                            island.onSwipe()
                        }
                    }
                    islandPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    islandPressed = false
                    if (islandSwipeUp(islandX, islandY)) {
                        island?.dismiss()
                    } else if (islandSwipe(islandX, islandY)) {
                        island?.onSwipe()
                    } else {
                        island?.onTap(islandX, islandY, context)
                    }
                    return
                }
            }
        }
        val px = logical?.first ?: return
        val py = logical.second

        // 叠加卡片（「强制横屏未生效」提示）优先消费触摸：它画在应用区的黑边上，
        // 必须先于注入判定命中，否则触摸会穿透到下面的应用。
        run {
            val visible = LetterboxHintController.isVisible()
            val hit = visible && LetterboxHintController.contains(px.toFloat(), py.toFloat())
            when (resolveCardGesture(visible, overlayPressed, contact.down, hit, appGestureActive = pressed)) {
                CardGesture.NOT_HANDLED -> overlayPressed = false
                CardGesture.CONSUME -> {
                    overlayPressed = contact.down
                    return
                }
                CardGesture.TAP -> {
                    overlayPressed = false
                    LetterboxHintController.onTap()
                    return
                }
            }
        }

        val launcher = VirtualLauncher
        val dockW = launcher.dockWidthPx
        if (dockW <= 0 || launcher.appDisplayId < 0 || launcher.dockDisplayId < 0) return
        var svc = CarInputClient.service
        if (svc == null) {
            // 主动触发重绑（手机重启/内置服务重启后 binder 丢失）
            CarInputClient.ensure(context)
            svc = CarInputClient.service
        }
        if (svc == null) {
            failStreak++
            diag(
                "注入服务不可用（连续 $failStreak 次，自动重绑中；" +
                    "若持续失效请返回本软件重新检查服务）",
            )
            return
        }
        val binder = svc.asBinder()
        if (gestureBinder !== binder) {
            // 注入服务重启后没有旧手势，新服务的第一笔必须重新发送 DOWN
            gesture.reset()
            gestureBinder = binder
        }
        failStreak = 0
        // 横屏 Dock 在左，竖屏 Dock 在底部；地图区域优先于应用区
        // 地图坐标平移，其余坐标还要换算应用屏的超采样倍率
        var inDock: Boolean
        var targetDisplay = -1
        var localX = 0
        var localY = 0
        var routedToMap = false
        val mapLayout = launcher.presentedMapLayout()
        val mapFullscreen = mapLayout == DualDisplayComposer.TileLayout.FULLSCREEN
        val mapVisible = mapLayout != DualDisplayComposer.TileLayout.HIDDEN
        val mapVisibleW = if (mapFullscreen) launcher.appAreaWidth else launcher.mapTileWidth
        val mapVisibleH = if (mapFullscreen) launcher.appAreaHeight else launcher.mapTileHeight
        // 按下命中分屏把手后，整段手势固定给应用屏，直到抬起
        // 快拖越过把手边界时也不改投地图，避免拖拽中断和误触
        val handlePin =
            splitHandlePressed ||
                (contact.down && !pressed && launcher.dashHandleHit(px.toFloat(), py.toFloat()))
        if (handlePin) {
            splitHandlePressed = contact.down
            inDock = false
            targetDisplay =
                if (launcher.gridDisplayId >= 0) launcher.gridDisplayId else launcher.activeAppDisplayId()
            if (launcher.dockAtBottom) {
                localX = px
                localY = py
            } else {
                val scale = launcher.gridScale().coerceAtLeast(1f)
                localX = ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        } else if (launcher.dockAtBottom) {
            val appH = launcher.appHeightPx
            inDock = py >= appH
            // 地图 tile（应用区上半；全屏=应用区全部）。触摸/流坐标顶部起算：
            // tile 矩形 py ∈ [CARD_MARGIN_PX, +mapTileHeight]，屏内 y = py-边距（顶边距扣除）
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                mapVisible &&
                (mapFullscreen || launcher.tileSlideFraction <= 0.01f) &&
                px < launcher.appAreaWidth &&
                (
                    mapFullscreen ||
                        (
                            px >= CARD_MARGIN_PX &&
                                px < CARD_MARGIN_PX + launcher.mapTileWidth &&
                                py >= CARD_MARGIN_PX &&
                                py < CARD_MARGIN_PX + launcher.mapTileHeight
                        )
                )
            ) {
                inDock = false
                targetDisplay = launcher.mapDisplayId
                localX = if (mapFullscreen) px else px - CARD_MARGIN_PX
                localY = if (mapFullscreen) py else py - CARD_MARGIN_PX
                routedToMap = true
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.appDisplayId
                localX = px
                localY = if (inDock) py - appH else py
            }
        } else {
            inDock = px < dockW
            if (!inDock &&
                launcher.mapDisplayId >= 0 &&
                mapVisible &&
                (mapFullscreen || launcher.tileSlideFraction <= 0.01f)
            ) {
                val margin = if (mapFullscreen) 0 else CARD_MARGIN_PX
                val tileX = px - dockW - margin
                val tileY = py - margin
                if (tileX in 0 until mapVisibleW && tileY in 0 until mapVisibleH) {
                    inDock = false
                    targetDisplay = launcher.mapDisplayId
                    // 先换算为地图可见区域局部坐标，再按实际屏尺寸映射
                    localX = tileX
                    localY = tileY
                    routedToMap = true
                }
            }
            if (!routedToMap) {
                targetDisplay = if (inDock) launcher.dockDisplayId else launcher.activeAppDisplayId()
                val scale = if (inDock) 1f else launcher.activeAppScale().coerceAtLeast(1f)
                localX = if (inDock) px else ((px - dockW) * scale).toInt()
                localY = (py * scale).toInt()
            }
        }
        if (targetDisplay < 0) {
            diag("目标屏无效 dock=${launcher.dockDisplayId} app=${launcher.appDisplayId}")
            return
        }
        if (routedToMap) {
            val actualSize = launcher.hintDisplaySize(targetDisplay) ?: return
            if (mapVisibleW <= 0 || mapVisibleH <= 0 || actualSize.first <= 0 || actualSize.second <= 0) return
            localX = scaleTouchCoordinate(localX, mapVisibleW, actualSize.first)
            localY = scaleTouchCoordinate(localY, mapVisibleH, actualSize.second)
        } else if (!inDock) {
            val configured = launcher.hintDisplaySize(targetDisplay)
            val actual = launcher.actualDisplayGeometry(targetDisplay)
            if (configured != null && actual != null) {
                localX = scaleTouchCoordinate(localX, configured.first, actual.width)
                localY = scaleTouchCoordinate(localY, configured.second, actual.height)
            }
        }
        // MOVE 节流：同屏的连续 move 间隔不足时合并丢弃（状态机不动，最新位置
        // 由后续 move/up 携带）。down/up/跨屏切换不节流。
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (!throttleInited) {
            throttleInited = true
            moveThrottleMs =
                DisplayPrefs
                    .moveThrottleMs(context)
                    .toLong()
        }
        if (moveThrottleMs > 0 &&
            contact.down &&
            pressed &&
            !gesture.awaitingRelease &&
            pressedDisplay == targetDisplay &&
            nowMs - lastInjectAt < moveThrottleMs
        ) {
            throttledCount++
            if (nowMs - lastThrottleLogAt > 5_000) {
                logSink?.invoke(
                    "TOUCH: move 节流 ${moveThrottleMs}ms 生效——累计合并 $throttledCount 次",
                )
                throttledCount = 0
                lastThrottleLogAt = nowMs
            }
            return
        }
        val injectionStarted = android.os.SystemClock.uptimeMillis()
        val injected =
            try {
                val ok =
                    gesture.dispatch(contact.down, PointerGesture.Target(targetDisplay, localX, localY)) { action, target ->
                        when (action) {
                            PointerGesture.Action.DOWN -> svc.pointerDown(target.displayId, target.x, target.y)
                            PointerGesture.Action.MOVE -> svc.pointerMove(target.displayId, target.x, target.y)
                            PointerGesture.Action.UP -> svc.pointerUp(target.displayId, target.x, target.y)
                        }
                    }
                if (ok) lastInjectAt = nowMs
                if (!ok) {
                    launcher.recordInteractionDiagnostics("触控注入被拒 display=$targetDisplay down=${contact.down}")
                    diag(
                        "注入被拒 disp=$targetDisplay ($localX,$localY) " +
                            "down=${contact.down} pressed=$pressed pressedDisplay=$pressedDisplay",
                    )
                } else if (contact.down) {
                    diag("注入OK disp=$targetDisplay ($localX,$localY)")
                }
                ok
            } catch (e: Exception) {
                launcher.recordInteractionDiagnostics("触控注入异常 display=$targetDisplay 类型=${e.javaClass.simpleName}")
                diag("注入异常：${e.message}")
                false
            } finally {
                val completedAt = android.os.SystemClock.uptimeMillis()
                if (completedAt - injectionStarted >= 100L && completedAt - lastSlowInjectLogAt >= 1_000L) {
                    lastSlowInjectLogAt = completedAt
                    logSink?.invoke(
                        "TOUCH: 注入耗时=${completedAt - injectionStarted}ms display=$targetDisplay " +
                            "down=${contact.down}，系统路由确认耗时，应用处理完成时间未知",
                    )
                }
            }

        // 拒绝的触摸不能继续触发地图全屏，失败抬起也必须作废此前的点按候选
        if (!injected) {
            cancelMapTileTap()
            // 按住期间失败后，后续成功的 MOVE 也不能重新成为轻点起点
            tapDragSlot = if (contact.down) contact.slot else -1
            return
        }

        // 仪表盘模式：地图 tile 的触摸同时喂给「点按 → 全屏地图」检测器
        // （注入逻辑不受影响；点按 = 快速轻点，平移/双击/双指等地图手势不触发）。
        // 横竖屏都启用：tileX 传 tile 内坐标（仅用于位移判定，起点取值不敏感）
        val tileHit =
            !inDock &&
                targetDisplay == launcher.mapDisplayId &&
                launcher.dashboardMode &&
                mapLayout == DualDisplayComposer.TileLayout.SPLIT
        updateMapTileTap(
            launcher,
            contact,
            tileHit,
            if (launcher.dockAtBottom) px - CARD_MARGIN_PX else px - dockW,
            py,
        )
    }

    @Synchronized
    fun reset() {
        gesture.reset()?.let { target ->
            runCatching { CarInputClient.service?.pointerUp(target.displayId, target.x, target.y) }
        }
        gestureBinder = null
        contactTracker.reset()
        overlayPressed = false
        splitHandlePressed = false
        cancelMapTileTap()
        tapDragSlot = -1
        geometry = null
        safeGesture.reset()
        lastInjectAt = 0L
    }

    // ---- 仪表盘地图 tile「快速点按 → 全屏地图」检测 ----
    // 点按判定：按下→抬起 ≤400ms 且位移 ≤30px；抬起后 300ms 内无第二次按下
    // （确认窗规避地图双击缩放误触）。拖动/拖出 tile/双指等地图手势不触发。
    private const val TAP_MAX_MS = 400L
    private const val TAP_MAX_MOVE_PX = 30
    private const val TAP_CONFIRM_MS = 300L
    private val tapHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tapArmSlot = -1

    /** 拖动手势取消后一直作废到物理抬起，避免抬起被误判为点击 */
    private var tapDragSlot = -1
    private var tapDownAt = 0L
    private var tapX = 0
    private var tapY = 0
    private var tapConfirm: Runnable? = null

    private fun updateMapTileTap(
        launcher: VirtualLauncher,
        contact: Contact,
        inTile: Boolean,
        tileX: Int,
        tileY: Int,
    ) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!launcher.dashboardMode || launcher.mapFullscreen) {
            cancelMapTileTap()
            return
        }
        if (contact.down) {
            if (inTile && tapConfirm != null) {
                cancelMapTileTap() // 确认窗内再次按下 = 双击缩放，取消全屏触发
                return
            }
            if (tapArmSlot < 0 && inTile && tapDragSlot < 0) {
                tapArmSlot = contact.slot
                tapDownAt = now
                tapX = tileX
                tapY = tileY
            } else if (tapArmSlot >= 0 &&
                (
                    !inTile ||
                        contact.slot != tapArmSlot ||
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(tileY - tapY) > TAP_MAX_MOVE_PX
                )
            ) {
                // 拖动 / 拖出 tile / 双指：整个手势作废到抬起（见 tapDragSlot 注释）
                tapDragSlot = tapArmSlot
                cancelMapTileTap()
            }
        } else {
            if (tapDragSlot >= 0) {
                if (contact.slot == tapDragSlot) tapDragSlot = -1
                return
            }
            if (tapArmSlot >= 0 && contact.slot == tapArmSlot) {
                val ok =
                    inTile &&
                        now - tapDownAt <= TAP_MAX_MS &&
                        kotlin.math.abs(tileX - tapX) + kotlin.math.abs(tileY - tapY) <= TAP_MAX_MOVE_PX
                cancelMapTileTap()
                if (ok) {
                    tapConfirm =
                        Runnable {
                            tapConfirm = null
                            if (launcher.dashboardMode && !launcher.mapFullscreen) {
                                logSink?.invoke("LAUNCHER: 地图卡片点按 → 全屏地图")
                                launcher.setMapFullscreen(true)
                            }
                        }
                    tapHandler.postDelayed(tapConfirm!!, TAP_CONFIRM_MS)
                }
            }
        }
    }

    private fun cancelMapTileTap() {
        tapArmSlot = -1
        tapConfirm?.let { tapHandler.removeCallbacks(it) }
        tapConfirm = null
    }
}
