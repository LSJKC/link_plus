package io.github.shaonialife.buicklinkplus.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderSizeSelectorTest {
    @Test fun preservesSupportedRequestAndNativeOrder() {
        val result = EncoderSizeSelector.candidates(1478 to 390, 1972 to 520, 2, 2) { _, _ -> true }
        assertEquals(listOf(1478 to 390, 1972 to 520), result.take(2))
        assertTrue(result.size <= 8)
    }

    @Test fun unsupportedBuickSizeFallsBackToActualAlignment() {
        val result = EncoderSizeSelector.candidates(1972 to 520, 1972 to 520, 16, 8) { w, h -> w % 16 == 0 && h % 8 == 0 }
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { (w, h) -> w <= 1972 && h <= 520 && w % 16 == 0 && h % 8 == 0 })
        assertTrue(result.distinct().size == result.size)
    }

    @Test fun exhaustedCapabilitiesDoNotReintroduceUnsupportedSizes() {
        assertTrue(EncoderSizeSelector.candidates(1972 to 520, 1972 to 520, 16, 16) { _, _ -> false }.isEmpty())
    }

    /** 完整枚举编码器尺寸，检查窄档位和最小尺寸回退 */
    @Test fun exactCapabilityBoundaryAndMinimumAreConsidered() {
        val boundary =
            EncoderSizeSelector.candidates(1972 to 520, 1972 to 520, 16, 8, 16..1920, 8..512) { w, h ->
                w == 1920 && h == 504
            }
        assertEquals(listOf(1920 to 504), boundary)
        val minimum = EncoderSizeSelector.candidates(160 to 40, 160 to 40, 16, 8, 320..1920, 80..512) { _, _ -> true }
        assertEquals(320 to 80, minimum.first())
    }
}
