package io.github.shaonialife.buicklinkplus.launcher

import android.content.ComponentName
import android.content.Context
import androidx.core.content.edit

/**
 * 保存默认地图，未设置时按优先级选择已安装应用
 * 解析只走 [resolveDefaultMap]，不回调 VirtualLauncher，避免递归
 */
object DefaultMapPrefs {
    private const val PREFS_NAME = "settings"

    /** 地图内容比例独立设置，默认 75% */
    fun contentScale(context: Context): Int = prefs(context).getInt("map_content_scale", 75).coerceIn(50, 100)

    /** 按 5% 步进保存比例，后台调整显示尺寸 */
    fun setContentScale(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit {
            putInt("map_content_scale", (value.coerceIn(50, 100) / 5) * 5)
        }
    }

    private const val KEY_DEFAULT_MAP = "defaultMapPackage"
    private const val KEY_AMAP_CAR_PAGE = "amap_car_page"

    /** 连接时读取高德模式，默认车载页面，导航中不自动切换 */
    fun amapCarPage(context: Context): Boolean = prefs(context).getBoolean(KEY_AMAP_CAR_PAGE, true)

    /**
     * 保存高德显示模式，下次连接生效
     */
    fun setAmapCarPage(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit {
            putBoolean(KEY_AMAP_CAR_PAGE, enabled)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 用户显式设置的默认地图包名（null = 未设置，走优先序）。 */
    fun manualPackage(context: Context): String? = prefs(context).getString(KEY_DEFAULT_MAP, null)

    /** 当前生效的默认地图（显式设置优先；否则按优先序取第一个已安装的）。 */
    fun resolve(context: Context): ComponentName? =
        resolveDefaultMap(
            manual = manualPackage(context),
            priority = VirtualLauncher.MAP_PACKAGES_PRIORITY,
            componentFor = { pkg -> launchComponent(context, pkg) },
        )

    /** 该包的可启动 Activity（未安装 / 无启动入口 → null）。 */
    private fun launchComponent(
        context: Context,
        pkg: String,
    ): ComponentName? {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val activity = context.packageManager.resolveActivity(launch, 0)?.activityInfo ?: return null
        // resolveActivity 在无匹配时可能给出其它包的兜底结果：只接受包名一致者，
        // 否则「已卸载」的显式设置会被误判为可用。
        if (activity.packageName != pkg) return null
        return ComponentName(pkg, activity.name)
    }

    fun set(
        context: Context,
        packageName: String?,
    ) {
        if (packageName == null) {
            prefs(context).edit {
                remove(KEY_DEFAULT_MAP)
            }
        } else {
            prefs(context).edit {
                putString(KEY_DEFAULT_MAP, packageName)
            }
        }
    }
}

/**
 * 优先解析显式地图选择，再按优先级扫描
 * 只调用传入的 [componentFor]，不能回调 VirtualLauncher.defaultMapComponent
 */
internal fun <T> resolveDefaultMap(
    manual: String?,
    priority: List<String>,
    componentFor: (String) -> T?,
): T? {
    // 显式设置优先；其应用已卸载时自然回落到优先序
    manual?.let { pkg -> componentFor(pkg)?.let { return it } }
    priority.forEach { pkg -> componentFor(pkg)?.let { return it } }
    return null
}
