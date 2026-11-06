package io.github.shaonialife.buicklinkplus.render

import io.github.shaonialife.buicklinkplus.input.SafeGestureGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 验证悬浮外观与原地图、侧栏逻辑坐标一致，透明区域不产生穿透手势
 */
class FloatingPanelLayoutTest {
    /**
     * 关闭轮廓后逐像素铺满安全区，拼接和四角都能反算到原坐标
     */
    @Test
    fun hiddenOutlineHasNoInsetGapRoundingOrTouchHoles() {
        for (bottom in listOf(false, true)) {
            val width = if (bottom) 64 else 128
            val height = if (bottom) 128 else 64
            val layout = FloatingPanelLayout.create(width, height, 16, bottom, false)
            assertEquals(1f, layout.scale, 0f)
            assertEquals(0f, layout.gap, 0f)
            assertEquals(0f, layout.dock.radius, 0f)
            assertEquals(0f, layout.app.radius, 0f)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    assertEquals(x to y, layout.logicalPoint(x.toFloat(), y.toFloat()))
                    assertTrue(layout.dock.contains(x.toFloat(), y.toFloat()) xor layout.app.contains(x.toFloat(), y.toFloat()))
                }
            }
            assertNull(layout.logicalPoint(-1f, 0f))
            assertNull(layout.logicalPoint(width.toFloat(), 0f))
            assertNull(layout.logicalPoint(0f, height.toFloat()))
        }
    }

    /**
     * 别克安全区下两块面板独立留缝且保持原始宽高比
     */
    @Test
    fun landscapePanelsHaveGapAndUniformScale() {
        val layout = FloatingPanelLayout.create(1408, 476, 111, false)
        assertTrue(layout.dock.left > 0f && layout.app.right < 1408f)
        assertTrue(layout.app.top > 0f && layout.app.bottom < 476f)
        assertEquals(layout.gap, layout.app.left - layout.dock.right, 0.001f)
        assertEquals(layout.dock.top, layout.app.top, 0.001f)
        assertEquals(layout.dock.bottom, layout.app.bottom, 0.001f)
        assertEquals(layout.scale, layout.dock.width / 111, 0.001f)
        assertEquals(layout.scale, layout.app.width / 1297, 0.001f)
        assertEquals(layout.scale, layout.app.height / 476, 0.001f)
    }

    /**
     * 竖屏底栏与内容上下分离，应用仍使用原先的顶部原点
     */
    @Test
    fun portraitPanelsKeepBottomDockIndependent() {
        val layout = FloatingPanelLayout.create(648, 1400, 126, true)
        assertEquals(layout.gap, layout.dock.top - layout.app.bottom, 0.001f)
        assertEquals(layout.dock.left, layout.app.left, 0.001f)
        assertEquals(layout.dock.right, layout.app.right, 0.001f)
        assertTrue(layout.app.top > 0f && layout.dock.bottom < 1400f)
        assertEquals(0f, layout.app.logicalTop)
        assertEquals(1274f, layout.dock.logicalTop)
        assertEquals(layout.scale, layout.dock.height / 126, 0.001f)
    }

    /**
     * 四个透明圆角都拒绝点击，边缘中央仍保留有效点击区域
     */
    @Test
    fun allFourCornersAreTransparentToTouch() {
        val layout = FloatingPanelLayout.create(1160, 540, 126, false)
        for (panel in listOf(layout.dock, layout.app)) {
            assertFalse(panel.contains(panel.left, panel.top))
            assertFalse(panel.contains(panel.right - 0.1f, panel.top))
            assertFalse(panel.contains(panel.left, panel.bottom - 0.1f))
            assertFalse(panel.contains(panel.right - 0.1f, panel.bottom - 0.1f))
            assertTrue(panel.contains(panel.left + panel.width / 2, panel.top + 1f))
            assertTrue(panel.contains(panel.left + 1f, panel.top + panel.height / 2))
        }
    }

    /**
     * 地图、桌面及侧栏坐标经绘制和触摸反算后不因留缝偏移
     */
    @Test
    fun drawingAndTouchRoundTripForBothOrientations() {
        for (bottom in listOf(false, true)) {
            val layout = FloatingPanelLayout.create(if (bottom) 648 else 1408, if (bottom) 1400 else 476, 111, bottom)
            for (panel in listOf(layout.dock, layout.app)) {
                for ((u, v) in listOf(0.5f to 0.5f, 0.25f to 0.2f, 0.75f to 0.8f)) {
                    val x = (panel.logicalLeft + panel.width / layout.scale * u).toInt()
                    val y = (panel.logicalTop + panel.height / layout.scale * v).toInt()
                    assertEquals(x to y, layout.logicalPoint(layout.displayX(panel, x.toFloat()), layout.displayY(panel, y.toFloat())))
                }
            }
        }
    }

    /**
     * 别克非零安全区叠加流分辨率缩放后，触摸仍回到同一面板逻辑位置
     */
    @Test
    fun safeAreaAndStreamScaleKeepTouchAligned() {
        for (streamScale in listOf(1f, 1.5f, 2f)) {
            val geometry =
                DisplayGeometry.create(
                    1972,
                    520,
                    (1972 * streamScale).toInt(),
                    (520 * streamScale).toInt(),
                    PixelArea(278, 44, 1686, 520),
                )
            val layout = FloatingPanelLayout.create(geometry.content.width, geometry.content.height, (111 * streamScale).toInt(), false)
            for (panel in listOf(layout.dock, layout.app)) {
                val logicalX = (panel.logicalLeft + panel.width / layout.scale / 2).roundToInt()
                val logicalY = (panel.logicalTop + panel.height / layout.scale / 2).roundToInt()
                val viewX = ((geometry.content.left + layout.displayX(panel, logicalX.toFloat())) / streamScale).roundToInt()
                val viewY = ((geometry.content.top + layout.displayY(panel, logicalY.toFloat())) / streamScale).roundToInt()
                val sx = geometry.streamX(viewX)
                val sy = geometry.streamY(viewY)
                assertTrue(geometry.content.contains(sx, sy))
                val point =
                    requireNotNull(layout.logicalPoint((sx - geometry.content.left).toFloat(), (sy - geometry.content.top).toFloat()))
                assertTrue(abs(logicalX - point.first) <= 1)
                assertTrue(abs(logicalY - point.second) <= 1)
            }
        }
    }

    /** 超采样继续由输入路由处理，不混入外观缩放 */
    @Test
    fun applicationPointKeepsOriginalNativeCoordinates() {
        val layout = FloatingPanelLayout.create(1408, 476, 111, false)
        val point = requireNotNull(layout.logicalPoint(layout.displayX(layout.app, 711f), layout.displayY(layout.app, 200f)))
        assertEquals(600, point.first - 111)
        assertEquals(200, point.second)
        assertEquals(900, ((point.first - 111) * 1.5f).toInt())
    }

    /** 从面板间隙拖入不触发应用点击 */
    @Test
    fun gapGestureRemainsBlockedUntilRelease() {
        val layout = FloatingPanelLayout.create(1408, 476, 111, false)
        val gapX = (layout.dock.right + layout.app.left) / 2
        val y = layout.app.top + layout.app.height / 2
        assertNull(layout.logicalPoint(gapX, y))
        val gate = SafeGestureGate()
        assertFalse(gate.accept(true, layout.logicalPoint(gapX, y) != null))
        assertFalse(gate.accept(true, layout.logicalPoint(layout.app.left + 100f, y) != null))
        assertFalse(gate.accept(false, false))
        assertTrue(gate.accept(true, layout.logicalPoint(layout.app.left + 100f, y) != null))
    }

    /**
     * 连续缩放档位与横竖画布都维持正尺寸、合法圆角及边界内布局
     */
    @Test
    fun scaleAndPanelBoundsStayValidAcrossSizes() {
        for ((width, height) in listOf(320 to 180, 1160 to 540, 2320 to 1080, 648 to 1400, 64 to 128)) {
            val bottom = height > width
            val dock = minOf(width, height) / 5
            val layout = FloatingPanelLayout.create(width, height, dock, bottom)
            assertTrue(layout.scale > 0f && layout.scale < 1f)
            for (panel in listOf(layout.dock, layout.app)) {
                assertTrue(panel.left >= 0 && panel.top >= 0 && panel.right <= width && panel.bottom <= height)
                assertTrue(panel.width > 0 && panel.height > 0)
                assertTrue(panel.radius <= minOf(panel.width, panel.height) / 2f + 0.001f)
            }
        }
    }
}
