package io.github.shaonialife.buicklinkplus.launcher

/** 优先复用任务，只迁移同包独占根任务，不动地图屏或混合任务 */
internal object AppLaunchRecovery {
    /** 迁移使用根任务 ID，检查前台和输入使用原活动任务 ID */
    data class Move(
        val sourceDisplay: Int,
        val rootTaskId: Int,
        val taskId: Int,
    )

    /** 保存根任务和活动归属，嵌套子任务 ID 不作根任务 ID */
    private class Root(
        val displayId: Int,
        val id: Int,
        val indent: Int,
        val standard: Boolean,
    ) {
        val members = linkedSetOf<Pair<String, Int>>()
        val leaves = linkedSetOf<Int>()
        val levels = ArrayList<Pair<Int, Int>>()
        var organized = false
        var hierarchyKnown = true
    }

    private val displayPattern = Regex("""^\s*Display(?:\s*#|:\s*mDisplayId=|\s+)(\d+)""")
    private val taskPattern = Regex("""^\s*(?:\*\s*)?Task\{[^}]*#(\d+)\b[^}]*\}""")
    private val activityPattern = Regex("""ActivityRecord\{[^}]*?\s([A-Za-z0-9_.$]+)/[A-Za-z0-9_.$]+\s+t(\d+)\b[^}]*\}""")
    private val reportedActivity = Regex("""(?m)^\s*Activity:\s+([A-Za-z0-9_.$]+)/([A-Za-z0-9_.$]+)\s*$""")
    private val taskType = Regex("""\btype=standard\b""")
    private val rootTaskId = Regex("""\brootTaskId=(\d+)\b""")
    private val operationError = Regex("""(?im)^\s*(?:Error:|Exception|SecurityException|Permission Denial|Unknown command)""")

    /** 用同一快照选择迁移任务，候选不唯一或层级未知时不迁移 */
    fun candidate(
        dump: String,
        packageName: String,
        requestedPage: String,
        launchOutput: String,
        targetDisplay: Int,
        sourceDisplays: Set<Int>,
    ): Move? {
        if (!MapLaunchResolver.hasDisplay(dump, targetDisplay)) return null
        if (hasTask(dump, packageName, targetDisplay)) return null
        val states =
            sourceDisplays.filter { it >= 0 && it != targetDisplay }.flatMap { display ->
                MapLaunchResolver.taskStates(dump, "", display, packageName, detailedOnly = true)
            }
        val active = states.filter { it.resumed && it.visible != false }
        val reported = reportedActivity.find(launchOutput)
        if (reported != null && reported.groupValues[1] != packageName) return null
        val reportedPage = reported?.groupValues?.get(2)?.let { if (it.startsWith('.')) packageName + it else it }
        val eligible =
            if (active.isNotEmpty()) active else states.filter { it.page == requestedPage || it.page == reportedPage }
        val selected = eligible.distinctBy { it.displayId to it.taskId }.singleOrNull() ?: return null
        val owner =
            roots(dump).singleOrNull {
                it.displayId == selected.displayId && (packageName to selected.taskId) in it.members
            } ?: return null
        // 叶任务缺少活动归属或由系统组织器管理时，不能证明迁移范围只包含用户选择的应用
        if (!owner.standard ||
            owner.organized ||
            !owner.hierarchyKnown ||
            owner.members.any { it.first != packageName } ||
            owner.leaves.any { leaf -> owner.members.none { it.second == leaf } }
        ) {
            return null
        }
        return Move(selected.displayId, owner.id, selected.taskId)
    }

    /** 只复用应用虚拟屏任务树中的同包任务，优先已恢复任务，再考虑目标屏 */
    fun reusableTask(
        dump: String,
        packageName: String,
        preferredDisplay: Int,
        otherDisplay: Int,
        pageMatches: (String) -> Boolean = { true },
    ): Move? {
        val roots = roots(dump)
        val candidates = ArrayList<Move>()
        val activeCandidates = ArrayList<Move>()
        for (display in listOf(preferredDisplay, otherDisplay).filter { it > 0 }.distinct()) {
            val owners = roots.filter { it.displayId == display && it.standard && !it.organized && it.hierarchyKnown }
            val states =
                MapLaunchResolver.taskStates(dump, "", display, packageName, detailedOnly = true).filter { state ->
                    pageMatches(state.page) && owners.any { (packageName to state.taskId) in it.members && state.taskId in it.leaves }
                }
            if (states.isEmpty()) continue
            val active = states.filter { it.resumed && it.visible != false }.distinctBy { it.taskId }
            val selected = active.singleOrNull() ?: states.distinctBy { it.taskId }.singleOrNull() ?: continue
            val owner = owners.singleOrNull { (packageName to selected.taskId) in it.members } ?: return null
            val move = Move(display, owner.id, selected.taskId)
            candidates += move
            if (active.size == 1) activeCandidates += move
        }
        return activeCandidates.firstOrNull() ?: candidates.firstOrNull()
    }

    /** 活动明细存在时才阻止新建；层级未知时保留任务，避免重复启动 */
    fun hasTask(
        dump: String,
        packageName: String,
        displayId: Int,
    ): Boolean = MapLaunchResolver.taskStates(dump, "", displayId, packageName, detailedOnly = true).isNotEmpty()

    /**
     * 同一份转储解析根任务与叶任务归属，复用与迁移共享层级边界
     */
    private fun roots(dump: String): List<Root> {
        val roots = ArrayList<Root>()
        val seenDisplays = HashSet<Int>()
        var readDisplay = false
        var display = -1
        var root: Root? = null
        for (line in dump.lineSequence()) {
            displayPattern.find(line)?.let {
                display = it.groupValues[1].toInt()
                root = null
                // 系统随后还会输出同一屏的窗口层级摘要，不能把同一根任务算成两个归属
                readDisplay = seenDisplays.add(display)
            }
            if (!readDisplay) continue
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            val task = taskPattern.find(line)
            val previous = root
            if (task != null && (previous == null || indent <= previous.indent)) {
                val id = task.groupValues[1].toInt()
                val declaredRoot =
                    rootTaskId
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                val standardRoot = taskType.containsMatchIn(line) && (declaredRoot == null || declaredRoot == id)
                val created = Root(display, id, indent, standardRoot)
                root = created
                roots += created
            } else if (line.isNotBlank() && previous != null && indent <= previous.indent) {
                root = null
            }
            val current = root ?: continue
            if (task != null) {
                val declaredRoot =
                    rootTaskId
                        .find(line)
                        ?.groupValues
                        ?.get(1)
                        ?.toInt()
                if (declaredRoot != null && declaredRoot != current.id) current.hierarchyKnown = false
                while (current.levels
                        .lastOrNull()
                        ?.first
                        ?.let { it >= indent } == true
                ) {
                    current.levels.removeAt(current.levels.lastIndex)
                }
                current.levels
                    .lastOrNull()
                    ?.second
                    ?.let { current.leaves.remove(it) }
                val id = task.groupValues[1].toInt()
                current.leaves += id
                current.levels += indent to id
            }
            if (line.contains("mCreatedByOrganizer=true")) current.organized = true
            activityPattern.findAll(line).forEach { activity ->
                current.members += activity.groupValues[1] to activity.groupValues[2].toInt()
            }
        }
        return roots
    }

    /** 迁移和聚焦可无输出，退出码与文本均需成功，最后回读任务状态 */
    fun operationAccepted(
        ok: Boolean,
        output: String,
    ): Boolean = ok && !operationError.containsMatchIn(output)
}
