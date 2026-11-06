package io.github.shaonialife.buicklinkplus.launcher

import android.content.Context
import androidx.core.content.edit

/** 保存桌面应用选择；首次只显示地图，设置后以用户选择为准，包括全部取消 */
object DesktopApps {
    private const val PREFS_NAME = "settings"
    private const val KEY_SELECTED = "desktopApps"

    /** 默认勾选的地图应用包名。 */
    private val MAP_PACKAGES =
        setOf(
            "com.autonavi.amapauto", // 高德地图车机版
            "com.autonavi.minimap", // 高德地图
            "com.baidu.BaiduMap", // 百度地图
            "com.tencent.map", // 腾讯地图
        )

    /** 当前应显示在桌面上的应用（component flattenToString 集合）。 */
    fun selected(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_SELECTED)) {
            return apps
                .filter { it.component.packageName in MAP_PACKAGES }
                .map { it.component.flattenToString() }
                .toSet()
        }
        return prefs.getStringSet(KEY_SELECTED, emptySet()).orEmpty().toSet()
    }

    fun save(
        context: Context,
        selection: Set<String>,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putStringSet(KEY_SELECTED, selection.toMutableSet())
            }
    }
}
