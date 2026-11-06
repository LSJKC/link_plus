package io.github.shaonialife.buicklinkplus.render

/** 可见地图连续十秒没有新输入时仅诊断一次，新帧或隐藏后重新计时 */
internal class MapStallGate {
    private var sequence = -1L
    private var since = -1L
    private var reported = false

    /** 静止画面只触发后台检查，不能直接判定故障 */
    fun inspect(
        now: Long,
        visible: Boolean,
        frame: Long,
    ): Boolean {
        if (!visible) {
            since = -1L
            reported = false
            return false
        }
        if (since < 0 || frame != sequence) {
            sequence = frame
            since = now
            reported = false
        }
        if (reported || now - since < 10_000L) return false
        reported = true
        return true
    }
}
