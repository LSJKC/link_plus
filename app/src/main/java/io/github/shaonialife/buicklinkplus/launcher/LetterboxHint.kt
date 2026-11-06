package io.github.shaonialife.buicklinkplus.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation

/**
 * 检测方向锁导致的 letterbox，并在黑边绘制重启提示
 * bounds 为应用窗口，mMaxBounds 为虚拟屏边界，用两者差值计算黑边
 */

/** 可用于 JVM 测试的整数矩形 */
internal data class PixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** `dumpsys activity activities` 里某块屏顶层 Activity 的相关事实。 */
internal data class LetterboxActivity(
    val packageName: String,
    /** `包/类` 形式，可直接 `am start -n`。 */
    val component: String,
    val displayId: Int,
    val letterboxed: Boolean,
    val letterboxReason: String?,
    /** 应用窗口矩形（`bounds=`）。 */
    val inner: PixelBounds,
    /** 该屏可用区域矩形（`mMaxBounds=`；缺失时用调用方给的屏尺寸）。 */
    val full: PixelBounds,
)

private val displayRegex = Regex("""^Display #(\d+) \(activities""")
private val topActivityRegex =
    Regex("""topResumedActivity=ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val historyRegex =
    Regex("""^\s*\* Hist\s+#\d+: ActivityRecord\{(\w+) u\d+ (\S+)/(\S+) t\d+\}""")
private val boundsRegex = Regex("""^\s+bounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")
private val maxBoundsRegex = Regex("""mMaxBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)""")

/**
 * 解析目标屏顶层 Activity 的 letterbox
 *
 * @param displayId 目标屏 ID
 * @param dump 原始 dumpsys 输出
 * @param fallbackW 屏宽，mMaxBounds 缺失时使用
 * @param fallbackH 屏高，mMaxBounds 缺失时使用
 */
internal fun parseTopLetterboxActivity(
    displayId: Int,
    dump: String,
    fallbackW: Int,
    fallbackH: Int,
): LetterboxActivity? {
    if (dump.isBlank()) return null
    val lines = dump.lineSequence().toList()
    var sectionStart = -1
    var sectionEnd = lines.size
    for (index in lines.indices) {
        val match = displayRegex.find(lines[index]) ?: continue
        if (sectionStart < 0) {
            if (match.groupValues[1].toInt() == displayId) sectionStart = index
        } else {
            sectionEnd = index
            break
        }
    }
    if (sectionStart < 0) return null
    // 只取目标 display 段，避免把别的屏的 Hist 块带进来
    val section = lines.subList(sectionStart + 1, sectionEnd)
    // 顶层任务：段内第一个 topResumedActivity（任务按从顶到底输出）
    var topHash: String? = null
    var topPkg: String? = null
    var topCls: String? = null
    for (line in section) {
        val match = topActivityRegex.find(line) ?: continue
        topHash = match.groupValues[1]
        topPkg = match.groupValues[2]
        topCls = match.groupValues[3]
        break
    }
    // 定位顶层 ActivityRecord 的 Hist 块：优先 hash 完全匹配，
    // 兼容 ROM 省略 topResumedActivity 时取第一块 Hist
    var block = -1
    var header: MatchResult? = null
    for (index in section.indices) {
        val match = historyRegex.find(section[index]) ?: continue
        if (topHash == null || match.groupValues[1] == topHash) {
            block = index
            header = match
            break
        }
    }
    if (block < 0 && topHash == null) {
        for (index in section.indices) {
            val match = historyRegex.find(section[index]) ?: continue
            block = index
            header = match
            break
        }
    }
    if (block < 0) return null
    val pkg = header!!.groupValues[2].ifBlank { topPkg ?: return null }
    val cls = header.groupValues[3].ifBlank { topCls ?: return null }
    if (pkg.isBlank() || cls.isBlank()) return null
    var letterboxed = false
    var reason: String? = null
    var inner: PixelBounds? = null
    var full: PixelBounds? = null
    for (index in block + 1 until section.size) {
        val line = section[index]
        if (historyRegex.containsMatchIn(line) || line.trimStart().startsWith("* Task")) break
        if (line.trimStart().startsWith("Resumed activities")) break
        if (line.contains("isLetterboxRunning=true")) letterboxed = true
        if (reason == null) {
            Regex("""letterboxReason=(\w+)""").find(line)?.let { reason = it.groupValues[1] }
        }
        if (inner == null) {
            boundsRegex.find(line)?.let { match ->
                inner =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
        if (full == null) {
            maxBoundsRegex.find(line)?.let { match ->
                full =
                    PixelBounds(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt(),
                        match.groupValues[4].toInt(),
                    )
            }
        }
    }
    val outer = full ?: PixelBounds(0, 0, fallbackW, fallbackH)
    val window = inner ?: outer
    if (outer.width <= 0 || outer.height <= 0) return null
    return LetterboxActivity(
        packageName = pkg,
        component = "$pkg/$cls",
        displayId = displayId,
        letterboxed = letterboxed,
        letterboxReason = reason,
        inner = window,
        full = outer,
    )
}

/** 应用在横屏名单中、因方向锁进入 letterbox，且用户本次未关闭提示时才显示 */
internal fun shouldShowLetterboxHint(
    activity: LetterboxActivity,
    forceLandscapePackages: Set<String>,
    dismissedPackages: Set<String>,
): Boolean =
    activity.letterboxed &&
        activity.letterboxReason == "FIXED_ORIENTATION" &&
        activity.packageName in forceLandscapePackages &&
        activity.packageName !in dismissedPackages

internal enum class LetterboxSide { LEFT, RIGHT }

/**
 * 提示卡优先放左侧，放不下时尝试较宽的右侧；两侧都不够返回 null
 * [neededPx] 为文字实测宽度加边距
 */
internal fun chooseLetterboxSide(
    leftPx: Float,
    rightPx: Float,
    neededPx: Float,
): LetterboxSide? =
    when {
        leftPx >= neededPx && leftPx >= rightPx -> LetterboxSide.LEFT
        rightPx >= neededPx -> LetterboxSide.RIGHT
        leftPx >= neededPx -> LetterboxSide.LEFT
        else -> null
    }

internal enum class LetterboxCardState { NORMAL, BUSY, FAILED }

/** 命中提示卡后独占整段手势；卡内抬起算点击，移出后抬起取消，不向应用注入 */
internal enum class CardGesture { NOT_HANDLED, CONSUME, TAP }

internal fun resolveCardGesture(
    cardVisible: Boolean,
    holding: Boolean,
    down: Boolean,
    hit: Boolean,
    appGestureActive: Boolean = false,
): CardGesture =
    when {
        // 卡片已收起：无论之前是否按住，都交回普通路由
        !cardVisible -> if (holding) CardGesture.CONSUME else CardGesture.NOT_HANDLED
        // 按住中：按下期间的 move 一律吞掉；抬起时按是否仍在卡片内决定点按
        holding && down -> CardGesture.CONSUME
        holding -> if (hit) CardGesture.TAP else CardGesture.CONSUME
        // 未按住：只有「按下瞬间就在卡片上」才接管。应用手势进行中拖进卡片区域
        // 不接管——否则应用收不到抬起，会停在按下态（卡死）。
        down && hit && !appGestureActive -> CardGesture.CONSUME
        else -> CardGesture.NOT_HANDLED
    }

/** 按画布密度绘制提示卡，最小宽度由文字和内边距决定 */
internal object LetterboxCardPainter {
    /**
     * 离屏绘图显式传入所在车机的语义色，避免误用手机主题
     */
    data class Colors(
        val background: Int,
        val border: Int,
        val accent: Int,
        val title: Int,
        val body: Int,
        val buttonText: Int,
        val disabledFill: Int,
        val disabledText: Int,
    )

    private const val MAX_WIDTH_DP = 200f
    private const val MARGIN_DP = 6f
    private const val PAD_H_DP = 8f
    private const val PAD_V_DP = 10f
    private const val GAP_DP = 7f

    /** 按钮高度随字号调整 */
    private const val BUTTON_H_DP = 28f
    private const val RADIUS_DP = 24f
    private const val TITLE_SP = 11.5f
    private const val BODY_SP = 9.5f

    /** 按钮字号与正文一致 */
    private const val BUTTON_SP = 9.5f
    private const val BUTTON_PAD_H_DP = 8f

    fun marginPx(density: Float): Float = MARGIN_DP * density

    /** 限制提示卡最大宽度 */
    fun maxWidthPx(density: Float): Float = MAX_WIDTH_DP * density

    /** 卡片最小宽度：由按钮文案与标题实测宽度决定。 */
    fun minWidthPx(density: Float): Float {
        val button = textPaint(BUTTON_SP * density, Color.WHITE, bold = true)
        val buttonW = button.measureText("强制重启") + 2f * BUTTON_PAD_H_DP * density
        val title = textPaint(TITLE_SP * density, Color.WHITE, bold = true)
        // 标题至少容纳 5 个汉字（再窄会碎成单字换行）
        val titleW = title.measureText("横屏未生效")
        return 2f * PAD_H_DP * density + maxOf(buttonW, titleW)
    }

    /** 按 [widthPx] 生成提示卡，空间放不下文字或按钮时返回 null */
    fun render(
        density: Float,
        widthPx: Int,
        maxHeightPx: Int,
        state: LetterboxCardState,
        colors: Colors,
    ): Bitmap? {
        if (widthPx <= 0 || maxHeightPx <= 0) return null
        val width = widthPx.toFloat()
        val textWidth = width - 2f * PAD_H_DP * density
        if (textWidth <= 0f) return null
        if (width < minWidthPx(density)) return null
        val (titleText, bodyText, buttonText) =
            when (state) {
                LetterboxCardState.NORMAL -> Triple("横屏未生效", "仍是竖屏窗口", "强制重启")
                LetterboxCardState.BUSY -> Triple("正在重启", "将以横屏打开", "请稍候")
                LetterboxCardState.FAILED -> Triple("仍然竖屏", "可能不支持", "关闭")
            }
        val titlePaint = textPaint(TITLE_SP * density, colors.title, bold = true)
        val bodyPaint = textPaint(BODY_SP * density, colors.body, bold = false)
        val title = staticLayout(titleText, titlePaint, textWidth.toInt())
        val body = staticLayout(bodyText, bodyPaint, textWidth.toInt())
        val height =
            (
                2f * PAD_V_DP * density + title.height + GAP_DP * density +
                    body.height + GAP_DP * density + BUTTON_H_DP * density
            ).toInt()
        if (height > maxHeightPx) return null
        val bitmap = createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = RADIUS_DP * density
        val bounds = RectF(0f, 0f, widthPx.toFloat(), height.toFloat())
        canvas.drawRoundRect(bounds, radius, radius, fillPaint(colors.background))
        canvas.drawRoundRect(bounds, radius, radius, strokePaint(colors.border, density))
        var y = PAD_V_DP * density
        canvas.withTranslation(PAD_H_DP * density, y) {
            title.draw(canvas)
        }
        y += title.height + GAP_DP * density
        canvas.withTranslation(PAD_H_DP * density, y) {
            body.draw(canvas)
        }
        y += body.height + GAP_DP * density
        val buttonW = width - 2f * PAD_H_DP * density
        val buttonH = BUTTON_H_DP * density
        val buttonRect =
            RectF(PAD_H_DP * density, y, PAD_H_DP * density + buttonW, y + buttonH)
        val enabled = state == LetterboxCardState.NORMAL
        canvas.drawRoundRect(
            buttonRect,
            buttonH / 2f,
            buttonH / 2f,
            fillPaint(if (enabled) colors.accent else colors.disabledFill),
        )
        val buttonPaint =
            textPaint(
                BUTTON_SP * density,
                if (enabled) colors.buttonText else colors.disabledText,
                bold = true,
            ).apply {
                // 文字居中对齐，避免从按钮中点起笔后向右溢出
                textAlign = Paint.Align.CENTER
            }
        val metrics = buttonPaint.fontMetrics
        val textY =
            buttonRect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(buttonText, buttonRect.centerX(), textY, buttonPaint)
        return bitmap
    }

    private fun staticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
    ): StaticLayout =
        StaticLayout
            .Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()

    private fun textPaint(
        sizePx: Float,
        color: Int,
        bold: Boolean,
    ): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private fun fillPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

    private fun strokePaint(
        color: Int,
        density: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            this.color = color
        }
}
