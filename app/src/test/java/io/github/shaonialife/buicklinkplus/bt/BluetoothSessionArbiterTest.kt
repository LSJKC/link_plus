package io.github.shaonialife.buicklinkplus.bt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 双向连接必须原子选出唯一所有者，不能以布尔检查代替占用 */
class BluetoothSessionArbiterTest {
    /** 检查回连已接受但出站仍在连接的竞争 */
    @Test
    fun inboundReservationBlocksOutboundBeforeWorkerStarts() {
        val arbiter = BluetoothSessionArbiter()
        val inbound = requireNotNull(arbiter.acquire(inbound = true))
        assertTrue(arbiter.inboundActive)
        assertTrue(arbiter.controlActive)
        assertNull(arbiter.acquire(inbound = false))
        assertNull(arbiter.acquire(inbound = true))
        inbound.close()
        assertFalse(arbiter.controlActive)
        assertFalse(arbiter.inboundActive)
        val outbound = requireNotNull(arbiter.acquire(inbound = false))
        outbound.confirm()
        assertNull(arbiter.acquire(inbound = true))
        assertFalse(arbiter.inboundActive)
        outbound.close()
    }

    /** 旧会话 finally 重复清理，不能让第三条连接插入新会话 */
    @Test
    fun lateDuplicateReleaseCannotUnlockNewOwner() {
        val arbiter = BluetoothSessionArbiter()
        val old = requireNotNull(arbiter.acquire(inbound = false))
        old.close()
        assertTrue(old.isLatest)
        val current = requireNotNull(arbiter.acquire(inbound = true))
        assertFalse(old.isLatest)
        old.close()
        assertTrue(arbiter.inboundActive)
        assertNull(arbiter.acquire(inbound = false))
        current.close()
        assertFalse(arbiter.controlActive)
    }

    /** 出站还在连接或初始协商时，已接受回连能接替它，迟到成功不能启动协议 */
    @Test
    fun acceptedInboundPreemptsOnlyUnconfirmedOutbound() {
        val arbiter = BluetoothSessionArbiter()
        val pending = requireNotNull(arbiter.acquire(inbound = false))
        val revoked = CountDownLatch(1)
        pending.onPreempted { revoked.countDown() }
        val inbound = requireNotNull(arbiter.acquire(inbound = true))
        assertEquals(0L, revoked.count)
        assertFalse(pending.isCurrent)
        assertThrows(IOException::class.java) { pending.confirm() }
        assertThrows(IOException::class.java) { pending.withOwnership { error("旧会话不能再写入") } }
        pending.close()
        assertTrue(inbound.isCurrent)
        assertTrue(inbound.awaitQuiescent(0))
        inbound.close()
    }

    /** 先发生回连再登记取消回调时仍能收尾，已经协商成功的连接不会被抢占 */
    @Test
    fun latePreemptionCallbackAndEstablishedChannelRemainSafe() {
        val arbiter = BluetoothSessionArbiter()
        val pending = requireNotNull(arbiter.acquire(inbound = false))
        val inbound = requireNotNull(arbiter.acquire(inbound = true))
        var notifications = 0
        pending.onPreempted { notifications++ }
        assertEquals(1, notifications)
        inbound.close()
        val established = requireNotNull(arbiter.acquire(inbound = false))
        established.confirm()
        assertNull(arbiter.acquire(inbound = true))
        established.close()
    }

    /** 旧写入退出前新通道继续等待，不因超时并发发送 */
    @Test
    fun handoverWaitsForOldOperationButNotForPhysicalClose() {
        val arbiter = BluetoothSessionArbiter()
        val outbound = requireNotNull(arbiter.acquire(inbound = false))
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val finishClose = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker =
            Thread({
                try {
                    outbound.withOwnership {
                        writing.countDown()
                        check(finishWrite.await(2, TimeUnit.SECONDS)) { "旧写入未解除" }
                    }
                    outbound.close()
                    closeStarted.countDown()
                    check(finishClose.await(2, TimeUnit.SECONDS)) { "模拟关闭未解除" }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }, "蓝牙交接及关闭阻塞测试").apply { isDaemon = true }
        var inbound: BluetoothSessionArbiter.Lease? = null
        try {
            worker.start()
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            val acquired = requireNotNull(arbiter.acquire(inbound = true))
            inbound = acquired
            assertFalse(acquired.awaitQuiescent(0))
            assertThrows(IOException::class.java) { acquired.withOwnership { error("不能与旧写入重叠") } }
            finishWrite.countDown()
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertTrue(acquired.awaitQuiescent(1_000))
            acquired.withOwnership { assertTrue(worker.isAlive) }
        } finally {
            finishWrite.countDown()
            finishClose.countDown()
            worker.join(2_000)
            inbound?.close()
            outbound.close()
        }
        failure.get()?.let { throw AssertionError("交接工作线程失败", it) }
        assertFalse(arbiter.controlActive)
    }

    /** 第二个等待者退役也不能丢失第一条阻塞写入，第三条连接仍须等待 */
    @Test
    fun releasingWaitingOwnerDoesNotLoseOutstandingOperation() {
        val arbiter = BluetoothSessionArbiter()
        val old = requireNotNull(arbiter.acquire(inbound = false))
        old.withOwnership {
            old.close()
            val waiting = requireNotNull(arbiter.acquire(inbound = true))
            assertFalse(waiting.awaitQuiescent(0))
            waiting.close()
            val third = requireNotNull(arbiter.acquire(inbound = false))
            try {
                assertFalse(third.awaitQuiescent(0))
                assertThrows(IOException::class.java) { third.withOwnership { error("旧写入仍未结束") } }
            } finally {
                third.close()
            }
            assertTrue(arbiter.controlActive)
        }
        assertFalse(arbiter.controlActive)
    }

    /** 两线程竞争时只有一个有效连接，回连可替换未确认的出站 */
    @Test
    fun simultaneousDirectionsHaveExactlyOneWinner() {
        val arbiter = BluetoothSessionArbiter()
        val ready = CountDownLatch(2)
        val begin = CountDownLatch(1)
        val done = CountDownLatch(2)
        val inbound = AtomicReference<BluetoothSessionArbiter.Lease?>()
        val outbound = AtomicReference<BluetoothSessionArbiter.Lease?>()
        val failure = AtomicReference<Throwable?>()
        val workers =
            listOf(true to inbound, false to outbound).map { (isInbound, result) ->
                Thread({
                    try {
                        ready.countDown()
                        check(begin.await(2, TimeUnit.SECONDS)) { "竞争测试未同时开始" }
                        result.set(arbiter.acquire(isInbound))
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    } finally {
                        done.countDown()
                    }
                }, "蓝牙互斥测试-$isInbound").apply {
                    isDaemon = true
                    start()
                }
            }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            begin.countDown()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("竞争线程失败", it) }
            val winners = listOfNotNull(inbound.get(), outbound.get()).filter { it.isCurrent }
            assertEquals(1, winners.size)
            assertTrue(arbiter.controlActive)
            assertNull(arbiter.acquire(inbound = false))
        } finally {
            begin.countDown()
            workers.forEach { it.join(2_000) }
            inbound.get()?.close()
            outbound.get()?.close()
        }
        assertFalse(arbiter.controlActive)
    }
}
