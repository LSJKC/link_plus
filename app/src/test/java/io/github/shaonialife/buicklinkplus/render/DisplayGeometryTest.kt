package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查主副屏边界和缩放 */
class DisplayGeometryTest {
    private val safe = PixelArea(278, 44, 1686, 520)

    @Test
    fun mainDisplayDoesNotDependOnAnnouncementOrder() {
        val main = mapOf("type" to 110L, "widthPixels" to 1972, "heightPixels" to 520)
        val cluster = mapOf("type" to 111L, "widthPixels" to 2194, "heightPixels" to 700)
        assertEquals(main, DisplayAreas.mainDisplay(listOf(cluster, main)))
        assertEquals(main, DisplayAreas.mainDisplay(listOf(main, cluster)))
        assertEquals(cluster, DisplayAreas.mainDisplay(listOf(null, "无效屏幕", cluster)))
        assertNull(DisplayAreas.mainDisplay(null))
        assertNull(DisplayAreas.mainDisplay(emptyList<Any>()))
    }

    @Test
    fun currentMainViewWinsOverPresetAndOtherViews() {
        val display =
            mapOf(
                "type" to 110,
                "initialViewArea" to 1,
                "viewAreas" to
                    listOf(
                        mapOf(
                            "safeArea" to
                                mapOf(
                                    "originXPixels" to 680,
                                    "originYPixels" to 82,
                                    "widthPixels" to 1162,
                                    "heightPixels" to 486,
                                ),
                        ),
                        mapOf(
                            "widthPixels" to 1972,
                            "heightPixels" to 520,
                            "safeArea" to
                                mapOf(
                                    "originXPixels" to 278,
                                    "originYPixels" to 44,
                                    "widthPixels" to 1408,
                                    "heightPixels" to 476,
                                ),
                        ),
                    ),
            )
        assertEquals(safe, DisplayAreas.resolve(display, 1972, 520))
        assertEquals(PixelArea(0, 0, 2194, 700), DisplayAreas.resolve(mapOf("type" to 111), 2194, 700))
    }

    @Test
    fun liveSafeAreaOverridesKnownPreset() {
        val live =
            mapOf(
                "type" to 110,
                "viewAreas" to
                    listOf(
                        mapOf(
                            "safeArea" to
                                mapOf(
                                    "originXPixels" to 280,
                                    "originYPixels" to 46,
                                    "widthPixels" to 1400,
                                    "heightPixels" to 472,
                                ),
                        ),
                    ),
            )
        assertEquals(PixelArea(280, 46, 1680, 518), DisplayAreas.resolve(live, 1972, 520))
    }

    @Test
    fun presetOnlyMatchesMainScreenAndInvalidSafeFallsBackToView() {
        assertEquals(safe, DisplayAreas.resolve(mapOf("type" to 110), 1972, 520))
        assertEquals(PixelArea(0, 0, 1972, 520), DisplayAreas.resolve(mapOf("type" to 111), 1972, 520))
        val view =
            mapOf(
                "originXPixels" to 20,
                "originYPixels" to 10,
                "widthPixels" to 900,
                "heightPixels" to 400,
                "safeArea" to mapOf("widthPixels" to -1, "heightPixels" to 400),
            )
        assertEquals(PixelArea(20, 10, 920, 410), DisplayAreas.resolve(mapOf("viewAreas" to listOf(view)), 1160, 540))
    }

    @Test
    fun actualEvenScaledCanvasUsesInwardRoundingAndExclusiveEdges() {
        val g = DisplayGeometry.create(1972, 520, 2168, 572, safe)
        assertEquals(PixelArea(306, 49, 1853, 572), g.content)
        assertFalse(g.content.contains(g.streamX(277), g.streamY(44)))
        assertTrue(g.content.contains(g.streamX(280), g.streamY(46)))
        assertFalse(g.content.contains(g.streamX(1686), g.streamY(520)))
    }

    @Test
    fun manualMarginsCannotExpandSafeAreaAtAnyScale() {
        for ((w, h) in listOf(590 to 180, 1972 to 520, 3154 to 832)) {
            val g = DisplayGeometry.create(1972, 520, w, h, safe, listOf(-30, 120, 120, 120))
            val automatic = DisplayGeometry.create(1972, 520, w, h, safe).content
            assertTrue(g.content.left >= automatic.left && g.content.top >= automatic.top)
            assertTrue(g.content.right <= automatic.right && g.content.bottom <= automatic.bottom)
            assertTrue(g.content.width > 0 && g.content.height > 0)
        }
    }
}
