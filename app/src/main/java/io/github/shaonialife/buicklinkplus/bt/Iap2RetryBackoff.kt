package io.github.shaonialife.buicklinkplus.bt

/** 失败后逐步延长重试间隔，达到阈值后每 30 秒继续尝试，等待车机旧会话过期 */
internal object Iap2RetryBackoff {
    /** 连续建链或会话失败达到该次数后从快速重试转为慢速重试 */
    const val MAX_CONSECUTIVE_FAILURES = 12

    /** 慢速重试间隔（毫秒）：给车机侧旧会话留出过期时间 */
    const val SLOW_RETRY_DELAY = 30_000L

    /** 快速重试间隔：3 秒起倍增，封顶 24 秒 */
    fun delay(failures: Int): Long = 3_000L shl (failures - 1).coerceIn(0, 3)

    /** 是否已进入慢速重试阶段 */
    fun shouldSlowDown(failures: Int): Boolean = failures >= MAX_CONSECUTIVE_FAILURES

    /** 本轮失败后的实际等待时长 */
    fun nextDelay(failures: Int): Long = if (shouldSlowDown(failures)) SLOW_RETRY_DELAY else delay(failures)
}
