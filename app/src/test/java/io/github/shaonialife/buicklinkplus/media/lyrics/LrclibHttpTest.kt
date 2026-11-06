package io.github.shaonialife.buicklinkplus.media.lyrics

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors

/** 通过本地 HTTP 服务检查编码、响应、超时和限流 */
class LrclibHttpTest {
    private val track = LyricsTrack(1, "com.tencent.qqmusic", "1", "歌曲", "歌手", "专辑", 180_000)
    private val synced =
        """
        {"trackName":"歌曲","artistName":"歌手","albumName":"专辑","duration":180,
        "syncedLyrics":"[00:01]歌词","plainLyrics":"歌词","instrumental":false}
        """.trimIndent()

    /** 检查 HTTP 参数、客户端标识和秒单位 */
    @Test fun exactContractUsesRealHttpAndJson() =
        withCatalog("exact") { catalog, requests ->
            val result = LyricsLookup(catalog).load(track, request())
            assertEquals(LyricsStatus.SYNCED, result.status)
            assertEquals("[00:01]歌词", result.rawLrc)
            assertEquals(1, requests.size)
            val query = URLDecoder.decode(requests.single().query, "UTF-8")
            assertTrue(query.contains("track_name=歌曲"))
            assertTrue(query.contains("duration=180.0"))
        }

    /** 404只补一次搜索，既有纯文本不因后续服务错误丢失 */
    @Test fun exactMissSearchesAndPlainResultSurvives() {
        withCatalog("search") { catalog, requests ->
            assertEquals(LyricsStatus.SYNCED, LyricsLookup(catalog).load(track, request()).status)
            assertEquals(listOf("/api/get", "/api/search"), requests.map { it.path })
        }
        withCatalog("plain") { catalog, requests ->
            assertEquals(LyricsStatus.PLAIN, LyricsLookup(catalog).load(track, request()).status)
            assertEquals(3, requests.size)
        }
    }

    /** 429不重试，503最多恢复一次，随后调用仍不能绕过共享退避 */
    @Test fun rateLimitAndOverloadBlockFurtherRequests() {
        for ((scenario, status) in listOf("limit" to 429, "overload" to 503)) {
            withCatalog(scenario) { catalog, requests ->
                assertEquals(status, catalog.get("/api/get", mapOf("track_name" to "歌曲", "artist_name" to "歌手"), request()).status)
                assertEquals(status, catalog.get("/api/get", emptyMap(), request()).status)
                assertEquals(if (status == 503) 2 else 1, requests.size)
            }
        }
    }

    /** 服务短暂繁忙后在同一后台预算内恢复，不要求用户反复关闭歌词 */
    @Test fun transientOverloadRecoversOnlyAfterRetryAfter() =
        withCatalog("recover") { catalog, requests ->
            assertEquals(LyricsStatus.SYNCED, LyricsLookup(catalog).load(track, request()).status)
            assertEquals(2, requests.size)
        }

    /** 退避不超过总预算，取消或额度耗尽后不重试 */
    @Test fun longRetryAndCancellationNeverAmplifyRequests() {
        withCatalog("long-limit") { catalog, requests ->
            assertEquals(LyricsFailure.SERVER_BUSY, LyricsLookup(catalog).load(track, request()).failure)
            assertEquals(1, requests.size)
        }
        val budget = request()
        assertTrue(budget.recoverBusyOnce())
        assertTrue(!budget.recoverBusyOnce())
        budget.cancel()
        assertTrue(runCatching { budget.remainingMs() }.isFailure)
    }

