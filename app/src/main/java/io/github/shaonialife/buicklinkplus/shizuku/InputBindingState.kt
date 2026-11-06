package io.github.shaonialife.buicklinkplus.shizuku

/** 输入服务绑定状态，慢回调不被高频触摸反复重启 */
internal class InputBindingState {
    enum class Phase { IDLE, BINDING, READY, FAILED }

    @Volatile var phase = Phase.IDLE
        private set
    private var generation = 0L
    private var attempts = 0
    private var startedAt = 0L

    /** 相同绑定最多等待十秒，每次明确重试最多两轮 */
    @Synchronized
    fun begin(now: Long): Long? {
        if (phase == Phase.READY || phase == Phase.FAILED) return null
        if (phase == Phase.BINDING && now - startedAt < 10_000L) return null
        if (attempts >= 2) {
            generation++
            phase = Phase.FAILED
            return null
        }
        attempts++
        startedAt = now
        phase = Phase.BINDING
        return ++generation
    }

    /** 只允许当前请求发布经过后台探活的服务 */
    @Synchronized
    fun owns(request: Long): Boolean = request == generation && phase == Phase.BINDING

    /** 失败等待本轮预算结束，避免紧密重试反复启动服务 */
    @Synchronized
    fun connected(request: Long): Boolean {
        if (!owns(request)) return false
        phase = Phase.READY
        return true
    }

    /** 主服务重启或当前输入服务死亡后重新开放有限绑定 */
    @Synchronized
    fun reset() {
        generation++
        attempts = 0
        phase = Phase.IDLE
    }

    /** 用户重新发起连接时才重置已耗尽的重试预算 */
    @Synchronized
    fun retryFailed() {
        if (phase == Phase.FAILED) reset()
    }
}
