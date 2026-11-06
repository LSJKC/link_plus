package io.github.shaonialife.buicklinkplus.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RetryBackoffTest {
    /** 快速重试从 3 秒倍增，最多 24 秒，成功后恢复 3 秒 */
    @Test fun retriesRemainBounded() {
        assertEquals(3_000L, Iap2RetryBackoff.delay(1))
        assertEquals(6_000L, Iap2RetryBackoff.delay(2))
        assertEquals(12_000L, Iap2RetryBackoff.delay(3))
        assertEquals(24_000L, Iap2RetryBackoff.delay(4))
        assertEquals(24_000L, Iap2RetryBackoff.delay(100))
    }

    /** 达到阈值后每 30 秒慢速重试，不停止连接 */
    @Test fun slowDownThresholdAndDelay() {
        assertFalse(Iap2RetryBackoff.shouldSlowDown(1))
        assertFalse(Iap2RetryBackoff.shouldSlowDown(Iap2RetryBackoff.MAX_CONSECUTIVE_FAILURES - 1))
        assertTrue(Iap2RetryBackoff.shouldSlowDown(Iap2RetryBackoff.MAX_CONSECUTIVE_FAILURES))
        assertTrue(Iap2RetryBackoff.shouldSlowDown(Iap2RetryBackoff.MAX_CONSECUTIVE_FAILURES + 1))
        assertEquals(3_000L, Iap2RetryBackoff.nextDelay(1))
        assertEquals(Iap2RetryBackoff.SLOW_RETRY_DELAY, Iap2RetryBackoff.nextDelay(Iap2RetryBackoff.MAX_CONSECUTIVE_FAILURES))
    }
}
