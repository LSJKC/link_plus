package io.github.shaonialife.buicklinkplus.bt

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 双向建链原子检查并取得同一占用凭据 */
internal class BluetoothSessionArbiter {
    // 此监视器需要 wait/notify，保留 Java Object 的显式接口
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object()
    private var owner: Lease? = null
    private var latest: Lease? = null
    private val operations = mutableMapOf<Lease, Int>()

    val inboundActive: Boolean get() = synchronized(lock) { owner?.inbound == true }
    val controlActive: Boolean get() = synchronized(lock) { owner != null || operations.isNotEmpty() }

    /** 回连可接替未协商成功的出站尝试，不抢占健康链路 */
    fun acquire(inbound: Boolean): Lease? {
        val previous: Lease?
        val acquired: Lease
        synchronized(lock) {
            previous = owner
            if (previous != null && (!inbound || previous.inbound || previous.confirmed)) return null
            previous?.preempted = true
            acquired = Lease(inbound)
            owner = acquired
            latest = acquired
            lock.notifyAll()
        }
        previous?.notifyPreempted()
        return acquired
    }

    /** 只有原凭据可释放占用，旧清理不解锁新连接 */
    inner class Lease internal constructor(
        val inbound: Boolean,
    ) : Closeable {
        internal var confirmed = inbound
        internal var preempted = false
        private var preemptAction: (() -> Unit)? = null

        val isCurrent: Boolean get() = synchronized(lock) { owner === this }
        val isLatest: Boolean get() = synchronized(lock) { latest === this }

        /** 回连先取得所有权后，出站即使迟到成功也不能再启动协议或交付结果 */
        fun checkCurrent() {
            if (!isCurrent) throw IOException("蓝牙控制会话已退役")
        }

        /** 协议协商成功后保护控制通道，RFCOMM 连通不算就绪 */
        fun confirm() {
            synchronized(lock) {
                checkCurrent()
                confirmed = true
            }
        }

        /** 锁外通知撤销，取消不等待写入锁 */
        fun onPreempted(action: () -> Unit) {
            val notify =
                synchronized(lock) {
                    preemptAction = action
                    preempted
                }
            if (notify) notifyPreempted()
        }

        /** 通知只消费一次，先撤销再登记回调也不能漏掉收尾 */
        internal fun notifyPreempted() {
            val action = synchronized(lock) { preemptAction.also { preemptAction = null } }
            action?.invoke()
        }

        /** 新通道必须等旧写入和音频策略退出；物理关闭本身不占用逻辑门闸 */
        fun awaitQuiescent(timeoutMillis: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            synchronized(lock) {
                while (owner === this && operations.keys.any { it !== this }) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return false
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lock, remaining)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
                return owner === this
            }
        }

        /** 写入和音频操作归原凭据，交接等待它们退出 */
        fun <T> withOwnership(action: () -> T): T {
            synchronized(lock) {
                checkCurrent()
                if (operations.keys.any { it !== this }) throw IOException("上一条蓝牙控制操作尚未退出")
                operations[this] = (operations[this] ?: 0) + 1
            }
            try {
                return action()
            } finally {
                synchronized(lock) {
                    val remaining = requireNotNull(operations[this]) - 1
                    if (remaining == 0) operations.remove(this) else operations[this] = remaining
                    lock.notifyAll()
                }
            }
        }

        /** 先撤销逻辑身份，再按额度关闭 socket；旧操作退出前不开放新写入 */
        override fun close() {
            synchronized(lock) {
                if (owner === this) owner = null
                lock.notifyAll()
            }
        }
    }
}

/** 进程内蓝牙控制通道所有权与已建立的投屏会话门闸 */
object BtSessionGate {
    private val arbiter = BluetoothSessionArbiter()

    val inboundActive: Boolean get() = arbiter.inboundActive
    val controlActive: Boolean get() = arbiter.controlActive

    @Volatile
    var sessionActive: Boolean = false

    /** accept 后先预留回连，等旧控制写入退出再启动协议 */
    internal fun acquireInbound(): BluetoothSessionArbiter.Lease? = arbiter.acquire(inbound = true)

    /** 出站创建套接字前原子占用，已建立投屏时保持原有静默策略 */
    internal fun acquireOutbound(): BluetoothSessionArbiter.Lease? = if (sessionActive) null else arbiter.acquire(inbound = false)
}
