package io.github.shaonialife.buicklinkplus.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.shaonialife.buicklinkplus.util.shellQuote

/** 手机版高德车载入口解析及目标屏前台页面核对 */
internal object MapLaunchResolver {
    const val AMAP = "com.autonavi.minimap"
    const val ACTION = "com.ucar.intent.action.UCAR"
    const val CATEGORY = "com.ucar.intent.category.UCAR"
    private val carPages = listOf("com.autonavi.map.activity.UcarSplashActivity", "com.autonavi.map.activity.UcarMapActivity")
    private val historyRecord = Regex("""\bHist\s+#""")

    // 无前缀的全局 ResumedActivity 位于最后一块屏之后，不代表该屏的真实前台
    private val resumedRecord = Regex("""^\s*(?:mResumedActivity\s*:|topResumedActivity\s*=)""", RegexOption.IGNORE_CASE)

    fun carCandidates(
        context: Context,
        component: ComponentName,
    ): List<ComponentName> {
        if (component.packageName != AMAP) return emptyList()
        val pm = context.packageManager
        val resolved =
            runCatching {
                pm.resolveActivity(Intent(ACTION).addCategory(CATEGORY).setPackage(AMAP), 0)?.activityInfo
            }.getOrNull()
        return listOfNotNull(resolved?.takeIf { it.packageName == AMAP }?.let { ComponentName(it.packageName, it.name) })
            .filter { candidate ->
                runCatching {
                    val info = pm.getActivityInfo(candidate, 0)
                    info.enabled && info.exported && info.applicationInfo.enabled && info.permission.isNullOrBlank()
                }.getOrDefault(false)
            }
    }

