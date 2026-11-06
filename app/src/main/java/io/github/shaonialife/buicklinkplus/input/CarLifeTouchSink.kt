/* P3 CarLife 触摸回传：把车机触摸映射到 CarLife 引擎的 9340 触摸通道。
 *
 * 逻辑移植自（同一作者，MIT）：
 * - hybrid63 `inject/src/tl/CarLifeTouch.java`：安全区/内容层 rect → 引擎视频坐标映射、
 *   单点 DOWN/MOVE/UP，以及第二指切换到复合动作（0x68001）的指针处理；
 * - CarPToCarL `carlife/CarLifeHijack.kt` 的 `handleTouch`：手势状态机——缺 UP 恢复
 *   （车机漏发抬起会让引擎永久卡在按住态）、超时收尾、短促点击的坐标回吸（避免抖动
 *   被当成拖动/抬起落到相邻控件）、第二指按下后整笔切换复合动作通道。
 *
 * 与参考实现的差异：
 * - 参考实现用静态字段直接持有客户端和几何；这里改为实例 + 显式接线
 *   （[setSender]/[updateViewport]），由 [TouchRouter] 在会话生命周期内 set/clear。
 * - 坐标域：输入是车机显示坐标（TouchReports 解析后的 display 像素域，与
 *   DisplayGeometry.canvas 同一域）；内部把 viewport 的 contentRect 按 contain 适配后
 *   映射到码流**真实**分辨率（[CarLifeHuClient.videoSize]：SPS 优先、协商档位兜底），
 *   即引擎侧视频坐标（0..sourceW / 0..sourceH），再调用
 *   touchDown/touchMove/touchUp 下发。
 * - 所有下发只进 [CarLifeHuClient] 自己的触摸队列（carlife-touch 线程串行写 socket），
 *   本类方法不阻塞调用线程（触摸回调/主线程）。
 *
 * 注意（现场调参）：CarPToCarL 后期结论（v0.2.17）认为「车机 display 坐标原样透传」
 * 才对——引擎会按 getTouchXOffset() 自行换算。本文件按 P3 任务要求沿用 hybrid63
 * 已验证的显式映射；若实车落点整体偏移/压缩，把 [updateViewport] 的 contentRect 传
 * 整幅 viewport、source 传 viewport 尺寸即可退化成恒等映射（接线侧改一行，本文件不动）。
 */
package io.github.shaonialife.buicklinkplus.input

import android.graphics.Rect
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.carlife.CarLifeHuClient
import io.github.shaonialife.buicklinkplus.carlife.CarLifeProtocol
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * CarLife 触摸 sink：车机触摸 → 9340 触摸通道。
 *
 * 典型接线（由 SessionController 完成，见任务报告）：
 * ```
 * CarLifeScreenSource.attach 成功后: TouchRouter.setCarLifeSink(sink); sink.setSender(client)
 * 码流尺寸变化时:                  sink.updateSourceSize(w, h)   // 缺省跟随 client.videoSize
 * 会话退役:                        TouchRouter.clearCarLifeSink()
 * ```
 * 未绑定客户端或几何未就绪时，所有方法安全空转（只记限频日志），不会抛出。
 */
