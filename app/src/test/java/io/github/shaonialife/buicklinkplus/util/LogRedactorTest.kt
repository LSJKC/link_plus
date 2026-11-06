package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    /** 明文、十六进制、无线组及跨行碎片均不能还原测试密码 */
    @Test fun hidesCredentialsAndUnparsedPayloads() {
        val secret = "Test-Password-981"
        val hex = secret.toByteArray().joinToString("") { "%02x".format(it) }
        val source =
            "psk=$secret\nRAW rx: $hex\niap2 0x4301 无线组参数: #1=$hex\n" +
                "TUNNEL: rx 0x4301 len=90 hex=$hex\n${hex.take(16)}\n${hex.drop(16)}"
        val safe = LogRedactor.redact(source)
        assertFalse(safe.contains(secret))
        assertFalse(safe.contains(hex.take(16)))
        assertTrue(safe.contains("0x4301 len=90"))
        assertEquals(safe, LogRedactor.redact(safe))
    }

    /** 解析失败时也隐藏载荷，保留错误类型和帧信息 */
    @Test fun malformedPayloadIsNotPrinted() {
        val safe = LogRedactor.redact("iap2 0x4301 原始体=无法解析-secret\nLINK rx: seq=2 len=44\npassword=带 空格密码")
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("带 空格密码"))
        assertTrue(safe.contains("seq=2 len=44"))
    }

    /** 历史密钥前缀、带分隔符或引号的凭据及单字节碎片同样隐藏 */
    @Test fun historicalKeysAndDelimitedPasswordsAreHidden() {
        val safe =
            LogRedactor.redact(
                "writeKey=0123456789abcdef… readKey=abcdef0123456789…\n" +
                    "{\"password\":\"逗号,分号;空格 密码\"}\n" +
                    "payload:无法解析凭据\n61\n0000: 62 63\nLINK rx seq=3 len=44",
            )
        listOf("0123456789abcdef", "abcdef0123456789", "逗号", "分号", "空格", "无法解析凭据", "62 63")
            .forEach { assertFalse(safe.contains(it)) }
        assertTrue(safe.contains("seq=3 len=44"))
        assertEquals(safe, LogRedactor.redact(safe))
    }
}
