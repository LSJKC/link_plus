package io.github.shaonialife.buicklinkplus.media.lyrics

import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.BuildConfig
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** 只使用公开歌词接口查询 */
internal class LrclibCatalog(
    private val open: (URL) -> HttpURLConnection,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val gate: LyricsRateGate = sharedGate,
    private val log: (String) -> Unit = { VirtualLauncher.logSink?.invoke(it) },
) : LyricsCatalog {
    constructor(internet: LyricsInternet) : this({ url -> internet.current().openConnection(url) as HttpURLConnection })

    /** 服务繁忙时预算足够才等待并重试一次，限流不重试 */
    override fun get(
        path: String,
        parameters: Map<String, String>,
        request: LyricsRequest,
    ): LyricsResponse {
        if (clock() < gate.retryNotBefore.get()) {
            logSafely("LYRICS: 来源=LRCLIB 阶段=退避中 HTTP=${gate.retryStatus.get()} 剩余=${gate.retryNotBefore.get() - clock()}ms")
            return LyricsResponse(gate.retryStatus.get().toInt())
        }
        val first = execute(path, parameters, request)
        if (first.status != 503) return first
        val wait = maxOf(gate.retryNotBefore.get(), gate.nextRequestAt.get()) - clock()
        if (wait >= request.remainingMs() - 1_500L || !request.recoverBusyOnce()) return first
        logSafely("LYRICS: 来源=LRCLIB 阶段=繁忙后恢复 等待=${wait.coerceAtLeast(0)}ms 本次最多恢复一次")
        if (wait > 0) Thread.sleep(wait)
        request.remainingMs()
        return execute(path, parameters, request)
    }

    /** 后台串行连接，错误日志不记录曲名或正文 */
    private fun execute(
        path: String,
        parameters: Map<String, String>,
        request: LyricsRequest,
    ): LyricsResponse {
        val spacing = gate.nextRequestAt.get() - clock()
        if (spacing > 0) Thread.sleep(minOf(spacing, request.remainingMs().toLong()))
        request.remainingMs()
        require(path == "/api/get" || path == "/api/search")
        val query =
            parameters.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
        request.remainingMs()
        val started = clock()
        var responseCode: Int? = null
        var connection: HttpURLConnection? = null
        try {
            connection = open(URL("https://lrclib.net$path?$query"))
            connection.instanceFollowRedirects = false
            connection.connectTimeout = minOf(3_000, request.remainingMs())
            connection.readTimeout = minOf(1_500, request.remainingMs())
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "User-Agent",
                "BuickLinkPlus/${BuildConfig.VERSION_NAME} (Android; based-on=https://github.com/shaonialife/cardeck)",
            )
            val status = connection.responseCode
            responseCode = status
            request.remainingMs()
            if (status == 429 || status == 503) {
                val delay = retryDelay(connection.getHeaderField("Retry-After"), System.currentTimeMillis())
                val jitter = kotlin.random.Random.nextLong(0, minOf(delay / 5, 5_000L) + 1)
                val now = clock()
                val wait = delay + jitter.coerceAtMost(Long.MAX_VALUE - delay)
                val until = now + wait.coerceAtMost(Long.MAX_VALUE - now)
                gate.retryNotBefore.updateAndGet { maxOf(it, until) }
                gate.retryStatus.set(status.toLong())
                logSafely("LYRICS: 来源=LRCLIB 阶段=服务退避 HTTP=$status 等待=${wait}ms")
            }
            if (status != 200) return LyricsResponse(status)
            if (connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.equals("application/json", true) != true
            ) {
                throw IOException("歌词响应类型不受支持")
            }
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw IOException("歌词响应超过大小限制")
            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    connection.readTimeout = minOf(1_500, request.remainingMs())
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) throw IOException("歌词响应超过大小限制")
                    bytes.write(buffer, 0, count)
                }
            }
            request.remainingMs()
            return decode(path, bytes.toString("UTF-8"))
        } catch (error: Exception) {
            logSafely("LYRICS: 来源=LRCLIB 阶段=请求失败 HTTP=${responseCode ?: "未收到响应"} 类型=${error.javaClass.simpleName}")
            throw error
        } finally {
            gate.nextRequestAt.set(clock() + 250)
            runCatching {
                log(
                    "LYRICS: 来源=LRCLIB 阶段=${if (path == "/api/get") "精确查询" else "目录搜索"} " +
                        "HTTP=${responseCode ?: "未收到响应"} 耗时=${clock() - started}ms",
                )
            }
            // 仅在后台执行，避免厂商网络关闭阻塞车机界面
            connection?.disconnect()
        }
    }

    /** 诊断失败不能影响歌词请求与资源释放 */
    private fun logSafely(message: String) {
        runCatching { log(message) }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1_024 * 1_024
        private val sharedGate = LyricsRateGate()

        /** 只读取身份和歌词字段，忽略未知字段 */
        internal fun decode(
            path: String,
            json: String,
        ): LyricsResponse {
            val records =
                if (path == "/api/get") {
                    listOf(JSONObject(json))
                } else {
                    val values = JSONArray(json)
                    (0 until minOf(values.length(), 20)).map { values.optJSONObject(it) ?: throw IOException("歌词记录类型不正确") }
                }
            return LyricsResponse(
                200,
                records.map { value ->
                    val title =
                        (value.opt("trackName") as? String)?.takeIf { it.isNotBlank() }
                            ?: throw IOException("歌词记录缺少有效曲名")
                    val artist =
                        (value.opt("artistName") as? String)?.takeIf { it.isNotBlank() }
                            ?: throw IOException("歌词记录缺少有效歌手")
                    val seconds = (value.opt("duration") as? Number)?.toDouble() ?: Double.NaN
                    if (!seconds.isFinite() || seconds < 0 || seconds > 86_400) throw IOException("歌词时长无效")
                    for (key in listOf("albumName", "syncedLyrics", "plainLyrics")) {
                        if (!value.isNull(key) && value.opt(key) !is String) throw IOException("歌词字段类型不正确")
                    }
                    if (!value.isNull("instrumental") && value.opt("instrumental") !is Boolean) throw IOException("纯音乐标志类型不正确")
                    LyricsRecord(
                        title,
                        artist,
                        value.optString("albumName"),
                        (seconds * 1_000).toLong(),
                        value.opt("syncedLyrics") as? String,
                        value.opt("plainLyrics") as? String,
                        value.optBoolean("instrumental"),
                    )
                },
            )
        }

        /** 解析秒数或 HTTP 日期，缺省等待 30 秒，本轮不重试 */
        internal fun retryDelay(
            value: String?,
            nowMs: Long,
        ): Long {
            value?.trim()?.toLongOrNull()?.let { seconds ->
                return seconds.coerceIn(1, Long.MAX_VALUE / 1_000) * 1_000
            }
            return runCatching {
                val until = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (until - nowMs).coerceAtLeast(1_000)
            }.getOrDefault(30_000)
        }
    }
}

/** 全部窗口共用限流时间，测试使用独立实例避免互相污染 */
internal class LyricsRateGate {
    val retryNotBefore = AtomicLong()
    val retryStatus = AtomicLong(429)
    val nextRequestAt = AtomicLong()
}
