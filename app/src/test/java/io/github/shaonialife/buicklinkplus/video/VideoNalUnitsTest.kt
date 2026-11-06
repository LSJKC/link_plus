package io.github.shaonialife.buicklinkplus.video

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** 逐字节检查视频封装 */
class VideoNalUnitsTest {
    /**
     * 混合起始码按各自 NAL 长度输出，不修改负载内容
     */
    @Test
    fun mixedStartCodesPreservePayloads() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 3, 0x65, 0x11, 0x22, 0, 0, 0, 2, 0x41, 0x33),
            VideoNalUnits.lengthPrefixed(byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0, 0, 1, 0x41, 0x33)),
        )
    }

    /**
     * 无起始码的单个 NAL 保持原兼容行为
     */
    @Test
    fun rawNalGetsOneLengthPrefix() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 0x12), VideoNalUnits.lengthPrefixed(byteArrayOf(0x65, 0x12)))
    }

    /**
     * 连续与尾部空起始码不产生空 NAL
     */
    @Test
    fun emptyNalsAreSkipped() {
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65),
            VideoNalUnits.lengthPrefixed(byteArrayOf(0, 0, 1, 0, 0, 0, 1, 0x65, 0, 0, 1)),
        )
        assertArrayEquals(byteArrayOf(), VideoNalUnits.lengthPrefixed(byteArrayOf()))
        assertArrayEquals(byteArrayOf(), VideoNalUnits.lengthPrefixed(byteArrayOf(0, 0, 1)))
    }

    /** 长 NAL 按大端写入完整长度 */
    @Test
    fun longNalUsesAllLengthBytes() {
        val payload = ByteArray(300) { 0x55 }
        assertArrayEquals(
            byteArrayOf(0, 0, 1, 44) + payload,
            VideoNalUnits.lengthPrefixed(byteArrayOf(0, 0, 0, 1) + payload),
        )
    }
}
