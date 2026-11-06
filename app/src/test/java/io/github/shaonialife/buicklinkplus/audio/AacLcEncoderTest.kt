package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查 ADTS 头各字段和负载封装 */
class AacLcEncoderTest {
    /** 裸 AAC 解码配置包含采样率和声道 */
    @Test fun decoderConfigurationMatchesRawAac() {
        org.junit.Assert.assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacLcEncoder.audioSpecificConfig(48_000, 2))
        org.junit.Assert.assertArrayEquals(byteArrayOf(0x12, 0x10), AacLcEncoder.audioSpecificConfig(44_100, 2))
    }

    /**
     * 自检剥离传输封装但保留裸帧内容及不完整帧
     */
    @Test fun decoderAcceptsRawAndAdtsPayloads() {
        val raw = byteArrayOf(0x21, 0x11, 0x45, 0)
        org.junit.Assert.assertArrayEquals(raw, AacLcEncoder.decoderPayload(raw))
        org.junit.Assert.assertArrayEquals(raw, AacLcEncoder.decoderPayload(AacLcEncoder.adtsHeader(raw.size, 48_000, 2) + raw))
        val partial = byteArrayOf(0xff.toByte(), 0xf1.toByte())
        org.junit.Assert.assertArrayEquals(partial, AacLcEncoder.decoderPayload(partial))
    }

    /** 从帧头解析 13 位帧长。 */
    private fun frameLength(header: ByteArray): Int =
        ((header[3].toInt() and 0x03) shl 11) or
            ((header[4].toInt() and 0xFF) shl 3) or
            ((header[5].toInt() and 0xFF) shr 5)

    /** 从帧头解析声道配置。 */
    private fun channelConfig(header: ByteArray): Int = ((header[2].toInt() and 0x01) shl 2) or ((header[3].toInt() and 0xFF) shr 6)

    @Test
    fun adtsHeaderFor44100Stereo() {
        val payload = 592
        val header = AacLcEncoder.adtsHeader(payload, 44_100, 2)

        assertEquals(7, header.size)
        // 同步字 FFF + MPEG-4 + layer 0 + protection_absent=1
        assertEquals(0xFF, header[0].toInt() and 0xFF)
        assertEquals(0xF1, header[1].toInt() and 0xFF)
        // profile = AAC-LC（存 AOT-1 = 1）
        assertEquals(1, (header[2].toInt() and 0xFF) shr 6)
        // 采样率索引：44100 → 4
        assertEquals(4, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        // 声道配置：立体声 → 2
        assertEquals(2, channelConfig(header))
        // 帧长（含 7 字节头）
        assertEquals(payload + 7, frameLength(header))
        // buffer_fullness = 0x7FF（11 位：h5 低 5 位 + h6 高 6 位），num_raw_data_blocks = 0
        assertEquals(
            0x7FF,
            ((header[5].toInt() and 0x1F) shl 6) or ((header[6].toInt() and 0xFC) shr 2),
        )
        assertEquals(0, header[6].toInt() and 0x03)
    }

    @Test
    fun adtsHeaderTracksSampleRateAndChannels() {
        // 48k → 索引 3；单声道 → 1
        val header = AacLcEncoder.adtsHeader(100, 48_000, 1)
        assertEquals(3, ((header[2].toInt() and 0xFF) shr 2) and 0x0F)
        assertEquals(1, channelConfig(header))
        assertEquals(107, frameLength(header))
    }

    @Test
    fun frequencyIndexTable() {
        assertEquals(4, AacLcEncoder.frequencyIndex(44_100))
        assertEquals(3, AacLcEncoder.frequencyIndex(48_000))
        assertEquals(8, AacLcEncoder.frequencyIndex(16_000))
        // 未收录的采样率退回 44100，避免写出非法索引
        assertEquals(4, AacLcEncoder.frequencyIndex(37_000))
    }

    @Test
    fun tuningIsFixedToRawFrames() {
        // 定案：裸 AAC 帧 + spf=1024 + 到达时间戳，只有这一组，不再提供切换
        assertEquals(1, AudioTuning.entries.size)
        assertTrue(!AudioTuning.RAW_SPF.adts)
        assertEquals(1024, AudioTuning.RAW_SPF.spf)
        assertTrue(!AudioTuning.RAW_SPF.highAccuracyTimestamps)
    }
}
