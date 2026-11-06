/* Adapted from hybrid63 inject/src/tl/CarLifeAudioPipe.java (prior port of CarPToCarL audio
 * behaviour, MIT, Copyright (c) 2026 lizhihua): fixed 1408B packets, 8-packet prefill and
 * real-time deadline pacing. link_plus has no ParcelFileDescriptor encoder pipe, so the
 * writer target is a packet callback (CarLifeAudioSink → AudioSender external PCM mode). */
package io.github.shaonialife.buicklinkplus.carlife

/**
 * CarLife PCM → 固定 1408B 实时包的水泵（hybrid63 `CarLifeAudioPipe.java` 的 Kotlin 移植）。
 *
 * hybrid63 把 CarLife 引擎 PCM 写进原版音频编码器读取的 `ParcelFileDescriptor` 管道：
 * 原版编码器每次读 1408B（352 采样帧、16bit 立体声），首包前要预灌 ~59ms 才能跨过它的
 * prime 门槛。link_plus 没有这条编码器管道（音频由 [io.github.shaonialife.buicklinkplus.audio.AudioSender]
 * 自己封 RTP 发送），所以本类保留同样的**数据形状与节拍**，只把「写管道」换成
 * [writePacket] 回调：
 *
 * - 每个包固定 [CHUNK_BYTES] = 1408B（352 帧 @ 48kHz 立体声 16bit，约 7.33ms）；
 * - 起播先预灌 [PREFILL_PACKETS] 包（约 59ms），与 hybrid63 一致，避免接收端/编码器
 *   判空闲把首批音频丢掉；
 * - 之后按 `packetNanos = 1408 × 1e9 / (rate × 4)` 的**绝对时间线**发（纳秒累加，避免
 *   hybrid63 里 `packetNs / 1_000_000` 整除带来的 4.7% 偏快）；进程卡顿超过
 *   [MAX_CATCH_UP_NANOS] 时只跳过错过的节拍，不突发补包；
 * - 每包先向 [readSource] 取 PCM（可以一次取不满，循环补齐），取不满的尾部补零——只有
 *   「真的取不到 PCM」时才补零，而不是无脑塞静音；随后 [mixTts] 把 TTS 播报混进本包。
 *
 * 线程模型：[start]/[stop] 任意线程、幂等；[readSource]/[mixTts]/[writePacket] 都在
 * 单独的水泵线程（"carlife-audio-pipe"）上调用，实现方需自行保证跨线程安全。
 */
