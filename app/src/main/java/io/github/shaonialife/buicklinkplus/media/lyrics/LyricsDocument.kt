package io.github.shaonialife.buicklinkplus.media.lyrics

import java.text.Normalizer
import java.util.Locale

/** 曲目身份与播放器共享内容分开参与联网查询和原生解析 */
internal data class LyricsTrack(
    val session: Long,
    val packageName: String,
    val mediaId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val embedded: String? = null,
) {
    val canSearch: Boolean
        get() = title.isNotBlank() && artist.isNotBlank() && title.length <= 512 && artist.length <= 512 && album.length <= 1_024

    /** 共享查询只按录音身份合并，不依赖窗口或实时歌词 */
    fun recording(): LyricsTrack = copy(session = 0, packageName = "", mediaId = null, embedded = null)

    /** 只有后台缓存访问才计算规范化录音键 */
    fun cacheKey(): String = listOf(title, artist, album).joinToString("\n", transform = ::normalize) + "\n$durationMs"

    companion object {
        /** 只规范字符宽度、空白和大小写，不抹去现场或翻唱等版本标记 */
        fun normalize(value: String): String =
            Normalizer
                .normalize(value, Normalizer.Form.NFKC)
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
    }
}

/** 同一时间的原文与译文合并显示，时间单位为毫秒 */
internal data class LyricsLine(
    val atMs: Long,
    val text: String,
)

/** 已解析的纯文本或逐行歌词，不包含任何播放控制状态 */
internal data class LyricsDocument(
    val lines: List<LyricsLine> = emptyList(),
    val plainText: String = "",
    val instrumental: Boolean = false,
    val plainRows: List<String> = emptyList(),
) {
    /** 按播放位置找当前行，首句前返回 -1 */
    fun lineAt(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lines[middle].atMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        return high
    }

    companion object {
        /** 后台拆分长段落，单行测量有界且不拆开表情的代理对 */
        fun plain(text: String): LyricsDocument? {
            val rows = mutableListOf<String>()
            for (line in text.lineSequence()) {
                if (line.isEmpty()) rows.add("")
                var start = 0
                while (start < line.length) {
                    var end = minOf(start + 512, line.length)
                    if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
                    rows.add(line.substring(start, end))
                    if (rows.size > 2_000) return null
                    start = end
                }
                if (rows.size > 2_000) return null
            }
            return LyricsDocument(plainText = text, plainRows = rows)
        }
    }
}

/** 歌词状态与媒体播放状态相互独立 */
internal enum class LyricsStatus(
    val label: String,
) {
    LOADING("加载中"),
    SYNCED("同步歌词"),
    LIVE("播放器实时歌词"),
    PLAIN("纯文本歌词"),
    INSTRUMENTAL("纯音乐"),
    WAITING_FOR_PLAYER("等待播放器共享歌词"),
    INSUFFICIENT_INFO("曲目信息不足"),
    FAILED("歌词查询或解析失败"),
    NOT_FOUND("未找到匹配歌词"),
    UNAVAILABLE("歌词暂不可用"),
}

/** 结果只提供本次展示所需信息，不暴露原始响应 */
internal data class LyricsResult(
    val status: LyricsStatus,
    val document: LyricsDocument? = null,
    val source: String = "",
    val rawLrc: String? = null,
    val trackId: String? = null,
    val sessionGeneration: Long = 0L,
    val failure: LyricsFailure? = null,
) {
    companion object {
        /** 统一从有效歌词内容判断展示类型 */
        fun from(
            document: LyricsDocument,
            source: String,
            rawLrc: String? = null,
        ): LyricsResult =
            LyricsResult(
                when {
                    document.instrumental -> LyricsStatus.INSTRUMENTAL
                    document.lines.isNotEmpty() -> LyricsStatus.SYNCED
                    else -> LyricsStatus.PLAIN
                },
                document,
                source,
                rawLrc,
            )
    }
}

/** 失败原因只用于诊断与提示，不作为无歌词负缓存 */
internal enum class LyricsFailure {
    NO_INTERNET,
    SERVER_BUSY,
    RATE_LIMITED,
    TIMEOUT,
    CONNECTION,
    INVALID_RESPONSE,
    QUEUE_FULL,
}

/** 有界解析公开提供的歌词文本，兼容常见逐行时间标签 */
internal object LyricsParser {
    const val MAX_BYTES = 256 * 1_024
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val offsetTag = Regex("""(?im)^\s*\[offset:([+-]?\d+)\]\s*$""")
    private val metadataTag = Regex("""^\s*\[(?:ar|al|ti|by|re|ve|length|offset):.*\]\s*$""", RegexOption.IGNORE_CASE)

    /** 正偏移让歌词提前，空行保留为间奏，拒绝损坏或超大内容 */
    fun parse(raw: String?): LyricsDocument? {
        if (raw.isNullOrBlank() || raw.length > MAX_BYTES || raw.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val text = raw.trimStart(0xFEFF.toChar()).trim()
        if (text.startsWith("https://") || text.startsWith("http://") || text.startsWith('<') || text.startsWith('{')) return null
        val offset =
            offsetTag
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
                ?.coerceIn(-86_400_000L, 86_400_000L) ?: 0L
        val timed = mutableListOf<LyricsLine>()
        val plain = mutableListOf<String>()
        var expandedBytes = 0L
        for (line in text.lineSequence().take(2_000)) {
            if (metadataTag.matches(line)) continue
            val tags = timestamp.findAll(line).take(2_001).toList()
            if (tags.size + timed.size > 2_000) return null
            if (tags.isEmpty()) {
                if (!line.trimStart().startsWith('[')) plain.add(line.trimEnd())
                continue
            }
            val content = line.substring(tags.last().range.last + 1).trim()
            expandedBytes += content.toByteArray(Charsets.UTF_8).size.toLong() * tags.size
            if (expandedBytes > MAX_BYTES) return null
            for (tag in tags) {
                val seconds = tag.groupValues[2].toInt()
                if (seconds > 59) continue
                val fraction =
                    tag.groupValues[3]
                        .padEnd(3, '0')
                        .take(3)
                        .toIntOrNull() ?: 0
                val at = tag.groupValues[1].toLong() * 60_000 + seconds * 1_000 + fraction - offset
                timed.add(LyricsLine(at.coerceAtLeast(0), content))
            }
        }
        if (timed.any { it.text.isNotBlank() }) {
            val lines =
                timed.groupBy { it.atMs }.toSortedMap().map { (at, values) ->
                    LyricsLine(
                        at,
                        values
                            .map { it.text }
                            .filter { it.isNotBlank() }
                            .distinct()
                            .joinToString("\n"),
                    )
                }
            return LyricsDocument(lines = lines)
        }
        return plain
            .joinToString("\n")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(LyricsDocument::plain)
    }

    /** 只读明确的歌词文本字段 */
    fun isLyricsKey(key: String): Boolean =
        key.substringAfterLast('.').substringAfterLast(':').lowercase(Locale.ROOT) in
            setOf("lyrics", "lyric", "lrc", "syncedlyrics", "synced_lyrics", "unsyncedlyrics", "unsynced_lyrics")
}
