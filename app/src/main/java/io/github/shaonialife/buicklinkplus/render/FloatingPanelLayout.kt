package io.github.shaonialife.buicklinkplus.render

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 悬浮侧栏与内容区共享的显示几何，原虚拟屏及地图逻辑尺寸保持不变
 */
class FloatingPanelLayout private constructor(
    val width: Int,
    val height: Int,
    val scale: Float,
    val gap: Float,
    val dock: Panel,
    val app: Panel,
) {
    /**
     * 面板边界使用顶部原点，逻辑原点仍沿用原先无间隙的布局
     */
    data class Panel(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val logicalLeft: Float,
        val logicalTop: Float,
        val radius: Float,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        /**
         * 透明圆角与面板间隙不属于应用的点击区域
         */
        fun contains(
            x: Float,
            y: Float,
        ): Boolean {
            if (x < left || x >= right || y < top || y >= bottom) return false
            val cx = x.coerceIn(left + radius, right - radius)
            val cy = y.coerceIn(top + radius, bottom - radius)
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= radius * radius
        }
    }

    /**
     * 将原逻辑横坐标映射到实际面板，不在每帧重新计算布局
     */
    fun displayX(
        panel: Panel,
        logicalX: Float,
    ): Float = panel.left + (logicalX - panel.logicalLeft) * scale

    /**
     * 将原逻辑纵坐标映射到实际面板，输入与结果均为顶部原点
     */
    fun displayY(
        panel: Panel,
        logicalY: Float,
    ): Float = panel.top + (logicalY - panel.logicalTop) * scale

    /** 用绘制几何反算触摸，再交给地图和超采样路由 */
    fun logicalPoint(
        x: Float,
        y: Float,
    ): Pair<Int, Int>? {
        val panel =
            when {
                dock.contains(x, y) -> dock
                app.contains(x, y) -> app
                else -> return null
            }
        val localX = ((x - panel.left) / scale).roundToInt().coerceIn(0, (panel.width / scale).roundToInt() - 1)
        val localY = ((y - panel.top) / scale).roundToInt().coerceIn(0, (panel.height / scale).roundToInt() - 1)
        return (panel.logicalLeft.toInt() + localX) to (panel.logicalTop.toInt() + localY)
    }

    companion object {
        /**
         * 显示轮廓时等比缩放并留缝，隐藏时按原坐标铺满，取消所有外围圆角
         */
        fun create(
            width: Int,
            height: Int,
            dockSize: Int,
            dockAtBottom: Boolean,
            outlineVisible: Boolean = true,
        ): FloatingPanelLayout {
            require(width > 1 && height > 1 && dockSize in 1 until if (dockAtBottom) height else width)
            val short = min(width, height).toFloat()
            val inset = if (outlineVisible) (short * 0.018f).coerceAtMost(24f) else 0f
            val gap = if (outlineVisible) (short * 0.022f).coerceAtMost(28f) else 0f
            val scale =
                min(
                    (width - 2 * inset - if (dockAtBottom) 0f else gap) / width,
                    (height - 2 * inset - if (dockAtBottom) gap else 0f) / height,
                )
            val left = (width - width * scale - if (dockAtBottom) 0f else gap) / 2f
            val top = (height - height * scale - if (dockAtBottom) gap else 0f) / 2f
            val radius = if (outlineVisible) (short * 0.044f).coerceAtMost(38f) else 0f

            /**
             * 圆角不能超过面板短边的一半，小尺寸侧栏也保留合法裁剪范围
             */
            fun panel(
                x: Float,
                y: Float,
                w: Int,
                h: Int,
                logicalX: Float,
                logicalY: Float,
            ): Panel = Panel(x, y, x + w * scale, y + h * scale, logicalX, logicalY, min(radius, min(w, h) * scale / 2f))

            val dock: Panel
            val app: Panel
            if (dockAtBottom) {
                val appHeight = height - dockSize
                app = panel(left, top, width, appHeight, 0f, 0f)
                dock = panel(left, top + appHeight * scale + gap, width, dockSize, 0f, appHeight.toFloat())
            } else {
                dock = panel(left, top, dockSize, height, 0f, 0f)
                app = panel(left + dockSize * scale + gap, top, width - dockSize, height, dockSize.toFloat(), 0f)
            }
            return FloatingPanelLayout(width, height, scale, gap, dock, app)
        }
    }
}
