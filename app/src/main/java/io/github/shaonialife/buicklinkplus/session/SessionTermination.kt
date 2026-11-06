package io.github.shaonialife.buicklinkplus.session

/** 同一连接仅保留首个结束原因，派生错误不能覆盖首因 */
internal class SessionTermination {
    data class Cause(
        val reason: String,
        val at: Long,
    )

    private var first: Cause? = null

    @Synchronized fun record(
        reason: String,
        at: Long,
    ): Cause = first ?: Cause(reason, at).also { first = it }

    @Synchronized fun snapshot(): Cause? = first
}
