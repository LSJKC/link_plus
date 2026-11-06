package io.github.shaonialife.buicklinkplus.audio

/** 采集资源归服务端会话所有，旧请求不占用新会话路由 */
class AudioCaptureLease {
    var owner = 0L
        private set
    private var closedThrough = 0L

    /** 确认请求有效后再释放原采集资源 */
    @Synchronized fun canBegin(session: Long): Boolean = session > closedThrough && session > owner

    /**
     * 同一服务只接受单调递增且没有被取消的请求
     */
    @Synchronized fun begin(session: Long): Boolean {
        if (!canBegin(session)) return false
        owner = session
        return true
    }

    /** 先记录取消，仅当前所有者可释放采集资源 */
    @Synchronized fun stop(session: Long): Boolean {
        closedThrough = maxOf(closedThrough, session)
        if (session != owner || owner == 0L) return false
        owner = 0
        return true
    }
}
