package io.github.shaonialife.buicklinkplus.bt

import android.content.Context
import androidx.core.content.edit

/** 按设备保存连接策略，避免把某一款车机的断连经验用于所有车机 */
object BtConnectionPrefs {
    private const val PREFS = "bt_connection_policy"

    /** 保留连接用于首次兼容测试，仅配对策略由用户明确选择 */
    enum class Mode(
        val title: String,
    ) {
        KEEP_CONNECTED("保留通话和媒体连接"),
        PAIR_ONLY("连接前断开通话和媒体"),
    }

    /** 未指定目标时使用自动模式配置，设备配置互不影响 */
    fun get(
        context: Context,
        address: String?,
    ): Mode {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(address), null)
        return Mode.entries.firstOrNull { it.name == value } ?: Mode.KEEP_CONNECTED
    }

    /** 保存后在下一次连接时生效，不中断正在使用的控制通道 */
    fun set(
        context: Context,
        address: String?,
        mode: Mode,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit {
                putString(key(address), mode.name)
            }
    }

    /** 蓝牙地址不区分大小写，自动模式使用独立键 */
    private fun key(address: String?): String = address?.uppercase(java.util.Locale.ROOT) ?: "auto"
}
