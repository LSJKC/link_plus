package io.github.shaonialife.buicklinkplus.media.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsNetworkPolicyTest {
    /** 车机热点无互联网时单独使用已有蜂窝通路，不改变热点绑定 */
    @Test fun localHotspotDoesNotBlockExistingInternet() {
        val candidates =
            listOf(
                LyricsNetworkCandidate("车机热点", false, false, false, true),
                LyricsNetworkCandidate("蜂窝网络", true, true, false, false),
            )
        assertEquals("蜂窝网络", LyricsNetworkPolicy.choose(candidates))
        assertNull(LyricsNetworkPolicy.choose(candidates.take(1)))
    }

    /** 存在VPN时不得绕行底层网络，VPN不可用就显示网络不可用 */
    @Test fun vpnCannotBeBypassedByCellularFallback() {
        val candidates =
            listOf(
                LyricsNetworkCandidate("VPN", true, true, true, false),
                LyricsNetworkCandidate("蜂窝网络", true, true, false, true),
            )
        assertEquals("VPN", LyricsNetworkPolicy.choose(candidates))
        assertNull(LyricsNetworkPolicy.choose(candidates.map { if (it.vpn) it.copy(validated = false) else it }))
    }

    /** 优先已验证默认网络，不主动申请或启用其他通路 */
    @Test fun validatedDefaultWinsOverOtherCandidates() {
        val candidates =
            listOf(
                LyricsNetworkCandidate("蜂窝网络", true, true, false, false),
                LyricsNetworkCandidate("系统Wi-Fi", true, true, false, true),
            )
        assertEquals("系统Wi-Fi", LyricsNetworkPolicy.choose(candidates))
    }
}
