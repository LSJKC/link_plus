package io.github.shaonialife.buicklinkplus.media.lyrics

/** 每个窗口独立持有解析代次，不保留其他窗口或旧会话的数据 */
internal class LyricsRequestState {
    private var generation = 0L
    private var current: LyricsTrack? = null

    /** 曲目、会话或共享内容变化才重新解析 */
    fun select(track: LyricsTrack): Long? {
        if (current == track) return null
        current = track
        return ++generation
    }

    /** 退出预览、断开或销毁时撤销当前展示所有权 */
    fun clear() {
        current = null
        generation++
    }

    /** 已完成的解析只发布一次，同曲轮询不隐式重试 */
    fun finish(
        ticket: Long,
        track: LyricsTrack,
    ): Boolean {
        if (!owns(ticket, track)) return false
        generation++
        return true
    }

    /** 歌曲再次相同时，旧代次仍不能提交 */
    fun owns(
        ticket: Long,
        track: LyricsTrack,
    ): Boolean = ticket == generation && current == track
}
