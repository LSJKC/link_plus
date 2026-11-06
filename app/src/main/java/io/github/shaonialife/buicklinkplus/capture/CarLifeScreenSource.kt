/* P1A CarLife 内容源：CarLife H.264 解码后直出到 VideoSender 的编码器输入 Surface。
 * CarLife 客户端协议/解码逻辑参考自 CarPToCarL（MIT, Copyright (c) 2026 lizhihua）与历史参考实现
 * 的 CarLifeBridge/解码逻辑；本文件只做 link_plus 的 ScreenSource 生命周期与线程编排。 */
package io.github.shaonialife.buicklinkplus.capture

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import io.github.shaonialife.buicklinkplus.carlife.CarGpsFix
import io.github.shaonialife.buicklinkplus.carlife.CarLifeAudioSink
import io.github.shaonialife.buicklinkplus.carlife.CarLifeHuClient
import io.github.shaonialife.buicklinkplus.carlife.CarLifeNav
import io.github.shaonialife.buicklinkplus.carlife.CarLifeNowPlaying
import io.github.shaonialife.buicklinkplus.carlife.CarLifeProtocol
import io.github.shaonialife.buicklinkplus.carlife.CarLifeVideoDecoder
import io.github.shaonialife.buicklinkplus.carlife.CarLifeVideoPipeline
import io.github.shaonialife.buicklinkplus.input.CarLifeTouchSink
import io.github.shaonialife.buicklinkplus.input.CarLifeVoiceKeyRouter
import io.github.shaonialife.buicklinkplus.launcher.settings.CarLifePrefs
import io.github.shaonialife.buicklinkplus.render.CarLifeComposer
import io.github.shaonialife.buicklinkplus.render.CarLifeWallpaperSource
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
 * 连不上就每 2s 重试，连上后持续转发视频帧、断线后自动收尾重连。[detach] 同步停解码器
 * （≤800ms，保证 release 返回后解码线程不再渲染编码器 Surface），把管理线程 join/音频桥
 * 停止等慢操作交给 daemon 清理线程；幂等，之后再次 [attach] 可重新工作。
 *
 * P2 音频直通：可选的 [audioSink]（[CarLifeAudioSink]，由 SessionController 创建注入）接住
 * [CarLifeHuClient] 的媒体 9240 / TTS 9241 回调，统一成 48kHz 立体声 1408B 实时包后喂给
 * [io.github.shaonialife.buicklinkplus.audio.AudioSender] 的外部 PCM 模式；sink 为空时音频
 * 回调静默丢弃，不影响视频主链路。
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
 * 同一编码器输入 Surface 上**绝不并发两个解码器**：[CarLifeVideoDecoder.stopAndWait] 超时
 * （旧线程仍在渲染）时由 [RetiringDecoderGate] 挂门，线程真正退出前拒绝新建。
 *
 * 诊断：对外暴露 [isEngineConnected]/[isWaitingEngine]/[retryCount]/[handshakeTimeoutCount]/
 * [deliveredFrames]/[streamWidth]/[streamHeight]/[lastErrorText]，供会话日志/首页/设置页采样；
 * 关键日志含 `carlife connected` / `decoder out` / `fit`。等待连接的重试日志限流（首次即时、
 * 之后最多每 10s 一行）。**握手看门狗**：`client.start()` 成功只保证 65538 版本协商，若 4s 内
 * 没有 65570 特征配置确认、也没有 CSD/首帧进度，判定引擎端口开着但未进入无线连接，主动断开
 * 重建（计入 [retryCount]/[handshakeTimeoutCount]）。已握手但 ≥5s 无视频帧、或持续收帧但
 * ≥8s 无解码输出（CSD 缺失/解码器静默不出帧）时各输出诊断行，后者会重发 98311 重建引擎编码器，
 * 尝试把永久黑屏救回来。[retryNow] 供「刷新/重连」入口立即打断等待重建一轮。
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

    /** 诊断：本会话累计「等待 CarLife+ 无线连接/握手」的重试次数（成功握手后不再增长）。 */
    @Volatile
    var retryCount: Int = 0
        private set

    /** 诊断：会话已挂载但引擎未连通/未完成握手（首页/设置页展示「等待 CarLife+ 无线连接」用）。 */
    @Volatile
    var isWaitingEngine: Boolean = false
        private set

    /** 诊断：已连上端口但 4s 内未完成握手（65570/首帧未到）而被主动重建的次数。 */
    @Volatile
    var handshakeTimeoutCount: Int = 0
        private set

    /**
     * 可选音频桥（P2 音频直通）：由 SessionController 创建并注入；null = 不处理 CarLife 音频。
     *
     * 生命周期由内容源管理线程驱动：CarLife 握手成功后 [CarLifeAudioSink.start]，
     * 断线时 [CarLifeAudioSink.reset]，[detach] 时 [CarLifeAudioSink.stop]。音频 SETUP 由
     * SessionController 独立协商，AudioSender 未起时音频桥只丢包、不影响视频。
     */
    @Volatile
    var audioSink: CarLifeAudioSink? = null

    /**
     * 可选触摸下沉（P3）：由 SessionController 创建并注入，并同步注册到
     * [io.github.shaonialife.buicklinkplus.input.TouchRouter]；CarLife 客户端
     * 握手成功后把自身交给 sink（9340 触摸通道），断线/退役时解绑。
     */
    @Volatile
    var touchSink: CarLifeTouchSink? = null

    /**
     * 可选语音键路由（P4 辅助）：车机 requestSiri / HID Consumer 0xCF → CarLife 硬键
     * 0x68008（VR_START/VR_STOP）。握手成功后绑定客户端，断线/退役解绑。
     */
    @Volatile
    var voiceKeySink: CarLifeVoiceKeyRouter? = null

    /**
     * 车机 GPS（可选）：把 Android Location 转出的 CarLife GpsFix 下发引擎 0x18010。
     * 无会话/未连接时返回 false（调用方节流即可）。
     */
    fun sendCarGps(fix: CarGpsFix): Boolean = session?.client?.sendCarGps(fix) ?: false

    /**
     * 立即打断当前等待并重建一轮 CarLife 连接（设置页「刷新/重连」等入口调用）。
     *
     * 会唤醒管理线程的等待睡眠；若客户端正卡在 15s 版本协商里（[CarLifeHuClient.start] 阻塞），
     * 同时 `stop()` 让它尽快返回，避免用户点刷新后还要干等十几秒。返回 false = 当前没有已挂载
     * 的内容源会话（未连接/会话已结束，需要先点「启动连接」）。
     */
    fun retryNow(): Boolean {
        val current =
            synchronized(lock) { session } ?: run {
                log("CARLIFE: 手动重试未执行——当前没有 CarLife 内容源会话（未连接或已结束）")
                return false
            }
        current.retryRequested = true
        synchronized(current.lock) { current.lock.notifyAll() }
        // 若管理线程正卡在 client.start() 的 15s 版本协商里，停掉客户端让这一轮立即返回。
        current.client?.stop()
        log("CARLIFE: 手动重试——立即打断等待并重建连接（已重试 $retryCount 次）")
        return true
    }

    /**
     * 可选导航出口（P4）：CarLife 0x10030 解析后的转向/距离快照。
     * 由 SessionController 接线到 [CarLifeNav.submitNextTurn]；null = 不处理导航。
     */
    @Volatile
    var navSink: ((CarLifeNav.NextTurn) -> Unit)? = null

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
        isWaitingEngine = true
        retryCount = 0
        handshakeTimeoutCount = 0
        deliveredFrames = 0L
        streamWidth = 0
        streamHeight = 0

        val newSession =
            Session(
                surface,
                width,
                height,
                targetFps,
                geometry,
                wallpaperEnabled = CarLifePrefs.wallpaperEnabled(context),
                cornerRadiusPx = CarLifePrefs.cornerRadius(context) * context.resources.displayMetrics.density,
                noDecodeWatchdog = NoDecodeWatchdog(NO_DECODE_WARN_MS, NO_DECODE_WARN_INTERVAL_MS),
                retiringLog = LogThrottle(RETIRING_LOG_INTERVAL_MS),
            )
        // P1B 合成器：壁纸 + 安全区 contain + 圆角；GL 启动放在管理线程，避免占住
        // SessionController 的会话锁。启动失败时回退 P1A 直出（decoder 写编码器 Surface）。
        newSession.composer =
            CarLifeComposer(surface, log = { line -> log(line) }).apply {
                this.targetFps = targetFps
                this.cornerRadiusPx = newSession.cornerRadiusPx
                // 这里只有编码器 profile 尺寸（= 98311 申请尺寸），不是码流真实尺寸；
                // 先传 0 让 pane 不成立（此时也还没有解码画面），等 SPS/解码输出格式回调再按真实宽高重算，
                // 避免用申请尺寸算 pane 导致画面按错误比例拉伸。
                updateLayout(geometry, 0, 0)
            }
        log(
            "CARLIFE-COMPOSER: 待启动（壁纸=" +
                if (newSession.wallpaperEnabled) {
                    "开"
                } else {
                    "关" +
                        " 圆角=${newSession.cornerRadiusPx}px；失败回退直出）"
                },
        )
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
        CarLifeNowPlaying.onCarLifeDisconnected()
        try {
            client?.stop()
        } catch (error: Exception) {
            lastErrorText = "停止 CarLife 客户端失败：${error.message}"
            log("CARLIFE: 停止客户端异常 - ${error.message}")
        }
        // 关 codec 生命周期线程（shutdownNow 立即返回；epoch 自增让在途创建任务作废）。
        doomed.executor.shutdownNow()
        doomed.decoderEpoch.incrementAndGet()
        doomed.pendingKeyFrame = null
        val decoder = doomed.decoder.getAndSet(null)
        val audio = audioSink
        audioSink = null
        // P1B：先停解码器（下面同步等），再停合成器；两者都必须在 SessionController 释放
        // 编码器输入 Surface 之前完成，否则 GL/解码线程会操作已释放的 Surface。
        val composer = doomed.composer
        doomed.composer = null
        val wallpaper = doomed.wallpaperSource
        doomed.wallpaperSource = null
        // 诊断字段立即复位：旧会话回调都带 isCurrent(session) 闸门，不会再把它们改回去。
        isEngineConnected = false
        isWaitingEngine = false
        streamWidth = 0
        streamHeight = 0
        // C0（审计修复）：release() 返回前**同步**等解码器停下（≤800ms），保证 SessionController
        // 随后释放编码器输入 Surface 时解码线程已不在渲染——异步停会和 Surface 释放竞态。
        // 管理线程 join / 音频桥停止放到 daemon 清理线程，避免在 SessionController 锁内等 1.5s。
        if (decoder != null) {
            val exited = decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
            if (!exited || decoder.isThreadAlive()) {
                doomed.retiringGate.retire(decoder)
                log(
                    "CARLIFE-VIDEO: 解码线程未在 ${DECODER_STOP_TIMEOUT_MS}ms 内退出" +
                        "（release 已同步尽力等待，余下由解码线程自然收尾）",
                )
            }
        }
        // 解码已停 → 合成器不再有新输入；停掉 GL 线程后才允许 SessionController 释放编码器 Surface。
        if (composer != null) {
            runCatching { composer.stop() }
            log("CARLIFE-COMPOSER: 合成器已停止")
        }
        wallpaper?.stop()
        // 慢路径（管理线程 join / 音频桥停止 / 收尾日志）交给 daemon 线程：只捕获 doomed
        // 这一代会话的引用，因此绝不会碰到随后 attach 的新会话。
        Thread({ cleanupDetached(doomed, audio) }, DETACH_THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * [detach] 的慢路径（daemon 线程）：停音频桥、等管理线程退出，然后打收尾日志。
     *
     * 只使用捕获的 [session]/[audioSink] 引用，与随后 [attach] 的新会话完全隔离。
     */
    private fun cleanupDetached(
        session: Session,
        audioSink: CarLifeAudioSink?,
    ) {
        audioSink?.stop()
        // 之前重建超时挂门的旧解码器：这里再给一次异步停止机会（不阻塞 release 调用方）。
        session.retiringGate.retiring?.let { old ->
            if (!old.stopAndWait(DECODER_STOP_TIMEOUT_MS)) {
                log("CARLIFE-VIDEO: 退休解码线程仍未退出（daemon 清理线程放弃等待）")
            }
        }
        val manager = session.manager
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
        log("CARLIFE: 内容源已停止")
    }

    override fun release() = detach()

    // ---- 管理线程：CarLife 客户端连接/重试/收尾 ----

    private fun manage(session: Session) {
        val pipeline = session.pipeline ?: return
        logForSession(session, "CARLIFE: 管理线程启动（等待 CarLife+ 打开无线连接）")
        ensureComposer(session)
        // 连接失败重试的原样日志会被「7 端口 × 每 2s」刷屏，这里限流：首次立即一行，之后最多每 10s 一行。
        val waitLog = LogThrottle(RETRY_LOG_INTERVAL_MS)
        while (session.active && isCurrent(session)) {
            if (session.retryRequested) {
                session.retryRequested = false
                logForSession(session, "CARLIFE: 手动重试——立即重建连接")
            }
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
                    onNaviTurn = { nextTurn -> navSink?.invoke(nextTurn) },
                    onStreamSize = { width, height ->
                        logForSession(session, "CARLIFE: 客户端 SPS 实测 ${width}x$height")
                    },
                    // ---- P2 音频直通：媒体 9240 / TTS 9241 → 可选 CarLifeAudioSink →
                    // AudioSender 外部 PCM 模式。sink 为空（未开启声音/未注入）时全部丢弃。 ----
                    onAudioFormat = { sampleRate, channels ->
                        if (isCurrent(session)) audioSink?.configureMedia(sampleRate, channels)
                    },
                    onAudioPcm = { pcm, size ->
                        if (isCurrent(session)) audioSink?.writeMedia(pcm, size)
                    },
                    onTtsFormat = { sampleRate, channels ->
                        if (isCurrent(session)) audioSink?.configureTts(sampleRate, channels)
                    },
                    onTtsPcm = { pcm, size ->
                        if (isCurrent(session)) audioSink?.writeTts(pcm, size)
                    },
                    onAudioGate = { active ->
                        if (isCurrent(session)) {
                            audioSink?.setAudioGate(active)
                            CarLifeNowPlaying.onAudioGate(active)
                        }
                    },
                    // 音频沉默看门狗（9240 长时间零 PCM 才重连）的「应该有声音」判据。
                    audioExpected = { isCurrent(session) && audioSink?.isAudioExpected() == true },
                    // P5 媒体 NowPlaying：0x10035 媒体信息 / MD_MEDIA_PROGRESS → iAP2 0x5001。
                    // 发送链路为 AirPlay 虚拟 iAP2 链路（车机 0x5000 订阅后按属性表发送）。
                    onMediaInfo = { info -> if (isCurrent(session)) CarLifeNowPlaying.onMediaInfo(info) },
                    onMediaProgress = { progress -> if (isCurrent(session)) CarLifeNowPlaying.onMediaProgress(progress) },
                    // 本车 98311 安全区字段 [宽,高,x,y]：用当前画布几何 content（申请画布坐标系）。
                    saveArea = saveAreaOf(session.geometry),
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
                bumpRetry(session)
                setWaitingEngine(session, true)
                setLastError(session, "CarLife 引擎未就绪（127.0.0.1 控制端口未连通或未完成 98305 版本协商）")
                // 首次立即打一行，之后最多每 10s 一行：现场能看出「还在等」但不会被每 2s 的
                // 7 端口失败日志刷屏（逐端口日志已由 quietPortFailures 关掉）。
                if (waitLog.allow("wait")) {
                    logForSession(
                        session,
                        "CARLIFE: 等待 CarLife+ 无线连接（第 $retryCount 次重试，127.0.0.1:${CarLifeProtocol.PORT_CMD}；" +
                            "打开 CarLife+ 并点「无线连接」）",
                    )
                }
                if (session.retryRequested) {
                    session.retryRequested = false
                    logForSession(session, "CARLIFE: 手动重试——跳过 2s 退避，立即重建连接")
                } else {
                    sleepWhileActive(session, RETRY_DELAY_MS)
                }
                continue
            }
            if (session.retryRequested) {
                session.retryRequested = false
                client.stop()
                session.client = null
                logForSession(session, "CARLIFE: 手动重试——本轮握手让位，立即重建连接")
                continue
            }
            setLastError(session, "")
            setEngineConnected(session, true)
            setWaitingEngine(session, true)
            session.lastFrameAt = SystemClock.uptimeMillis()
            // P3：客户端已握手 → 触摸 sink 可下发 9340（未连接时保持解绑）。
            touchSink?.setSender(client)
            voiceKeySink?.setSender(client)
            // 解码进度看门狗基线：以本轮连接时的累计解码帧为起点，避免把上一轮进度算进来。
            val framesAtConnect = session.framesReceived
            session.noDecodeWatchdog.reset(deliveredFrames)
            logForSession(session, "CARLIFE: carlife connected 127.0.0.1（65538 版本协商完成，等待特征配置/视频帧）")
            // P2：引擎连接建立后启动音频水泵（无声时按节拍发静音包保活接收端音频管线）。
            audioSink?.start()
            // 握手看门狗：start() 成功只保证 65538 版本协商；若 4s 内既没有 65570 特征配置确认，
            // 也没有 CSD/首帧进度，说明 CarLife+ 端口开着但没真正进入无线连接等待态——继续等会
            // 永远停在「已连接但没画面」，这里主动断开重建（计入 retryCount/handshakeTimeoutCount）。
            val handshakeStartedAt = SystemClock.uptimeMillis()
            var handshakeSettled = false
            var lastNoVideoWarnAt = 0L
            while (session.active && isCurrent(session) && client.isRunning) {
                if (session.retryRequested) {
                    session.retryRequested = false
                    logForSession(session, "CARLIFE: 手动重试——结束本轮连接，立即重建")
                    break
                }
                sleepWhileActive(session, STATUS_POLL_MS)
                if (!session.active || !isCurrent(session) || !client.isRunning) break
                if (session.retryRequested) {
                    session.retryRequested = false
                    logForSession(session, "CARLIFE: 手动重试——结束本轮连接，立即重建")
                    break
                }
                val now = SystemClock.uptimeMillis()
                if (!handshakeSettled) {
                    val progressed =
                        client.isFeatureConfigAcked ||
                            session.pipeline?.hasCsd == true ||
                            session.framesReceived > framesAtConnect
                    if (progressed) {
                        handshakeSettled = true
                        setWaitingEngine(session, false)
                        logForSession(
                            session,
                            "CARLIFE: 握手完成（65570=${client.isFeatureConfigAcked}，" +
                                "CSD=${if (session.pipeline?.hasCsd == true) "已就绪" else "缺失"}，" +
                                "已收帧=${session.framesReceived - framesAtConnect}）",
                        )
                    } else if (now - handshakeStartedAt >= HANDSHAKE_TIMEOUT_MS) {
                        bumpRetry(session)
                        bumpHandshakeTimeout(session)
                        setLastError(session, "已连上 CarLife 端口但 ${HANDSHAKE_TIMEOUT_MS}ms 内未完成握手/无视频进度")
                        logForSession(
                            session,
                            "CARLIFE: 已连上端口但 ${HANDSHAKE_TIMEOUT_MS}ms 内未完成握手" +
                                "（65570 特征配置/首帧均未到）——判定 CarLife+ 未进入无线连接，" +
                                "主动断开重建（第 $retryCount 次重试）",
                        )
                        break
                    }
                }
                // 看门狗 1：已握手但长时间没有视频帧（诊断行带管线状态，区分没进无线连接 vs SPS 没解析到）。
                val idle = now - session.lastFrameAt
                if (handshakeSettled &&
                    idle >= NO_VIDEO_WARN_MS &&
                    (lastNoVideoWarnAt == 0L || now - lastNoVideoWarnAt >= NO_VIDEO_WARN_INTERVAL_MS)
                ) {
                    lastNoVideoWarnAt = now
                    val csdText = if (session.pipeline?.hasCsd == true) "已就绪" else "缺失"
                    val streamText = session.pipeline?.streamSize?.let { "${it.first}x${it.second}" } ?: "未知"
                    logForSession(
                        session,
                        "CARLIFE: 已握手但 ${idle / 1000}s 无视频帧" +
                            "（检查 CarLife+ 是否进入无线连接/是否有内容；CSD=$csdText，码流=$streamText）",
                    )
                }
                // 看门狗 2：持续收帧但解码输出不涨（无 CSD / 解码器 HAL 静默不出帧 → 永久黑屏）。
                val received = session.framesReceived - framesAtConnect
                if (session.noDecodeWatchdog.shouldWarn(received, deliveredFrames, now)) {
                    val csdText = if (session.pipeline?.hasCsd == true) "已就绪" else "缺失"
                    logForSession(
                        session,
                        "CARLIFE-VIDEO: 持续收帧但无解码输出（CSD=$csdText，已收帧=$received，" +
                            "已解码=$deliveredFrames，尝试重发 98311 重建引擎编码器）",
                    )
                    // 重发 98386+98311 让引擎重建编码器：无 CSD 时会补 SPS/PPS+IDR，解码器静默死锁时也换新流。
                    client.requestVideoReinit()
                    // 解码器还在但不出帧：顺便重建一次解码器（退休门保证不会出现双 producer）。
                    if (session.decoder.get() != null) {
                        scheduleDecoderRecreate(session, "持续收帧但无解码输出")
                    }
                }
            }
            setEngineConnected(session, false)
            if (session.active && isCurrent(session)) {
                setWaitingEngine(session, true)
                logForSession(session, "CARLIFE: 连接已断开，收尾解码器/管线后 2s 重连")
            }
            client.stop()
            session.client = null
            touchSink?.setSender(null)
            voiceKeySink?.setSender(null)
            CarLifeNowPlaying.onCarLifeDisconnected()
            teardownDecoder(session)
            pipeline.reset()
            // 音频：清掉上一轮排队的 PCM/TTS 残留，等待新连接重新宣告格式（水泵保持运行）。
            audioSink?.reset()
            if (!session.active || !isCurrent(session)) break
            sleepWhileActive(session, RETRY_DELAY_MS)
        }
        teardownDecoder(session)
        setEngineConnected(session, false)
        setWaitingEngine(session, false)
        logForSession(session, "CARLIFE: 管理线程退出")
    }

    /**
     * 启动 P1B 合成器（管理线程上执行，GL 启动最多等 4s，不占会话锁）。
     *
     * 成功：解码器输出改到 [CarLifeComposer.inputSurface]（SurfaceTexture）；
     * 失败：保持直出（解码器输出到 VideoSender 编码器 Surface），只少了壁纸/安全区/圆角。
     */
    private fun ensureComposer(session: Session) {
        if (!session.active || !isCurrent(session)) return
        val composer = session.composer ?: return
        if (composer.isRunning) return
        val started =
            try {
                composer.start()
            } catch (error: Throwable) {
                logForSession(session, "CARLIFE-COMPOSER: 启动异常 - ${error.message ?: error.javaClass.simpleName}")
                false
            }
        if (!started) {
            logForSession(session, "CARLIFE-COMPOSER: 启动失败，回退 P1A 直出（无壁纸/安全区/圆角）")
            return
        }
        session.decoderSurface = composer.inputSurface()
        logForSession(
            session,
            "CARLIFE-COMPOSER: 合成器已启动 canvas=${session.width}x${session.height} " +
                "content=${session.geometry.content} radius=${composer.cornerRadiusPx}",
        )
        // 壁纸：用户当前壁纸按画布 cover 预合成；加载失败由合成器回退纯色底。
        if (!session.wallpaperEnabled) {
            logForSession(session, "CARLIFE-COMPOSER: 壁纸背景已关闭，使用纯色底")
            return
        }
        val wallpaper = CarLifeWallpaperSource(context, log = { line -> logForSession(session, line) })
        session.wallpaperSource = wallpaper
        wallpaper.start(session.width, session.height) { bitmap -> composer.setWallpaper(bitmap) }
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
        session.framesReceived++
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
        // 合成器布局先跟随 SPS 实测尺寸（SurfaceTexture 缓冲尺寸也在此同步），再决定是否重建解码器。
        session.composer?.updateLayout(session.geometry, width, height)
        // 触摸映射与画面用同一个 pane：显式把 SPS 真实尺寸同步给触摸 sink，不依赖 client.videoSize 的时序。
        touchSink?.updateSourceSize(width, height)
        val decoder = session.decoder.get() ?: return
        if (width != session.appliedWidth || height != session.appliedHeight) {
            logFit(session, width, height)
            scheduleDecoderRecreate(session, "SPS 尺寸变化 -> ${width}x$height")
        }
    }

    /** 调度创建解码器（单线程执行器、去重、失败后退避 2s 再试）。 */
    private fun scheduleDecoderCreate(session: Session) {
        if (!session.active || !isCurrent(session)) return
        if (!session.decoderSurface.isValid) {
            noteSurfaceInvalid(session)
            return
        }
        if (SystemClock.uptimeMillis() < session.nextCreateAt) return
        // 退休门：旧解码线程还没退时绝不新建（否则同一 Surface 双 producer）。
        if (!canCreateDecoder(session)) return
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
                    // 旧解码器没退干净时挂门并放弃本次新建：等它真正退出后由 handleFrame 重新调度，
                    // 绝不在同一编码器 Surface 上并发出两个解码器。
                    if (!retireDecoder(session, old)) return@execute
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
        // 双重保险：调度后到真正执行之间可能又有解码器被停超时挂门（退休门是唯一放行依据）。
        if (!canCreateDecoder(session)) return
        if (!session.decoderSurface.isValid) {
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
                session.decoderSurface,
                DecoderCallbacks(session),
            )
        decoder.setCsd(sps, pps)
        if (!decoder.start()) {
            retireDecoder(session, decoder)
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
            if (session.decoder.compareAndSet(decoder, null)) retireDecoder(session, decoder)
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
            retireDecoder(session, decoder)
        }
    }

    /**
     * 停一个解码器，若 [CarLifeVideoDecoder.stopAndWait] 超时（线程仍可能渲染同一个编码器
     * 输入 Surface）就把实例挂到退休门，让之后的新建全部推迟到它真正退出。
     *
     * @return true = 线程已退出，可以继续新建；false = 线程仍存活，已挂门（调用方不得新建）。
     */
    private fun retireDecoder(
        session: Session,
        decoder: CarLifeVideoDecoder?,
    ): Boolean {
        if (decoder == null) return true
        decoder.stopAndWait(DECODER_STOP_TIMEOUT_MS)
        // 必须用 isThreadAlive 而不是 isAlive：stop 超时后 running 已置 false，isAlive 会误报 false，
        // 而线程可能还在 releaseOutputBuffer(render=true)。
        if (!decoder.isThreadAlive()) return true
        session.retiringGate.retire(decoder)
        logForSession(
            session,
            "CARLIFE-VIDEO: 旧解码线程未在 ${DECODER_STOP_TIMEOUT_MS}ms 内退出，推迟新建解码器" +
                "（同一编码器 Surface 禁止双生产者）",
        )
        return false
    }

    /**
     * 退休门检查：旧解码线程还活着就推迟新建（[RETIRING_RETRY_DELAY_MS] 后重试，日志限流）。
     *
     * @return true = 可以新建；false = 旧线程仍存活，本次必须放弃。
     */
    private fun canCreateDecoder(session: Session): Boolean {
        if (session.retiringGate.canCreate()) return true
        session.nextCreateAt = SystemClock.uptimeMillis() + RETIRING_RETRY_DELAY_MS
        if (session.retiringLog.allow("retiring")) {
            logForSession(
                session,
                "CARLIFE-VIDEO: 旧解码线程未退出，推迟新建解码器" +
                    "（同一编码器 Surface 禁止双生产者，${RETIRING_RETRY_DELAY_MS}ms 后重试）",
            )
        }
        return false
    }

    // ---- 日志/诊断/睡眠工具 ----

    private fun isCurrent(session: Session): Boolean = this.session === session

    /**
     * 98311 安全区字段 [宽, 高, x, y]：几何 content 已是「申请画布」坐标系，
     * 与 initialWidth/Height（申请画布尺寸）同一空间，引擎按它做厂商适配。
     */
    private fun saveAreaOf(geometry: DisplayGeometry): IntArray? {
        val content = geometry.content
        if (content.width <= 0 || content.height <= 0) return null
        return intArrayOf(content.width, content.height, content.left, content.top)
    }

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

    /** 累计一次等待引擎/握手的重试（成功握手后不再清空，供现场看总次数）。 */
    private fun bumpRetry(session: Session) {
        if (isCurrent(session)) retryCount += 1
    }

    /** 更新「等待 CarLife+ 无线连接」展示状态（首页/设置页通过 SessionController 读取）。 */
    private fun setWaitingEngine(
        session: Session,
        waiting: Boolean,
    ) {
        if (isCurrent(session)) isWaitingEngine = waiting
    }

    /** 累计一次「端口已连但握手超时」的重建。 */
    private fun bumpHandshakeTimeout(session: Session) {
        if (isCurrent(session)) handshakeTimeoutCount += 1
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
            while (session.active && !session.retryRequested) {
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
            // pane 以 SPS 码流真实尺寸为准（与触摸映射同一基准）；解码输出格式只在 SPS 未知时兜底。
            // 二者通常一致；若不一致（例如解码器输出对齐到宏块尺寸），优先信 SPS，避免把对齐尺寸当画面比例。
            val stream = session.pipeline?.streamSize
            val paneWidth = stream?.first?.takeIf { it > 0 } ?: width
            val paneHeight = stream?.second?.takeIf { it > 0 } ?: height
            session.composer?.updateLayout(session.geometry, paneWidth, paneHeight)
            touchSink?.updateSourceSize(paneWidth, paneHeight)
            logForSession(
                session,
                "CARLIFE-VIDEO: 解码输出格式 ${width}x$height" +
                    "（pane 按 ${paneWidth}x$paneHeight，编码器 ${session.width}x${session.height}）",
            )
            logFit(session, paneWidth, paneHeight)
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
        /** 会话显示几何（画布/安全区 content rect），合成器布局与触摸映射共用。 */
        val geometry: DisplayGeometry,
        /** 设置「壁纸背景」开关（false = 合成器纯色底）。 */
        val wallpaperEnabled: Boolean,
        /** 设置「画面圆角」半径（画布 px，0 = 直角）。 */
        val cornerRadiusPx: Float,
        /** 持续收帧但无解码输出的看门狗状态机（纯逻辑，可 JVM 单测）。 */
        val noDecodeWatchdog: NoDecodeWatchdog,
        /** 退休门推迟日志限流（旧解码线程长时间不退时避免每 1s 刷一行）。 */
        val retiringLog: LogThrottle,
    ) {
        // 此监视器需要 wait/notify，保留 Java Object 的显式接口（见 BtSessionGate 同样写法）
        @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
        val lock = Object()

        @Volatile
        var active = true

        /** 手动重试请求：[retryNow] 置位，管理线程看到后立即打断等待/握手并重建一轮。 */
        @Volatile
        var retryRequested = false

        /** P1B 合成器（壁纸+安全区 contain+圆角）；null 或启动失败时直出。 */
        @Volatile
        var composer: CarLifeComposer? = null

        /** 壁纸来源（随 WallpaperStore 变化自动重载）。 */
        @Volatile
        var wallpaperSource: CarLifeWallpaperSource? = null

        /** 解码器输出目标：合成器 inputSurface，或启动失败时回退为编码器 Surface。 */
        @Volatile
        var decoderSurface: Surface = surface

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

        /** 本次会话累计收到的视频帧数（无解码输出看门狗用）。 */
        @Volatile
        var framesReceived = 0L

        /** 停超时解码器的退休门：旧解码线程真正退出前，禁止在同一编码器输入 Surface 上新建。 */
        val retiringGate = RetiringDecoderGate<CarLifeVideoDecoder> { decoder -> decoder.isThreadAlive() }

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

        /**
         * 握手看门狗窗口（毫秒）：client.start() 成功（65538 版本协商完成）后，若这么久内
         * 既没有 65570 特征配置确认、也没有 CSD/首帧进度，判定引擎端口开着但未进入无线连接，
         * 主动断开重建（避免永远停在「已连接但没画面」）。
         */
        private const val HANDSHAKE_TIMEOUT_MS = 4_000L

        /** 连接失败重试日志限流窗口：首次立即打，之后最多每 10s 一行。 */
        private const val RETRY_LOG_INTERVAL_MS = 10_000L

        private const val STATUS_POLL_MS = 500L

        /** 已握手但连续多久没有视频帧就开始告警（毫秒）。 */
        private const val NO_VIDEO_WARN_MS = 5_000L

        /** 同一会话里无视频帧告警的最小重复间隔（毫秒）。 */
        private const val NO_VIDEO_WARN_INTERVAL_MS = 5_000L

        /** 持续收帧但连续多久没有解码输出就告警/恢复（毫秒）。 */
        private const val NO_DECODE_WARN_MS = 8_000L

        /** 无解码输出告警与恢复动作（重发 98311）的最小重复间隔（毫秒）。 */
        private const val NO_DECODE_WARN_INTERVAL_MS = 15_000L

        /** 退休门推迟新建后的复查间隔（毫秒）：旧解码线程退出前每 1s 重试一次。 */
        private const val RETIRING_RETRY_DELAY_MS = 1_000L

        /** 退休门推迟日志限流窗口（毫秒）。 */
        private const val RETIRING_LOG_INTERVAL_MS = 5_000L

        private const val DECODER_STOP_TIMEOUT_MS = 800L
        private const val DETACH_THREAD_NAME = "carlife-detach"
        private const val CREATE_RETRY_DELAY_MS = 2_000L
        private const val MANAGER_JOIN_TIMEOUT_MS = 1_500L
        private const val FRAME_LOG_INTERVAL = 150L
    }
}

