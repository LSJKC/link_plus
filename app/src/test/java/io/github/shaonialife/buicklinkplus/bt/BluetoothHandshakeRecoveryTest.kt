package io.github.shaonialife.buicklinkplus.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 兼容尝试按目标隔离，取消后旧结果失效 */
class BluetoothHandshakeRecoveryTest {
    /** 原流程始终是首选，重复读取不消耗尝试，不同目标互不影响 */
    @Test
    fun silenceChangesOnlyTheReportedTarget() {
        val recovery = BluetoothHandshakeRecovery()
        val first = recovery.attempt("aa:bb:cc:dd:ee:01")
        assertEquals(BluetoothHandshakeRecovery.Mode.ACTIVE, first.mode)
        assertEquals(first, recovery.attempt("AA:BB:CC:DD:EE:01"))
        assertEquals(BluetoothHandshakeRecovery.Mode.RESPONDER, recovery.recordSilence("AA:BB:CC:DD:EE:01", first))
        assertEquals(BluetoothHandshakeRecovery.Mode.RESPONDER, recovery.attempt("aa:bb:cc:dd:ee:01").mode)
        assertEquals(BluetoothHandshakeRecovery.Mode.ACTIVE, recovery.attempt("AA:BB:CC:DD:EE:02").mode)
    }

    /** 重复报告不翻转模式，应答只试一轮，失败恢复默认 */
    @Test
    fun duplicateReportsDoNotUndoFallback() {
        val recovery = BluetoothHandshakeRecovery()
        val address = "AA:BB:CC:DD:EE:01"
        val first = recovery.attempt(address)
        recovery.recordSilence(address, first)
        assertNull(recovery.recordSilence(address, first))
        val fallback = recovery.attempt(address)
        assertNull(recovery.recordSilence(address, fallback))
        assertTrue(recovery.finishAttempt(address, fallback))
        assertFalse(recovery.finishAttempt(address, fallback))
        assertNull(recovery.recordSilence(address, first))
        assertEquals(BluetoothHandshakeRecovery.Mode.ACTIVE, recovery.attempt(address).mode)
        assertNull(recovery.recordSilence("AA:BB:CC:DD:EE:02", recovery.attempt(address)))
    }

    /** 用户重新发起后恢复默认，旧线程即使晚到也不能改变新操作 */
    @Test
    fun resetRejectsStaleResultsFromBothDirections() {
        val recovery = BluetoothHandshakeRecovery()
        val address = "AA:BB:CC:DD:EE:01"
        val oldActive = recovery.attempt(address)
        recovery.recordSilence(address, oldActive)
        val oldResponder = recovery.attempt(address)
        recovery.clear()
        assertEquals(BluetoothHandshakeRecovery.Mode.ACTIVE, recovery.attempt(address).mode)
        assertNull(recovery.recordSilence(address, oldActive))
        assertNull(recovery.recordSilence(address, oldResponder))
        assertFalse(recovery.finishAttempt(address, oldResponder))
        assertEquals(BluetoothHandshakeRecovery.Mode.ACTIVE, recovery.attempt(address).mode)
    }
}
