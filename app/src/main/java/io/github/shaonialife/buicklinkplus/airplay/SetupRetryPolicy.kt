package io.github.shaonialife.buicklinkplus.airplay

/** 初始会话重试只处理可恢复故障，取消及失效网络由外层重新入网 */
internal object SetupRetryPolicy {
    enum class Failure(
        val title: String,
    ) {
        NONE("已完成"),
        TIMEOUT("协商超时"),
        SERVER("接收端内部错误"),
        NETWORK("网络失效"),
        CANCELLED("已取消"),
        CLOSED("控制连接关闭"),
        REJECTED("接收端拒绝"),
        INVALID("协商结果无效"),
    }

    fun classify(
        status: String?,
        error: Throwable?,
        cancelled: Boolean,
    ): Failure =
        when {
            cancelled || Thread.currentThread().isInterrupted -> Failure.CANCELLED
            error?.message?.contains("ENONET", true) == true || error?.message?.contains("网络已失效") == true -> Failure.NETWORK
            error is java.net.SocketTimeoutException || error?.message?.contains("超时") == true -> Failure.TIMEOUT
            error != null -> Failure.CLOSED
            status?.split(' ')?.getOrNull(1) == "500" -> Failure.SERVER
            status?.split(' ')?.getOrNull(1) != "200" -> Failure.REJECTED
            else -> Failure.INVALID
        }

    /** 初始 SETUP 总共最多两次，含加密升级重试 */
    fun canRetry(
        failure: Failure,
        attempts: Int,
        remaining: Long,
    ): Boolean =
        attempts < 2 &&
            remaining > 2_000 &&
            failure in setOf(Failure.TIMEOUT, Failure.SERVER, Failure.CLOSED)
}
