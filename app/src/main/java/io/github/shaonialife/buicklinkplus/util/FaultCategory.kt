package io.github.shaonialife.buicklinkplus.util

/** 界面与日志共用故障分类，区分取消、断开和超时 */
internal enum class FaultCategory(
    val title: String,
) {
    CANCELLED("已取消"),
    NETWORK("网络已失效"),
    PEER_CLOSED("连接已关闭"),
    TIMEOUT("等待超时"),
    REJECTED("对端拒绝"),
    UNKNOWN("失败原因待确认"),
    ;

    companion object {
        fun classify(
            message: String?,
            cancelled: Boolean = false,
        ): FaultCategory {
            val text = message.orEmpty()
            return when {
                cancelled || text.contains("取消") || text.contains("中断") -> CANCELLED
                Regex("(?i)ENONET|ENETUNREACH|network is unreachable|网络.*失效").containsMatchIn(text) -> NETWORK
                Regex("(?i)EOF|closed|reset|broken pipe|关闭|断开").containsMatchIn(text) -> PEER_CLOSED
                Regex("(?i)timed? ?out|timeout|超时|截止").containsMatchIn(text) -> TIMEOUT
                Regex("(?i)拒绝|rejected|forbidden|(?:^|\\s)(?:403|456|500|501)(?:\\s|$)").containsMatchIn(text) -> REJECTED
                else -> UNKNOWN
            }
        }
    }
}
