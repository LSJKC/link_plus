package io.github.shaonialife.buicklinkplus.shizuku

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.Socket

/** 检查连接错误后的重新发现，以及预算、认证和取消边界 */
class AdbActivationRetryTest {
    /** 首轮端口拒绝，重新发现后只连接成功一次 */
    @Test
    fun refusedTransportRediscoversAndConnects() {
        val refusedPort = ServerSocket(0).use { it.localPort }
        var attempts = 0
        var retries = 0
        var time = 0L
        ServerSocket(0).use { server ->
            server.soTimeout = 2000
            val finished =
                awaitAdbActivation(
                    deadline = 90_000,
                    now = { time },
                    checkActive = {},
                    attempt = {
                        val port = if (++attempts == 1) refusedPort else server.localPort
                        AdbTransport("127.0.0.1", port).use { transport ->
                            Socket("127.0.0.1", transport.localPort).use { client ->
                                client.soTimeout = 2000
                                if (attempts == 1) {
                                    assertEquals(-1, client.getInputStream().read())
                                    val failure = checkNotNull(transport.failure)
                                    assertTrue(failure is ConnectException)
                                    throw EOFException("代理已关闭").apply { addSuppressed(failure) }
                                }
                                server.accept().use { peer ->
                                    peer.soTimeout = 2000
                                    client.getOutputStream().write(37)
                                    assertEquals(37, peer.getInputStream().read())
                                    peer.getOutputStream().write(51)
                                    assertEquals(51, client.getInputStream().read())
                                }
                            }
                        }
                        true
                    },
                    pause = { time += it },
                    onConnectionFailure = { retries++ },
                )
            assertTrue(finished)
        }
        assertEquals(2, attempts)
        assertEquals(1, retries)
        assertEquals(300L, time)
    }

    /**
     * 持续拒绝不能刷新预算，最后一段等待只使用剩余时间并保留真实错误
     */
    @Test
    fun repeatedRefusalExhaustsOriginalBudget() {
        var time = 0L
        var attempts = 0
        val pauses = mutableListOf<Long>()
        val failure = ConnectException("连接被拒绝")
        val thrown =
            assertThrows(ConnectException::class.java) {
                awaitAdbActivation(
                    650,
                    { time },
                    {},
                    {
                        attempts++
                        throw failure
                    },
                    {
                        pauses.add(it)
                        time += it
                    },
                    {},
                )
            }
        assertSame(failure, thrown)
        assertEquals(3, attempts)
        assertEquals(listOf(300L, 300L, 50L), pauses)
        assertEquals(650L, time)
    }

    /**
     * 拒绝后再无端点也不能将原连接错误丢成单纯的未发现
     */
    @Test
    fun noEndpointAfterRefusalKeepsFailure() {
        var time = 0L
        var attempts = 0
        val failure = IOException("握手已结束").apply { addSuppressed(ConnectException()) }
        val thrown =
            assertThrows(IOException::class.java) {
                awaitAdbActivation(
                    600,
                    { time },
                    {},
                    { if (++attempts == 1) throw failure else false },
                    { time += it },
                    {},
                )
            }
        assertSame(failure, thrown)
        assertEquals(2, attempts)
    }

    /**
     * 从未发现端点保留原有等待结束语义
     */
    @Test
    fun missingEndpointReturnsFalse() {
        var time = 0L
        assertFalse(awaitAdbActivation(600, { time }, {}, { false }, { time += it }, {}))
        assertEquals(600L, time)
    }

    /**
     * 单轮已耗尽预算时不再等待或启动第二次连接
     */
    @Test
    fun expiredAttemptStopsImmediately() {
        var time = 0L
        val failure = ConnectException()
        assertSame(
            failure,
            assertThrows(ConnectException::class.java) {
                awaitAdbActivation(600, { time }, {}, {
                    time = 600
                    throw failure
                }, { error("不能再等待") }, { error("不能再重试") })
            },
        )
    }

    /**
     * 认证、权限、中断及普通 IO 错误不得被附加连接错误覆盖
     */
    @Test
    fun permanentFailuresAreNotRetried() {
        val failures =
            listOf(
                AdbAuthenticationFailedException(),
                AdbPairingRequiredException("需重新配对"),
                SecurityException("权限不足"),
                InterruptedException("操作已取消"),
                java.io.InterruptedIOException("传输已中断"),
            ).map { IOException("外层错误", it).apply { addSuppressed(ConnectException()) } } +
                listOf(EOFException("无明确连接原因"), IllegalStateException("服务交接失败"))
        failures.forEach { failure ->
            val thrown =
                assertThrows(Exception::class.java) {
                    awaitAdbActivation(600, { 0 }, {}, { throw failure }, { error("不能等待") }, { error("不能重试") })
                }
            assertSame(failure, thrown)
        }
    }

    /**
     * 失败回调触发取消后，不等待、不进入下一轮发现
     */
    @Test
    fun cancellationPreventsRediscovery() {
        var active = true
        var attempts = 0
        val cancellation = IllegalStateException("请求已取消")
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                awaitAdbActivation(
                    600,
                    { 0 },
                    { if (!active) throw cancellation },
                    {
                        attempts++
                        throw ConnectException()
                    },
                    { error("取消后不能等待") },
                    { active = false },
                )
            }
        assertSame(cancellation, thrown)
        assertEquals(1, attempts)
    }

    /**
     * 等待中的中断直接透传，不清除或吞掉取消结果
     */
    @Test
    fun interruptedPauseStopsRetry() {
        var attempts = 0
        assertThrows(InterruptedException::class.java) {
            awaitAdbActivation(600, { 0 }, {}, {
                attempts++
                throw ConnectException()
            }, { throw InterruptedException() }, {})
        }
        assertEquals(1, attempts)
    }

    /**
     * 成功返回前若请求已失效，不能提交旧请求的成功结果
     */
    @Test
    fun staleSuccessIsRejected() {
        var active = true
        assertThrows(IllegalStateException::class.java) {
            awaitAdbActivation(600, { 0 }, { check(active) }, {
                active = false
                true
            }, { error("不能等待") }, {})
        }
    }

    /** 异常链有环时仍能提取连接错误并结束 */
    @Test
    fun cyclicExceptionGraphTerminates() {
        val failure = IOException("外层错误")
        failure.addSuppressed(
            IOException("内层错误").apply {
                addSuppressed(failure)
                addSuppressed(ConnectException())
            },
        )
        var time = 0L
        var attempts = 0
        assertTrue(awaitAdbActivation(600, { time }, {}, { if (++attempts == 1) throw failure else true }, { time += it }, {}))
        assertEquals(2, attempts)
    }
}
