package io.github.shaonialife.buicklinkplus.compat

/**
 * 内置无线 ADB 激活已按需求整体移除；这些仅为遗留调用点的降级占位，不执行任何 ADB/shell/root 操作。
 *
 * 内置 shell 服务（原 shizuku 包内的 Shell）已随内置 ADB 激活一并删除。
 * 保留同名入口只为让既有调用点继续编译并安全降级：任何命令都返回失败，
 * 调用方应把失败当作“无 shell 能力”处理，不得据此伪造成功状态。
 */
object Shell {
    /** 保留旧调用点使用的命令结果格式。 */
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    /** 兼容占位：不再拦截主线程 shell 调用，恒为 0。 */
    @Volatile
    var mainThreadViolations: Int = 0
        private set

    /** 保留日志出口以兼容上游赋值；主线程告警不会再产生。 */
    @Volatile
    var violationSink: ((String) -> Unit)? = null

    /** 兼容占位：shell 已随内置无线 ADB 激活一起移除，任何命令都失败。 */
    @Suppress("UNUSED_PARAMETER")
    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result = Result(-1, "内置无线 ADB 激活已移除，shell 不可用")
}
