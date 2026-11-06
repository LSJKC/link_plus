package io.github.shaonialife.buicklinkplus.audio

/** 静音流公开音量可能为零，恢复成功必须按原静音语义分别判断 */
internal object MediaVolumeRestorePolicy {
    fun matches(
        originalMuted: Boolean,
        target: Int,
        actualVolume: Int,
        actualMuted: Boolean,
    ): Boolean = if (originalMuted) actualMuted else !actualMuted && actualVolume == target
}
