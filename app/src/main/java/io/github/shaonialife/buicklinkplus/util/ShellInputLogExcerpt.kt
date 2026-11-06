package io.github.shaonialife.buicklinkplus.util

/** 只提取最后一次本包启动后的失败字段，不导出异常正文 */
internal object ShellInputLogExcerpt {
    const val MAX_BYTES = 65_536

    // DateFormat 不识别毫秒 S，旧日志末尾的 SSS 是字面量
    private const val TIME_PATTERN = """\d{2}:\d{2}:\d{2}\.(?:\d{3}|SSS)"""
    private val startup = Regex("""^($TIME_PATTERN) service start: pkg=([A-Za-z0-9_.]+) lastUpdate=(\d+) pid=(\d+)$""")
    private val failure = Regex("""^($TIME_PATTERN) 输入注入(被拒|异常) display=(-?\d+) action=(\d+)\b""")
    private val exceptionType = Regex("""\b(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*(?:Exception|Error)\b""")

    /** 启动标记不完整或最后属于其他包时，不提取历史故障 */
    fun extract(
        text: String,
        packageName: String,
    ): String {
        val lines = text.takeLast(MAX_BYTES).lines()
        val start = lines.indexOfLast { startup.matches(it) }
        if (start < 0) return "未提取：有界尾部缺少完整 shell 启动标记，无法确认归属"
        val owner = checkNotNull(startup.matchEntire(lines[start])).groupValues
        if (owner[2] != packageName) return "未提取：末次 shell 启动属于其他安装包"
        val failures =
            lines
                .drop(start + 1)
                .mapNotNull { line ->
                    val fields = failure.find(line)?.groupValues ?: return@mapNotNull null
                    val reason = line.substringAfter(" 原因=", "")
                    val type = exceptionType.find(reason)?.value ?: "未知"
                    "时间=${fields[1]} 输入注入${fields[2]} display=${fields[3]} action=${fields[4]}" +
                        if (fields[2] == "异常") {
                            " 原因类型=$type 权限标记=${if (reason.contains("INJECT_EVENTS")) "INJECT_EVENTS" else "未记录"}"
                        } else {
                            " 返回=false"
                        }
                }.takeLast(32)
        return buildString {
            appendLine("末次 shell 启动=${owner[1]} 包=${owner[2]} pid=${owner[4]} 安装更新时间=${owner[3]}")
            appendLine("以下为共享文件的历史片段；仅时分秒，可能跨日期或投屏会话，不能代替故障时刻系统快照")
            appendLine("时间后缀 SSS 为旧日志格式字面值，不能解释成毫秒")
            appendLine("失败行未带 PID；其他存活服务可能交错写入，不能仅凭此片段逐条确认进程归属")
            if (failures.isEmpty()) append("该片段未记录输入失败，不代表触控已通过") else append(failures.joinToString("\n"))
        }
    }
}
