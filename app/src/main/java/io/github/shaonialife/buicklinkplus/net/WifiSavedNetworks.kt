package io.github.shaonialife.buicklinkplus.net

/**
 * 解析 cmd wifi list-networks：首列为 netId，末列为安全类型，中间为 SSID
 * 连续空格会合并，无法精确匹配时不提供删除入口
 */
object WifiSavedNetworks {
    /** 返回精确匹配 SSID 的去重 netId，未匹配返回空列表 */
    fun idsOf(
        listNetworksOutput: String,
        ssid: String,
    ): List<Int> =
        listNetworksOutput
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                // 至少 netId + SSID + security 三段
                if (parts.size < 3) return@mapNotNull null
                val id = parts.first().toIntOrNull() ?: return@mapNotNull null
                val name = parts.subList(1, parts.size - 1).joinToString(" ")
                if (name == ssid) id else null
            }.distinct()
            .toList()
}