/**
 * 「退休解码器」门（纯 Kotlin、无 Android 依赖，JVM 可单测）。
 *
 * 背景：[CarLifeVideoDecoder.stopAndWait] 超时时解码线程可能仍在把帧渲染到**同一个**编码器
 * 输入 Surface；此时若新建解码器，同一个 BufferQueue 会出现两个 producer（画面错乱/花屏/崩溃）。
 * 所以旧线程真正退出前必须禁止新建——本门只记录「停超时的解码器」，由调用方注入线程存活判断。
 *
 * @param isThreadAlive 判断「该解码器的解码线程是否仍存活」（调用方注入，便于脱离 Android 单测）。
 */
internal class RetiringDecoderGate<T : Any>(
    private val isThreadAlive: (T) -> Boolean,
) {
    /** 停超时、可能仍在渲染的解码器；null = 无退休解码器。 */
    @Volatile
    var retiring: T? = null
        private set

    /** @return true = 没有退休解码器/其线程已退出（可以新建）；false = 旧线程仍活着，必须推迟。 */
    fun canCreate(): Boolean {
        val current = retiring ?: return true
        if (isThreadAlive(current)) return false
        // 线程已真正退出：清门放行，之后再建解码器不会再有双 producer。
        retiring = null
        return true
    }

    /** 记录一个 stop 超时、线程可能仍在渲染的解码器。 */
    fun retire(decoder: T) {
        retiring = decoder
    }
}

