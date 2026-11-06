/* Adapted from the prior hybrid63 decode port com/turtlelink/carlife/CarLifeVideoDecoder.java
 * (itself based on CarPToCarL/H264 decode behaviour, MIT, Copyright (c) 2026 lizhihua):
 * kept the proven decode path (bounded non-blocking queue, keyframe gating, CSD version
 * recreate, INFO_OUTPUT_FORMAT_CHANGED, surface-output rendering) and dropped the
 * snapshot/bitmap diagnostic feature. */
package io.github.shaonialife.buicklinkplus.carlife

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * CarLife H.264 视频解码器（P1A 直出用）：MediaCodec Surface 输出模式。
 *
 * 解码输出直接渲染到调用方传入的 [outputSurface]（P1A = VideoSender 的编码器输入 Surface），
 * 不做 EGL/GL 合成。**Surface 归调用方所有，本类只使用、绝不释放**；Surface 失效视为会话
 * 收尾，解码线程干净退出而不是报错重试。
 *
 * 线程模型：
 * - [queueFrame] 由 CarLife 视频读线程调用，**绝不阻塞**：有界队列（[FRAME_QUEUE_CAPACITY] 帧），
 *   满时优先丢最旧的**非关键帧**，若全是关键帧则丢最旧的关键帧（只保留最新数据）；
 * - 内部单解码线程 [THREAD_NAME] 负责 codec 创建/配置/start、输入投喂、输出渲染与错误重建；
 * - [setCsd] 任意线程可调用：运行中 CSD 变化由解码线程比对版本号后释放旧 codec、以新 CSD 重建，
 *   并要求从下一个关键帧重新起解；
 * - [start]/[stop]/[stopAndWait] 幂等；[stopAndWait] 会 join 解码线程（有界超时）。
 *
 * 错误恢复：codec 创建/解码失败时上报 [Listener.onDecoderError]，按 250ms×次数线性退避
 * （上限 2s）重建 codec，最多 [MAX_ERROR_ATTEMPTS] 次（有帧成功渲染后计数清零）；超过上限
 * 上报致命错误并退出线程。关键帧闸门在 (重)建 codec 后最多等 [KEY_FRAME_WAIT_TIMEOUT_MS]
 * 毫秒，超时兜底投喂非关键帧，避免异常流永久停摆。
 */
