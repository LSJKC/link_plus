package io.github.shaonialife.buicklinkplus.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * PCM 转 AAC-LC，供 type-102 MainHighAudio 使用
 * RTP 每包一帧裸 AAC，不带 ADTS 或 AU header；每帧 1024 个采样
 * 格式参考 catplay aac_encoder 和 rtp_recorder
 */
class AacLcEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitRate: Int = 192_000,
    /** 是否给每帧补 7 字节 ADTS 头（见 [AudioTuning.ADTS]）。 */
    private val wrapAdts: Boolean = false,
    private val log: (String) -> Unit,
) {
    companion object {
        /** AAC-LC 一帧固定 1024 个采样（每声道）。 */
        const val FRAME_SAMPLES = 1024

        /** 一帧 PCM 的字节数（16bit）。 */
        fun pcmBytes(channels: Int): Int = FRAME_SAMPLES * channels * 2

        /** ADTS 头长度（无 CRC）。 */
        private const val ADTS_HEADER_SIZE = 7

        /** ADTS 里的 audioObjectType：AAC-LC = 2（存的是 AOT-1）。 */
        private const val AAC_PROFILE_LC = 2

        private const val MIME = "audio/mp4a-latm"
        private const val TIMEOUT_US = 10_000L

        /** 生成无 CRC 的 7 字节 MPEG-4 AAC-LC ADTS 头，供 [AudioTuning.ADTS] 使用 */
        fun adtsHeader(
            payloadSize: Int,
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val frameLength = payloadSize + ADTS_HEADER_SIZE
            val freqIndex = frequencyIndex(sampleRate)
            val header = ByteArray(ADTS_HEADER_SIZE)
            header[0] = 0xFF.toByte()
            // syncword 剩余 4 位 + MPEG-4(0) + layer(00) + protection_absent(1)
            header[1] = 0xF1.toByte()
            // profile=LC(2) 存的是 AOT-1；采样率索引 <<2；声道配置高 2 位
            header[2] =
                (
                    ((AAC_PROFILE_LC - 1) shl 6) or
                        ((freqIndex and 0x0F) shl 2) or
                        ((channels shr 2) and 0x03)
                ).toByte()
            // 声道配置低 2 位 + 帧长高 2 位
            header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
            header[4] = ((frameLength shr 3) and 0xFF).toByte()
            // 帧长低 3 位 + buffer_fullness=0x7FF（VBR 惯例）+ 单帧（低 2 位 0）
            header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            header[6] = 0xFC.toByte()
            return header
        }

        /** 采样率 → ADTS 的 samplingFrequencyIndex。 */
        fun frequencyIndex(rate: Int): Int =
            when (rate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> 4
            }

        /**
         * 用平台解码器检查一帧裸 AAC，返回峰值和样本数，失败返回 null
         * AAC 是比特流，不能按 PCM 做字节换序
         */
        fun decodePeak(
            frame: ByteArray,
            sampleRate: Int,
            channels: Int,
        ): Pair<Int, Int>? {
            var created: MediaCodec? = null
            try {
                val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels)
                format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(audioSpecificConfig(sampleRate, channels)))
                val codec = MediaCodec.createDecoderByType(MIME)
                created = codec
                codec.configure(format, null, null, 0)
                codec.start()
                val inputIndex = codec.dequeueInputBuffer(200_000L)
                if (inputIndex < 0) return null
                val input = codec.getInputBuffer(inputIndex) ?: return null
                input.clear()
                val raw = decoderPayload(frame)
                input.put(raw)
                codec.queueInputBuffer(inputIndex, 0, raw.size, 0L, 0)
                val end = codec.dequeueInputBuffer(100_000L)
                if (end >= 0) codec.queueInputBuffer(end, 0, 0, 1_024_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                val info = MediaCodec.BufferInfo()
                val deadline = System.nanoTime() + 600_000_000L
                while (System.nanoTime() < deadline) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 60_000L)
                    if (outputIndex < 0) continue
                    val output = codec.getOutputBuffer(outputIndex)?.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    var peak = 0
                    var samples = 0
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        while (output.remaining() >= 2) {
                            val value = output.short.toInt()
                            val magnitude = if (value < 0) -value else value
                            if (magnitude > peak) peak = magnitude
                            samples++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (samples > 0) return peak to samples
                }
                return null
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { created?.stop() }
                runCatching { created?.release() }
            }
        }

        /** 解码裸 AAC 时提供采样率和声道配置 */
        internal fun audioSpecificConfig(
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val value = (AAC_PROFILE_LC shl 11) or (frequencyIndex(sampleRate) shl 7) or (channels shl 3)
            return byteArrayOf((value shr 8).toByte(), value.toByte())
        }

        /**
         * 自检统一读取裸帧，线上选择 ADTS 时移除本帧已有头部
         */
        internal fun decoderPayload(frame: ByteArray): ByteArray {
            if (frame.size < 7 || (frame[0].toInt() and 255) != 255 || (frame[1].toInt() and 240) != 240) return frame
            val header = if ((frame[1].toInt() and 1) == 1) 7 else 9
            return if (frame.size > header) frame.copyOfRange(header, frame.size) else frame
        }
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    /** ADTS 剥离只提示一次，避免刷屏。 */
    private var adtsStripped = false

    /** ADTS 封装提示只打一次。 */
    private var adtsWrapped = false

    /** 首帧十六进制只打一次。 */
    private var firstFrameLogged = false

    /** 每帧 PCM 输入字节数。 */
    val inputFrameBytes: Int = pcmBytes(channels)

    var started: Boolean = false
        private set

    fun start(): Boolean {
        if (started) return true
        return try {
            val format =
                MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputFrameBytes)
                }
            val created = MediaCodec.createEncoderByType(MIME)
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            codec = created
            presentationUs = 0L
            started = true
            log(
                "AUDIO: AAC-LC 编码器就绪（${sampleRate}Hz ${channels}ch " +
                    "${bitRate / 1000}kbps，帧=${FRAME_SAMPLES}采样/${inputFrameBytes}B）",
            )
            true
        } catch (error: Exception) {
            log("AUDIO: AAC-LC 编码器启动失败 - ${error.message}（本档位放弃）")
            started = false
            false
        }
    }

    /** 输入 [inputFrameBytes] 字节 PCM，返回本次输出的 AAC 帧；编码延迟时可能为空 */
    fun encode(
        pcm: ByteArray,
        size: Int,
    ): List<ByteArray> {
        val encoder = codec ?: return emptyList()
        val output = mutableListOf<ByteArray>()
        try {
            val inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val buffer = encoder.getInputBuffer(inputIndex)
                if (buffer == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, presentationUs, 0)
                } else {
                    buffer.clear()
                    buffer.put(pcm, 0, size.coerceAtMost(buffer.capacity()))
                    encoder.queueInputBuffer(inputIndex, 0, size, presentationUs, 0)
                    presentationUs += FRAME_SAMPLES * 1_000_000L / sampleRate
                }
            }
            drain(encoder, output)
        } catch (error: Exception) {
            log("AUDIO: AAC 编码异常 - ${error.message}")
        }
        return output
    }

    private fun drain(
        encoder: MediaCodec,
        output: MutableList<ByteArray>,
    ) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                index < 0 -> return
                else -> {
                    val buffer = encoder.getOutputBuffer(index)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (buffer != null && bufferInfo.size > 0 && !isConfig) {
                        val data = ByteArray(bufferInfo.size)
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        buffer.get(data)
                        output += finalizeFrame(data)
                    }
                    encoder.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    /** 识别并去掉编码器附带的 7 或 9 字节 ADTS 头 */
    private fun stripAdts(frame: ByteArray): ByteArray {
        if (frame.size < 7) return frame
        val b0 = frame[0].toInt() and 0xFF
        val b1 = frame[1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return frame
        // ADTS 的 protection_absent 位在第二个字节最低位：1=无 CRC（7 字节头），0=9 字节
        val headerSize = if ((b1 and 0x01) == 0x01) 7 else 9
        if (frame.size <= headerSize) return frame
        if (!adtsStripped) {
            adtsStripped = true
            log("AUDIO: 编码器输出带 ADTS 头（${headerSize}B）——已剥离，改发裸 AAC 帧")
        }
        return frame.copyOfRange(headerSize, frame.size)
    }

    /** 先去掉已有 ADTS 头，再按配置选择裸帧或重新加头 */
    private fun finalizeFrame(frame: ByteArray): ByteArray {
        val raw = stripAdts(frame)
        if (!adtsWrapped && wrapAdts) {
            adtsWrapped = true
            log("AUDIO: 已改为 ADTS 封装（每帧补 7 字节头，方案2）")
        }
        val out = if (wrapAdts) buildAdts(raw) else raw
        // 首帧留档：出问题时能直接从日志里核对负载是不是合法 AAC（而不是等用户导文件）
        if (!firstFrameLogged) {
            firstFrameLogged = true
            val head = out.take(16).joinToString(" ") { "%02X".format(it) }
            log(
                "AUDIO: 首帧 ${out.size}B（裸帧 ${raw.size}B）head=$head" +
                    (if (wrapAdts) "（ADTS，sync=FFF）" else ""),
            )
        }
        return out
    }

    /** 组 7 字节 ADTS 头（无 CRC）：MPEG-4 / LC / 对应采样率与声道。 */
    private fun buildAdts(raw: ByteArray): ByteArray = adtsHeader(raw.size, sampleRate, channels) + raw

    fun stop() {
        val encoder = codec ?: return
        codec = null
        started = false
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
    }
}
