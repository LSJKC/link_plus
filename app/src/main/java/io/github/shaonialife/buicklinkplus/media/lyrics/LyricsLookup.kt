package io.github.shaonialife.buicklinkplus.media.lyrics

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicBoolean

/** 服务端记录先转成受限字段，再参与录音版本匹配 */
internal data class LyricsRecord(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val synced: String?,
    val plain: String?,
    val instrumental: Boolean = false,
)

/** HTTP 错误只保留状态码，不展示或记录响应正文 */
internal data class LyricsResponse(
    val status: Int,
    val records: List<LyricsRecord> = emptyList(),
)

/** 请求预算使用经过时间，取消信号允许跨线程读取 */
internal class LyricsRequest(
    private val clock: () -> Long,
) {
    private val cancelled = AtomicBoolean()
    private val recoveredBusy = AtomicBoolean()
    private val deadline = clock() + 12_000L

    /** 取消只置位，不在界面线程同步关闭网络资源 */
    fun cancel() {
        cancelled.set(true)
    }

    /** 精确与搜索共用一次繁忙恢复额度，避免两阶段分别放大请求 */
    fun recoverBusyOnce(): Boolean = recoveredBusy.compareAndSet(false, true)

    /** 所有连接、读取与退避共用一个截止时间 */
    fun remainingMs(): Int {
        val remaining = deadline - clock()
        if (cancelled.get() || remaining <= 0 || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("歌词请求已取消或超时")
        }
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** 可注入查询实现，单元测试无需联网 */
internal fun interface LyricsCatalog {
    fun get(
        path: String,
        parameters: Map<String, String>,
        request: LyricsRequest,
    ): LyricsResponse
}

/** 每次最多精确查询和搜索各一次，避免叠加重试 */
internal class LyricsLookup(
    private val catalog: LyricsCatalog,
) {
    /** 只有明确未找到或没有可靠内容才搜索，限流和服务故障立即结束本次请求 */
    fun load(
        track: LyricsTrack,
        request: LyricsRequest,
    ): LyricsResult {
        if (!track.canSearch) return LyricsResult(LyricsStatus.INSUFFICIENT_INFO)
        val parameters = linkedMapOf("track_name" to track.title, "artist_name" to track.artist)
        val exact = LinkedHashMap(parameters)
        if (track.album.isNotBlank()) exact["album_name"] = track.album
        if (track.durationMs in 1_000L..3_600_000L) exact["duration"] = (track.durationMs / 1_000.0).toString()
        request.remainingMs()
        val response = catalog.get("/api/get", exact, request)
        if (response.status != 200 && response.status != 404) return failed(response.status)
        val first = if (response.status == 200) matchedResult(track, response.records) else null
        if (first?.status == LyricsStatus.SYNCED || first?.status == LyricsStatus.INSTRUMENTAL) return first
        request.remainingMs()
        val searched = runCatching { catalog.get("/api/search", parameters, request) }.getOrElse { return first ?: throw it }
        if (searched.status != 200) {
            return first ?: if (searched.status == 404) LyricsResult(LyricsStatus.NOT_FOUND) else failed(searched.status)
        }
        return matchedResult(track, searched.records) ?: first ?: LyricsResult(LyricsStatus.NOT_FOUND)
    }

    companion object {
        /** 分别保留限流、服务繁忙和无匹配结果 */
        private fun failed(status: Int): LyricsResult =
            LyricsResult(
                LyricsStatus.FAILED,
                failure =
                    when (status) {
                        429 -> LyricsFailure.RATE_LIMITED
                        503 -> LyricsFailure.SERVER_BUSY
                        else -> LyricsFailure.CONNECTION
                    },
            )

        /** 歌名、歌手及已知专辑规范化匹配，已知时长允许两秒误差 */
        fun match(
            track: LyricsTrack,
            records: List<LyricsRecord>,
        ): LyricsDocument? = matchedResult(track, records)?.document

        /** 保留匹配结果中的原始 LRC，纯文本单独展示 */
        private fun matchedResult(
            track: LyricsTrack,
            records: List<LyricsRecord>,
        ): LyricsResult? {
            if (!track.canSearch) return null
            val candidates =
                records.filter {
                    LyricsTrack.normalize(it.title) == LyricsTrack.normalize(track.title) &&
                        LyricsTrack.normalize(it.artist) == LyricsTrack.normalize(track.artist) &&
                        (track.album.isBlank() || LyricsTrack.normalize(it.album) == LyricsTrack.normalize(track.album)) &&
                        (track.durationMs <= 0 || kotlin.math.abs(it.durationMs - track.durationMs) <= 2_000)
                }
            val record =
                when {
                    candidates.size == 1 -> candidates.single()
                    // 多条无法区分的候选不能因为排序或有歌词就被自动选中
                    candidates.size > 1 -> return null
                    else -> {
                        // 翻唱署名/专辑与原唱不同：严格匹配落空时，歌名唯一且时长吻合
                        // 才认同名歌词；同名多曲仍拒绝
                        val titleOnly =
                            records.filter {
                                LyricsTrack.normalize(it.title) == LyricsTrack.normalize(track.title) &&
                                    (track.durationMs <= 0 || kotlin.math.abs(it.durationMs - track.durationMs) <= 2_000)
                            }
                        if (titleOnly.size != 1) return null
                        titleOnly.single()
                    }
                }
            if (record.instrumental) return LyricsResult.from(LyricsDocument(instrumental = true), "LRCLIB")
            val synced = LyricsParser.parse(record.synced)?.takeIf { it.lines.isNotEmpty() }
            val document =
                synced ?: LyricsParser.parse(record.plain) ?: run {
                    if (!record.synced.isNullOrBlank() || !record.plain.isNullOrBlank()) throw IOException("歌词载荷不受支持")
                    return null
                }
            return LyricsResult.from(document, "LRCLIB", record.synced.takeIf { synced != null })
        }
    }
}
