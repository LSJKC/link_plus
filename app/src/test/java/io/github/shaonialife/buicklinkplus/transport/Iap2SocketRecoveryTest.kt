package io.github.shaonialife.buicklinkplus.transport

import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 用本机 socket 复现零字节和 EOF 后的新链路恢复 */
class Iap2SocketRecoveryTest {
    /** 零接收时取消旧连接，再完整检查新链路握手和收发 */
    @Test
    fun silentConnectedSocketCanBeCancelledAndNextConnectionCompletes() {
        repeat(3) { round ->
            withSockets { phone, car ->
                val channel = Iap2LinkChannel(stream(phone), name = "零接收-$round", allowPeerRestart = true)
                val waiting = CountDownLatch(1)
                val result = AtomicBoolean(true)
                val waiter =
                    Thread {
                        waiting.countDown()
                        result.set(channel.awaitReady(10_000))
                    }
                try {
                    assertFalse(channel.awaitReady(80))
                    assertTrue(channel.silentNegotiationTimedOut)
                    assertTrue(channel.failureDescription("初始协商").contains("接收字节=0"))
                    assertTrue("对端确实收到本端开场，不能用未连接代替零回包", car.getInputStream().read() >= 0)
                    waiter.start()
                    assertTrue(waiting.await(1, TimeUnit.SECONDS))
                    channel.close()
                    waiter.join(1000)
                    assertFalse("取消必须解除真实等待", waiter.isAlive)
                    assertFalse(result.get())
                    assertFalse(channel.silentNegotiationTimedOut)
                    assertTrue(channel.failureDescription("取消").contains("本地主动关闭"))
                } finally {
                    channel.close()
                    waiter.interrupt()
                    waiter.join(1000)
                }
            }
            assertNewConnectionWorks()
        }
        assertWorkersExited("零接收-")
    }

    /** 区分 EOF 和本地取消，旧队列不进入新会话 */
    @Test
    fun actualPeerEofDoesNotPoisonNewSocketOrControlQueue() {
        withSockets { phone, car ->
            val channel = Iap2LinkChannel(stream(phone), name = "真实EOF")
            try {
                assertTrue(car.getInputStream().read() >= 0)
                car.shutdownOutput()
                assertFalse(channel.awaitReady(1000))
                assertFalse(channel.isAlive)
                assertTrue(channel.failureDescription("协商").contains("对端关闭"))
                assertTrue(channel.canRetryMfiAfterDisconnect)
            } finally {
                channel.close()
            }
        }
        assertNewConnectionWorks()
        assertWorkersExited("真实EOF")
    }

    /** 两端使用生产协议引擎，检查完整同步和双向收发 */
    private fun assertNewConnectionWorks() {
        withSockets { phone, car ->
            val sender = Iap2LinkChannel(stream(phone), name = "恢复手机")
            val receiver = Iap2LinkChannel(stream(car), name = "恢复车机")
            try {
                assertTrue(sender.awaitReady(2000))
                assertTrue(receiver.awaitReady(2000))
                val request = Iap2Csm.encode(0x1d01)
                val reply = Iap2Csm.encode(0x1d02)
                assertTrue(sender.sendControl(request))
                assertArrayEquals(request, receiver.recvControl(1000))
                assertTrue(receiver.sendControl(reply))
                assertArrayEquals(reply, sender.recvControl(1000))
            } finally {
                sender.close()
                receiver.close()
            }
        }
    }

    /** 生产字节流封装通过本机 TCP 传输 */
    private fun stream(socket: Socket): DuplexStream =
        StreamDuplexStream(socket.getInputStream(), socket.getOutputStream()) { socket.close() }

    /**
     * 仅监听回环地址，有界接受且无远程通信
     */
    private fun withSockets(action: (Socket, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 2000
            Socket(listener.inetAddress, listener.localPort).use { phone ->
                listener.accept().use { car ->
                    car.soTimeout = 2000
                    action(phone, car)
                }
            }
        }
    }

    /** 等待旧线程退出，不只依赖 daemon 标记 */
    private fun assertWorkersExited(prefix: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith(prefix) } &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(10)
        }
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.startsWith(prefix) })
    }
}
