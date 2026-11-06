package io.github.shaonialife.buicklinkplus.airplay

import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查 SETUP 的 deviceID 使用本机蓝牙地址 */
class SessionSetupBodyTest {
    private fun client() =
        AirPlayClient(
            Iap2SenderSession.CarPlayEndpoint(
                ssid = "Test-CarPlay-Hotspot",
                passphrase = "test-password",
                channel = 0,
                securityType = 2,
                airPlayPort = 17000,
                deviceIdentifier = null,
                publicKey = null,
                sourceVersion = null,
                ipAddresses = listOf("192.0.2.1"),
            ),
            log = {},
        )

    @Test
    fun deviceIdIsCarriedVerbatimIntoSetupBody() {
        val body = client().buildSessionSetupBody("02:AA:BB:CC:DD:22", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertEquals("02:AA:BB:CC:DD:22", root["deviceID"])
    }

    @Test
    fun deviceIdIsNotTheCatplayPlaceholder() {
        val body = client().buildSessionSetupBody("02:AA:BB:CC:DD:22", timingPort = 35449)
        val root = BinaryPlist.parse(body) as Map<*, *>
        assertNotEquals("ff:ee:dd:cc:bb:aa", root["deviceID"])
    }

    @Test
    fun bodyKeepsTheFieldsReceiversNegotiateOn() {
        val port = 35449
        val body = client().buildSessionSetupBody("02:AA:BB:CC:DD:22", port)
        val root = BinaryPlist.parse(body) as Map<*, *>
        // timingPort 是媒体时钟同步入口；features 决定 enabledFeatures（缺失即视频管线不启用）
        assertEquals(port.toLong(), root["timingPort"])
        assertTrue((root["features"] as List<*>).contains("viewAreas"))
        assertEquals(true, root["keepAliveLowPower"])
    }

    /** 检查未配对 SETUP 显式携带 et=1，即 AirPlayEncryptionType.NONE */
    @Test
    fun unpairedSessionDeclaresEtNone() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("02:AA:BB:CC:DD:22", 35449, encrypted = false),
            ) as Map<*, *>
        assertEquals(1L, root["et"])
        assertEquals(false, root["updateSessionRequest"])
    }

    @Test
    fun pairedSessionOmitsEt() {
        val root =
            BinaryPlist.parse(
                client().buildSessionSetupBody("02:AA:BB:CC:DD:22", 35449, encrypted = true),
            ) as Map<*, *>
        assertNull(root["et"])
    }
}
