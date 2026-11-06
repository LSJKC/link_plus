package io.github.shaonialife.buicklinkplus.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.NavGuidance
import io.github.shaonialife.buicklinkplus.media.WeatherState
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsDocument
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsRepository
import io.github.shaonialife.buicklinkplus.media.lyrics.LyricsTrack
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.util.Bg
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 在安全区左侧绘制用户选择的小组件，使用 242×128 设计尺寸等比居中
 * 内容变化时后台生成位图，GL 上传纹理，触摸经 [active] 派发
 */
internal class LeftWidgetColumn(
    private val geometry: DisplayGeometry,
    context: Context,
) {
    private val context = context.applicationContext
    private val mediaGlyph = MediaGlyph(context)

    enum class Widget(
        val id: String,
    ) {
        MUSIC("music"),
        LYRICS("lyrics"),
        NAV("nav"),
        TRIP("trip"),
        MUSIC_CONTROL("musicControl"),
        CLOCK("clock"),
        CALENDAR("calendar"),
        WEATHER("weather"),
        PARKING("parking"),
        QUICK_APPS("quickApps"),
        ;

        companion object {
            /** 板块排列顺序：动态信息在上，静态与工具收底 */
            val displayOrder =
                listOf(MUSIC, LYRICS, NAV, TRIP, MUSIC_CONTROL, CLOCK, CALENDAR, WEATHER, PARKING, QUICK_APPS)
        }
    }

    private val lock = Any()
    private val working = AtomicBoolean()
    private var closed = false
    private var prepared: Bitmap? = null
    private var nextCheckAt = 0L

    @Volatile private var lastKey: String? = null

    /** 当前曲目的 LRC 时间轴（播放器全文经 LRCLIB 兜底），到达后驱动重绘 */
    @Volatile private var lyricDocument: LyricsDocument? = null

    private var lastTrack: LyricsTrack? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 多个窗口共用歌词查询，播放器未共享时查询 LRCLIB */
    private val lyricsRepository =
        LyricsRepository(context) { result ->
            lyricDocument = result.document
            lastKey = null
        }

    /** 本次连接起点（行程信息用） */
    private val sessionStartedAt = android.os.SystemClock.elapsedRealtime()

    init {
        active = this
    }

    /**
     * 每 700ms 读一次内存快照，内容未变不绘图；文字排版与光栅化在后台完成
     */
    fun takePrepared(elapsed: Long): Bitmap? {
        val bounds = area(geometry) ?: return null
        if (elapsed >= nextCheckAt) {
            nextCheckAt = elapsed + 700
            WeatherState.ensureFresh(context, CarAppearancePrefs.weatherCity(context), elapsed)
            syncLyricsTrack()
            val key = contentKey(elapsed)
            if (key != lastKey && working.compareAndSet(false, true)) {
                lastKey = key
                val selection = selected()
                Bg.run("left-widgets") {
                    try {
                        val bitmap = draw(bounds, selection, elapsed)
                        synchronized(lock) {
                            if (closed) {
                                bitmap.recycle()
                            } else {
                                prepared?.recycle()
                                prepared = bitmap
                            }
                        }
                    } finally {
                        working.set(false)
                    }
                }
            }
        }
        return synchronized(lock) { prepared.also { prepared = null } }
    }

    /** 结束后释放尚未上传的独占位图，后台迟到结果同样丢弃 */
    fun close() {
        synchronized(lock) {
            closed = true
            prepared?.recycle()
            prepared = null
        }
        if (active === this) active = null
        mainHandler.post { lyricsRepository.clear() }
    }

    /** 命中测试（流像素）：整列留白 */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean {
        val bounds = area(geometry) ?: return false
        return bounds.contains(x.roundToInt(), y.roundToInt())
    }

    /** 抬起时分发音乐控制、快捷应用和停车计时操作 */
    fun onTap(
        x: Float,
        y: Float,
        context: Context,
    ) {
        val bounds = area(geometry) ?: return
        val selection = selected()
        val sections = sectionRects(bounds, selection.size)
        sections.forEachIndexed { index, section ->
            if (!section.contains(x.roundToInt(), y.roundToInt())) return@forEachIndexed
            val point = designPoint(section, x, y) ?: return
            when (selection[index]) {
                Widget.MUSIC_CONTROL -> {
                    val button = buttonIndexAt(point.first) ?: return
                    val key =
                        when (button) {
                            0 -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                            1 -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            else -> KeyEvent.KEYCODE_MEDIA_NEXT
                        }
                    MediaSessionsState.dispatchMediaKey(context, key, "左侧组件")
                }
                Widget.QUICK_APPS -> {
                    val button = buttonIndexAt(point.first) ?: return
                    launchQuickApp(button, context)
                }
                Widget.PARKING -> toggleParking()
                else -> Unit
            }
            return
        }
    }

    /** 快捷应用：0=地图 1=音乐 2=桌面（桌面切主线程回退） */
    private fun launchQuickApp(
        button: Int,
        context: Context,
    ) {
        val packages =
            when (button) {
                0 -> listOf("com.autonavi.amapauto", "com.autonavi.minimap")
                1 -> listOf("com.tencent.qqmusic")
                else -> {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { VirtualLauncher.showDesktop() }
                    return
                }
            }
        val intent = packages.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) }
        if (intent != null) {
            runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** 停车计时：点按开始/停止，起点持久化（进程重启继续计） */
    private fun toggleParking() {
        val running = CarAppearancePrefs.parkingStartedAt(context) > 0
        CarAppearancePrefs.setParkingStartedAt(context, if (running) 0L else System.currentTimeMillis())
    }

    /** 设计空间 x → 就近按钮序号（0/1/2），距圆心超过 30 不命中 */
    private fun buttonIndexAt(designX: Float): Int? {
        val nearest = BUTTON_X.indices.minByOrNull { kotlin.math.abs(designX - BUTTON_X[it]) } ?: return null
        return nearest.takeIf { kotlin.math.abs(designX - BUTTON_X[it]) <= 30f }
    }

    private fun selected(): List<Widget> {
        val chosen = CarAppearancePrefs.leftWidgets(context)
        return Widget.displayOrder.filter { it.id in chosen }
    }

    /** 按组件内容生成重绘键，时钟按分钟、停车计时按秒更新 */
    private fun contentKey(elapsed: Long): String {
        val media = MediaSessionsState.nowPlaying
        val nav = NavGuidance.current
        val selection = selected()
        val parkingSince = CarAppearancePrefs.parkingStartedAt(context)
        val tick =
            if (Widget.PARKING in selection && parkingSince > 0) {
                elapsed / 1000
            } else {
                elapsed / 60_000
            }
        return "${selection.joinToString(",")}|$tick|" +
            "${media?.title}|${media?.artist}|${media?.playing}|" +
            "${System.identityHashCode(media?.art)}|${currentLyricLine(elapsed)}|" +
            "${nav?.direction}|${nav?.distance}|${nav?.remaining}|${nav?.text}|" +
            "${WeatherState.current}|${CarAppearancePrefs.weatherCity(context)}|" +
            "$parkingSince|${MediaSessionsState.accessGranted}"
    }

    /** 曲目身份变化才查询歌词（LRCLIB/播放器全文），切换后驱动一次重绘 */
    private fun syncLyricsTrack() {
        val media = MediaSessionsState.nowPlaying
        val track =
            media?.let {
                LyricsTrack(
                    session = it.sessionGeneration,
                    packageName = it.pkg.orEmpty(),
                    mediaId = it.mediaId,
                    title = it.title.orEmpty(),
                    artist = it.artist.orEmpty(),
                    album = it.album.orEmpty(),
                    durationMs = it.durationMs,
                    embedded = it.lyrics,
                )
            }
        if (track != lastTrack) {
            lastTrack = track
            if (track == null) {
                lyricDocument = null
                mainHandler.post { lyricsRepository.clear() }
            } else {
                mainHandler.post { lyricsRepository.select(track) }
            }
        }
    }

    /** 当前歌词句：播放器实时句优先，其次按位置从 LRC 时间轴取句 */
    private fun currentLyricLine(elapsed: Long): String? {
        MediaSessionsState.nowPlaying
            ?.lyricLine
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val document = lyricDocument ?: return null
        val position = MediaSessionsState.nowPlaying?.displayPositionAt(elapsed) ?: 0L
        return document.lines
            .getOrNull(document.lineAt(position))
            ?.text
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** 板块参考设计空间（同车每格约 242×128 流像素），文字按比例缩放居中 */
        const val REF_W = 242f

        const val REF_H = 128f

        /** 快捷应用/音乐控制按钮圆心（设计空间） */
        private val BUTTON_X = listOf(61f, 121f, 181f)

        /** 当前活跃组件列（TouchRouter 命中入口）；合成器退出后清空 */
        @Volatile
        // 仅持有 applicationContext 的会话资源；退出时停止并清理，不持有 Activity
        @android.annotation.SuppressLint("StaticFieldLeak")
        var active: LeftWidgetColumn? = null
            private set

        /** 使用左缘与安全区之间的留白，上下留边避开车机状态栏 */
        fun area(geometry: DisplayGeometry): PixelArea? {
            if (!geometry.outsideUiAllowed || geometry.content.width <= geometry.content.height) return null
            val scale = geometry.content.height / 476f
            val sideMargin = (14 * scale).roundToInt().coerceAtLeast(1)
            val topMargin = (24 * scale).roundToInt().coerceAtLeast(1)
            val bottomMargin = (48 * scale).roundToInt().coerceAtLeast(1)
            val left = geometry.streamX(geometry.sourceView.left) + sideMargin
            val right = geometry.content.left - sideMargin
            return PixelArea(left, geometry.content.top + topMargin, right, geometry.content.bottom - bottomMargin)
                .takeIf { it.width >= 120 * scale && it.height >= 180 * scale }
        }

        /** 纵向等分板块（板块间留缝），板块数由勾选数量决定 */
        fun sectionRects(
            area: PixelArea,
            count: Int,
        ): List<PixelArea> {
            if (count <= 0) return emptyList()
            val gap = maxOf(6, area.width / 24)
            val sectionHeight = (area.height - gap * (count - 1)) / count
            return (0 until count).map { index ->
                val top = area.top + index * (sectionHeight + gap)
                PixelArea(area.left, top, area.right, top + sectionHeight)
            }
        }

        /** 流像素 → 设计空间（等比居中变换的逆），板块外返回 null */
        internal fun designPoint(
            section: PixelArea,
            x: Float,
            y: Float,
        ): Pair<Float, Float>? {
            val s = min(section.width / REF_W, section.height / REF_H)
            if (s <= 0f) return null
            val dx = section.left + (section.width - REF_W * s) / 2f
            val dy = section.top + (section.height - REF_H * s) / 2f
            val px = (x - dx) / s
            val py = (y - dy) / s
            return if (px in 0f..REF_W && py in 0f..REF_H) px to py else null
        }
    }

    /** 整列位图：按板块顺序逐格绘制 */
    private fun draw(
        bounds: PixelArea,
        selection: List<Widget>,
        elapsed: Long,
    ): Bitmap {
        val width = bounds.width
        val height = bounds.height
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val media = MediaSessionsState.nowPlaying
        val granted = MediaSessionsState.accessGranted
        val nav = NavGuidance.current
        val weather = WeatherState.current
        val parkingSince = CarAppearancePrefs.parkingStartedAt(context)
        val sections = sectionRects(PixelArea(0, 0, width, height), selection.size)
        selection.forEachIndexed { index, widget ->
            val section = sections[index]
            drawSectionCard(canvas, section, bounds)
            val s = min(section.width / REF_W, section.height / REF_H)
            val dx = section.left + (section.width - REF_W * s) / 2f
            val dy = section.top + (section.height - REF_H * s) / 2f
            canvas.withTranslation(dx, dy) {
                canvas.scale(s, s)
                when (widget) {
                    Widget.MUSIC -> drawMusic(canvas, media, granted)
                    Widget.LYRICS -> drawLyrics(canvas, currentLyricLine(elapsed))
                    Widget.NAV -> drawNav(canvas, nav)
                    Widget.TRIP -> drawTrip(canvas, elapsed, nav)
                    Widget.MUSIC_CONTROL -> drawMusicControl(canvas, media?.playing == true)
                    Widget.CLOCK -> drawClock(canvas)
                    Widget.CALENDAR -> drawCalendar(canvas)
                    Widget.WEATHER -> drawWeather(canvas, weather)
                    Widget.PARKING -> drawParking(canvas, parkingSince)
                    Widget.QUICK_APPS -> drawQuickApps(canvas)
                }
            }
        }
        return bitmap
    }

    /** 绘制磨砂壁纸、遮罩、细描边和阴影 */
    private fun drawSectionCard(
        canvas: Canvas,
        section: PixelArea,
        bounds: PixelArea,
    ) {
        val rect = RectF(section.left.toFloat(), section.top.toFloat(), section.right.toFloat(), section.bottom.toFloat())
        // 柔和投影 + 可读底色（同一次绘制的 shadowLayer 输出投影）
        val base =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(58, 12, 9, 28)
                setShadowLayer(14f, 0f, 6f, Color.argb(70, 0, 0, 0))
            }
        canvas.drawRoundRect(rect, 22f, 22f, base)
        val clip =
            Path().apply {
                addRoundRect(rect, 22f, 22f, Path.Direction.CW)
            }
        canvas.withClip(clip) {
            // 磨砂壁纸底（与面板同配方，alpha 让底色参与混合保证白字可读）
            val backdrop =
                runCatching {
                    WallpaperStore.windowBackdrop(
                        context,
                        section.width,
                        section.height,
                        geometry.canvasWidth,
                        geometry.canvasHeight,
                        bounds.left + section.left,
                        bounds.top + section.top,
                        geometry,
                        null,
                        WallpaperStore.isDarkWallpaper(context) == true,
                    )
                }.getOrNull()
            if (backdrop != null) {
                canvas.drawBitmap(backdrop, null, rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { alpha = 200 })
            }
            // 遮罩强度沿用「车机外观」背景透明度预设
            val scrim =
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    UiAppearance.color(context, R.color.m3_surface),
                    CarAppearancePrefs.opacity(context) * 255 / 100,
                )
            canvas.drawRect(rect, Paint().apply { color = scrim })
            // 顶部玻璃高光
            val sheen =
                android.graphics.LinearGradient(
                    0f,
                    rect.top,
                    0f,
                    rect.top + rect.height() * 0.45f,
                    Color.argb(26, 255, 255, 255),
                    Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sheen })
        }
        // 淡紫发丝描边（与面板轮廓同色系，上浅下深）
        val hairline =
            android.graphics.LinearGradient(
                0f,
                rect.top,
                0f,
                rect.bottom,
                Color.rgb(171, 133, 250),
                Color.rgb(245, 237, 255),
                android.graphics.Shader.TileMode.CLAMP,
            )
        canvas.drawRoundRect(
            rect,
            22f,
            22f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                shader = hairline
            },
        )
    }

    /** 歌名板块：封面 + 歌名 + 歌手 */
    private fun drawMusic(
        canvas: Canvas,
        media: MediaSessionsState.NowPlaying?,
        granted: Boolean,
    ) {
        drawArt(canvas, media, 16f, 36f, 56f, 12f)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 17f
            }
        val secondary =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 13f
            }
        val label = titleText(media, granted)
        val lines =
            StaticLayout.Builder
                .obtain(label, 0, label.length, title, 146)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        canvas.withTranslation(84f, 32f) {
            lines.draw(canvas)
        }
        media
            ?.artist
            ?.takeIf { it.isNotBlank() }
            ?.let {
                canvas.drawText(TextUtils.ellipsize(it, secondary, 146f, TextUtils.TruncateAt.END).toString(), 84f, 104f, secondary)
            }
    }

    /** 歌词板块：实时歌词句 */
    private fun drawLyrics(
        canvas: Canvas,
        lyricLine: String?,
    ) {
        val label =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(211, 193, 252)
                textSize = 12f
            }
        canvas.drawText("歌词", 18f, 26f, label)
        val text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 16f
            }
        val body = lyricLine?.takeIf { it.isNotBlank() } ?: "暂无歌词"
        val lines =
            StaticLayout.Builder
                .obtain(body, 0, body.length, text, 206)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        canvas.withTranslation(18f, 48f) {
            lines.draw(canvas)
        }
    }

    /** 导航板块：转向图标 + 距离动作 + 道路/剩余 */
    private fun drawNav(
        canvas: Canvas,
        step: NavGuidance.Step?,
    ) {
        if (step == null) {
            val hint =
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(190, 255, 255, 255)
                    textSize = 15f
                }
            canvas.drawText("暂无导航", 18f, 70f, hint)
            return
        }
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
        canvas.drawCircle(42f, 64f, 24f, circle)
        NavGlyph.draw(canvas, 42f, 64f, 13f, step.direction)
        val title =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 15f
            }
        val secondary =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 12f
            }
        canvas.drawText(TextUtils.ellipsize(NavGlyph.label(step), title, 148f, TextUtils.TruncateAt.END).toString(), 76f, 52f, title)
        step.road
            ?.takeIf { it.isNotBlank() }
            ?.let {
                canvas.drawText(TextUtils.ellipsize(it, secondary, 148f, TextUtils.TruncateAt.END).toString(), 76f, 74f, secondary)
            }
        step.remaining
            ?.takeIf { it.isNotBlank() }
            ?.let {
                canvas.drawText(TextUtils.ellipsize(it, secondary, 148f, TextUtils.TruncateAt.END).toString(), 76f, 96f, secondary)
            }
    }

    /** 行程板块：本次连接时长 + 导航剩余 */
    private fun drawTrip(
        canvas: Canvas,
        elapsed: Long,
        step: NavGuidance.Step?,
    ) {
        val label =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(211, 193, 252)
                textSize = 12f
            }
        canvas.drawText("本次连接", 18f, 28f, label)
        val big =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                textSize = 30f
            }
        canvas.drawText(formatDuration(elapsed - sessionStartedAt), 18f, 68f, big)
        val secondary =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 13f
            }
        val remaining = step?.remaining?.takeIf { it.isNotBlank() } ?: "暂无导航行程"
        canvas.drawText(TextUtils.ellipsize(remaining, secondary, 206f, TextUtils.TruncateAt.END).toString(), 18f, 100f, secondary)
    }

    /** 音乐控制板块：上一 / 播放暂停 / 下一 */
    private fun drawMusicControl(
        canvas: Canvas,
        playing: Boolean,
    ) {
        val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        BUTTON_X.forEach { cx -> canvas.drawCircle(cx, 64f, 22f, circle) }
        mediaGlyph.skip(canvas, glyph, BUTTON_X[0], 64f, 9f, forward = false)
        mediaGlyph.playPause(canvas, glyph, BUTTON_X[1], 64f, 9f, playing)
        mediaGlyph.skip(canvas, glyph, BUTTON_X[2], 64f, 9f, forward = true)
        val hint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 255, 255, 255)
                textSize = 11f
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText("音乐控制", REF_W / 2f, 112f, hint)
    }

    /** 时间板块：大时钟 + 日期 */
    private fun drawClock(canvas: Canvas) {
        val now = Date()
        val time =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                textSize = 46f
                textAlign = Paint.Align.CENTER
            }
        val date =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 14f
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText(SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE).format(now), REF_W / 2f, 60f, time)
        canvas.drawText(SimpleDateFormat("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE).format(now), REF_W / 2f, 92f, date)
    }

    /** 日历板块：当月迷你月历（周一起始），今天高亮 */
    private fun drawCalendar(canvas: Canvas) {
        val now = Calendar.getInstance()
        val header =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 13f
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText(SimpleDateFormat("yyyy年M月", Locale.SIMPLIFIED_CHINESE).format(now.time), REF_W / 2f, 22f, header)
        val cell =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textSize = 10f
                textAlign = Paint.Align.CENTER
            }
        val colWidth = REF_W / 7f
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { index, day ->
            canvas.drawText(day, colWidth * (index + 0.5f), 42f, cell)
        }
        val first =
            (now.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
            }
        // 周一为首列：Calendar.DAY_OF_WEEK（周日=1）换算成周一起点
        val lead = (first.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        val today = now.get(Calendar.DAY_OF_MONTH)
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(211, 193, 252) }
        for (day in 1..daysInMonth) {
            val cellIndex = lead + day - 1
            val cx = colWidth * (cellIndex % 7 + 0.5f)
            val cy = 58f + (cellIndex / 7) * 13f
            if (day == today) {
                canvas.drawCircle(cx, cy - 3f, 9f, highlight)
                cell.color = Color.BLACK
                canvas.drawText(day.toString(), cx, cy, cell)
                cell.color = Color.argb(190, 255, 255, 255)
            } else {
                canvas.drawText(day.toString(), cx, cy, cell)
            }
        }
    }

    /** 天气板块：城市 + 气温 + 天气描述 */
    private fun drawWeather(
        canvas: Canvas,
        weather: WeatherState.Weather?,
    ) {
        val city =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(211, 193, 252)
                textSize = 12f
            }
        val temp =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                textSize = 38f
            }
        val text =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(210, 255, 255, 255)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 15f
            }
        canvas.drawText(weather?.city ?: WeatherState.label(CarAppearancePrefs.weatherCity(context)), 18f, 28f, city)
        if (weather != null) {
            canvas.drawText("${weather.temperatureC.roundToInt()}°", 18f, 80f, temp)
            canvas.drawText(weather.text, 110f, 80f, text)
        } else {
            canvas.drawText("获取中…", 18f, 76f, text)
        }
    }

    /** 停车计时板块：计时读数 + 起停提示 */
    private fun drawParking(
        canvas: Canvas,
        parkingSince: Long,
    ) {
        val label =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(211, 193, 252)
                textSize = 12f
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText("停车计时", REF_W / 2f, 28f, label)
        val big =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                textSize = 30f
                textAlign = Paint.Align.CENTER
            }
        val hint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 255, 255, 255)
                textSize = 11f
                textAlign = Paint.Align.CENTER
            }
        if (parkingSince > 0) {
            canvas.drawText(formatDuration(System.currentTimeMillis() - parkingSince), REF_W / 2f, 74f, big)
            canvas.drawText("点按结束计时", REF_W / 2f, 104f, hint)
        } else {
            canvas.drawText("未开始", REF_W / 2f, 74f, big)
            canvas.drawText("点按开始计时", REF_W / 2f, 104f, hint)
        }
    }

    /** 快捷应用板块：地图 / 音乐 / 桌面 */
    private fun drawQuickApps(canvas: Canvas) {
        val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(36, 255, 255, 255) }
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val label =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textSize = 11f
                textAlign = Paint.Align.CENTER
            }
        val names = listOf("地图", "音乐", "桌面")
        BUTTON_X.forEachIndexed { index, cx ->
            canvas.drawRoundRect(RectF(cx - 22f, 30f, cx + 22f, 74f), 12f, 12f, tile)
            val icon =
                when (index) {
                    0 -> R.drawable.ic_menu_map
                    1 -> R.drawable.ic_music
                    else -> R.drawable.ic_menu_grid
                }
            mediaGlyph.draw(canvas, icon, cx, 52f, 14f, glyph.color)
            canvas.drawText(names[index], cx, 96f, label)
        }
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

    private fun titleText(
        media: MediaSessionsState.NowPlaying?,
        granted: Boolean,
    ): String =
        media?.title?.takeIf { it.isNotBlank() }
            ?: when {
                !granted -> "播放信息未连接"
                media?.playing == true -> "正在播放"
                else -> "暂无播放信息"
            }

    /** H:MM:SS 时长 */
    private fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        return "${total / 3600}:${((total % 3600) / 60).toString().padStart(2, '0')}:${(total % 60).toString().padStart(2, '0')}"
    }
}
