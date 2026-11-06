package io.github.shaonialife.buicklinkplus.net

/**
 * 排序接收端地址：IPv4、全局 IPv6、链路本地 IPv6
 * 带或不带 scope 的同地址只保留首项，链路本地 scope 由调用方补齐
 */
object EndpointHosts {
    fun isIpv4(host: String): Boolean = host.contains('.') && !host.contains(':')

    fun isIpv6(host: String): Boolean = host.contains(':')

    fun isLinkLocalIpv6(host: String): Boolean {
        val normalized = host.substringBefore('%').lowercase()
        return normalized.startsWith("fe80:") ||
            normalized == "fe80" ||
            normalized.startsWith("fe8") ||
            normalized.startsWith("fe9") ||
            normalized.startsWith("fea") ||
            normalized.startsWith("feb")
    }

    /** 去掉 `%wlan0` / `%3` 这类 scope 后缀。 */
    fun stripScope(host: String): String = host.substringBefore('%')

    /** 检查端点是否包含可尝试的 IPv4 或 IPv6 地址 */
    fun hasUsable(addresses: List<String>): Boolean = candidates(addresses).isNotEmpty()

    /** 排序 + 去重。空串/纯 scope 一律剔除。 */
    fun candidates(addresses: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(addresses.size)

        fun add(host: String) {
            val trimmed = host.trim()
            if (trimmed.isEmpty()) return
            val base = stripScope(trimmed)
            if (base.isEmpty()) return
            if (!isIpv4(base) && !isIpv6(base)) return
            val key = base.lowercase()
            if (seen.add(key)) out += trimmed
        }
        addresses.filter(::isIpv4).forEach(::add)
        addresses.filter { isIpv6(it) && !isLinkLocalIpv6(it) }.forEach(::add)
        addresses.filter(::isLinkLocalIpv6).forEach(::add)
        return out
    }

    /** 判断是否在车机网段：IPv4 比较 /24，IPv6 全局地址比较 /64，链路本地直接接受 */
    fun isSameNetwork(
        host: String,
        localIpv4: String?,
        localIpv6: List<String> = emptyList(),
    ): Boolean {
        if (isIpv4(host)) {
            val prefix = localIpv4?.substringBeforeLast('.')?.plus(".")
            return prefix == null || host.startsWith(prefix)
        }
        if (!isIpv6(host)) return false
        if (isLinkLocalIpv6(host)) return true
        val candidate = ipv6Prefix64(host) ?: return false
        return localIpv6.any { ipv6Prefix64(it) == candidate }
    }

    /** IPv6 的前 64 位（前 4 组 hextet，展开后比较；解析失败返回 null）。 */
    fun ipv6Prefix64(host: String): String? {
        val groups = expandIpv6(stripScope(host)) ?: return null
        return groups.take(4).joinToString(":")
    }

    /** 把 IPv6 文本展开为 8 组（处理 `::` 压缩），返回小写 hextet 列表。 */
    private fun expandIpv6(host: String): List<String>? {
        if (!host.contains(':')) return null
        val doubleColon = host.indexOf("::")
        val head: List<String>
        val tail: List<String>
        if (doubleColon >= 0) {
            head = host.substring(0, doubleColon).split(':').filter { it.isNotEmpty() }
            tail = host.substring(doubleColon + 2).split(':').filter { it.isNotEmpty() }
        } else {
            head = host.split(':')
            tail = emptyList()
        }
        if (head.size + tail.size > 8) return null
        val fill = 8 - head.size - tail.size
        val groups = ArrayList<String>(8)
        groups += head
        repeat(fill) { groups += "0" }
        groups += tail
        if (groups.size != 8) return null
        if (groups.any { it.length > 4 || it.any { c -> c.digitToIntOrNull(16) == null } }) return null
        return groups.map { it.lowercase().padStart(4, '0') }
    }
}
