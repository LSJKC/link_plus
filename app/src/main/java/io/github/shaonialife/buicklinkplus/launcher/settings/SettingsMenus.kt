package io.github.shaonialife.buicklinkplus.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.buicklinkplus.R

/**
 * 手机与车机共用的设置菜单。
 *
 * CarLife 成为唯一内容源后，link_plus 原桌面相关面板（桌面应用/超采样/横屏/侧栏/
 * 地图分屏/官方车载应用/仪表盘音乐组件）全部下线，只保留六类页面：
 * 连接与设备、显示与画面、音频、CarLife、外观与壁纸、日志与关于。
 */
object SettingsMenus {
    // ---- 六类页面 ----
    const val CONNECTION = 105
    const val DISPLAY = 5
    const val AUDIO = 104
    const val CARLIFE = 9
    const val APPEARANCE = 100
    const val ABOUT = 107

    /** 连接与设备下的二级页面：权限与后台。 */
    const val PERMISSIONS = 106

    // ---- 已下线的 link_plus 桌面页 ID（仅遗留死代码引用，勿重新加入菜单）----
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DEFAULT_MAP = 4
    const val CAR_APPEARANCE = 6
    const val OFFICIAL_CAR_APPS = 7
    const val DASHBOARD_WIDGET = 8
    const val APPS = 101
    const val LAYOUT = 102
    const val MAP = 103

    data class Item(
        val id: Int,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    /** 六类页面的顺序与文案。 */
    val VISIBLE =
        listOf(
            Item(CONNECTION, "连接与设备", "目标车机、网络、本机蓝牙地址", R.drawable.ic_wifi),
            Item(DISPLAY, "显示与画面", "分辨率、帧率、码率、安全区、圆角", R.drawable.ic_menu_display),
            Item(AUDIO, "音频", "CarLife 音源、TTS 播报、音量与通道状态", R.drawable.ic_speaker),
            Item(CARLIFE, "CarLife", "引擎连接、端口自检、重连与诊断", R.drawable.ic_screen_rotation),
            Item(APPEARANCE, "外观与壁纸", "内置壁纸与壁纸编辑器", R.drawable.ic_wallpaper),
            Item(ABOUT, "日志与关于", "日志导出、版本与免责声明", R.drawable.ic_doc),
        )

    /**
     * 二级菜单列表：CarLife 版本只保留六类页面。
     */
    val PANELS: List<Item> = VISIBLE

    /**
     * 内嵌页面（手机首页设置里由 MainActivity 承载）。
     */
    val ALL: List<Item> = VISIBLE + listOf(Item(PERMISSIONS, "权限与后台", "运行时权限与省电指引", R.drawable.ic_brightness))

    data class SearchEntry(
        val target: Int,
        val title: String,
        val description: String,
        val keywords: String,
    )

    /** 搜索和快捷入口共用稳定目标，关键词只保存在本地 */
    val SEARCH: List<SearchEntry> =
        ALL.map {
            SearchEntry(
                it.id,
                it.title,
                it.desc,
                when (it.id) {
                    CONNECTION -> "蓝牙 WiFi 热点 车机 MAC 地址 网络"
                    DISPLAY -> "清晰度 画质 分辨率 码率 帧率 安全区 圆角 壁纸背景 fps dpi"
                    AUDIO -> "声音 音源 TTS 导航播报 音量 通道"
                    CARLIFE -> "引擎 端口 自检 重连 诊断 7240 8240"
                    APPEARANCE -> "壁纸 背景 图片"
                    ABOUT -> "日志 导出 版本 免责声明 许可"
                    else -> "权限 授权 省电 自启动"
                },
            )
        }

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
    fun itemOf(id: Int): Item = (ALL + PANELS).first { it.id == id }

    fun titleOf(id: Int): String = itemOf(id).title

    /**
     * 手机内容宿主直接取页面 ID；保留旧调用点（LAYOUT/MAP 均已下线）。
     */
    fun directPhonePanel(id: Int): Int? = null

    fun createFragment(id: Int): Fragment =
        when (id) {
            DISPLAY -> DisplayPanelFragment()
            else -> SettingsCategoryFragment().apply { arguments = android.os.Bundle().apply { putInt("category", id) } }
        }
}
