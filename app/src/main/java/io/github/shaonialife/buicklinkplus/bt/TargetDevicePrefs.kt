package io.github.shaonialife.buicklinkplus.bt

import android.content.Context
import androidx.core.content.edit

/**
 * 保存目标车机的地址和名称，连接和回连只接受该设备
 * 地址忽略大小写用于匹配，名称快照仅用于显示
 */
object TargetDevicePrefs {
    private const val PREFS = "target_device"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Target(
        val address: String,
        val name: String,
    )

    /** null = 未指定（自动：出站尝试全部候选、入站接受任一设备）。 */
    fun get(context: Context): Target? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = prefs.getString(KEY_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
        return Target(address = address, name = prefs.getString(KEY_NAME, null) ?: address)
    }

    fun set(
        context: Context,
        target: Target?,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (target == null) {
            prefs.edit {
                clear()
            }
        } else {
            prefs
                .edit {
                    putString(KEY_ADDRESS, target.address)
                    putString(KEY_NAME, target.name)
                }
        }
    }

    fun matches(
        target: Target,
        address: String?,
    ): Boolean = address != null && address.equals(target.address, ignoreCase = true)
}
