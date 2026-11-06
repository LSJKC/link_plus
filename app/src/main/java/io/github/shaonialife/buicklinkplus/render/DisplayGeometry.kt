package io.github.shaonialife.buicklinkplus.render

import kotlin.math.ceil
import kotlin.math.floor

/** 顶部原点、右下开区间的有效像素矩形 */
data class PixelArea(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(
        x: Int,
        y: Int,
    ): Boolean = x >= left && x < right && y >= top && y < bottom

    fun intersect(other: PixelArea): PixelArea? =
        PixelArea(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
            .takeIf { it.width > 0 && it.height > 0 }
}

/** 同一会话的布局与触控共用此对象，内容坐标相对安全区左上角 */
data class DisplayGeometry(
    val viewWidth: Int,
    val viewHeight: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val content: PixelArea,
    val sourceView: PixelArea = PixelArea(0, 0, viewWidth, viewHeight),
    val outsideUiAllowed: Boolean = false,
) {
    fun streamX(x: Int): Int = floor(x.toDouble() * canvasWidth / viewWidth).toInt()

    fun streamY(y: Int): Int = floor(y.toDouble() * canvasHeight / viewHeight).toInt()

    companion object {
        /** 缩放后的边界只向内取整，四边微调使用原始投屏像素且只能收缩 */
        fun create(
            viewWidth: Int,
            viewHeight: Int,
            width: Int,
            height: Int,
            safe: PixelArea,
            margins: List<Int> = listOf(0, 0, 0, 0),
            viewArea: PixelArea = PixelArea(0, 0, viewWidth, viewHeight),
            outsideUiAllowed: Boolean = false,
        ): DisplayGeometry {
            require(viewWidth > 0 && viewHeight > 0 && width > 0 && height > 0 && margins.size == 4)
            val view = viewArea.intersect(PixelArea(0, 0, viewWidth, viewHeight)) ?: PixelArea(0, 0, viewWidth, viewHeight)
            val clipped = safe.intersect(view) ?: view
            val inset =
                PixelArea(
                    clipped.left + margins[0].coerceIn(0, clipped.width / 4),
                    clipped.top + margins[1].coerceIn(0, clipped.height / 4),
                    clipped.right - margins[2].coerceIn(0, clipped.width / 4),
                    clipped.bottom - margins[3].coerceIn(0, clipped.height / 4),
                )
            val x = width.toDouble() / viewWidth
            val y = height.toDouble() / viewHeight
            val content =
                PixelArea(
                    ceil(inset.left * x).toInt(),
                    ceil(inset.top * y).toInt(),
                    floor(inset.right * x).toInt(),
                    floor(inset.bottom * y).toInt(),
                )
            require(content.width > 0 && content.height > 0) { "当前安全区在缩放后没有有效像素，请提高分辨率" }
            return DisplayGeometry(viewWidth, viewHeight, width, height, content, view, outsideUiAllowed)
        }
    }
}

/** 仅从已选主屏读取视图，避免误用仪表副屏的安全区 */
object DisplayAreas {
    /** 按协议类型选择主屏，不依赖通告顺序 */
    fun mainDisplay(displays: List<*>?): Map<*, *>? {
        val candidates = displays?.filterIsInstance<Map<*, *>>().orEmpty()
        return candidates.firstOrNull { (it["type"] as? Number)?.toInt() == 110 } ?: candidates.firstOrNull()
    }

    private fun area(
        raw: Map<*, *>?,
        width: Int,
        height: Int,
    ): PixelArea? {
        raw ?: return null
        val w = (raw["widthPixels"] as? Number)?.toInt() ?: return null
        val h = (raw["heightPixels"] as? Number)?.toInt() ?: return null
        val x = (raw["originXPixels"] as? Number)?.toInt() ?: 0
        val y = (raw["originYPixels"] as? Number)?.toInt() ?: 0
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x.toLong() + w > width || y.toLong() + h > height) return null
        return PixelArea(x, y, x + w, y + h)
    }

    private fun currentView(display: Map<*, *>?): Map<*, *>? {
        val views = display?.get("viewAreas") as? List<*>
        val initial = display?.get("initialViewArea")
        return (initial as? Map<*, *>) ?: (views?.getOrNull((initial as? Number)?.toInt() ?: 0) as? Map<*, *>)
    }

    fun viewArea(
        display: Map<*, *>?,
        width: Int,
        height: Int,
    ): PixelArea = area(currentView(display), width, height) ?: PixelArea(0, 0, width, height)

    /** 只使用接收端明确允许的主视图留白区域 */
    fun allowsOutsideUi(display: Map<*, *>?): Boolean {
        val safe = (currentView(display)?.get("safeArea") ?: display?.get("safeArea")) as? Map<*, *>
        return safe?.get("drawUIOutsideSafeArea") == true
    }

    fun resolve(
        display: Map<*, *>?,
        width: Int,
        height: Int,
    ): PixelArea {
        val view = currentView(display)
        val validView = viewArea(display, width, height)
        val safe =
            area(view?.get("safeArea") as? Map<*, *>, width, height)
                ?: area(display?.get("safeArea") as? Map<*, *>, width, height)
        if (safe != null) return safe.intersect(validView) ?: validView
        // 协议记录中的主屏尺寸，区别于物理整屏的 6K 规格
        if (width == 1972 && height == 520 && (display?.get("type") as? Number)?.toInt() == 110) {
            return PixelArea(278, 44, 1686, 520).intersect(validView) ?: validView
        }
        return validView
    }
}
