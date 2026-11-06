package io.github.shaonialife.buicklinkplus.launcher

import android.content.Context
import androidx.core.content.edit

/**
 * 侧栏尺寸偏好，背景统一由车机外观管理
 */
object DockPrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_WIDTH = "dockWidthPercent"

    /** 侧栏宽度缩放为 50～100%，步进 5%；尺寸和密度同时缩放，下次连接生效 */
    const val WIDTH_MIN = 50
    const val WIDTH_MAX = 100
    const val WIDTH_STEP = 5
    const val WIDTH_DEFAULT = 100

    /**
     * 读取原来的侧栏比例，初始化不写入偏好
     */
    fun widthPercent(context: Context): Int =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_WIDTH, WIDTH_DEFAULT)
            .coerceIn(WIDTH_MIN, WIDTH_MAX)

    /**
     * 只保存用户操作，下次创建侧栏显示屏时生效
     */
    fun setWidthPercent(
        context: Context,
        percent: Int,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putInt(KEY_WIDTH, percent.coerceIn(WIDTH_MIN, WIDTH_MAX))
            }
    }
}
