package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsTimelineTest {
    private val document = requireNotNull(LyricsParser.parse("[00:01]第一句\n[00:03]第二句\n[00:09]末句"))

    /** 下一句边界按真实速度计算，末句没有循环唤醒 */
    @Test fun schedulesOnlyNextBoundaryAndStopsAtEnd() {
        assertEquals(1_000L, LyricsTimeline.nextDelay(document, 0, 1f, true))
        assertEquals(1_000L, LyricsTimeline.nextDelay(document, 1_000, 2f, true))
        assertEquals(6_000L, LyricsTimeline.nextDelay(document, 3_000, 1f, true))
        assertNull(LyricsTimeline.nextDelay(document, 9_000, 1f, true))
    }

    /** 暂停、未知进度或异常速度均不安排后台任务 */
    @Test fun inactiveOrInvalidClockDoesNotSchedule() {
        assertNull(LyricsTimeline.nextDelay(document, 0, 1f, false))
        assertNull(LyricsTimeline.nextDelay(document, null, 1f, true))
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(LyricsTimeline.nextDelay(document, 0, speed, true))
        }
    }

    /** 大量进度更新只跨越真实行边界，不生成新的歌词文档 */
    @Test fun repeatedProgressAndSeeksKeepBoundedTimeline() {
        var changes = 0
        var last = -1
        repeat(100_000) { sample ->
            val line = document.lineAt(sample.toLong())
            if (line != last) {
                changes++
                last = line
            }
        }
        assertEquals(3, changes)
        assertEquals(0, document.lineAt(1_500))
        assertEquals(2, document.lineAt(50_000))
        assertEquals(-1, document.lineAt(0))
        assertEquals(3, document.lines.size)
    }
}
