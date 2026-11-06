// 新增测试：JVM 假引擎集成测试（无设备）。无移植来源。
package io.github.shaonialife.buicklinkplus.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P1A **无设备** JVM 集成测试：真实 [CarLifeHuClient] ↔ 假 CarLife+ 引擎（[FakeCarLifeEngine]），
 * 全部走 127.0.0.1 的真实 TCP socket。
 *
 * ## 覆盖的链路（每条都由断言钉住）
 *
 * 1. `start()` 返回 true：客户端连上 7 个端口，完成 `98305(协议 3.0) → 65538 match=1` 版本协商；
 * 2. 控制通道双向：`98343 统计信息 → 65540 手机设备信息 → 98307 HU_INFO`（车机画布回传）、
 *    `98386 特征配置 → 65570 已确认`、`98311 编码参数申请 → 65544 确认`、
 *    `65574 模块状态(status=0) → 98344 MODULE_CONTROL` 回包；
 * 3. 视频通道：收到 `65544` 后客户端发 `98313`，假引擎在 8240 推
 *    `[12B 帧头] + SPS/PPS/IDR` 与 P 帧；客户端回调全部触发：
 *    - `onVideoParams(1280,720,20)`（65544 回显值）；
 *    - `onStreamSize(1280,720)`（**由 SPS 解析**，不是回显值）；
 *    - `onVideoFrame(idr, keyFrame=true, pts=1234567)`、`onVideoFrame(p, keyFrame=false, pts=1234607)`，
 *      且帧负载与引擎写入的 Annex-B 字节**逐字节一致**；
 * 4. `isRunning` 生命周期：握手收帧期间为 true，`stop()` 后为 false。
 *
 * ## 明确**不**覆盖（这些仍必须真机验收）
 *
 * - MediaCodec/Surface 解码、`CarLifeVideoDecoder` 与 VideoSender Surface 落地（需要 Android Codec）；
 * - `CarLifeScreenSource` 的日志门面（"CARLIFE: carlife connected" / "CARLIFE-VIDEO: decoder out"）；
 * - 真 CarLife+ 引擎的白名单降档（65544 回显 ≠ SPS 真实尺寸）、加密鉴权分支、真机断链重连；
 * - 车机侧 AirPlay/蓝牙链路（本仓库 P2 范围）。
 *
 * ## 平台注意
 *
 * 客户端内部调用 `android.os.SystemClock.uptimeMillis()`；本地 JVM 单测依赖
 * `android.testOptions.unitTests.isReturnDefaultValues = true`（app/build.gradle.kts），
 * 否则 stub android.jar 会抛 "Method ... not mocked"。返回值恒为 0 只会让 5s 帧率统计、
 * 无帧看门狗等定时逻辑保持「刚上线」状态，不影响握手与收帧断言。
 *
 * 端口被占用时通过 JUnit Assumption 跳过（明确提示），不会挂起到超时。
 */
