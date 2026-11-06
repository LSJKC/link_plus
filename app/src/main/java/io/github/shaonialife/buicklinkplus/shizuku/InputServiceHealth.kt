package io.github.shaonialife.buicklinkplus.shizuku

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** 同一服务共用探活任务，调用方超时不取消任务，旧结果不覆盖新服务 */
internal class InputServiceHealth<O : Any, I : Any>(
    private val currentOwner: () -> O?,
    private val inputAlive: (I) -> Boolean,
    private val probe: (O) -> I,
    private val log: (String) -> Unit,
    private val executor: ThreadPoolExecutor =
        ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { task -> Thread(task, "embedded-service-health").apply { isDaemon = true } },
        ),
) {
    private val lock = Any()
    private var attempt: Attempt<O>? = null
    private var binding: Pair<O, I>? = null
    private var retryAfterNs = 0L

    /**
     * 仅返回属于当前服务且 Binder 仍存活的输入快照
     */
    val service: I?
        get() = synchronized(lock) { binding?.takeIf { it.first === currentOwner() && inputAlive(it.second) }?.second }

    /**
     * 后台预热不创建额外等待线程，也不重复排队
     */
    fun ensure() {
        if (service == null) request(retry = false)
    }

    /**
     * 所有等待者共用结果，只有调用者自己的等待受截止时间限制
     */
    fun await(
        retry: Boolean,
        timeoutMs: Long = 10_000,
    ): Boolean {
        if (!retry && service != null) return true
        val task = request(retry) ?: return false
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS) && service != null
        } catch (_: TimeoutException) {
            log("SERVICE: 输入检查等待超时（${timeoutMs}毫秒），保留在途检查，可直接重试")
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * 服务死亡后撤销快照及在途结果，只移除尚未开始的旧检查
     */
    fun invalidate(owner: O): Boolean =
        synchronized(lock) {
            val affected = binding?.first === owner || attempt?.owner === owner
            if (binding?.first === owner) binding = null
            if (attempt?.owner === owner) {
                attempt?.let { executor.remove(it.task) }
                attempt = null
            }
            affected
        }

    /** 最多运行一个任务并排队一个最新请求，失败后预热间隔至少 1 秒 */
    private fun request(retry: Boolean): FutureTask<Boolean>? =
        synchronized(lock) {
            val owner = currentOwner() ?: return null
            val previous = attempt
            if (previous?.owner === owner) {
                if (!previous.task.isDone || !retry && System.nanoTime() < retryAfterNs) return previous.task
            } else {
                previous?.let { executor.remove(it.task) }
            }
            val next = Attempt(owner)
            next.task =
                FutureTask {
                    try {
                        val input = probe(owner)
                        check(inputAlive(input)) { "输入服务连接已失效" }
                        synchronized(lock) {
                            if (attempt !== next || currentOwner() !== owner) return@FutureTask false
                            binding = owner to input
                            log("SERVICE: 内置 shell 与输入服务已就绪")
                        }
                        true
                    } catch (error: Exception) {
                        synchronized(lock) {
                            if (attempt === next && currentOwner() === owner) {
                                binding = null
                                retryAfterNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
                                log("SERVICE: 输入服务检查失败：${error.javaClass.simpleName}：${error.message ?: "原因未知"}")
                            }
                        }
                        false
                    }
                }
            attempt = next
            retryAfterNs = 0L
            executor.execute(next.task)
            log("SERVICE: 开始检查内置输入服务")
            next.task
        }

    /**
     * 请求身份用于屏蔽已失效或被替换的迟到结果
     */
    private class Attempt<O>(
        val owner: O,
    ) {
        lateinit var task: FutureTask<Boolean>
    }
}
