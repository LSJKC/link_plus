package io.github.shaonialife.buicklinkplus.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellServiceGuidanceTest {
    @Test
    fun firstUseExplainsLocalPairingAndDeveloperOptions() {
        val guide = ShellServiceGate.guidanceOf(ShellServiceGate.State.NEEDS_PAIRING)
        assertTrue(guide.message.contains("无线调试"))
        assertTrue(guide.message.contains("开发者选项"))
        assertTrue(guide.message.contains("无需安装其他应用"))
        assertEquals("激活服务", guide.primaryLabel)
    }

    @Test
    fun stoppedServiceExplainsReactivationWithoutForcedRepairing() {
        val guide = ShellServiceGate.guidanceOf(ShellServiceGate.State.STOPPED)
        assertTrue(guide.message.contains("重新启动"))
        assertTrue(guide.message.contains("已有配对可继续使用"))
        assertTrue(guide.message.contains("自动"))
        assertTrue(guide.message.contains("WLAN"))
        assertEquals("查看恢复方式", guide.primaryLabel)
    }

    @Test
    fun onlineServiceNeedsOnlyInputRecheck() {
        val guide = ShellServiceGate.guidanceOf(ShellServiceGate.State.INPUT_UNAVAILABLE)
        assertTrue(guide.message.contains("无需重新配对"))
        assertEquals("重新检查", guide.primaryLabel)
    }

    @Test
    fun activationAndReadyHaveDifferentActions() {
        assertEquals("查看激活进度", ShellServiceGate.guidanceOf(ShellServiceGate.State.ACTIVATING).primaryLabel)
        assertNull(ShellServiceGate.guidanceOf(ShellServiceGate.State.READY).primaryLabel)
        val guides = ShellServiceGate.State.entries.map { ShellServiceGate.guidanceOf(it) }
        assertEquals(guides.size, guides.map { it.title }.toSet().size)
        guides.forEach { assertTrue(!it.message.contains("Shizuku")) }
    }

    @Test
    fun startupArgumentsCannotEscapeSingleQuotedShellWords() {
        assertEquals("'a'\\''b'", EmbeddedAdb.quote("a'b"))
        assertEquals("'a; echo x'", EmbeddedAdb.quote("a; echo x"))
    }
}
