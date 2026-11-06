package io.github.shaonialife.buicklinkplus.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * H264Sps 解析测试：用位写入器构造合成 Annex-B SPS（profile_idc=66），覆盖 720p、
 * 1080p 裁剪、CarLife 实测非标 1412x654 与非法输入。
 *
 * 只依赖纯 JVM API，不触碰 android.*；[H264Sps] 是 internal，同包测试可直接访问。
 */
class H264SpsTest {
    @Test
    fun parsesBaselineSps1280x720() {
        // 1280/16=80 个宏块列，720/16=45 个宏块行（frame_mbs_only=1，无裁剪）。
        assertEquals(1280 to 720, H264Sps.parseAnnexB(SyntheticH264.spsAnnexB(80, 45)))
        assertEquals(1280 to 720, H264Sps.parseAnnexB(SyntheticH264.csd(80, 45)))
    }

    @Test
    fun parsesSameSizeFromNalWithoutStartCode() {
        val nal = SyntheticH264.spsNal(80, 45)
        assertEquals(1280 to 720, H264Sps.parseNal(nal))
    }

    @Test
    fun parsesCroppedSps1920x1080() {
        // 1080 不是 16 的倍数：68 个宏块行(1088) - 底部裁 4 个裁剪单位(4*2=8) = 1080。
        assertEquals(1920 to 1080, H264Sps.parseAnnexB(SyntheticH264.spsAnnexB(120, 68, cropBottom = 4)))
    }

    @Test
    fun parsesCarLifeSizedSps1412x654() {
        // CarLife 实测的内容层尺寸：89*16=1424 裁右 6*2=12 → 1412；41*16=656 裁底 1*2=2 → 654。
        val annexB = SyntheticH264.spsAnnexB(89, 41, cropRight = 6, cropBottom = 1)
        assertEquals(1412 to 654, H264Sps.parseAnnexB(annexB))
    }

    @Test
    fun parsesSps2560x1440() {
        assertEquals(2560 to 1440, H264Sps.parseAnnexB(SyntheticH264.spsAnnexB(160, 90)))
    }

    @Test
    fun findsSpsAfterLeadingNal() {
        // 真实首帧顺序可能是 IDR 在前、SPS/PPS 在后；扫描必须能跳过其它 NAL 找到 SPS。
        val frame = SyntheticH264.nonKeyAnnexB() + SyntheticH264.csd(80, 45)
        assertEquals(1280 to 720, H264Sps.parseAnnexB(frame))
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(H264Sps.parseNal(ByteArray(0)))
        assertNull(H264Sps.parseNal(byteArrayOf(0x67, 0x42, 0x00)))
        assertNull(H264Sps.parseAnnexB(ByteArray(0)))
        // 只有 P 帧、没有 SPS：扫描不到 SPS 必须返回 null 而不是乱猜。
        assertNull(H264Sps.parseAnnexB(SyntheticH264.nonKeyAnnexB()))
        // 全是 0 的伪 NAL：指数哥伦布读取会越界，必须被 runCatching 收敛成 null。
        assertNull(H264Sps.parseNal(ByteArray(16)))
    }

    @Test
    fun syntheticBuilderEscapesEmulationPreventionBytes() {
        // 00 00 后跟 00/01/02/03 时插入 0x03，这是 Annex-B 码流能被正确切分的前提。
        val escaped = SyntheticH264.escapeRbsp(byteArrayOf(0, 0, 0, 0, 1, 2))
        assertEquals(listOf(0, 0, 3, 0, 0, 3, 1, 2), escaped.map { it.toInt() and 0xFF })
        assertTrue(escaped.size > 6)
    }
}

/**
 * 合成 H.264 测试码流构造器（仅测试使用）。
 *
 * SPS 只实现非 High profile 的字段序列（CarLife 引擎常见 Baseline），足以让
 * [H264Sps] 解析出宏块数 × 16 - 裁剪的宽高；RBSP 尾部按规范写 rbsp_stop_one_bit 并对齐。
 */
internal object SyntheticH264 {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    private val HIGH_PROFILES =
        setOf(
            100,
            110,
            122,
            244,
            44,
            83,
            86,
            118,
            128,
            138,
            139,
            134,
            135,
        )

