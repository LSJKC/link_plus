package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [DisplayGeometry.containPane] 纯函数单测：安全区 contain 必须严格等比、居中、不越界。
 *
 * 这是 CarLife 画面不变形的核心保证：合成器按同一个 pane 把解码帧贴到画布，触摸映射也用同一 pane
 * 反算码流坐标；一旦这里算错（例如按 98311 申请尺寸而不是 SPS 真实尺寸），车机上就会看到拉伸/偏移。
 */
class DisplayGeometryContainTest {
    /** 超宽车机安全区（1972x520 是原版奥迪/宽屏常见画布）。 */
    private val ultraWide = PixelArea(0, 0, 1972, 520)

    /** 带左右安全区边距的内容层（如 1920x720 画布，content x=48..1880）。 */
    private val padded = PixelArea(48, 0, 1880, 720)

    @Test
    fun containsStreamIntoUltraWideContentWithSideBars() {
        // 1280x720 流放进 1972x520：以高为准，左右各留壁纸边，垂直铺满。
        val pane = DisplayGeometry.containPane(ultraWide, 1280, 720)
        assertEquals(0, pane.top)
        assertEquals(520, pane.height)
        assertEquals(520, pane.bottom)
        // 520 * 16/9 = 924.44 → 四舍五入 924
        assertTrue("pane 宽应约 924，实际 ${pane.width}", abs(pane.width - 924) <= 1)
        // 居中：左右留边差不超过 1px
        assertClose(ultraWide.width - pane.right, pane.left)
        assertClose(ultraWide.centerX(), pane.centerX())
        // 比例误差 < 0.1%（1px 取整误差量级）
        assertAspectClose(1280, 720, pane, tolerance = 0.002f)
    }

    @Test
    fun contains2128x992StreamIntoPaddedContentWithSideBars() {
        // 部分 CarLife 引擎实际编 2128x992（宽高比 ≈2.1451）而申请 1972x520：仍须按真实比例 contain。
        val pane = DisplayGeometry.containPane(padded, 2128, 992)
        assertEquals(padded.top, pane.top)
        assertEquals(padded.height, pane.height)
        // 720 * 2128/992 = 1544.52 → 1545
        assertTrue("pane 宽应约 1545，实际 ${pane.width}", abs(pane.width - 1545) <= 1)
        assertClose(padded.centerX(), pane.centerX())
        assertTrue(pane.left >= padded.left && pane.right <= padded.right)
        assertAspectClose(2128, 992, pane, tolerance = 0.002f)
    }

    @Test
    fun letterboxesVerticallyWhenContentIsTallerThanStream() {
        // 内容区比码流更"高"（竖屏/窄安全区）：以宽为准，上下留边。
        val tallContent = PixelArea(0, 0, 400, 1000)
        val pane = DisplayGeometry.containPane(tallContent, 1920, 1080)
        assertEquals(0, pane.left)
        assertEquals(400, pane.width)
        assertEquals(400, pane.right)
        // 400 / (16/9) = 225
        assertClose(225, pane.height)
        assertClose(tallContent.centerY(), pane.centerY())
        assertTrue(pane.top >= tallContent.top && pane.bottom <= tallContent.bottom)
        assertAspectClose(1920, 1080, pane, tolerance = 0.002f)
    }

    @Test
    fun keepsPaneInsideContentForSquareStreamInUltraWideContent() {
        val pane = DisplayGeometry.containPane(ultraWide, 720, 720)
        assertEquals(520, pane.height)
        assertClose(520, pane.width)
        assertClose(ultraWide.centerX(), pane.centerX())
        assertTrue(pane.left >= ultraWide.left && pane.right <= ultraWide.right)
    }

    @Test
    fun extremeWideStreamStaysInsideContent() {
        // 4096x16 这种荒谬比例只验证边界/不越界（1px 整数取整会放大比例误差，不再断言比例）。
        val pane = DisplayGeometry.containPane(ultraWide, 4096, 16)
        assertEquals(ultraWide.left, pane.left)
        assertEquals(ultraWide.right, pane.right)
        assertTrue(pane.height >= 1 && pane.height <= ultraWide.height)
        assertTrue(pane.top >= ultraWide.top && pane.bottom <= ultraWide.bottom)
        assertClose(ultraWide.centerY(), pane.centerY())
    }

