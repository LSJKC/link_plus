package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRequestStateTest {
    /** 快速切歌、播放器换会话、退出再进入均拒绝迟到歌词 */
    @Test fun lateCallbacksCannotOverwriteNewSelection() {
        val state = LyricsRequestState()
        val first = LyricsTrack(1, "酷我", "1", "歌曲甲", "歌手", "", 120_000)
        val old = requireNotNull(state.select(first))
        assertNull(state.select(first))
        val second = first.copy(title = "歌曲乙")
        val next = requireNotNull(state.select(second))
        assertFalse(state.owns(old, first))
        assertTrue(state.owns(next, second))
        val restarted = second.copy(session = 2, packageName = "QQ音乐")
        state.select(restarted)
        assertFalse(state.owns(next, second))
        state.clear()
        val reopened = requireNotNull(state.select(first))
        assertFalse(state.owns(old, first))
        assertTrue(state.owns(reopened, first))
    }

    /** 曲目信息分阶段补齐及播放器补发歌词允许重新请求 */
    @Test fun enrichedMetadataInvalidatesEarlierRequest() {
        val state = LyricsRequestState()
        val initial = LyricsTrack(1, "网易云", null, "歌曲", "", "", 0)
        val pending = requireNotNull(state.select(initial))
        val complete = initial.copy(artist = "歌手", durationMs = 180_000)
        state.select(complete)
        assertFalse(state.owns(pending, initial))
        assertTrue(state.select(complete.copy(embedded = "[00:01]歌词")) != null)
    }

    /** 正常完成只能发布一次，同曲轮询不触发隐式重试 */
    @Test fun completedRequestCannotPublishTwice() {
        val state = LyricsRequestState()
        val track = LyricsTrack(1, "酷狗", null, "歌曲", "歌手", "", 120_000)
        val ticket = requireNotNull(state.select(track))
        assertTrue(state.finish(ticket, track))
        assertFalse(state.finish(ticket, track))
        assertFalse(state.owns(ticket, track))
        assertNull(state.select(track))
        state.clear()
        assertTrue(state.select(track) != null)
    }
}
