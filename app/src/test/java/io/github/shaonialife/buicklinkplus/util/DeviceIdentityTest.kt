package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DeviceIdentity.normalize] 纯 JVM 测试。
 *
 * normalize 只做字符串处理（不读蓝牙/设置/文件），本地单测无需设备或真机权限；
 * 它同时是「用户手填 MAC」与「偏好回读」的入口，格式归一化/占位地址拒绝必须钉死。
 */
class DeviceIdentityTest {
    @Test
    fun normalizesPlainHex() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceIdentity.normalize("AABBCCDDEEFF"))
    }

    @Test
    fun normalizesDashSeparated() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceIdentity.normalize("AA-BB-CC-DD-EE-FF"))
    }

    @Test
    fun normalizesLowercaseColonSeparated() {
        assertEquals("AA:BB:CC:DD:EE:FF", DeviceIdentity.normalize("aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun rejectsTooShort() {
        assertNull(DeviceIdentity.normalize("AA:BB:CC:DD:EE"))
        assertNull(DeviceIdentity.normalize("AABBCCDDEE"))
        assertNull(DeviceIdentity.normalize(""))
    }

    @Test
    fun rejectsTooLong() {
        assertNull(DeviceIdentity.normalize("AA:BB:CC:DD:EE:FF:00"))
        assertNull(DeviceIdentity.normalize("AABBCCDDEEFF00"))
    }

    @Test
    fun rejectsNonHex() {
        // 非十六进制字符会被过滤，过滤后不足 12 位 → 非法。
        assertNull(DeviceIdentity.normalize("ZZ:BB:CC:DD:EE:FF"))
        assertNull(DeviceIdentity.normalize("AA:BB:CC:DD:EE:FG"))
    }

    @Test
    fun rejectsPlaceholderMac() {
        // Android 11+ 对普通应用返回的占位地址：必须当作「没解析到」。
        assertNull(DeviceIdentity.normalize(DeviceIdentity.PLACEHOLDER_MAC))
        assertNull(DeviceIdentity.normalize("02-00-00-00-00-00"))
        assertNull(DeviceIdentity.normalize("020000000000"))
    }
}
