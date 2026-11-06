package io.github.shaonialife.buicklinkplus.transport

import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import io.github.shaonialife.buicklinkplus.iap2.Iap2LinkEngine
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 重放蓝牙引导阶段的完整探测、重新同步与新旧控制会话隔离 */
class Iap2LinkChannelTest {
    /** 只有最终确认后才保护出站通道；EOF 与本地关闭的恢复资格必须分开 */
    @Test
    fun readyCallbackRequiresFinalAckAndLocalCloseOverridesEofRecovery() {
        val ready = AtomicInteger()
        val peer = Peer(respondToPeerHandshake = true, onReady = { ready.incrementAndGet() })
        try {
            peer.stream.inbound.put(peer.syn)
            val echo = peer.nextWrite()
            assertEquals(0, ready.get())
            peer.stream.inbound.put(frame(0x40, 96, echo[5].toInt() and 0xff))
            assertTrue(peer.channel.awaitReady(2_000))
            assertEquals(1, ready.get())
            peer.stream.inbound.put(frame(0x40, 96, echo[5].toInt() and 0xff))
            assertTrue(peer.channel.awaitReady(0))
            assertEquals(1, ready.get())
            peer.stream.close()
            peer.channel.recvControl(2_000)
            assertFalse(peer.channel.isAlive)
            assertTrue(peer.channel.canRetryMfiAfterDisconnect)
            peer.channel.close()
            assertFalse(peer.channel.canRetryMfiAfterDisconnect)
        } finally {
            peer.channel.close()
        }
    }

    /** 应答模式保持静默，完整接收探测后才回复 */
    @Test
    fun responderWaitsForCompleteDetectWithoutPeriodicTraffic() {
        val peer = Peer(respondToPeerHandshake = true)
        try {
            assertTrue(peer.stream.reading.await(2, TimeUnit.SECONDS))
            assertNull(peer.stream.outbound.poll(600, TimeUnit.MILLISECONDS))
            assertFalse(peer.channel.silentNegotiationTimedOut)
            assertFalse(peer.channel.awaitReady(10))
            assertTrue(peer.channel.silentNegotiationTimedOut)
            peer.stream.inbound.put(Iap2LinkEngine.MARKER.copyOfRange(0, 3))
            assertNull(peer.stream.outbound.poll(100, TimeUnit.MILLISECONDS))
            peer.stream.inbound.put(Iap2LinkEngine.MARKER.copyOfRange(3, 6))
            assertArrayEquals(Iap2LinkEngine.MARKER, peer.nextWrite())
            assertFalse(peer.channel.silentNegotiationTimedOut)
            assertNull(peer.stream.outbound.poll(600, TimeUnit.MILLISECONDS))
            assertFalse(peer.channel.awaitReady(0))
        } finally {
            peer.channel.close()
        }
    }

    /** 探测与同步合包时先应答探测，仍须等待正确的最终确认，不能提前交付业务 */
    @Test
    fun responderHandlesCombinedDetectAndSynWithFinalAcknowledgement() {
        val peer = Peer(respondToPeerHandshake = true)
        try {
            peer.stream.inbound.put(Iap2LinkEngine.MARKER + peer.syn)
            assertArrayEquals(Iap2LinkEngine.MARKER, peer.nextWrite())
            val echo = peer.nextWrite()
            assertEquals(0xc0, echo[4].toInt() and 0xff)
            val echoSequence = echo[5].toInt() and 0xff
            assertFalse(peer.channel.awaitReady(0))
            val stale = Iap2Csm.encode(0x4301)
            peer.stream.inbound.put(frame(0x40, 97, (echoSequence + 1) and 0xff, 1, stale))
            assertFalse(peer.channel.awaitReady(50))
            assertNull(peer.channel.recvControl(0))
            peer.stream.inbound.put(frame(0x40, 96, echoSequence))
            assertTrue(peer.channel.awaitReady(2_000))
            val fresh = Iap2Csm.encode(0x1d01)
            peer.stream.inbound.put(frame(0x40, 97, echoSequence, 1, fresh))
            assertArrayEquals(fresh, peer.channel.recvControl(1_000))
            assertEquals(0, peer.channel.sessionGeneration)
        } finally {
            peer.channel.close()
        }
    }

