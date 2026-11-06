package io.github.shaonialife.buicklinkplus.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查日夜设置优先级和会话隔离 */
class ReceiverAppearanceStateTest {
    @Test
    fun `迟到初始或侧信道信息不能覆盖明确事件`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        assertTrue(state.initialize(connection, true))
        assertTrue(state.update(connection, false))
        assertFalse(state.initialize(connection, true))
        assertFalse(state.isNight(true, true))
    }

    @Test
    fun `未收到通告时两块屏都跟随系统`() {
        val state = ReceiverAppearanceState()
        state.begin(Any())
        listOf(false, true).forEach { night ->
            assertEquals(night, state.isNight(carDisplay = false, systemNight = night))
            assertEquals(night, state.isNight(carDisplay = true, systemNight = night))
        }
    }

    @Test
    fun `车机夜间优先且手机仍保持系统日间`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        assertTrue(state.update(connection, true))
        assertTrue(state.isNight(carDisplay = true, systemNight = false))
        assertFalse(state.isNight(carDisplay = false, systemNight = false))
    }

    @Test
    fun `明确日间值优先于系统夜间`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        assertTrue(state.update(connection, false))
        assertFalse(state.isNight(carDisplay = true, systemNight = true))
        assertTrue(state.isNight(carDisplay = false, systemNight = true))
    }

    @Test
    fun `明确事件可以在本连接中来回切换`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        state.update(connection, true)
        assertTrue(state.update(connection, false))
        assertFalse(state.isNight(true, true))
        assertTrue(state.update(connection, 1))
        assertTrue(state.isNight(true, false))
    }

    @Test
    fun `无效值与普通事件不清除已有通告`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        state.update(connection, true)
        listOf(null, "false", "true", -1, 2, 0.5, Double.NaN, Double.POSITIVE_INFINITY, emptyMap<String, Any>()).forEach {
            assertFalse(state.update(connection, it))
            assertTrue(state.isNight(true, false))
        }
    }

    @Test
    fun `重复通告和重复取得连接不重复刷新`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        state.update(connection, false)
        assertFalse(state.begin(connection))
        assertFalse(state.update(connection, 0L))
        assertFalse(state.isNight(true, true))
    }

    @Test
    fun `新连接先清旧值且拒绝旧连接更新与关闭`() {
        val state = ReceiverAppearanceState()
        val old = Any()
        val current = Any()
        state.begin(old)
        state.update(old, true)
        assertTrue(state.begin(current))
        assertFalse(state.isNight(true, false))
        assertFalse(state.initialize(old, true))
        assertFalse(state.isNight(true, false))
        state.update(current, false)
        assertFalse(state.update(old, true))
        assertFalse(state.end(old))
        assertFalse(state.isNight(true, true))
    }

    @Test
    fun `断开后跟随当前系统且迟到更新无效`() {
        val state = ReceiverAppearanceState()
        val connection = Any()
        state.begin(connection)
        state.update(connection, false)
        assertTrue(state.end(connection))
        assertTrue(state.isNight(true, true))
        assertFalse(state.isNight(true, false))
        assertFalse(state.update(connection, true))
        assertFalse(state.end(connection))
    }

    @Test
    fun `内容相等的不同连接也不能取得所有权`() {
        val state = ReceiverAppearanceState()
        val current = String(charArrayOf('a'))
        val other = String(charArrayOf('a'))
        state.begin(current)
        assertFalse(state.update(other, true))
        assertFalse(state.end(other))
        assertTrue(state.update(current, false))
    }

    @Test
    fun `解析只接受布尔值与精确零一`() {
        listOf(0, 0L, 0.0, false).forEach { assertEquals(false, parseNightMode(it)) }
        listOf(1, 1L, 1.0, true).forEach { assertEquals(true, parseNightMode(it)) }
        listOf(null, "0", "1", "true", 2, -1, 1.01).forEach { assertNull(parseNightMode(it)) }
    }
}
