package io.github.shaonialife.buicklinkplus.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 内存链路在对端关闭后返回 EOF */
class InMemoryStreamPairTest {
    /**
     * 双向验证关闭的是写端，不能关闭读端后让既有阻塞读取继续等待
     */
    @Test fun peerCloseUnblocksReadWithEofInBothDirections() {
        repeat(2) { direction ->
            val pair = InMemoryStreamPair()
            val readerSide = if (direction == 0) pair.sideA else pair.sideB
            val peer = if (direction == 0) pair.sideB else pair.sideA
            val started = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val count = AtomicInteger(0)
            val failure = AtomicReference<Throwable?>()
            val reader =
                Thread({
                    started.countDown()
                    try {
                        count.set(readerSide.read(ByteArray(16)))
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        ended.countDown()
                    }
                }, "模拟链路关闭回归").apply { isDaemon = true }
            try {
                reader.start()
                assertTrue(started.await(1, TimeUnit.SECONDS))
                peer.close()
                assertTrue("对端关闭后读取未被唤醒", ended.await(1, TimeUnit.SECONDS))
                assertNull(failure.get())
                assertEquals(-1, count.get())
            } finally {
                pair.sideA.close()
                pair.sideB.close()
                reader.interrupt()
                reader.join(1000)
            }
        }
    }
}
