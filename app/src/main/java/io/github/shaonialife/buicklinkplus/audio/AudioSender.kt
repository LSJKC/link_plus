package io.github.shaonialife.buicklinkplus.audio

import io.github.shaonialife.buicklinkplus.airplay.AirPlayCrypto
import io.github.shaonialife.buicklinkplus.airplay.TimingServer
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay RTP 音频发送端，支持 PCM 和 AAC-LC
 * 报文：[12B RTP 头][密文及 16B tag][8B LE nonce]
 * ChaCha20-Poly1305 的 AAD 为 RTP 头 4..12 字节，nonce 前补 4B 零
 * PT 使用流类型，SSRC 为 0，时间戳按样本递增，首包置 Marker
 * 每秒向 controlPort 发送 [AudioSyncPacket]，建立 RTP 与 NTP 的对应关系
 * 格式参考 catplay rtp_cipher_chacha 和 DiPlay AudioStream
 *
 * **只保留外部 PCM 直通数据源**（CarLife 音频：媒体 9240 / TTS 9241 → [CarLifeAudioSink]
 * 统一成 48kHz 立体声 1408B 实时包 → [startExternal] + [feedExternalPcm]）。原「本机播放 +
 * MediaProjection 播放捕获」的兼容采集路径已删除：CarLife 音频来自引擎本地 TCP，
 * **不需要屏幕录制/投屏授权，也不申请录音权限**。
 */
