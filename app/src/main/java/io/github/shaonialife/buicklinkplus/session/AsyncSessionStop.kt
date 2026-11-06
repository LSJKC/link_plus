package io.github.shaonialife.buicklinkplus.session

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 合并退出请求并在后台执行阻塞资源清理
 */
internal class AsyncSessionStop(
    private val executor: Executor,
) {
    private val busy = AtomicBoolean()
    val stopping: Boolean get() = busy.get()

    /**
     * 立即撤销旧操作但不在调用线程释放资源
     */
    fun submit(
        cancel: () -> Unit,
        cleanup: () -> Unit,
    ): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            cancel()
            executor.execute {
                try {
                    cleanup()
                } finally {
                    busy.set(false)
                }
            }
        } catch (error: RuntimeException) {
            busy.set(false)
            throw error
        }
        return true
    }
}