    /** 车机直接发送同步也能应答，重复同序号同步按原协议逐字节幂等回显 */
    @Test
    fun responderAcceptsDirectSynAndReplaysIdenticalEcho() {
        val peer = Peer(respondToPeerHandshake = true)
        try {
            peer.stream.inbound.put(peer.syn)
            val first = peer.nextWrite()
            assertEquals(0xc0, first[4].toInt() and 0xff)
            peer.stream.inbound.put(peer.syn)
            assertArrayEquals(first, peer.nextWrite())
            assertFalse(peer.channel.awaitReady(0))
            peer.stream.inbound.put(frame(0x40, 96, first[5].toInt() and 0xff))
            assertTrue(peer.channel.awaitReady(2_000))
        } finally {
            peer.channel.close()
        }
    }

    /** 仅无数据且等满截止时间才是静默超时，收到噪声也不能误判为零接收 */
    @Test
    fun receivedBytesPreventSilentFallbackEvenWithoutValidFrames() {
        val peer = Peer(respondToPeerHandshake = true)
        val received = CountDownLatch(1)
        val stream = ScriptedStream()
        val channel =
            Iap2LinkChannel(stream, allowPeerRestart = true, respondToPeerHandshake = true) { line ->
                if (line.startsWith("RAW rx:")) received.countDown()
            }
        try {
            assertTrue(peer.stream.reading.await(2, TimeUnit.SECONDS))
            assertFalse(peer.channel.awaitReady(10))
            assertTrue(peer.channel.silentNegotiationTimedOut)
            peer.stream.close()
            assertFalse(peer.channel.awaitReady(2_000))
            assertFalse(peer.channel.silentNegotiationTimedOut)
            stream.inbound.put(byteArrayOf(0x11))
            assertTrue(received.await(2, TimeUnit.SECONDS))
            assertFalse(channel.awaitReady(10))
            assertFalse(channel.silentNegotiationTimedOut)
        } finally {
            peer.channel.close()
            channel.close()
        }
    }

    /** 正常模式保留 marker 加主动 SYN，未等待超时或已经主动关闭时不切换 */
    @Test
    fun activeOpeningRemainsUnchangedAndCloseCannotTriggerFallback() {
        val peer = Peer()
        try {
            val opening = peer.nextWrite()
            assertArrayEquals(Iap2LinkEngine.MARKER, opening.copyOfRange(0, Iap2LinkEngine.MARKER.size))
            assertEquals(0x80, opening[Iap2LinkEngine.MARKER.size + 4].toInt() and 0xff)
            assertFalse(peer.channel.silentNegotiationTimedOut)
            assertFalse(peer.channel.awaitReady(10))
            assertTrue(peer.channel.silentNegotiationTimedOut)
            peer.channel.close()
            assertFalse(peer.channel.silentNegotiationTimedOut)
        } finally {
            peer.channel.close()
        }
    }

    /** 无应答与本地主动取消保留不同原因，不修改握手报文 */
    @Test fun silentAndCancelledHandshakeHaveDifferentReasons() {
        val pair = InMemoryStreamPair()
        val channel = Iap2LinkChannel(pair.sideA)
        try {
            assertFalse(channel.awaitReady(10))
            assertTrue(channel.failureDescription("协商").contains("未收到任何数据"))
            channel.close()
            assertTrue(channel.failureDescription("协商").contains("本地主动关闭"))
        } finally {
            channel.close()
            pair.sideB.close()
        }
    }

    /** 6 字节探测跨多次读取时，完整后才应答 */
    @Test
    fun splitDetectRepliesOnlyWhenCompleteWithoutRestarting() {
        val peer = Peer()
        try {
            peer.establish()
            peer.stream.inbound.put(Iap2LinkEngine.MARKER.copyOfRange(0, 3))
            assertNull(peer.stream.outbound.poll(100, TimeUnit.MILLISECONDS))
            peer.stream.inbound.put(Iap2LinkEngine.MARKER.copyOfRange(3, 6))
            assertArrayEquals(Iap2LinkEngine.MARKER, peer.nextWrite())
            assertEquals(0, peer.channel.sessionGeneration)
            assertTrue(peer.channel.awaitReady(0, 0))
            peer.stream.inbound.put(ByteArray(600) { Iap2LinkEngine.MARKER[it % Iap2LinkEngine.MARKER.size] })
            assertNull(peer.stream.outbound.poll(200, TimeUnit.MILLISECONDS))
            assertEquals(0, peer.channel.sessionGeneration)
        } finally {
            peer.channel.close()
        }
    }

