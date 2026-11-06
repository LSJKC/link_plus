package io.github.shaonialife.buicklinkplus.media

import android.content.ComponentName
import android.content.Context

/**
 * 查询并提示通知使用权（媒体会话枚举）。
 *
 * 内置 shell 服务已随无线 ADB 激活移除，应用不再能静默执行 `cmd notification allow_listener`，
 * 因此这里只依据监听器自身回调的状态报告结果，并提示用户到系统设置手动授权；
 * 不改动系统设置，也不执行任何 ADB/shell/root 操作。
 */
object NotificationAccess {
    /** 进程内上一次确认到的授权状态（服务 onListenerConnected 也会更新它）。 */
    @Volatile
    var granted: Boolean = false
        private set

    /** 服务连接回调里调用：系统已认可授权。 */
    internal fun markGranted() {
        granted = true
    }

    /** 通知监听器的包/类组件名，供系统设置页与诊断展示使用 */
    fun componentSpec(context: Context): String = ComponentName(context, MediaListenerService::class.java).flattenToString()

    /** 按冒号拆分授权列表后精确匹配组件，避免包名前缀误判 */
    internal fun isGrantedIn(
        enabledListeners: String?,
        componentSpec: String,
    ): Boolean =
        enabledListeners
            ?.split(':')
            ?.any { it.trim().equals(componentSpec, ignoreCase = true) } == true

    /**
     * 在后台确保通知使用权已授予，支持重复调用
     *
     * 内置授权通道已移除：只返回监听器连接状态，未连接时提示用户在系统设置手动授予。
     *
     * @return 当前是否已授权
     */
    fun ensureGranted(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        if (serviceConnected) {
            granted = true
            return true
        }
        val spec = componentSpec(context)
        log(
            "MEDIA: 通知使用权需在系统设置「通知使用权」中手动授予 " +
                "（$spec）；未授予时音乐卡只显示播放状态、无歌名",
        )
        return granted
    }

    /** 只在 dumpsys notification 的 Live listeners 段检查组件是否连接 */
    internal fun isLiveIn(
        dump: String,
        componentSpec: String,
    ): Boolean {
        val start = dump.indexOf("Live notification listeners")
        if (start < 0) return false
        val section = dump.substring(start).substringBefore("Snoozed notification listeners")
        return section.contains(componentSpec, ignoreCase = true)
    }

    /** 服务连上时置位（[MediaListenerService.onListenerConnected] 调用）。 */
    @Volatile
    private var serviceConnected: Boolean = false

    internal fun markConnected() {
        serviceConnected = true
    }

    internal fun markDisconnected() {
        serviceConnected = false
    }
}
