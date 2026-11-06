package io.github.shaonialife.buicklinkplus.audio

import android.content.Context
import androidx.core.content.edit

/**
 * 按车机保存音频档位结果
 * working 优先重用，rejected 排到最后，全部档位确认拆链后 disabled
 * 存活判定窗口由调用方传入
 */
object AudioProfileMemory {
    /** 单台车机的音频记忆。 */
    data class Entry(
        val working: String? = null,
        val rejected: Set<String> = emptySet(),
        val disabled: Boolean = false,
    ) {
        val isEmpty: Boolean get() = working == null && rejected.isEmpty() && !disabled
    }

    // v2：档位键加入「流类型 + 编码方式」（102/AAC-LC 与 100/PCM 是两条完全不同的通路）。
    // 换文件名而不是复用旧文件，是为了让 v1 时代「PCM 三档全拆链」的结论不再挡路——
    // 那套结论只覆盖 PCM，对新增的 AAC 通路没有参考价值。
    private const val FILE = "audio_profile_memory_v2"
    private const val KEY_OK = "ok_"
    private const val KEY_REJECTED = "rejected_"
    private const val KEY_DISABLED = "disabled_"

    /** 用稳定的车机身份生成存储键 */
    fun receiverKey(identityText: String): String =
        identityText
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { "unknown" }

    fun load(
        context: Context?,
        identityText: String,
    ): Entry {
        val key = receiverKey(identityText)
        val prefs = prefs(context)
        if (prefs == null) {
            return synchronized(memory) { memory[key] ?: Entry() }
        }
        return Entry(
            working = prefs.getString(KEY_OK + key, null)?.takeIf { it.isNotBlank() },
            rejected = readKeys(prefs.getString(KEY_REJECTED + key, null)),
            disabled = prefs.getBoolean(KEY_DISABLED + key, false),
        )
    }

    /** 档位通过存活窗口后记为首选，并清除旧拒绝记录 */
    fun recordWorking(
        context: Context?,
        identityText: String,
        profileKey: String,
    ) {
        update(context, identityText) { entry ->
            Entry(
                working = profileKey,
                rejected = entry.rejected - profileKey,
                disabled = false,
            )
        }
    }

    /**
     * 将不可用档位排到最后
     *
     * @param markExhausted 全部档位确认拆链时禁用音频；SETUP 拒绝或超时传 false
     */
    fun recordRejected(
        context: Context?,
        identityText: String,
        profileKey: String,
        allCandidates: Set<String>,
        markExhausted: Boolean = true,
    ) {
        update(context, identityText) { entry ->
            if (entry.working == profileKey) {
                entry
            } else {
                val rejected = entry.rejected + profileKey
                val exhausted =
                    markExhausted && allCandidates.isNotEmpty() && allCandidates.all { it in rejected }
                Entry(working = entry.working, rejected = rejected, disabled = exhausted)
            }
        }
    }

    /** 手动重置档位记忆，允许重新尝试 */
    fun reset(
        context: Context?,
        identityText: String,
    ) {
        update(context, identityText) { Entry() }
    }

    /** 是否有音频记忆，供界面决定是否显示清除入口 */
    fun hasAny(context: Context?): Boolean {
        val all = prefs(context)?.all ?: return synchronized(memory) { memory.isNotEmpty() }
        return all.any { (key, value) ->
            when {
                key.startsWith(KEY_DISABLED) -> value == true
                key.startsWith(KEY_OK) -> (value as? String)?.isNotBlank() == true
                key.startsWith(KEY_REJECTED) -> (value as? String)?.isNotBlank() == true
                else -> false
            }
        }
    }

    /** 清空所有车机的音频记忆 */
    fun clearAll(context: Context?) {
        synchronized(memory) {
            memory.clear()
            prefs(context)?.edit {
                clear()
            }
        }
    }

    /** 打印用：压成一行日志。 */
    fun describe(entry: Entry): String =
        when {
            entry.disabled -> "本车机音频已判定不兼容（只推视频）"
            entry.isEmpty -> "无"
            else ->
                buildList {
                    entry.working?.let { add("可用=$it") }
                    if (entry.rejected.isNotEmpty()) add("拆链=${entry.rejected.sorted().joinToString("/")}")
                }.joinToString("，")
        }

    /**
     * 结合车机记忆排序候选档位
     *
     * @param candidates 按推荐顺序排列的候选
     * @param entry 该车机的档位记忆
     * @return 尝试顺序，空列表表示该车机已禁用音频
     */
    fun order(
        candidates: List<AudioProfile>,
        entry: Entry,
    ): List<AudioProfile> {
        if (candidates.isEmpty()) return emptyList()
        if (entry.disabled) return emptyList()
        val keys = candidates.map { it.key }.toSet()
        val rejected = entry.rejected.filter { it in keys }.toSet()
        val working = candidates.firstOrNull { it.key == entry.working }
        val rest = candidates.filter { it.key != working?.key }
        // 只有「还有没被判死的档位」时才把 rejected 排到最后；全被判死说明与记忆矛盾
        // （换车机/固件升级却沿用同一身份键），此时照常全试，避免永久无声。
        val allRejected = rest.isNotEmpty() && rest.all { it.key in rejected }
        val safe = if (allRejected) rest else rest.filter { it.key !in rejected }
        val risky = if (allRejected) emptyList() else rest.filter { it.key in rejected }
        return (listOfNotNull(working) + safe + risky).distinctBy { it.key }
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
            val next = transform(load(context, identityText))
            memory[key] = next
            val prefs = prefs(context) ?: return
            prefs
                .edit {
                    if (next.working != null) putString(KEY_OK + key, next.working) else remove(KEY_OK + key)
                    putString(KEY_REJECTED + key, next.rejected.sorted().joinToString(","))
                    putBoolean(KEY_DISABLED + key, next.disabled)
                }
        }
    }

    private fun readKeys(raw: String?): Set<String> =
        raw
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            ?.toSet()
            .orEmpty()
}
