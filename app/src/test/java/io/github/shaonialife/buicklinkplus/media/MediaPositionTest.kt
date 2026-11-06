package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaPositionTest {
    /** 相同状态被多次轮询时仍从播放器的原始时间锚外推 */
    @Test fun pollingDoesNotResetPositionAnchor() {
        assertEquals(15_000L, MediaPosition.at(10_000, 1_000, 1f, true, 60_000, 6_000))
        assertEquals(18_000L, MediaPosition.at(10_000, 1_000, 1f, true, 60_000, 9_000))
    }

    /** 暂停、缓冲及未知时间锚不能继续猜测播放进度 */
    @Test fun pausedAndUnknownClockStayAtReportedPosition() {
        assertEquals(10_000L, MediaPosition.at(10_000, 1_000, 0f, false, 60_000, 6_000))
        assertEquals(10_000L, MediaPosition.at(10_000, 0, 1f, true, 60_000, 6_000))
        assertEquals(10_000L, MediaPosition.at(10_000, 7_000, 1f, true, 60_000, 6_000))
        assertNull(MediaPosition.at(-1, 1_000, 1f, true, 60_000, 6_000))
    }

    /** 跳转与倍速使用最新状态，并限制在有效歌曲时长内 */
    @Test fun seekSpeedAndDurationAreRespected() {
        assertEquals(24_000L, MediaPosition.at(20_000, 1_000, 2f, true, 60_000, 3_000))
        assertEquals(60_000L, MediaPosition.at(59_000, 1_000, 2f, true, 60_000, 3_000))
        assertEquals(0L, MediaPosition.at(500, 1_000, -1f, true, 60_000, 3_000))
        assertEquals(20_000L, MediaPosition.at(20_000, 1_000, Float.NaN, true, 0, 3_000))
    }
}
