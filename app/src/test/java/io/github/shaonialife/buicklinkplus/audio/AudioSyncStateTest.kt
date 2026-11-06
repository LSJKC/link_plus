package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AudioSyncStateTest {
    /** 同步拒绝只登记降级，旧线程不能覆盖新会话或停止后的状态 */
    @Test fun rejectedSyncIsScopedToItsGeneration() {
        val state = AudioSyncState()
        val old = state.begin()
        state.update(old, "同步拒绝，媒体继续")
        assertEquals("同步拒绝，媒体继续", state.status)
        val current = state.begin()
        state.update(old, "迟到错误")
        assertEquals("正在建立同步", state.status)
        state.stop()
        state.update(current, "迟到成功")
        assertEquals("已停止同步", state.status)
        assertFalse(state.owns(current))
    }
}
