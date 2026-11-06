package io.github.shaonialife.buicklinkplus.shizuku

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 为 ADB 握手增加限时传输，关闭 socket 可取消阻塞读取 */
internal class AdbTransport(
    host: String,
    port: Int,
) : Closeable {
    private val closed = AtomicBoolean()
    private val firstFailure = AtomicReference<Exception?>()
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val remote = Socket()

    @Volatile private var local: Socket? = null
    private val deadline = timer.schedule({ close() }, 30, TimeUnit.SECONDS)
    val localPort: Int = listener.localPort

    /** 保留传输异常，避免代理将连接拒绝变成无原因 EOF */
    val failure: Exception? get() = firstFailure.get()

    init {
        Thread({
            try {
                val accepted = listener.accept()
                local = accepted
                if (closed.get()) {
                    accepted.close()
                    return@Thread
                }
                remote.connect(InetSocketAddress(host, port), 5000)
                remote.tcpNoDelay = true
                accepted.tcpNoDelay = true
                Thread({
                    try {
                        accepted.getInputStream().copyTo(remote.getOutputStream())
                    } catch (error: Exception) {
                        if (!closed.get()) firstFailure.compareAndSet(null, error)
                        // 对端关闭或用户取消时结束上传
                    } finally {
                        close()
                    }
                }, "adb-upload").apply { isDaemon = true }.start()
                remote.getInputStream().copyTo(accepted.getOutputStream())
            } catch (error: Exception) {
                if (!closed.get()) firstFailure.compareAndSet(null, error)
                // 取消、连接拒绝或超时均由调用方收到真实连接失败
            } finally {
                close()
            }
        }, "adb-transport").apply { isDaemon = true }.start()
    }

    /**
     * 关闭监听和双向连接，使配对与激活均能及时退出
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        deadline.cancel(false)
        runCatching { listener.close() }
        runCatching { remote.close() }
        runCatching { local?.close() }
    }

    companion object {
        private val timer =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "adb-timeout").apply { isDaemon = true }
            }
    }
}
