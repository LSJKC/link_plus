package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSampleStatsTest {
    /** 静音或不完整采样不产生信号峰值 */
    @Test fun silenceAndPartialSamplesStayZero() {
        assertEquals(AudioSampleStats(0, 0), AudioSampleStats.inspect(ByteArray(20), 20))
        assertEquals(AudioSampleStats(0, 0), AudioSampleStats.inspect(byteArrayOf(127), 1))
    }

    /**
     * 小端正负采样及最小负数正确计数，缓冲尾部未读取内容不参与统计
     */
    @Test fun signedLittleEndianSamplesRespectReadBoundary() {
        val bytes = byteArrayOf(0, 0, 0, 128.toByte(), 1, 0, 127)
        assertEquals(AudioSampleStats(32768, 66), AudioSampleStats.inspect(bytes, 6))
        assertEquals(AudioSampleStats(0, 0), AudioSampleStats.inspect(bytes, 2))
    }
}
