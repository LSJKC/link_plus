package io.github.shaonialife.buicklinkplus.session

/** 取消会推进代次，旧网络或蓝牙回调不能释放新连接的互斥 */
internal class SessionAttemptGate {
    private var generation = 0L
    private var active: Long? = null

    @Synchronized fun get(): Boolean = active != null

    @Synchronized fun diagnostics(): String = "代次=$generation 建立中=${active != null}"

    @Synchronized fun begin(): Long? {
        if (active != null) return null
        return (++generation).also { active = it }
    }

    @Synchronized fun owns(request: Long): Boolean = request == generation

    @Synchronized fun finish(request: Long) {
        if (active == request) active = null
    }

    @Synchronized fun cancel() {
        generation++
        active = null
    }
}
