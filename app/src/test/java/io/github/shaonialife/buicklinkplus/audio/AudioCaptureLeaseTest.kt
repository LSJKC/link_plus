package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务死亡、取消与快速重连共用同一请求所有权规则
 */
class AudioCaptureLeaseTest {
    /** 未来请求先取消后，迟到启动不释放当前采集 */
    @Test fun cancelledFutureStartCannotDisplaceActiveOwner() {
        val lease = AudioCaptureLease()
        assertTrue(lease.begin(10))
        assertFalse(lease.stop(12))
        assertFalse(lease.canBegin(11))
        assertFalse(lease.canBegin(12))
        assertEquals(10L, lease.owner)
        assertTrue(lease.canBegin(13))
        assertTrue(lease.stop(10))
        assertTrue(lease.begin(13))
    }

    @Test fun cancelledBeforeStartCanNeverRegisterRoute() {
        val lease = AudioCaptureLease()
        assertFalse(lease.stop(20))
        assertFalse(lease.begin(20))
        assertFalse(lease.begin(19))
        assertTrue(lease.begin(21))
    }

    @Test fun oldStopAndLateOldStartNeverReplaceNewOwner() {
        val lease = AudioCaptureLease()
        assertTrue(lease.begin(10))
        assertTrue(lease.stop(10))
        assertTrue(lease.begin(11))
        assertFalse(lease.stop(10))
        assertEquals(11L, lease.owner)
        assertFalse(lease.begin(10))
        assertFalse(lease.begin(11))
        assertTrue(lease.stop(11))
        assertEquals(0L, lease.owner)
        assertFalse(lease.stop(11))
    }
}
