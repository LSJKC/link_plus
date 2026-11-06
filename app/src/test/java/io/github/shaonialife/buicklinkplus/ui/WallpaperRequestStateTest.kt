package io.github.shaonialife.buicklinkplus.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperRequestStateTest {
    /** 失败不记为已应用，相同请求合并，只允许一次退避重试 */
    @Test fun failureIsBoundedAndSuccessCommits() {
        val state = WallpaperRequestState()
        val first = state.begin("版本1:100x200", 0)!!
        assertNull(state.begin(first.key, 1))
        assertTrue(state.finish(first, false, 2))
        assertNull(state.begin(first.key, 501))
        val retry = state.begin(first.key, 502)!!
        assertFalse(state.finish(retry, false, 503))
        assertNull(state.begin(first.key, 100_000))
        val next = state.begin("版本2:100x200", 100_001)!!
        assertFalse(state.finish(next, true, 100_002))
        assertNull(state.begin(next.key, 200_000))
    }

    /** 尺寸变化和销毁后旧结果失效，重挂载不继承取消错误 */
    @Test fun geometryAndLifecycleInvalidateOldResults() {
        val state = WallpaperRequestState()
        val old = state.begin("版本1:100x200", 0)!!
        val resized = state.begin("版本1:200x100", 1)!!
        assertFalse(state.owns(old))
        assertFalse(state.finish(old, true, 2))
        assertTrue(state.owns(resized))
        state.cancel()
        assertFalse(state.owns(resized))
        assertNotNull(state.begin(resized.key, 3))
    }
}
