/* Adapted from:
 *  - CarPToCarL carlife/CarLifeAudioSink.kt (MIT, Copyright (c) 2026 lizhihua), commit 7b729fb:
 *    媒体/TTS 分流职责、格式宣告、音乐暂停残留 PCM 截断、电平/带宽诊断日志；
 *  - 历史参考实现（同一作者的历史音频实现，只读参考）：
 *    媒体 ring + TTS 独立 ring、44.1k→48k 跨包相位连续线性重采样、TTS duck/mix；
 *  - 历史参考实现的 CarLifeAudioPipe 的 1408B 包/预灌/实时节拍，已实现为 CarLifeAudioPipe.kt。
 * link_plus 适配点：原参考实现直接调 AudioSender.feedPcm（内部再对齐/累积），本仓改为
 * CarLifeAudioPipe 按 1408B 实时包输出，调用 AudioSender.feedExternalPcm（外部 PCM 模式）。 */
package io.github.shaonialife.buicklinkplus.carlife

import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.audio.AudioSender
import io.github.shaonialife.buicklinkplus.util.LogThrottle

/**
 * CarLife 音频桥（手机 CarLife 引擎 → 车机 RTP 直通）。
 *
 * 两条 PCM 流：媒体 9240（实测 44.1kHz 立体声）与 TTS/导航 9241（实测 16kHz 单声道）。
 * 本类把它们统一成 **48kHz 立体声 16bit**，由 [CarLifeAudioPipe] 切成 1408B（352 帧）包，
 * 实时喂给 [AudioSender.feedExternalPcm]（外部 PCM 模式，不依赖 MediaProjection 采集）：
 *
 * - 媒体 ring 保留引擎原始格式字节，读包时才做 44.1k→48k 线性重采样（相位 [mediaPhase]
 *   跨包连续，避免每包重置造成周期性爆音）与单声道→立体声扩展；
 * - TTS 用**独立 ring**（不能覆盖媒体格式），按自己的相位重采样后 duck 媒体到 45%、
 *   TTS 90% 混进同一个 1408B 包；TTS 只在最近 400ms 内确实收到 PCM 时才混；
 * - 空数据才补零（真无 PCM / 取不满一包），不主动塞静音；引擎静默时水泵仍按节拍发
 *   静音包，维持接收端解码管线与时钟（真 iPhone 也是连续发流，CarPlay 接收端停流会判空闲）；
 * - 音乐会话暂停后的 [PAUSE_DROP_WINDOW_MS] 窗口内丢弃媒体残留 PCM（避免重复播放）；
 *   TTS 通道不套用该规则（导航播报多发生在没放音乐/暂停时）。
 *
 * 线程模型：媒体 9240 读线程调 [writeMedia]，TTS 9241 读线程调 [writeTts]，水泵线程调
 * [CarLifeAudioPipe] 的读包/混音/送出回调；锁只覆盖各自的 ring/相位，日志与发送不在锁内
 * 长时间持有。Playback 暂停联动由外部（SessionController/媒体会话状态）调 [setPlaybackPaused]。
 *
 * @param log 日志出口（App 会话日志）
 * @param senderProvider 取当前 AudioSender（音频 SETUP 成功后才存在；为空时水泵照跑但丢包，
 *   AudioSender 起来后自动接上）
 * @param dropWhenPaused 是否启用「音乐会话暂停 → 丢弃媒体残留 PCM」联动（TTS 永不丢）
 */
