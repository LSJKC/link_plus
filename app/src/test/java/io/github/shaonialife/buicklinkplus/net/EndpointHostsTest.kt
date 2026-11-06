package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查 IPv4、IPv6 候选排序和 mDNS features 解析 */
class EndpointHostsTest {
    @Test
    fun ipv6OnlyEndpointIsUsable() {
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::1234:5678:9abc:def0%wlan0")))
        assertTrue(EndpointHosts.hasUsable(listOf("fe80::1")))
        assertFalse(EndpointHosts.hasUsable(emptyList()))
        assertFalse(EndpointHosts.hasUsable(listOf("", "not-an-ip")))
    }

    @Test
    fun ipv4PreferredThenGlobalIpv6ThenLinkLocal() {
        val ordered =
            EndpointHosts.candidates(
                listOf(
                    "fe80::1%wlan0",
                    "2001:db8::5",
                    "192.0.2.1",
                ),
            )
        assertEquals(listOf("192.0.2.1", "2001:db8::5", "fe80::1%wlan0"), ordered)
    }

    @Test
    fun duplicateScopedAndUnscopedAddressesCollapse() {
        val ordered = EndpointHosts.candidates(listOf("fe80::1", "fe80::1%wlan0", "FE80::1%3"))
        assertEquals(1, ordered.size)
        assertEquals("fe80::1", ordered.first())
    }

    @Test
    fun ipv4NetworkFilterUses24Prefix() {
        assertTrue(EndpointHosts.isSameNetwork("192.0.2.1", "192.0.2.57"))
        assertFalse(EndpointHosts.isSameNetwork("198.51.100.246", "192.0.2.57"))
        // 拿不到本地 IPv4 时不设限（旧行为）
        assertTrue(EndpointHosts.isSameNetwork("192.0.2.1", null))
    }

    @Test
    fun linkLocalIpv6AlwaysAcceptedGlobalIpv6NeedsMatchingPrefix() {
        assertTrue(EndpointHosts.isSameNetwork("fe80::1%wlan0", "10.0.0.2"))
        assertTrue(
            EndpointHosts.isSameNetwork(
                "2001:db8:1:2::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        assertFalse(
            EndpointHosts.isSameNetwork(
                "2001:db8:9:9::5",
                "10.0.0.2",
                listOf("2001:db8:1:2::99"),
            ),
        )
        // 本地没有 IPv6 信息：拒绝全局 IPv6（宁可走网关兜底，也不误连家庭网络设备）
        assertFalse(EndpointHosts.isSameNetwork("2001:db8:1:2::5", "10.0.0.2"))
    }

    @Test
    fun ipv6Prefix64ExpandsCompressedForms() {
        assertEquals("2001:0db8:0001:0002", EndpointHosts.ipv6Prefix64("2001:db8:1:2::5"))
        assertEquals("fe80:0000:0000:0000", EndpointHosts.ipv6Prefix64("fe80::1%wlan0"))
        assertEquals("2001:0db8:0000:0000", EndpointHosts.ipv6Prefix64("2001:db8::"))
        assertNull(EndpointHosts.ipv6Prefix64("not-ipv6"))
    }
}

class AirPlayFeaturesTest {
    @Test
    fun parsesDiplayStyleFeatureBitmap() {
        // DiPlay: features=0x5653aee2,0x61
        val features = AirPlayFeatures.parse("0x5653aee2,0x61")
        assertEquals((0x61L shl 32) or 0x5653aee2L, features)
        // 高 32 位的 bit38 → 全局 bit38
        assertTrue(features!! and AirPlayFeatures.HK_PAIRING_AND_ENCRYPT != 0L)
        assertEquals(true, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun preHomeKitBitmapLacksPairingBit() {
        // CarPlay 210.81 时代（catplay README 兼容表）：无 HK_PAIRING_AND_ENCRYPT
        val features = AirPlayFeatures.parse("0x00000080,0x0")
        assertEquals(false, AirPlayFeatures.supportsPairing(features))
    }

    @Test
    fun missingOrMalformedFeaturesStayUnknown() {
        assertNull(AirPlayFeatures.parse(null))
        assertNull(AirPlayFeatures.parse(""))
        assertNull(AirPlayFeatures.parse("not-hex"))
        assertNull(AirPlayFeatures.parse("0x1,0x2,0x3"))
        // 能力未知时返回 null，不能判为不支持配对
        assertNull(AirPlayFeatures.supportsPairing(null))
    }
}
