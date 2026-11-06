package io.github.shaonialife.buicklinkplus.bt

import java.util.Locale

/** 仅在当前连接操作中，为零接收超时的目标安排一次应答开场 */
internal class BluetoothHandshakeRecovery {
    enum class Mode(
        val title: String,
    ) {
        ACTIVE("主动握手"),
        RESPONDER("应答握手"),
    }

    /** 切换或清空握手后作废旧凭据，忽略迟到结果 */
    data class Attempt(
        val mode: Mode,
        val token: Long,
    )

    private val attempts = mutableMapOf<String, Attempt>()
    private var nextToken = 0L

    /** 已可应答的目标保留原流程，不按品牌猜测模式 */
    @Synchronized
    fun attempt(address: String): Attempt = attempts.getOrPut(address.uppercase(Locale.ROOT)) { Attempt(Mode.ACTIVE, nextToken++) }

    /** 调用方必须确认本轮未取消、未关链，且初始协商截止时接收仍为零 */
    @Synchronized
    fun recordSilence(
        address: String,
        attempted: Attempt,
    ): Mode? {
        val key = address.uppercase(Locale.ROOT)
        if (attempts[key] != attempted || attempted.mode != Mode.ACTIVE) return null
        attempts[key] = Attempt(Mode.RESPONDER, nextToken++)
        return Mode.RESPONDER
    }

    /** 应答只试一轮，部分收包后失败也回到默认流程 */
    @Synchronized
    fun finishAttempt(
        address: String,
        attempted: Attempt,
    ): Boolean {
        val key = address.uppercase(Locale.ROOT)
        if (attempted.mode != Mode.RESPONDER || attempts[key] != attempted) return false
        attempts[key] = Attempt(Mode.ACTIVE, nextToken++)
        return true
    }

    /** 用户重新连接时恢复原首选策略，兼容回退不写入长期偏好 */
    @Synchronized
    fun clear() {
        attempts.clear()
    }
}

/** 入站与出站按同一目标共享本次尝试结果，旧代次不能写入 */
internal object BtHandshakeRecovery {
    val attempts = BluetoothHandshakeRecovery()
}