class FakeCarLifeEngineTest {
    @Test(timeout = 30_000)
    fun handshakeAndH264FramesFireProductionCallbacks() {
        val engine = FakeCarLifeEngine()
        try {
            try {
                engine.start()
            } catch (error: IOException) {
                assumeNoException(
                    "本机 127.0.0.1 的 CarLife 端口（7240/8240/9240/9241/9242/9340/9440）" +
                        "无法全部监听（可能被占用），跳过假引擎集成测试（不会挂起）",
                    error,
                )
            }

            val logLines = Collections.synchronizedList(ArrayList<String>())
            val videoParams = AtomicReference<Triple<Int, Int, Int>?>(null)
            val streamSize = AtomicReference<Pair<Int, Int>?>(null)
            val frames = Collections.synchronizedList(ArrayList<CapturedFrame>())
            val paramsLatch = CountDownLatch(1)
            val sizeLatch = CountDownLatch(1)
            val framesLatch = CountDownLatch(2)

            val client =
                CarLifeHuClient(
                    host = FakeCarLifeEngine.HOST,
                    log = { line -> logLines += line },
                    onVideoFrame = { data, keyFrame, sourceTimestampMs ->
                        frames += CapturedFrame(data, keyFrame, sourceTimestampMs)
                        framesLatch.countDown()
                    },
                    onVideoParams = { width, height, fps ->
                        videoParams.set(Triple(width, height, fps))
                        paramsLatch.countDown()
                    },
                    onStreamSize = { width, height ->
                        streamSize.set(width to height)
                        sizeLatch.countDown()
                    },
                    initialWidth = REQUESTED_WIDTH,
                    initialHeight = REQUESTED_HEIGHT,
                    requestedFps = REQUESTED_FPS,
                )

            try {
                assertTrue("start() 必须在 98305→65538 握手成功后返回 true", client.start())
                assertTrue(
                    "未在 ${LATCH_TIMEOUT_SECONDS}s 内收到 65544 触发的 onVideoParams",
                    paramsLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertTrue(
                    "未在 ${LATCH_TIMEOUT_SECONDS}s 内从 SPS 解析出 onStreamSize",
                    sizeLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertTrue(
                    "未在 ${LATCH_TIMEOUT_SECONDS}s 内收到 IDR+P 两帧",
                    framesLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
                assertTrue("握手/收帧期间 isRunning 应为 true", client.isRunning)
            } finally {
                client.stop()
            }
            assertFalse("client.stop() 后 isRunning 应为 false", client.isRunning)

            // ---- 生产回调内容 ----
            assertEquals(
                "onVideoParams 应是 65544 回显的协商档位",
                Triple(REQUESTED_WIDTH, REQUESTED_HEIGHT, REQUESTED_FPS),
                videoParams.get(),
            )
            assertEquals(
                "onStreamSize 应是 SPS 解析出的码流真实尺寸",
                REQUESTED_WIDTH to REQUESTED_HEIGHT,
                streamSize.get(),
            )
            assertEquals(
                "SPS 解析后 videoSize 应切换为码流真实尺寸（触摸坐标基准）",
                REQUESTED_WIDTH to REQUESTED_HEIGHT,
                client.videoSize,
            )
            assertTrue("SPS 解析成功后 videoSizeConfirmed 应为 true", client.videoSizeConfirmed)
            assertEquals(
                "negotiatedSize 应是 65544 回显值",
                REQUESTED_WIDTH to REQUESTED_HEIGHT,
                client.negotiatedSize,
            )

            val captured = synchronized(frames) { frames.toList() }
            assertTrue("至少应收到 IDR/P 两帧，实际 ${captured.size} 帧", captured.size >= 2)
            val idr = captured[0]
            assertTrue("首帧应是 IDR（keyFrame=true）", idr.keyFrame)
            assertEquals("IDR 的 pts 应原样透传帧头时间戳", FakeCarLifeEngine.IDR_TIMESTAMP_MS, idr.sourceTimestampMs)
            assertArrayEquals("IDR 负载应与假引擎写入的 Annex-B 字节完全一致", engine.idrFramePayload, idr.data)
            val pFrame = captured[1]
            assertFalse("第二帧应是 P 帧（keyFrame=false）", pFrame.keyFrame)
            assertEquals("P 帧的 pts 应原样透传帧头时间戳", FakeCarLifeEngine.P_TIMESTAMP_MS, pFrame.sourceTimestampMs)
            assertArrayEquals("P 帧负载应与假引擎写入的 Annex-B 字节完全一致", engine.pFramePayload, pFrame.data)
            assertTrue("假引擎应已写出至少 2 帧，实际 ${engine.videoFramesSent}", engine.videoFramesSent >= 2)

            // ---- 假引擎侧观测到的线上字节/消息序列 ----
            assertEquals(
                "98305 载荷必须是协议 3.0（08 03 10 00）",
                listOf(0x08, 0x03, 0x10, 0x00),
                engine.protocolVersionPayload?.map { it.toInt() and 0xFF },
            )
            assertEquals(
                "98311 应申请 1280x720@20",
                Triple(REQUESTED_WIDTH, REQUESTED_HEIGHT, REQUESTED_FPS),
                engine.requestedEncoderInfo,
            )
            assertContainsInOrder(
                listOf(
                    CarLifeProtocol.HU_PROTOCOL_VERSION,
                    CarLifeProtocol.HU_STATISTICS,
                    CarLifeProtocol.HU_INFO,
                    CarLifeProtocol.HU_VIDEO_ENC_INIT,
                    CarLifeProtocol.VIDEO_ENC_START,
                ),
                engine.receivedCmdTypes.toList(),
            )
            assertTrue(
                "客户端应就 status=0 的模块回复 98344 MODULE_CONTROL，实际收到 ${engine.receivedCmdTypes}",
                engine.receivedCmdTypes.contains(CarLifeProtocol.HU_MODULE_CONTROL),
            )
            val huInfoText = engine.huInfoPayload?.toString(Charsets.UTF_8).orEmpty()
            assertTrue("98307 HU_INFO 应携带车机画布 1280x720，实际 $huInfoText", huInfoText.contains("1280x720"))

            // ---- 生产日志里的关键证据（对应 CarLifeHuClient 的握手/收帧节点） ----
            val logs = synchronized(logLines) { logLines.toList() }
            assertTrue("日志应出现「版本匹配成功」，实际日志：$logs", logs.any { it.contains("版本匹配成功") })
            assertTrue("日志应出现「手机确认编码参数」，实际日志：$logs", logs.any { it.contains("手机确认编码参数") })
            assertTrue("日志应出现 SPS「码流真实分辨率」，实际日志：$logs", logs.any { it.contains("码流真实分辨率") })
            assertNull("假引擎侧不应有非预期异常：${engine.failure}", engine.failure)

            // 可回溯证据：会写进 JUnit XML 的 system-out，评审时不必重跑即可核对线上字节。
            println("==== FakeCarLifeEngineTest 证据（全部走 127.0.0.1 TCP） ====")
            println("7240 命令序列 = ${engine.receivedCmdTypes}")
            println("98311 申请 = ${engine.requestedEncoderInfo} ／ 65544 确认 = ${client.negotiatedSize}")
            println("SPS 码流真实尺寸 = ${client.videoSize}（confirmed=${client.videoSizeConfirmed}）")
            println(
                "8240 收到 ${captured.size} 帧：IDR keyFrame=${idr.keyFrame} pts=${idr.sourceTimestampMs}， " +
                    "P keyFrame=${pFrame.keyFrame} pts=${pFrame.sourceTimestampMs}",
            )
            println("生产日志：")
            logs.forEach { println("  $it") }
        } finally {
            engine.stop()
        }
    }

    /** 断言 [expected] 是 [actual] 的**相对有序**子序列（允许中间夹杂其它消息）。 */
    private fun assertContainsInOrder(
        expected: List<Int>,
        actual: List<Int>,
    ) {
        var cursor = 0
        for (id in actual) {
            if (cursor < expected.size && expected[cursor] == id) cursor++
        }
        assertEquals(
            "命令序列缺少 ${expected.drop(cursor)}（期望按序 $expected，实际 $actual）",
            expected.size,
            cursor,
        )
    }

    /** 一次视频帧回调的快照。 */
    private data class CapturedFrame(
        val data: ByteArray,
        val keyFrame: Boolean,
        val sourceTimestampMs: Long,
    )

    private companion object {
        const val REQUESTED_WIDTH = 1280
        const val REQUESTED_HEIGHT = 720
        const val REQUESTED_FPS = 20
        const val LATCH_TIMEOUT_SECONDS = 10L
    }
}