    /** 退出歌词后的取消信号在退避结束时生效，不再发起恢复连接 */
    @Test fun cancellationDuringBackoffStopsTheSecondConnection() {
        val waiting = java.util.concurrent.CountDownLatch(1)
        withCatalog("cancel-backoff", { if (it.contains("阶段=繁忙后恢复")) waiting.countDown() }) { catalog, requests ->
            val executor = Executors.newSingleThreadExecutor()
            val budget = request()
            try {
                val work =
                    executor.submit<Boolean> {
                        runCatching { LyricsLookup(catalog).load(track, budget) }.isFailure
                    }
                assertTrue(waiting.await(3, java.util.concurrent.TimeUnit.SECONDS))
                budget.cancel()
                assertTrue(work.get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, requests.size)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    /** 精确接口恢复后搜索仍繁忙时，整次查询不能再获得第二份重试额度 */
    @Test fun exactAndSearchShareOneRecoveryBudget() =
        withCatalog("recover-search") { catalog, requests ->
            assertEquals(LyricsFailure.SERVER_BUSY, LyricsLookup(catalog).load(track, request()).failure)
            assertEquals(listOf("/api/get", "/api/get", "/api/search"), requests.map { it.path })
        }

    /** 错误类型、损坏 JSON 和超大响应不缓存为无歌词 */
    @Test fun invalidPayloadsFailSafely() {
        for (scenario in listOf("html", "malformed", "huge")) {
            withCatalog(scenario) { catalog, requests ->
                assertTrue(runCatching { LyricsLookup(catalog).load(track, request()) }.isFailure)
                assertEquals(1, requests.size)
            }
        }
        for ((path, json) in listOf("/api/get" to "{}", "/api/search" to "[1]", "/api/search" to "[{}]")) {
            assertTrue(runCatching { LrclibCatalog.decode(path, json) }.isFailure)
        }
        assertTrue(LrclibCatalog.decode("/api/search", "[]").records.isEmpty())
    }

    /** 慢响应触发读取超时，并由共享查询发布降级结果 */
    @Test fun slowResponseFallsBackWithoutSearch() =
        withCatalog("slow") { catalog, requests ->
            val executor = Executors.newSingleThreadExecutor()
            val completed = java.util.concurrent.CountDownLatch(1)
            val results = mutableListOf<LyricsResult>()
            val shared = SharedLyricsQueries(executor, {}, ::elapsed) { value, budget -> LyricsLookup(catalog).load(value, budget) }
            try {
                val subscription =
                    shared.subscribe(track) {
                        results.add(it)
                        completed.countDown()
                    }
                assertTrue(completed.await(4, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(LyricsStatus.FAILED, results.single().status)
                assertEquals(LyricsFailure.TIMEOUT, results.single().failure)
                assertEquals(1, requests.size)
                subscription.cancel()
                assertEquals(0, shared.subscriberCount)
            } finally {
                executor.shutdownNow()
            }
        }

    /** Retry-After日期和秒数均不得缩短服务端要求的等待 */
    @Test fun retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(120_000L, LrclibCatalog.retryDelay("120", 0))
        assertEquals(120_000L, LrclibCatalog.retryDelay("Thu, 01 Jan 1970 00:02:00 GMT", 0))
        assertEquals(30_000L, LrclibCatalog.retryDelay("无效值", 0))
        assertEquals(Long.MAX_VALUE / 1_000 * 1_000, LrclibCatalog.retryDelay(Long.MAX_VALUE.toString(), 0))
    }

    /** 默认使用本地服务，也可通过环境变量指定 Postman 模拟服务 */
    private fun withCatalog(
        scenario: String,
        onLog: (String) -> Unit = {},
        action: (LrclibCatalog, MutableList<URL>) -> Unit,
    ) {
        val external = System.getenv("BUICK_LYRICS_SIMULATION_URL")
        var requestCount = 0
        val times = mutableListOf<Long>()
        val server = if (external.isNullOrBlank()) MockWebServer() else null
        server?.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestCount++
                    times.add(elapsed())
                    val path = request.path.orEmpty().substringBefore('?')
                    val status =
                        when (scenario) {
                            "search" -> if (path == "/api/get") 404 else 200
                            "plain" -> if (path == "/api/search") 503 else 200
                            "limit" -> 429
                            "overload" -> 503
                            "recover" -> if (requestCount == 1) 503 else 200
                            "long-limit" -> 503
                            "cancel-backoff" -> 503
                            "recover-search" -> if (path == "/api/search" || requestCount == 1) 503 else 404
                            else -> 200
                        }
                    val body =
                        when {
                            scenario == "html" || scenario == "limit" || scenario == "overload" -> "<html>暂不可用</html>"
                            scenario == "malformed" -> "损坏JSON"
                            status == 404 -> """{"name":"TrackNotFound","statusCode":404}"""
                            scenario == "plain" -> synced.replace("\"[00:01]歌词\"", "null")
                            path == "/api/search" -> "[$synced]"
                            else -> synced
                        }
                    return MockResponse().setResponseCode(status).setBody(body).apply {
                        setHeader("Content-Type", if (scenario == "html") "text/html" else "application/json")
                        if (status == 429 || status == 503) setHeader("Retry-After", if (scenario == "long-limit") "60" else "1")
                        if (scenario == "slow") setBodyDelay(1_800, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (scenario == "huge") setHeader("Content-Length", 2L * 1_024 * 1_024 + 1)
                    }
                }
            }
        server?.start()
        val base = external ?: server!!.url("/").toString().trimEnd('/')
        val requests = java.util.concurrent.CopyOnWriteArrayList<URL>()
        val catalog =
            LrclibCatalog(
                open = { original ->
                    requests.add(original)
                    (URL(base + original.file).openConnection() as HttpURLConnection).apply {
                        setRequestProperty("X-Lyrics-Test-Case", scenario)
                    }
                },
                clock = ::elapsed,
                gate = LyricsRateGate(),
                log = onLog,
            )
        try {
            action(catalog, requests)
            if (scenario == "recover" && server != null) assertTrue(times[1] - times[0] >= 1_000)
        } finally {
            server?.shutdown()
        }
    }

    /** 测试使用单调时钟，不调用 Android 空桩 */
    private fun elapsed(): Long = System.nanoTime() / 1_000_000

    /** 每次查询保留与生产相同的 12 秒预算 */
    private fun request(): LyricsRequest = LyricsRequest(::elapsed)
}
