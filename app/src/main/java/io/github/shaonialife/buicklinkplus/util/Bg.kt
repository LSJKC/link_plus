package io.github.shaonialife.buicklinkplus.util

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次性后台任务共用的缓存线程池，空闲 60 秒回收
 * GL、收包和监听等长驻循环仍使用独立线程
 */
object Bg {
    private val counter = AtomicInteger(0)

    val pool: ExecutorService =
        Executors.newCachedThreadPool { r ->
            Thread(r, "app-bg-${counter.incrementAndGet()}").apply { isDaemon = true }
        }

    /** 提交一次性后台任务。[name] 进线程名（阻塞/崩溃时 logcat 可定位）。 */
    fun run(
        name: String,
        block: () -> Unit,
    ) {
        pool.execute {
            Thread.currentThread().name = "app-bg-$name"
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("Bg", "bg task '$name' failed", t)
            }
        }
    }
}
