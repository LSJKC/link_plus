package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查帧率边界和静止心跳 */
class FramePacingTest {
    /** 30fps 和 60fps 不因毫秒取整降为 34ms 和 17ms 出帧 */
    @Test
    fun preciseIntervalsRespectReceiverLimit() {
        assertEquals(33_333_334L, FramePacing.waitNanos(0L, 0L, 30, true))
        assertEquals(16_666_667L, FramePacing.waitNanos(0L, 0L, 60, true))
        assertEquals(1L, FramePacing.waitNanos(33_333_333L, 0L, 30, true))
        assertEquals(0L, FramePacing.waitNanos(33_333_334L, 0L, 30, true))
    }

    /**
     * 高频通知只改变等待剩余时间，低帧率与空闲时也不能突破输出上限
     */
    @Test
    fun repeatedWakeupsDoNotAdvanceFrameDeadline() {
        for (elapsed in 1L..20L) {
            assertTrue(FramePacing.waitNanos(elapsed * 1_000_000L, 0L, 30, true) > 0L)
        }
        assertEquals(100_000_000L, FramePacing.waitNanos(0L, 0L, 10, false))
        assertEquals(33_333_334L, FramePacing.waitNanos(0L, 0L, 60, false))
        assertEquals(0L, FramePacing.waitNanos(200_000_000L, 0L, 30, true))
    }

    /** 模拟毫秒唤醒，检查 120 帧内无持续计时误差 */
    @Test
    fun roundedWakeupKeepsPhaseAndLongStallDoesNotBurst() {
        for (fps in listOf(30, 60)) {
            var clock = 0L
            var frameTime = 0L
            repeat(120) {
                val wait = FramePacing.waitNanos(clock, frameTime, fps, true)
                clock += ((wait + 999_999L) / 1_000_000L) * 1_000_000L
                frameTime = FramePacing.advance(clock, frameTime, fps, true)
            }
            assertTrue(clock - 120_000_000_000L / fps in 0L..1_000_000L)
        }
        val resumed = FramePacing.advance(500_000_000L, 0L, 30, true)
        assertEquals(500_000_000L, resumed)
        assertTrue(FramePacing.waitNanos(500_000_000L, resumed, 30, true) > 0L)
    }
}