    /** 构造单个 SPS NAL（NAL 头 0x67 + 转义后的 RBSP，不含起始码）。 */
    fun spsNal(
        widthMbs: Int,
        heightMapUnits: Int,
        cropLeft: Int = 0,
        cropRight: Int = 0,
        cropTop: Int = 0,
        cropBottom: Int = 0,
        profileIdc: Int = 66,
        levelIdc: Int = 31,
    ): ByteArray {
        require(widthMbs > 0 && heightMapUnits > 0) { "宏块数必须为正" }
        require(profileIdc !in HIGH_PROFILES) { "合成 SPS 只实现非 High profile" }
        val writer = BitWriter()
        writer.u(8, profileIdc)
        writer.u(8, 0) // constraint_set0..5 + reserved
        writer.u(8, levelIdc)
        writer.ue(0) // seq_parameter_set_id
        writer.ue(0) // log2_max_frame_num_minus4
        writer.ue(0) // pic_order_cnt_type
        writer.ue(0) // log2_max_pic_order_cnt_lsb_minus4
        writer.ue(1) // max_num_ref_frames
        writer.u1(0) // gaps_in_frame_num_value_allowed_flag
        writer.ue(widthMbs - 1)
        writer.ue(heightMapUnits - 1)
        writer.u1(1) // frame_mbs_only_flag
        writer.u1(1) // direct_8x8_inference_flag
        if (cropLeft != 0 || cropRight != 0 || cropTop != 0 || cropBottom != 0) {
            writer.u1(1) // frame_cropping_flag
            writer.ue(cropLeft)
            writer.ue(cropRight)
            writer.ue(cropTop)
            writer.ue(cropBottom)
        } else {
            writer.u1(0)
        }
        writer.u1(0) // vui_parameters_present_flag
        return byteArrayOf(0x67.toByte()) + escapeRbsp(writer.toRbsp())
    }

    /**
     * 构造 Annex-B 形式的 SPS（含起始码）。
     *
     * [withFollowingPps] 为 true 时再拼一个 PPS NAL，模拟真实首帧里 SPS 后面紧跟 PPS 的布局；
     * 这同时避开 [H264Sps.parseAnnexB] 对「SPS 是最后一个 NAL」时末尾扫描截止位置的限制。
     */
    fun spsAnnexB(
        widthMbs: Int,
        heightMapUnits: Int,
        cropLeft: Int = 0,
        cropRight: Int = 0,
        cropTop: Int = 0,
        cropBottom: Int = 0,
        withFollowingPps: Boolean = true,
    ): ByteArray {
        val sps = START_CODE + spsNal(widthMbs, heightMapUnits, cropLeft, cropRight, cropTop, cropBottom)
        return if (withFollowingPps) sps + ppsAnnexB() else sps
    }

    fun ppsNal(): ByteArray = byteArrayOf(0x68.toByte(), 0xCE.toByte(), 0x3C, 0x80.toByte())

    fun ppsAnnexB(): ByteArray = START_CODE + ppsNal()

    /** SPS+PPS 的 CSD 前缀（等价于解码器 csd-0 + csd-1 拼起来）。 */
    fun csd(
        widthMbs: Int,
        heightMapUnits: Int,
    ): ByteArray = spsAnnexB(widthMbs, heightMapUnits, withFollowingPps = false) + ppsAnnexB()

    /** 裸 IDR（起始码 + 0x65，无 SPS/PPS）。 */
    fun idrAnnexB(payloadSize: Int = 48): ByteArray = START_CODE + byteArrayOf(0x65) + payload(payloadSize)

    /** 普通非关键帧（起始码 + 0x41，切片类型非 IDR）。 */
    fun nonKeyAnnexB(payloadSize: Int = 32): ByteArray = START_CODE + byteArrayOf(0x41) + payload(payloadSize)

    /** 防竞争字节插入：RBSP 内 00 00 后跟 00/01/02/03 时插入 0x03。 */
    fun escapeRbsp(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** 负载字节避开 0x00，防止意外构成起始码把模拟帧切断。 */
    private fun payload(size: Int): ByteArray = ByteArray(size) { index -> (0x81 + index % 0x30).toByte() }

    /** 按位写 RBSP：指数哥伦布 ue、定长 u、末尾 rbsp_stop_one_bit + 对齐。 */
    private class BitWriter {
        private val bits = StringBuilder()

        fun u1(bit: Int) {
            bits.append(if (bit != 0) '1' else '0')
        }

        fun u(
            count: Int,
            value: Int,
        ) {
            for (index in count - 1 downTo 0) {
                u1((value ushr index) and 1)
            }
        }

        fun ue(value: Int) {
            val codeNum = value + 1
            val length = codeNum.toString(2).length
            repeat(length - 1) { u1(0) }
            u(length, codeNum)
        }

        fun toRbsp(): ByteArray {
            u1(1)
            while (bits.length % 8 != 0) {
                u1(0)
            }
            val out = ByteArray(bits.length / 8)
            for (index in out.indices) {
                var value = 0
                for (offset in 0 until 8) {
                    value = (value shl 1) or if (bits[index * 8 + offset] == '1') 1 else 0
                }
                out[index] = value.toByte()
            }
            return out
        }
    }
}
