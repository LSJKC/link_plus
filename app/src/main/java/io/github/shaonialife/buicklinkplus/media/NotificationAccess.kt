package io.github.shaonialife.buicklinkplus.media

import android.content.ComponentName
import android.content.Context
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import java.util.concurrent.atomic.AtomicBoolean

/** 查询和补授通知使用权，内置服务就绪后可重复调用，已授权则跳过 */
object NotificationAccess {
    /** 进程内上一次确认到的授权状态（服务 onListenerConnected 也会更新它）。 */
    @Volatile
    var granted: Boolean = false
        private set

    private val granting = AtomicBoolean(false)

    /** 服务连接回调里调用：系统已认可授权，无需再跑 shell 探测。 */
    internal fun markGranted() {
        granted = true
    }

    /** 通知监听器的包/类组件名，供 allow_listener 使用 */
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
     * @return 当前是否已授权
     */
    fun ensureGranted(
        context: Context,
        log: (String) -> Unit,
    ): Boolean {
        val spec = componentSpec(context)
        val current =
            Shell
                .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                .output
        if (isGrantedIn(current, spec)) {
            granted = true
            // 有授权不代表系统已绑定监听器，绑定丢失时重新绑定一次
            rebindIfNotConnected(context, spec, log)
            return true
        }
        if (!granting.compareAndSet(false, true)) return granted
        try {
            val result = Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // 以短轮询读回的授权状态为准，命令退出码和立即读值都可能不准
            var ok = false
            for (attempt in 0 until 4) {
                if (attempt > 0) Thread.sleep(250)
                val after =
                    Shell
                        .exec("settings get secure enabled_notification_listeners", timeoutMs = 4_000)
                        .output
                if (isGrantedIn(after, spec)) {
                    ok = true
                    break
                }
            }
            granted = ok
            log(
                if (ok) {
                    "MEDIA: 通知使用权已授予（音乐卡可显示歌名/播放状态）"
                } else {
                    "MEDIA: 通知使用权未授予（cmd 结果=${result.exitCode}）——" +
                        "音乐卡只显示播放状态、无歌名；可在系统设置「通知使用权」手动开启"
                },
            )
            return ok
        } finally {
            granting.set(false)
        }
    }

    /** 已授权但未连接时，通过 disallow 和 allow 重新绑定监听器 */
    private fun rebindIfNotConnected(
        context: Context,
        spec: String,
        log: (String) -> Unit,
    ) {
        // 服务已连上（onListenerConnected 置位）就无需探测，避免每次会话建立都跑 dumpsys。
        if (serviceConnected) return
        if (!rebinding.compareAndSet(false, true)) return
        try {
            val live = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
            if (isLiveIn(live, spec)) {
                serviceConnected = true
                return
            }
            log("MEDIA: 通知使用权在列表里但监听器未绑定——触发一次重绑")
            Shell.exec("cmd notification disallow_listener $spec", timeoutMs = 4_000)
            Thread.sleep(300)
            Shell.exec("cmd notification allow_listener $spec", timeoutMs = 4_000)
            // 绑定是异步的：短轮询确认，避免下游继续拿着「已授权但读不到」的状态
            for (attempt in 0 until 6) {
                Thread.sleep(400)
                val after = Shell.exec("dumpsys notification --noredact", timeoutMs = 6_000).output
                if (isLiveIn(after, spec)) {
                    serviceConnected = true
                    log("MEDIA: 监听器已重新绑定（音乐卡元数据恢复）")
                    return
                }
            }
            log("MEDIA: ⚠ 重绑后监听器仍未连上——音乐卡可能只显示播放状态")
        } catch (_: Exception) {
            // shell 受限/超时：不影响主流程，下次会话再试
        } finally {
            rebinding.set(false)
        }
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

    private val rebinding = AtomicBoolean(false)
}
