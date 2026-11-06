package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 当前热点识别的回归用例，防止重复入网及误绑定其他网络
 */
class WifiStatusTest {
    /**
     * 直接名称保留空格和中文
     */
    @Test
    fun readsQuotedConnectionName() {
        assertEquals("测试热点 0001", WifiStatus.connectedSsid("Wifi is enabled\nWifi is connected to \"测试热点 0001\""))
    }

    /**
     * 系统连接信息中的名称不会被误认为未连接
     */
    @Test
    fun readsSsidFromConnectionDetails() {
        val output = "Wifi is enabled\nWifi is connected to SSID: \"测试热点 0001\", BSSID: 02:00:00:00:00:01, MAC: 02:00:00:00:00:02"
        assertEquals("测试热点 0001", WifiStatus.connectedSsid(output))
    }

    /**
     * 带对象前缀的连接信息也能保留未加引号的热点名称
     */
    @Test
    fun readsSsidFromWifiInfo() {
        val output = "Wifi is connected to WifiInfo{SSID: 测试热点 0001, BSSID: 02:00:00:00:00:01}"
        assertEquals("测试热点 0001", WifiStatus.connectedSsid(output))
    }

    /**
     * 热点名称内的连续空格及逗号不被压缩或截断
     */
    @Test
    fun preservesNameSeparators() {
        val output = "Wifi is connected to SSID: \"本地,  测试\", BSSID: 02:00:00:00:00:01"
        assertEquals("本地,  测试", WifiStatus.connectedSsid(output))
    }

    /**
     * 已断开时不使用残留连接信息或附近网络的名称
     */
    @Test
    fun ignoresCachedAndScannedNamesWhenDisconnected() {
        assertNull(WifiStatus.connectedSsid("Wifi is enabled\nWifi is not connected\nSSID: \"旧热点\", BSSID: 02:00:00:00:00:01"))
        assertNull(WifiStatus.connectedSsid("Scan results:\nSSID: \"附近热点\", BSSID: 02:00:00:00:00:01"))
    }

    /**
     * 无线开关关闭时不复用残留的已连接行
     */
    @Test
    fun ignoresConnectionWhenWifiDisabled() {
        assertNull(WifiStatus.connectedSsid("Wifi is disabled\nWifi is connected to \"旧热点\""))
    }

    /**
     * 隐藏或空名称不能作为已连接目标热点的证据
     */
    @Test
    fun rejectsUnknownAndBlankNames() {
        assertNull(WifiStatus.connectedSsid("Wifi is connected to SSID: <unknown ssid>, BSSID: <none>"))
        assertNull(WifiStatus.connectedSsid("Wifi is connected to \"<unknown ssid>\""))
        assertNull(WifiStatus.connectedSsid("Wifi is connected to \"\""))
        assertNull(WifiStatus.connectedSsid(""))
    }

    /**
     * 大小写和行首空白不影响连接状态识别
     */
    @Test
    fun acceptsConnectionPrefixCaseAndIndentation() {
        assertEquals("本地测试", WifiStatus.connectedSsid("  WIFI IS CONNECTED TO \"本地测试\"  "))
    }
}
