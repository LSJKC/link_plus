package io.github.shaonialife.buicklinkplus.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查自动暗幕只在开关开启、会话活跃且已挂载时生效，手动暗幕不受限制 */
class ScreenDimmerTest {
    @Test
    fun autoDimRequiresActiveSession() {
        assertFalse(
            "会话结束后绝不能再弹暗幕（本次 bug）",
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = false, attached = true),
        )
        assertTrue(
            ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = true),
        )
    }

    @Test
    fun autoDimRequiresSettingEnabled() {
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = false, sessionActive = true, attached = true))
    }

    @Test
    fun autoDimRequiresAttachment() {
        // 缺悬浮窗权限时 windowManager 为 null，showDim 也加不上窗口
        assertFalse(ScreenDimmer.shouldAutoDim(enabled = true, sessionActive = true, attached = false))
    }

    @Test
    fun allConditionsMustHold() {
        val cases =
            listOf(
                Triple(true, true, true) to true,
                Triple(true, true, false) to false,
                Triple(true, false, true) to false,
                Triple(true, false, false) to false,
                Triple(false, true, true) to false,
                Triple(false, true, false) to false,
                Triple(false, false, true) to false,
                Triple(false, false, false) to false,
            )
        cases.forEach { (input, expected) ->
            val (enabled, session, attached) = input
            assertTrue(
                "enabled=$enabled session=$session attached=$attached 期望 $expected",
                ScreenDimmer.shouldAutoDim(enabled, session, attached) == expected,
            )
        }
    }
}
