package io.github.shaonialife.buicklinkplus.launcher.settings

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.DesktopApps
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/** 设置列表行：分组标题 / 拼音字母标题 / 应用（三个应用列表共用）。 */
sealed class SettingsRow {
    abstract val id: Long

    data class Header(
        val title: String,
        val key: String,
    ) : SettingsRow() {
        override val id: Long get() = key.hashCode().toLong()
    }

    data class App(
        val entry: VirtualLauncher.AppEntry,
        val state: String = "",
    ) : SettingsRow() {
        override val id: Long get() = entry.component.hashCode().toLong()
    }
}

/** 应用列表先分为桌面内外两组，仅桌面外的应用按拼音分节并显示字母导航 */
object SettingsRows {
    fun build(
        context: Context,
        apps: List<VirtualLauncher.AppEntry>,
    ): List<SettingsRow> {
        val selected = DesktopApps.selected(context, apps)
        val snapshot = applicationSnapshot(apps, selected) { it.component.flattenToString() }
        val onDesktop = snapshot.desktop
        val offDesktop = snapshot.others
        val rows = ArrayList<SettingsRow>(apps.size * 2)
        if (onDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("在桌面的应用（${onDesktop.size}）", "on")
            // 扁平直出（清单本身按拼音有序，过滤后仍有序），不分字母节、不进导航条
            rows += onDesktop.map { SettingsRow.App(it) }
        }
        if (offDesktop.isNotEmpty()) {
            rows += SettingsRow.Header("不在桌面的应用（${offDesktop.size}）", "off")
            rows += letterRows(offDesktop)
        }
        return rows.map { row ->
            if (row is SettingsRow.App) {
                val component = row.entry.component
                row.copy(
                    state =
                        "${component.flattenToString() in selected}:" +
                            "${io.github.shaonialife.buicklinkplus.launcher.SupersamplePrefs.mode(context, component)}:" +
                            "${AppOrientationCompat.isEnabled(context, component.packageName)}:${VirtualLauncher.appsCacheVersion}",
                )
            } else {
                row
            }
        }
    }

    private fun letterRows(group: List<VirtualLauncher.AppEntry>): List<SettingsRow> {
        val byLetter = LinkedHashMap<String, MutableList<VirtualLauncher.AppEntry>>()
        group.forEach { entry ->
            byLetter
                .getOrPut(VirtualLauncher.indexLetterOf(entry.label)) { mutableListOf() }
                .add(entry)
        }
        val rows = ArrayList<SettingsRow>(group.size + byLetter.size)
        byLetter.forEach { (letter, list) ->
            rows += SettingsRow.Header(letter, "letter_$letter")
            rows += list.map { SettingsRow.App(it) }
        }
        return rows
    }

    /** 右侧字母导航条：行集合变化时由 AdapterDataObserver 驱动重建。 */
    fun setupIndexBar(
        fragment: androidx.fragment.app.Fragment,
        bar: LinearLayout,
        list: RecyclerView,
        rowsProvider: () -> List<SettingsRow>,
    ) {
        val density = fragment.resources.displayMetrics.density

        fun rebuild() {
            bar.removeAllViews()
            val letters = LinkedHashSet<String>()
            rowsProvider().forEach { row ->
                if (row is SettingsRow.Header && row.key.startsWith("letter_")) {
                    letters += row.key.removePrefix("letter_")
                }
            }
            letters.forEach { letter ->
                bar.addView(
                    TextView(fragment.requireContext()).apply {
                        text = letter
                        textSize = 9f
                        setAppearanceTextColor(R.color.m3_on_var)
                        setPadding(0, (1.5f * density).toInt(), 0, (1.5f * density).toInt())
                        setOnClickListener {
                            val target =
                                rowsProvider()
                                    .indexOfFirst { it is SettingsRow.Header && it.key == "letter_$letter" }
                            if (target >= 0) {
                                (list.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(target, 0)
                            }
                        }
                    },
                )
            }
        }
        rebuild()
        val adapter = list.adapter ?: return
        val pending = Runnable { if (fragment.view != null) rebuild() }

        fun schedule() {
            bar.removeCallbacks(pending)
            bar.post(pending)
        }
        val observer =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = schedule()

                override fun onItemRangeChanged(
                    positionStart: Int,
                    itemCount: Int,
                ) = schedule()

                override fun onItemRangeInserted(
                    positionStart: Int,
                    itemCount: Int,
                ) = schedule()

                override fun onItemRangeRemoved(
                    positionStart: Int,
                    itemCount: Int,
                ) = schedule()

                override fun onItemRangeMoved(
                    fromPosition: Int,
                    toPosition: Int,
                    itemCount: Int,
                ) = schedule()
            }
        adapter.registerAdapterDataObserver(observer)
        fragment.viewLifecycleOwner.lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    bar.removeCallbacks(pending)
                    adapter.unregisterAdapterDataObserver(observer)
                }
            },
        )
    }

    /** 分组标题行复用 ViewHolder。 */
    class HeaderHolder(
        val title: TextView,
    ) : RecyclerView.ViewHolder(title) {
        fun bind(text: String) {
            title.text = text
        }
    }

    fun inflateHeader(parent: ViewGroup): TextView =
        android.view.LayoutInflater
            .from(parent.context)
            .inflate(R.layout.item_section_header, parent, false) as TextView
}

