package io.github.shaonialife.buicklinkplus.launcher

import android.content.Context
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.settings.AppOrientationCompat
import io.github.shaonialife.buicklinkplus.render.OverlayCard
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.util.Bg

/** 在连接、应用启动和地图全屏后检查 letterbox，显示一键重启提示，不轮询 */
object LetterboxHintController {
    private const val TAG = "LetterboxHint"
    private val cardLock = Any()
    private var cardGeneration = 0L
    private val redrawGeneration =
        java.util.concurrent.atomic
            .AtomicLong()
    private val appearanceListener = { redrawCurrentCard() }

    init {
        // 进程级单例只保留自身监听，不捕获窗口
        UiAppearance.addListener(appearanceListener)
    }

    /** 用户关闭或确认无法修复后，本次会话不再提示 */
    private val dismissed = HashSet<String>()

    private enum class State { NORMAL, BUSY, FAILED }

    @Volatile
    private var state = State.NORMAL

    /** 当前提示对应的目标（null = 未显示）。 */
    private data class Target(
        val packageName: String,
        val component: String,
        val displayId: Int,
    )

    @Volatile
    private var target: Target? = null

    /** 卡片矩形（输出画面坐标，顶部原点）；触摸命中直接用它。 */
    @Volatile
    private var cardLeft = 0f

    @Volatile
    private var cardTop = 0f

    @Volatile
    private var cardRight = 0f

    @Volatile
    private var cardBottom = 0f

    @Volatile
    private var probeInFlight = false

    fun isVisible(): Boolean = target != null

    /** 卡片是否命中（输出画面坐标；由 TouchRouter 在注入前调用）。 */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = target != null && x >= cardLeft && x < cardRight && y >= cardTop && y < cardBottom

    /** 卡片被点按：NORMAL=重启应用；BUSY=忽略；FAILED=关闭。 */
    fun onTap() {
        when (state) {
            State.NORMAL -> {
                val t = target ?: return
                restart(t)
            }

            State.BUSY -> Unit

            State.FAILED -> dismiss()
        }
    }

    fun dismiss() {
        target?.let { synchronized(dismissed) { dismissed.add(it.packageName) } }
        clear()
    }

    /** 回桌面 / 会话结束：清掉提示并恢复状态。 */
    fun clear() {
        synchronized(cardLock) {
            cardGeneration++
            redrawGeneration.incrementAndGet()
            target = null
            state = State.NORMAL
            cardLeft = 0f
            cardTop = 0f
            cardRight = 0f
            cardBottom = 0f
            VirtualLauncher.setOverlayCard(null)
        }
    }

    /** 新会话：清空「本次会话不再提示」记录。 */
    fun resetSession() {
        synchronized(dismissed) { dismissed.clear() }
        clear()
    }

    /**
     * 在后台检查目标屏的 letterbox
     * 优先使用 [displayIdHint]，缺失时读取合成器当前屏，避免切屏首帧尚未到达时查错屏
     */
    fun scheduleProbe(
        context: Context,
        reason: String,
        displayIdHint: Int = -1,
        delayMs: Long = 1_200L,
    ) {
        val app = context.applicationContext
        val session = AppOrientationCompat.captureSession()
        Bg.run("letterbox-probe") {
            if (delayMs > 0) Thread.sleep(delayMs)
            if (!AppOrientationCompat.isCurrent(session)) return@run
            runCatching { probe(app, reason, displayIdHint, session) }
                .onFailure { android.util.Log.w(TAG, "probe failed after $reason", it) }
        }
    }