class CarLifeAudioSink(
    private val log: (String) -> Unit = {},
    private val senderProvider: () -> AudioSender?,
    private val dropWhenPaused: Boolean = true,
    /** 媒体音源开关（设置「CarLife 音源」，默认开）。 */
    private val mediaEnabled: () -> Boolean = { true },
    /** TTS 导航播报开关（设置「TTS 导航播报」，默认开）。 */
    private val ttsEnabled: () -> Boolean = { true },
) {
    // ---- 媒体 9240：源格式 ring + 跨包相位重采样 ----

    private val mediaLock = Any()
    private val mediaRing = ByteRing(MEDIA_RING_BYTES)

    /** 引擎宣告的媒体采样率 / 声道数（0/2 = 尚未宣告，按 48k 立体声处理）。 */
    private var mediaRate = 0
    private var mediaChannels = 2

    /** 44.1k→48k 重采样的相位（跨包连续）。 */
    private var mediaPhase = 0.0
    private var mediaResamples = 0L

    // ---- TTS 9241：独立 ring + 独立重采样相位 ----

    private val ttsLock = Any()
    private val ttsRing = ByteRing(TTS_RING_BYTES)
    private var ttsRate = 16_000
    private var ttsChannels = 1
    private var ttsPhase = 0.0

    @Volatile
    private var ttsLastPcmAt = 0L
    private var ttsMixedFrames = 0L

    // ---- 生命周期 ----

    /** 水泵已启动（引擎连接期间为 true）。 */
    @Volatile
    private var started = false

    /** 外部明确 stop 过：禁止写数据路径把水泵重新拉起来。 */
    @Volatile
    private var stoppedByOwner = false

    /** 引擎音频开停信号（0x30002/0x30003），只用于 [isAudioExpected] 诊断。 */
    @Volatile
    private var engineAudioActive = false

    /** 1408B 实时包水泵。 */
    private val pipe: CarLifeAudioPipe

    // ---- 诊断统计 ----

    private var packets = 0L
    private var bytes = 0L
    private var lastReportAt = 0L
    private var ttsBytes = 0L
    private var droppedNoSender = 0L
    private var peakSinceReport = 0
    private var nonZeroSinceReport = 0L
    private var sampledSinceReport = 0L

    /** 最近一次收到媒体 PCM 的时刻（uptimeMillis）；[isAudioExpected] 用。 */
    @Volatile
    private var lastMediaPcmAt = 0L

    // ---- 「音乐暂停 → 丢弃残留 PCM」联动 ----

    @Volatile
    private var playbackPaused = false

    @Volatile
    private var pausedSince = 0L

    private var pauseWindowExpiredLogged = false
    private var droppedWhenPaused = 0L
    private var lastDropReportAt = 0L

    /** 丢包日志限流（AudioSender 尚未启动时避免每包一行）。 */
    private val dropLog = LogThrottle(5_000L)

    init {
        pipe =
            CarLifeAudioPipe(
                readSource = ::readMedia48kStereo,
                mixTts = ::mixTtsIntoPacket,
                writePacket = ::writeToSender,
                sampleRate = TARGET_RATE,
                log = log,
            )
    }

    /** 诊断摘要（会话日志/日志导出用）。 */
    fun stats(): String =
        "媒体包=$packets 源字节=$bytes TTS字节=$ttsBytes 暂停丢弃=$droppedWhenPaused " +
            "重采样=$mediaResamples TTS混入帧=$ttsMixedFrames 发送端缺失丢弃=$droppedNoSender " +
            "｜${pipe.stats()}"

    // ---- 生命周期 ----

    /** 引擎连接建立后调用：启动 1408B 水泵（无声时也按节拍发静音包保活接收端）。 */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        stoppedByOwner = false
        pipe.start()
        log(
            "CARLIFE: 音频直通已启动（媒体 9240 + TTS 9241 → 48kHz 立体声 1408B 包 → AudioSender 外部 PCM；" +
                "暂停联动=${if (dropWhenPaused) "开" else "关"}）",
        )
    }

    /** 会话断开/内容源 detach 时调用：停水泵并清空缓冲（幂等）。 */
    @Synchronized
    fun stop() {
        stoppedByOwner = true
        started = false
        pipe.stop()
        synchronized(mediaLock) {
            mediaRing.clear()
            mediaPhase = 0.0
        }
        synchronized(ttsLock) {
            ttsRing.clear()
            ttsPhase = 0.0
        }
        reportIfDue(force = true)
        log("CARLIFE: 音频直通已停止（累计 $packets 包 / ${bytes / 1024}KB）")
    }

    /** 断线重连：清空两条 ring 的残留（避免上一轮排队的 PCM 在新连接里重放），水泵保持运行。 */
    fun reset() {
        synchronized(mediaLock) {
            mediaRing.clear()
            mediaPhase = 0.0
        }
        synchronized(ttsLock) {
            ttsRing.clear()
            ttsPhase = 0.0
            ttsLastPcmAt = 0L
        }
        log("CARLIFE: 音频直通缓冲已重置（等待引擎重新宣告格式/PCM）")
    }

    private fun ensureStarted() {
        if (stoppedByOwner) return
        if (!started) {
            start()
        } else if (!pipe.isRunning) {
            pipe.start()
        }
    }

    // ---- 媒体 9240 ----

    /** 引擎宣告媒体音频格式（CarlifeMusicInit）。重复调用无副作用。 */
    fun configureMedia(
        sampleRate: Int,
        channels: Int,
    ) {
        val rate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        val channelCount = if (channels == 1) 1 else 2
        var changed = false
        synchronized(mediaLock) {
            changed = rate != mediaRate || channelCount != mediaChannels
            mediaRate = rate
            mediaChannels = channelCount
            if (changed) {
                mediaRing.clear()
                mediaPhase = 0.0
            }
        }
        log(
            "CARLIFE: 媒体音频格式 ${rate}Hz ${if (channelCount == 1) "单声道" else "立体声"}" +
                " 16bit（直通→${TARGET_RATE}Hz 立体声 1408B 包" +
                (if (rate != TARGET_RATE) "，跨包相位重采样" else "") + "）",
        )
    }

    /** 收到一包媒体裸 PCM（16bit 小端交织）。由 9240 读线程调用，绝不阻塞。 */
    fun writeMedia(
        pcm: ByteArray,
        size: Int,
    ) {
        if (size <= 0 || size > pcm.size) return
        if (stoppedByOwner) return
        if (!mediaEnabled()) return
        val now = SystemClock.uptimeMillis()
        lastMediaPcmAt = now
        // 音乐会话暂停后的一小段窗口内：丢弃引擎仍在推的残留 PCM（避免重复播放）。
        if (dropWhenPaused && shouldDropPaused(now)) {
            countDroppedWhenPaused()
            return
        }
        ensureStarted()
        sampleLevel(pcm, size)
        synchronized(mediaLock) { mediaRing.write(pcm, 0, size) }
        packets++
        bytes += size
        reportIfDue()
    }

    private fun shouldDropPaused(now: Long): Boolean {
        if (!playbackPaused) return false
        val since = pausedSince
        if (since == 0L) return true
        if (now - since < PAUSE_DROP_WINDOW_MS) return true
        if (!pauseWindowExpiredLogged) {
            pauseWindowExpiredLogged = true
            log(
                "CARLIFE: 暂停已超过 ${PAUSE_DROP_WINDOW_MS / 1000} 秒——恢复放行 PCM" +
                    "（残留队列早已排空，避免误丢导航播报/提示音）",
            )
        }
        return false
    }

    private fun countDroppedWhenPaused() {
        droppedWhenPaused++
        val now = SystemClock.uptimeMillis()
        if (lastDropReportAt == 0L) {
            lastDropReportAt = now
            return
        }
        if (now - lastDropReportAt < DROP_REPORT_INTERVAL_MS) return
        log("CARLIFE: 暂停期间丢弃 $droppedWhenPaused 包 PCM（若此时本应有导航/媒体声音，说明会话状态被误判）")
        droppedWhenPaused = 0
        lastDropReportAt = now
    }

    /**
     * 外部联动：音乐会话暂停时置 true（丢弃媒体残留 PCM），恢复播放时置 false。
     */
    fun setPlaybackPaused(paused: Boolean) {
        val changed = playbackPaused != paused
        playbackPaused = paused
        if (!paused) {
            pausedSince = 0L
            pauseWindowExpiredLogged = false
            if (changed) log("CARLIFE: 音乐会话恢复——停止丢弃引擎 PCM")
            return
        }
        if (changed || pausedSince == 0L) {
            pausedSince = SystemClock.uptimeMillis()
            pauseWindowExpiredLogged = false
            log(
                "CARLIFE: 音乐会话暂停——丢弃引擎残留 PCM（避免重复播放，" +
                    "${PAUSE_DROP_WINDOW_MS / 1000} 秒后自动恢复放行）",
            )
        }
    }

    /** 引擎音频开停信号（0x30002 开始 / 0x30003 停止）。 */
    fun setAudioGate(active: Boolean) {
        engineAudioActive = active
    }

    /**
     * 诊断：当前是否「应该有声音」。
     *
     * 音频沉默看门狗（CarLifeHuClient.audioSupervisor）用它决定要不要因 9240 长时间零 PCM
     * 而重连。这里只在「未暂停且最近确实有媒体 PCM（或引擎刚说过音频开始）」时返回 true，
     * 避免用户只是停在桌面没放音乐也被当成故障反复重连。
     */
    fun isAudioExpected(): Boolean {
        if (playbackPaused) return false
        if (engineAudioActive) return true
        val last = lastMediaPcmAt
        if (last == 0L) return false
        return SystemClock.uptimeMillis() - last < AUDIO_EXPECTED_WINDOW_MS
    }

    // ---- TTS 9241 ----

    /** 引擎宣告 TTS 通道音频格式（导航播报常见 16kHz 单声道）。 */
    fun configureTts(
        sampleRate: Int,
        channels: Int,
    ) {
        val rate = sampleRate.coerceIn(MIN_RATE, MAX_RATE)
        val channelCount = if (channels == 1) 1 else 2
        var changed = false
        synchronized(ttsLock) {
            changed = rate != ttsRate || channelCount != ttsChannels
            ttsRate = rate
            ttsChannels = channelCount
            if (changed) {
                ttsRing.clear()
                ttsPhase = 0.0
            }
        }
        log(
            "CARLIFE: TTS 音频格式 ${rate}Hz ${if (channelCount == 1) "单声道" else "立体声"}" +
                " 16bit（导航播报 → 混入 ${TARGET_RATE}Hz 立体声 1408B 包）",
        )
    }

    /** 收到一包 TTS 裸 PCM（16bit 小端交织）。由 9241 读线程调用，绝不阻塞。 */
    fun writeTts(
        pcm: ByteArray,
        size: Int,
    ) {
        if (size <= 0 || size > pcm.size) return
        if (stoppedByOwner) return
        if (!ttsEnabled()) return
        ensureStarted()
        synchronized(ttsLock) {
            ttsRing.write(pcm, 0, size)
            ttsLastPcmAt = SystemClock.uptimeMillis()
        }
        ttsBytes += size
    }

    // ---- 水泵回调：媒体读包 / TTS 混音 / 送 AudioSender ----

    /**
     * 从媒体 ring 取一段并统一成 48kHz 立体声（历史参考实现 `CarLifeAudio.fill` 的实现）。
     *
     * @return 实际写入字节数（4 的整数倍）；0 = 当前无数据
     */
    private fun readMedia48kStereo(
        out: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        synchronized(mediaLock) {
            if (mediaRing.count <= 0) return 0
            val srcRate = if (mediaRate > 0) mediaRate else TARGET_RATE
            val srcBpf = if (mediaChannels == 1) 2 else TARGET_FRAME_BYTES
            val maxOutFrames = length / TARGET_FRAME_BYTES
            if (maxOutFrames <= 0) return 0
            val src = mediaRing.drainAll()
            val srcFrames = src.size / srcBpf
            if (srcFrames <= 0) return 0
            if (srcRate == TARGET_RATE) {
                val outFrames = minOf(maxOutFrames, srcFrames)
                for (frame in 0 until outFrames) {
                    val dst = offset + frame * TARGET_FRAME_BYTES
                    val from = frame * srcBpf
                    if (srcBpf == 2) {
                        // 单声道 → 立体声：左右复制同一采样。
                        out[dst] = src[from]
                        out[dst + 1] = src[from + 1]
                        out[dst + 2] = src[from]
                        out[dst + 3] = src[from + 1]
                    } else {
                        out[dst] = src[from]
                        out[dst + 1] = src[from + 1]
                        out[dst + 2] = src[from + 2]
                        out[dst + 3] = src[from + 3]
                    }
                }
                val remainFrames = srcFrames - outFrames
                if (remainFrames > 0) {
                    mediaRing.pushFront(src, outFrames * srcBpf, remainFrames * srcBpf)
                }
                return outFrames * TARGET_FRAME_BYTES
            }
            // 44.1k→48k（或其它比值）线性重采样：相位跨包连续，只消费真正用到的源帧。
            var pos = mediaPhase
            val step = srcRate.toDouble() / TARGET_RATE
            var outFrames = 0
            while (outFrames < maxOutFrames && pos + 1.0 < srcFrames) {
                val i0 = pos.toInt()
                val frac = pos - i0
                val left = interpolateSample(src, i0, i0 + 1, srcBpf, 0, frac)
                val right = interpolateSample(src, i0, i0 + 1, srcBpf, 1, frac)
                val dst = offset + outFrames * TARGET_FRAME_BYTES
                out[dst] = left.toByte()
                out[dst + 1] = (left shr 8).toByte()
                out[dst + 2] = right.toByte()
                out[dst + 3] = (right shr 8).toByte()
                outFrames++
                pos += step
            }
            val consumedFrames = pos.toInt()
            mediaPhase = pos - consumedFrames
            mediaResamples++
            val remainFrames = srcFrames - consumedFrames
            if (remainFrames > 0) {
                mediaRing.pushFront(src, consumedFrames * srcBpf, remainFrames * srcBpf)
            }
            outFrames * TARGET_FRAME_BYTES
        }

    /**
     * TTS duck/mix（历史参考实现 `CarLifeAudio.mixTts` 的实现）：媒体 45% + TTS 90%，
     * 只消费本包实际用到的 TTS 源帧，剩余留到下一包；只在本包有 TTS 时改媒体采样。
     *
     * @return 本包混入的采样帧数（0 = 本包无 TTS）
     */
    private fun mixTtsIntoPacket(
        packet: ByteArray,
        outFrames: Int,
        outRate: Int,
    ): Int =
        synchronized(ttsLock) {
            val ttsCount = ttsRing.count
            if (ttsCount < 4) return 0
            val now = SystemClock.uptimeMillis()
            if (now - ttsLastPcmAt > TTS_MIX_WINDOW_MS) return 0
            val srcBpf = if (ttsChannels == 1) 2 else TARGET_FRAME_BYTES
            val srcFrames = ttsCount / srcBpf
            if (srcFrames < 2) return 0
            val src = ttsRing.drainAll()
            val step = maxOf(0.25, ttsRate.toDouble() / outRate.coerceAtLeast(1))
            var pos = ttsPhase
            var mixed = 0
            while (mixed < outFrames && pos + 1.0 < srcFrames) {
                val i0 = pos.toInt()
                val frac = pos - i0
                val ttsLeft = interpolateSample(src, i0, i0 + 1, srcBpf, 0, frac)
                val ttsRight = interpolateSample(src, i0, i0 + 1, srcBpf, if (ttsChannels == 1) 0 else 1, frac)
                val dst = mixed * TARGET_FRAME_BYTES
                val mediaLeft = ((packet[dst].toInt() and 0xFF) or (packet[dst + 1].toInt() shl 8)).toShort().toInt()
                val mediaRight = ((packet[dst + 2].toInt() and 0xFF) or (packet[dst + 3].toInt() shl 8)).toShort().toInt()
                val outLeft = (mediaLeft * MEDIA_DUCK + ttsLeft * TTS_GAIN).toInt().coerceIn(-32_768, 32_767)
                val outRight = (mediaRight * MEDIA_DUCK + ttsRight * TTS_GAIN).toInt().coerceIn(-32_768, 32_767)
                packet[dst] = outLeft.toByte()
                packet[dst + 1] = (outLeft shr 8).toByte()
                packet[dst + 2] = outRight.toByte()
                packet[dst + 3] = (outRight shr 8).toByte()
                mixed++
                pos += step
            }
            val consumedFrames = pos.toInt().coerceIn(0, srcFrames)
            ttsPhase = pos - consumedFrames
            ttsMixedFrames += mixed
            val remainFrames = srcFrames - consumedFrames
            if (remainFrames > 0) {
                ttsRing.pushFront(src, consumedFrames * srcBpf, remainFrames * srcBpf)
            } else {
                ttsRing.clear()
            }
            mixed
        }

    /** 送出一整包 1408B 给 AudioSender 外部 PCM 入口；AudioSender 尚未启动时静默丢弃。 */
    private fun writeToSender(
        packet: ByteArray,
        length: Int,
    ): Boolean {
        val sender = senderProvider()
        if (sender == null || !sender.isRunning) {
            droppedNoSender++
            if (dropLog.allow("no-sender")) {
                log("CARLIFE: AudioSender 尚未启动/已停止——丢弃音频包 $droppedNoSender 个（视频不受影响）")
            }
            // 返回 true：水泵继续跑，等 AudioSender 起来后自动接上。
            return true
        }
        return sender.feedExternalPcm(packet, length, TARGET_RATE, TARGET_CHANNELS, isTts = false)
    }

    // ---- 采样电平/统计 ----

    /** 抽样统计电平（每 16 个采样点取 1 个，开销可忽略）。 */
    private fun sampleLevel(
        pcm: ByteArray,
        size: Int,
    ) {
        var index = 0
        while (index + 1 < size) {
            val sample = (((pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)).toShort()).toInt()
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peakSinceReport) peakSinceReport = magnitude
            if (magnitude >= SILENCE_FLOOR) nonZeroSinceReport++
            sampledSinceReport++
            index += 32
        }
    }

    private fun reportIfDue(force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (lastReportAt == 0L) {
            lastReportAt = now
            if (!force) return
        }
        if (!force && now - lastReportAt < REPORT_INTERVAL_MS) return
        val seconds = (now - lastReportAt) / 1000.0
        val kbps = if (seconds > 0) (bytes * 8 / 1000.0 / seconds).toInt() else 0
        val peakPercent = peakSinceReport * 100 / 32_768
        val nonZeroPercent = if (sampledSinceReport > 0) (nonZeroSinceReport * 100 / sampledSinceReport).toInt() else 0
        log(
            "CARLIFE: 音频直通 $packets 包 / ${bytes / 1024}KB / ${kbps}kbps" +
                " · 电平 $peakPercent%（非零 $nonZeroPercent%）" +
                " · TTS 混入 $ttsMixedFrames 帧",
        )
        lastReportAt = now
        packets = 0
        bytes = 0
        peakSinceReport = 0
        nonZeroSinceReport = 0
        sampledSinceReport = 0
    }

    // ---- 工具 ----

    /** 线性插值取一个 16bit s16 采样（left/right 由 channel + 源声道数决定）。 */
    private fun interpolateSample(
        src: ByteArray,
        i0: Int,
        i1: Int,
        srcBpf: Int,
        channel: Int,
        frac: Double,
    ): Int {
        val o0 = i0 * srcBpf + if (channel == 1 && srcBpf >= TARGET_FRAME_BYTES) 2 else 0
        val o1 = i1 * srcBpf + if (channel == 1 && srcBpf >= TARGET_FRAME_BYTES) 2 else 0
        val a = ((src[o0].toInt() and 0xFF) or (src[o0 + 1].toInt() shl 8)).toShort().toInt()
        val b = ((src[o1].toInt() and 0xFF) or (src[o1 + 1].toInt() shl 8)).toShort().toInt()
        return Math.round(a + (b - a) * frac).toInt()
    }

    /**
     * 简单环形字节缓冲（历史参考实现 `CarLifeAudio` 里 ring/head/tail/count 的 Kotlin 版）。
     */
    private class ByteRing(
        private val capacity: Int,
    ) {
        private val data = ByteArray(capacity)
        private var head = 0
        private var tail = 0
        var count = 0
            private set

        /** 写入；空间不足时丢弃最旧字节（低延迟优先，与历史参考实现一致）。 */
        fun write(
            src: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (length <= 0) return
            var from = offset
            var len = length
            if (len > capacity) {
                from += len - capacity
                len = capacity
            }
            while (count + len > capacity) {
                val drop = minOf(count, count + len - capacity)
                tail = (tail + drop) % capacity
                count -= drop
            }
            val first = minOf(len, capacity - head)
            System.arraycopy(src, from, data, head, first)
            if (first < len) System.arraycopy(src, from + first, data, 0, len - first)
            head = (head + len) % capacity
            count += len
        }

        /** 按环形顺序取出全部字节并清空。 */
        fun drainAll(): ByteArray {
            if (count <= 0) return ByteArray(0)
            val out = ByteArray(count)
            val first = minOf(count, capacity - tail)
            System.arraycopy(data, tail, out, 0, first)
            if (first < count) System.arraycopy(data, 0, out, first, count - first)
            clear()
            return out
        }

        /** 把未消费的尾部放回队首（覆盖式，对应历史参考实现的 pushFront；超容量时只留最新字节）。 */
        fun pushFront(
            src: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (length <= 0) return
            var from = offset
            var len = length
            if (len > capacity) {
                from += len - capacity
                len = capacity
            }
            head = 0
            tail = 0
            System.arraycopy(src, from, data, 0, len)
            head = len % capacity
            count = len
        }

        fun clear() {
            head = 0
            tail = 0
            count = 0
        }
    }

    private companion object {
        /** 统一输出：48kHz 立体声 16bit（一采样帧 4 字节）。 */
        const val TARGET_RATE = 48_000
        const val TARGET_CHANNELS = 2
        const val TARGET_FRAME_BYTES = TARGET_CHANNELS * 2

        const val MIN_RATE = 8_000
        const val MAX_RATE = 48_000

        /** 媒体 ring：约 250ms 48k 立体声，超了丢最旧（低延迟优先）。 */
        const val MEDIA_RING_BYTES = 48 * 1024

        /** TTS ring：16k 单声道约 1s，导航播报允许稍多排队。 */
        const val TTS_RING_BYTES = 32 * 1024

        const val REPORT_INTERVAL_MS = 5_000L
        const val DROP_REPORT_INTERVAL_MS = 30_000L

        /** 暂停后只在这段窗口内丢弃 PCM（引擎残留队列几秒就排空，避免长期误丢导航/提示音）。 */
        const val PAUSE_DROP_WINDOW_MS = 15_000L

        /** 电平统计的「非零」门限（约 -66dBFS，低于它的样本算静音底噪）。 */
        const val SILENCE_FLOOR = 16

        /** TTS 只在这段时间内收到过 PCM 才混入（避免旧播报残留被反复混）。 */
        const val TTS_MIX_WINDOW_MS = 400L

        /** TTS 活跃时媒体 duck 到 45%，TTS 90%（历史实车调参）。 */
        const val MEDIA_DUCK = 0.45
        const val TTS_GAIN = 0.90

        /** [isAudioExpected] 的「最近有 PCM」窗口：60 秒内有过声音才算「应该有声音」。 */
        const val AUDIO_EXPECTED_WINDOW_MS = 60_000L
    }
}
