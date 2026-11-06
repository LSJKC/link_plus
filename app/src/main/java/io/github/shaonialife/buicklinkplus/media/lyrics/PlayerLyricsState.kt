package io.github.shaonialife.buicklinkplus.media.lyrics

/** 通知歌词与当前媒体会话及曲目共同绑定，切歌后不复用旧文本 */
internal data class PlayerLyricsIdentity(
    val session: Long,
    val packageName: String,
    val mediaId: String?,
    val title: String,
    val artist: String,
    val album: String,
)

/** 只保留当前曲目的共享内容，不积累通知或其他应用的歌词 */
internal data class SharedPlayerLyrics(
    val text: String,
    val live: Boolean,
    val notificationKey: String,
    val postedAtMs: Long,
    val receivedAtMs: Long,
)

/** 接收播放器共享的歌词 */
internal class PlayerLyricsState {
    private var identity: PlayerLyricsIdentity? = null
    private var selectedAtMs = 0L
    private var full: SharedPlayerLyrics? = null
    private var line: SharedPlayerLyrics? = null
    private var lastPostedAtMs = Long.MIN_VALUE

    /** 全文与实时句分开保留，后续实时通知不会丢掉已收到的完整时间轴 */
    val fullText: SharedPlayerLyrics? get() = full

    /** 元数据补齐后重新绑定，避免显示上一首歌词 */
    fun select(
        value: PlayerLyricsIdentity?,
        wallTimeMs: Long,
    ): Boolean {
        if (identity == value) return false
        identity = value
        selectedAtMs = wallTimeMs
        full = null
        line = null
        lastPostedAtMs = Long.MIN_VALUE
        return true
    }

    /** 只接收本次选曲之后发布的明确歌词，普通通知内容不能调用此入口 */
    fun accept(
        packageName: String,
        notificationKey: String,
        postedAtMs: Long,
        receivedAtMs: Long,
        text: String,
        live: Boolean,
        titleHint: String? = null,
    ): Boolean {
        val target = identity ?: return false
        if (target.packageName != packageName || (target.title.isBlank() && target.mediaId.isNullOrBlank())) return false
        val titleMatches = !titleHint.isNullOrBlank() && LyricsTrack.normalize(titleHint) == LyricsTrack.normalize(target.title)
        if (!titleHint.isNullOrBlank() && !titleMatches) return false
        // 全文与当前歌名明确一致时，允许通知略早于本次元数据回调，避免丢掉唯一一份全文
        val justPublishedFullText = !live && titleMatches && selectedAtMs - postedAtMs in 0L..2_000L
        if (postedAtMs < selectedAtMs && !justPublishedFullText) return false
        if (postedAtMs < lastPostedAtMs) return false
        if (!PlayerLyricsPolicy.validText(text, live)) return false
        val value = SharedPlayerLyrics(text.trim(), live, notificationKey, postedAtMs, receivedAtMs)
        if (live) line = value else full = value
        lastPostedAtMs = postedAtMs
        return true
    }

    /** 实时通道长时间停更时清除旧句，暂停时保留当前句而不伪造下一句 */
    fun current(
        elapsedMs: Long,
        playing: Boolean,
    ): SharedPlayerLyrics? {
        val value = line
        if (value != null && playing && elapsedMs - value.receivedAtMs >= 20_000) {
            line = null
        }
        return line ?: full
    }

    /** 同通知编号的旧移除回调不能清掉后来更新的内容 */
    fun remove(
        notificationKey: String,
        postedAtMs: Long,
    ): Boolean {
        var removed = false
        full?.let { value ->
            if (value.notificationKey == notificationKey && postedAtMs >= value.postedAtMs) {
                full = null
                removed = true
            }
        }
        line?.let { value ->
            if (value.notificationKey == notificationKey && postedAtMs >= value.postedAtMs) {
                line = null
                removed = true
            }
        }
        return removed
    }

    /** 服务退役后同时撤销曲目身份和最后一份歌词 */
    fun clear() {
        identity = null
        selectedAtMs = 0L
        full = null
        line = null
        lastPostedAtMs = Long.MIN_VALUE
    }
}

/** 识别魅族歌词通知标志，依据其开源实现 */
internal object PlayerLyricsPolicy {
    private const val ALWAYS_SHOW_LYRIC_TICKER = 0x01000000
    private const val ONLY_UPDATE_LYRIC_TICKER = 0x02000000
    private val players =
        setOf(
            "com.tencent.qqmusic",
            "com.netease.cloudmusic",
            "com.kugou.android",
            "cn.kuwo.player",
            "com.tencent.qqmusiccar",
            "com.netease.cloudmusic.iot",
            "com.kugou.android.auto",
        )

    /** 白名单只限制歌词，不影响其他播放器原有的媒体控制 */
    fun supports(packageName: String): Boolean = packageName in players

    /** 匹配歌词标志和播放器后才读取 ticker */
    fun isLyricTicker(
        packageName: String,
        flags: Int,
    ): Boolean = packageName in players && flags and (ALWAYS_SHOW_LYRIC_TICKER or ONLY_UPDATE_LYRIC_TICKER) != 0

    /** 从媒体通知正文识别歌词，排除与歌手、专辑或标题重复的文字 */
    fun isLyricBody(
        packageName: String,
        body: String?,
        notificationTitle: String?,
        targetTitle: String?,
        targetArtist: String?,
    ): Boolean {
        if (packageName !in players || body == null) return false
        if (!validText(body, live = true)) return false
        val normalized = LyricsTrack.normalize(body)
        if (normalized.isBlank()) return false
        // 播放器通知的 UI 状态文案不是歌词
        if (normalized in uiNoise) return false
        if (normalized == LyricsTrack.normalize(notificationTitle.orEmpty())) return false
        if (normalized == LyricsTrack.normalize(targetTitle.orEmpty())) return false
        if (normalized == LyricsTrack.normalize(targetArtist.orEmpty())) return false
        // 「歌手 - 歌名」式组合简介同时包含两者，不是逐行歌词
        val containsTitle = targetTitle?.let { normalized.contains(LyricsTrack.normalize(it)) } == true
        val containsArtist = targetArtist?.let { normalized.contains(LyricsTrack.normalize(it)) } == true
        return !(containsTitle && containsArtist)
    }

    /** 通知里常见的状态/操作文案，按规范化形态排除 */
    private val uiNoise =
        setOf(
            LyricsTrack.normalize("正在播放"),
            LyricsTrack.normalize("已暂停"),
            LyricsTrack.normalize("播放中"),
            LyricsTrack.normalize("暂停中"),
            LyricsTrack.normalize("歌词"),
            LyricsTrack.normalize("歌词加载中"),
            LyricsTrack.normalize("暂无歌词"),
            LyricsTrack.normalize("加载中"),
        )

    /** 限制实时句长度，过滤网址和未知结构数据 */
    fun validText(
        text: String,
        live: Boolean,
    ): Boolean {
        val limit = if (live) 4_096 else LyricsParser.MAX_BYTES
        if (text.length > limit || text.toByteArray(Charsets.UTF_8).size > limit) return false
        val trimmed = text.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("http://") &&
            !trimmed.startsWith("https://") &&
            !trimmed.startsWith('<') &&
            !trimmed.startsWith('{')
    }
}
