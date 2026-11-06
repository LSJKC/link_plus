package io.github.shaonialife.buicklinkplus.media

/** 串行合并天气请求，忽略旧结果 */
internal class WeatherRefreshState(
    private val intervalMs: Long = 30 * 60_000L,
) {
    data class Request(
        val choice: String,
        val generation: Long,
    )

    private var choice: String? = null
    private var generation = 0L
    private var running: Request? = null
    private var lastAttempt: Long? = null
    private var requested = false

    /**
     * 首次请求立即执行并合并在途期间的新选择
     */
    @Synchronized
    fun begin(
        value: String,
        now: Long,
        force: Boolean = false,
    ): Request? {
        if (choice != value) {
            choice = value
            generation++
            requested = true
        }
        if (force) requested = true
        if (running != null) return null
        if (!requested && lastAttempt?.let { now - it < intervalMs } == true) return null
        requested = false
        return Request(value, generation).also { running = it }
    }

    /** 只提交当前城市结果，失败后记录重试时间 */
    @Synchronized
    fun complete(
        request: Request,
        now: Long,
        publish: () -> Unit,
    ): Boolean {
        if (running !== request) return false
        running = null
        val current = request.generation == generation
        if (current) {
            lastAttempt = now
            publish()
        }
        return current
    }

    /**
     * 在原后台任务内继续处理最新的合并选择
     */
    @Synchronized
    fun next(now: Long): Request? = if (requested) choice?.let { begin(it, now) } else null
}
