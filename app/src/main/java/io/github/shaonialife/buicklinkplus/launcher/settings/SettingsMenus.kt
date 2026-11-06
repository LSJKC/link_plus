package io.github.shaonialife.buicklinkplus.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.buicklinkplus.R

/** 手机和车机共用的设置菜单，包含 ID、标题和面板工厂 */
object SettingsMenus {
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DISPLAY = 5
    const val CAR_APPEARANCE = 6
    const val OFFICIAL_CAR_APPS = 7

    data class Item(
        val id: Int,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    val PANELS =
        listOf(
            Item(DESKTOP, "管理桌面应用", "勾选的应用显示在车机桌面", R.drawable.ic_menu_grid),
            Item(OFFICIAL_CAR_APPS, "车载应用与手机版", "查看真实车载入口并管理桌面应用", R.drawable.ic_menu_grid),
            Item(SUPERSAMPLE, "应用超采样", "竖屏应用渲染更精细，可三态调节", R.drawable.ic_menu_zoom),
            Item(ORIENTATION, "应用横屏", "竖屏应用在车机横屏全屏显示", R.drawable.ic_screen_rotation),
            Item(CAR_APPEARANCE, "车机外观", "左侧只读小组件与天气位置", R.drawable.ic_wallpaper),
            Item(DOCK, "侧栏大小", "调整图标、时钟与文字的整体比例", R.drawable.ic_menu_dock),
            Item(DISPLAY, "显示与画面质量", "分辨率、视频码率与目标帧率", R.drawable.ic_menu_display),
        )

    const val APPEARANCE = 100
    const val APPS = 101
    const val LAYOUT = 102
    const val MAP = 103
    const val AUDIO = 104
    const val CONNECTION = 105
    const val PERMISSIONS = 106
    const val ABOUT = 107

    /** 新增：CarLife 连接页（引擎状态/重连/诊断）。 */
    const val CARLIFE = 108

    val ALL =
        listOf(
            Item(APPEARANCE, "画面与外观", "画质、安全区、壁纸、侧栏与浮层", R.drawable.ic_wallpaper),
            Item(APPS, "桌面与应用", "桌面选择、超采样与横屏", R.drawable.ic_menu_grid),
            Item(LAYOUT, "显示与布局", "分辨率、码率与帧率", R.drawable.ic_menu_display),
            Item(AUDIO, "音乐与声音", "音频策略与当前通路", R.drawable.ic_speaker),
            Item(CONNECTION, "连接与设备", "目标车辆、网络与权限", R.drawable.ic_wifi),
            Item(PERMISSIONS, "权限与后台", "首次授权与省电指引", R.drawable.ic_brightness),
            Item(ABOUT, "帮助与诊断", "入门说明、状态、日志、版本与许可", R.drawable.ic_menu_dock),
            Item(DISPLAY, "显示与画面", "分辨率、帧率、码率与画面边距", R.drawable.ic_menu_display),
            Item(CARLIFE, "CarLife", "引擎状态、无线连接提示、重连与诊断", R.drawable.ic_screen_rotation),
        )

    /**
     * 两端统一六个主分类，旧分类仍可通过原 ID 访问
     */
    val VISIBLE = listOf(CARLIFE, CONNECTION, DISPLAY, AUDIO, APPEARANCE, ABOUT).map { id -> ALL.first { it.id == id } }

    data class SearchEntry(
        val target: Int,
        val title: String,
        val description: String,
        val keywords: String,
    )

    /**
     * 搜索和快捷入口共用稳定目标，关键词只保存在本地
     */
    val SEARCH: List<SearchEntry> =
        VISIBLE.map {
            SearchEntry(
                it.id,
                it.title,
                it.desc,
                when (it.id) {
                    CARLIFE -> "引擎 端口 自检 重连 诊断 无线连接 TTS 播报 导航 GPS HUD 媒体"
                    CONNECTION -> "蓝牙 WiFi 热点 车机 MAC 网络 权限"
                    DISPLAY -> "清晰度 画质 分辨率 码率 帧率 安全区 边距 壁纸背景 圆角 fps dpi"
                    AUDIO -> "音质 兼容模式 声音 音频策略 音量"
                    APPEARANCE -> "壁纸 背景 圆角 车机外观 图片"
                    ABOUT -> "日志 导出 版本 免责声明 许可 帮助"
                    else -> ""
                },
            )
        } +
            listOf(
                SearchEntry(PERMISSIONS, "权限与后台", "蓝牙、通知与电池设置", "省电 自启动 授权 通知 蓝牙"),
            )

    /**
     * 按空白拆分关键词并匹配名称、说明和别名
     */
    fun search(query: String): List<SearchEntry> {
        val terms =
            query
                .trim()
                .lowercase(java.util.Locale.ROOT)
                .split(Regex("""\s+"""))
                .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return SEARCH
            .filter { entry ->
                val content = "${entry.title} ${entry.description} ${entry.keywords}".lowercase(java.util.Locale.ROOT)
                terms.all { it in content }
            }.distinctBy { it.target }
    }

    /**
     * 查找分组或二级面板，不依赖列表下标
     */
    fun itemOf(id: Int): Item = (ALL + PANELS).firstOrNull { it.id == id } ?: Item(id, "设置", "", R.drawable.ic_app_settings)

    fun titleOf(id: Int): String = itemOf(id).title

    /**
     * 手机分类只有一个功能时直接进入面板，所有手机入口复用同一规则
     */
    fun directPhonePanel(id: Int): Int? =
        when (id) {
            LAYOUT -> DISPLAY
            else -> null
        }

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            OFFICIAL_CAR_APPS -> OfficialCarAppsPanelFragment()
            SUPERSAMPLE -> SupersamplePanelFragment()
            ORIENTATION -> OrientationPanelFragment()
            DOCK -> DockPanelFragment()
            CAR_APPEARANCE -> CarAppearancePanelFragment()
            DISPLAY -> DisplayPanelFragment()
            else -> SettingsCategoryFragment().apply { arguments = android.os.Bundle().apply { putInt("category", id) } }
        }
}
