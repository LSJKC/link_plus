package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SharedLyricsQueriesTest {
    private val track = LyricsTrack(1, "com.tencent.qqmusic", "1", "歌曲", "歌手", "专辑", 180_000)
    private val result = LyricsResult.from(LyricsDocument(plainText = "歌词"), "LRCLIB")

    private class Queue : Executor {
        val tasks = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    /** 两个窗口合并一次查询，一个窗口退出不取消另一个窗口 */
    @Test fun windowsShareRequestAndCancelIndependently() {
        val queue = Queue()
        var calls = 0
        val shared =
            SharedLyricsQueries(queue, { queue.tasks.remove(it) }, { 0 }) { _, _ ->
                calls++
                result
            }
        val firstResults = mutableListOf<LyricsResult>()
        val secondResults = mutableListOf<LyricsResult>()
        val first = shared.subscribe(track, firstResults::add)
        val second = shared.subscribe(track.copy(session = 2, embedded = "实时变化"), secondResults::add)
        assertEquals(1, queue.tasks.size)
        first.cancel()
        queue.runAll()
        assertTrue(firstResults.isEmpty())
        assertEquals(listOf(result), secondResults)
        assertEquals(1, calls)
        val third = shared.subscribe(track, firstResults::add)
        assertEquals(listOf(result), firstResults)
        assertEquals(0, queue.tasks.size)
        second.cancel()
        third.cancel()
        assertEquals(0, shared.subscriberCount)
    }

    /** 连续切歌和隐藏重开一千轮，取消后不残留订阅或排队任务 */
    @Test fun rapidChangesKeepQueueAndOwnershipBounded() {
        val queue = Queue()
        var calls = 0
        var deliveries = 0
        val shared =
            SharedLyricsQueries(queue, { queue.tasks.remove(it) }, { 0 }) { _, _ ->
                calls++
                result
            }
        repeat(1_000) { index ->
            val first = shared.subscribe(track.copy(title = "歌曲$index")) { deliveries++ }
            val second = shared.subscribe(track.copy(title = "歌曲$index")) { deliveries++ }
            assertEquals(1, queue.tasks.size)
            first.cancel()
            second.cancel()
            assertEquals(0, shared.activeRequests)
            assertEquals(0, shared.subscriberCount)
            assertTrue(queue.tasks.isEmpty())
        }
        queue.runAll()
        assertEquals(0, calls)
        assertEquals(0, deliveries)
    }

    /** 排队预算到期和队列拒绝都返回降级结果，不在调用线程执行查询 */
    @Test fun expiryAndRejectedQueueDoNotRunLookup() {
        val queue = Queue()
        var time = 0L
        var calls = 0
        val shared =
            SharedLyricsQueries(queue, { queue.tasks.remove(it) }, { time }) { _, _ ->
                calls++
                result
            }
        val results = mutableListOf<LyricsResult>()
        val subscription = shared.subscribe(track, results::add)
        time = 12_001
        queue.runAll()
        assertEquals(LyricsStatus.FAILED, results.single().status)
        assertEquals(LyricsFailure.TIMEOUT, results.single().failure)
        subscription.cancel()
        val rejected =
            SharedLyricsQueries(Executor { throw RejectedExecutionException() }, {}, { 0 }) { _, _ ->
                calls++
                result
            }
        rejected.subscribe(track, results::add).cancel()
        assertEquals(LyricsStatus.FAILED, results.last().status)
        assertEquals(LyricsFailure.QUEUE_FULL, results.last().failure)
        assertEquals(0, calls)
        assertEquals(0, rejected.subscriberCount)
    }

    /** 区分断网、连接中断和损坏内容，不记为无歌词 */
    @Test fun failureReasonsSurviveTheSharedQueryBoundary() {
        for ((error, reason) in listOf(
            LyricsNoInternet() to LyricsFailure.NO_INTERNET,
            java.net.SocketException("连接中断") to LyricsFailure.CONNECTION,
            java.net.UnknownHostException("无法解析") to LyricsFailure.CONNECTION,
            java.io.IOException("载荷损坏") to LyricsFailure.INVALID_RESPONSE,
        )) {
            val queue = Queue()
            val shared = SharedLyricsQueries(queue, {}, { 0 }) { _, _ -> throw error }
            val results = mutableListOf<LyricsResult>()
            val subscription = shared.subscribe(track, results::add)
            queue.runAll()
            assertEquals(LyricsStatus.FAILED, results.single().status)
            assertEquals(reason, results.single().failure)
            subscription.cancel()
            assertEquals(0, shared.subscriberCount)
        }
    }

    /** 后台请求忽略取消而迟到时，不能覆盖后来同名的新请求 */
    @Test fun lateRunningWorkCannotPublishToReplacement() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var calls = 0
        val results = java.util.Collections.synchronizedList(mutableListOf<LyricsResult>())
        val shared =
            SharedLyricsQueries(executor, {}, { 0 }) { _, _ ->
                if (++calls == 1) {
                    started.countDown()
                    while (release.count > 0) {
                        try {
                            release.await()
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                result
            }
        try {
            val old = shared.subscribe(track, results::add)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            old.cancel()
            val fresh =
                shared.subscribe(track) {
                    results.add(it)
                    finished.countDown()
                }
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(result), results.toList())
            fresh.cancel()
            assertEquals(0, shared.subscriberCount)
            assertEquals(0, shared.activeRequests)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
