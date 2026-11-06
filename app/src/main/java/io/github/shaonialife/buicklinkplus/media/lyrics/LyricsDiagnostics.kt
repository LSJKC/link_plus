package io.github.shaonialife.buicklinkplus.media.lyrics

import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 通知结构诊断异步写入，队列满时丢弃诊断而不阻塞媒体回调 */
internal object LyricsDiagnostics {
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(8),
            { action -> Thread(action, "lyrics-diagnostics").apply { isDaemon = true } },
            ThreadPoolExecutor.DiscardPolicy(),
        ).apply { allowCoreThreadTimeOut(true) }

    /** 消息由调用处限制大小，不持有通知对象或界面上下文 */
    fun record(message: String) {
        executor.execute { runCatching { VirtualLauncher.logSink?.invoke(message) } }
    }
}
