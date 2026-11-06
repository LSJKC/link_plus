package io.github.shaonialife.buicklinkplus.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Executor

class AsyncSessionStopTest {
    /**
     * 调用线程只取消而清理等待后台调度
     */
    @Test fun cleanupIsDeferredAndRepeatedClicksCoalesce() {
        val queued = mutableListOf<Runnable>()
        val stop = AsyncSessionStop(Executor { queued.add(it) })
        var cancelled = false
        var cleaned = false
        assertTrue(stop.submit({ cancelled = true }, { cleaned = true }))
        assertTrue(cancelled)
        assertFalse(cleaned)
        assertTrue(stop.stopping)
        assertFalse(stop.submit({ fail() }, { fail() }))
        queued.single().run()
        assertTrue(cleaned)
        assertFalse(stop.stopping)
    }

    /**
     * 清理异常和调度拒绝都必须释放退出门闸
     */
    @Test fun failuresReleaseGate() {
        val queued = mutableListOf<Runnable>()
        val stop = AsyncSessionStop(Executor { queued.add(it) })
        stop.submit({}, { error("清理失败") })
        runCatching { queued.single().run() }
        assertFalse(stop.stopping)
        val rejected = AsyncSessionStop(Executor { throw java.util.concurrent.RejectedExecutionException() })
        runCatching { rejected.submit({}, {}) }
        assertFalse(rejected.stopping)
    }
}
