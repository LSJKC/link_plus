package io.github.shaonialife.buicklinkplus.iap2

/** 本次连接操作内的临时换档，不把传输断开持久化为长度拒绝 */
class MfiChallengeRecovery {
    /** 每次认证使用独立凭据，迟到或重复的断链不能影响后续会话 */
    class Attempt internal constructor(
        internal val receiverKey: String,
        internal val token: Long,
        val lengths: List<Int>,
    )

    private data class State(
        val token: Long,
        val disconnected: Set<Int>,
    )

    private val states = mutableMapOf<String, State>()
    private var nextToken = 0L

    /** 保留持久化首选，只在本次操作中把曾遇传输断开的档位临时后移 */
    @Synchronized
    fun begin(
        identity: String,
        baseline: List<Int>,
    ): Attempt {
        val key = MfiLengthMemory.receiverKey(identity)
        val previous = states[key]?.disconnected.orEmpty()
        val failed = if (baseline.all { it in previous }) emptySet() else previous
        val order = baseline.filter { it !in failed } + baseline.filter { it in failed }
        val token = nextToken++
        states[key] = State(token, failed)
        return Attempt(key, token, order)
    }

    /** 只记录已发送挑战后的传输断开；调用方负责排除本地取消、退役和协议超时 */
    @Synchronized
    fun recordDisconnect(
        attempt: Attempt,
        length: Int,
    ): Boolean {
        val state = states[attempt.receiverKey] ?: return false
        if (state.token != attempt.token || length !in attempt.lengths || length in state.disconnected) return false
        states[attempt.receiverKey] = state.copy(disconnected = state.disconnected + length)
        return true
    }

    /** 真实签名应答恢复持久化首选，旧凭据同时作废 */
    @Synchronized
    fun recordSuccess(attempt: Attempt) {
        if (states[attempt.receiverKey]?.token == attempt.token) states.remove(attempt.receiverKey)
    }
}