    @Test
    fun extremeTallStreamStaysInsideContent() {
        val pane = DisplayGeometry.containPane(ultraWide, 16, 4096)
        assertEquals(ultraWide.top, pane.top)
        assertEquals(ultraWide.bottom, pane.bottom)
        assertTrue(pane.width >= 1 && pane.width <= ultraWide.width)
        assertTrue(pane.left >= ultraWide.left && pane.right <= ultraWide.right)
        assertClose(ultraWide.centerX(), pane.centerX())
    }

    @Test
    fun unknownSourceSizeFillsContentWithoutAssumingAspect() {
        // 码流尺寸未知（解码器还没出画面）时不得假设任何比例，直接返回安全区本身。
        assertEquals(padded, DisplayGeometry.containPane(padded, 0, 0))
        assertEquals(padded, DisplayGeometry.containPane(padded, -1, 720))
        assertEquals(padded, DisplayGeometry.containPane(padded, 1280, 0))
    }

    @Test
    fun emptyOrInvalidContentReturnsContent() {
        val empty = PixelArea(0, 0, 0, 0)
        assertEquals(empty, DisplayGeometry.containPane(empty, 1280, 720))
    }

    @Test
    fun paneNeverExceedsContentForCommonStreamSizes() {
        val contents =
            listOf(
                ultraWide,
                padded,
                PixelArea(278, 44, 1686, 520),
                PixelArea(0, 0, 512, 900),
            )
        val streams =
            listOf(
                1280 to 720,
                1412 to 654,
                1452 to 476,
                2128 to 992,
                1972 to 520,
                1920 to 1080,
                1024 to 768,
                720 to 1280,
            )
        for (content in contents) {
            for ((sw, sh) in streams) {
                val pane = DisplayGeometry.containPane(content, sw, sh)
                assertTrue("pane 必须在 content 内: content=$content stream=$sw x $sh pane=$pane", pane.left >= content.left)
                assertTrue("pane 必须在 content 内: content=$content stream=$sw x $sh pane=$pane", pane.top >= content.top)
                assertTrue("pane 必须在 content 内: content=$content stream=$sw x $sh pane=$pane", pane.right <= content.right)
                assertTrue("pane 必须在 content 内: content=$content stream=$sw x $sh pane=$pane", pane.bottom <= content.bottom)
                assertTrue("pane 尺寸必须为正: $pane", pane.width > 0 && pane.height > 0)
                // 常见尺寸下比例误差应小于 0.3%（整数像素取整 + 奇数居中）
                assertAspectClose(sw, sh, pane, tolerance = 0.003f)
            }
        }
    }

    // ---- 断言辅助 ----

    private fun PixelArea.centerX(): Float = (left + right) / 2f

    private fun PixelArea.centerY(): Float = (top + bottom) / 2f

    /** 整数像素级居中误差（取整后允许 1px 偏差）。 */
    private fun assertClose(
        expected: Int,
        actual: Int,
        tolerance: Int = 1,
    ) {
        assertTrue("期望 $expected，实际 $actual（容差 ±$tolerance）", abs(expected - actual) <= tolerance)
    }

    private fun assertClose(
        expected: Float,
        actual: Float,
        tolerance: Float = 1f,
    ) {
        assertTrue("期望 $expected，实际 $actual（容差 ±$tolerance）", abs(expected - actual) <= tolerance)
    }

    private fun assertAspectClose(
        sourceWidth: Int,
        sourceHeight: Int,
        pane: PixelArea,
        tolerance: Float,
    ) {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val paneAspect = pane.width.toFloat() / pane.height
        val error = abs(paneAspect - sourceAspect) / sourceAspect
        assertTrue(
            "pane=$pane 比例 $paneAspect 相对码流 ${sourceWidth}x$sourceHeight($sourceAspect) 误差 $error 超过 $tolerance",
            error <= tolerance,
        )
    }
}
