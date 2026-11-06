package io.github.shaonialife.buicklinkplus.airplay

/**
 * 控制通道加密帧：2B LE 长度 AAD、密文、16B tag
 * nonce 为 4B 零加 8B LE 计数器，收发密钥和计数器独立
 * 发送分片最多 1422 字节（1440 MTU 减 AAD 和 tag），参考 catplay AirPlayCipherCodec
 * 接收端不限制为此分片大小
 */
class ControlCipher(
    private val readKey: ByteArray,
    private val writeKey: ByteArray,
) {
    class Decrypted(
        val data: ByteArray,
        val rest: ByteArray,
    )

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output += AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad)
            readCounter++
            offset = frameEnd
        }
        val joined = if (output.isEmpty()) ByteArray(0) else output.reduce { acc, bytes -> acc + bytes }
        return Decrypted(joined, buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag =
                AirPlayCrypto.chachaSeal(
                    writeKey,
                    AirPlayCrypto.nonce64(writeCounter),
                    chunk,
                    header,
                )
            output += header
            output += ciphertextAndTag
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return output.reduce { acc, bytes -> acc + bytes }
    }

    private fun readU16Le(
        source: ByteArray,
        offset: Int,
    ): Int = (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16

        /** 1440 MTU 减 2B AAD 和 16B tag，参考 catplay AIRPLAY_CIPHER_MTU_MAX */
        const val MAX_PAYLOAD = 1422
    }
}
