package io.github.shaonialife.buicklinkplus.iap2

import android.content.Context
import androidx.core.content.edit

/**
 * 按车机保存 MFi 挑战长度
 * 成功档优先，其余按基线、静默、明确拒绝的顺序尝试；普通断链不记为拒绝
 * 所有档位都曾拒绝时仍完整尝试，避免永久无法连接
 */
object MfiLengthMemory {
    /** 单个车机的 MFi 记忆。 */
    data class Entry(
        val succeeded: Int? = null,
        val silent: Set<Int> = emptySet(),
        val rejected: Set<Int> = emptySet(),
    ) {
        val isEmpty: Boolean get() = succeeded == null && silent.isEmpty() && rejected.isEmpty()
    }

    private const val FILE = "mfi_length_memory"
    private const val KEY_OK = "ok_"
    private const val KEY_SILENT = "silent_"
    private const val KEY_REJECTED = "rejected_"

    /** 车机身份 → 存储键。只用稳定字段，避免蓝牙名里的可变后缀影响命中。 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    /** 读取某车机的记忆（无 context 时退化为进程内内存表，便于单测）。 */
    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        val ok = prefs.getInt(KEY_OK + key, 0).takeIf { it > 0 }
        return Entry(
            succeeded = ok,
            silent = readLengths(prefs.getString(KEY_SILENT + key, null)),
            rejected = readLengths(prefs.getString(KEY_REJECTED + key, null)),
        )
    }

    fun recordSuccess(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            // 成功即证伪该档位的 silent/rejected 旧结论（固件升级后行为可能变）
            Entry(succeeded = length, silent = entry.silent - length, rejected = entry.rejected - length)
        }
    }

    fun recordSilent(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(silent = entry.silent + length, rejected = entry.rejected - length)
            }
        }
    }

    fun recordRejected(
        context: Context?,
        identityText: String,
        length: Int,
    ) {
        update(context, identityText) { entry ->
            if (entry.succeeded == length) {
                entry
            } else {
                entry.copy(rejected = entry.rejected + length, silent = entry.silent - length)
            }
        }
    }

    /** 打印用：把记忆压成一行日志（便于对照实测）。 */
    fun describe(entry: Entry): String =
        if (entry.isEmpty) {
            "无"
        } else {
            buildList {
                entry.succeeded?.let { add("成功=${it}B") }
                if (entry.silent.isNotEmpty()) add("静默=${entry.silent.sorted().joinToString("/")}B")
                if (entry.rejected.isNotEmpty()) add("被拒=${entry.rejected.sorted().joinToString("/")}B")
            }.joinToString("，")
        }

    /**
     * 结合车机记忆排序 MFi 挑战长度
     *
     * @param baseline 按接收端类型给出的长度顺序
     * @param entry 车机的长度记忆
     * @return 覆盖 baseline 全部档位的非空列表
     */
    fun orderAttempts(
        baseline: List<Int>,
        entry: Entry,
    ): List<Int> {
        val rest = baseline.filter { it != entry.succeeded }
        // 只有「还存在没被判死的档位」时才把 rejected 排到最后；
        // 全部 rejected 说明与记忆矛盾（换固件/换车同一身份），照常全试。
        val allRejected = rest.isNotEmpty() && rest.all { it in entry.rejected }
        val rejected = if (allRejected) emptySet() else entry.rejected.filter { it in baseline }.toSet()
        val silent = entry.silent.filter { it in rest && it !in rejected }.toSet()
        val normal = rest.filter { it !in silent && it !in rejected }
        val silentOrdered = rest.filter { it in silent }
        val rejectedOrdered = rest.filter { it in rejected }
        return (listOfNotNull(entry.succeeded) + normal + silentOrdered + rejectedOrdered).distinct()
    }

    // ---- 内部 ----

    private val memory = HashMap<String, Entry>()

    private fun prefs(context: Context?): android.content.SharedPreferences? = context?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun update(
        context: Context?,
        identityText: String,
        transform: (Entry) -> Entry,
    ) {
        val key = receiverKey(identityText)
        synchronized(memory) {
            val current = load(context, identityText)
            val next = transform(current)
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit {
                    if (next.succeeded != null) putInt(KEY_OK + key, next.succeeded) else remove(KEY_OK + key)
                    putString(KEY_SILENT + key, next.silent.sorted().joinToString(","))
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                }
        }
    }

    private fun readLengths(raw: String?): Set<Int> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it > 0 }
            ?.toSet()
            .orEmpty()
}
