package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import io.github.shaonialife.buicklinkplus.R

/**
 * 车机共用的壁纸遮罩与模糊偏好，兼容原侧栏保存值
 */
object CarAppearancePrefs {
    private const val KEY_OPACITY = "dockScrimOpacity"
    private const val KEY_BLUR = "dockBlur"
    private const val KEY_PANEL_OUTLINE = "panelOutlineVisible"
    private const val KEY_MUSIC_ISLAND = "musicIslandVisible"
    private const val KEY_LEFT_WIDGETS = "leftWidgets"
    private const val KEY_PARKING_STARTED_AT = "parkingStartedAt"
    private const val KEY_WEATHER_CITY = "weatherCity"

    /**
     * 预设同时更新遮罩和模糊，避免界面短暂呈现不完整配置
     */
    enum class Preset(
        val title: String,
        val opacity: Int,
        val blur: Int,
    ) {
        TRANSPARENT("通透", 0, 0),
        FROSTED("磨砂", 38, 40),
        SOLID("实色", 100, 0),
    }

    /**
     * 沿用原偏好文件，不在读取时写入默认值
     */
    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * 背景遮罩不透明度，已有自定义值优先
     */
    fun opacity(context: Context): Int = prefs(context).getInt(KEY_OPACITY, Preset.FROSTED.opacity).coerceIn(0, 100)

    /** 界面透明度为遮罩不透明度的补数 */
    fun transparency(context: Context): Int = 100 - opacity(context)

    /**
     * 背景模糊百分比，零值保持原始壁纸
     */
    fun blur(context: Context): Int = prefs(context).getInt(KEY_BLUR, Preset.FROSTED.blur).coerceIn(0, 100)

    /** 独立轮廓开关，关闭后去掉描边、阴影、留白和圆角 */
    fun panelOutlineVisible(context: Context): Boolean = prefs(context).getBoolean(KEY_PANEL_OUTLINE, false)

    /**
     * 音乐岛显示开关，默认开启；关闭后合成器完全隐藏浮层
     */
    fun musicIslandVisible(context: Context): Boolean = prefs(context).getBoolean(KEY_MUSIC_ISLAND, true)

    fun setMusicIslandVisible(
        context: Context,
        visible: Boolean,
    ) {
        prefs(context).edit {
            putBoolean(KEY_MUSIC_ISLAND, visible)
        }
        UiAppearance.refreshWindows()
    }

    /** 左列组件选择，默认显示歌名、歌词和时间 */
    fun leftWidgets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LEFT_WIDGETS, null) ?: setOf("music", "lyrics", "clock")

    fun setLeftWidgets(
        context: Context,
        widgets: Set<String>,
    ) {
        prefs(context).edit {
            putStringSet(KEY_LEFT_WIDGETS, widgets)
        }
        UiAppearance.refreshWindows()
    }

    /** 停车计时起点（epoch 毫秒），0 = 未在计时 */
    fun parkingStartedAt(context: Context): Long = prefs(context).getLong(KEY_PARKING_STARTED_AT, 0L)

    fun setParkingStartedAt(
        context: Context,
        value: Long,
    ) {
        prefs(context).edit {
            putLong(KEY_PARKING_STARTED_AT, value)
        }
        UiAppearance.refreshWindows()
    }

    /** 天气位置：auto=当前定位，否则为 WeatherState.presetCities 城市名；默认定位 */
    fun weatherCity(context: Context): String = prefs(context).getString(KEY_WEATHER_CITY, "auto") ?: "auto"

    fun setWeatherCity(
        context: Context,
        city: String,
    ) {
        prefs(context).edit {
            putString(KEY_WEATHER_CITY, city)
        }
        UiAppearance.refreshWindows()
    }

    /**
     * 手机和车机共用轮廓开关，不覆盖用户的透明度或模糊配置
     */
    fun setPanelOutlineVisible(
        context: Context,
        visible: Boolean,
    ) {
        prefs(context).edit {
            putBoolean(KEY_PANEL_OUTLINE, visible)
        }
        UiAppearance.refreshWindows()
    }

    /**
     * 保存用户调整的透明度并通知所有外观宿主
     */
    fun setTransparency(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit {
            putInt(KEY_OPACITY, 100 - value.coerceIn(0, 100))
        }
        UiAppearance.refreshWindows()
    }

    /**
     * 保存背景模糊，不触发壁纸重新解码
     */
    fun setBlur(
        context: Context,
        value: Int,
    ) {
        prefs(context).edit {
            putInt(KEY_BLUR, value.coerceIn(0, 100))
        }
        UiAppearance.refreshWindows()
    }

    /**
     * 原子保存预设并仅发送一次外观通知
     */
    fun applyPreset(
        context: Context,
        preset: Preset,
    ) {
        prefs(context)
            .edit {
                putInt(KEY_OPACITY, preset.opacity)
                putInt(KEY_BLUR, preset.blur)
            }
        UiAppearance.refreshWindows()
    }

    /** 只模糊壁纸并叠一层遮罩，文字、图标和地图保持清晰 */
    fun applyBackdrop(
        image: ImageView,
        scrim: View,
        scale: Float,
    ) {
        val context = image.context
        val color = ColorUtils.setAlphaComponent(UiAppearance.color(context, R.color.m3_surface), opacity(context) * 255 / 100)
        if ((scrim.background as? android.graphics.drawable.ColorDrawable)?.color != color) scrim.setBackgroundColor(color)
        val radius = blur(context) / 100f * 40f * scale.coerceAtLeast(0.1f)
        if (image.getTag(R.id.carBackdropBlurRadius) != radius) {
            image.setRenderEffect(if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
            image.setTag(R.id.carBackdropBlurRadius, radius)
        }
    }
}
