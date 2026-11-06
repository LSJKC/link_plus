package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFallbackPolicyTest {
    /** 无会话或旧状态不算新播放请求成功 */
    @Test fun noSessionAndStaleStateRemainUnconfirmed() {
        assertTrue(MediaFallbackPolicy.message(false, false, true, false).contains("未找到"))
        assertTrue(MediaFallbackPolicy.message(true, false, true, true).contains("尚未取得"))
        assertTrue(MediaFallbackPolicy.message(true, true, true, false).contains("请确认"))
        assertTrue(MediaFallbackPolicy.message(true, true, true, true).contains("回读到播放"))
    }
}
