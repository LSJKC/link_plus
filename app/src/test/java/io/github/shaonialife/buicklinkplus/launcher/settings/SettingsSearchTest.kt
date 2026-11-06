package io.github.shaonialife.buicklinkplus.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索匹配和稳定路由不能依赖菜单排列顺序。
 *
 * CarLife 成为唯一内容源后菜单收敛为六类页面（连接与设备/显示与画面/音频/CarLife/
 * 外观与壁纸/日志与关于），旧 link_plus 桌面页（LAYOUT/MAP 等）已下线，不再可从
 * [SettingsMenus.directPhonePanel] 直达。
 */
class SettingsSearchTest {
    /**
     * 常用词能直接匹配真实面板并兼容大小写和多词
     */
    @Test fun commonTermsResolveToStablePanels() {
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.search("码率").single().target)
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.search(" FPS ").single().target)
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.search("安全区 边距").single().target)
        assertEquals(SettingsMenus.CARLIFE, SettingsMenus.search("TTS 播报").single().target)
        assertEquals(SettingsMenus.CARLIFE, SettingsMenus.search("端口 自检").single().target)
        assertEquals(SettingsMenus.CONNECTION, SettingsMenus.search("蓝牙 MAC").single().target)
        assertEquals(SettingsMenus.APPEARANCE, SettingsMenus.search("车机外观").single().target)
        assertEquals(SettingsMenus.ABOUT, SettingsMenus.search("日志 导出").single().target)
        assertTrue(SettingsMenus.search("无法匹配的词").isEmpty())
        assertTrue(SettingsMenus.search("  ").isEmpty())
    }

    /**
     * 六类页面顺序稳定，搜索目标都能解析到真实面板，旧桌面页不再可直达
     */
    @Test fun visibleCategoriesRetainLegacyTargets() {
        assertEquals(listOf(108, 105, 5, 104, 100, 107), SettingsMenus.VISIBLE.map { it.id })
        assertEquals(SettingsMenus.PERMISSIONS, SettingsMenus.itemOf(SettingsMenus.PERMISSIONS).id)
        SettingsMenus.SEARCH.forEach { assertEquals(it.target, SettingsMenus.itemOf(it.target).id) }
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.directPhonePanel(SettingsMenus.LAYOUT))
        assertNull(SettingsMenus.directPhonePanel(SettingsMenus.MAP))
    }
}