class CarLifeVideoDecoder(
    width: Int,
    height: Int,
    private val fps: Int,
    private val outputSurface: Surface,
    private val listener: Listener,
) {
    /** 解码器回调，全部在解码线程触发；实现方不得阻塞（日志/计数即可）。 */
    interface Listener {
        /** 输出格式确定/变化（宽高以 MediaCodec 输出格式为准，通常等于 SPS 解析值）。 */
        fun onOutputFormatChanged(
            width: Int,
            height: Int,
        )

        /** 每帧渲染到输出 Surface 后回调；ptsUs 来自 CarLife 帧头 ms*1000。 */
        fun onFrameRendered(ptsUs: Long)

        /** 解码器/配置/线程错误（可重试错误每轮一次；最后一轮消息含「放弃」）。 */
        fun onDecoderError(message: String)

        /** 诊断事件（codec 启动/释放、CSD 更新、线程退出等），供上层写会话日志。 */
        fun onDecoderEvent(line: String)
    }

    /** 一帧待解码数据（已拷贝，调用方可复用原 buffer）。 */
    private class Frame(
        val data: ByteArray,
        val ptsUs: Long,
        val keyFrame: Boolean,
    )

    // 此监视器需要 wait/notify，保留 Java Object 的显式接口（见 BtSessionGate 同样写法）
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()

    private val queue = ArrayDeque<Frame>()

    private var codecWidth: Int = if (width > 0) width else FALLBACK_WIDTH
    private var codecHeight: Int = if (height > 0) height else FALLBACK_HEIGHT
    private var spsNal: ByteArray? = null
    private var ppsNal: ByteArray? = null

    private var decodeThread: Thread? = null
    private var started = false

    @Volatile
    private var running = false

    /** CSD 版本号：setCsd 每次 +1；解码线程发现 appliedCsdVersion != csdVersion 时重建 codec。 */
    private var csdVersion = 0
    private var appliedCsdVersion = -1

    private var needKeyFrame = true
    private var keyFrameWaitStartMs = -1L
    private var errorAttempts = 0
    private var droppedFrames = 0L
    private var submittedFrames = 0L
    private var lastDropLogMs = 0L

    /**
     * 设置/更新 CSD（SPS/PPS 的 Annex-B NAL，可空，含起始码）。
     * 未运行时在下一次 [start] 生效；运行中更新会触发解码线程以新 CSD 重建 codec。
     */
    fun setCsd(
        sps: ByteArray?,
        pps: ByteArray?,
    ) {
        var changed = false
        var version = 0
        synchronized(lock) {
            if (!contentEquals(spsNal, sps) || !contentEquals(ppsNal, pps)) {
                spsNal = sps?.copyOf()
                ppsNal = pps?.copyOf()
                csdVersion++
                version = csdVersion
                changed = true
                lock.notifyAll()
            }
        }
        if (changed) {
            emitEvent("CSD 已更新（sps=${sps?.size ?: 0}B pps=${pps?.size ?: 0}B v$version）")
        }
    }

    /**
     * 投喂一帧 Annex-B H.264（含起始码，可带 SPS/PPS）。线程安全、不阻塞：
     * 队列满时优先丢最旧非关键帧，其次丢最旧关键帧。
     *
     * @param ptsMs 帧头的毫秒时间戳（原样使用，不做基准换算）
     */
    fun queueFrame(
        data: ByteArray,
        offset: Int,
        length: Int,
        ptsMs: Long,
        keyFrame: Boolean,
    ) {
        if (length <= 0 || offset < 0 || offset + length > data.size) {
            emitEvent("queueFrame 参数非法 offset=$offset length=$length size=${data.size}")
            return
        }
        val frame = Frame(data.copyOfRange(offset, offset + length), ptsMs * 1000L, keyFrame)
        var dropLogLine: String? = null
        synchronized(lock) {
            while (queue.size >= FRAME_QUEUE_CAPACITY) {
                var removedNonKey = false
                val iterator = queue.iterator()
                while (iterator.hasNext()) {
                    if (!iterator.next().keyFrame) {
                        iterator.remove()
                        removedNonKey = true
                        break
                    }
                }
                if (!removedNonKey) {
                    queue.pollFirst()
                }
                droppedFrames++
            }
            queue.addLast(frame)
            val now = SystemClock.uptimeMillis()
            if (droppedFrames > 0 && now - lastDropLogMs >= DROP_LOG_INTERVAL_MS) {
                lastDropLogMs = now
                dropLogLine = "帧队列丢帧累计 $droppedFrames（当前排队 ${queue.size}）"
            }
            lock.notifyAll()
        }
        dropLogLine?.let { emitEvent(it) }
    }

    /**
     * 启动内部解码线程。
     *
     * @return true = 新线程已启动；false = 被拒绝（旧线程仍存活 / Surface 无效）
     */
    fun start(): Boolean {
        var failure: String? = null
        var refused = false
        synchronized(lock) {
            if (decodeThread?.isAlive == true) {
                refused = true
            } else {
                decodeThread = null
                started = false
                running = false
                errorAttempts = 0
                needKeyFrame = true
                keyFrameWaitStartMs = -1L
                queue.clear()
                if (!outputSurface.isValid) {
                    failure = "输出 Surface 无效，解码器未启动"
                } else {
                    started = true
                    running = true
                    val thread = Thread({ decodeLoop() }, THREAD_NAME)
                    thread.isDaemon = true
                    decodeThread = thread
                    thread.start()
                    return true
                }
            }
        }
        val pendingFailure = failure
        when {
            refused -> emitEvent("start 被拒绝：上一解码线程仍存活")
            pendingFailure != null -> reportFatal(pendingFailure)
        }
        return false
    }

    /** 停止并等待解码线程退出（默认 1500ms）；幂等。 */
    fun stop() {
        stopAndWait(STOP_TIMEOUT_MS)
    }

    /**
     * 停止并等待解码线程退出（最多 timeoutMs）。
     *
     * @return true = 线程已退出/不存在；false = 超时或从解码线程自身调用
     *   （超时时保留线程引用，禁止在同一 Surface 上再建新解码器）
     */
    fun stopAndWait(timeoutMs: Long): Boolean {
        var decodeThreadRef: Thread? = null
        synchronized(lock) {
            if (!started && decodeThread == null) {
                queue.clear()
                needKeyFrame = true
                keyFrameWaitStartMs = -1L
                return true
            }
            started = false
            running = false
            needKeyFrame = true
            keyFrameWaitStartMs = -1L
            queue.clear()
            decodeThreadRef = decodeThread
            lock.notifyAll()
        }
        val thread = decodeThreadRef ?: return true
        if (thread === Thread.currentThread()) {
            // 解码线程自停：不能 join 自己；线程引用由 decodeLoop 的 finally 清理。
            emitEvent("stop 在解码线程内调用，等待线程自然退出")
            return false
        }
        thread.interrupt()
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (thread.isAlive && SystemClock.uptimeMillis() < deadline) {
            try {
                thread.join(50L)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!thread.isAlive) {
            synchronized(lock) {
                if (decodeThread === thread) decodeThread = null
            }
            return true
        }
        emitEvent("解码线程未在 ${timeoutMs}ms 内退出，保留引用等待其自然结束")
        return false
    }

    /** 是否正在解码（running 且线程存活）。 */
    fun isAlive(): Boolean = running && (decodeThread?.isAlive == true)

    // ---- 解码线程 ----

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        try {
            while (running) {
                if (!outputSurface.isValid) {
                    emitEvent("输出 Surface 失效，解码线程干净退出")
                    break
                }
                if (codec == null) {
                    codec = openCodec()
                    if (codec == null) {
                        if (!retryAfterFailure("打开解码器失败")) break
                        continue
                    }
                }
                var csdChanged = false
                var currentVersion = 0
                synchronized(lock) {
                    csdChanged = appliedCsdVersion != csdVersion
                    currentVersion = csdVersion
                }
                if (csdChanged) {
                    emitEvent("CSD 版本变化（applied=$appliedCsdVersion current=$currentVersion），重建 codec")
                    releaseCodec(codec)
                    codec = null
                    synchronized(lock) {
                        needKeyFrame = true
                        keyFrameWaitStartMs = -1L
                    }
                    continue
                }
                var worked = false
                try {
                    worked = drainOutput(codec)
                    worked = feedInput(codec) || worked
                } catch (error: Throwable) {
                    if (!running || !outputSurface.isValid) {
                        // stop() 已置 running=false 或 Surface 已被会话收尾释放：属于生命周期竞态，不是解码错误。
                        emitEvent("codec 操作与 stop/Surface 失效竞争，干净退出 - ${error.message}")
                        break
                    }
                    emitEvent("解码异常 ${error.javaClass.simpleName}，释放 codec 后重建")
                    releaseCodec(codec)
                    codec = null
                    synchronized(lock) {
                        needKeyFrame = true
                        keyFrameWaitStartMs = -1L
                    }
                    if (!retryAfterFailure("解码异常 ${error.javaClass.simpleName}")) break
                    continue
                }
                if (!worked) {
                    waitForWork(WAIT_SLICE_MS)
                }
            }
        } catch (error: Throwable) {
            reportFatal("解码线程异常退出：${error.message ?: error.javaClass.simpleName}")
        } finally {
            releaseCodec(codec)
            synchronized(lock) {
                running = false
                started = false
                if (decodeThread === Thread.currentThread()) decodeThread = null
                appliedCsdVersion = -1
                needKeyFrame = true
                keyFrameWaitStartMs = -1L
                queue.clear()
            }
            emitEvent("解码线程退出（已提交 $submittedFrames 帧 / 丢弃 $droppedFrames 帧）")
        }
    }

    private fun openCodec(): MediaCodec? {
        var codec: MediaCodec? = null
        try {
            val snapshot = configSnapshot()
            val format = buildFormat(snapshot)
            val created = MediaCodec.createDecoderByType(MIME_TYPE)
            codec = created
            created.configure(format, outputSurface, null, 0)
            created.start()
            synchronized(lock) {
                appliedCsdVersion = snapshot.csdVersion
            }
            emitEvent("解码器已启动 ${snapshot.width}x${snapshot.height} csd=${snapshot.hasCsd}")
            return created
        } catch (error: Throwable) {
            releaseCodec(codec)
            emitEvent("打开解码器失败 - ${error.message ?: error.javaClass.simpleName}")
            return null
        }
    }

    private fun buildFormat(snapshot: ConfigSnapshot): MediaFormat {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, snapshot.width, snapshot.height)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        if (fps > 0) {
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        }
        snapshot.sps?.takeIf { it.isNotEmpty() }?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
        snapshot.pps?.takeIf { it.isNotEmpty() }?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
        return format
    }

    private fun configSnapshot(): ConfigSnapshot =
        synchronized(lock) {
            ConfigSnapshot(codecWidth, codecHeight, spsNal, ppsNal, csdVersion)
        }

    /** 取空输出缓冲（非阻塞）；返回是否处理过任何事件。 */
    private fun drainOutput(codec: MediaCodec): Boolean {
        var worked = false
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_OUTPUT_TIMEOUT_US)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return worked
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                val fallbackWidth = synchronized(lock) { codecWidth }
                val fallbackHeight = synchronized(lock) { codecHeight }
                val outputWidth = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else fallbackWidth
                val outputHeight =
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else fallbackHeight
                synchronized(lock) {
                    codecWidth = outputWidth
                    codecHeight = outputHeight
                }
                emitEvent("输出格式变化 ${outputWidth}x$outputHeight")
                callListener { listener.onOutputFormatChanged(outputWidth, outputHeight) }
                worked = true
                continue
            }
            if (index < 0) return worked
            worked = true
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                codec.releaseOutputBuffer(index, false)
                return true
            }
            // Surface 输出模式：置 render=true 即把这一帧画到编码器 Surface 上。
            codec.releaseOutputBuffer(index, true)
            synchronized(lock) { errorAttempts = 0 }
            callListener { listener.onFrameRendered(info.presentationTimeUs) }
        }
    }

    /** 把队列里的帧按顺序投喂给 codec；返回是否处理过任何帧。 */
    private fun feedInput(codec: MediaCodec): Boolean {
        var worked = false
        while (true) {
            val frame = synchronized(lock) { queue.pollFirst() } ?: return worked
            worked = true
            if (!acceptFrame(frame)) continue
            val index = codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (index < 0) {
                pushFront(frame)
                return true
            }
            val buffer = codec.getInputBuffer(index)
            if (buffer == null) {
                pushFront(frame)
                return true
            }
            buffer.clear()
            if (frame.data.size > buffer.capacity()) {
                emitEvent("输入缓冲不足：帧 ${frame.data.size}B > 容量 ${buffer.capacity()}B，丢弃")
                // 归还空 buffer 所有权，避免 codec 输入槽饥饿；该帧丢弃。
                codec.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
                continue
            }
            buffer.put(frame.data, 0, frame.data.size)
            val flags = if (frame.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, flags)
            submittedFrames++
        }
    }

    /** 关键帧起解状态机；返回 true 表示该帧可投喂。 */
    private fun acceptFrame(frame: Frame): Boolean {
        var timeoutLine: String? = null
        val accepted =
            synchronized(lock) {
                if (!needKeyFrame) {
                    return true
                }
                if (frame.keyFrame) {
                    needKeyFrame = false
                    keyFrameWaitStartMs = -1L
                    return true
                }
                val now = SystemClock.uptimeMillis()
                if (keyFrameWaitStartMs < 0) {
                    keyFrameWaitStartMs = now
                }
                if (now - keyFrameWaitStartMs < KEY_FRAME_WAIT_TIMEOUT_MS) {
                    droppedFrames++
                    return false
                }
                needKeyFrame = false
                keyFrameWaitStartMs = -1L
                timeoutLine = "等待关键帧 ${KEY_FRAME_WAIT_TIMEOUT_MS}ms 超时，直接投喂非关键帧"
                true
            }
        timeoutLine?.let { emitEvent(it) }
        return accepted
    }

    private fun pushFront(frame: Frame) {
        synchronized(lock) {
            if (!running) return
            queue.addFirst(frame)
            lock.notifyAll()
        }
    }

    private fun waitForWork(timeoutMs: Long) {
        synchronized(lock) {
            if (!running || queue.isNotEmpty()) return
            try {
                lock.wait(timeoutMs)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** 线性退避等待（可被 stop 唤醒）；返回 false = 重试次数耗尽，调用方应退出循环。 */
    private fun retryAfterFailure(reason: String): Boolean {
        var attempt = 0
        synchronized(lock) {
            errorAttempts++
            attempt = errorAttempts
            lock.notifyAll()
        }
        if (attempt > MAX_ERROR_ATTEMPTS) {
            reportFatal("$reason：连续 $MAX_ERROR_ATTEMPTS 次重建解码器仍失败，放弃（Surface 归调用方，未释放）")
            return false
        }
        callListener { listener.onDecoderError("$reason（第 $attempt/$MAX_ERROR_ATTEMPTS 次，退避后重建 codec）") }
        val backoffMs = minOf(ERROR_BACKOFF_MAX_MS, ERROR_BACKOFF_BASE_MS * attempt)
        emitEvent("等待 ${backoffMs}ms 后重建解码器")
        backoffSleep(backoffMs)
        return running
    }

    private fun backoffSleep(durationMs: Long) {
        val deadline = SystemClock.uptimeMillis() + durationMs
        synchronized(lock) {
            while (running) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0) break
                try {
                    lock.wait(remaining)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    private fun releaseCodec(codec: MediaCodec?) {
        if (codec == null) return
        try {
            codec.stop()
        } catch (error: Throwable) {
            emitEvent("codec.stop 异常 - ${error.message ?: error.javaClass.simpleName}")
        }
        try {
            codec.release()
        } catch (error: Throwable) {
            emitEvent("codec.release 异常 - ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** 致命错误：置停止态并通知监听器（每轮致命错误只由退出路径调用一次）；解码线程随后退出。 */
    private fun reportFatal(message: String) {
        synchronized(lock) {
            running = false
            started = false
            lock.notifyAll()
        }
        emitEvent(message)
        callListener { listener.onDecoderError(message) }
    }

    private fun emitEvent(line: String) {
        callListener { listener.onDecoderEvent(line) }
    }

    /** 监听器异常不能杀死解码线程或破坏状态机。 */
    private fun callListener(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            // 监听器自身异常只吞掉：解码线程的职责是保持画面链路存活。
        }
    }

    private class ConfigSnapshot(
        val width: Int,
        val height: Int,
        val sps: ByteArray?,
        val pps: ByteArray?,
        val csdVersion: Int,
    ) {
        val hasCsd: Boolean get() = sps != null && pps != null
    }

    private companion object {
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC

        /** 未传/非法宽高时的回退档（CarLife 引擎默认编码档）。 */
        private const val FALLBACK_WIDTH = 1280
        private const val FALLBACK_HEIGHT = 720

        /** CarLifeHuClient 单帧上限 4MB。 */
        private const val MAX_INPUT_SIZE = 4 * 1024 * 1024

        /** 有界帧队列：约 20~30fps 下留 ~300ms 抖动余量，超过即按丢帧策略处理。 */
        private const val FRAME_QUEUE_CAPACITY = 8
        private const val INPUT_DEQUEUE_TIMEOUT_US = 5_000L
        private const val DEQUEUE_OUTPUT_TIMEOUT_US = 0L
        private const val WAIT_SLICE_MS = 5L

        /** 重建 codec 后最多等多久关键帧；超时兜底投喂非关键帧，避免永久停摆。 */
        private const val KEY_FRAME_WAIT_TIMEOUT_MS = 3_000L
        private const val DROP_LOG_INTERVAL_MS = 2_000L

        private const val MAX_ERROR_ATTEMPTS = 5
        private const val ERROR_BACKOFF_BASE_MS = 250L
        private const val ERROR_BACKOFF_MAX_MS = 2_000L
        private const val STOP_TIMEOUT_MS = 1_500L
        private const val THREAD_NAME = "CarLifeDec"

        private fun contentEquals(
            left: ByteArray?,
            right: ByteArray?,
        ): Boolean =
            when {
                left == null -> right == null
                right == null -> false
                else -> left.contentEquals(right)
            }
    }
}
