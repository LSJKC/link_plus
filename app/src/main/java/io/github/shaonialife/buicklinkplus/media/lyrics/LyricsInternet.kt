package io.github.shaonialife.buicklinkplus.media.lyrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.shaonialife.buicklinkplus.net.existingNetworks
import java.io.IOException

/** 没有已存在的互联网通路时降级展示，不触发移动网络申请 */
internal class LyricsNoInternet : IOException("暂无可用的歌词网络")

/** 可见网络能力快照，与车机本地热点的有效性判定分开 */
internal data class LyricsNetworkCandidate<T>(
    val network: T,
    val internet: Boolean,
    val validated: Boolean,
    val vpn: Boolean,
    val isDefault: Boolean,
)

/** 公网请求使用已验证网络，有 VPN 时不绕过 */
internal object LyricsNetworkPolicy {
    /** 先选择VPN或默认网络，否则复用现有已验证互联网 */
    fun <T> choose(candidates: List<LyricsNetworkCandidate<T>>): T? {
        val vpn = candidates.filter { it.vpn }
        val allowed = if (vpn.isNotEmpty()) vpn else candidates
        return allowed
            .filter { it.internet && it.validated }
            .sortedByDescending { it.isDefault }
            .firstOrNull()
            ?.network
    }
}

/** 每次检查可用网络，不复用已失效网络，也不修改进程的投屏绑定 */
internal class LyricsInternet(
    context: Context,
) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** 返回仅供本次HTTPS连接使用的实际网络 */
    fun current(): Network {
        val manager = connectivity ?: throw LyricsNoInternet()
        return runCatching {
            val current = manager.activeNetwork
            val candidates =
                manager.existingNetworks().mapNotNull { network ->
                    val caps = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                    LyricsNetworkCandidate(
                        network,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        network == current,
                    )
                }
            LyricsNetworkPolicy.choose(candidates)
        }.getOrNull() ?: throw LyricsNoInternet()
    }
}
