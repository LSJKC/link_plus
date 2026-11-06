package io.github.shaonialife.buicklinkplus.media.lyrics

import android.app.Notification
import android.media.session.MediaSession
import android.os.Build
import android.os.Bundle

/** 解析后的原生歌词通知，只包含歌词字段 */
internal data class PlayerLyricsNotificationValue(
    val text: String,
    val live: Boolean,
    val titleHint: String?,
)

/** 按明确协议解析歌词，不从普通通知猜测 */
internal object PlayerLyricsNotification {
    private val unsafeKey = Regex("[^A-Za-z0-9_.:-]")

    /** 仅记录字段名和专用标志，不记录通知正文或歌词内容 */
    fun schema(notification: Notification): String =
        runCatching {
            val extras = notification.extras
            val keys =
                extras
                    ?.keySet()
                    ?.take(128)
                    .orEmpty()
                    .sorted()
                    .take(24)
            "flags=${notification.flags.toString(16)} ticker=${notification.tickerText != null} " +
                "字段=${keys.joinToString(",") { unsafeKey.replace(it.take(64), "?") }}"
        }.getOrDefault("通知结构无法读取")

    /** 通知包名由系统提供，还需匹配当前播放器 */
    fun read(
        packageName: String,
        notification: Notification,
        targetToken: MediaSession.Token,
        targetTitle: String?,
        targetArtist: String?,
    ): PlayerLyricsNotificationValue? =
        runCatching {
            if (!PlayerLyricsPolicy.supports(packageName)) return@runCatching null
            val extras = notification.extras
            val token = notificationToken(extras)
            if (extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true && token != targetToken) return@runCatching null
            val ticker = PlayerLyricsPolicy.isLyricTicker(packageName, notification.flags)
            val fullText = explicitLyrics(extras)
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val sameTitle = !title.isNullOrBlank() && LyricsTrack.normalize(title) == LyricsTrack.normalize(targetTitle.orEmpty())
            // 实时句候选：仅常驻/前台媒体通知（ONGOING/FOREGROUND 标志）的正文与
            // ticker（酷狗/网易云/酷我把逐行歌词放在这里）；普通通知正文永不当歌词，
            // 候选再经 isLyricBody 排除元数据与 UI 噪词
            val ongoing =
                (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE)) != 0
            val body =
                if (fullText == null && !ticker && ongoing) {
                    listOfNotNull(
                        extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim(),
                        notification.tickerText?.toString()?.trim(),
                    ).firstOrNull { PlayerLyricsPolicy.isLyricBody(packageName, it, title, targetTitle, targetArtist) }
                } else {
                    null
                }
            val bodyIsLive = body != null
            if (!ticker && fullText == null && !bodyIsLive) return@runCatching null
            if (!ticker && !bodyIsLive && token == null && !sameTitle) return@runCatching null
            // 专用实时通知的标题可能是应用名称，只有全文使用歌曲标题作为额外校验
            if (fullText != null && !title.isNullOrBlank() && !sameTitle) return@runCatching null
            // 取词顺序：明确全文 > ticker 实时句 > 通知正文实时句（ticker 空白时补位）
            val tickerText = notification.tickerText?.toString()
            val text = fullText ?: if (ticker && !tickerText.isNullOrBlank()) tickerText else body.orEmpty()
            val live = fullText == null
            if (text.isNotBlank() && !PlayerLyricsPolicy.validText(text, live)) return@runCatching null
            PlayerLyricsNotificationValue(text, live, if (sameTitle) title else null)
        }.getOrNull()

    /** 用媒体 Token 匹配通知归属，拒绝错误类型 */
    @Suppress("DEPRECATION")
    private fun notificationToken(extras: Bundle?): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= 33) {
            extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            extras?.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

    /** 读取明确的歌词扩展字段 */
    private fun explicitLyrics(extras: Bundle?): String? =
        extras
            ?.keySet()
            ?.take(128)
            ?.filter(LyricsParser::isLyricsKey)
            ?.sortedBy {
                val name = it.substringAfterLast('.').substringAfterLast(':').lowercase(java.util.Locale.ROOT)
                if (name.startsWith("synced") || name == "lrc") 0 else 1
            }?.firstNotNullOfOrNull { key ->
                runCatching { extras.getCharSequence(key) }
                    .getOrNull()
                    ?.toString()
                    ?.takeIf { PlayerLyricsPolicy.validText(it, false) }
            }
}
