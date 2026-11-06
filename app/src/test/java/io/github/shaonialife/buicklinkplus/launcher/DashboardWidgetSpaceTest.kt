package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不缩小官方控件，分屏尺寸不足必须回退到明确提示 */
class DashboardWidgetSpaceTest {
    @Test fun exactMinimumIsUsable() {
        assertTrue(widgetFits(250, 125, 250, 125))
        assertTrue(widgetFits(300, 220, 200, 200))
    }

    @Test fun narrowOrShortAreaCannotClipControls() {
        assertFalse(widgetFits(249, 300, 250, 125))
        assertFalse(widgetFits(400, 124, 250, 125))
        assertFalse(widgetFits(100, 500, 200, 60))
    }

    @Test fun unmeasuredAndInvalidAreasCannotCreateHost() {
        assertFalse(widgetFits(0, 125, 0, 125))
        assertFalse(widgetFits(250, 0, 250, 0))
        assertFalse(widgetFits(-1, 200, 200, 200))
    }

    @Test fun portraitAndLandscapeUseActualArea() {
        assertFalse(widgetFits(180, 420, 250, 125))
        assertTrue(widgetFits(420, 180, 250, 125))
    }
}
