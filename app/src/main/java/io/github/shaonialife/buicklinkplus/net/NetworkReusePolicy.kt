package io.github.shaonialife.buicklinkplus.net

/** 复用无互联网热点前检查身份、系统网络和实际链路 */
internal object NetworkReusePolicy {
    fun usable(
        expected: String,
        bound: String?,
        observed: String?,
        present: Boolean,
        wifi: Boolean,
        linked: Boolean,
    ): Boolean = expected == bound && present && wifi && linked && (observed == null || observed == expected)
}
