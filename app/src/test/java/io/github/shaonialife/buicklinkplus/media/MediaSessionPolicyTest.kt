package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionPolicyTest {
    /**
     * 播放器暂停后列表重排及元数据暂缺不能把控制发给另一个会话
     */
    @Test fun pausedSessionSurvivesReorderingAndMissingMetadata() {
        val candidates = listOf(MediaSessionPolicy.Candidate("视频", false, true), MediaSessionPolicy.Candidate("音乐", false, false))
        assertEquals(1, MediaSessionPolicy.choose(candidates, "音乐"))
    }

    /**
     * 其他播放器实际开始播放后切换目标，仍在播放的原会话不因列表重排跳动
     */
    @Test fun activePlaybackWinsWithoutShufflingCurrentPlayback() {
        val candidates = listOf(MediaSessionPolicy.Candidate("新音乐", true, true), MediaSessionPolicy.Candidate("原音乐", false, true))
        assertEquals(0, MediaSessionPolicy.choose(candidates, "原音乐"))
        assertEquals(1, MediaSessionPolicy.choose(candidates.map { it.copy(playing = true) }, "原音乐"))
    }

    /**
     * 旧会话销毁后只能选择仍存在的候选，没有可用会话时保持空
     */
    @Test fun destroyedSessionCannotRemainSelected() {
        assertEquals(0, MediaSessionPolicy.choose(listOf(MediaSessionPolicy.Candidate("新音乐", false, true)), "旧音乐"))
        assertNull(MediaSessionPolicy.choose(emptyList<MediaSessionPolicy.Candidate<String>>(), "旧音乐"))
        assertNull(MediaSessionPolicy.choose(listOf(MediaSessionPolicy.Candidate("未知", false, false)), null))
    }
}
