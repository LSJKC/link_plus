package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeatherRefreshStateTest {
    /**
     * 开机不足半小时也能首次查询和手动刷新
     */
    @Test fun firstRequestAndExplicitRefreshIgnoreBootAge() {
        val state = WeatherRefreshState()
        val first = state.begin("北京", 10)!!
        assertTrue(state.complete(first, 20) {})
        assertNull(state.begin("北京", 30))
        assertNotNull(state.begin("北京", 40, force = true))
    }

    /**
     * 多次切换只查询最新城市且不发布旧结果
     */
    @Test fun cityChangesCoalesceAndRejectOldResponse() {
        val state = WeatherRefreshState()
        val old = state.begin("北京", 0)!!
        assertNull(state.begin("上海", 1, true))
        assertNull(state.begin("广州", 2, true))
        var published = false
        assertFalse(state.complete(old, 3) { published = true })
        assertFalse(published)
        val next = state.next(4)!!
        assertEquals("广州", next.choice)
        assertTrue(state.complete(next, 5) { published = true })
        assertTrue(published)
        assertNull(state.next(6))
    }

    /**
     * 失败查询仍需退避并防止重复完成清空新请求
     */
    @Test fun failureBackoffAndDuplicateCompletionAreBounded() {
        val state = WeatherRefreshState(100)
        val first = state.begin("北京", 0)!!
        state.complete(first, 1) {}
        assertNull(state.begin("北京", 100))
        val next = state.begin("北京", 101)!!
        assertFalse(state.complete(first, 102) { fail() })
        assertNull(state.begin("北京", 103))
        assertTrue(state.complete(next, 104) {})
    }
}
