package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 左列组件遵守视图许可和安全区，等分后不越界 */
class LeftWidgetColumnTest {
    /**
     * 只有明确布尔许可才允许绘制，不能继承副屏许可或将字符串当真值
     */
    @Test fun receiverMustExplicitlyAllowOutsideUi() {
        assertFalse(DisplayAreas.allowsOutsideUi(null))
        assertFalse(DisplayAreas.allowsOutsideUi(mapOf("safeArea" to mapOf("drawUIOutsideSafeArea" to "true"))))
        assertFalse(
            DisplayAreas.allowsOutsideUi(
                mapOf(
                    "safeArea" to mapOf("drawUIOutsideSafeArea" to true),
                    "initialViewArea" to mapOf("safeArea" to emptyMap<String, Any>()),
                ),
            ),
        )
        assertTrue(DisplayAreas.allowsOutsideUi(mapOf("initialViewArea" to mapOf("safeArea" to mapOf("drawUIOutsideSafeArea" to true)))))
    }

    /** 别克左列组件按安全区留白缩放，避开上下原生覆盖区 */
    @Test fun buickAreaStaysOutsideSafeContent() {
        val safe = PixelArea(278, 44, 1686, 520)
        val disabled = DisplayGeometry.create(1972, 520, 1972, 520, safe)
        assertNull(LeftWidgetColumn.area(disabled))
        val enabled = disabled.copy(outsideUiAllowed = true)
        val area = checkNotNull(LeftWidgetColumn.area(enabled))
        assertTrue(area.right < safe.left)
        assertTrue(area.left >= 0)
        assertTrue(area.top > safe.top && area.bottom < safe.bottom)
        assertFalse(safe.contains(area.left, area.top))
        val scaled = DisplayGeometry.create(1972, 520, 3944, 1040, safe, outsideUiAllowed = true)
        val doubled = checkNotNull(LeftWidgetColumn.area(scaled))
        assertEquals(area.left * 2, doubled.left)
        assertEquals(area.right * 2, doubled.right)
    }

    /**
     * 无留白、竖屏及左缘保留区被视图占用时不显示，不挤占原内容
     */
    @Test fun unavailableSpaceNeverOverlapsContentOrOtherView() {
        assertNull(LeftWidgetColumn.area(DisplayGeometry.create(1160, 540, 1160, 540, PixelArea(0, 0, 1160, 540), outsideUiAllowed = true)))
        assertNull(
            LeftWidgetColumn.area(DisplayGeometry.create(648, 1400, 648, 1400, PixelArea(200, 0, 648, 1400), outsideUiAllowed = true)),
        )
        assertNull(
            LeftWidgetColumn.area(
                DisplayGeometry.create(
                    1972,
                    520,
                    1972,
                    520,
                    PixelArea(278, 44, 1686, 520),
                    viewArea = PixelArea(200, 0, 1972, 520),
                    outsideUiAllowed = true,
                ),
            ),
        )
    }

    /**
     * 勾选几个组件就把留白纵向等分为几段，板块不出区域且互不重叠
     */
    @Test fun sectionsSplitAreaEvenlyWithoutOverlap() {
        val area = PixelArea(0, 0, 242, 404)
        val three = LeftWidgetColumn.sectionRects(area, 3)
        assertEquals(3, three.size)
        assertEquals(area.left, three.first().left)
        assertEquals(area.right, three.first().right)
        assertEquals(area.top, three.first().top)
        assertEquals(area.bottom, three.last().bottom)
        three.zipWithNext().forEach { (upper, lower) ->
            assertTrue(upper.bottom <= lower.top)
            assertTrue(lower.top - upper.bottom <= area.width / 24)
        }
        assertEquals(emptyList<PixelArea>(), LeftWidgetColumn.sectionRects(area, 0))
        val single = LeftWidgetColumn.sectionRects(area, 1)
        assertEquals(listOf(area), single)
    }

    /**
     * 设计空间逆变换：板块中心映射到设计空间中心，板块外不命中
     */
    @Test fun designPointMapsSectionCenterAndRejectsOutside() {
        val section = PixelArea(10, 20, 252, 148)
        val center = LeftWidgetColumn.designPoint(section, 131f, 84f)
        assertEquals(LeftWidgetColumn.REF_W / 2f, center?.first ?: 0f, 1f)
        assertEquals(LeftWidgetColumn.REF_H / 2f, center?.second ?: 0f, 1f)
        assertNull(LeftWidgetColumn.designPoint(section, 5f, 84f))
        assertNull(LeftWidgetColumn.designPoint(section, 131f, 200f))
    }
}
