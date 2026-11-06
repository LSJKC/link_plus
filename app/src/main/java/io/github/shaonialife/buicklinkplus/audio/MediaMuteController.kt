package io.github.shaonialife.buicklinkplus.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import io.github.shaonialife.buicklinkplus.util.Bg

/** 音量原值只保存一次，重连后旧会话不恢复新会话音量 */
internal class MediaMuteLease {
    data class Snapshot(
        val volume: Int,
        val muted: Boolean,
    )

    @Volatile
    var owner: Any? = null
        private set
    var snapshot: Snapshot? = null
        private set

    fun acquire(
        token: Any,
        original: Snapshot,
        active: Boolean = true,
    ): Boolean {
        if (!active) return false
        if (snapshot == null) snapshot = original
        owner = token
        return true
    }

    fun release(token: Any): Snapshot? {
        if (owner !== token) return null
        owner = null
        return snapshot.also { snapshot = null }
    }
}

/** 采集成功后才静音，保留异常退出后的恢复记录 */
internal object MediaMuteController {
    private const val VOLUME = "audio_saved_media_volume"
    private const val MUTED = "audio_saved_media_muted"
    private val lease = MediaMuteLease()
    private var monitor: Thread? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private var shellAttempted = false

    @Volatile
    private var message = ""

    fun status(token: Any): String = if (lease.owner === token) message else ""

    @Synchronized
    // 后台保存原音量并确认落盘后才修改系统音量，必须保留 commit 返回值
    @android.annotation.SuppressLint("UseKtx")
    fun acquire(
        context: Context,
        token: Any,
        log: (String) -> Unit,
        isActive: () -> Boolean,
    ) {
        if (!isActive()) return
        val ctx = context.applicationContext
        val manager = checkNotNull(ctx.getSystemService(AudioManager::class.java)) { "音频服务不可用" }
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val snapshot =
            MediaMuteLease.Snapshot(
                if (prefs.contains(VOLUME)) {
                    prefs.getInt(VOLUME, 0).coerceAtLeast(0)
                } else {
                    manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                },
                if (prefs.contains(MUTED)) prefs.getBoolean(MUTED, false) else manager.isStreamMute(AudioManager.STREAM_MUSIC),
            )
        // 取得全局锁之前旧采集可能已停止，提交所有权时必须再次检查
        if (!lease.acquire(token, snapshot, isActive())) return
        val original = checkNotNull(lease.snapshot)
        if (!prefs
                .edit()
                .putInt(VOLUME, original.volume)
                .putBoolean(MUTED, original.muted)
                .commit()
        ) {
            message = "手机静音失败：原音量未能保存"
            log("AUDIO: " + message)
            return
        }
        monitor?.interrupt()
        deviceCallback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        shellAttempted = false
        val callback =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    Bg.run("media-mute-device") { verify(ctx, token, log) }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    Bg.run("media-mute-device") { verify(ctx, token, log) }
                }
            }
        deviceCallback = callback
        runCatching { manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
        verify(ctx, token, log)
        monitor =
            Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        Thread.sleep(1000L)
                        synchronized(this) { if (lease.owner !== token) return@Thread }
                        verify(ctx, token, log)
                    }
                } catch (_: InterruptedException) {
                    // 会话退出或所有权转移时停止巡检
                }
            }, "media-mute-watch").apply {
                isDaemon = true
                start()
            }
    }

    @Synchronized
    private fun verify(
        context: Context,
        token: Any,
        log: (String) -> Unit,
    ) {
        if (lease.owner !== token) return
        val manager =
            context.getSystemService(AudioManager::class.java) ?: run {
                message = "手机静音失败：音频服务不可用"
                log("AUDIO: " + message)
                return
            }

        fun silent(): Boolean = manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 || manager.isStreamMute(AudioManager.STREAM_MUSIC)
        var ok =
            runCatching {
                if (!silent()) manager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                silent()
            }.getOrDefault(false)
        if (!ok && !shellAttempted) {
            shellAttempted = true
            val result = Shell.exec("cmd media_session volume --stream 3 --set 0", timeoutMs = 2000)
            ok = runCatching { silent() }.getOrDefault(false)
            log("AUDIO: 内置服务补设媒体静音 exit=" + result.exitCode + " 回读=" + ok)
        }
        val next = if (ok) "手机媒体已静音" else "手机静音失败，请检查手机媒体输出"
        if (next != message) log("AUDIO: " + next)
        message = next
    }

    @Synchronized
    fun release(
        context: Context,
        token: Any,
        log: (String) -> Unit,
    ) {
        val original = lease.release(token) ?: return
        monitor?.interrupt()
        monitor = null
        val manager = context.getSystemService(AudioManager::class.java)
        deviceCallback?.let { runCatching { manager?.unregisterAudioDeviceCallback(it) } }
        deviceCallback = null
        restore(context, original, log)
        message = ""
    }

    /** 进程重启且无新会话时恢复音量，失败后保留记录重试 */
    @Synchronized
    fun recover(
        context: Context,
        log: (String) -> Unit,
    ) {
        if (lease.owner != null) return
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains(VOLUME)) return
        restore(context, MediaMuteLease.Snapshot(prefs.getInt(VOLUME, 0).coerceAtLeast(0), prefs.getBoolean(MUTED, false)), log)
    }

    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    private fun restore(
        context: Context,
        original: MediaMuteLease.Snapshot,
        log: (String) -> Unit,
    ) {
        var target = original.volume
        var actualVolume: Int? = null
        var actualMuted: Boolean? = null
        var failure: String? = null
        val ok =
            runCatching {
                val manager = checkNotNull(context.getSystemService(AudioManager::class.java)) { "音频服务不可用" }
                target = original.volume.coerceAtMost(manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                // 已静音流的公开音量会读到零，不能用这个零覆盖系统保存的非静音音量
                if (!original.muted) manager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                // 部分系统设置零音量会同时静音，最后恢复静音位才能保留原来的零音量状态
                manager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (original.muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
                var restored = false
                for (delay in listOf(0L, 150L, 350L)) {
                    if (delay > 0) Thread.sleep(delay)
                    actualVolume = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    actualMuted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
                    restored = MediaVolumeRestorePolicy.matches(original.muted, target, actualVolume, actualMuted)
                    if (restored) break
                }
                restored
            }.onFailure {
                failure = "${it.javaClass.simpleName}: ${it.message}"
                if (it is InterruptedException) Thread.currentThread().interrupt()
            }.getOrDefault(false)
        var recordCleared = false
        if (ok) {
            recordCleared =
                context
                    .getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit()
                    .remove(VOLUME)
                    .remove(MUTED)
                    .commit()
            if (!recordCleared) {
                // commit 失败也可能已修改内存，补回恢复值供本进程后续恢复使用
                context
                    .getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit()
                    .putInt(VOLUME, original.volume)
                    .putBoolean(MUTED, original.muted)
                    .commit()
                failure = "音量已恢复，但原状态记录未能清除"
            }
        }
        log(
            "AUDIO: 手机音量恢复=${if (ok) "已确认" else "失败，已保留原状态记录"} " +
                "目标音量=$target 原静音=${original.muted} 实际音量=$actualVolume 实际静音=$actualMuted 记录清除=$recordCleared 异常=$failure",
        )
    }
}
