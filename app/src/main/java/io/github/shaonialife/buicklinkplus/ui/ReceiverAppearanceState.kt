package io.github.shaonialife.buicklinkplus.ui

/**
 * 按控制连接隔离车机日夜状态，缺少有效通告时回退手机系统
 */
internal class ReceiverAppearanceState {
    private var owner: Any? = null
    private var receiverNight: Boolean? = null

    /**
     * 新连接取得外观状态所有权，并清除上一连接的日夜通告
     */
    @Synchronized
    fun begin(connection: Any): Boolean {
        if (owner === connection) return false
        val changed = receiverNight != null
        owner = connection
        receiverNight = null
        return changed
    }

    /**
     * 只接收当前连接的有效日夜值，重复值和无效值不触发刷新
     */
    @Synchronized
    fun update(
        connection: Any,
        value: Any?,
    ): Boolean {
        if (owner !== connection) return false
        val night = parseNightMode(value) ?: return false
        if (receiverNight == night) return false
        receiverNight = night
        return true
    }

    /**
     * 初始及侧信道信息只填补缺失值，不能覆盖已经接收的明确事件
     */
    @Synchronized
    fun initialize(
        connection: Any,
        value: Any?,
    ): Boolean {
        if (receiverNight != null) return false
        return update(connection, value)
    }

    /** 只有所属连接可清除日夜通告 */
    @Synchronized
    fun end(connection: Any): Boolean {
        if (owner !== connection) return false
        val changed = receiverNight != null
        owner = null
        receiverNight = null
        return changed
    }

    /**
     * 手机遵循系统，车机优先采用本次连接的明确通告
     */
    @Synchronized
    fun isNight(
        carDisplay: Boolean,
        systemNight: Boolean,
    ): Boolean = if (carDisplay) receiverNight ?: systemNight else systemNight
}

/** 只接受布尔值或数字 0、1 */
internal fun parseNightMode(value: Any?): Boolean? =
    when (value) {
        is Boolean -> value
        is Number ->
            when (value.toDouble()) {
                0.0 -> false
                1.0 -> true
                else -> null
            }
        else -> null
    }
