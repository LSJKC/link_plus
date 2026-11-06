package io.github.shaonialife.buicklinkplus.media

/** 分别记录按键提交和播放器后续状态 */
internal object MediaFallbackPolicy {
    fun message(
        hasSession: Boolean,
        fresh: Boolean,
        expectedPlaying: Boolean?,
        playing: Boolean,
    ): String =
        when {
            !hasSession -> "未找到可控制的媒体会话，请先在手机打开播放器"
            !fresh -> "媒体键已提交，尚未取得新的播放状态"
            expectedPlaying != null && expectedPlaying == playing -> "已回读到${if (playing) "播放" else "暂停"}状态"
            else -> "媒体键已提交，请确认播放器是否响应"
        }
}
