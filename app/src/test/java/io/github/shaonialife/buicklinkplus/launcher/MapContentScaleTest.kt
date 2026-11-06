package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 固定参考版本尺寸算例，防止误用视频缩放或改变触摸几何
 */
class MapContentScaleTest {
    @Test fun landscapeTileUsesContainerLimit() {
        assertEquals(125, MapContentScale.density(1160, 540, 500, 500, 75))
        assertEquals(83, MapContentScale.density(1160, 540, 500, 500, 50))
        assertEquals(166, MapContentScale.density(1160, 540, 500, 500, 100))
    }

    @Test fun portraitUsesItsOwnMinimumContentHeight() {
        assertEquals(144, MapContentScale.density(648, 1241, 600, 360, 75))
        assertEquals(135, MapContentScale.density(1034, 540, 1034, 540, 75))
    }

    @Test fun outOfRangeAndExtremeScreensRemainBounded() {
        assertEquals(MapContentScale.density(1160, 540, 500, 500, 50), MapContentScale.density(1160, 540, 500, 500, -1))
        assertEquals(640, MapContentScale.density(10000, 10000, 10000, 10000, 200))
        assertEquals(1, MapContentScale.density(1, 1, 1, 1, 50))
    }
}