    /** 有探测的新同步即使沿用旧序号，也必须清旧消息并等待本轮同步确认 */
    @Test
    fun identicalSynchronizationInvalidatesOldSessionUntilFinalAck() {
        val peer = Peer()
        try {
            peer.establish()
            val oldGeneration = peer.channel.sessionGeneration
            val oldPayload = Iap2Csm.encode(0x4301, byteArrayOf(1, 2))
            peer.stream.inbound.put(frame(0x40, 97, peer.echoSequence, 1, oldPayload))
            peer.waitForWrite { it.size == 9 && (it[6].toInt() and 0xff) == 97 }
            peer.stream.inbound.put(Iap2LinkEngine.MARKER)
            assertArrayEquals(Iap2LinkEngine.MARKER, peer.nextWrite())
            peer.stream.inbound.put(peer.syn)
            val echo = peer.waitForWrite { it.size >= 9 && (it[4].toInt() and 0xff) == 0xc0 }
            val newGeneration = peer.channel.sessionGeneration
            assertEquals(oldGeneration + 1, newGeneration)
            assertThrows(Iap2LinkChannel.PeerRestartedException::class.java) {
                peer.channel.recvControl(0, oldGeneration)
            }
            assertThrows(Iap2LinkChannel.PeerRestartedException::class.java) {
                peer.channel.sendControl(Iap2Csm.encode(0x1d02), expectedGeneration = oldGeneration)
            }
            assertFalse(peer.channel.awaitReady(0, newGeneration))
            peer.stream.inbound.put(frame(0x40, 96, 111))
            assertFalse(peer.channel.awaitReady(50, newGeneration))
            peer.stream.inbound.put(frame(0x40, 96, echo[5].toInt() and 0xff))
            assertTrue(peer.channel.awaitReady(2_000, newGeneration))
            val fresh = Iap2Csm.encode(0x1d01)
            peer.stream.inbound.put(frame(0x40, 97, echo[5].toInt() and 0xff, 1, fresh))
            // 重新同步哨兵只负责唤醒，旧热点载荷不能混入新身份消息
            var received = peer.channel.recvControl(500, newGeneration)
            if (received == null) received = peer.channel.recvControl(500, newGeneration)
            assertArrayEquals(fresh, received)
            assertNull(peer.channel.recvControl(0, newGeneration))
        } finally {
            peer.channel.close()
        }
    }