class CarLifeTouchSink(
    private val log: (String) -> Unit = {},
) {
    /** 当前 CarLife 客户端；下发触摸时用它取视频尺寸并写 9340。 */
    @Volatile
    private var sender: CarLifeHuClient? = null

    // ---- viewport：本 sink 的触摸坐标域（TouchRouter 把显示坐标换算成编码流坐标后传入）----

    @Volatile
    private var viewportWidth = 0

    @Volatile
    private var viewportHeight = 0

    /** 视频窗格所在的内容层 rect（DisplayGeometry.content，编码流坐标域）。 */
    @Volatile
    private var content = Rect()

    /** 显式视频尺寸覆盖（>0 生效；0 = 跟随 [CarLifeHuClient.videoSize]，即 SPS 优先）。 */
    @Volatile
    private var explicitSourceWidth = 0

    @Volatile
    private var explicitSourceHeight = 0

    private var lastViewportLog = ""

    // ---- 手势状态（全部在本对象锁内读写）----

    /** 主指针（HID 槽 0）是否按住。 */
    private var pressed = false

    /** 本次手势是否已切换到复合动作通道（0x68001，第二根手指按下后）。 */
    private var multiActive = false

    /** 第二根手指（HID 槽 1）是否按住。 */
    private var secondaryDown = false

    private var secondaryX = 0

    private var secondaryY = 0

    /** 最近一次成功映射的主指针视频坐标（补发 UP / 超时收尾用）。 */
    private var primaryX = 0

    private var primaryY = 0

    /** 主指针按下点（视频坐标），短促点击时用它回吸。 */
    private var downX = 0

    private var downY = 0

    private var gestureStartedAt = 0L

    private var tapStartedAt = 0L

    private var lastEventAt = 0L

    private var moveCount = 0

    private var secondaryMoveCount = 0

    private var gestureCount = 0L

    private var sentCount = 0L

    private var ignoredCount = 0L

    /** 绑定/解绑客户端；切换客户端前先给旧客户端补发一个 UP，避免幽灵触点。 */
    @Synchronized
    fun setSender(client: CarLifeHuClient?) {
        if (sender === client) return
        cancel()
        sender = client
        resetGesture()
        if (client == null) {
            log("CARLIFE-TOUCH: 解绑 CarLife 客户端（触摸不再下发）")
        } else {
            log("CARLIFE-TOUCH: 绑定 CarLife 客户端，触摸走 9340（${statusText()}）")
        }
    }

    /**
     * 更新触摸坐标域映射。
     *
     * @param viewWidth/viewHeight 传入 [down]/[move]/[up] 的坐标域尺寸（TouchRouter 传
     *   DisplayGeometry.canvasWidth/Height，即编码器输入画面尺寸）。
     * @param contentRect 视频窗格所在内容层 rect（DisplayGeometry.content，原版安全区语义）；
     *   小于视频宽高比时按 contain 居中适配（与 hybrid63 publishFit 的窗格算法一致）。
     * @param sourceWidth/sourceHeight 视频真实分辨率；传 0/0（默认）表示跟随
     *   [CarLifeHuClient.videoSize]（SPS 优先、协商档位兜底），一般不需要显式传。
     */
    @Synchronized
    fun updateViewport(
        viewWidth: Int,
        viewHeight: Int,
        contentRect: Rect,
        sourceWidth: Int = 0,
        sourceHeight: Int = 0,
    ) {
        viewportWidth = viewWidth.coerceAtLeast(0)
        viewportHeight = viewHeight.coerceAtLeast(0)
        content = Rect(contentRect)
        if (sourceWidth > 0 && sourceHeight > 0) {
            explicitSourceWidth = sourceWidth
            explicitSourceHeight = sourceHeight
        } else if (sourceWidth <= 0 && sourceHeight <= 0) {
            explicitSourceWidth = 0
            explicitSourceHeight = 0
        }
        // 只在几何真正变化时打一条，避免每次路由都刷日志
        val text =
            "viewport=${viewportWidth}x$viewportHeight content=$content " +
                "source=" + if (explicitSourceWidth > 0) "${explicitSourceWidth}x$explicitSourceHeight" else "跟随客户端 videoSize"
        if (text != lastViewportLog) {
            lastViewportLog = text
            log("CARLIFE-TOUCH: 几何更新 $text")
        }
    }

    /** 显式指定码流尺寸（可选；不调用则自动跟随 [CarLifeHuClient.videoSize]）。 */
    @Synchronized
    fun updateSourceSize(
        width: Int,
        height: Int,
    ) {
        if (width > 0 && height > 0) {
            explicitSourceWidth = width
            explicitSourceHeight = height
        } else {
            explicitSourceWidth = 0
            explicitSourceHeight = 0
        }
    }

    /** 是否已具备下发条件（客户端 + viewport）。 */
    val isReady: Boolean
        get() = sender != null && viewportWidth > 0 && viewportHeight > 0 && content.width() > 0 && content.height() > 0

    /** 诊断摘要（会话日志/自检用）。 */
    fun statusText(): String {
        val client = sender ?: return "未绑定客户端"
        val source = effectiveSourceSize(client) ?: (0 to 0)
        return "client=${System.identityHashCode(client)} 视频=${source.first}x${source.second} " +
            "viewport=${viewportWidth}x$viewportHeight content=$content 已发触摸=$sentCount"
    }

    // ---- 触摸入口（原始 HID 报告每帧重发槽状态：down=true 且已按住时等价于 move）----

    /**
     * 指针按下；同一指针已按住时按移动处理（原始 HID 报告会持续重发 down 状态）。
     *
     * @return true = 已下发，false = 客户端/几何未就绪或指针非法
     */
    @Synchronized
    fun down(
        x: Int,
        y: Int,
        pointerId: Int = 0,
    ): Boolean {
        val client = sender ?: return drop("down")
        if (pointerId !in 0..1) return drop("down[$pointerId]")
        val point = mapPoint(client, x, y) ?: return drop("down")
        val nx = point[0]
        val ny = point[1]
        val now = SystemClock.uptimeMillis()

        if (pressed) {
            // 车机漏发 UP 会让引擎永久「按住」；与上一笔间隔过大又收到按下时先补收笔。
            val gap = now - lastEventAt
            if (gap > STALE_TOUCH_MS) {
                finishGesture(client, primaryX, primaryY, now, "触摸超时 ${gap}ms")
            } else if (gap > NEW_GESTURE_GAP_MS) {
                finishGesture(client, primaryX, primaryY, now, "上一笔缺抬起 ${gap}ms")
            }
        }

        if (!pressed) {
            // 第二根手指先到（车机把占位槽报成按下）：忽略，等主指针按下再算。
            if (pointerId != 0) return false
            pressed = true
            gestureCount++
            multiActive = false
            secondaryDown = false
            gestureStartedAt = now
            tapStartedAt = now
            moveCount = 0
            secondaryMoveCount = 0
            downX = nx
            downY = ny
            primaryX = nx
            primaryY = ny
            lastEventAt = now
            client.touchDown(nx, ny)
            sentCount++
            if (gestureCount <= 5 || gestureCount % 200 == 0L) {
                log("CARLIFE-TOUCH: ↓ ($x,$y)→($nx,$ny) 手势#$gestureCount")
            }
            return true
        }

        if (pointerId == 0) {
            primaryX = nx
            primaryY = ny
            lastEventAt = now
            moveCount++
            if (multiActive) {
                client.touchAction(CarLifeProtocol.TouchAction.MOVE, nx, ny)
            } else {
                client.touchMove(nx, ny)
            }
            sentCount++
            return true
        }

        // 第二根手指按下：整笔手势切到复合动作通道（单点消息没有指针概念）。
        if (!secondaryDown) {
            secondaryDown = true
            secondaryX = nx
            secondaryY = ny
            if (!multiActive) {
                multiActive = true
                secondaryMoveCount = 0
                client.touchAction(
                    CarLifeProtocol.TouchAction.withPointer(CarLifeProtocol.TouchAction.POINTER_DOWN, pointerId),
                    nx,
                    ny,
                )
                sentCount++
                log("CARLIFE-TOUCH: ↓2 ($x,$y)→($nx,$ny) 进入双指复合动作（手指$pointerId）")
            }
            return true
        }
        secondaryX = nx
        secondaryY = ny
        lastEventAt = now
        secondaryMoveCount++
        if (multiActive) {
            client.touchAction(CarLifeProtocol.TouchAction.MOVE, nx, ny)
            sentCount++
        }
        return true
    }

    /** 指针移动；未按下时忽略。 */
    @Synchronized
    fun move(
        x: Int,
        y: Int,
        pointerId: Int = 0,
    ): Boolean {
        val client = sender ?: return drop("move")
        if (!pressed) return false
        if (pointerId == 1) {
            if (!secondaryDown) return false
        } else if (pointerId != 0) {
            return false
        }
        val point = mapPoint(client, x, y) ?: return drop("move")
        val nx = point[0]
        val ny = point[1]
        lastEventAt = SystemClock.uptimeMillis()
        if (pointerId == 0) {
            primaryX = nx
            primaryY = ny
            moveCount++
            if (multiActive) {
                client.touchAction(CarLifeProtocol.TouchAction.MOVE, nx, ny)
            } else {
                client.touchMove(nx, ny)
            }
        } else {
            secondaryX = nx
            secondaryY = ny
            secondaryMoveCount++
            if (!multiActive) return false
            client.touchAction(CarLifeProtocol.TouchAction.MOVE, nx, ny)
        }
        sentCount++
        return true
    }

    /** 指针抬起；短促点击用按下点回吸（避免抖动被判成拖动/抬到相邻控件）。 */
    @Synchronized
    fun up(
        x: Int,
        y: Int,
        pointerId: Int = 0,
    ): Boolean {
        val client = sender ?: return drop("up")
        if (!pressed) return false
        if (pointerId == 1) {
            if (!secondaryDown) return false
            if (multiActive) {
                client.touchAction(
                    CarLifeProtocol.TouchAction.withPointer(CarLifeProtocol.TouchAction.POINTER_UP, 1),
                    secondaryX,
                    secondaryY,
                )
                sentCount++
            }
            secondaryDown = false
            lastEventAt = SystemClock.uptimeMillis()
            return true
        }
        if (pointerId != 0) return false
        val point = mapPoint(client, x, y) ?: return drop("up")
        val nx = point[0]
        val ny = point[1]
        val now = SystemClock.uptimeMillis()
        val isTap =
            !multiActive &&
                now - tapStartedAt <= TAP_MAX_MS &&
                abs(nx - downX) <= TAP_MAX_DRIFT_PX &&
                abs(ny - downY) <= TAP_MAX_DRIFT_PX
        finishGesture(
            client,
            if (isTap) downX else nx,
            if (isTap) downY else ny,
            now,
            null,
        )
        return true
    }

    /** 取消当前手势（会话退役/客户端切换/几何失效）：有按住的手指时补发 UP，避免幽灵触点。 */
    @Synchronized
    fun cancel() {
        val client = sender
        if (client != null && pressed) {
            if (multiActive) {
                if (secondaryDown) {
                    client.touchAction(
                        CarLifeProtocol.TouchAction.withPointer(CarLifeProtocol.TouchAction.POINTER_UP, 1),
                        secondaryX,
                        secondaryY,
                    )
                    secondaryDown = false
                }
                client.touchAction(CarLifeProtocol.TouchAction.UP, primaryX, primaryY)
                sentCount++
            } else {
                client.touchUp(primaryX, primaryY)
                sentCount++
            }
            log("CARLIFE-TOUCH: 取消手势，补发 UP($primaryX,$primaryY)")
        }
        resetGesture()
    }

    // ---- 内部实现（全部在对象锁内调用）----

    private fun resetGesture() {
        pressed = false
        multiActive = false
        secondaryDown = false
        primaryX = 0
        primaryY = 0
        downX = 0
        downY = 0
        gestureStartedAt = 0L
        tapStartedAt = 0L
        lastEventAt = 0L
        moveCount = 0
        secondaryMoveCount = 0
    }

    /** 结束一笔手势：复合动作先抬起第二指再 UP；单点走 touchUp。 */
    private fun finishGesture(
        client: CarLifeHuClient,
        upX: Int,
        upY: Int,
        now: Long,
        abnormal: String?,
    ) {
        lastEventAt = now
        primaryX = upX
        primaryY = upY
        if (multiActive) {
            if (secondaryDown) {
                client.touchAction(
                    CarLifeProtocol.TouchAction.withPointer(CarLifeProtocol.TouchAction.POINTER_UP, 1),
                    secondaryX,
                    secondaryY,
                )
                secondaryDown = false
            }
            client.touchAction(CarLifeProtocol.TouchAction.UP, upX, upY)
            sentCount++
            val summary =
                "双指手势结束（第二指移动 ${secondaryMoveCount} 次）→ 复合 UP($upX,$upY)" +
                    if (abnormal != null) " · $abnormal" else ""
            log("CARLIFE-TOUCH: $summary")
            multiActive = false
        } else {
            client.touchUp(upX, upY)
            sentCount++
            val duration = now - gestureStartedAt
            if (abnormal != null) {
                log("CARLIFE-TOUCH: $abnormal → 补发 UP($upX,$upY)")
            } else if (moveCount >= 3 || duration >= 300L) {
                val span = abs(upX - downX) + abs(upY - downY)
                log("CARLIFE-TOUCH: 手势结束 移动${moveCount}次 时长${duration}ms 位移${span}px ↓($downX,$downY)→UP($upX,$upY)")
            }
        }
        resetGesture()
    }

    /**
     * 显示坐标 → 视频坐标（移植 hybrid63 CarLifeTouch.onTouch 的公式，含 contain 窗格）：
     * 先把视频按原始宽高比 contain 到 contentRect 得到实际窗格 pane，再按比例映射。
     */
    private fun mapPoint(
        client: CarLifeHuClient,
        x: Int,
        y: Int,
    ): IntArray? {
        val vw = viewportWidth
        val vh = viewportHeight
        if (vw <= 0 || vh <= 0) return null
        val source = effectiveSourceSize(client) ?: return null
        val sw = source.first
        val sh = source.second
        val pane = containedPane(sw, sh) ?: return null
        val nx = ((x - pane[0]) * sw / pane[2].toDouble()).roundToInt().coerceIn(0, sw - 1)
        val ny = ((y - pane[1]) * sh / pane[3].toDouble()).roundToInt().coerceIn(0, sh - 1)
        return intArrayOf(nx, ny)
    }

    /** 把 sourceW×sourceH 等比 contain 进 [content]，返回 [x, y, width, height]（帧坐标）。 */
    private fun containedPane(
        sourceWidth: Int,
        sourceHeight: Int,
    ): IntArray? {
        val rect = content
        val cw = rect.width()
        val ch = rect.height()
        if (cw <= 0 || ch <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return null
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        var paneW = cw.toDouble()
        var paneH = ch.toDouble()
        var paneX = rect.left.toDouble()
        var paneY = rect.top.toDouble()
        if (cw.toDouble() / ch > sourceAspect) {
            // 内容区更宽：以高为准，左右留边
            paneH = ch.toDouble()
            paneW = ch * sourceAspect
            paneX += (cw - paneW) / 2.0
        } else {
            // 内容区更高：以宽为准，上下留边
            paneW = cw.toDouble()
            paneH = cw / sourceAspect
            paneY += (ch - paneH) / 2.0
        }
        return intArrayOf(paneX.roundToInt(), paneY.roundToInt(), paneW.roundToInt().coerceAtLeast(1), paneH.roundToInt().coerceAtLeast(1))
    }

    private fun effectiveSourceSize(client: CarLifeHuClient): Pair<Int, Int>? {
        val size =
            if (explicitSourceWidth > 0 && explicitSourceHeight > 0) {
                explicitSourceWidth to explicitSourceHeight
            } else {
                client.videoSize
            }
        return if (size.first > 0 && size.second > 0) size else null
    }

    /** 未就绪时丢弃触摸：只限频留痕，避免把正常等待阶段的日志刷满。 */
    private fun drop(reason: String): Boolean {
        ignoredCount++
        if (ignoredCount <= 3 || ignoredCount % 200 == 0L) {
            log("CARLIFE-TOUCH: 触摸暂不可下发（$reason），客户端/几何未就绪，累计忽略 $ignoredCount 条")
        }
        return false
    }

    private companion object {
        /** 新手势间隔：超过这个时长又收到按下，就认定上一笔的 UP 丢了（毫秒）。 */
        private const val NEW_GESTURE_GAP_MS = 220L

        /** 点击判定：按住时长上限（毫秒）。 */
        private const val TAP_MAX_MS = 350L

        /** 点击判定：允许的手指抖动（视频像素）。 */
        private const val TAP_MAX_DRIFT_PX = 8

        /** 按下后多久没有任何事件就认定 UP 丢失（毫秒）。 */
        private const val STALE_TOUCH_MS = 3_000L
    }
}