class CarLifeAudioPipe(
    /**
     * 取一段媒体 PCM（16bit 交织，已转换为 48kHz 立体声；可只返回部分字节）。
     *
     * @return 实际写入 out 的字节数；0 = 当前没有数据
     */
    private val readSource: (out: ByteArray, offset: Int, length: Int) -> Int,
    /**
     * 把 TTS 播报混进本包（媒体 duck + TTS 上混），返回混入的采样帧数；0 = 本包无 TTS。
     */
    private val mixTts: (packet: ByteArray, frames: Int, sampleRate: Int) -> Int = { _, _, _ -> 0 },
    /** 写出一整包（固定 [CHUNK_BYTES]）；返回 false = 下游已断开，水泵停止。 */
    private val writePacket: (packet: ByteArray, length: Int) -> Boolean,
    private val sampleRate: Int = TARGET_SAMPLE_RATE,
    private val log: (String) -> Unit = {},
) {
    companion object {
        /** 与 hybrid63 原版编码器每次读取的字节数一致（352 采样帧）。 */
        const val CHUNK_BYTES = 1408

        /** 立体声 16bit：每采样帧 4 字节。 */
        const val BYTES_PER_FRAME = 4

        /** 一包采样帧数（352）。 */
        const val FRAMES_PER_CHUNK = CHUNK_BYTES / BYTES_PER_FRAME

        /** 目标采样率：CarLife 引擎 44.1k/48k 统一到 48k（车机音频档位也以 48k PCM 为主）。 */
        const val TARGET_SAMPLE_RATE = 48_000

        /** 起播预灌包数：8 × 7.33ms ≈ 59ms，跨过 hybrid63 原版编码器的 80ms prime 前段。 */
        private const val PREFILL_PACKETS = 8

        /** 卡顿后允许落后多少：超过只跳过错过的节拍，绝不突发补包（会和接收端时钟打架）。 */
        private const val MAX_CATCH_UP_NANOS = 20_000_000L

        /** [stop] 等待水泵线程退出的上限。 */
        private const val JOIN_TIMEOUT_MILLIS = 200L
    }

    @Volatile
    private var running = false

    private var worker: Thread? = null

    /** 已写出包数 / 纯净音包数 / 下游拒收（例如 AudioSender 未运行）包数（诊断用）。 */
    @Volatile
    private var packets = 0L

    @Volatile
    private var silencePackets = 0L

    @Volatile
    private var droppedPackets = 0L

    @Volatile
    private var mixedFrames = 0L

    val isRunning: Boolean get() = running

    /** 启动水泵线程（幂等）。 */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val thread =
            Thread({ writerLoop() }, "carlife-audio-pipe").apply {
                isDaemon = true
                start()
            }
        worker = thread
    }

    /** 停止水泵线程（幂等；最长等待 [JOIN_TIMEOUT_MILLIS]）。 */
    @Synchronized
    fun stop() {
        if (!running && worker == null) return
        running = false
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            runCatching { thread.join(JOIN_TIMEOUT_MILLIS) }
        }
        log("CARLIFE-AUDIO: 1408B 包水泵停止（累计 $packets 包，其中静音/不完整 $silencePackets 包，下游未接收 $droppedPackets 包）")
    }

    /** 诊断摘要（会话日志/导出用）。 */
    fun stats(): String =
        "1408B 包=$packets 静音包=$silencePackets 下游未接收=$droppedPackets 混入TTS帧=$mixedFrames"

    /** 水泵主循环：预灌 → 绝对时间线实时发包。 */
    private fun writerLoop() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {
            // 调优先级失败不影响数据面（与 hybrid63 一致）。
        }
        val packet = ByteArray(CHUNK_BYTES)
        val scratch = ByteArray(CHUNK_BYTES)
        // 一包的实时时长（纳秒；48k 立体声 = 7.333ms）。
        val packetNanos = CHUNK_BYTES.toLong() * 1_000_000_000L / (sampleRate.coerceAtLeast(1).toLong() * BYTES_PER_FRAME)
        var nextAtNanos = System.nanoTime()
        try {
            // 预灌 8 包（约 59ms）：跨过原版编码器的 prime 门槛后才开始按实时节拍送。
            for (index in 0 until PREFILL_PACKETS) {
                if (!running) return
                if (!emitPacket(packet, scratch)) return
            }
            log(
                "CARLIFE-AUDIO: 1408B 包水泵启动（$sampleRate Hz 立体声，预灌 $PREFILL_PACKETS 包，" +
                    "节拍 ${packetNanos / 1_000_000.0}ms/包）",
            )
            while (running) {
                val now = System.nanoTime()
                if (now < nextAtNanos) {
                    val sleepMillis = (nextAtNanos - now) / 1_000_000L
                    if (sleepMillis > 0) {
                        try {
                            Thread.sleep(sleepMillis)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } else if (now - nextAtNanos > MAX_CATCH_UP_NANOS) {
                    // 进程卡顿后不突发补包：只把时间线拉到当前时刻。
                    nextAtNanos = now
                }
                if (!running) break
                if (!emitPacket(packet, scratch)) return
                nextAtNanos += packetNanos
            }
        } finally {
            running = false
            log("CARLIFE-AUDIO: 1408B 包水泵线程退出（累计 $packets 包，静音/不完整 $silencePackets 包，混入 TTS $mixedFrames 帧）")
        }
    }

    /** 组装并写出一包：[readSource] 补齐 → 真无数据才补零 → 混 TTS → [writePacket]。 */
    private fun emitPacket(
        packet: ByteArray,
        scratch: ByteArray,
    ): Boolean {
        var filled = 0
        while (filled < CHUNK_BYTES && running) {
            val read =
                try {
                    readSource(scratch, 0, minOf(scratch.size, CHUNK_BYTES - filled))
                } catch (_: Throwable) {
                    0
                }
            if (read <= 0) break
            System.arraycopy(scratch, 0, packet, filled, read)
            filled += read
        }
        if (filled < CHUNK_BYTES) {
            // 只有这拍真的取不到 PCM（引擎静默/抖动）才补零到整包，保证固定包长与节拍。
            java.util.Arrays.fill(packet, filled, CHUNK_BYTES, 0)
            silencePackets++
        }
        if (running) {
            mixedFrames +=
                runCatching { mixTts(packet, FRAMES_PER_CHUNK, sampleRate) }.getOrDefault(0)
        }
        val written = try {
            writePacket(packet, CHUNK_BYTES)
        } catch (_: Throwable) {
            false
        }
        if (!written) {
            droppedPackets++
            log("CARLIFE-AUDIO: 下游写入失败（累计 $droppedPackets 包）——水泵停止")
            return false
        }
        packets++
        return true
    }
}