    /** 探测后紧跟真正的新业务时，不能再补发过期探测而打断仍在工作的链路 */
    @Test
    fun freshBusinessInSameReadCancelsPendingDetectReply() {
        val peer = Peer()
        try {
            peer.establish()
            val fresh = Iap2Csm.encode(0x5000)
            peer.stream.inbound.put(Iap2LinkEngine.MARKER + frame(0x40, 97, peer.echoSequence, 1, fresh))
            val reply = peer.nextWrite()
            assertTrue(reply.size >= 9)
            assertEquals(0x5a, reply[1].toInt() and 0xff)
            assertArrayEquals(fresh, peer.channel.recvControl(500, 0))
            assertEquals(0, peer.channel.sessionGeneration)
            assertNull(peer.stream.outbound.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            peer.channel.close()
        }
    }

    /** 旧 CSM 半帧不能在新同步后与新身份拼接成假帧，等待者必须及时退出 */
    @Test
    fun restartWakesOldSessionWithPartialControlFrame() {
        val peer = Peer()
        val failure = AtomicReference<Throwable?>()
        val started = CountDownLatch(1)
        var worker: Thread? = null
        try {
            peer.establish()
            val session = Iap2SenderSession(peer.channel, {})
            val sessionWorker =
                Thread({
                    started.countDown()
                    try {
                        session.run(5_000)
                    } catch (error: Throwable) {
                        failure.set(error)
                    }
                }, "旧蓝牙控制会话测试").apply {
                    isDaemon = true
                    start()
                }
            worker = sessionWorker
            assertTrue(started.await(1, TimeUnit.SECONDS))
            peer.waitForWrite { it.size > 14 && it[13] == 0x1d.toByte() && it[14] == 0.toByte() }
            peer.stream.inbound.put(frame(0x40, 97, 101, 1, Iap2Csm.encode(0x1d01).copyOfRange(0, 4)))
            peer.waitForWrite { it.size == 9 && (it[6].toInt() and 0xff) == 97 }
            peer.stream.inbound.put(Iap2LinkEngine.MARKER + peer.syn)
            peer.waitForWrite { it.size >= 9 && (it[4].toInt() and 0xff) == 0xc0 }
            sessionWorker.join(1_000)
            assertFalse("旧会话不能继续阻塞等待旧识别消息", sessionWorker.isAlive)
            assertTrue(failure.get() is Iap2LinkChannel.PeerRestartedException)
        } finally {
            peer.channel.close()
            worker?.join(1_000)
        }
    }

    /** 重探测噪音不能无限重建，完成三次恢复后继续保留第三代会话 */
    @Test
    fun peerRestartsHaveFiniteLimit() {
        val peer = Peer()
        try {
            peer.establish()
            repeat(3) { count ->
                peer.stream.inbound.put(Iap2LinkEngine.MARKER + peer.syn)
                val echo = peer.waitForWrite { it.size >= 9 && (it[4].toInt() and 0xff) == 0xc0 }
                peer.stream.inbound.put(frame(0x40, 96, echo[5].toInt() and 0xff))
                assertTrue(peer.channel.awaitReady(2_000, count + 1))
            }
            peer.stream.inbound.put(Iap2LinkEngine.MARKER + peer.syn)
            assertNull(peer.stream.outbound.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(3, peer.channel.sessionGeneration)
        } finally {
            peer.channel.close()
        }
    }

    /** 使用生产链路引擎检查日志中的窗口、会话和同步参数 */
    private class Peer(
        respondToPeerHandshake: Boolean = false,
        onReady: () -> Unit = {},
    ) {
        val stream = ScriptedStream()
        val channel =
            Iap2LinkChannel(
                stream,
                name = "蓝牙探测测试",
                allowPeerRestart = true,
                respondToPeerHandshake = respondToPeerHandshake,
                onReady = onReady,
            )
        val syn = frame(0x80, 96, 0, 0, hex("01 05 08 00 05 dc 00 49 1e 03 01 00 02 02 01 02"))
        var echoSequence = 0

        /** 车机主动同步后回最终确认，测试从已建链状态开始 */
        fun establish() {
            stream.inbound.put(syn)
            val echo = waitForWrite { it.size >= 9 && (it[4].toInt() and 0xff) == 0xc0 }
            echoSequence = echo[5].toInt() and 0xff
            stream.inbound.put(frame(0x40, 96, echoSequence))
            check(channel.awaitReady(2_000, 0)) { "测试初始链路未就绪" }
            stream.outbound.clear()
        }

        /** 输出等待设置超时，避免死锁 */
        fun nextWrite(): ByteArray = stream.outbound.poll(2, TimeUnit.SECONDS) ?: error("没有收到链路应答")

        /** 跳过启动标记和裸确认，只查本场景需要的完整输出 */
        fun waitForWrite(predicate: (ByteArray) -> Boolean): ByteArray {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                val bytes = stream.outbound.poll(50, TimeUnit.MILLISECONDS) ?: continue
                if (predicate(bytes)) return bytes
            }
            error("没有收到期望的链路应答")
        }
    }

    /** 队列流允许测试脚本精确控制分片和合包，不依赖管道的写线程存活状态 */
    private class ScriptedStream : DuplexStream {
        val inbound = LinkedBlockingQueue<ByteArray>()
        val outbound = LinkedBlockingQueue<ByteArray>()
        val reading = CountDownLatch(1)

        /** 空数组只作为结束信号，测试输入均小于读取缓冲 */
        override fun read(buffer: ByteArray): Int {
            reading.countDown()
            val bytes = inbound.take()
            if (bytes.isEmpty()) return -1
            check(bytes.size <= buffer.size)
            bytes.copyInto(buffer)
            return bytes.size
        }

        /** 保留每次实际写出的字节，供探测和同步确认断言 */
        override fun write(bytes: ByteArray) {
            outbound.put(bytes.copyOf())
        }

        /** 唤醒正在等待输入的读取线程 */
        override fun close() {
            inbound.offer(ByteArray(0))
        }
    }

    companion object {
        /** 构造带完整头校验和与载荷校验和的对端链路帧 */
        private fun frame(
            control: Int,
            sequence: Int,
            ack: Int,
            session: Int = 0,
            payload: ByteArray? = null,
        ): ByteArray {
            val length = if (payload == null) 9 else 10 + payload.size
            val bytes =
                byteArrayOf(
                    0xff.toByte(),
                    0x5a,
                    (length ushr 8).toByte(),
                    length.toByte(),
                    control.toByte(),
                    sequence.toByte(),
                    ack.toByte(),
                    session.toByte(),
                    0,
                ) + (payload ?: byteArrayOf())
            bytes[8] = (-bytes.take(8).sumOf { it.toInt() and 0xff }).toByte()
            return if (payload == null) bytes else bytes + (-payload.sumOf { it.toInt() and 0xff }).toByte()
        }

        /** 十六进制参数只用于测试样例 */
        private fun hex(value: String): ByteArray = value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }
}
