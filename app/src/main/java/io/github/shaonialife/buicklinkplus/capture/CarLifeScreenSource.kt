/* P1A CarLife 内容源：CarLife H.264 解码后直出到 VideoSender 的编码器输入 Surface。
 * CarLife 客户端协议/解码逻辑来自 CarPToCarL（MIT, Copyright (c) 2026 lizhihua）与旧工程
 * hybrid63 的 CarLifeBridge/解码移植；本文件只做 link_plus 的 ScreenSource 生命周期与线程编排。 */
package io.github.shaonialife.buicklinkplus.capture

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import io.github.shaonialife.buicklinkplus.carlife.CarLifeHuClient
import io.github.shaonialife.buicklinkplus.carlife.CarLifeProtocol
import io.github.shaonialife.buicklinkplus.carlife.CarLifeVideoDecoder
import io.github.shaonialife.buicklinkplus.carlife.CarLifeVideoPipeline
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.util.LogThrottle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * P1A CarLife 内容源：把手机 CarLife+ 的 H.264 码流解码后**直接画到 VideoSender 的编码器
 * 输入 Surface**（单 Surface 直出，暂不做壁纸/圆角/letterbox 合成）。
 *
 * 生命周期：[attach] 只存参数、起一条后台管理线程并立即返回（CarLife 引擎通常还没起来，
 * 用户手动打开 CarLife+ 的「无线连接」后才可用）；管理线程循环创建 [CarLifeHuClient]，
 * 连不上就每 2s 重试，连上后持续转发视频帧、断线后自动收尾重连。[detach] 停客户端/解码器/
 * 管理线程，幂等；之后再次 [attach] 可重新工作。
 *
 * 视频路径（全部不阻塞 CarLife 视频读线程）：
 * ```
 * CarLifeHuClient.onVideoFrame
 *   → CarLifeVideoPipeline.feed（NAL/CSD 维护、裸 IDR 补 CSD、SPS 尺寸解析）
 *     → CarLifeVideoDecoder.queueFrame（有界队列，满时丢旧帧）
 *       → MediaCodec 解码 → releaseOutputBuffer(render=true) → 编码器输入 Surface
 * ```
 * 解码器生命周期（创建/重建/停止）收敛到单线程 [Session.executor]，只在
 * 「CSD 就绪」「SPS 分辨率变化」「解码线程退出」时调度，避免在视频读线程上做 MediaCodec 操作。
 *
 * 诊断：对外暴露 [isEngineConnected]/[deliveredFrames]/[streamWidth]/[streamHeight]/
 * [lastErrorText]，供会话日志采样；关键日志含 `carlife connected` / `decoder out` / `fit`。
 * 等待连接的重试日志限流（首次即时、之后最多每 10s 一行）；已握手但 ≥5s 无视频帧时输出
 * 一行带 CSD/码流尺寸的看门狗日志，方便现场区分「手机没进无线连接」与「有码流但解不出」。
 */
