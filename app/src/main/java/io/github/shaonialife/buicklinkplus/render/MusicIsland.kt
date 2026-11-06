package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.NavGuidance
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.util.Bg
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 音乐岛包含隐藏、胶囊和展开卡，展示音乐或导航，左右滑动切换
 * 内容变化时后台生成位图，动画只插值位置和透明度
 * 绘制和触摸共用 [boxFor] 几何
 */
internal class MusicIsland(
    private val geometry: DisplayGeometry,
    context: Context,
) {
    private val context = context.applicationContext
    private val mediaGlyph = MediaGlyph(context)

    enum class State { HIDDEN, COMPACT, EXPANDED }

    /** 岛上展示的内容源：导航指引优先，用户可滑动切换 */
    private enum class Content { NAV, MUSIC }

    @Volatile private var userContent: Content? = null

    /** 上滑隐藏后本次连接内不再显示；设置开关重开或重连才恢复 */
    @Volatile private var dismissed = false

    /** 上一次读到的设置开关值（关→开的沿视为用户要求恢复显示） */
    @Volatile private var lastPrefVisible = true

    /** 岛矩形（内容区像素，顶部原点），命中与绘制同源 */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /** 圆角外不算命中（与 FloatingPanelLayout.Panel.contains 同一公式） */
        fun contains(
            x: Float,
            y: Float,
            radius: Float,
        ): Boolean {
            if (x < left || x >= right || y < top || y >= bottom) return false
            val cx = x.coerceIn(left + radius, right - radius)
            val cy = y.coerceIn(top + radius, bottom - radius)
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= radius * radius
        }
    }

    /** 计算过渡帧，主层与旧层按透明度混合；[outgoingAlpha] 为 0 时只绘制主层 */
    class Frame(
        val box: Box,
        val primaryExpanded: Boolean,
        val primaryAlpha: Float,
        val outgoingExpanded: Boolean,
        val outgoingAlpha: Float,
    )

    /** 后台渲染产出的两张位图；GL 线程取走上传后回收 */
    class Snapshot(
        val compact: Bitmap,
        val expanded: Bitmap,
    ) {
        fun recycle() {
            compact.recycle()
            expanded.recycle()
        }
    }

    @Volatile private var fromState = State.HIDDEN

    @Volatile private var toState = State.HIDDEN

    /** 当前过渡起点（elapsedRealtime）；from == to 时无过渡 */
    @Volatile private var transitionStart = 0L

    /** 展开卡最后交互时刻，超时自动收回胶囊 */
    @Volatile private var lastInteraction = 0L

    private val stateLock = Any()

    private val lock = Any()
    private var pending: Snapshot? = null
    private var closed = false
    private val working =
        java.util.concurrent.atomic
            .AtomicBoolean()

    @Volatile private var lastKey: String? = null
    private var nextPollAt = 0L

    private val mediaListener = {
        syncVisibility()
        requestRender()
    }

    private val navListener = {
        syncVisibility()
        requestRender()
    }

    init {
        active = this
        MediaSessionsState.addListener(mediaListener)
        NavGuidance.addListener(navListener)
        syncVisibility()
        requestRender()
    }

    /** 切换目标状态，快速连点时先完成当前过渡再开始下一次 */
    fun setTarget(state: State) {
        synchronized(stateLock) {
            if (state == toState) return
            fromState = toState
            toState = state
            val now = android.os.SystemClock.elapsedRealtime()
            transitionStart = now
            if (state == State.EXPANDED) lastInteraction = now
        }
    }

    /** 点按分发（TouchRouter 抬手时调用）：胶囊展开，展开卡按钮走媒体键，其余收起 */
    fun onTap(
        x: Float,
        y: Float,
        context: Context,
    ) {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        when (toState) {
            State.COMPACT -> setTarget(State.EXPANDED)
            State.EXPANDED ->
                if (resolveContent() == Content.NAV) {
                    // 导航卡无按钮：点按即收起
                    setTarget(State.COMPACT)
                } else {
                    when (buttonAt(animatedBox(), x, y)) {
                        BUTTON_PREV -> MediaSessionsState.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "音乐岛")
                        BUTTON_PLAY -> MediaSessionsState.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "音乐岛")
                        BUTTON_NEXT -> MediaSessionsState.dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT, "音乐岛")
                        null -> setTarget(State.COMPACT)
                    }
                }
            State.HIDDEN -> Unit
        }
    }

    /** 上滑隐藏：本次连接内不再显示，换歌/新导航都不再打扰 */
    fun dismiss() {
        dismissed = true
        setTarget(State.HIDDEN)
    }

    /** 左/右滑切换展示内容（音乐 ⇄ 导航）；另一内容不存在时无效果 */
    fun onSwipe() {
        val current = resolveContent() ?: return
        val other = if (current == Content.NAV) Content.MUSIC else Content.NAV
        val otherAvailable =
            if (other == Content.NAV) {
                activeNavStep() != null
            } else {
                MediaSessionsState.nowPlaying != null
            }
        if (!otherAvailable) return
        userContent = other
        lastInteraction = android.os.SystemClock.elapsedRealtime()
        syncVisibility()
        requestRender()
    }

    /** 命中测试（内容区像素）：隐藏与淡出途中不接管触摸 */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean {
        if (toState == State.HIDDEN) return false
        val box = animatedBox()
        // 胶囊是全圆角，展开卡是固定圆角——命中圆角与各自位图一致
        val corner = if (toState == State.EXPANDED) box.height * EXPANDED_RADIUS_RATIO else box.height / 2f
        return box.contains(x, y, corner)
    }

    /** GL 每帧更新收起计时和绘制参数，完全隐藏时返回 null */
    fun frameAt(elapsed: Long): Frame? {
        if (elapsed >= nextPollAt) {
            nextPollAt = elapsed + POLL_MS
            syncVisibility()
            requestRender()
        }
        synchronized(stateLock) {
            if (toState == State.EXPANDED && elapsed - lastInteraction >= EXPANDED_HOLD_MS) {
                fromState = toState
                toState = State.COMPACT
                transitionStart = elapsed
            }
            if (fromState == State.HIDDEN && toState == State.HIDDEN) return null
            val rawT =
                if (fromState == toState) {
                    1f
                } else {
                    ((elapsed - transitionStart).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
                }
            if (rawT >= 1f) fromState = toState
            val spec = spec(fromState, toState, rawT, geometry.content.width, geometry.content.height)
            return Frame(spec.box, spec.primaryExpanded, spec.primaryAlpha, spec.outgoingExpanded, spec.outgoingAlpha)
        }
    }

    /** GL 线程取走待上传位图（每份快照只取一次，上传后调用方回收） */
    fun takeSnapshot(): Snapshot? =
        synchronized(lock) {
            val snapshot = pending
            pending = null
            snapshot
        }

    /** 合成循环退出后在 GL 线程调用：回收未取走的快照并注销监听 */
    fun close() {
        synchronized(lock) {
            closed = true
            pending?.recycle()
            pending = null
        }
        // 会话重启时新实例可能已就位，不能把它的引用清掉
        if (active === this) active = null
        MediaSessionsState.removeListener(mediaListener)
        NavGuidance.removeListener(navListener)
    }

    /** 设置开启、未被上滑隐藏且有可展示内容才显示 */
    private fun syncVisibility() {
        val pref = CarAppearancePrefs.musicIslandVisible(context)
        // 设置关→开是用户显式要求恢复，解除上滑隐藏
        if (pref && !lastPrefVisible) dismissed = false
        lastPrefVisible = pref
        val visible = pref && !dismissed && resolveContent() != null
        setTarget(
            when {
                !visible -> State.HIDDEN
                toState == State.HIDDEN -> State.COMPACT
                else -> toState
            },
        )
    }

    /** 当前展示内容：用户滑动选择优先，所选内容消失时回退另一内容 */
    private fun resolveContent(): Content? {
        val hasNav = activeNavStep() != null
        val hasMusic = MediaSessionsState.nowPlaying != null
        return when (userContent) {
            Content.NAV ->
                if (hasNav) {
                    Content.NAV
                } else if (hasMusic) {
                    Content.MUSIC
                } else {
                    null
                }
            Content.MUSIC ->
                if (hasMusic) {
                    Content.MUSIC
                } else if (hasNav) {
                    Content.NAV
                } else {
                    null
                }
            null ->
                if (hasNav) {
                    Content.NAV
                } else if (hasMusic) {
                    Content.MUSIC
                } else {
                    null
                }
        }
    }

    /** 地图全屏时不显示导航岛，改为音乐或隐藏 */
    private fun activeNavStep(): NavGuidance.Step? =
        NavGuidance.current?.takeIf {
            VirtualLauncher.presentedMapLayout() != DualDisplayComposer.TileLayout.FULLSCREEN
        }

    private fun animatedBox(): Box {
        val now = android.os.SystemClock.elapsedRealtime()
        val rawT =
            if (fromState == toState) {
                1f
            } else {
                ((now - transitionStart).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
            }
        return spec(fromState, toState, rawT, geometry.content.width, geometry.content.height).box
    }

    /** 音乐岛重绘键，进度按 500ms 分桶，避免逐帧生成位图 */
    private fun contentKey(): String {
        val nav = activeNavStep()
        val media = MediaSessionsState.nowPlaying
        val position = media?.displayPositionAt(android.os.SystemClock.elapsedRealtime())?.div(500) ?: 0
        return "${resolveContent()}|${nav?.direction}|${nav?.distance}|${nav?.road}|${nav?.text}|" +
            "${media?.title}|${media?.artist}|${media?.playing}|${media?.durationMs}|" +
            "${System.identityHashCode(media?.art)}|${media?.sessionGeneration}|$position|" +
            "${MediaSessionsState.accessGranted}"
    }

    /** 内容键变化才排后台渲染；同键重入直接丢弃 */
    private fun requestRender() {
        val key = contentKey()
        if (key == lastKey) return
        if (!working.compareAndSet(false, true)) return
        lastKey = key
        val width = geometry.content.width
        val height = geometry.content.height
        Bg.run("music-island") {
            try {
                val step = if (resolveContent() == Content.NAV) activeNavStep() else null
                val media = MediaSessionsState.nowPlaying
                val granted = MediaSessionsState.accessGranted
                val elapsed = android.os.SystemClock.elapsedRealtime()
                val snapshot =
                    if (step != null) {
                        Snapshot(renderNavCompact(width, height, step), renderNavExpanded(width, height, step))
                    } else {
                        Snapshot(
                            renderCompact(width, height, media, granted),
                            renderExpanded(width, height, media, granted, elapsed),
                        )
                    }
                synchronized(lock) {
                    if (closed) {
                        snapshot.recycle()
                    } else {
                        pending?.recycle()
                        pending = snapshot
                    }
                }
            } finally {
                working.set(false)
            }
        }
    }

    /** 紧凑胶囊：封面 + 歌名 + 播放态标记 */
    private fun renderCompact(
        contentWidth: Int,
        contentHeight: Int,
        media: MediaSessionsState.NowPlaying?,
        granted: Boolean,
    ): Bitmap {
        val box = boxFor(State.COMPACT, contentWidth, contentHeight)
        val width = box.width.roundToInt().coerceAtLeast(1)
        val height = box.height.roundToInt().coerceAtLeast(1)
        val s = width / REF_COMPACT_W
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 黑色不透明底，圆角外保留透明
                color = Color.BLACK
            }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 28 * s, 28 * s, bg)
        drawArt(canvas, media, 8f * s, 8f * s, 40f * s, 10f * s)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 18f * s
            }
        val label = titleText(media, granted)
        val maxTitle = width - 60f * s - 56f * s
        canvas.drawText(
            TextUtils.ellipsize(label, title, maxTitle, TextUtils.TruncateAt.END).toString(),
            60f * s,
            35f * s,
            title,
        )
        if (media?.playing == true) {
            val bars = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 193, 252) }
            val x0 = width - 38f * s
            val base = height / 2f
            floatArrayOf(10f, 16f, 7f).forEachIndexed { index, barH ->
                val h = barH * s
                canvas.drawRoundRect(
                    RectF(x0 + index * 9f * s, base - h / 2f, x0 + index * 9f * s + 4f * s, base + h / 2f),
                    2f * s,
                    2f * s,
                    bars,
                )
            }
        } else {
            val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 193, 252) }
            val cx = width - 26f * s
            val cy = height / 2f
            mediaGlyph.playPause(canvas, glyph, cx, cy, 9f * s, false)
        }
        return bitmap
    }

    /** 展开卡：封面 + 歌名/歌手 + 时间与进度 + 上一/播放/下一按钮 */
    private fun renderExpanded(
        contentWidth: Int,
        contentHeight: Int,
        media: MediaSessionsState.NowPlaying?,
        granted: Boolean,
        elapsed: Long,
    ): Bitmap {
        val box = boxFor(State.EXPANDED, contentWidth, contentHeight)
        val width = box.width.roundToInt().coerceAtLeast(1)
        val height = box.height.roundToInt().coerceAtLeast(1)
        val s = width / REF_EXPANDED_W
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 黑色不透明底，圆角外保留透明
                color = Color.BLACK
            }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 24f * s, 24f * s, bg)
        drawArt(canvas, media, 20f * s, 18f * s, 76f * s, 14f * s)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 22f * s
            }
        val secondary =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 15f * s
            }
        val maxText = width - 112f * s - 150f * s
        canvas.drawText(
            TextUtils.ellipsize(titleText(media, granted), title, maxText, TextUtils.TruncateAt.END).toString(),
            112f * s,
            50f * s,
            title,
        )
        media
            ?.artist
            ?.takeIf { it.isNotBlank() }
            ?.let {
                canvas.drawText(
                    TextUtils.ellipsize(it, secondary, maxText, TextUtils.TruncateAt.END).toString(),
                    112f * s,
                    78f * s,
                    secondary,
                )
            }
        val duration = media?.durationMs ?: 0L
        if (duration > 0) {
            val position = media?.displayPositionAt(elapsed) ?: 0L
            val time = "${formatTime(position)} / ${formatTime(duration)}"
            canvas.drawText(time, width - 20f * s - secondary.measureText(time), 50f * s, secondary)
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(48, 255, 255, 255) }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val trackW = width - 40f * s
            val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
            canvas.drawRoundRect(RectF(20f * s, 100f * s, width - 20f * s, 105f * s), 2.5f * s, 2.5f * s, track)
            if (fraction > 0f) {
                canvas.drawRoundRect(RectF(20f * s, 100f * s, 20f * s + trackW * fraction, 105f * s), 2.5f * s, 2.5f * s, fill)
            }
        }
        val playing = media?.playing == true
        BUTTON_CENTERS.forEachIndexed { index, (fx, fy) ->
            val cx = fx * width
            val cy = fy * height
            val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
            canvas.drawCircle(cx, cy, 20f * s, circle)
            val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            when (index) {
                BUTTON_PREV -> mediaGlyph.skip(canvas, glyph, cx, cy, 9f * s, forward = false)
                BUTTON_PLAY -> mediaGlyph.playPause(canvas, glyph, cx, cy, 9f * s, playing)
                BUTTON_NEXT -> mediaGlyph.skip(canvas, glyph, cx, cy, 9f * s, forward = true)
            }
        }
        return bitmap
    }

    /** 导航紧凑态：转向箭头 + 距离动作 + 道路 */
    private fun renderNavCompact(
        contentWidth: Int,
        contentHeight: Int,
        step: NavGuidance.Step,
    ): Bitmap {
        val box = boxFor(State.COMPACT, contentWidth, contentHeight)
        val width = box.width.roundToInt().coerceAtLeast(1)
        val height = box.height.roundToInt().coerceAtLeast(1)
        val s = width / REF_COMPACT_W
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 黑色不透明底，圆角外保留透明
                color = Color.BLACK
            }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 28 * s, 28 * s, bg)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
        canvas.drawCircle(28f * s, height / 2f, 20f * s, circle)
        NavGlyph.draw(canvas, 28f * s, height / 2f, 11f * s, step.direction)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 18f * s
            }
        val road =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 12f * s
            }
        val roadText = step.road.orEmpty()
        val maxTitle = width - 58f * s - 20f * s - if (roadText.isEmpty()) 0f else road.measureText(roadText) + 12f * s
        canvas.drawText(
            TextUtils.ellipsize(NavGlyph.label(step), title, maxTitle, TextUtils.TruncateAt.END).toString(),
            58f * s,
            35f * s,
            title,
        )
        if (roadText.isNotEmpty()) {
            canvas.drawText(roadText, width - 20f * s - road.measureText(roadText), 33f * s, road)
        }
        return bitmap
    }

    /** 导航展开卡：转向箭头 + 距离动作 + 道路 + 原始指引文案 */
    private fun renderNavExpanded(
        contentWidth: Int,
        contentHeight: Int,
        step: NavGuidance.Step,
    ): Bitmap {
        val box = boxFor(State.EXPANDED, contentWidth, contentHeight)
        val width = box.width.roundToInt().coerceAtLeast(1)
        val height = box.height.roundToInt().coerceAtLeast(1)
        val s = width / REF_EXPANDED_W
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                // 黑色不透明底，圆角外保留透明
                color = Color.BLACK
            }
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 24f * s, 24f * s, bg)
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
        canvas.drawCircle(58f * s, 56f * s, 38f * s, circle)
        NavGlyph.draw(canvas, 58f * s, 56f * s, 21f * s, step.direction)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 22f * s
            }
        val secondary =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 15f * s
            }
        val maxText = width - 112f * s - 24f * s
        canvas.drawText(
            TextUtils.ellipsize(NavGlyph.label(step), title, maxText, TextUtils.TruncateAt.END).toString(),
            112f * s,
            50f * s,
            title,
        )
        step.road?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText(
                TextUtils.ellipsize(it, secondary, maxText, TextUtils.TruncateAt.END).toString(),
                112f * s,
                78f * s,
                secondary,
            )
        }
        canvas.drawText(
            TextUtils.ellipsize(step.text, secondary, width - 40f * s, TextUtils.TruncateAt.END).toString(),
            20f * s,
            140f * s,
            secondary,
        )
        return bitmap
    }

    /** 封面缩放绘制；无封面时画音符占位 */
    private fun drawArt(
        canvas: Canvas,
        media: MediaSessionsState.NowPlaying?,
        left: Float,
        top: Float,
        size: Float,
        radius: Float,
    ) {
        val rect = RectF(left, top, left + size, top + size)
        val art = media?.art
        if (art != null && !art.isRecycled) {
            val clip =
                Path().apply {
                    addRoundRect(rect, radius, radius, Path.Direction.CW)
                }
            canvas.withClip(clip) {
                canvas.drawBitmap(art, null, rect, Paint(Paint.ANTI_ALIAS_FLAG))
            }
        } else {
            val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 211, 193, 252) }
            canvas.drawRoundRect(rect, radius, radius, fallback)
            mediaGlyph.draw(canvas, R.drawable.ic_music, rect.centerX(), rect.centerY(), size * 0.3f, Color.rgb(211, 193, 252))
        }
    }

    companion object {
        const val BUTTON_PREV = 0
        const val BUTTON_PLAY = 1
        const val BUTTON_NEXT = 2

        /** 过渡时长（显隐淡入淡出与紧凑 ⇄ 展开形变共用） */
        const val TRANSITION_MS = 280L

        /** 展开卡无操作自动收回 */
        const val EXPANDED_HOLD_MS = 8_000L

        /** 内容键轮询间隔（进度走秒用） */
        private const val POLL_MS = 700L

        private const val REF_COMPACT_W = 400f
        private const val REF_COMPACT_H = 56f
        private const val REF_EXPANDED_W = 600f
        private const val REF_EXPANDED_H = 168f

        /** 参考安全区 1408×476，按内容画布缩放 */
        private const val REF_CONTENT_W = 1408f
        private const val REF_CONTENT_H = 476f

        /** 展开卡按钮圆心（框内比例）与命中半径（相对框高），命中与绘制共用 */
        private val BUTTON_CENTERS = listOf(0.4147f to 0.8333f, 0.5f to 0.8333f, 0.5853f to 0.8333f)

        private const val BUTTON_HIT_R = 0.17f

        /** 命中半径相对框宽上限：不超过相邻按钮圆心间距一半，圆域不重叠 */
        private const val BUTTON_MAX_HIT_R = 0.039f

        /** 展开卡圆角（24/168）相对框高的比例，命中与位图绘制一致 */
        private const val EXPANDED_RADIUS_RATIO = 24f / 168f

        /** 当前活跃岛（TouchRouter 命中入口）；合成器退出后清空 */
        @Volatile
        // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
        @android.annotation.SuppressLint("StaticFieldLeak")
        var active: MusicIsland? = null
            private set

        /** 显示缩放按内容区宽高相对参考画布约束，同尺寸画布为 1:1 */
        fun scale(
            contentWidth: Int,
            contentHeight: Int,
        ): Float = minOf(contentHeight / REF_CONTENT_H, contentWidth / REF_CONTENT_W)

        /** 各态矩形（内容区像素，顶部居中）；HIDDEN 锚在胶囊矩形上供显隐动画 */
        fun boxFor(
            state: State,
            contentWidth: Int,
            contentHeight: Int,
        ): Box {
            val s = scale(contentWidth, contentHeight)
            val refW: Float
            val refH: Float
            if (state == State.EXPANDED) {
                refW = REF_EXPANDED_W
                refH = REF_EXPANDED_H
            } else {
                refW = REF_COMPACT_W
                refH = REF_COMPACT_H
            }
            val w = refW * s
            val h = refH * s
            val margin = 12f * s
            val cx = contentWidth / 2f
            return Box(cx - w / 2f, margin, cx + w / 2f, margin + h)
        }

        /** 展开卡按钮命中：返回 BUTTON_*，圆外返回 null */
        fun buttonAt(
            box: Box,
            x: Float,
            y: Float,
        ): Int? {
            val r = minOf(BUTTON_HIT_R * box.height, box.width * BUTTON_MAX_HIT_R)
            BUTTON_CENTERS.forEachIndexed { index, (fx, fy) ->
                val cx = box.left + fx * box.width
                val cy = box.top + fy * box.height
                if (hypot(x - cx, y - cy) <= r) return index
            }
            return null
        }

        /** 缓出曲线（smoothstep），显隐与形变共用 */
        fun ease(t: Float): Float {
            val v = t.coerceIn(0f, 1f)
            return v * v * (3f - 2f * v)
        }

        /** 计算状态过渡：隐藏只淡入淡出，可见状态之间插值矩形并交叉淡入 */
        fun spec(
            from: State,
            to: State,
            rawT: Float,
            contentWidth: Int,
            contentHeight: Int,
        ): Frame {
            val t = ease(rawT.coerceIn(0f, 1f))
            if (from == to || rawT >= 1f) {
                val box = boxFor(to, contentWidth, contentHeight)
                return if (to == State.HIDDEN) {
                    Frame(box, false, 0f, false, 0f)
                } else {
                    Frame(box, to == State.EXPANDED, 1f, false, 0f)
                }
            }
            return when {
                from == State.HIDDEN ->
                    Frame(boxFor(to, contentWidth, contentHeight), to == State.EXPANDED, t, false, 0f)
                to == State.HIDDEN ->
                    Frame(boxFor(from, contentWidth, contentHeight), from == State.EXPANDED, 1f - t, false, 0f)
                else ->
                    Frame(
                        lerp(boxFor(from, contentWidth, contentHeight), boxFor(to, contentWidth, contentHeight), t),
                        to == State.EXPANDED,
                        t,
                        from == State.EXPANDED,
                        1f - t,
                    )
            }
        }

        /** m:ss 播放时间 */
        fun formatTime(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
        }

        private fun titleText(
            media: MediaSessionsState.NowPlaying?,
            granted: Boolean,
        ): String =
            media?.title?.takeIf { it.isNotBlank() }
                ?: when {
                    !granted -> "暂无播放信息"
                    media?.playing == true -> "正在播放"
                    else -> "暂无播放"
                }

        private fun lerp(
            a: Box,
            b: Box,
            t: Float,
        ): Box =
            Box(
                a.left + (b.left - a.left) * t,
                a.top + (b.top - a.top) * t,
                a.right + (b.right - a.right) * t,
                a.bottom + (b.bottom - a.bottom) * t,
            )
    }
}
