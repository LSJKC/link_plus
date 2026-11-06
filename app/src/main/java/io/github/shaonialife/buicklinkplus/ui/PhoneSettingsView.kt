package io.github.shaonialife.buicklinkplus.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus

/**
 * 手机设置的分组导航，原操作控件保留在宿主内，进入详情后不再展开整张分类列表
 */
class PhoneSettingsView(
    context: Context,
) : FrameLayout(context) {
    private val pages = mutableMapOf<Int, LinearLayout>()
    private val scrollPositions = mutableMapOf<Int, Int>()
    private val index: ScrollView
    private val detail: ScrollView
    private val content: FrameLayout
    private val summaries = mutableMapOf<Int, TextView>()
    private var navigationGeneration = 0L
    var currentSection: Int? = null
        private set
    var onSectionShown: ((Int) -> Unit)? = null
    var onSectionRequested: ((Int) -> Boolean)? = null
    var onBackRequested: (() -> Boolean)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_phone_settings, this, true)
        index = findViewById(R.id.phoneSettingsIndex)
        detail = findViewById(R.id.phoneSettingsDetail)
        content = findViewById(R.id.phoneCategoryBody)
        findViewById<View>(R.id.phoneCategoryBack).setOnClickListener {
            if (onBackRequested?.invoke() != true) showIndex()
        }
        // 旧分类保留在视图树内，授权回调仍可按控件 ID 找到它们
        SettingsMenus.ALL.forEach { item ->
            pages[item.id] =
                LinearLayout(context)
                    .apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }.also { content.addView(it, LayoutParams(-1, -2)) }
        }
        val categories = findViewById<LinearLayout>(R.id.phoneExperienceGroup)
        SettingsMenus.VISIBLE.forEach { item ->
            summaries[item.id] = addRow(categories, item.id, item.title, item.desc)
        }
        findViewById<EditText>(R.id.settingsSearch).doAfterTextChanged { input ->
            val query = input?.toString().orEmpty()
            val results = findViewById<LinearLayout>(R.id.phoneSearchResults)
            results.removeAllViews()
            val searching = query.isNotBlank()
            categories.visibility = if (searching) View.GONE else View.VISIBLE
            results.visibility = if (searching) View.VISIBLE else View.GONE
            val matches = SettingsMenus.search(query)
            matches.forEach { addRow(results, it.target, it.title, it.description) }
            findViewById<View>(R.id.phoneSearchEmpty).visibility = if (searching && matches.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /**
     * 搜索和分类行复用同一目标规则与可访问触控区域
     */
    private fun addRow(
        group: LinearLayout,
        id: Int,
        title: String,
        description: String,
    ): TextView {
        val row = LayoutInflater.from(context).inflate(R.layout.item_phone_category, group, false)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageResource(SettingsMenus.itemOf(id).iconRes)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageTint(R.color.m3_primary)
        row.findViewById<TextView>(R.id.categoryTitle).text = title
        row.findViewById<TextView>(R.id.categoryDescription).apply {
            text = description
            visibility = View.VISIBLE
        }
        row.setOnClickListener {
            if (onSectionRequested?.invoke(id) != true) showSection(id)
        }
        group.addView(row)
        return row.findViewById(R.id.categoryDescription)
    }

    /** 返回列表时读取已有偏好，不扫描应用 */
    private fun refreshSummaries() {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val display = io.github.shaonialife.buicklinkplus.launcher.settings.DisplayPrefs
        val values =
            mapOf(
                SettingsMenus.CONNECTION to (
                    io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
                        .get(context)
                        ?.name ?: "未选择车辆"
                ),
                SettingsMenus.APPS to (preferences.getStringSet("desktopApps", null)?.let { "已选 ${it.size} 个桌面应用" } ?: "沿用默认桌面应用"),
                SettingsMenus.APPEARANCE to "画质配置 ${display.scalePercent(context)}% · 目标 ${display.fps(context)} fps",
                SettingsMenus.MAP to (
                    io.github.shaonialife.buicklinkplus.launcher.DefaultMapPrefs
                        .manualPackage(context) ?: "自动选择已安装地图"
                ),
                SettingsMenus.AUDIO to "音频策略：${io.github.shaonialife.buicklinkplus.audio.AudioPreference.read(context).title}",
                SettingsMenus.ABOUT to "当前版本 ${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_NAME}",
            )
        summaries.forEach { (id, label) ->
            label.text = context.getString(R.string.settings_summary_details, values[id], SettingsMenus.itemOf(id).desc)
        }
    }

    /**
     * 取得分类容器，宿主将已有的事件控件移入该容器
     */
    fun body(id: Int): LinearLayout = pages.getValue(id)

    /**
     * 系统重建时保存搜索、分类和各页滚动位置
     */
    fun saveNavigation(): Bundle =
        Bundle().apply {
            currentSection?.let { scrollPositions[it] = detail.scrollY }
            putString("query", findViewById<EditText>(R.id.settingsSearch).text.toString())
            putInt("section", currentSection ?: -1)
            putInt("indexScroll", index.scrollY)
            scrollPositions.forEach { (id, offset) -> putInt("scroll:$id", offset) }
        }

    /** 恢复搜索和滚动位置，过期滚动回调失效 */
    fun restoreNavigation(state: Bundle) {
        SettingsMenus.ALL.forEach { scrollPositions[it.id] = state.getInt("scroll:${it.id}", 0) }
        findViewById<EditText>(R.id.settingsSearch).setText(state.getString("query", ""))
        val section = state.getInt("section", -1)
        if (section >= 0) showSection(section) else showIndex()
        val generation = navigationGeneration
        index.post { if (navigationGeneration == generation) index.scrollTo(0, state.getInt("indexScroll", 0)) }
    }

    /**
     * 页面切换先保存旧滚动位置，再显示唯一可见的分类详情
     */
    fun showSection(id: Int) {
        if (id !in pages) return
        currentSection?.let { scrollPositions[it] = detail.scrollY }
        val generation = ++navigationGeneration
        currentSection = id
        pages.forEach { (key, page) -> page.visibility = if (key == id) View.VISIBLE else View.GONE }
        findViewById<TextView>(R.id.phoneCategoryTitle).text = SettingsMenus.titleOf(id)
        index.visibility = View.GONE
        detail.visibility = View.VISIBLE
        detail.post {
            if (navigationGeneration == generation && currentSection == id) {
                detail.scrollTo(0, scrollPositions[id] ?: 0)
            }
        }
        onSectionShown?.invoke(id)
    }

    /**
     * 返回分类列表时保留各详情位置，过期滚动回调不能影响下一个页面
     */
    fun showIndex() {
        currentSection?.let { scrollPositions[it] = detail.scrollY }
        navigationGeneration++
        currentSection = null
        detail.visibility = View.GONE
        pages.values.forEach { it.visibility = View.GONE }
        index.visibility = View.VISIBLE
        refreshSummaries()
    }

    /**
     * 离开窗口后使排队的页面滚动失效
     */
    override fun onDetachedFromWindow() {
        navigationGeneration++
        super.onDetachedFromWindow()
    }
}