    /** 通过 shell 解析 UCAR 入口，兼容应用包查询受限的系统 */
    fun resolvedCarComponent(output: String): ComponentName? =
        output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.matches(Regex("${Regex.escape(AMAP)}/[A-Za-z0-9_.$]+"))
            }?.let(ComponentName::unflattenFromString)

    /**
     * 车载入口保留原始任务策略，避免清空普通地图任务或复用错误的手机页面
     */
    fun command(
        component: ComponentName,
        displayId: Int,
        car: Boolean,
        reuse: Boolean,
    ): String {
        val target =
            if (component.packageName == "com.autonavi.amapauto") {
                "com.autonavi.amapauto/.MainMapActivity"
            } else {
                component.flattenToString()
            }
        val extras = if (car) " -a $ACTION -c $CATEGORY --ez isUcarMode true --ei screenMode 1 --ei displayId $displayId" else ""
        val flags =
            if (car) {
                ""
            } else if (reuse) {
                " -f 0x10000000"
            } else {
                " -f 0x18000000"
            }
        return "am start -W -n ${shellQuote(target)}$extras --display $displayId --windowingMode 1$flags 2>&1"
    }

    /** 必须是目标显示屏当前恢复的页面，不能把历史任务中的车载页面误认为已启动 */
    fun activePage(
        dump: String,
        displayId: Int,
        pkg: String,
    ): String? {
        var current = -1
        for (line in dump.lineSequence()) {
            Regex("^\\s*Display(?:\\s*#|:\\s*mDisplayId=|\\s+)(\\d+)")
                .find(line)
                ?.let { current = it.groupValues[1].toInt() }
            if (current != displayId || !resumedRecord.containsMatchIn(line)) continue
            Regex(Regex.escape(pkg) + "/([A-Za-z0-9_.$]+)").find(line)?.let {
                val page = it.groupValues[1]
                return if (page.startsWith('.')) pkg + page else page
            }
        }
        return taskStates(dump, "", displayId, pkg).firstOrNull { it.resumed && it.visible != false }?.page
    }

    fun isCarPage(page: String?): Boolean = page != null && page in carPages

    /**
     * 读取实际前台组件，供返回和应用内部跳转后的侧栏状态对账
     */
    fun activeComponent(
        dump: String,
        displayId: Int,
    ): String? {
        var current = -1
        for (line in dump.lineSequence()) {
            Regex("^\\s*Display(?:\\s*#|:\\s*mDisplayId=|\\s+)(\\d+)")
                .find(line)
                ?.let { current = it.groupValues[1].toInt() }
            if (current != displayId || !resumedRecord.containsMatchIn(line)) continue
            val match = Regex("\\s([A-Za-z0-9_.$]+)/([A-Za-z0-9_.$]+)").find(line) ?: continue
            val pkg = match.groupValues[1]
            val page = match.groupValues[2].let { if (it.startsWith('.')) pkg + it else it }
            return "$pkg/$page"
        }
        return taskStates(dump, "", displayId)
            .firstOrNull { it.resumed && it.visible != false }
            ?.let { "${it.packageName}/${it.page}" }
    }

    /**
     * 启动命令成功只表示系统接受请求，错误文本与非零退出码不能当作成功
     */
    fun commandAccepted(
        ok: Boolean,
        output: String,
    ): Boolean {
        if (!ok ||
            Regex("(?im)^\\s*(?:Error:|Exception|SecurityException|Permission Denial|Unknown command)").containsMatchIn(output)
        ) {
            return false
        }
        return output.contains("Starting:", true) || output.contains("Status: ok", true) || output.contains("Warning: Activity", true)
    }

    data class TaskState(
        val displayId: Int,
        val taskId: Int,
        val packageName: String,
        val page: String,
        val resumed: Boolean,
        val visible: Boolean?,
        val drawn: Boolean?,
        val lifecycleKnown: Boolean = true,
    ) {
        /** 可继续检查新帧；缺少可见字段不等于不可见，提交前仍需确认输入帧 */
        val ready: Boolean get() = resumed && visible != false && drawn == true

        /**
         * 同屏其他任务已经恢复时允许唤回地图，生命周期缺失只接受用户明确选择
         */
        fun obscuredBy(
            states: List<TaskState>,
            userRequested: Boolean = false,
        ): Boolean =
            !resumed &&
                (lifecycleKnown || visible == false || userRequested) &&
                visible != true &&
                states.any { it.displayId == displayId && it.resumed && it.visible != false && it.taskId != taskId }
    }

    /** 只读目标屏活动明细，不用历史或其他屏状态补全未知字段 */
    fun taskStates(
        dump: String,
        windows: String,
        displayId: Int,
        pkg: String? = null,
        detailedOnly: Boolean = false,
    ): List<TaskState> {
        val displayHeader = Regex("(?m)^\\s*Display(?:\\s*#|:\\s*mDisplayId=|\\s+)(\\d+)")
        val headers = displayHeader.findAll(dump).toList()
        val header = headers.indexOfFirst { it.groupValues[1].toInt() == displayId }
        if (header < 0) return emptyList()
        val section = dump.substring(headers[header].range.first, headers.getOrNull(header + 1)?.range?.first ?: dump.length)
        // 左右大括号都显式转义，兼容安卓正则实现，不能依赖桌面 JVM 对裸右括号的容忍
        val record = Regex("ActivityRecord\\{[^\\r\\n}]*?\\s([A-Za-z0-9_.$]+)/([A-Za-z0-9_.$]+)\\s+t(\\d+)[^\\r\\n}]*\\}")
        val records = record.findAll(section).toList()
        val resumed =
            records
                .filter { match ->
                    val start = (section.lastIndexOf('\n', match.range.first) + 1).coerceAtLeast(0)
                    resumedRecord.containsMatchIn(section.substring(start, match.range.first))
                }.map { it.groupValues[3].toInt() to it.groupValues[2] }
                .toSet()
        return records
            .mapIndexedNotNull { index, match ->
                val start = (section.lastIndexOf('\n', match.range.first) + 1).coerceAtLeast(0)
                val prefix = section.substring(start, match.range.first)
                val detailed = historyRecord.containsMatchIn(prefix) || prefix.trim() == "*"
                if (detailedOnly && !detailed) return@mapIndexedNotNull null
                val packageName = match.groupValues[1]
                if (pkg != null && packageName != pkg) return@mapIndexedNotNull null
                val indent = prefix.takeWhile { it == ' ' || it == '\t' }.length
                val detail =
                    if (detailed) {
                        section
                            .substring(match.range.last + 1, records.getOrNull(index + 1)?.range?.first ?: section.length)
                            .lineSequence()
                            .drop(1)
                            .takeWhile { it.isBlank() || it.takeWhile { char -> char == ' ' || char == '\t' }.length > indent }
                            .joinToString("\n")
                    } else {
                        ""
                    }
                val rawPage = match.groupValues[2]
                val page = if (rawPage.startsWith('.')) packageName + rawPage else rawPage
                val task = match.groupValues[3].toInt()
                // 实际可见字段优先，不能把请求可见与实际不可见混成未知后放行
                val visible = booleanField(detail, "(?:mVisible|visible)") ?: booleanField(detail, "mVisibleRequested")
                val activityDrawn = booleanField(detail, "(?:mReportedDrawn|mDrawn|allDrawn)")
                val windowDrawn = drawnWindow(windows, displayId, task, packageName, rawPage)
                val active = (task to rawPage) in resumed || Regex("\\b(?:mState|state)=RESUMED\\b").containsMatchIn(detail)
                val lifecycleKnown = active || Regex("\\b(?:mState|state)=[A-Z_]+\\b").containsMatchIn(detail)
                TaskState(displayId, task, packageName, page, active, visible, windowDrawn ?: activityDrawn, lifecycleKnown)
            }.groupBy { it.taskId to it.page }
            .values
            .map { states ->
                states.maxBy { (if (it.lifecycleKnown) 4 else 0) + (if (it.visible != null) 2 else 0) + (if (it.drawn != null) 1 else 0) }
            }
    }

    /**
     * 显示屏段缺失表示快照未知，不能按空任务列表重新启动导航
     */
    fun hasDisplay(
        dump: String,
        displayId: Int,
    ): Boolean = Regex("(?m)^\\s*Display(?:\\s*#|:\\s*mDisplayId=|\\s+)$displayId\\b").containsMatchIn(dump)

    /**
     * 未输出或冲突的状态保持未知，不能把缺字段等同于已绘制
     */
    private fun booleanField(
        text: String,
        name: String,
    ): Boolean? {
        val values = Regex("\\b$name=(true|false)\\b").findAll(text).map { it.groupValues[1] == "true" }.toSet()
        return values.singleOrNull()
    }

    /** 显示屏、组件和任务均匹配时，才采用窗口绘制状态 */
    private fun drawnWindow(
        windows: String,
        displayId: Int,
        taskId: Int,
        pkg: String,
        page: String,
    ): Boolean? {
        val blocks = windows.split(Regex("(?m)(?=^\\s*Window #\\d+ Window\\{)"))
        val block =
            blocks.firstOrNull {
                val title = it.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty()
                val titlePage = Regex(Regex.escape(pkg) + "/([A-Za-z0-9_.$]+)").find(title)?.groupValues?.get(1)
                val normalized = titlePage?.let { name -> if (name.startsWith('.')) pkg + name else name }
                val expected = if (page.startsWith('.')) pkg + page else page
                normalized == expected &&
                    Regex("\\bmDisplayId=$displayId\\b").containsMatchIn(it) &&
                    Regex("ActivityRecord\\{[^}]*\\bt$taskId\\b").containsMatchIn(it)
            } ?: return null
        return Regex("\\bmDrawState=(HAS_DRAWN|READY_TO_SHOW|DRAW_PENDING|COMMIT_DRAW_PENDING|NO_SURFACE)\\b")
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.let { it == "HAS_DRAWN" }
    }
}

/** 按显示模式尝试入口，车载模式禁止静默切成普通手机页面 */
internal fun <T> launchPreferredMap(
    carCandidates: List<T>,
    normal: T,
    allowNormalFallback: Boolean = true,
    launch: (T, Boolean) -> Boolean,
): Boolean {
    for (candidate in carCandidates) if (runCatching { launch(candidate, true) }.getOrDefault(false)) return true
    return allowNormalFallback && runCatching { launch(normal, false) }.getOrDefault(false)
}
