package io.github.shaonialife.buicklinkplus.audio

import android.media.AudioRecord
import android.os.Binder
import android.os.ParcelFileDescriptor
import io.github.shaonialife.buicklinkplus.compat.ICarInput
import java.io.Closeable

/**
 * 旧「本机采集」数据源抽象（系统投屏播放捕获 / 内置服务管道）。
 *
 * **CarLife 音频不再使用本机采集**：引擎 9240/9241 直接推 PCM，经
 * [io.github.shaonialife.buicklinkplus.carlife.CarLifeAudioSink] → AudioSender 外部 PCM 发送，
 * 不需要 MediaProjection/录音权限。本文件整体废弃，仅为 androidTest（ShellCapture 取消语义）
 * 与遗留引用保留编译兼容；等测试迁移后删除。
 */
internal interface AudioCaptureSource : Closeable {
    val exclusive: Boolean

    fun start()

    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

/**
 * 原系统授权采集保留在应用进程（已废弃：CarLife 音频不再需要 MediaProjection 采集）。
 */
internal class ProjectionCapture(
    private val recorder: AudioRecord,
) : AudioCaptureSource {
    override val exclusive = false

    /**
     * 必须确认实际进入录音状态
     */
    override fun start() {
        recorder.startRecording()
        check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "系统未允许采集" }
    }

    /**
     * 读取保留实际字节数，由发送器拼接完整采样帧
     */
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = recorder.read(buffer, offset, length, AudioRecord.READ_BLOCKING)

    /**
     * 停止和释放允许重复调用
     */
    override fun close() {
        runCatching { recorder.stop() }
        recorder.release()
    }
}

/**
 * 管道和服务凭据绑定同一会话，旧发送器不停止新采集。
 *
 * 已废弃：CarLife 音频不再走内置服务采集管道；仅为 androidTest（取消语义）保留。
 */
internal class ShellCapture(
    private val service: ICarInput,
) : AudioCaptureSource {
    private val token = Binder()
    private val session = nextSession.incrementAndGet()
    private val stateLock = Any()
    private var closed = false
    private var input: ParcelFileDescriptor.AutoCloseInputStream? = null
    override val exclusive = true

    /** 返回管道前确认采集已启动 */
    override fun start() = Unit

    /**
     * 管道结束直接报告 EOF，不能继续发送旧采样
     */
    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = checkNotNull(synchronized(stateLock) { input }).read(buffer, offset, length)

    /**
     * 先关闭应用管道解除阻塞，服务停止在后台完成
     */
    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            runCatching { input?.close() }
            input = null
        }
        io.github.shaonialife.buicklinkplus.util.Bg
            .run("shell-audio-stop") { runCatching { service.stopAudioCapture(session) } }
    }

    /** 远程初始化设置超时，取消后关闭迟到管道 */
    private fun initialize(
        sampleRate: Int,
        isCurrent: () -> Boolean,
    ) {
        val completed = java.util.concurrent.CountDownLatch(1)
        var failure: Exception? = null
        try {
            starts.execute {
                try {
                    if (synchronized(stateLock) { closed }) return@execute
                    check(service.capabilities() and 1 != 0) { "服务未提供媒体路由能力，请重新绑定或使用兼容授权" }
                    val pipe =
                        ParcelFileDescriptor.AutoCloseInputStream(
                            checkNotNull(service.startAudioCapture(session, sampleRate, token)),
                        )
                    val accepted =
                        synchronized(stateLock) {
                            if (closed) {
                                false
                            } else {
                                input = pipe
                                true
                            }
                        }
                    if (!accepted) {
                        runCatching { pipe.close() }
                        runCatching { service.stopAudioCapture(session) }
                    }
                } catch (error: Exception) {
                    failure = error
                } finally {
                    completed.countDown()
                }
            }
            val deadline = android.os.SystemClock.uptimeMillis() + 4_000
            while (!completed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                check(isCurrent()) { "音频会话已取消" }
                check(android.os.SystemClock.uptimeMillis() < deadline) { "媒体采集初始化超时，请使用兼容授权" }
            }
            check(isCurrent()) { "音频会话已取消" }
            failure?.let { throw it }
            check(synchronized(stateLock) { input != null && !closed }) { "媒体采集未返回管道" }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    companion object {
        private val starts =
            java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(1),
                { runnable -> Thread(runnable, "shell-audio-start").apply { isDaemon = true } },
            )
        private val nextSession =
            java.util.concurrent.atomic
                .AtomicLong(System.nanoTime())

        /** 只返回已启动且属于当前会话的采集源 */
        fun open(
            service: ICarInput,
            sampleRate: Int,
            isCurrent: () -> Boolean,
        ): ShellCapture = ShellCapture(service).apply { initialize(sampleRate, isCurrent) }
    }
}
