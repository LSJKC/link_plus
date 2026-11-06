package io.github.shaonialife.buicklinkplus.iap2

/**
 * iAP2 链路包编解码，蓝牙和 USB 共用格式
 * FF5A、u16 长度、control、seq、ack、sessionId、头校验和、载荷、载荷校验和
 * 校验范围内的字节和模 256 为 0；FF55 用于协商探测
 * 格式参考 carplay-wifi-extractor link_layer.py 和 Oligo Security《Pwn My Ride》
 */
object Iap2LinkPacket {
    const val START_DATA = 0xFF5A
    const val START_SYN = 0xFF55

    const val CTRL_SYN: Int = 0x80
    const val CTRL_ACK: Int = 0x40
    const val CTRL_EAK: Int = 0x20
    const val CTRL_RST: Int = 0x10

    /** 帧头为 FF5A、2B 长度、control、seq、ack、sessionId 和头校验和 */
    const val HEADER_BYTES = 9
    const val CHECKSUM_BYTES = 1

    const val SESSION_CONTROL = 0
    const val SESSION_DATA = 1

    data class LinkPacket(
        val control: Int,
        val seq: Int,
        val ack: Int,
        val sessionId: Int,
        val payload: ByteArray,
    ) {
        val isSyn: Boolean get() = control and CTRL_SYN != 0
        val isAck: Boolean get() = control and CTRL_ACK != 0

        override fun equals(other: Any?): Boolean =
            other is LinkPacket &&
                other.control == control &&
                other.seq == seq &&
                other.ack == ack &&
                other.sessionId == sessionId &&
                other.payload.contentEquals(payload)

        override fun hashCode(): Int = control * 31 + seq * 961 + ack * 31 + sessionId + payload.contentHashCode()
    }

    /** 所有字节（含校验和）相加 mod 256 应为 0；因此校验和 = -(载荷和) & 0xFF。 */
    fun checksum(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Int {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return (-sum) and 0xFF
    }

    fun verify(
        bytes: ByteArray,
        from: Int = 0,
        to: Int = bytes.size,
    ): Boolean {
        var sum = 0
        for (index in from until to) sum += bytes[index].toInt() and 0xFF
        return sum and 0xFF == 0
    }

    fun encode(packet: LinkPacket): ByteArray {
        val totalLen = HEADER_BYTES + packet.payload.size + CHECKSUM_BYTES
        val out = ByteArray(totalLen)
        writeU16(out, 0, START_DATA)
        writeU16(out, 2, totalLen)
        out[4] = packet.control.toByte()
        out[5] = packet.seq.toByte()
        out[6] = packet.ack.toByte()
        out[7] = packet.sessionId.toByte()
        out[8] = checksum(out, 0, 8).toByte()
        packet.payload.copyInto(out, HEADER_BYTES)
        out[out.size - 1] = checksum(out, HEADER_BYTES, out.size - 1).toByte()
        return out
    }

    /** 解析定长 FF5A 包，流重组由链路状态机处理 */
    fun decode(bytes: ByteArray): LinkPacket? {
        if (bytes.size < HEADER_BYTES + CHECKSUM_BYTES) return null
        if (readU16(bytes, 0) != START_DATA) return null
        if (!verify(bytes, 0, HEADER_BYTES)) return null
        val length = readU16(bytes, 2)
        if (length != bytes.size) return null
        if (!verify(bytes, HEADER_BYTES, bytes.size)) return null
        return LinkPacket(
            control = bytes[4].toInt() and 0xFF,
            seq = bytes[5].toInt() and 0xFF,
            ack = bytes[6].toInt() and 0xFF,
            sessionId = bytes[7].toInt() and 0xFF,
            payload = bytes.copyOfRange(HEADER_BYTES, bytes.size - 1),
        )
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}
