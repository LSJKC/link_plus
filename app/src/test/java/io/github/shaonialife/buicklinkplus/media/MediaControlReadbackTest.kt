package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaControlReadbackTest {
    /** 播放 400ms 后在 1500ms 暂停时，不保留早期成功提示 */
    @Test fun detectsPlaybackThatStopsAgain() {
        val readback = MediaControlReadback<String>()
        val request = readback.begin("甲")
        assertEquals(MediaControlReadback.Result.PLAYING, readback.observe(request, "甲", true))
        assertEquals(MediaControlReadback.Result.PLAYED_THEN_PAUSED, readback.observe(request, "甲", false))
    }

    /** 快速控制、更换播放器及服务退役都拒绝旧回读 */
    @Test fun staleRequestsCannotChangeNewObservation() {
        val readback = MediaControlReadback<String>()
        val old = readback.begin("甲")
        val current = readback.begin("乙")
        assertNull(readback.observe(old, "甲", true))
        assertNull(readback.observe(current, "甲", true))
        readback.begin(null)
        assertNull(readback.observe(current, "乙", true))
    }
}
