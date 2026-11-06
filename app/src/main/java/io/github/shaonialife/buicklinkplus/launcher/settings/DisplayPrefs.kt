package io.github.shaonialife.buicklinkplus.launcher.settings

import android.content.Context
import androidx.core.content.edit

/**
 * 显示设置：分辨率缩放下次连接生效，帧率可实时调整
 * 当前生效值由 SessionController 保存，与用户偏好分开
 */
object DisplayPrefs {
    const val SCALE_MIN = 30
    const val SCALE_MAX = 160
    const val SCALE_STEP = 5
    const val SCALE_DEFAULT = 100
    val SCALE_PRESETS = listOf(75, 100, 125, 150)

    const val FPS_DEFAULT = 30

    /** 触摸 move 注入节流默认值（ms）：0=默认关闭，交互滞后时在「显示」面板调高试验。 */
    const val MOVE_THROTTLE_DEFAULT = 0
    const val MOVE_THROTTLE_MAX = 48

    private const val PREFS_NAME = "settings"
    private const val KEY_SCALE = "display_scale_percent"
    private const val KEY_FPS = "display_fps"
    private const val KEY_MOVE_THROTTLE = "move_throttle_ms"

    /** 流分辨率缩放百分比（30–160，步进 5；默认 100）。 */
    fun scalePercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCALE, SCALE_DEFAULT)
            .coerceIn(SCALE_MIN, SCALE_MAX)

    fun setScalePercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_SCALE, percent.coerceIn(SCALE_MIN, SCALE_MAX))
            }
    }

    /** 目标帧率（30/60；驱动合成器出帧节奏，运行中切换实时生效）。 */
    fun fps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_FPS, FPS_DEFAULT)

    fun setFps(
        context: Context,
        fps: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_FPS, if (fps >= 60) 60 else 30)
            }
    }

    /** 触摸 move 注入节流（ms；0=关闭）。 */
    fun moveThrottleMs(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MOVE_THROTTLE, MOVE_THROTTLE_DEFAULT)
            .coerceIn(0, MOVE_THROTTLE_MAX)

    fun setMoveThrottleMs(
        context: Context,
        ms: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_MOVE_THROTTLE, ms.coerceIn(0, MOVE_THROTTLE_MAX))
            }
    }

    /** 视频码率档位，零表示按像素和帧率自动计算 */
    fun bitrateMbps(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("display_bitrate_mbps", 0)
            .takeIf { it in listOf(8, 12, 16, 20) } ?: 0

    fun setBitrateMbps(
        context: Context,
        value: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt("display_bitrate_mbps", value.takeIf { it in listOf(8, 12, 16, 20) } ?: 0)
            }
    }

    /** 安全区内收边距，单位为原始投屏像素，顺序为左上右下 */
    fun safeMargins(context: Context): List<Int> =
        listOf("left", "top", "right", "bottom").map {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt("safe_margin_$it", 0).coerceIn(0, 120)
        }

    fun setSafeMargin(
        context: Context,
        side: String,
        value: Int,
    ) {
        require(side in listOf("left", "top", "right", "bottom"))
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt("safe_margin_$side", value.coerceIn(0, 120))
            }
    }
}
