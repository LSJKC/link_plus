package io.github.shaonialife.buicklinkplus.net

/**
 * 合并 features 的低 32 位和高 32 位，bit38 表示 HomeKit 配对加密
 * 解析失败返回 null，不能当成不支持配对
 */
object AirPlayFeatures {
    /** HomeKit pair-setup 和 pair-verify 能力位 */
    const val HK_PAIRING_AND_ENCRYPT: Long = 1L shl 38

    /** 支持屏幕镜像。 */
    const val SCREEN: Long = 1L shl 7

    /** 车机控制（CarPlay）。 */
    const val CARPLAY_CONTROL: Long = 1L shl 37

    fun parse(txt: String?): Long? {
        val text = txt?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > 2) return null
        val low = parseHex(parts[0]) ?: return null
        val high = if (parts.size == 2) parseHex(parts[1]) ?: return null else 0L
        if (low < 0 || high < 0) return null
        return (high shl 32) or low
    }

    /** 接收端是否声明支持 HomeKit 配对/加密；null = TXT 缺失或不可解析。 */
    fun supportsPairing(features: Long?): Boolean? = features?.let { (it and HK_PAIRING_AND_ENCRYPT) != 0L }

    private fun parseHex(value: String): Long? {
        val cleaned = value.removePrefix("0x").removePrefix("0X")
        if (cleaned.isEmpty()) return null
        return cleaned.toLongOrNull(16)
    }
}
