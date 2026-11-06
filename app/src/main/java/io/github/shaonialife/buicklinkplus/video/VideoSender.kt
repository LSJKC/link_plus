package io.github.shaonialife.buicklinkplus.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import io.github.shaonialife.buicklinkplus.airplay.AirPlayCrypto
import io.github.shaonialife.buicklinkplus.airplay.BinaryPlist
import io.github.shaonialife.buicklinkplus.airplay.StreamFrameCipher
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 视频发送端，输入由画面源绘制到 MediaCodec Surface
 * 报文为 128B 头和消息体，头含 u32LE 长度、opcode 和 NTP 时间，整头用作 AAD
 * 配置帧为明文 avcC，视频帧为长度前缀 NAL，按协商方式加密
 * 格式参考 DiPlay ScreenStream
 */
class VideoSender(
    private val host: String,
    private val dataPort: Int,
    /** 流加密方式参考 catplay ScreenFrameCodec：已配对用 ChaCha，et=NONE 用 AES-CTR */
    private val frameCipher: StreamFrameCipher,
    /** H.264 4:2:0 的宽高必须为偶数 */
    width: Int,
    height: Int,
    /** 目标帧率（30/60）：编码器元数据 + 60fps 提码率；实际节奏由合成器驱动。 */
    private val targetFps: Int = 30,
    private val bitrateMbps: Int = 0,
    private val nativeWidth: Int = width,
    private val nativeHeight: Int = height,
    private val log: (String) -> Unit,
    /** 推流自然结束（对端断开等）时回调——CarPlay 会话真实结束的信号。 */
    private val onEnded: (() -> Unit)? = null,
    /** 每秒发送屏幕流保活，按 /info 决定使用 opcode=5 统计体或 opcode=2 空帧 */
    private val keepAliveWithStats: Boolean = false,
) {
    private var width: Int = (width / 2) * 2
    private var height: Int = (height / 2) * 2

    @Volatile
    var activeProfile: VideoProfile? = null
        private set

    /** 编码能力或配置已耗尽时暂停自动连接，避免重复失败 */
    var configurationExhausted = false
        private set
    private var socket: Socket? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var frameCounter = 0L
    private var configSent = false

    /** AES-CTR 密钥流实例：状态必须跨帧连续（每帧新建会导致对端解出乱码）。 */
    private val aesStream: AirPlayCrypto.Aes128CtrStream? =
        (frameCipher as? StreamFrameCipher.AesCtr)?.let { AirPlayCrypto.Aes128CtrStream(it.key, it.iv) }

    /** 上一次屏幕流 keepAlive 的时刻（uptimeMillis；0 = 尚未发过）。 */
    private var lastKeepAliveAt = 0L

    /** 两次保活之间的帧数和字节数，供统计体使用 */
    private var framesSinceKeepAlive = 0
    private var bytesSinceKeepAlive = 0L
    private var keepAliveCount = 0L
    private var maxPackingNanos = 0L
    private var maxWritingNanos = 0L

    @Volatile
    private var framesSent = 0L

    /** 推流是否活跃，启动失败时保持 false */
    val isRunning: Boolean get() = running.get()

    /** 已发送帧总数（英雄卡实时帧率 = 1s 轮询取差值）。 */
    val framesSentTotal: Long get() = framesSent

    /** 在协商音频前连接视频 TCP 端口，避免超过车机数据连接看门狗 */
    fun openDataPort(): Boolean {
        val existing = socket
        if (existing != null && !existing.isClosed) return true
        return try {
            val client = Socket().also { socket = it }
            client.connect(InetSocketAddress(host, dataPort), 5_000)
            client.tcpNoDelay = true
            // 请求的缓冲仅是系统提示，是否仍有发送阻塞由分段耗时日志核验
            runCatching { client.sendBufferSize = 1 shl 20 }
                .onFailure { log("VIDEO: 发送缓冲设置未接受，沿用系统配置 - ${it.message}") }
            log("VIDEO: 数据端口已连通 $host:$dataPort，发送缓冲=${client.sendBufferSize}B（等 RECORD 后启动编码器）")
            true
        } catch (error: Exception) {
            runCatching { socket?.close() }
            socket = null
            log("VIDEO: 数据端口连接失败 - ${error.message}")
            false
        }
    }

    /** 启动编码和推流，通过 [onSurfaceReady] 提供编码器输入 Surface */
    fun start(onSurfaceReady: (Surface, VideoProfile) -> Unit): Boolean {
        if (running.get()) return true
        stopped.set(false)
        return try {
            if (!openDataPort()) {
                stop()
                return false
            }
            configurationExhausted = true
            val probe = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val capability =
                try {
                    checkNotNull(probe.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities)
                } finally {
                    probe.release()
                }
            val candidates =
                EncoderSizeSelector
                    .candidates(
                        width to height,
                        nativeWidth / 2 * 2 to nativeHeight / 2 * 2,
                        capability.widthAlignment,
                        capability.heightAlignment,
                        capability.supportedWidths.lower..capability.supportedWidths.upper,
                        capability.supportedHeights.lower..capability.supportedHeights.upper,
                    ) { w, h -> capability.isSizeSupported(w, h) }
                    .map { (w, h) -> VideoProfile(w, h, targetFps, VideoProfile.bitrate(w, h, targetFps, bitrateMbps)) }
            configurationExhausted = true
            var selected: VideoProfile? = null
            for ((index, candidate) in candidates.withIndex()) {
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    val created = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    codec = created
                    val caps = checkNotNull(created.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities)
                    require(caps.isSizeSupported(candidate.width, candidate.height)) { "编码器不支持该偶数尺寸" }
                    val rateLimit = kotlin.math.floor(caps.getSupportedFrameRatesFor(candidate.width, candidate.height).upper).toInt()
                    val fps = VideoProfile.limitFps(candidate.fps, targetFps, rateLimit)
                    require(caps.areSizeAndRateSupported(candidate.width, candidate.height, fps.toDouble())) { "编码器不支持该帧率" }
                    val requestedBitrate =
                        if (index == 0) VideoProfile.bitrate(candidate.width, candidate.height, fps, bitrateMbps) else candidate.bitrate
                    val profile =
                        candidate.copy(
                            fps = fps,
                            bitrate = requestedBitrate.coerceIn(caps.bitrateRange.lower, caps.bitrateRange.upper),
                        )
                    val format =
                        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, profile.width, profile.height).apply {
                            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                            setInteger(MediaFormat.KEY_BIT_RATE, profile.bitrate)
                            setInteger(MediaFormat.KEY_FRAME_RATE, profile.fps)
                            // I 帧间隔 2 秒：降低编码负载与码率脉冲以减轻发热，车机需要时经 forceKeyFrame 立即补
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                        }
                    created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    surface = created.createInputSurface()
                    created.start()
                    encoder = created
                    inputSurface = surface
                    selected = profile
                    break
                } catch (error: Exception) {
                    runCatching { codec?.stop() }
                    runCatching { codec?.release() }
                    runCatching { surface?.release() }
                    log("VIDEO: 编码配置失败，尝试有界回退：" + candidate + "，原因=" + error.message)
                }
            }
            val profile = checkNotNull(selected) { "请求配置与原生回退配置均不可用" }
            configurationExhausted = false
            width = profile.width
            height = profile.height
            activeProfile = profile
            log("VIDEO: 启动编码器 " + width + "x" + height + "@" + profile.fps + "fps bitrate=" + profile.bitrate / 1000 + "kbps")
            running.set(true)
            lastKeepAliveAt = android.os.SystemClock.uptimeMillis()
            onSurfaceReady(checkNotNull(inputSurface), profile)

            Thread({ drainEncoder() }, "video-drain").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("VIDEO: 启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /**
     * 关闭网络以解除发送等待，并幂等释放编码器和输入画面资源
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        // 先关闭网络以解除取帧线程的写等待，再释放编码器资源
        runCatching { socket?.close() }
        socket = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    /** 收到车机 forceKeyFrame 后立即请求关键帧 */
    fun requestKeyFrame(): Boolean {
        val codec = encoder ?: return false
        return try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec.setParameters(params)
            true
        } catch (error: Exception) {
            log("VIDEO: 请求关键帧失败 - ${error.message}")
            false
        }
    }

    // ---- 编码输出 → 协议封装发送 ----

    private fun drainEncoder() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        try {
            val output = socket?.getOutputStream() ?: return
            while (running.get()) {
                val index =
                    try {
                        codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (error: Exception) {
                        // 编码器进错误态（如非法尺寸首帧）：必须留痕——静默 break 曾让
                        // 「零帧推流 4 秒后断开」无从排查（接收端只见 peer EOF）
                        if (running.get()) log("VIDEO: 编码器取帧异常，停止推流 - ${error.message}")
                        break
                    }
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        maybeSendKeepAlive(output)
                        continue
                    }

                    index >= 0 -> {
                        try {
                            val buffer = codec.getOutputBuffer(index) ?: continue
                            buffer.position(bufferInfo.offset)
                            buffer.limit(bufferInfo.offset + bufferInfo.size)
                            val flags = bufferInfo.flags
                            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                sendVideoConfig(buffer, bufferInfo.size, output)
                            } else if (bufferInfo.size > 0) {
                                sendVideoFrame(buffer, bufferInfo.size, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, output)
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        maybeSendKeepAlive(output)
                    }
                }
            }
        } catch (error: Exception) {
            // 视频数据口断开（如接收端屏幕关闭收摊会话）：只终止推流，绝不冒泡——
            // 后台线程未捕获异常会杀死整个进程（曾导致锁屏后 app 消失）
            if (running.get()) log("VIDEO: 推流中止 - ${error.message}（等待接收端恢复后重连）")
        } finally {
            val unexpected = running.getAndSet(false)
            stop()
            // 会话真实结束信号（静默降噪解除 / 状态收尾）
            if (unexpected) runCatching { onEnded?.invoke() }
        }
    }

    /**
     * 每秒发送明文屏幕流保活，不消耗加密帧计数
     * 统计体长度按 f32 LE 写入头部 params[14]，参考 catplay ScreenTransmitSession
     */
    private fun maybeSendKeepAlive(output: java.io.OutputStream) {
        if (!running.get()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastKeepAliveAt < KEEPALIVE_INTERVAL_MS) return
        val elapsedNanos = (now - lastKeepAliveAt).coerceAtLeast(1L) * 1_000_000.0
        val frames = framesSinceKeepAlive
        val bytes = bytesSinceKeepAlive
        val framesPerSecond = (frames * 1_000_000_000.0 / elapsedNanos).toInt()
        val bytesPerSecond = bytes * 1_000_000_000.0 / elapsedNanos
        framesSinceKeepAlive = 0
        bytesSinceKeepAlive = 0
        lastKeepAliveAt = now
        keepAliveCount++
        val frame = buildKeepAliveFrame(keepAliveWithStats, frames, bytesPerSecond, framesPerSecond)
        output.write(frame)
        output.flush()
        if (keepAliveCount <= 3 || keepAliveCount % 30 == 0L) {
            log(
                "VIDEO: keepAlive #$keepAliveCount " +
                    "opcode=${if (keepAliveWithStats) OP_KEEP_ALIVE_WITH_BODY else OP_KEEP_ALIVE} " +
                    "body=${frame.size - HEADER_LEN}B fps=$framesPerSecond",
            )
            log("VIDEO: 阶段最大耗时 封装加密=${maxPackingNanos / 1_000_000}ms TCP写入=${maxWritingNanos / 1_000_000}ms")
            maxPackingNanos = 0L
            maxWritingNanos = 0L
        }
    }

    private fun sendVideoConfig(
        buffer: java.nio.ByteBuffer,
        size: Int,
        output: java.io.OutputStream,
    ) {
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val nalUnits = splitAnnexB(annexB)
        val sps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps == null || pps == null) {
            log("VIDEO: csd 缺少 SPS/PPS（${nalUnits.size} 个 NAL），跳过 config")
            return
        }
        val avcc = ByteArrayOutputStream(8 + sps.size + pps.size)
        avcc.write(1)
        avcc.write(sps[1].toInt()) // profile
        avcc.write(sps[2].toInt()) // compat
        avcc.write(sps[3].toInt()) // level
        avcc.write(0xFF) // 6 位保留 + 2 位长度尺寸-1（4 字节）
        avcc.write(0xE1) // 3 位保留 + 5 位 SPS 数
        avcc.write((sps.size ushr 8) and 0xFF)
        avcc.write(sps.size and 0xFF)
        avcc.write(sps)
        avcc.write(1)
        avcc.write((pps.size ushr 8) and 0xFF)
        avcc.write(pps.size and 0xFF)
        avcc.write(pps)
        val body = avcc.toByteArray()
        // 配置帧按 ScreenFrame::config 格式写入加密标记和两个 f32 LE 画布尺寸
        // params[0] 保持为零，PTS 只写入视频帧
        val header = buildVideoConfigHeader(body.size, width, height)
        output.write(header)
        output.write(body)
        output.flush()
        configSent = true
        log(
            "VIDEO: ✓ VideoConfig 已发送 avcC=${body.size}B（SPS ${sps.size}B/PPS ${pps.size}B，" +
                "profile=${sps[1].toInt() and 0xFF} level=${sps[3].toInt() and 0xFF} " +
                "flags=0x%02x ${width}x$height）".format(header[6].toInt() and 0xFF) +
                "\n  ↳ 头部前 24 字节=" + header.take(24).joinToString(" ") { "%02x".format(it) },
        )
    }

    private fun sendVideoFrame(
        buffer: java.nio.ByteBuffer,
        size: Int,
        keyFrame: Boolean,
        output: java.io.OutputStream,
    ) {
        if (!configSent) {
            log("VIDEO: 丢弃一帧（config 尚未发送）")
            return
        }
        val startedAt = System.nanoTime()
        val annexB = ByteArray(size)
        buffer.get(annexB)
        val plain = VideoNalUnits.lengthPrefixed(annexB)
        if (plain.isEmpty()) return

        // ChaCha 用最终的 128B 头作 AAD，长度包含 16B tag
        // AES-CTR 无 tag，密钥流跨帧连续；配置和保活帧不加密
        // 帧头只构造一次，避免时间戳变化后 AAD 与发出的数据不符
        val header =
            buildHeader(
                OP_FRAME,
                if (frameCipher is StreamFrameCipher.ChaCha) plain.size + TAG_SIZE else plain.size,
            )
        val sealed =
            when (val cipher = frameCipher) {
                is StreamFrameCipher.ChaCha ->
                    AirPlayCrypto.chachaSeal(cipher.key, AirPlayCrypto.nonce64(frameCounter), plain, header)
                is StreamFrameCipher.AesCtr -> {
                    val encrypted = plain.copyOf()
                    aesStream?.applyKeystream(encrypted)
                    encrypted
                }
            }
        frameCounter++
        val writingAt = System.nanoTime()
        maxPackingNanos = maxOf(maxPackingNanos, writingAt - startedAt)
        output.write(header)
        output.write(sealed)
        output.flush()
        maxWritingNanos = maxOf(maxWritingNanos, System.nanoTime() - writingAt)
        framesSent++
        framesSinceKeepAlive++
        bytesSinceKeepAlive += plain.size.toLong()
        if (keyFrame || framesSent <= 3 || framesSent % 150 == 0L) {
            log("VIDEO: 帧 #$framesSent ${plain.size}B${if (keyFrame) " (key)" else ""} 已发送")
        }
    }

    /** 128B 头包含 u32LE 长度、opcode、NTP 时间和标志参数 */
    private fun buildHeader(
        opcode: Int,
        bodyLength: Int,
    ): ByteArray {
        val header = ByteArray(HEADER_LEN)
        header[0] = (bodyLength and 0xFF).toByte()
        header[1] = ((bodyLength ushr 8) and 0xFF).toByte()
        header[2] = ((bodyLength ushr 16) and 0xFF).toByte()
        header[3] = ((bodyLength ushr 24) and 0xFF).toByte()
        header[4] = opcode.toByte()
        val nanos = System.nanoTime()
        val seconds = nanos / 1_000_000_000L
        val fraction = ((nanos % 1_000_000_000L) shl 32) / 1_000_000_000L
        val ntp = (seconds shl 32) or fraction
        for (index in 0 until 8) {
            header[8 + index] = ((ntp ushr (8 * index)) and 0xFF).toByte()
        }
        return header
    }

    private fun writeMessage(
        opcode: Int,
        body: ByteArray,
        declaredLength: Int,
        output: java.io.OutputStream,
    ) {
        output.write(buildHeader(opcode, declaredLength))
        output.write(body)
        output.flush()
    }

    /** 按 00 00 00 01 / 00 00 01 起始码切分 AnnexB 码流为 NAL 列表。 */
    private fun splitAnnexB(bytes: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var index = 0
        while (index + 3 < bytes.size) {
            if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
                if (bytes[index + 2] == 1.toByte()) {
                    starts += index
                    index += 3
                    continue
                }
                if (index + 3 < bytes.size && bytes[index + 2] == 0.toByte() && bytes[index + 3] == 1.toByte()) {
                    starts += index
                    index += 4
                    continue
                }
            }
            index++
        }
        if (starts.isEmpty()) {
            if (bytes.isNotEmpty()) units += bytes
            return units
        }
        for (unit in 0 until starts.size) {
            val start = starts[unit]
            val prefix = if (bytes[start + 2] == 1.toByte()) 3 else 4
            val bodyStart = start + prefix
            val bodyEnd = if (unit + 1 < starts.size) starts[unit + 1] else bytes.size
            if (bodyEnd > bodyStart) units += bytes.copyOfRange(bodyStart, bodyEnd)
        }
        return units
    }

    companion object {
        private const val HEADER_LEN = 128
        private const val OP_FRAME = 0
        private const val OP_CONFIG = 1
        private const val OP_KEEP_ALIVE = 2
        private const val OP_KEEP_ALIVE_WITH_BODY = 5
        private const val TAG_SIZE = 16

        /** 头部偏移 6 的加密标志，配置帧也需声明，参考 catplay ScreenFlag */
        private const val SCREEN_FLAG_ENCRYPTED = 0x04

        /** VideoConfig 头部 `params[1]` 的偏移：8 + 1×8，两个 f32 LE = 宽、高。 */
        private const val CONFIG_WIDTH_HEIGHT_OFFSET = 16

        /** 屏幕流 keepAlive 间隔（catplay `DEFAULT_KEEP_ALIVE` = 1s）。 */
        private const val KEEPALIVE_INTERVAL_MS = 1_000L

        /** 生成明文保活头和可选的 bplist 统计体 */
        internal fun buildKeepAliveFrame(
            withStats: Boolean,
            frames: Int,
            bytesPerSecond: Double,
            framesPerSecond: Int,
        ): ByteArray {
            val body =
                if (withStats) {
                    // 字段名对照 catplay ScreenSenderStats（serde camelCase）
                    BinaryPlist.encode(
                        linkedMapOf(
                            "txUsageAvg" to bytesPerSecond,
                            "encoderCurrentFPS" to framesPerSecond.toLong(),
                            "sentFramesAvg" to frames.toLong(),
                            "queuedFramesAvg" to 0L,
                            "lossAvg" to 0.0,
                        ),
                    )
                } else {
                    ByteArray(0)
                }
            val header = ByteArray(HEADER_LEN)
            // 头部布局：u32LE body 长度、u8 opcode、3 字节小参数、15 × 8 字节参数
            header[0] = (body.size and 0xFF).toByte()
            header[1] = ((body.size ushr 8) and 0xFF).toByte()
            header[2] = ((body.size ushr 16) and 0xFF).toByte()
            header[3] = ((body.size ushr 24) and 0xFF).toByte()
            header[4] = (if (body.isEmpty()) OP_KEEP_ALIVE else OP_KEEP_ALIVE_WITH_BODY).toByte()
            if (body.isNotEmpty()) {
                // params[14]（偏移 8+14*8=120）：前 4 字节 0.0f，后 4 字节 body 长度 f32 LE
                writeLittleEndianFloat(header, 120, 0f)
                writeLittleEndianFloat(header, 124, body.size.toFloat())
            }
            return if (body.isEmpty()) header else header + body
        }

        /**
         * 生成 VideoConfig 帧头，参考 catplay ScreenFrame::config
         * 偏移 6 声明加密，偏移 16 写入宽高 f32 LE，配置帧不带 PTS
         */
        internal fun buildVideoConfigHeader(
            bodySize: Int,
            width: Int,
            height: Int,
        ): ByteArray {
            val header = ByteArray(HEADER_LEN)
            header[0] = (bodySize and 0xFF).toByte()
            header[1] = ((bodySize ushr 8) and 0xFF).toByte()
            header[2] = ((bodySize ushr 16) and 0xFF).toByte()
            header[3] = ((bodySize ushr 24) and 0xFF).toByte()
            header[4] = OP_CONFIG.toByte()
            header[6] = SCREEN_FLAG_ENCRYPTED.toByte()
            for (index in 0 until 4) {
                val widthBits = width.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + index] = ((widthBits ushr (8 * index)) and 0xFF).toByte()
                val heightBits = height.toFloat().toRawBits()
                header[CONFIG_WIDTH_HEIGHT_OFFSET + 4 + index] = ((heightBits ushr (8 * index)) and 0xFF).toByte()
            }
            return header
        }

        private fun writeLittleEndianFloat(
            target: ByteArray,
            offset: Int,
            value: Float,
        ) {
            val bits = value.toRawBits()
            for (index in 0 until 4) {
                target[offset + index] = ((bits ushr (8 * index)) and 0xFF).toByte()
            }
        }
    }
}
