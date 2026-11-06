package io.github.shaonialife.buicklinkplus.shizuku

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** 用可控慢调用检查并发、超时、取消和服务替换 */
class InputServiceHealthTest {
    private val workers = mutableListOf<java.util.concurrent.ExecutorService>()

    /**
     * 每个用例结束回收其执行器，避免线程泄漏影响后续检查
     */
    @After
    fun closeWorkers() {
        workers.forEach { it.shutdownNow() }
        workers.forEach { assertTrue(it.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    /**
     * 首页预热与多个连接检查只发起一次远端调用
     */
    @Test
    fun concurrentWarmupAndWaitersShareOneProbe() {
        val owner = Any()
        val count = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val health =
            monitor({ owner }) {
                count.incrementAndGet()
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                owner
            }
        health.ensure()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(100) { health.ensure() }
        val callers = Executors.newFixedThreadPool(8).also { workers.add(it) }
        val waiting = CountDownLatch(8)
        val results =
            (1..8).map {
                callers.submit<Boolean> {
                    waiting.countDown()
                    health.await(retry = false)
                }
            }
        assertTrue(waiting.await(3, TimeUnit.SECONDS))
        release.countDown()
        results.forEach { assertTrue(it.get(3, TimeUnit.SECONDS)) }
        assertEquals(1, count.get())
        assertSame(owner, health.service)
    }

    /**
     * 短等待超时后同一个慢结果仍可发布，重试不重复请求
     */
    @Test
    fun timeoutKeepsSlowResultAndRetrySharesIt() {
        val owner = Any()
        val count = AtomicInteger()
        val release = CountDownLatch(1)
        val health =
            monitor({ owner }) {
                count.incrementAndGet()
                check(release.await(3, TimeUnit.SECONDS))
                owner
            }
        assertFalse(health.await(retry = true, timeoutMs = 1))
        assertNull(health.service)
        assertFalse(health.await(retry = true, timeoutMs = 1))
        release.countDown()
        assertTrue(health.await(retry = false))
        assertEquals(1, count.get())
    }

    /**
     * 取消单个等待者只中断其等待，不能取消其他入口共用的检查
     */
    @Test
    fun interruptedWaiterDoesNotCancelProbe() {
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val health =
            monitor({ owner }) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                owner
            }
        val interrupted = AtomicBoolean()
        val waiter =
            Thread {
                assertFalse(health.await(retry = true))
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        waiter.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        waiter.interrupt()
        waiter.join(3000)
        assertFalse(waiter.isAlive)
        assertTrue(interrupted.get())
        release.countDown()
        assertTrue(health.await(retry = false))
    }

    /** 能力检查失败后撤销就绪，明确重试可恢复 */
    @Test
    fun failedRefreshRevokesReadyAndCanRecover() {
        val owner = Any()
        val fail = AtomicBoolean()
        val count = AtomicInteger()
        val health =
            monitor({ owner }) {
                count.incrementAndGet()
                check(!fail.get()) { "输入能力不可用" }
                owner
            }
        assertTrue(health.await(retry = true))
        fail.set(true)
        assertFalse(health.await(retry = true))
        assertNull(health.service)
        repeat(100) { health.ensure() }
        assertEquals(2, count.get())
        fail.set(false)
        assertTrue(health.await(retry = true))
    }

    /**
     * 老服务卡住时排队只保留最新服务，迟到结果不得污染当前状态
     */
    @Test
    fun repeatedReplacementKeepsOnlyLatestQueuedProbe() {
        val first = Any()
        val current = AtomicReference(first)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val count = AtomicInteger()
        val health =
            monitor({ current.get() }) { owner ->
                count.incrementAndGet()
                if (owner === first) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                }
                owner
            }
        health.ensure()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        repeat(100) {
            current.set(Any())
            health.ensure()
        }
        assertNull(health.service)
        assertFalse(health.invalidate(first))
        release.countDown()
        assertTrue(health.await(retry = false))
        assertSame(current.get(), health.service)
        assertEquals(2, count.get())
    }

    /**
     * 死亡回调撤销在途身份，即使远端随后返回也不能恢复旧状态
     */
    @Test
    fun invalidatedSlowProbeCannotRepublish() {
        val owner = Any()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = executor()
        val health =
            InputServiceHealth({ owner }, { true }, {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                owner
            }, {}, executor)
        health.ensure()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(health.invalidate(owner))
        release.countDown()
        executor.execute { finished.countDown() }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        assertNull(health.service)
    }

    /** 无服务或输入 Binder 死亡时不报告就绪 */
    @Test
    fun missingOwnerAndDeadInputRemainUnavailable() {
        val owner = Any()
        val current = AtomicReference<Any?>(null)
        val alive = AtomicBoolean(true)
        val health = InputServiceHealth({ current.get() }, { _: Any -> alive.get() }, { it }, {}, executor())
        assertFalse(health.await(retry = true))
        current.set(owner)
        assertTrue(health.await(retry = true))
        alive.set(false)
        assertNull(health.service)
        assertFalse(health.await(retry = true))
    }

    /**
     * 创建测试专用的有界探活执行器
     */
    private fun executor(): ThreadPoolExecutor =
        ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)).also { workers.add(it) }

    /**
     * 用例共享生产协调器，远端调用由每个用例控制
     */
    private fun monitor(
        owner: () -> Any?,
        probe: (Any) -> Any,
    ): InputServiceHealth<Any, Any> = InputServiceHealth(owner, { true }, probe, {}, executor())
}
