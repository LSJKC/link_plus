package io.github.shaonialife.buicklinkplus.media

/**
 * 根据播放器提供的位置更新时间外推，轮询采样不能重新开始计时
 */
internal object MediaPosition {
    /**
     * 未知位置保持未知，无效或未来时间锚只使用播放器的原始位置
     */
    fun at(
        positionMs: Long,
        updatedAtMs: Long,
        speed: Float,
        playing: Boolean,
        durationMs: Long,
        nowMs: Long,
    ): Long? {
        if (positionMs < 0) return null
        val elapsed = if (updatedAtMs in 1..nowMs) nowMs - updatedAtMs else 0L
        val delta = if (playing && speed.isFinite()) elapsed.toDouble() * speed else 0.0
        val position = (positionMs.toDouble() + delta).coerceAtLeast(0.0)
        return position.coerceAtMost(if (durationMs > 0) durationMs.toDouble() else Long.MAX_VALUE.toDouble()).toLong()
    }
}