    private fun probe(
        context: Context,
        reason: String,
        displayIdHint: Int,
        session: AppOrientationCompat.SessionToken,
    ) {
        if (probeInFlight) return
        probeInFlight = true
        try {
            val displayId =
                displayIdHint
                    .takeIf { it >= 0 && VirtualLauncher.hintDisplaySize(it) != null }
                    ?: visibleDisplayId() ?: run {
                    if (isVisible()) clear()
                    return
                }
            val (fallbackW, fallbackH) =
                VirtualLauncher.hintDisplaySize(displayId) ?: run {
                    if (isVisible()) clear()
                    return
                }
            // 方向覆盖是异步串行写入的：探测前等队列排空，否则可能读到写入前的状态
            AppOrientationCompat.awaitPending()
            if (!AppOrientationCompat.isCurrent(session)) return
            val enabled = AppOrientationCompat.enabledPackages(context)
            if (enabled.isEmpty()) {
                if (isVisible()) clear()
                return
            }
            val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
            if (!AppOrientationCompat.isCurrent(session)) return
            val activity =
                parseTopLetterboxActivity(displayId, dump, fallbackW, fallbackH) ?: run {
                    if (isVisible()) clear()
                    return
                }
            val should =
                shouldShowLetterboxHint(activity, enabled, synchronized(dismissed) { dismissed.toSet() })
            if (!should) {
                if (isVisible()) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 清除提示（$reason）——" +
                            "letterbox=${activity.letterboxed} reason=${activity.letterboxReason}",
                    )
                    clear()
                }
                return
            }
            // 偏好已开启但系统覆盖丢失时补写；am compat 会杀掉应用，之后要在原屏重新打开
            val changeIds = AppOrientationCompat.changeIdsRegistered()
            var systemOn =
                changeIds && AppOrientationCompat.isSystemEnabledFor(activity.packageName)
            var current = activity
            var healedAndRelaunched = false
            if (!systemOn && changeIds) {
                val healed =
                    AppOrientationCompat.applySavedForSession(context, activity.packageName, session) ==
                        AppOrientationCompat.WriteResult.OK
                if (!AppOrientationCompat.isCurrent(session) || !AppOrientationCompat.isEnabled(context, activity.packageName)) return
                systemOn = healed || AppOrientationCompat.isSystemEnabledFor(activity.packageName)
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${activity.packageName}」系统侧横屏覆盖缺失——补写" +
                        "${if (healed) "成功" else "失败"}",
                )
                if (systemOn) {
                    // 补写已杀掉进程：等它退出后在原屏重启（与按钮动作等价，但自动完成）
                    Thread.sleep(600)
                    if (!AppOrientationCompat.isCurrent(session) || !AppOrientationCompat.isEnabled(context, activity.packageName)) return
                    val start =
                        Shell.exec(
                            "am start -n ${activity.component} --display $displayId -f 0x18000000",
                            timeoutMs = 8_000,
                        )
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 补写后重启 「${activity.packageName}」ok=" +
                            (start.ok && start.output.contains("Starting:", ignoreCase = true)),
                    )
                    healedAndRelaunched = true
                    Thread.sleep(2_200)
                    val fresh = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                    if (!AppOrientationCompat.isCurrent(session) || !AppOrientationCompat.isEnabled(context, activity.packageName)) return
                    current =
                        parseTopLetterboxActivity(displayId, fresh, fallbackW, fallbackH) ?: run {
                            if (isVisible()) clear()
                            return
                        }
                    if (!shouldShowLetterboxHint(
                            current,
                            enabled,
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                    ) {
                        if (isVisible()) clear()
                        VirtualLauncher.logSink?.invoke(
                            "HINT: 补写覆盖并重启后「${activity.packageName}」已横屏",
                        )
                        return
                    }
                }
            }
            if (!systemOn) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${activity.packageName}」letterbox，但本机不支持横屏覆盖——" +
                        "不提示重启（changeIds=$changeIds）",
                )
                if (isVisible()) clear()
                return
            }
            // 黑边太窄（放不下卡片）时 showCard 返回 false 且不挂卡片；日志只记真实展示
            val wasVisible = isVisible()
            // 补写并重启后仍竖屏 → 说明这个应用压根不吃方向覆盖，直接给「仍然竖屏」
            // 终态卡（不再诱导用户再点一次必然失败的重启）
            val nextState = if (healedAndRelaunched) State.FAILED else State.NORMAL
            // 自愈后复探过的话以最新读数为准（组件可能已换成重建后的实例）
            val nextTarget = Target(current.packageName, current.component, displayId)
            synchronized(cardLock) {
                if (!AppOrientationCompat.isCurrent(session) || !AppOrientationCompat.isEnabled(context, current.packageName)) return
                cardGeneration++
                state = nextState
                target = nextTarget
            }
            val shown = showCard(current, nextState, nextTarget)
            if (!shown) {
                synchronized(cardLock) { if (target === nextTarget) clear() }
                return
            }
            if (!wasVisible) {
                VirtualLauncher.logSink?.invoke(
                    "HINT: 「${current.packageName}」横屏未生效（$reason）" +
                        " 内窗=${current.inner.width}x${current.inner.height}" +
                        " 外框=${current.full.width}x${current.full.height}" +
                        " 黑边 左=${current.inner.left - current.full.left}" +
                        "/右=${current.full.right - current.inner.right}",
                )
            }
        } finally {
            probeInFlight = false
        }
    }

    /** 地图全屏时返回地图屏，否则返回当前应用屏 */
    private fun visibleDisplayId(): Int? {
        if (VirtualLauncher.mapFullscreen && VirtualLauncher.mapDisplayId >= 0) {
            return VirtualLauncher.mapDisplayId
        }
        val id = VirtualLauncher.activeAppDisplayId()
        return if (id >= 0) id else null
    }

    /** 把应用屏 bounds 换算成输出坐标，再按黑边宽度生成提示卡 */
    private fun showCard(
        activity: LetterboxActivity,
        newState: State,
        expectedTarget: Target,
    ): Boolean {
        val expectedState: State
        val expectedGeneration: Long
        synchronized(cardLock) {
            if (target !== expectedTarget) return false
            expectedState = state
            expectedGeneration = cardGeneration
        }
        val appearanceVersion = UiAppearance.version
        val area = VirtualLauncher.appAreaRect() ?: return false
        val display =
            VirtualLauncher.hintDisplaySize(activity.displayId) ?: return false
        if (display.first <= 0 || area.width() <= 0f) return false
        val scale = area.width() / display.first
        val leftBar = (activity.inner.left - activity.full.left) * scale
        val rightBar = (activity.full.right - activity.inner.right) * scale
        val density = VirtualLauncher.uiDensity()
        val margin = LetterboxCardPainter.marginPx(density)
        val needed = LetterboxCardPainter.minWidthPx(density) + 2f * margin
        // 黑边太窄（与全屏横屏已无区别）→ 不显示
        val side = chooseLetterboxSide(leftBar, rightBar, needed) ?: return false
        val barWidth = if (side == LetterboxSide.LEFT) leftBar else rightBar
        val width = minOf(LetterboxCardPainter.maxWidthPx(density), barWidth - 2f * margin).toInt()
        val bitmap =
            LetterboxCardPainter.render(
                density = density,
                widthPx = width,
                maxHeightPx = area.height().toInt(),
                state = newState.toCardState(),
                colors = cardColors() ?: return false,
            ) ?: return false
        val barStart = if (side == LetterboxSide.LEFT) area.left else area.right - barWidth
        val left = barStart + (barWidth - bitmap.width) / 2f
        val top = area.top + (area.height() - bitmap.height) / 2f
        synchronized(cardLock) {
            if (cardGeneration != expectedGeneration || target !== expectedTarget || state != expectedState) return false
            redrawGeneration.incrementAndGet()
            state = newState
            cardLeft = left
            cardTop = top
            cardRight = left + bitmap.width
            cardBottom = top + bitmap.height
            VirtualLauncher.setOverlayCard(OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom))
        }
        VirtualLauncher.logSink?.invoke(
            "HINT: 卡片 bitmap=${bitmap.width}x${bitmap.height} rect=" +
                "(${cardLeft.toInt()},${cardTop.toInt()}-${cardRight.toInt()},${cardBottom.toInt()})" +
                " bar=$barWidth density=$density widthPx=$width",
        )
        if (UiAppearance.version != appearanceVersion) redrawCurrentCard()
        return true
    }

    private fun State.toCardState(): LetterboxCardState =
        when (this) {
            State.NORMAL -> LetterboxCardState.NORMAL
            State.BUSY -> LetterboxCardState.BUSY
            State.FAILED -> LetterboxCardState.FAILED
        }

    /** 在后台等待方向设置完成，再停止并在原屏重启应用，最后重新检查 */
    private fun restart(t: Target) {
        synchronized(cardLock) {
            if (target !== t) return
            state = State.BUSY
        }
        reRenderBusy()
        VirtualLauncher.logSink?.invoke("HINT: 强制重启 ${t.packageName}（display=${t.displayId}）")
        Bg.run("letterbox-restart") {
            runCatching {
                AppOrientationCompat.awaitPending()
                if (target !== t) return@runCatching
                // 系统可能按旧任务配置恢复，先停止应用，让下次启动重新读取方向设置
                Shell.exec("am force-stop ${t.packageName}", timeoutMs = 8_000)
                Thread.sleep(400)
                if (target !== t) return@runCatching
                val start =
                    Shell.exec(
                        "am start -n ${t.component} --display ${t.displayId} -f 0x18000000",
                        timeoutMs = 8_000,
                    )
                if (!start.ok) {
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 重启 ${t.packageName} 失败 exit=${start.exitCode} out=${start.output.take(120)}",
                    )
                }
                // 等应用重建窗口后再复探
                Thread.sleep(2_000)
                val (w, h) = VirtualLauncher.hintDisplaySize(t.displayId) ?: return@runCatching
                val ctx = VirtualLauncher.appContext() ?: return@runCatching
                val dump = Shell.exec("dumpsys activity activities", timeoutMs = 4_000).output
                if (target !== t) return@runCatching
                val activity = parseTopLetterboxActivity(t.displayId, dump, w, h)
                val still =
                    activity != null &&
                        activity.packageName == t.packageName &&
                        shouldShowLetterboxHint(
                            activity,
                            AppOrientationCompat.enabledPackages(ctx),
                            synchronized(dismissed) { dismissed.toSet() },
                        )
                if (still) {
                    if (!showCard(activity, State.FAILED, t)) {
                        // 黑边放不下卡片（或屏已释放）：直接收掉，避免重启无反馈
                        synchronized(cardLock) { if (target === t) clear() }
                    }
                    VirtualLauncher.logSink?.invoke(
                        "HINT: 重启后「${t.packageName}」仍然 letterbox（reason=${activity.letterboxReason}）",
                    )
                } else {
                    synchronized(cardLock) { if (target === t) clear() }
                    VirtualLauncher.logSink?.invoke("HINT: 重启后「${t.packageName}」已横屏")
                }
            }.onFailure {
                synchronized(cardLock) { if (target === t) state = State.NORMAL }
                redrawCurrentCard()
                VirtualLauncher.logSink?.invoke("HINT: 重启异常 ${it.message}")
            }
        }
    }

    /** BUSY 态重画：沿用当前卡片的宽度/位置，避免重新解析。 */
    private fun reRenderBusy() {
        redrawCurrentCard()
    }

    /** 主题或状态变化后在后台重画，换目标后丢弃旧结果 */
    private fun redrawCurrentCard() {
        val request = redrawGeneration.incrementAndGet()
        val expectedTarget: Target
        val expectedState: State
        val bounds: FloatArray
        val generation: Long
        synchronized(cardLock) {
            expectedTarget = target ?: return
            expectedState = state
            bounds = floatArrayOf(cardLeft, cardTop, cardRight, cardBottom)
            generation = cardGeneration
        }
        val density = VirtualLauncher.uiDensity()
        val width = (bounds[2] - bounds[0]).toInt()
        val maxHeight = (VirtualLauncher.appAreaRect()?.height() ?: 0f).toInt()
        Bg.run("letterbox-appearance") {
            val colors = cardColors() ?: return@run
            val bitmap = LetterboxCardPainter.render(density, width, maxHeight, expectedState.toCardState(), colors) ?: return@run
            synchronized(cardLock) {
                if (cardGeneration != generation ||
                    redrawGeneration.get() != request ||
                    target !== expectedTarget ||
                    state != expectedState
                ) {
                    return@synchronized
                }
                val centerY = (bounds[1] + bounds[3]) / 2f
                cardTop = centerY - bitmap.height / 2f
                cardBottom = cardTop + bitmap.height
                cardRight = cardLeft + bitmap.width
                VirtualLauncher.setOverlayCard(OverlayCard(bitmap, cardLeft, cardTop, cardRight, cardBottom))
            }
        }
    }

    /**
     * 统一读取车机配色，第三方应用内容不参与主题变更
     */
    private fun cardColors(): LetterboxCardPainter.Colors? {
        val context = VirtualLauncher.appContext() ?: return null

        fun color(resource: Int) = UiAppearance.carColor(context, resource)
        return LetterboxCardPainter.Colors(
            color(R.color.glass_panel),
            color(R.color.glass_stroke),
            color(R.color.m3_primary),
            color(R.color.m3_on_surface),
            color(R.color.m3_on_var),
            color(R.color.m3_on_primary),
            color(R.color.glass_muted_panel),
            color(R.color.m3_on_var),
        )
    }

    /** 诊断用：当前提示的包名（未显示时 null）。 */
    fun currentPackage(): String? = target?.packageName

    /** 是否有探测在跑（测试/诊断）。 */
    fun isProbing(): Boolean = probeInFlight

    /** 上次卡片的屏幕坐标快照（诊断/测试用）。 */
    fun cardRect(): FloatArray = floatArrayOf(cardLeft, cardTop, cardRight, cardBottom)
}
