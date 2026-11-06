package io.github.shaonialife.buicklinkplus.bt

/** 按单调时钟限制连接期限和遗留连接数量 */
internal class BluetoothConnectBudget(
    private val limit: Int = 2,
) {
    private val active = mutableSetOf<String>()

    /** 同一目标只能保留一个阻塞任务，所有目标合计也有上限 */
    @Synchronized fun acquire(target: String): Boolean = active.size < limit && active.add(target)

    /** 连接线程结束后再释放额度，关闭请求不算结束 */
    @Synchronized fun release(target: String) {
        active.remove(target)
    }

    /** 按实际截止时间拒绝迟到成功，不依赖定时器是否触发 */
    fun expired(
        startedAt: Long,
        now: Long,
        timeout: Long,
    ): Boolean = now - startedAt >= timeout
}
