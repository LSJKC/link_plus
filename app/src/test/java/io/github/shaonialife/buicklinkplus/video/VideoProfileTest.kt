package io.github.shaonialife.buicklinkplus.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoProfileTest {
    @Test
    fun minimumSizePreservesScreenAspectAndBothAxesStayEven() {
        assertEquals(682 to 180, VideoProfile.scaledSize(1972, 520, 30))
        assertEquals(1972 to 520, VideoProfile.scaledSize(1972, 520, 100))
        assertEquals(2168 to 572, VideoProfile.scaledSize(1972, 520, 110))
        assertEquals(324 to 696, VideoProfile.scaledSize(1080, 2320, 30))
    }

    @Test
    fun requested60HonorsBuick30AndEncoder24() {
        assertEquals(30, VideoProfile.limitFps(60, 30))
        assertEquals(24, VideoProfile.limitFps(60, 30, 24))
        assertEquals(30, VideoProfile.limitFps(30, 60, 60))
    }

    @Test
    fun automaticRateScalesWithPixelsAndFpsWithinBounds() {
        assertEquals(8_203_520, VideoProfile.bitrate(1972, 520, 30, 0))
        assertEquals(4_000_000, VideoProfile.bitrate(590, 180, 30, 0))
        assertEquals(12_000_000, VideoProfile.bitrate(3154, 832, 60, 0))
        assertEquals(8_000_000, VideoProfile.bitrate(1972, 520, 30, 8))
        assertEquals(20_000_000, VideoProfile.bitrate(1972, 520, 30, 20))
        assertEquals(6_152_640, VideoProfile.legacyBitrate(1972, 520, 30))
    }
}
