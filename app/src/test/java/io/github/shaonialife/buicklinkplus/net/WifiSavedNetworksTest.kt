package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** 检查已保存 WiFi 的解析和精确匹配，避免误删其他网络 */
class WifiSavedNetworksTest {
    /** 虚构热点样本，保留重复编号、空格和中文名称 */
    private val sampleOutput =
        """
        Network Id      SSID                         Security type
        0            TestHome-01                            wpa2-psk
        0            TestHome-01                            wpa3-sae^
        1            测试手机的热点                    wpa3-sae
        2            Office Test Network                    open
        2            Office Test Network                    owe
        3            TestCar_0001_5GHz               wpa2-psk
        """.trimIndent()

    @Test
    fun findsExactMatchIgnoringOtherNetworks() {
        assertEquals(listOf(3), WifiSavedNetworks.idsOf(sampleOutput, "TestCar_0001_5GHz"))
    }

    @Test
    fun dedupesMultipleSecurityRowsForSameNetwork() {
        // 同一 netId 可能有 wpa2/wpa3 两行
        assertEquals(listOf(0), WifiSavedNetworks.idsOf(sampleOutput, "TestHome-01"))
    }

    @Test
    fun handlesSsidWithSpacesAndNonAscii() {
        // SSID 中间的空白被重组为单个空格
        assertEquals(listOf(2), WifiSavedNetworks.idsOf(sampleOutput, "Office Test Network"))
        assertEquals(listOf(1), WifiSavedNetworks.idsOf(sampleOutput, "测试手机的热点"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        // SSID 不做近似匹配，避免误删其他网络
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(sampleOutput, "TestCar_0001"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(sampleOutput, "Unrelated Network"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(sampleOutput, ""))
    }

    @Test
    fun headerOnlyOrEmptyOutputIsSafe() {
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("Network Id SSID Security type", "X"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("", "X"))
    }

    @Test
    fun skipsMalformedRows() {
        val messy =
            """
            Network Id      SSID                         Security type
            notanumber   Foo                          wpa2-psk
            7            OnlyTwoColumns
            8            Bar                          wpa2-psk
            """.trimIndent()
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(messy, "Bar"))
    }
}
