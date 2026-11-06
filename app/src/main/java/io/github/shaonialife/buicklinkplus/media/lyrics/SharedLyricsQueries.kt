package io.github.shaonialife.buicklinkplus.media.lyrics

import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

/** 窗口退出时只撤销自己的订阅，最后一个订阅离开才取消共用请求 */
internal fun interface LyricsSubscription {
    fun cancel()
}

/** 同一录音只占一个后台任务，完成结果仅在仍有窗口订阅时保留 */
internal class SharedLyricsQueries(
    private val executor: Executor,
    private val removeQueued: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val load: (LyricsTrack, LyricsRequest) -> LyricsResult,
) {
    private class Job(
        val key: LyricsTrack,
        val request: LyricsRequest,
    ) {
        val listeners = linkedMapOf<Long, (LyricsResult) -> Unit>()
        lateinit var work: FutureTask<Unit>
        var result: LyricsResult? = null
    }

    private val lock = Any()
    private val jobs = mutableMapOf<LyricsTrack, Job>()
    private var nextListener = 0L
    val activeRequests: Int get() = synchronized(lock) { jobs.values.count { it.result == null } }
    val subscriberCount: Int get() = synchronized(lock) { jobs.values.sumOf { it.listeners.size } }

    /** 注册和排队互斥，防止窗口先取消后旧任务才进入队列 */
    fun subscribe(
        track: LyricsTrack,
        deliver: (LyricsResult) -> Unit,
    ): LyricsSubscription {
        val key = track.recording()
        var ready: LyricsResult? = null
        var rejected: Job? = null
        val id: Long
        synchronized(lock) {
            id = ++nextListener
            val existing = jobs[key]
            val job = existing ?: Job(key, LyricsRequest(clock)).also { jobs[key] = it }
            job.listeners[id] = deliver
            ready = job.result
            if (existing == null) {
                job.work =
                    FutureTask {
                        val result =
                            runCatching {
                                job.request.remainingMs()
                                load(track, job.request).also { job.request.remainingMs() }
                            }.getOrElse { error ->
                                LyricsResult(
                                    LyricsStatus.FAILED,
                                    failure =
                                        when (error) {
                                            is LyricsNoInternet -> LyricsFailure.NO_INTERNET
                                            is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> LyricsFailure.TIMEOUT
                                            is java.net.UnknownHostException, is java.net.SocketException,
                                            is javax.net.ssl.SSLException,
                                            -> LyricsFailure.CONNECTION
                                            else -> LyricsFailure.INVALID_RESPONSE
                                        },
                                )
                            }
                        complete(job, result)
                        Unit
                    }
                runCatching { executor.execute(job.work) }.onFailure { rejected = job }
            }
        }
        rejected?.let { complete(it, LyricsResult(LyricsStatus.FAILED, failure = LyricsFailure.QUEUE_FULL)) }
        ready?.let(deliver)
        return LyricsSubscription { unsubscribe(key, id) }
    }

    /** 先缓存结果再回调，窗口自行检查会话和请求代次 */
    private fun complete(
        job: Job,
        result: LyricsResult,
    ) {
        val listeners =
            synchronized(lock) {
                if (jobs[job.key] !== job || job.result != null) return
                job.result = result
                job.listeners.values.toList()
            }
        listeners.forEach { runCatching { it(result) } }
    }

    /** 取消不等待线程退出，不在界面线程关闭厂商网络连接 */
    private fun unsubscribe(
        key: LyricsTrack,
        id: Long,
    ) {
        synchronized(lock) {
            val job = jobs[key] ?: return
            job.listeners.remove(id)
            if (job.listeners.isNotEmpty()) return
            jobs.remove(key)
            job.request.cancel()
            job.work.cancel(true)
            removeQueued(job.work)
        }
    }
}
