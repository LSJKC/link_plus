package io.github.shaonialife.buicklinkplus.util

/** 写入和导出都做脱敏，不保留可拼接的原始载荷 */
object LogRedactor {
    private val raw =
        Regex("""(?i)(RAW\s+(?:rx|tx)\s*[:=]|原始体[^=]*=|无线组参数\s*:|\bhex\s*[:=]|\b(?:payload|body)\s*[:=](?!\d+\s*(?:B|字节)\b))""")

    // 解析失败时宁可丢弃本行后续诊断，也不能让含分隔符的密码或密钥残留
    private val credential =
        Regex(
            """(?i)((?:passphrase|password|passwd|psk|密码|writeKey|readKey|phoneWriteKey|phoneReadKey|sharedSecret|privateKey)["']?\s*[:=]\s*)[^\r\n]*""",
        )
    private val fragment = Regex("""^(?:\[[^]]*]\s*)?(?:[0-9a-fA-F]{4,8}:\s*)?(?:[0-9a-fA-F]{2}\s*)+$""")

    /** 按行处理可覆盖历史分片，重复脱敏保持一致 */
    fun redact(text: String): String =
        text.lineSequence().joinToString("\n") { line ->
            val marker = raw.find(line)
            val safe =
                if (marker != null) {
                    val prefix = line.substring(0, marker.range.last + 1)
                    val body = line.substring(marker.range.last + 1)
                    if (body.startsWith("[载荷已隐藏")) line else "$prefix[载荷已隐藏，字符数=${body.length}]"
                } else if (fragment.matches(line.trim())) {
                    "[原始分片已隐藏]"
                } else {
                    line
                }
            credential.replace(safe) { "${it.groupValues[1]}***" }
        }
}
