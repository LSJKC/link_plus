package io.github.shaonialife.buicklinkplus.media.lyrics

import kotlin.math.ceil

/** 只安排下一句的刷新，避免高频轮询整卡 */
internal object LyricsTimeline {
    /** 暂停、未知位置和无效速度不创建定时任务 */
    fun nextDelay(
        document: LyricsDocument,
        positionMs: Long?,
        speed: Float,
        playing: Boolean,
    ): Long? {
        if (!playing || positionMs == null || !speed.isFinite() || speed <= 0f) return null
        val next = document.lines.getOrNull(document.lineAt(positionMs) + 1) ?: return null
        return ceil((next.atMs - positionMs).toDouble() / speed).toLong().coerceIn(16L, 86_400_000L)
    }
}
