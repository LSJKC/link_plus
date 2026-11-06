package io.github.shaonialife.buicklinkplus.net

/** 网络仍在但属性缺失时等待，明确移除后重新入网 */
object CarNetworkReadiness {
    enum class State { READY, WAITING, LOST }

    /**
     * 只依据系统存活、接口及地址状态分类，不根据失败次数猜测断网
     */
    fun classify(
        present: Boolean,
        interfaceReady: Boolean,
        addressReady: Boolean,
    ): State =
        if (!present) {
            State.LOST
        } else if (interfaceReady && addressReady) {
            State.READY
        } else {
            State.WAITING
        }
}
