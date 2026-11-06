package io.github.shaonialife.buicklinkplus.net

/**
 * 解析系统 Wi-Fi 当前连接状态，保留热点名称中的空格及中文
 */
object WifiStatus {
    /**
     * 只从明确的当前连接行提取热点名称，未知名称与未连接状态返回空
     */
    fun connectedSsid(output: String): String? {
        if (output.lineSequence().any { it.trim().equals("Wifi is disabled", ignoreCase = true) }) return null
        val connection =
            output
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Wifi is connected to ", ignoreCase = true) }
                ?.substring("Wifi is connected to ".length)
                ?.trim()
                ?: return null
        val raw =
            if (connection.startsWith('"') && connection.endsWith('"')) {
                connection
            } else {
                Regex("(?:^|[\\s{])SSID:\\s*(.*?)(?:,\\s*BSSID:|$)")
                    .find(connection)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?: return null
            }
        val ssid = raw.removeSurrounding("\"")
        return ssid.takeIf {
            it.isNotBlank() &&
                !it.equals("<unknown ssid>", ignoreCase = true) &&
                !it.equals("<unknown>", ignoreCase = true)
        }
    }
}
