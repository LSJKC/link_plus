package io.github.shaonialife.buicklinkplus.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CarLifeVideoPipeline 纯 JVM 测试：不触碰 android.*，用 [SyntheticH264] 构造 Annex-B 码流。
 *
 * 覆盖：普通帧透传、SPS/PPS 缓存、裸 IDR 前置 CSD、非关键帧不改写、尺寸变化只在变化时回调、
 * reset 清理、offset 语义与非法区间忽略。
 */
class CarLifeVideoPipelineTest {
    @Test
    fun passesThroughOrdinaryFrame() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val frame = SyntheticH264.nonKeyAnnexB()
        pipeline.feed(frame, 0, frame.size, 1234L, false)

        assertEquals(1, listener.frames.size)
        val record = listener.frames.first()
        assertEquals(0, record.offset)
        assertEquals(frame.size, record.length)
        assertEquals(1234L, record.ptsMs)
        assertFalse(record.keyFrame)
        assertArrayEquals(frame, record.bytes)
        assertTrue(listener.sizes.isEmpty())
        assertFalse(pipeline.hasCsd)
        assertNull(pipeline.streamSize)
        assertNull(pipeline.sps())
        assertNull(pipeline.pps())
    }

    @Test
    fun cachesSpsAndPpsAndParsesStreamSize() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val frame = SyntheticH264.csd(80, 45) + SyntheticH264.idrAnnexB()
        pipeline.feed(frame, 0, frame.size, 10L, true)

        assertTrue(pipeline.hasCsd)
        assertArrayEquals(SyntheticH264.spsAnnexB(80, 45, withFollowingPps = false), pipeline.sps())
        assertArrayEquals(SyntheticH264.ppsAnnexB(), pipeline.pps())
        assertEquals(1280 to 720, pipeline.streamSize)
        assertEquals(listOf(1280 to 720), listener.sizes)
        // 本身带 CSD 的帧不改写，原样转发。
        assertEquals(1, listener.frames.size)
        assertEquals(frame.size, listener.frames.first().length)
        assertArrayEquals(frame, listener.frames.first().bytes)
    }

    @Test
    fun prependsCachedCsdToBareIdr() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val csd = SyntheticH264.csd(80, 45)
        val first = csd + SyntheticH264.idrAnnexB()
        pipeline.feed(first, 0, first.size, 1L, true)
        listener.frames.clear()

        // 引擎后续周期性 IDR 是裸片：没有 SPS/PPS 也必须补上，否则后建解码器永远配置不出来。
        val bareIdr = SyntheticH264.idrAnnexB(payloadSize = 64)
        pipeline.feed(bareIdr, 0, bareIdr.size, 2L, true)

        assertEquals(1, listener.frames.size)
        val forwarded = listener.frames.first()
        val expected = csd + bareIdr
        assertEquals(expected.size, forwarded.length)
        assertEquals(0, forwarded.offset)
        assertArrayEquals(expected, forwarded.bytes)
        assertTrue(forwarded.keyFrame)
        assertEquals(2L, forwarded.ptsMs)
    }

    @Test
    fun doesNotModifyNonKeyFramesEvenWithCachedCsd() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val csdFrame = SyntheticH264.csd(80, 45) + SyntheticH264.idrAnnexB()
        pipeline.feed(csdFrame, 0, csdFrame.size, 1L, true)
        listener.frames.clear()

        val nonKey = SyntheticH264.nonKeyAnnexB()
        pipeline.feed(nonKey, 0, nonKey.size, 2L, false)

        assertEquals(1, listener.frames.size)
        assertArrayEquals(nonKey, listener.frames.first().bytes)
        assertEquals(nonKey.size, listener.frames.first().length)
    }

    @Test
    fun streamSizeCallbackFiresOnlyOnChange() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val first = SyntheticH264.csd(80, 45) + SyntheticH264.idrAnnexB()
        pipeline.feed(first, 0, first.size, 1L, true)
        // 同一分辨率再来一帧：不应重复回调。
        val same = SyntheticH264.csd(80, 45) + SyntheticH264.idrAnnexB()
        pipeline.feed(same, 0, same.size, 2L, true)
        assertEquals(listOf(1280 to 720), listener.sizes)

        // SPS 分辨率变化：必须再回调一次，并更新 streamSize。
        val resized = SyntheticH264.csd(64, 48) + SyntheticH264.idrAnnexB()
        pipeline.feed(resized, 0, resized.size, 3L, true)
        assertEquals(listOf(1280 to 720, 1024 to 768), listener.sizes)
        assertEquals(1024 to 768, pipeline.streamSize)
    }

    @Test
    fun doesNotReportImplausibleSpsSize() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        // 512*16=8192 宽、15*16=240 高：宽高比 34 远超合理范围，按解析异常处理，不上报尺寸。
        val frame = SyntheticH264.csd(512, 15) + SyntheticH264.idrAnnexB()
        pipeline.feed(frame, 0, frame.size, 1L, true)

        assertTrue(listener.sizes.isEmpty())
        assertNull(pipeline.streamSize)
        // CSD 仍应被缓存（尺寸不可信不妨碍解码器用 CSD 配置）。
        assertTrue(pipeline.hasCsd)
    }

    @Test
    fun resetClearsCachedCsdAndStreamSize() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val frame = SyntheticH264.csd(80, 45) + SyntheticH264.idrAnnexB()
        pipeline.feed(frame, 0, frame.size, 1L, true)
        assertTrue(pipeline.hasCsd)
        assertEquals(1280 to 720, pipeline.streamSize)

        pipeline.reset()
        assertFalse(pipeline.hasCsd)
        assertNull(pipeline.streamSize)
        assertNull(pipeline.sps())
        assertNull(pipeline.pps())

        listener.frames.clear()
        val bareIdr = SyntheticH264.idrAnnexB()
        pipeline.feed(bareIdr, 0, bareIdr.size, 2L, true)
        // 缓存已清空：裸 IDR 不能再前置 CSD。
        assertArrayEquals(bareIdr, listener.frames.first().bytes)
    }

    @Test
    fun respectsOffsetIntoCallerBuffer() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        val frame = SyntheticH264.nonKeyAnnexB()
        val buffer = byteArrayOf(9, 9, 9, 9, 9) + frame + byteArrayOf(7, 7)
        pipeline.feed(buffer, 5, frame.size, 42L, false)

        assertEquals(1, listener.frames.size)
        val record = listener.frames.first()
        assertEquals(5, record.offset)
        assertEquals(frame.size, record.length)
        assertArrayEquals(frame, record.bytes)
    }

    @Test
    fun ignoresInvalidRange() {
        val listener = RecordingListener()
        val pipeline = CarLifeVideoPipeline(listener)
        pipeline.feed(ByteArray(4), 0, 10, 0L, false)
        pipeline.feed(ByteArray(4), -1, 2, 0L, false)
        pipeline.feed(ByteArray(4), 0, 0, 0L, true)
        assertTrue(listener.frames.isEmpty())
        assertTrue(listener.sizes.isEmpty())
    }

    /** 记录管线回调；帧内容按 offset/length 拷贝，同时保留原始 offset 供语义断言。 */
    private class RecordingListener : CarLifeVideoPipeline.Listener {
        class Frame(
            val bytes: ByteArray,
            val offset: Int,
            val length: Int,
            val ptsMs: Long,
            val keyFrame: Boolean,
        )

        val frames = ArrayList<Frame>()

        val sizes = ArrayList<Pair<Int, Int>>()

        override fun onFrame(
            data: ByteArray,
            offset: Int,
            length: Int,
            ptsMs: Long,
            keyFrame: Boolean,
        ) {
            frames += Frame(data.copyOfRange(offset, offset + length), offset, length, ptsMs, keyFrame)
        }

        override fun onStreamSize(
            width: Int,
            height: Int,
        ) {
            sizes += width to height
        }
    }
}
