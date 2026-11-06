package io.github.shaonialife.buicklinkplus.util

/**
 * 取消和替换请求时推进代次，旧任务只能结束自身工作
 */
class RequestEpoch {
    private val counter =
        java.util.concurrent.atomic
            .AtomicLong()
    val current: Long get() = counter.get()

    /**
     * 创建新请求或取消当前请求并使旧凭据失效
     */
    fun advance(): Long = counter.incrementAndGet()

    /**
     * 判断回调是否仍持有当前请求
     */
    fun owns(ticket: Long): Boolean = current == ticket
}