/** 应用设置列表共用适配器，统一分组头，应用行由子类提供 */
abstract class AppRowsAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val rowLayout: Int,
    private val appsProvider: () -> List<VirtualLauncher.AppEntry>,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    /** 应用行 Holder（分组头之外的内容行）。 */
    abstract class AppHolder(
        row: android.view.View,
    ) : RecyclerView.ViewHolder(row) {
        abstract fun bind(entry: VirtualLauncher.AppEntry)
    }

    /** 创建应用行，[rowLayout] 已完成加载 */
    protected abstract fun onCreateAppHolder(row: android.view.View): AppHolder

    var rows: List<SettingsRow> = emptyList()
        private set
    private val requests =
        io.github.shaonialife.buicklinkplus.util
            .RequestEpoch()
    private var rebindPending = false

    /** 数据变化时生成列表快照，后台比较后更新当前视图 */
    fun refreshRows(rebind: Boolean = false) {
        if (rebind) rebindPending = true
        val root = fragment.view ?: return
        val context = root.context.applicationContext
        val apps = appsProvider().toList()
        val request = requests.advance()
        val previous = rows
        io.github.shaonialife.buicklinkplus.util.Bg.run("settings-rows") {
            val next = SettingsRows.build(context, apps)
            val diff =
                androidx.recyclerview.widget.DiffUtil.calculateDiff(
                    object : androidx.recyclerview.widget.DiffUtil.Callback() {
                        override fun getOldListSize(): Int = previous.size

                        override fun getNewListSize(): Int = next.size

                        override fun areItemsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int,
                        ): Boolean {
                            val old = previous[oldItemPosition]
                            val fresh = next[newItemPosition]
                            return when {
                                old is SettingsRow.Header && fresh is SettingsRow.Header -> old.key == fresh.key
                                old is SettingsRow.App && fresh is SettingsRow.App -> old.entry.component == fresh.entry.component
                                else -> false
                            }
                        }

                        override fun areContentsTheSame(
                            oldItemPosition: Int,
                            newItemPosition: Int,
                        ): Boolean = previous[oldItemPosition] == next[newItemPosition]
                    },
                )
            root.post {
                if (!requests.owns(request) || fragment.view !== root || !fragment.isAdded || !root.isShown) return@post
                rows = next
                diff.dispatchUpdatesTo(this)
                if (rebindPending && rows.isNotEmpty()) notifyItemRangeChanged(0, rows.size, "状态")
                rebindPending = false
            }
        }
    }

    final override fun getItemViewType(position: Int): Int = if (rows[position] is SettingsRow.Header) 0 else 1

    final override fun getItemId(position: Int): Long = rows[position].id

    final override fun getItemCount(): Int = rows.size

    final override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder =
        if (viewType == 0) {
            SettingsRows.HeaderHolder(SettingsRows.inflateHeader(parent))
        } else {
            onCreateAppHolder(fragment.layoutInflater.inflate(rowLayout, parent, false))
        }

    final override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is SettingsRow.Header -> (holder as SettingsRows.HeaderHolder).bind(row.title)
            is SettingsRow.App -> (holder as AppHolder).bind(row.entry)
        }
    }
}
