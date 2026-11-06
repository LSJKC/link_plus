package io.github.shaonialife.buicklinkplus.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证默认填充、范围、画布偏移和低分辨率预览一致性
 */
class WallpaperCalibrationTest {
    @Test fun defaultKeepsCenteredAspectFill() {
        val bounds = WallpaperCalibration().bounds(100, 100, 200f, 100f)
        assertEquals(WallpaperBounds(0f, -50f, 200f, 150f), bounds)
    }

    @Test fun supportedLimitsAreClamped() {
        assertEquals(WallpaperCalibration(25, -1000, 1000), WallpaperCalibration(1, -9000, 9000).normalized())
        assertEquals(300, WallpaperCalibration(999).normalized().zoomPercent)
    }

    @Test fun offsetsUseCanvasDimensions() {
        val value = WallpaperCalibration(100, 1000, -1000).bounds(200, 100, 200f, 100f)
        assertEquals(WallpaperBounds(200f, -100f, 400f, 0f), value)
    }

    @Test fun previewAndFullCanvasHaveSameNormalizedTransform() {
        val config = WallpaperCalibration(175, 300, -250)
        val full = config.bounds(1600, 900, 2000f, 600f)
        val small = config.bounds(1600, 900, 500f, 150f)
        assertEquals(full.left / 4f, small.left, 0.001f)
        assertEquals(full.top / 4f, small.top, 0.001f)
        assertEquals(full.right / 4f, small.right, 0.001f)
        assertEquals(full.bottom / 4f, small.bottom, 0.001f)
    }
}
