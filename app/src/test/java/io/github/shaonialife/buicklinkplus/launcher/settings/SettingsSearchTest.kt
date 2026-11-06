package io.github.shaonialife.buicklinkplus.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索匹配和稳定路由不能依赖菜单排列顺序
 */
class SettingsSearchTest {
    /**
     * 常用词能直接匹配真实面板并兼容大小写和多词
     */
    @Test fun commonTermsResolveToStablePanels() {
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.search("码率").single().target)
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.search(" FPS ").single().target)
        assertEquals(SettingsMenus.CAR_APPEARANCE, SettingsMenus.search("天气 城市").single().target)
        assertEquals(SettingsMenus.DASHBOARD_WIDGET, SettingsMenus.search("QQ 歌词").single().target)
        assertTrue(SettingsMenus.search("无法匹配的词").isEmpty())
        assertTrue(SettingsMenus.search("  ").isEmpty())
    }

    /**
     * 六个主分类和全部旧 ID 都有唯一可解析目标
     */
    @Test fun visibleCategoriesRetainLegacyTargets() {
        assertEquals(listOf(105, 101, 100, 103, 104, 107), SettingsMenus.VISIBLE.map { it.id })
        assertEquals(102, SettingsMenus.itemOf(SettingsMenus.LAYOUT).id)
        assertEquals(106, SettingsMenus.itemOf(SettingsMenus.PERMISSIONS).id)
        SettingsMenus.SEARCH.forEach { assertEquals(it.target, SettingsMenus.itemOf(it.target).id) }
        assertEquals(SettingsMenus.DISPLAY, SettingsMenus.directPhonePanel(SettingsMenus.LAYOUT))
    }
}
