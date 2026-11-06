package io.github.shaonialife.buicklinkplus.airplay

/**
 * 按 AirPlay 认证方式选择流加密，参考 catplay ScreenFrameCodec
 * HomeKit 配对用 ChaCha20-Poly1305；MFi-SAP 用 AES-128-CTR
 * et=NONE 仍用 AES-128-CTR，派生前的会话密钥为全零
 */
sealed class StreamFrameCipher {
    /** 视频帧使用 ChaCha20-Poly1305 和 16B tag，保活与配置帧不加密 */
    class ChaCha(
        val key: ByteArray,
    ) : StreamFrameCipher()

    /** 视频帧 AES-128-CTR 流式加密（无 tag，密钥流跨帧连续）。 */
    class AesCtr(
        val key: ByteArray,
        val iv: ByteArray,
    ) : StreamFrameCipher()
}

object AirPlayStreamKeys {
    /**
     * ChaCha 流密钥使用 HKDF-SHA512(shared, DataStream-Salt<connId>, DataStream-Output-Encryption-Key)
     * 参考 catplay create_chacha_ciphers
     */
    fun chachaStreamKey(
        sharedSecret: ByteArray,
        connectionId: Long,
    ): ByteArray =
        AirPlayCrypto.hkdfSha512(
            sharedSecret,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            "DataStream-Output-Encryption-Key".toByteArray(Charsets.US_ASCII),
        )

    /** et=NONE 的派生输入为 16 字节全零会话密钥，参考 catplay ScreenFrameCodec */
    val ZERO_SESSION_KEY: ByteArray = ByteArray(16)

    /**
     * AES 流密钥和 IV，参考 catplay derive_aes_stream_keys
     * key = SHA512("AirPlayStreamKey" + connId(十进制) + sessionKey)[0..16]
     * iv = SHA512("AirPlayStreamIV" + connId(十进制) + sessionKey)[0..16]
     */
    fun aesStreamKeys(
        sessionKey: ByteArray,
        connectionId: Long,
    ): Pair<ByteArray, ByteArray> {
        fun digest(prefix: String): ByteArray =
            AirPlayCrypto
                .sha512(
                    prefix.toByteArray(Charsets.US_ASCII),
                    connectionId.toString().toByteArray(Charsets.US_ASCII),
                    sessionKey,
                ).copyOfRange(0, 16)
        return digest("AirPlayStreamKey") to digest("AirPlayStreamIV")
    }
}
