package io.github.shaonialife.buicklinkplus.media

/** 控制后检查一次播放状态，不自动重发或抢占焦点 */
internal class MediaControlReadback<T> {
    enum class Result { PLAYING, PAUSED, PLAYED_THEN_PAUSED, UNKNOWN }

    var generation = 0L
        private set
    private var target: T? = null
    private var played = false

    /** 新控制使所有旧回读失效，即便来自同一播放器 */
    fun begin(token: T?): Long {
        target = token
        played = false
        return ++generation
    }

    /** 回调和延迟读取都检查 Token 与代次 */
    fun observe(
        request: Long,
        token: T?,
        playing: Boolean?,
    ): Result? {
        if (request != generation || target == null || target != token) return null
        if (playing == true) played = true
        return when (playing) {
            true -> Result.PLAYING
            false -> if (played) Result.PLAYED_THEN_PAUSED else Result.PAUSED
            null -> Result.UNKNOWN
        }
    }
}
