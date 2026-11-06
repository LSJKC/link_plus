package io.github.shaonialife.buicklinkplus.audio

/**
 * 音频同步包，每秒发往 controlPort，关联 RTP 和 NTP 时间
 * 20B 大端格式：[0x80][0xD4][seq(2)][当前 RTP(4)][NTP(8)][下一包 RTP(4)]
 * 首包首字节为 0x90；NTP 与 TimingServer 共用单调时钟
 * 格式参考 Unofficial AirPlay Specification 的 audio/rtp_streams
 */
object AudioSyncPacket {
    /** 同步包定长（规范：8B 头 + 8B NTP + 4B 下一包时间戳）。 */
    const val SIZE = 20

    /** RTP 负载类型：84 = 时间同步（AirPlay 私有 RTCP 类型）。 */
    private const val PT_SYNC = 0xD4

    /** 版本 2 + Marker=1。 */
    private const val BASE_FIRST_BYTE = 0x80

    /** 扩展位（X）：RECORD/FLUSH 之后的首个同步包要置上。 */
    private const val EXTENSION_BIT = 0x10

    /**
     * 生成同步包
     *
     * @param sequence 独立的同步包序号
     * @param currentRtpTimestamp 与 ntp 对应的已发送包时间戳
     * @param nextRtpTimestamp 下一包时间戳
     * @param ntp 8 字节大端 NTP 时间
     * @param first 是否为 RECORD 后首包
     */
    fun build(
        sequence: Int,
        currentRtpTimestamp: Long,
        nextRtpTimestamp: Long,
        ntp: ByteArray,
        first: Boolean,
    ): ByteArray {
        require(ntp.size == 8) { "NTP 时间必须是 8 字节，实际 ${ntp.size}" }
        val packet = ByteArray(SIZE)
        packet[0] = (BASE_FIRST_BYTE or (if (first) EXTENSION_BIT else 0)).toByte()
        packet[1] = PT_SYNC.toByte()
        packet[2] = ((sequence ushr 8) and 0xFF).toByte()
        packet[3] = (sequence and 0xFF).toByte()
        writeBe32(packet, 4, currentRtpTimestamp)
        ntp.copyInto(packet, 8)
        writeBe32(packet, 16, nextRtpTimestamp)
        return packet
    }

    private fun writeBe32(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        val truncated = value.toInt()
        target[offset] = ((truncated ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((truncated ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((truncated ushr 8) and 0xFF).toByte()
        target[offset + 3] = (truncated and 0xFF).toByte()
    }
}
