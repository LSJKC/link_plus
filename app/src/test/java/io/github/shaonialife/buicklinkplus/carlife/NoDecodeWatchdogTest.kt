package io.github.shaonialife.buicklinkplus.carlife

import io.github.shaonialife.buicklinkplus.capture.NoDecodeWatchdog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NoDecodeWatchdog] 纯 JVM 测试（时钟由调用方传入，完全确定、无 sleep）。
 *
 * 钉住：没收到帧不误报；解码进度正常不报；持续收帧但 8s 无解码输出才报；之后最多每 15s
 * 报一次；解码恢复后重新计时。
 */
class NoDecodeWatchdogTest {
    private companion object {
        const val STALL_MS = 8_000L
        const val REPEAT_MS = 15_000L
    }

    private fun watchdog() = NoDecodeWatchdog(STALL_MS, REPEAT_MS)

    @Test
    fun doesNotWarnBeforeAnyFrameArrives() {
        val watchdog = watchdog()
        watchdog.reset(0)
        assertFalse(watchdog.shouldWarn(framesReceived = 0, decoded = 0, nowMs = 0))
        assertFalse(watchdog.shouldWarn(framesReceived = 0, decoded = 0, nowMs = 60_000))
    }

    @Test
    fun doesNotWarnWhileDecodeProgresses() {
        val watchdog = watchdog()
        watchdog.reset(0)
        var decoded = 0L
        var now = 0L
        repeat(40) {
            now += 500
            decoded += 5
            assertFalse(watchdog.shouldWarn(framesReceived = 5, decoded = decoded, nowMs = now))
        }
    }

    @Test
    fun warnsAfterStallThreshold() {
        val watchdog = watchdog()
        watchdog.reset(0)
        var now = 0L
        // 首次轮询在 0.5s（基线也在此刻建立）：8s 内不报。
        while (now < 8_000L) {
            now += 500
            assertFalse(watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now))
        }
        // 8.5s：满 8s 阈值，报。
        now += 500
        assertTrue(watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now))
    }

    @Test
    fun repeatsAtMostOncePerRepeatInterval() {
        val watchdog = watchdog()
        watchdog.reset(0)
        var now = 0L
        // 首次告警在 8s（基线在第一次轮询 now=0 建立）。
        while (!watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now)) {
            now += 500
            if (now > 20_000) error("持续收帧且无解码输出时应在 8s 后告警")
        }
        assertEquals(8_000L, now)
        // 之后 15s 内不再报（8.5s ~ 22.5s）；23s 起才允许第二次。
        while (now < 22_500L) {
            now += 500
            assertFalse("告警最小间隔为 ${REPEAT_MS}ms", watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now))
        }
        now += 500
        assertTrue("距上次告警满 ${REPEAT_MS}ms 后应再次告警", watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now))
    }

    @Test
    fun decodeProgressRestartsStallTimer() {
        val watchdog = watchdog()
        watchdog.reset(0)
        var now = 0L
        // 0~3s 收帧但无解码输出：仍未到阈值。
        while (now < 3_000L) {
            now += 500
            assertFalse(watchdog.shouldWarn(framesReceived = 10, decoded = 0, nowMs = now))
        }
        // 3.5s 解码恢复（累计输出 1 帧）：计时清零。
        now += 500
        val resumedAt = now
        assertFalse(watchdog.shouldWarn(framesReceived = 10, decoded = 1, nowMs = resumedAt))
        // 恢复后必须重新计满 8s 才告警，不能沿用旧的停滞起点。
        var warnAt = 0L
        while (now < resumedAt + 20_000L) {
            now += 500
            if (watchdog.shouldWarn(framesReceived = 10, decoded = 1, nowMs = now)) {
                warnAt = now
                break
            }
        }
        assertTrue("恢复后应在约 8s 后再次告警，实际 warnAt=$warnAt", warnAt >= resumedAt + STALL_MS)
        assertTrue("恢复后不应早于 8s 告警，实际 warnAt=$warnAt", warnAt <= resumedAt + STALL_MS + 1_000L)
    }

    @Test
    fun resetRebaselinesDecodedProgress() {
        val watchdog = watchdog()
        watchdog.reset(100)
        // 基线已经是 100：即便累计值不变，也按「收帧但无解码进度」计时（首次轮询在 0.5s）。
        var now = 0L
        while (now < 8_000L) {
            now += 500
            assertFalse(watchdog.shouldWarn(framesReceived = 10, decoded = 100, nowMs = now))
        }
        now += 500
        assertTrue(watchdog.shouldWarn(framesReceived = 10, decoded = 100, nowMs = now))
        // reset 到更高基线（新连接/重建后的累计帧）同样立即生效。
        watchdog.reset(200)
        assertFalse(watchdog.shouldWarn(framesReceived = 10, decoded = 200, nowMs = now))
    }
}