class AudioSender(
    private val host: String,
    private val dataPort: Int,
    private val streamKey: ByteArray,
    private val log: (String) -> Unit,
    /** 输出采样率必须与 SETUP 协商结果一致，默认 48kHz */
    private val sampleRate: Int = 48_000,
    /** 负载编码：PCM（type=100 通路）或 AAC-LC（type=102 通路）。 */
    private val codec: AudioCodec = AudioCodec.PCM,
    /** RTP 流类型，仅用于日志（PT 与 SETUP 的 type 保持一致）。 */
    private val streamType: Long = 100L,
    /** AAC 帧是否补 ADTS 头（见 [AudioTuning.ADTS]，排查车机解码链用）。 */
    private val adtsFrames: Boolean = false,
    /** SETUP 返回的音频控制端口，大于 0 时定期发送同步包，为 0 时只发送音频 */
    private val controlPort: Int = 0,
) {
    companion object {
        const val CHANNELS = 2

        /** PCM 每包 352 个样本帧，参考 catplay MAX_FRAMES_PER_PACKET_PCM */
        const val SAMPLES_PER_PACKET = 352

        /** 一包负载字节数（16bit 立体声）。 */
        const val PACKET_BYTES = SAMPLES_PER_PACKET * CHANNELS * 2

        /** 同步包发送间隔（规范：每秒一个）。 */
        const val SYNC_INTERVAL_MILLIS = 1_000L

        private const val RTP_HEADER_LEN = 12
        private const val NONCE_SIZE = 8

        /** 小端 s16 → 大端 s16（每两字节换序）。CarPlay 线格式要求大端样本。 */
        fun bigEndianS16(source: ByteArray): ByteArray {
            val out = source.copyOf()
            var index = 0
            while (index + 1 < out.size) {
                val tmp = out[index]
                out[index] = out[index + 1]
                out[index + 1] = tmp
                index += 2
            }
            return out
        }
    }

    private val running = AtomicBoolean(false)

    /** stop() 幂等闸门（多条收尾路径都会调 stop）。 */
    private val stopped = AtomicBoolean(false)
    private val resourceLock = Any()
    private var socket: DatagramSocket? = null
    private var syncSocket: DatagramSocket? = null
    private var syncThread: Thread? = null

    /** AAC-LC 编码器（仅 [AudioCodec.AAC_LC] 时创建）。 */
    private var aacEncoder: AacLcEncoder? = null

    /** RTP 游标，供发送循环和同步包线程共同读取 */
    @Volatile private var sequence = 0

    @Volatile private var timestamp = 0L

    @Volatile private var nonceCounter = 0L

    /** 首个音频包标记（打完就清）。 */
    private var markerPending = true

    /** 每包/每帧对应的 PCM 字节数（PCM 通路=一包的负载；AAC 通路=一帧的输入）。 */
    private val framePcmBytes: Int
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.pcmBytes(CHANNELS) else PACKET_BYTES

    /** 每包推进的样本数（AAC 一帧 1024，PCM 一包 352）。 */
    private val samplesPerPacket: Long
        get() = if (codec == AudioCodec.AAC_LC) AacLcEncoder.FRAME_SAMPLES.toLong() else SAMPLES_PER_PACKET.toLong()

    /** RTP SSRC 固定为 0，参考 catplay prepare_header */
    private val ssrc = 0

    /** 外部 PCM 直通模式（CarLife）：数据由 [feedExternalPcm] 注入，不启动任何本机采集。 */
    @Volatile
    private var externalMode = false

    /** 外部注入 PCM 的帧对齐累积缓冲（切满一帧就封包/发送）。 */
    private val externalBuffer = java.io.ByteArrayOutputStream()

    /** 最近一次外部注入真实 PCM 的时刻（uptimeMillis，诊断用）。 */
    @Volatile
    private var lastExternalFeedAt = 0L

    /** 推流统计（会话日志用）。 */
    @Volatile private var packetsSent = 0L

    @Volatile private var bytesSent = 0L

    /** 外部注入累计包/字节/TTS 字节（诊断用）。 */
    private var externalPackets = 0L
    private var externalBytes = 0L
    private var externalTtsBytes = 0L

    private val syncState = AudioSyncState()
    private val started = AtomicBoolean(false)
    val isRunning: Boolean get() = running.get()

    /** 当前是否为外部 PCM 直通模式（诊断/会话日志用）。 */
    val isExternalMode: Boolean get() = externalMode

    /** 发送阶段摘要（不含任何本机采集/静音状态：CarLife 音频由引擎侧提供）。 */
    fun status(): String =
        "音频：${if (running.get()) "外部PCM直通（CarLife）" else "已停止"}" +
            " · 发送：$packetsSent 包 / ${bytesSent / 1024} KB · 同步：${syncState.status}"

    /**
     * 车机侧音源状态文案（首页音频入口用）。
     *
     * 外部 PCM 直通不静音手机、也不走本机播放，因此没有「媒体独占路由/手机静音」状态。
     */
    val phoneMuteStatus: String get() = if (running.get()) "外部PCM直通（CarLife 引擎音源）" else ""

    /**
     * 启动**外部 PCM 直通模式**（CarLife 引擎音频 → RTP，**不启动 MediaProjection 采集**）。
     *
     * 数据由 [feedExternalPcm] 注入（[io.github.shaonialife.buicklinkplus.carlife.CarLifeAudioSink]
     * 已把 9240 媒体 / 9241 TTS 统一成 48kHz 立体声 1408B 实时包）。本模式只建立 UDP 数据
     * 套接字、可选的 AAC 编码器和同步包线程，复用与采集路径完全一致的封包/加密/发送逻辑；
     * 不申请录音/投屏权限，也不静音手机媒体（CarLife 引擎自己负责采集）。
     *
     * @return 是否已进入发送循环
     */
    fun startExternal(): Boolean {
        if (!started.compareAndSet(false, true)) return running.get()
        if (running.get()) return true
        if (stopped.get()) return false
        return try {
            val client = DatagramSocket()
            synchronized(resourceLock) {
                if (stopped.get()) {
                    client.close()
                    return false
                }
                socket = client
            }
            client.connect(InetAddress.getByName(host), dataPort)

            // AAC 通路仍需要编码器（注入的是 PCM，编码照常）；失败就不进入发送循环。
            if (codec == AudioCodec.AAC_LC) {
                val encoder = AacLcEncoder(sampleRate, CHANNELS, wrapAdts = adtsFrames, log = log)
                if (!encoder.start()) {
                    client.close()
                    socket = null
                    return false
                }
                synchronized(resourceLock) {
                    if (stopped.get()) {
                        encoder.stop()
                        return false
                    }
                    aacEncoder = encoder
                }
            }

            synchronized(resourceLock) {
                if (stopped.get()) return false
                externalMode = true
                running.set(true)
            }
            log(
                "AUDIO: 已启动外部 PCM 直通模式（CarLife 引擎 PCM → $host:$dataPort，" +
                    "通路=t$streamType/${codec.name}，目标 ${sampleRate}Hz " +
                    if (codec == AudioCodec.AAC_LC) {
                        "（每包 1 个 AAC 帧${if (adtsFrames) "，ADTS 封装" else "，裸帧"}，不采集 MediaProjection）"
                    } else {
                        "立体声（每包 ${SAMPLES_PER_PACKET} 帧 PCM，不采集 MediaProjection）"
                    },
            )
            // 同步包线程先起：锚点越早建立，接收端越早能排播。
            startSyncLoop()
            true
        } catch (error: Exception) {
            log("AUDIO: 外部 PCM 直通启动失败 - ${error.message}")
            stop()
            false
        }
    }

    /** 停止音频发送，可重复调用 */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        synchronized(resourceLock) {
            running.set(false)
            aacEncoder?.stop()
            aacEncoder = null
            runCatching { socket?.close() }
            socket = null
            runCatching { syncSocket?.close() }
            syncSocket = null
            syncState.stop()
            syncThread?.interrupt()
            syncThread = null
            externalMode = false
            externalBuffer.reset()
            if (externalPackets > 0L) {
                log("AUDIO: 外部 PCM 累计发送 $externalPackets 帧包 / ${externalBytes / 1024}KB（其中 TTS 注入 $externalTtsBytes B）")
            }
            lastExternalFeedAt = 0L
            log("AUDIO: 已停止（共发 $packetsSent 包 / ${bytesSent / 1024}KB）")
        }
    }

    // ---- 外部 PCM 直通：CarLife 引擎 PCM 注入 → 同一套封包/加密/同步包/发送 ----

    /**
     * 直通模式下由外部注入一段 PCM（16bit 小端交织）。
     *
     * 会先把任意采样率/声道数对齐到本发送端的 [sampleRate]/[CHANNELS]（CarLife 音频桥已经
     * 统一到 48kHz 立体声，正常情况下走零拷贝快路径），再按固定帧长（PCM 通路每包
     * [SAMPLES_PER_PACKET] 帧、AAC 通路每帧 [AacLcEncoder.FRAME_SAMPLES] 帧）累积切帧，
     * 交给与采集循环一致的 [sendPayload]（RTP 头 + ChaCha20-Poly1305 + nonce）。
     *
     * **必须同步**：媒体（9240）与 TTS（9241）虽然由音频桥先混好再单线程送包，但保留
     * `@Synchronized` 防止其它调用方并发注入造成半帧交错。
     *
     * @param pcm 输入 PCM（16bit 小端交织）
     * @param size 有效字节数
     * @param srcSampleRate 输入采样率（<=0 = 与 [sampleRate] 相同，不重采样）
     * @param srcChannels 输入声道数（1 或 2；其它按 2 处理）
     * @param isTts 仅用于诊断统计（TTS 已由音频桥 duck/mix 进媒体包，正常传 false）
     * @return false = 未在外部模式/发送失败
     */
    @Synchronized
    fun feedExternalPcm(
        pcm: ByteArray,
        size: Int,
        srcSampleRate: Int = 0,
        srcChannels: Int = CHANNELS,
        isTts: Boolean = false,
    ): Boolean {
        if (!running.get() || !externalMode) return false
        if (size <= 0 || size > pcm.size) return false
        lastExternalFeedAt = android.os.SystemClock.uptimeMillis()
        if (isTts) externalTtsBytes += size
        val aligned = alignExternalPcm(pcm, size, srcSampleRate, srcChannels)
        if (aligned.isEmpty()) return false
        externalBuffer.write(aligned, 0, aligned.size)
        val frameBytes = framePcmBytes
        var ok = true
        while (externalBuffer.size() >= frameBytes) {
            val all = externalBuffer.toByteArray()
            val frame = all.copyOfRange(0, frameBytes)
            externalBuffer.reset()
            if (all.size > frameBytes) externalBuffer.write(all, frameBytes, all.size - frameBytes)
            if (!dispatchExternalFrame(frame)) {
                ok = false
                break
            }
        }
        return ok
    }

    /** 外部注入的 PCM 对齐到发送端格式（参考 CarPToCarL AudioSender.alignPcm 的实现）。 */
    private fun alignExternalPcm(
        src: ByteArray,
        size: Int,
        srcRate: Int,
        srcChannels: Int,
    ): ByteArray {
        val inRate = if (srcRate > 0) srcRate else sampleRate
        val inCh = if (srcChannels == 1) 1 else CHANNELS
        // 声道对齐：单声道 → 立体声（左右复制同一采样）。
        val rateAligned: ByteArray
        if (inCh == 1) {
            val monoFrames = size / 2
            val out = ByteArray(monoFrames * CHANNELS * 2)
            var i = 0
            var o = 0
            while (i + 1 < size) {
                val lo = src[i]
                val hi = src[i + 1]
                out[o] = lo
                out[o + 1] = hi
                out[o + 2] = lo
                out[o + 3] = hi
                i += 2
                o += 4
            }
            rateAligned = out
        } else {
            rateAligned = if (size == src.size) src else src.copyOf(size)
        }
        if (inRate == sampleRate) return rateAligned
        // 线性重采样（frame = 所有声道的一组采样）。CarLife 桥已做跨包相位连续，这里只兜底。
        val inFrames = rateAligned.size / (CHANNELS * 2)
        val outFrames = (inFrames.toLong() * sampleRate / inRate).toInt()
        val out = ByteArray(outFrames * CHANNELS * 2)
        val frameBytes = CHANNELS * 2
        for (frame in 0 until outFrames) {
            val srcPos = (frame.toLong() * inRate / sampleRate).toInt()
            val srcByte = srcPos * frameBytes
            if (srcByte + frameBytes <= rateAligned.size) {
                System.arraycopy(rateAligned, srcByte, out, frame * frameBytes, frameBytes)
            }
        }
        return out
    }

    /** 一帧 PCM → 编码（AAC 通路）/原样（PCM 通路）→ [sendPayload]。 */
    private fun dispatchExternalFrame(frame: ByteArray): Boolean {
        val payloads: List<ByteArray> =
            if (codec == AudioCodec.AAC_LC) {
                val encoder = aacEncoder ?: return false
                encoder.encode(frame, frame.size)
            } else {
                listOf(frame)
            }
        if (payloads.isEmpty()) return true
        for (payload in payloads) {
            if (!sendPayload(payload)) return false
        }
        externalPackets++
        externalBytes += frame.size
        if (externalPackets == 1L) {
            log("AUDIO: 外部 PCM 首帧包已发送（每包 ${framePcmBytes}B，通路 t$streamType/${codec.name}）")
        }
        return true
    }

    // ---- 音频同步包（RTP 时间戳 ↔ NTP，每秒一个） ----

    private fun startSyncLoop() {
        val request =
            synchronized(resourceLock) {
                if (stopped.get() || !running.get()) return
                syncState.begin()
            }
        if (controlPort <= 0) {
            syncState.update(request, "车机未声明同步端口")
            log("AUDIO: 车机未下发 controlPort——不发同步包（只能靠音频包自带时间戳）")
            return
        }
        val client = DatagramSocket()
        try {
            client.connect(InetAddress.getByName(host), controlPort)
        } catch (error: Exception) {
            client.close()
            syncState.update(request, "同步不可用，媒体发送继续")
            log("AUDIO: 同步包套接字创建失败 - ${error.message}")
            return
        }
        synchronized(resourceLock) {
            if (stopped.get() || !running.get() || !syncState.owns(request)) {
                client.close()
                return
            }
            syncSocket = client
        }
        log("AUDIO: 同步包已启用 → $host:$controlPort（每 ${SYNC_INTERVAL_MILLIS}ms 一个，建立 RTP↔NTP 锚点）")
        val worker =
            Thread(
                {
                    var syncSequence = 0
                    var first = true
                    try {
                        while (running.get() && syncState.owns(request) && syncSocket === client && !client.isClosed) {
                            val next = timestamp
                            val current = (next - samplesPerPacket).coerceAtLeast(0L)
                            val ntp = TimingServer.ntpFromNanos(System.nanoTime())
                            val packet = AudioSyncPacket.build(syncSequence, current, next, ntp, first)
                            client.send(DatagramPacket(packet, packet.size))
                            syncState.update(request, "同步包已发送，接收未确认")
                            syncSequence = (syncSequence + 1) and 0xFFFF
                            if (first) {
                                first = false
                                log(
                                    "AUDIO: 首个同步包已发出（X 位=1）ts=$current→$next——" +
                                        "同步发送不代表车机已接收或扬声器已出声",
                                )
                            }
                            Thread.sleep(SYNC_INTERVAL_MILLIS)
                        }
                    } catch (error: Exception) {
                        if (running.get() && syncState.owns(request)) {
                            syncState.update(request, "发送受阻，已降级，媒体发送继续")
                            log("AUDIO: 同步包发送停止 - ${error.message}；媒体发送继续，不能据此认定车机停止消费")
                        }
                    } finally {
                        client.close()
                    }
                },
                "audio-sync",
            )
        worker.isDaemon = true
        synchronized(resourceLock) {
            if (stopped.get() || !syncState.owns(request)) {
                client.close()
                return
            }
            syncThread = worker
            worker.start()
        }
    }

    /** 封包 + 发送一包（同步更新 seq/ts/nonce 游标）。返回 false 表示链路已断。 */
    private fun sendPayload(payload: ByteArray): Boolean {
        val wire = sealPacket(payload, sequence, timestamp, nonceCounter, markerPending)
        markerPending = false
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonceCounter++
        val client = socket ?: return false
        return try {
            client.send(DatagramPacket(wire, wire.size))
            packetsSent++
            bytesSent += wire.size
            true
        } catch (error: IOException) {
            log("AUDIO: UDP 发送失败 - ${error.message}")
            false
        }
    }

    /** 将 PCM 加密后组装为 RTP 头、密文、tag 和 nonce */
    internal fun sealPacket(
        pcm: ByteArray,
        sequence: Int,
        timestamp: Long,
        nonceCounter: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = buildRtpHeader(sequence, timestamp, marker)
        // AAD 取 RTP 头的 timestamp 和 SSRC，即字节 4..12
        val aad = header.copyOfRange(4, RTP_HEADER_LEN)
        // Android 采集的 PCM 是小端，CarPlay LPCM 使用大端 s16，需要换序
        // AAC 是编码后的字节流，保持原顺序
        val wirePayload = if (codec == AudioCodec.AAC_LC) pcm else bigEndianS16(pcm)
        val sealed = AirPlayCrypto.chachaSeal(streamKey, AirPlayCrypto.nonce64(nonceCounter), wirePayload, aad)
        val wire = ByteArray(RTP_HEADER_LEN + sealed.size + NONCE_SIZE)
        header.copyInto(wire, 0)
        sealed.copyInto(wire, RTP_HEADER_LEN)
        // 尾部 8 字节小端 nonce（与 AirPlayCrypto.nonce64 的低 8 字节一致）
        for (index in 0 until NONCE_SIZE) {
            wire[wire.size - NONCE_SIZE + index] =
                ((nonceCounter ushr (8 * index)) and 0xFF).toByte()
        }
        return wire
    }

    private fun buildRtpHeader(
        sequence: Int,
        timestamp: Long,
        marker: Boolean = false,
    ): ByteArray {
        val header = ByteArray(RTP_HEADER_LEN)
        header[0] = 0x80.toByte() // version=2
        // PT 跟随流类型（catplay `payload_type = stream_type`）：100=PCM 通路、102=AAC 通路
        header[1] = ((if (marker) 0x80 else 0) or (streamType.toInt() and 0x7F)).toByte()
        header[2] = ((sequence ushr 8) and 0xFF).toByte()
        header[3] = (sequence and 0xFF).toByte()
        val ts = timestamp.toInt()
        header[4] = ((ts ushr 24) and 0xFF).toByte()
        header[5] = ((ts ushr 16) and 0xFF).toByte()
        header[6] = ((ts ushr 8) and 0xFF).toByte()
        header[7] = (ts and 0xFF).toByte()
        header[8] = ((ssrc ushr 24) and 0xFF).toByte()
        header[9] = ((ssrc ushr 16) and 0xFF).toByte()
        header[10] = ((ssrc ushr 8) and 0xFF).toByte()
        header[11] = (ssrc and 0xFF).toByte()
        return header
    }
}
