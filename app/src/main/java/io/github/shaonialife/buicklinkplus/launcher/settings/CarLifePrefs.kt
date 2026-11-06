package io.github.shaonialife.buicklinkplus.launcher.settings

import android.content.Context
import androidx.core.content.edit

/**
 * CarLife 内容源偏好，与其它设置共用 `settings` SharedPreferences。
 *
 * 键名是跨模块契约（carlife/audio/render 等读取），改名需同步所有消费方。
 * 布尔开关默认开启；安全区边距与圆角半径单位为 dp，0 表示关闭。
 */
object CarLifePrefs {
    private const val PREFS_NAME = "settings"

    /** CarLife 媒体音频进 CarPlay 音频通道（默认开）。 */
    const val KEY_AUDIO_ENABLED = "carlife_audio_enabled"

    /** CarLife TTS 导航播报混入音频通道（默认开）。 */
    const val KEY_TTS_ENABLED = "carlife_tts_enabled"

    /** 车机导航指引上行 0x5201/0x5202（默认开）。 */
    const val KEY_NAV_ENABLED = "carlife_nav_enabled"

    /** 画面合成使用壁纸背景（默认开；关闭时纯色底）。 */
    const val KEY_WALLPAPER_ENABLED = "carlife_wallpaper_enabled"

    /** 安全区内收边距，单位 dp，0..64。 */
    const val KEY_SAFE_MARGIN = "carlife_safe_margin"

    /** 画面圆角半径，单位 dp，0=关闭（UI 开关写入 12）。 */
    const val KEY_CORNER_RADIUS = "carlife_corner_radius"

    const val SAFE_MARGIN_MAX = 64
    const val CORNER_RADIUS_ON = 12
    const val CORNER_RADIUS_MAX = 32

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun audioEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUDIO_ENABLED, true)

    fun setAudioEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_AUDIO_ENABLED, enabled) }
    }

    fun ttsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TTS_ENABLED, true)

    fun setTtsEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_TTS_ENABLED, enabled) }
    }

    fun navEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NAV_ENABLED, true)

    fun setNavEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_NAV_ENABLED, enabled) }
    }

    fun wallpaperEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WALLPAPER_ENABLED, true)

    fun setWallpaperEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        prefs(context).edit { putBoolean(KEY_WALLPAPER_ENABLED, enabled) }
    }

    /** 安全区边距（dp，0..64）。 */
    fun safeMargin(context: Context): Int = prefs(context).getInt(KEY_SAFE_MARGIN, 0).coerceIn(0, SAFE_MARGIN_MAX)

    fun setSafeMargin(
        context: Context,
        dp: Int,
    ) {
        prefs(context).edit { putInt(KEY_SAFE_MARGIN, dp.coerceIn(0, SAFE_MARGIN_MAX)) }
    }

    /** 圆角半径（dp，0=关闭）。 */
    fun cornerRadius(context: Context): Int = prefs(context).getInt(KEY_CORNER_RADIUS, 0).coerceIn(0, CORNER_RADIUS_MAX)

    fun setCornerRadius(
        context: Context,
        dp: Int,
    ) {
        prefs(context).edit { putInt(KEY_CORNER_RADIUS, dp.coerceIn(0, CORNER_RADIUS_MAX)) }
    }

    /** 圆角开关（UI 语义：开=12dp，关=0）。 */
    fun cornerEnabled(context: Context): Boolean = cornerRadius(context) > 0

    fun setCornerEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        setCornerRadius(context, if (enabled) CORNER_RADIUS_ON else 0)
    }
}
