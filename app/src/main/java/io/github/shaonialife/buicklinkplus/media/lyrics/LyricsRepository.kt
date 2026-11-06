package io.github.shaonialife.buicklinkplus.media.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 窗口独立持有展示代次，联网查询与缓存由进程内共用后台执行器负责 */
internal class LyricsRepository(
    context: Context,
    private val onResult: (LyricsResult) -> Unit,
) {
    private val queries = shared(context)
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var nativeGeneration = 0L
    private var current: LyricsTrack? = null
    private var nativeRaw: String? = null
    private var nativeResult: LyricsResult? = null
    private var networkResult = LyricsResult(LyricsStatus.LOADING)
    private var lastResult: LyricsResult? = null
    private var subscription: LyricsSubscription? = null
    private var parseTask: FutureTask<Unit>? = null
    private var waiting = false
    private val timeout =
        Runnable {
            if (waiting) {
                waiting = false
                subscription?.cancel()
                subscription = null
                networkResult = LyricsResult(LyricsStatus.FAILED, failure = LyricsFailure.TIMEOUT)
                publish()
            }
        }

    /** 只有曲目身份变化才联网，播放器补发全文只更新原生解析 */
    fun select(track: LyricsTrack) {
        val identity = track.copy(embedded = null)
        if (current != identity) {
            clear()
            current = identity
            if (!PlayerLyricsPolicy.supports(track.packageName)) {
                networkResult = LyricsResult(LyricsStatus.UNAVAILABLE)
                publish()
                return
            }
            networkResult = LyricsResult(if (track.canSearch) LyricsStatus.LOADING else LyricsStatus.INSUFFICIENT_INFO)
            val ticket = generation
            if (track.canSearch) {
                waiting = true
                handler.postDelayed({ startQuery(identity, ticket) }, 250)
                handler.postDelayed(timeout, 12_000)
            }
        }
        if (!PlayerLyricsPolicy.supports(track.packageName)) return
        if (nativeRaw != track.embedded) parsePlayer(track.embedded)
        publish()
    }

    /** 后台结果回到主线程后再次确认窗口归属，缓存命中也走相同路径 */
    private fun startQuery(
        track: LyricsTrack,
        ticket: Long,
    ) {
        if (generation != ticket || !waiting) return
        subscription =
            queries.subscribe(track) { result ->
                handler.post {
                    if (generation == ticket && waiting) {
                        waiting = false
                        handler.removeCallbacks(timeout)
                        networkResult = result
                        publish()
                    }
                }
            }
    }

    /** 解析与网络使用独立线程，实时通知不重复解析全文 */
    private fun parsePlayer(raw: String?) {
        nativeRaw = raw
        nativeResult = null
        val nativeTicket = ++nativeGeneration
        val ticket = generation
        cancelParsing()
        if (raw.isNullOrBlank()) return
        val work =
            FutureTask<Unit> {
                val result =
                    runCatching { LyricsParser.parse(raw) }
                        .getOrNull()
                        ?.let { document -> LyricsResult.from(document, "播放器", raw.takeIf { document.lines.isNotEmpty() }) }
                handler.post {
                    if (generation == ticket && nativeGeneration == nativeTicket) {
                        parseTask = null
                        nativeResult = result
                        publish()
                    }
                }
                Unit
            }
        parseTask = work
        runCatching { parser.execute(work) }.onFailure { cancelParsing() }
    }

    /** 同步网络歌词优先，网络没有时间轴时保留播放器实际共享的同步歌词 */
    private fun publish() {
        val track = current ?: return
        val value =
            when {
                networkResult.status == LyricsStatus.SYNCED || networkResult.status == LyricsStatus.INSTRUMENTAL -> networkResult
                nativeResult?.status == LyricsStatus.SYNCED -> nativeResult!!
                networkResult.status == LyricsStatus.PLAIN -> networkResult
                nativeResult != null -> nativeResult!!
                else -> networkResult
            }.copy(
                trackId = track.mediaId ?: "${track.title}\n${track.artist}\n${track.album}\n${track.durationMs}",
                sessionGeneration = track.session,
            )
        if (value != lastResult) {
            lastResult = value
            onResult(value)
        }
    }

    /** 隐藏或切歌立即撤销所有权，最后一个窗口退出后共用查询也会取消 */
    fun clear() {
        generation++
        nativeGeneration++
        handler.removeCallbacksAndMessages(null)
        subscription?.cancel()
        subscription = null
        waiting = false
        cancelParsing()
        current = null
        nativeRaw = null
        nativeResult = null
        lastResult = null
    }

    /** 取消只移除队列，不等待正在解析的后台线程 */
    private fun cancelParsing() {
        parseTask?.let {
            parser.remove(it)
            it.cancel(true)
        }
        parseTask = null
    }

    companion object {
        private var sharedQueries: SharedLyricsQueries? = null
        private val network = executor("lyrics-network", 2)
        private val parser = executor("lyrics-parser", 4)

        /** 执行器允许空闲退出，队列满时只降级当前歌词，不占用界面线程 */
        private fun executor(
            name: String,
            queueSize: Int,
        ): ThreadPoolExecutor =
            ThreadPoolExecutor(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                ArrayBlockingQueue(queueSize),
                { action -> Thread(action, name).apply { isDaemon = true } },
            ).apply { allowCoreThreadTimeOut(true) }

        /** 只持有应用上下文，缓存目录的创建与访问均延迟到后台查询 */
        @Synchronized
        private fun shared(context: Context): SharedLyricsQueries =
            sharedQueries ?: run {
                val application = context.applicationContext
                val lookup = LyricsLookup(LrclibCatalog(LyricsInternet(application)))
                var cache: LyricsCache? = null
                SharedLyricsQueries(network, { network.remove(it) }, SystemClock::elapsedRealtime) { track, request ->
                    val local = cache ?: LyricsCache(File(application.filesDir, "lyrics-v2")).also { cache = it }
                    val key = track.cacheKey()
                    request.remainingMs()
                    local.get(key) ?: lookup.load(track, request).also {
                        request.remainingMs()
                        local.put(key, it)
                    }
                }.also { sharedQueries = it }
            }
    }
}