/**
 * 「持续收帧但无解码输出」看门狗状态机（纯 Kotlin、无 Android 依赖，JVM 可单测）。
 *
 * 只在「确实收到帧、但解码输出计数持续不涨」超过 [stallMs] 时放行一次告警（之后至少隔
 * [repeatMs] 再放行），用来发现两种永久黑屏：码流有但 CSD/SPS 始终解析不到；解码器 HAL
 * 静默不出帧。调用方拿到 [shouldWarn] == true 后负责重发 98311 重建引擎编码器。
 *
 * 线程模型：只在管理线程（连接等待循环）里调用，内部不加锁。
 */
internal class NoDecodeWatchdog(
    private val stallMs: Long,
    private val repeatMs: Long,
) {
    /** 上次观测到的累计解码帧数基线。 */
    private var lastDecoded = 0L

    /** 本轮「收帧但无解码进度」的起始时刻（<0 = 尚未建立基线）。 */
    private var stallSince = -1L

    /** 上次放行告警的时刻（<0 = 还没告警过）。 */
    private var lastWarnAt = -1L

    /** 连接（重新）建立时调用：以当前累计解码帧为基线，避免把上一轮的进度算进本轮。 */
    fun reset(decoded: Long) {
        lastDecoded = decoded
        stallSince = -1L
        lastWarnAt = -1L
    }

    /**
     * @param framesReceived 本轮连接已收到的视频帧数（相对计数；0 = 还没收到帧，不判故障）
     * @param decoded 累计已渲染输出帧数（绝对计数）
     * @param nowMs 当前时刻（调用方时钟，毫秒）
     * @return true = 现在应输出告警并触发恢复动作
     */
    fun shouldWarn(
        framesReceived: Long,
        decoded: Long,
        nowMs: Long,
    ): Boolean {
        if (framesReceived <= 0L) {
            stallSince = -1L
            return false
        }
        if (decoded > lastDecoded) {
            // 解码进度在涨：健康，重新开始计时。
            lastDecoded = decoded
            stallSince = -1L
            return false
        }
        if (stallSince < 0L) {
            stallSince = nowMs
            return false
        }
        if (nowMs - stallSince < stallMs) return false
        if (lastWarnAt >= 0L && nowMs - lastWarnAt < repeatMs) return false
        lastWarnAt = nowMs
        return true
    }
}
