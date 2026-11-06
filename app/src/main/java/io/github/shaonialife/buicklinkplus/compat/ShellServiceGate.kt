package io.github.shaonialife.buicklinkplus.compat

import android.content.Context

/**
 * 内置无线 ADB 激活已按需求整体移除；这些仅为遗留调用点的降级占位，不执行任何 ADB/shell/root 操作。
 *
 * 原 shizuku 包内的服务状态机（配对/激活/输入检查）已随内置 ADB 激活一并删除。
 * 本版本不需要开发者选项或无线调试，状态恒为 [ShellServiceGate.State.STOPPED]，
 * 指引只说明激活已移除，不再提供可执行操作。
 */
object ShellServiceGate {
    /** 保留旧状态枚举，避免遗留调用点引用缺失。 */
    enum class State { NEEDS_PAIRING, STOPPED, ACTIVATING, INPUT_UNAVAILABLE, READY }

    /** 兼容占位：内置服务已移除，状态恒为 [State.STOPPED]。 */
    @Suppress("UNUSED_PARAMETER")
    fun state(context: Context): State = State.STOPPED

    /** 兼容占位：无论旧状态如何，指引都说明本版本不需要激活。 */
    @Suppress("UNUSED_PARAMETER")
    internal fun guidanceOf(state: State): ShellServiceGuidance =
        ShellServiceGuidance(
            title = "内置无线 ADB 激活已移除",
            message = "本版本不需要开发者选项/无线调试，也不执行任何 shell/root 操作；相关能力已改用 Android 原生方案或移除。",
            primaryLabel = null,
        )
}

/** 兼容旧界面的服务状态说明；内置激活移除后不再提供操作入口。 */
internal data class ShellServiceGuidance(
    /** 当前状态标题 */
    val title: String,
    /** 当前状态的操作说明 */
    val message: String,
    /** 可执行的主操作标题；移除后恒为 null */
    val primaryLabel: String?,
)
