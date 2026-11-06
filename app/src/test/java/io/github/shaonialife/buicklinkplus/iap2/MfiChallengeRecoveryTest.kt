package io.github.shaonialife.buicklinkplus.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 临时换档需要跨新连接推进，但不能变成永久拒绝或污染其他车机 */
class MfiChallengeRecoveryTest {
    /** 首档拆链后新连接进入下一档，所有档位都试过后恢复基线 */
    @Test
    fun disconnectionsAdvanceAcrossConnectionsWithoutPermanentRejection() {
        val recovery = MfiChallengeRecovery()
        val baseline = listOf(32, 20, 16)
        val first = recovery.begin("临时换档车机", baseline)
        assertTrue(recovery.recordDisconnect(first, 32))
        assertFalse(recovery.recordDisconnect(first, 32))
        val second = recovery.begin("临时换档车机", baseline)
        assertEquals(listOf(20, 16, 32), second.lengths)
        assertTrue(recovery.recordDisconnect(second, 20))
        val third = recovery.begin("临时换档车机", baseline)
        assertEquals(listOf(16, 32, 20), third.lengths)
        assertTrue(recovery.recordDisconnect(third, 16))
        assertEquals(baseline, recovery.begin("临时换档车机", baseline).lengths)
    }

    /** 新认证和成功结果都作废旧凭据，不同身份及新操作保持基线 */
    @Test
    fun staleResultsAndSuccessfulAttemptsCannotPoisonLaterConnections() {
        val recovery = MfiChallengeRecovery()
        val baseline = listOf(32, 20, 16)
        val old = recovery.begin("receiver-one", baseline)
        val current = recovery.begin("receiver-one", baseline)
        assertFalse(recovery.recordDisconnect(old, 32))
        assertTrue(recovery.recordDisconnect(current, 32))
        assertEquals(baseline, recovery.begin("receiver-two", baseline).lengths)
        recovery.recordSuccess(current)
        assertFalse(recovery.recordDisconnect(current, 20))
        assertEquals(baseline, recovery.begin("receiver-one", baseline).lengths)
        assertEquals(baseline, MfiChallengeRecovery().begin("receiver-one", baseline).lengths)
    }
}