class CarLifeScreenSource(
    private val context: Context,
    private val log: (String) -> Unit = {},
) : ScreenSource {
    override val name: String = "CarLife 内容源"

    /** 诊断：CarLife 主机端口是否已完成握手（即客户端 start 成功）。 */
    @Volatile
    var isEngineConnected: Boolean = false
        private set

    /** 诊断：已渲染到编码器 Surface 的输出帧数。 */
    @Volatile
    var deliveredFrames: Long = 0L
        private set

    /** 诊断：SPS 解析出的码流真实宽度（0 = 尚未解析到）。 */
    @Volatile
    var streamWidth: Int = 0
        private set

    /** 诊断：SPS 解析出的码流真实高度（0 = 尚未解析到）。 */
    @Volatile
    var streamHeight: Int = 0
        private set

    /** 诊断：最近一次错误文本（连接成功后清空）。 */
    @Volatile
    var lastErrorText: String = ""
        private set

    private val lock = Any()

    /** 当前（最近一次 attach 的）会话；detach 时先置空，旧回调凭引用比对自动失效。 */
    @Volatile
    private var session: Session? = null

    override fun attach(
        surface: Surface,
        width: Int,
        height: Int,
        densityDpi: Int,
        geometry: DisplayGeometry,
        targetFps: Int,
    ): Boolean {
        detach()
        lastErrorText = ""
        isEngineConnected = false
        deliveredFrames = 0L
        streamWidth = 0
        streamHeight = 0

        val newSession = Session(surface, width, height, targetFps)
        newSession.pipeline =
            CarLifeVideoPipeline(
                object : CarLifeVideoPipeline.Listener {
                    override fun onFrame(
                        data: ByteArray,
                        offset: Int,
                        length: Int,
                        ptsMs: Long,
                        keyFrame: Boolean,
                    ) = handleFrame(newSession, data, offset, length, ptsMs, keyFrame)

                    override fun onStreamSize(
                        width: Int,
                        height: Int,
                    ) = handleStreamSize(newSession, width, height)
                },
            )
        synchronized(lock) { session = newSession }
        val managerThread = Thread({ manage(newSession) }, MANAGER_THREAD_NAME)
        managerThread.isDaemon = true
        newSession.manager = managerThread
        managerThread.start()
        log(
            "CARLIFE: 内容源已挂载 ${width}x$height@${targetFps}fps density=$densityDpi " +
                "content=${geometry.content} pkg=${context.packageName}（等待 CarLife+ 进入无线连接）",
        )
        return true
    }

    override fun detach() {
        val doomed =
            synchronized(lock) {
                val current = session
                session = null
                current
            } ?: return
        doomed.active = false
        synchronized(doomed.lock) { doomed.lock.notifyAll() }
        log("CARLIFE: 停止内容源（客户端/解码器/管理线程）")
        // 先停客户端以解除 socket 读写阻塞，管理线程才能尽快退出。
        val client = doomed.client
        doomed.client = null
        try {
            client?.stop()
        } catch (error: Exception) {
            lastErrorText = "停止 CarLife 客户端失败：${error.message}"
            log("CARLIFE: 停止客户端异常 - ${error.message}")
        }
        // 先关 codec 生命周期线程，避免停止过程中又新建解码器。
        doomed.executor.shutdownNow()
        doomed.pendingKeyFrame = null
        val decoder = doomed.decoder.getAndSet(null)
        if (decoder != null) {
            val exited = decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
            if (!exited) {
                log("CARLIFE-VIDEO: 解码线程未在 ${DECODER_STOP_TIMEOUT_MS}ms 内退出")
            }
        }
        val manager = doomed.manager
        if (manager != null && manager !== Thread.currentThread()) {
            try {
                manager.join(MANAGER_JOIN_TIMEOUT_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (manager.isAlive) {
                log("CARLIFE: 管理线程未在 ${MANAGER_JOIN_TIMEOUT_MS}ms 内退出（可能仍在等待引擎连接超时），已放弃等待")
            }
        }
        isEngineConnected = false
        streamWidth = 0
        streamHeight = 0
        log("CARLIFE: 内容源已停止")
    }

    override fun release() = detach()

    // ---- 管理线程：CarLife 客户端连接/重试/收尾 ----

    private fun manage(session: Session) {
        val pipeline = session.pipeline ?: return
        logForSession(session, "CARLIFE: 管理线程启动（等待 CarLife+ 打开无线连接）")
        // 连接失败重试的原样日志会被「7 端口 × 每 2s」刷屏，这里限流：首次立即一行，之后最多每 10s 一行。
        val waitLog = LogThrottle(RETRY_LOG_INTERVAL_MS)
        var retryCount = 0L
        while (session.active && isCurrent(session)) {
            val client =
                CarLifeHuClient(
                    host = HOST,
                    log = { message -> logForSession(session, message) },
                    onVideoFrame = { data, keyFrame, timestampMs ->
                        pipeline.feed(data, 0, data.size, timestampMs, keyFrame)
                    },
                    onVideoParams = { width, height, fps ->
                        logForSession(session, "CARLIFE: 协商编码参数 ${width}x$height@${fps}fps")
                    },
                    onStreamSize = { width, height ->
                        logForSession(session, "CARLIFE: 客户端 SPS 实测 ${width}x$height")
                    },
                    requestedFps = session.fps,
                    initialWidth = session.width,
                    initialHeight = session.height,
                    needAdapterScreen = true,
                    orderedInit = true,
                    settleReinit = true,
                    // 重试循环每 2s 建一次客户端：逐端口失败日志（每次 7 行）留给客户端调试，
                    // 现场只需要下面「等待 CarLife+ 无线连接」的限流行。
                    quietPortFailures = true,
                )
            session.client = client
            val started =
                try {
                    client.start()
                } catch (error: Exception) {
                    logForSession(session, "CARLIFE: 客户端启动异常 - ${error.message}")
                    false
                }
            if (!session.active || !isCurrent(session)) {
                client.stop()
                break
            }
            if (!started) {
                session.client = null
                client.stop()
                retryCount++
                setLastError(session, "CarLife 引擎未就绪（127.0.0.1 控制端口未连通）")
                // 首次立即打一行，之后最多每 10s 一行：现场能看出「还在等」但不会被每 2s 的
                // 7 端口失败日志刷屏（逐端口日志已由 quietPortFailures 关掉）。
                if (waitLog.allow("wait")) {
                    logForSession(
                        session,
                        "CARLIFE: 等待 CarLife+ 无线连接（127.0.0.1:${CarLifeProtocol.PORT_CMD}）" +
                            "第 $retryCount 次重试（打开 CarLife+ 并点「无线连接」）",
                    )
                }
                sleepWhileActive(session, RETRY_DELAY_MS)
                continue
            }
            setLastError(session, "")
            setEngineConnected(session, true)
            session.lastFrameAt = SystemClock.uptimeMillis()
            logForSession(session, "CARLIFE: carlife connected 127.0.0.1（握手成功，等待视频帧）")
            var lastNoVideoWarnAt = 0L
            while (session.active && isCurrent(session) && client.isRunning) {
                sleepWhileActive(session, STATUS_POLL_MS)
                if (!session.active || !isCurrent(session) || !client.isRunning) break
                // 已握手但长时间没有视频帧：诊断行带上管线状态（CSD 是否就绪、SPS 码流尺寸），
                // 现场一眼看出是「没进无线连接/手机没内容」还是「有码流但 SPS 没解析到」。
                val now = SystemClock.uptimeMillis()
                val idle = now - session.lastFrameAt
                if (idle < NO_VIDEO_WARN_MS) continue
                if (lastNoVideoWarnAt != 0L && now - lastNoVideoWarnAt < NO_VIDEO_WARN_INTERVAL_MS) continue
                lastNoVideoWarnAt = now
                val csdText = if (session.pipeline?.hasCsd == true) "已就绪" else "缺失"
                val streamText = session.pipeline?.streamSize?.let { "${it.first}x${it.second}" } ?: "未知"
                logForSession(
                    session,
                    "CARLIFE: 已握手但 ${idle / 1000}s 无视频帧" +
                        "（检查 CarLife+ 是否进入无线连接/是否有内容；CSD=$csdText，码流=$streamText）",
                )
            }
            setEngineConnected(session, false)
            if (session.active && isCurrent(session)) {
                logForSession(session, "CARLIFE: 连接已断开，收尾解码器/管线后 2s 重连")
            }
            client.stop()
            session.client = null
            teardownDecoder(session)
            pipeline.reset()
            if (!session.active || !isCurrent(session)) break
            sleepWhileActive(session, RETRY_DELAY_MS)
        }
        teardownDecoder(session)
        setEngineConnected(session, false)
        logForSession(session, "CARLIFE: 管理线程退出")
    }

    // ---- 视频路径：管线回调 → 解码器生命周期 ----

    private fun handleFrame(
        session: Session,
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsMs: Long,
        keyFrame: Boolean,
    ) {
        if (!isCurrent(session)) return
        // 无视频帧看门狗用：任意一帧（解码前也算）都证明码流还活着。
        session.lastFrameAt = SystemClock.uptimeMillis()
        val decoder = session.decoder.get()
        if (decoder != null) {
            // 解码器已就绪：不再需要为「晚建解码器」保留关键帧。
            session.pendingKeyFrame = null
            if (decoder.isAlive()) {
                decoder.queueFrame(data, offset, length, ptsMs, keyFrame)
            } else {
                scheduleDecoderRecreate(session, "解码器线程已退出")
            }
            return
        }
        // 只在 CSD 就绪后建解码器；创建放在单独的 codec 线程，不阻塞视频读线程。
        if (session.pipeline?.hasCsd == true) {
            // 建解码器期间先把触发创建的关键帧留着，起来后立刻解码，避免空等下一个 IDR。
            if (keyFrame) {
                session.pendingKeyFrame = PendingKeyFrame(data.copyOfRange(offset, offset + length), ptsMs)
            }
            scheduleDecoderCreate(session)
        }
    }

    private fun handleStreamSize(
        session: Session,
        width: Int,
        height: Int,
    ) {
        if (!isCurrent(session)) return
        streamWidth = width
        streamHeight = height
        logForSession(session, "CARLIFE-VIDEO: 码流真实尺寸 ${width}x$height（编码器 ${session.width}x${session.height}）")
        val decoder = session.decoder.get() ?: return
        if (width != session.appliedWidth || height != session.appliedHeight) {
            logFit(session, width, height)
            scheduleDecoderRecreate(session, "SPS 尺寸变化 -> ${width}x$height")
        }
    }

    /** 调度创建解码器（单线程执行器、去重、失败后退避 2s 再试）。 */
    private fun scheduleDecoderCreate(session: Session) {
        if (!session.active || !isCurrent(session)) return
        if (!session.surface.isValid) {
            noteSurfaceInvalid(session)
            return
        }
        if (SystemClock.uptimeMillis() < session.nextCreateAt) return
        if (!session.decoderScheduled.compareAndSet(false, true)) return
        val epoch = session.decoderEpoch.get()
        try {
            session.executor.execute {
                try {
                    createDecoder(session, epoch)
                } finally {
                    session.decoderScheduled.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            session.decoderScheduled.set(false)
            logForSession(session, "CARLIFE-VIDEO: 解码器调度被拒绝（执行器已关闭）- ${error.message}")
        }
    }

    /** 调度「停旧解码器 + 按当前 SPS 尺寸重建」，用于分辨率变化/解码线程退出。 */
    private fun scheduleDecoderRecreate(
        session: Session,
        reason: String,
    ) {
        if (!session.active || !isCurrent(session)) return
        if (!session.surface.isValid) {
            noteSurfaceInvalid(session)
            return
        }
        if (!session.decoderScheduled.compareAndSet(false, true)) return
        val epoch = session.decoderEpoch.incrementAndGet()
        logForSession(session, "CARLIFE-VIDEO: 重建解码器（$reason）")
        try {
            session.executor.execute {
                try {
                    if (!session.active || !isCurrent(session) || session.decoderEpoch.get() != epoch) return@execute
                    val old = session.decoder.getAndSet(null)
                    session.appliedWidth = 0
                    session.appliedHeight = 0
                    old?.stopAndWait(DECODER_STOP_TIMEOUT_MS)
                    createDecoder(session, epoch)
                } finally {
                    session.decoderScheduled.set(false)
                }
            }
        } catch (error: RejectedExecutionException) {
            session.decoderScheduled.set(false)
            logForSession(session, "CARLIFE-VIDEO: 重建调度被拒绝（执行器已关闭）- ${error.message}")
        }
    }

    /** 在 codec 线程执行：按当前管线 CSD/尺寸创建解码器（epoch 失效则丢弃新建的解码器）。 */
    private fun createDecoder(
        session: Session,
        epoch: Int,
    ) {
        if (!session.active || !isCurrent(session) || session.decoderEpoch.get() != epoch) return
        if (!session.surface.isValid) {
            noteSurfaceInvalid(session)
            return
        }
        val pipeline = session.pipeline ?: return
        val sps = pipeline.sps()
        val pps = pipeline.pps()
        if (sps == null || pps == null) {
            logForSession(session, "CARLIFE-VIDEO: CSD 尚未就绪，等待 SPS/PPS")
            return
        }
        val size = pipeline.streamSize ?: (session.width to session.height)
        val decoder =
            CarLifeVideoDecoder(
                size.first,
                size.second,
                session.fps,
                session.surface,
                DecoderCallbacks(session),
            )
        decoder.setCsd(sps, pps)
        if (!decoder.start()) {
            decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
            session.nextCreateAt = SystemClock.uptimeMillis() + CREATE_RETRY_DELAY_MS
            setLastError(session, "解码器启动失败（${size.first}x${size.second}）")
            logForSession(session, "CARLIFE-VIDEO: 解码器启动失败，${CREATE_RETRY_DELAY_MS}ms 后重试")
            return
        }
        session.decoder.set(decoder)
        session.appliedWidth = size.first
        session.appliedHeight = size.second
        session.nextCreateAt = 0L
        if (!session.active || !isCurrent(session) || session.decoderEpoch.get() != epoch) {
            // 创建期间会话已收尾/尺寸已再次变化：丢弃刚建好的解码器，避免泄漏与抢同一 Surface。
            if (session.decoder.compareAndSet(decoder, null)) decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
            return
        }
        // 先投喂建解码器期间暂存的关键帧（通常就是首帧 SPS+PPS+IDR）；没有暂存帧时用管线缓存的
        // 最新关键帧兜底——解码器（重建）起来后立即出画面，不必空等引擎下一个 IDR。
        val seed = session.pendingKeyFrame ?: pipeline.lastKeyFrame()?.let { PendingKeyFrame(it, 0L) }
        session.pendingKeyFrame = null
        seed?.let { decoder.queueFrame(it.data, 0, it.data.size, it.ptsMs, true) }
        logForSession(
            session,
            "CARLIFE-VIDEO: 解码器已启动 stream=${size.first}x${size.second} " +
                "encoder=${session.width}x${session.height} target=${session.fps}fps",
        )
        logFit(session, size.first, size.second)
    }

    /** 停掉当前解码器（管理线程断线收尾用；创建中的任务由 epoch 校验丢弃）。 */
    private fun teardownDecoder(session: Session) {
        session.decoderEpoch.incrementAndGet()
        session.pendingKeyFrame = null
        val decoder = session.decoder.getAndSet(null)
        session.appliedWidth = 0
        session.appliedHeight = 0
        session.nextCreateAt = 0L
        if (decoder != null) {
            logForSession(session, "CARLIFE-VIDEO: 停止解码器")
            decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
        }
    }

    // ---- 日志/诊断/睡眠工具 ----

    private fun isCurrent(session: Session): Boolean = this.session === session

    private fun setEngineConnected(
        session: Session,
        connected: Boolean,
    ) {
        if (isCurrent(session)) isEngineConnected = connected
    }

    private fun setLastError(
        session: Session,
        message: String,
    ) {
        if (isCurrent(session)) lastErrorText = message
    }

    private fun logForSession(
        session: Session,
        line: String,
    ) {
        if (isCurrent(session)) log(line)
    }

    private fun noteSurfaceInvalid(session: Session) {
        if (!session.surfaceInvalidLogged) {
            session.surfaceInvalidLogged = true
            logForSession(session, "CARLIFE-VIDEO: 编码器 Surface 已失效，停止重建解码器（会话收尾）")
        }
    }

    /** 码流与编码器画布尺寸关系：直出模式靠编码器 Surface 缩放，尺寸不一致会被拉伸。 */
    private fun logFit(
        session: Session,
        sourceWidth: Int,
        sourceHeight: Int,
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val encoderWidth = session.width
        val encoderHeight = session.height
        val scaleX = encoderWidth * 100 / sourceWidth / 100.0
        val scaleY = encoderHeight * 100 / sourceHeight / 100.0
        val mismatch = sourceWidth != encoderWidth || sourceHeight != encoderHeight
        val hint =
            if (mismatch) {
                "；尺寸不一致：直出到编码器 Surface 会按 fill 拉伸（P1B 合成器再做 contain 修正）"
            } else {
                ""
            }
        logForSession(
            session,
            "CARLIFE-VIDEO: fit stream=${sourceWidth}x$sourceHeight " +
                "encoder=${encoderWidth}x$encoderHeight scale=${scaleX}x$scaleY$hint",
        )
    }

    /** 可被 detach 唤醒的分段睡眠：不长时间阻塞管理线程退出。 */
    private fun sleepWhileActive(
        session: Session,
        durationMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        synchronized(session.lock) {
            while (session.active) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) return
                try {
                    session.lock.wait(remaining)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** 解码器回调：只在仍是当前会话时计数/记日志，避免旧会话污染新会话诊断。 */
    private inner class DecoderCallbacks(
        private val session: Session,
    ) : CarLifeVideoDecoder.Listener {
        override fun onOutputFormatChanged(
            width: Int,
            height: Int,
        ) {
            if (!isCurrent(session)) return
            logForSession(session, "CARLIFE-VIDEO: 解码输出格式 ${width}x$height（编码器 ${session.width}x${session.height}）")
            logFit(session, width, height)
        }

        override fun onFrameRendered(ptsUs: Long) {
            if (!isCurrent(session)) return
            deliveredFrames++
            val rendered = deliveredFrames
            if (rendered == 1L || rendered % FRAME_LOG_INTERVAL == 0L) {
                logForSession(
                    session,
                    "CARLIFE-VIDEO: decoder out #$rendered pts=${ptsUs / 1000}ms " +
                        "stream=${streamWidth}x$streamHeight encoder=${session.width}x${session.height}",
                )
            }
        }

        override fun onDecoderError(message: String) {
            if (!isCurrent(session)) return
            setLastError(session, message)
            logForSession(session, "CARLIFE-VIDEO: 解码器错误 - $message")
        }

        override fun onDecoderEvent(line: String) {
            if (!isCurrent(session)) return
            logForSession(session, "CARLIFE-VIDEO: $line")
        }
    }

    /** 建解码器期间暂存的关键帧：decoder 就绪后立即投喂，避免空等下一个 IDR。 */
    private class PendingKeyFrame(
        val data: ByteArray,
        val ptsMs: Long,
    )

    /** 一次 attach 的完整上下文；detach 后旧引用凭 [isCurrent] 判断自动失效。 */
    private class Session(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val fps: Int,
    ) {
        // 此监视器需要 wait/notify，保留 Java Object 的显式接口（见 BtSessionGate 同样写法）
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val lock = Object()

        @Volatile
        var active = true

        @Volatile
        var client: CarLifeHuClient? = null

        @Volatile
        var manager: Thread? = null

        @Volatile
        var pipeline: CarLifeVideoPipeline? = null

        /** 建解码器期间暂存的关键帧（见 [PendingKeyFrame]）。 */
        @Volatile
        var pendingKeyFrame: PendingKeyFrame? = null

        /** 最近一次收到视频帧的时刻（uptimeMillis；握手成功时也会重置）。无视频帧看门狗用。 */
        @Volatile
        var lastFrameAt = 0L

        /** 解码器生命周期单线程：MediaCodec 创建/配置/stop 都不在视频读线程上做。 */
        val executor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "carlife-codec").apply { isDaemon = true }
            }

        val decoder = AtomicReference<CarLifeVideoDecoder?>(null)
        val decoderScheduled = AtomicBoolean(false)
        val decoderEpoch = AtomicInteger(0)

        @Volatile
        var appliedWidth = 0

        @Volatile
        var appliedHeight = 0

        /** 解码器创建失败后的最早重试时刻（uptimeMillis）。 */
        @Volatile
        var nextCreateAt = 0L

        @Volatile
        var surfaceInvalidLogged = false
    }

    private companion object {
        private const val HOST = "127.0.0.1"
        private const val MANAGER_THREAD_NAME = "carlife-source"
        private const val RETRY_DELAY_MS = 2_000L

        /** 连接失败重试日志限流窗口：首次立即打，之后最多每 10s 一行。 */
        private const val RETRY_LOG_INTERVAL_MS = 10_000L

        private const val STATUS_POLL_MS = 500L

        /** 已握手但连续多久没有视频帧就开始告警（毫秒）。 */
        private const val NO_VIDEO_WARN_MS = 5_000L

        /** 同一会话里无视频帧告警的最小重复间隔（毫秒）。 */
        private const val NO_VIDEO_WARN_INTERVAL_MS = 5_000L

        private const val DECODER_STOP_TIMEOUT_MS = 800L
        private const val CREATE_RETRY_DELAY_MS = 2_000L
        private const val MANAGER_JOIN_TIMEOUT_MS = 1_500L
        private const val FRAME_LOG_INTERVAL = 150L
    }
}
