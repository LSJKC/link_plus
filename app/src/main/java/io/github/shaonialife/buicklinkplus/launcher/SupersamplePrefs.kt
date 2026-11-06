package io.github.shaonialife.buicklinkplus.launcher

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit

/**
 * 每个应用的超采样偏好：自动、开启、关闭
 * 自动模式根据启动 Activity 的方向识别竖屏应用，显式选择优先
 */
object SupersamplePrefs {
    const val MODE_AUTO = 0
    const val MODE_ON = 1
    const val MODE_OFF = 2

    private const val PREFS_NAME = "settings"
    private const val KEY_PREFIX = "ss_"

    /** 读偏好（默认自动）。 */
    fun mode(
        context: Context,
        component: android.content.ComponentName,
    ): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREFIX + component.flattenToString(), MODE_AUTO)

    fun setMode(
        context: Context,
        component: android.content.ComponentName,
        mode: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_PREFIX + component.flattenToString(), mode)
            }
    }

    /** 本次启动是否走超采样应用屏（三态解析后的最终结果）。 */
    fun shouldSupersample(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        resolveMode(mode(context, component), OfficialCarApps.find(component.packageName)?.independentCar == true) {
            isPortraitActivity(context, component)
        }

    /** 自动模式下独立车载版优先原生屏，用户显式设置优先 */
    fun resolveMode(
        mode: Int,
        isCarApp: Boolean,
        isPortrait: () -> Boolean,
    ): Boolean =
        when (mode) {
            MODE_ON -> true
            MODE_OFF -> false
            else -> !isCarApp && isPortrait()
        }

    /** 自动检测：启动 Activity 是否竖屏锁定（在横屏应用屏上会被 letterbox 的形态）。 */
    fun isPortraitActivity(
        context: Context,
        component: android.content.ComponentName,
    ): Boolean =
        try {
            val info = context.packageManager.getActivityInfo(component, 0)
            when (info.screenOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT,
                -> true

                else -> false
            }
        } catch (_: Exception) {
            false
        }
}
