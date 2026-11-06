package io.github.shaonialife.buicklinkplus.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.input.TouchRouter
import io.github.shaonialife.buicklinkplus.launcher.AppIcons
import io.github.shaonialife.buicklinkplus.launcher.DefaultMapPrefs
import io.github.shaonialife.buicklinkplus.launcher.DesktopApps
import io.github.shaonialife.buicklinkplus.launcher.DockPrefs
import io.github.shaonialife.buicklinkplus.launcher.SupersamplePrefs
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.AppRowsAdapter.AppHolder
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.DarkMessageDialog
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/** 手机和车机共用的设置 Fragment */
class DesktopPanelFragment : SettingsPanelFragment() {
    private var countText: TextView? = null
    private var selected: MutableSet<String> = mutableSetOf()
    private lateinit var adapter: DesktopAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_desktop, container, false)

    override fun onViewCreated(view: View) {
        countText = view.findViewById(R.id.settingsCount)
        adapter = DesktopAdapter()
        setupAppList(view, R.id.appList, R.id.appListIndex, adapter) {
            selected.clear()
            selected += DesktopApps.selected(requireContext(), apps)
            updateCount()
        }
    }

    private fun updateCount() {
        countText?.text = getString(R.string.desktop_selected_count, selected.size)
    }

    private fun toggle(
        entry: VirtualLauncher.AppEntry,
        checked: Boolean,
    ) {
        val key = entry.component.flattenToString()
        if (checked) selected.add(key) else selected.remove(key)
        DesktopApps.save(requireContext(), selected)
        updateCount()
        hostToast(if (checked) "「${entry.label}」已显示到桌面" else "「${entry.label}」已从桌面移除")
        adapter.refreshRows()
    }

    private inner class DesktopAdapter : AppRowsAdapter(this@DesktopPanelFragment, R.layout.item_app_pick, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = PickerHolder(row)
    }

    private inner class PickerHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.pickIcon)
        private val name: TextView = row.findViewById(R.id.pickName)
        private val check: ImageView = row.findViewById(R.id.pickCheck)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val key = entry.component.flattenToString()
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            AppIcons.bind(icon, entry, iconPx, viewLifecycleOwner)
            name.text = entry.label
            check.setAppearanceImageResource(
                if (key in selected) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            row.setOnClickListener {
                val nowChecked = key !in selected
                toggle(entry, nowChecked)
                check.setAppearanceImageResource(
                    if (nowChecked) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
                )
            }
        }
    }
}

class SupersamplePanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_supersample, container, false)

    override fun onViewCreated(view: View) {
        setupAppList(view, R.id.ssList, R.id.ssListIndex, SupersampleAdapter())
    }

    private inner class SupersampleAdapter : AppRowsAdapter(this@SupersamplePanelFragment, R.layout.item_app_ss, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = SsHolder(row)
    }

    private inner class SsHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.ssIcon)
        private val name: TextView = row.findViewById(R.id.ssName)
        private val segAuto: TextView = row.findViewById(R.id.ssAuto)
        private val segOn: TextView = row.findViewById(R.id.ssOn)
        private val segOff: TextView = row.findViewById(R.id.ssOff)

        override fun bind(entry: VirtualLauncher.AppEntry) {
            val iconPx = (28 * resources.displayMetrics.density).toInt()
            AppIcons.bind(icon, entry, iconPx, viewLifecycleOwner)
            name.text = entry.label
            refresh(SupersamplePrefs.mode(requireContext(), entry.component))

            fun pick(
                mode: Int,
                label: String,
            ) {
                SupersamplePrefs.setMode(requireContext(), entry.component, mode)
                refresh(mode)
                hostToast("「${entry.label}」超采样：$label（下次启动生效）")
            }
            segAuto.setOnClickListener { pick(SupersamplePrefs.MODE_AUTO, "自动") }
            segOn.setOnClickListener { pick(SupersamplePrefs.MODE_ON, "开启") }
            segOff.setOnClickListener { pick(SupersamplePrefs.MODE_OFF, "关闭") }
        }

        private fun refresh(mode: Int) {
            val segs =
                listOf(
                    segAuto to SupersamplePrefs.MODE_AUTO,
                    segOn to SupersamplePrefs.MODE_ON,
                    segOff to SupersamplePrefs.MODE_OFF,
                )
            segs.forEach { (view, segMode) ->
                val active = segMode == mode
                view.setAppearanceBackground(if (active) R.drawable.bg_segment_sel else 0)
                view.setAppearanceTextColor(if (active) R.color.m3_on_surface else R.color.m3_on_var)
            }
        }
    }
}

class OrientationPanelFragment : SettingsPanelFragment() {
    /** 面板打开时后台读取系统覆盖，列表绑定只读缓存，开关操作就地更新 */
    private var systemOn: Set<String> = emptySet()
    private lateinit var listAdapter: OrientationAdapter
    private lateinit var panelView: View

    private var capability = OrientationCapability.UNKNOWN
    private var capabilityRequest = 0L
    private var systemRevision = 0L
    private val failedPackages = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_panel_orientation, container, false)

    override fun onViewCreated(view: View) {
        panelView = view
        listAdapter = OrientationAdapter()
        // 应用清单就绪后重算横幅（首帧 apps 为空，全部会被误判成「不在列表」）
        setupAppList(view, R.id.oriList, R.id.oriListIndex, listAdapter) { updateOrphanBanner() }
    }

    /** 返回页面后重新检查平台能力，不把首次读取失败的结果固定在整次进程内 */
    override fun onResume() {
        super.onResume()
        refreshCapability()
    }

    /** 隐藏后旧检查不能覆盖下次进入的结果 */
    override fun onPause() {
        capabilityRequest++
        super.onPause()
    }

    /** 平台能力和系统覆盖在后台读取，失败保留未知状态，列表始终可以保存配置 */
    private fun refreshCapability() {
        val expectedView = view ?: return
        val request = ++capabilityRequest
        val revision = systemRevision
        Bg.run("orientation-capability") {
            val platform =
                when {
                    android.os.Build.VERSION.SDK_INT < AppOrientationCompat.MIN_SUPPORTED_SDK -> OrientationCapability.UNSUPPORTED
                    else -> AppOrientationCompat.capability()
                }
            val state = AppOrientationCompat.readSystemEnabled()
            activity?.runOnUiThread {
                if (!isAdded || !isResumed || this.view !== expectedView || request != capabilityRequest) return@runOnUiThread
                capability = if (platform == OrientationCapability.SUPPORTED && state == null) OrientationCapability.UNKNOWN else platform
                // 查询开始后若用户已完成一次修改，旧快照不能覆盖新的系统状态
                if (systemRevision == revision) systemOn = state.orEmpty()
                applyCapabilityState()
                listAdapter.refreshRows(rebind = true)
                updateOrphanBanner()
            }
        }
    }

    /** 提示平台限制，单个应用失败不禁用其他配置 */
    private fun applyCapabilityState() {
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriUnsupportedBanner) ?: return
        val list = panelView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.oriList)
        val index = panelView.findViewById<LinearLayout>(R.id.oriListIndex)
        list?.alpha = 1f
        index?.alpha = 1f
        if (capability == OrientationCapability.SUPPORTED) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        val explanation =
            when {
                android.os.Build.VERSION.SDK_INT < AppOrientationCompat.MIN_SUPPORTED_SDK ->
                    "横屏兼容项需要 Android 14 及以上，当前为 Android ${android.os.Build.VERSION.RELEASE}。可以保存名单，当前系统暂时无法应用。"
                capability == OrientationCapability.UNKNOWN ->
                    "尚未成功读取系统横屏能力，可能是检查暂时失败。配置已保留，可重新检查后再连接车机。"
                else ->
                    "系统检查成功，但未发现完整的横屏兼容项。配置仍可保存，实际能否横屏取决于当前系统和目标应用。"
            }
        banner.text =
            when {
                android.os.Build.VERSION.SDK_INT < AppOrientationCompat.MIN_SUPPORTED_SDK -> "当前系统低于 Android 14 · 查看说明"
                capability == OrientationCapability.UNKNOWN -> "暂未确认横屏能力 · 点击重查"
                else -> "当前系统的横屏兼容项不完整 · 查看"
            }
        banner.minimumHeight = (48 * resources.displayMetrics.density).toInt()
        banner.isFocusable = true
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                host,
                "应用横屏",
                explanation,
                listOf(
                    DarkMessageDialog.Action("重新检查", DarkMessageDialog.Emphasis.PRIMARY) { refreshCapability() },
                    DarkMessageDialog.Action("知道了") {},
                ),
            )
        }
    }

    /** 显示应用列表外的系统覆盖残留，确认后清理，包括卸载应用和旧组件键 */
    private fun updateOrphanBanner() {
        val expectedView = view ?: return
        if (!::panelView.isInitialized) return
        val banner = panelView.findViewById<TextView>(R.id.oriOrphanBanner) ?: return
        // 应用清单未就绪时先不判定：apps 为空会让所有已开启覆盖都「看起来像列表外残留」
        // （列表真正装载后 onAppsReady 会再调一次）
        if (apps.isEmpty()) return
        val known = apps.map { it.component.packageName }.toSet()
        val orphans = systemOn.filter { it !in known }.sorted()
        if (orphans.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.visibility = View.VISIBLE
        // 组件键（含 /）很长：显示时截到包名部分，完整键留给弹窗
        val summary =
            orphans.take(3).joinToString("、") { it.substringBefore('/') } +
                if (orphans.size > 3) " 等 ${orphans.size} 个" else ""
        banner.text = getString(R.string.orientation_orphan_details, orphans.size, summary)
        banner.setOnClickListener {
            val host = activity ?: return@setOnClickListener
            DarkMessageDialog.show(
                activity = host,
                title = "清除横屏残留",
                message =
                    "以下条目已不在应用列表，但系统侧横屏覆盖仍开着：\n" +
                        orphans.joinToString("\n") { "· $it" } +
                        "\n\n清除会立即重启在运行的这些应用（未保存的界面状态会丢失）；" +
                        "已卸载的条目不受影响。是否继续？",
                actions =
                    listOf(
                        DarkMessageDialog.Action("取消") {},
                        DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                            Bg.run("orientation-orphan-clear") {
                                val cleared = orphans.filter { AppOrientationCompat.clearSystemOnly(it) }.toSet()
                                activity?.runOnUiThread {
                                    if (!isAdded || !isResumed || view !== expectedView) return@runOnUiThread
                                    hostToast("已清除 ${cleared.size}/${orphans.size} 个横屏残留")
                                    systemRevision++
                                    systemOn = systemOn - cleared
                                    listAdapter.refreshRows(rebind = true)
                                    updateOrphanBanner()
                                }
                            }
                        },
                    ),
            )
        }
    }

    private inner class OrientationAdapter :
        AppRowsAdapter(this@OrientationPanelFragment, R.layout.item_orientation_car, { apps }) {
        override fun onCreateAppHolder(row: View): AppHolder = OriHolder(row)
    }

    private inner class OriHolder(
        private val row: View,
    ) : AppHolder(row) {
        private val icon: ImageView = row.findViewById(R.id.oriIcon)
        private val name: TextView = row.findViewById(R.id.oriName)
        private val hint: TextView = row.findViewById(R.id.oriHint)
        private val check: ImageView = row.findViewById(R.id.oriCheck)
        private lateinit var entry: VirtualLauncher.AppEntry
        private var shown = false

        override fun bind(entry: VirtualLauncher.AppEntry) {
            this.entry = entry
            val iconPx = (30 * resources.displayMetrics.density).toInt()
            AppIcons.bind(icon, entry, iconPx, viewLifecycleOwner)
            name.text = entry.label
            // 按当前勾选状态取反，避免重新读偏好后把操作方向算反
            shown = AppOrientationCompat.isEnabled(requireContext(), pkg())
            refresh(shown)

            row.setOnClickListener {
                // 系统写入必须异步执行，串行写队列最坏可达 8s
                // 直接同步调用会把点击回调所在的主线程卡到 ANR。
                val target = !shown
                shown = target
                refresh(shown) // 立即回显已保存的用户意愿，系统执行失败不回滚配置
                val expectedView = view
                val requestedPackage = pkg()
                val requestedLabel = entry.label
                AppOrientationCompat.setEnabledAsync(requireContext(), requestedPackage, target) { result ->
                    if (!isAdded ||
                        !isResumed ||
                        view !== expectedView ||
                        AppOrientationCompat.isEnabled(requireContext(), requestedPackage) != target
                    ) {
                        return@setEnabledAsync
                    }
                    when (result) {
                        AppOrientationCompat.WriteResult.SAVED -> {
                            failedPackages.remove(requestedPackage)
                            hostToast("「$requestedLabel」配置已保存，下次连接时尝试应用")
                        }
                        AppOrientationCompat.WriteResult.OK -> {
                            // 就地更新系统侧缓存（写入已同步完成，无需重查 dumpsys）
                            systemRevision++
                            systemOn = if (target) systemOn + requestedPackage else systemOn - requestedPackage
                            failedPackages.remove(requestedPackage)
                            // am compat 写入时就会杀掉目标应用，直接提示下次打开时生效
                            hostToast("「$requestedLabel」横屏：${if (target) "开" else "关"}——应用将以新设置重启")
                        }

                        AppOrientationCompat.WriteResult.UNSUPPORTED -> {
                            failedPackages.add(requestedPackage)
                            hostToast("「$requestedLabel」配置已保存，但系统拒绝了此应用的横屏覆盖，其他应用仍可配置")
                        }

                        AppOrientationCompat.WriteResult.FAILED -> {
                            failedPackages.add(requestedPackage)
                            hostToast("配置已保存，暂未应用；可稍后重新检查")
                        }
                    }
                    listAdapter.refreshRows(rebind = true)
                }
            }
        }

        private fun pkg() = entry.component.packageName

        /** 显示用户选择与系统覆盖的差异，系统残留可点击清理 */
        private fun refresh(enabled: Boolean) {
            check.setAppearanceImageResource(
                if (enabled) R.drawable.bg_checkbox_on else R.drawable.bg_checkbox_off,
            )
            val onSystem = pkg() in systemOn
            when {
                pkg() in failedPackages && !onSystem -> {
                    hint.text = "配置已保存，系统暂未应用 · 可重连后检查"
                    hint.setAppearanceTextColor(R.color.m3_amber)
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }
                onSystem && enabled -> {
                    hint.text = "已开启横屏（会话期间生效）"
                    hint.setAppearanceTextColor(R.color.m3_on_var)
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }

                onSystem -> {
                    hint.text = "系统残留：横屏仍在生效 · 点击清除"
                    hint.setAppearanceTextColor(R.color.m3_error)
                    hint.visibility = View.VISIBLE
                    hint.isClickable = true
                    // 只清系统侧覆盖、不动偏好（偏好本来就是关的）；弹窗提示会重启应用
                    hint.setOnClickListener {
                        val host = activity ?: return@setOnClickListener
                        // 弹窗可能跨越列表回收，操作目标必须固定为打开弹窗时的应用
                        val requestedPackage = pkg()
                        val requestedLabel = entry.label
                        val expectedView = view
                        val app = host.applicationContext
                        DarkMessageDialog.show(
                            activity = host,
                            title = "清除横屏残留",
                            message =
                                "「$requestedLabel」的系统侧横屏覆盖仍在生效（可能是卸载前的残留）。" +
                                    "\n\n清除会立即重启该应用（正在运行的应用会丢失未保存的界面状态）。是否继续？",
                            actions =
                                listOf(
                                    DarkMessageDialog.Action("取消") {},
                                    DarkMessageDialog.Action("清除", DarkMessageDialog.Emphasis.DANGER) {
                                        if (AppOrientationCompat.isEnabled(app, requestedPackage)) {
                                            hostToast("此应用已重新开启横屏配置，请关闭后再清除残留")
                                            return@Action
                                        }
                                        Bg.run("orientation-row-clear") {
                                            val ok =
                                                AppOrientationCompat.clearSystemOnly(requestedPackage) {
                                                    !AppOrientationCompat.isEnabled(app, requestedPackage)
                                                }
                                            activity?.runOnUiThread {
                                                if (!isAdded ||
                                                    !isResumed ||
                                                    view !== expectedView
                                                ) {
                                                    return@runOnUiThread
                                                }
                                                if (AppOrientationCompat.isEnabled(app, requestedPackage)) {
                                                    refreshCapability()
                                                } else if (ok) {
                                                    systemRevision++
                                                    systemOn = systemOn - requestedPackage
                                                    listAdapter.refreshRows(rebind = true)
                                                    hostToast("「$requestedLabel」横屏残留已清除——应用将重启")
                                                } else {
                                                    hostToast("清除失败，可稍后重试")
                                                }
                                            }
                                        }
                                    },
                                ),
                        )
                    }
                }

                enabled -> {
                    hint.text = "已保存，连接车机时尝试应用"
                    hint.setAppearanceTextColor(R.color.m3_on_var)
                    hint.visibility = View.VISIBLE
                    hint.setOnClickListener(null)
                    hint.isClickable = false
                }
                else -> {
                    hint.visibility = View.GONE
                    hint.setOnClickListener(null)
                }
            }
        }
    }
}

class DockPanelFragment : SettingsPanelFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(if (isPhonePanel) R.layout.fragment_phone_panel_dock else R.layout.fragment_panel_dock, container, false)

    override fun onViewCreated(view: View) {
        val widthSeek = view.findViewById<SeekBar>(R.id.dockWidthSeek)
        val widthValue = view.findViewById<TextView>(R.id.dockWidthValue)
        view.findViewById<android.widget.Button>(R.id.openCarAppearance).apply {
            if (!isPhonePanel) {
                io.github.shaonialife.buicklinkplus.ui.CarUi
                    .styleAction(this)
            }
            setOnClickListener {
                val host = activity
                if (host is io.github.shaonialife.buicklinkplus.launcher.SettingsActivity) {
                    host.openPanel(SettingsMenus.CAR_APPEARANCE)
                } else {
                    io.github.shaonialife.buicklinkplus.SettingsContentActivity.start(
                        requireContext(),
                        SettingsMenus.PANELS.first { it.id == SettingsMenus.CAR_APPEARANCE },
                    )
                }
            }
        }
        val savedWidth = DockPrefs.widthPercent(requireContext())
        widthSeek.progress = (savedWidth - DockPrefs.WIDTH_MIN) / DockPrefs.WIDTH_STEP
        widthValue.text = widthValue.context.getString(R.string.ui_percentage, savedWidth.toString())
        widthSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (isPhonePanel) PhonePanelUi.refreshStepper(widthSeek)
                    if (!fromUser) return
                    widthValue.text =
                        widthValue.context.getString(
                            R.string.ui_percentage,
                            (DockPrefs.WIDTH_MIN + progress * DockPrefs.WIDTH_STEP).toString(),
                        )
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent =
                        DockPrefs.WIDTH_MIN + widthSeek.progress * DockPrefs.WIDTH_STEP
                    DockPrefs.setWidthPercent(requireContext(), percent)
                    hostToast("Dock 栏宽度 $percent% 已保存，下次连接起效")
                }
            },
        )

        if (isPhonePanel) {
            PhonePanelUi.bindStepper(widthSeek) { progress ->
                val percent = DockPrefs.WIDTH_MIN + progress * DockPrefs.WIDTH_STEP
                widthValue.text = widthValue.context.getString(R.string.ui_percentage, percent.toString())
                DockPrefs.setWidthPercent(requireContext(), percent)
            }
        }
    }
}

class DefaultMapPanelFragment : SettingsPanelFragment() {
    private val mapPreferencesChanged =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "map_content_scale") {
                view?.findViewById<SeekBar>(R.id.mapScaleSeek)?.progress = (DefaultMapPrefs.contentScale(requireContext()) - 50) / 5
            }
        }

    /**
     * 只在面板可见时监听共享参数，另一端调整后同步实际比例
     */
    override fun onResume() {
        super.onResume()
        requireContext()
            .getSharedPreferences(
                "settings",
                android.content.Context.MODE_PRIVATE,
            ).registerOnSharedPreferenceChangeListener(mapPreferencesChanged)
        view?.findViewById<SeekBar>(R.id.mapScaleSeek)?.progress = (DefaultMapPrefs.contentScale(requireContext()) - 50) / 5
    }

    /**
     * 隐藏后解除监听，不持有旧面板控件
     */
    override fun onPause() {
        requireContext()
            .getSharedPreferences(
                "settings",
                android.content.Context.MODE_PRIVATE,
            ).unregisterOnSharedPreferenceChangeListener(mapPreferencesChanged)
        super.onPause()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(if (isPhonePanel) R.layout.fragment_phone_panel_map else R.layout.fragment_panel_map, container, false)

    override fun onViewCreated(view: View) {
        if (isPhonePanel) {
            val modes = view.findViewById<RadioGroup>(R.id.amapModeOptions)
            for (index in 0 until modes.childCount) (modes.getChildAt(index) as? RadioButton)?.let(PhonePanelUi::styleOption)
        }
        val context = requireContext().applicationContext
        val seek = view.findViewById<SeekBar>(R.id.mapScaleSeek)
        val label = view.findViewById<TextView>(R.id.mapScaleValue)
        val state = view.findViewById<TextView>(R.id.mapHandoffState)

        fun showScale() {
            label.text = getString(R.string.map_content_scale, 50 + seek.progress * 5)
        }
        seek.progress = (DefaultMapPrefs.contentScale(context) - 50) / 5
        showScale()
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) = Unit

                override fun onProgressChanged(
                    bar: SeekBar,
                    value: Int,
                    fromUser: Boolean,
                ) {
                    showScale()
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    val value = 50 + bar.progress * 5
                    DefaultMapPrefs.setContentScale(context, value)
                    VirtualLauncher.applyMapContentScale()
                }
            },
        )
        state.text = VirtualLauncher.mapHandoffStatus()
        val phone = view.findViewById<android.widget.Button>(R.id.mapToPhone)
        val car = view.findViewById<android.widget.Button>(R.id.mapToCar)

        fun handoff(toPhone: Boolean) {
            phone.isEnabled = false
            car.isEnabled = false
            state.text = "正在核验地图任务与显示结果…"
            Bg.run("map-handoff") {
                val message = VirtualLauncher.handoffMap(toPhone)
                view.post {
                    if (this.view !== view) return@post
                    state.text = message
                    phone.isEnabled = true
                    car.isEnabled = true
                }
            }
        }
        phone.setOnClickListener { handoff(true) }
        car.setOnClickListener { handoff(false) }
        reloadWhenVisible { loadApps { renderMapOptions(view) } }
    }

    /**
     * 使用共享应用快照构建地图选项，不在界面线程扫描或生成图标
     */
    private fun renderMapOptions(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.mapOptions)
        container.removeAllViews()
        view.findViewById<RadioGroup>(R.id.amapModeOptions).setOnCheckedChangeListener(null)
        view.findViewById<View>(R.id.amapModeSection).visibility = View.GONE
        val installed =
            VirtualLauncher.MAP_PACKAGES_PRIORITY.mapNotNull { pkg ->
                apps.firstOrNull { it.component.packageName == pkg }
            }
        if (installed.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "未安装任何受支持的地图应用（高德/百度/腾讯）"
                    setAppearanceTextColor(R.color.m3_on_var)
                    textSize = 13f
                },
            )
            return
        }
        var current =
            DefaultMapPrefs.manualPackage(requireContext())
                ?: installed.firstOrNull()?.component?.packageName
        installed.forEach { entry ->
            val pkg = entry.component.packageName
            val label = entry.label
            val row = layoutInflater.inflate(R.layout.item_map_option, container, false)
            val iconPx = (34 * resources.displayMetrics.density).toInt()
            AppIcons.bind(row.findViewById(R.id.mapOptionIcon), entry, iconPx, viewLifecycleOwner)
            row.findViewById<TextView>(R.id.mapOptionName).text = label
            val radio = row.findViewById<ImageView>(R.id.mapOptionRadio)
            if (isPhonePanel) row.minimumHeight = (64 * resources.displayMetrics.density).toInt()

            fun refresh() {
                radio.setAppearanceImageResource(
                    if (pkg == current) {
                        if (isPhonePanel) R.drawable.ic_check_circle else R.drawable.bg_radio_on
                    } else {
                        R.drawable.bg_radio_off
                    },
                )
                if (isPhonePanel) {
                    row.isSelected = pkg == current
                    row.setAppearanceBackground(if (pkg == current) R.drawable.bg_phone_nav_active else R.drawable.bg_segment)
                    radio.setAppearanceImageTint(R.color.m3_primary)
                }
            }
            refresh()
            row.setOnClickListener {
                current = pkg
                DefaultMapPrefs.set(requireContext(), pkg)
                for (i in 0 until container.childCount) {
                    (container.getChildAt(i).tag as? Function0<*>)?.invoke()
                }
                hostToast("默认地图：$label")
            }
            row.tag = { refresh() }
            container.addView(row)
        }
        if (installed.any { it.component.packageName == "com.autonavi.minimap" }) {
            view.findViewById<View>(R.id.amapModeSection).visibility = View.VISIBLE
            val modes = view.findViewById<RadioGroup>(R.id.amapModeOptions)
            modes.check(if (DefaultMapPrefs.amapCarPage(requireContext())) R.id.amapCarPage else R.id.amapNormalPage)
            modes.setOnCheckedChangeListener { _, checked ->
                DefaultMapPrefs.setAmapCarPage(requireContext(), checked == R.id.amapCarPage)
                hostToast("高德显示模式已保存，下次连接生效")
            }
        }
    }
}

/** 保存分辨率、码率和目标帧率；前两项下次连接生效，帧率可在支持范围内实时调整 */
class DisplayPanelFragment : SettingsPanelFragment() {
    private var refreshQuality: (() -> Unit)? = null
    private var customExpanded = false
    private var advancedExpanded = false
    private var qualityExpanded = false
    private val qualityTick =
        object : Runnable {
            override fun run() {
                refreshQuality?.invoke()
                view?.postDelayed(this, 1_000)
            }
        }

    /**
     * 设置显示期间更新当前编码配置，断开和帧率变化无需重进页面
     */
    override fun onResume() {
        super.onResume()
        view?.post(qualityTick)
    }

    /**
     * 页面不可见时停止刷新
     */
    override fun onPause() {
        view?.removeCallbacks(qualityTick)
        super.onPause()
    }

    /**
     * 释放对旧页面和控件的引用
     */
    override fun onDestroyView() {
        view?.removeCallbacks(qualityTick)
        refreshQuality = null
        super.onDestroyView()
    }

    /**
     * 保存手机折叠状态，旋转或进程恢复后不强制收起正在编辑的区域
     */
    override fun onSaveInstanceState(outState: Bundle) {
        view?.let { root ->
            if (isPhonePanel) {
                outState.putBoolean("phoneCustomExpanded", root.findViewById<View>(R.id.phoneScaleBody).isVisible)
                outState.putBoolean("phoneAdvancedExpanded", root.findViewById<View>(R.id.phoneAdvancedBody).isVisible)
                outState.putBoolean("phoneQualityExpanded", root.findViewById<View>(R.id.displayActiveQuality).isVisible)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        customExpanded =
            savedInstanceState?.getBoolean("phoneCustomExpanded")
                ?: (DisplayPrefs.scalePercent(requireContext()) !in DisplayPrefs.SCALE_PRESETS)
        advancedExpanded = savedInstanceState?.getBoolean("phoneAdvancedExpanded") ?: false
        qualityExpanded = savedInstanceState?.getBoolean("phoneQualityExpanded") ?: false
        return inflater.inflate(
            if (isPhonePanel) R.layout.fragment_phone_panel_display else R.layout.fragment_panel_display,
            container,
            false,
        )
    }

    override fun onViewCreated(view: View) {
        val ctx = requireContext()
        val scaleSeek = view.findViewById<SeekBar>(R.id.displayScaleSeek)
        val scaleValue = view.findViewById<TextView>(R.id.displayScaleValue)
        val scaleNote = view.findViewById<TextView>(R.id.displayScaleNote)
        val fps30 = view.findViewById<TextView>(R.id.btnFps30)
        val fps60 = view.findViewById<TextView>(R.id.btnFps60)
        val presets = view.findViewById<RadioGroup>(R.id.displayScalePresets)
        val presetNote = view.findViewById<TextView>(R.id.displayScalePresetNote)
        val activeQuality = view.findViewById<TextView>(R.id.displayActiveQuality)
        val bitrateNote = view.findViewById<TextView>(R.id.bitrateNote)
        if (isPhonePanel) {
            PhonePanelUi.bindDisclosure(view, R.id.phoneScaleToggle, R.id.phoneScaleBody, R.id.phoneScaleArrow, customExpanded)
            PhonePanelUi.bindDisclosure(view, R.id.phoneAdvancedToggle, R.id.phoneAdvancedBody, R.id.phoneAdvancedArrow, advancedExpanded)
            PhonePanelUi.bindDisclosure(view, R.id.phoneQualityToggle, R.id.displayActiveQuality, R.id.phoneQualityArrow, qualityExpanded)
        }
        var sampledSender: Any? = null
        var sampledAt = 0L
        var sampledFrames = 0L
        var measuredFps = "采样中"
        var syncingSelection = false
        var trackingScale = false
        var refreshSelection: (() -> Unit)? = null

        // 滑条 progress（0..26）→ 百分比（30..160，步进 5）
        fun progressOf(percent: Int) = (percent - DisplayPrefs.SCALE_MIN) / DisplayPrefs.SCALE_STEP

        fun percentOf(progress: Int) = DisplayPrefs.SCALE_MIN + progress * DisplayPrefs.SCALE_STEP

        val saved = DisplayPrefs.scalePercent(ctx)
        scaleSeek.progress = progressOf(saved)
        scaleValue.text = ctx.getString(R.string.ui_percentage, saved.toString())

        fun refreshScaleNote() {
            val percent = DisplayPrefs.scalePercent(ctx)
            scaleNote.text = ctx.getString(R.string.ui_saved_resolution, percent.toString())
            presetNote.text = if (percent in DisplayPrefs.SCALE_PRESETS) "已选快捷档位 $percent%" else "自定义 $percent%"
            val rate = DisplayPrefs.bitrateMbps(ctx)
            bitrateNote.text =
                if (isPhonePanel) {
                    if (rate == 0) "自动按画面与帧率匹配，范围 4～12 Mbps" else "已保存 $rate Mbps"
                } else {
                    "已保存码率：" + (if (rate == 0) "自动（4～12 Mbps）" else "$rate Mbps") + "；下次连接生效"
                }
            if (isPhonePanel) {
                view.findViewById<TextView>(R.id.phoneScaleCustomLabel).text =
                    if (percent in DisplayPrefs.SCALE_PRESETS) "自定义比例" else "自定义比例 · $percent%"
            }
            val sender = SessionController.videoSender?.takeIf { it.isRunning }
            val profile = sender?.activeProfile
            val now = android.os.SystemClock.elapsedRealtime()
            if (sender == null || sampledSender !== sender) {
                sampledSender = sender
                sampledAt = now
                sampledFrames = sender?.framesSentTotal ?: 0L
                measuredFps = "采样中"
            } else if (now - sampledAt >= 800) {
                val frames = sender.framesSentTotal
                measuredFps = "${maxOf(0L, frames - sampledFrames) * 1000 / (now - sampledAt)} fps"
                sampledAt = now
                sampledFrames = frames
            }
            activeQuality.text =
                if (profile == null) {
                    "当前未投屏；目标帧率 ${DisplayPrefs.fps(ctx)} fps"
                } else {
                    "当前编码：${profile.width} × ${profile.height}（${SessionController.activeScalePercent}%）\n" +
                        "配置码率 ${profile.bitrate / 1000} kbps · 帧率上限 ${SessionController.activeTargetFps} fps\n" +
                        "当前发送帧率 $measuredFps\n" +
                        "已保存目标帧率 ${DisplayPrefs.fps(ctx)} fps；实际受车机及编码器限制"
                }
            refreshSelection?.invoke()
        }
        refreshQuality = { refreshScaleNote() }
        refreshScaleNote()

        DisplayPrefs.SCALE_PRESETS.forEach { percent ->
            val option =
                RadioButton(ctx).apply {
                    id = View.generateViewId()
                    tag = percent
                    text = ctx.getString(R.string.ui_percentage, percent.toString())
                    textSize = 13f
                    minHeight = (40 * resources.displayMetrics.density).toInt()
                    setAppearanceTextColor(R.color.m3_on_surface)
                    io.github.shaonialife.buicklinkplus.ui.AppearanceBindings
                        .buttonTint(this)
                    if (isPhonePanel) PhonePanelUi.styleOption(this)
                }
            presets.addView(option, RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (percent == saved) presets.check(option.id)
        }
        presets.setOnCheckedChangeListener { group, checked ->
            if (syncingSelection) return@setOnCheckedChangeListener
            val percent = group.findViewById<RadioButton>(checked)?.tag as? Int ?: return@setOnCheckedChangeListener
            DisplayPrefs.setScalePercent(ctx, percent)
            scaleSeek.progress = progressOf(percent)
            scaleValue.text = ctx.getString(R.string.ui_percentage, percent.toString())
            refreshScaleNote()
            hostToast("分辨率 $percent% 已保存，下次连接生效")
        }

        scaleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (isPhonePanel) PhonePanelUi.refreshStepper(scaleSeek)
                    if (!fromUser) return
                    scaleValue.text = ctx.getString(R.string.ui_percentage, percentOf(progress).toString())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    trackingScale = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val percent = percentOf(scaleSeek.progress)
                    DisplayPrefs.setScalePercent(ctx, percent)
                    trackingScale = false
                    val checked =
                        (0 until presets.childCount)
                            .map { presets.getChildAt(it) }
                            .firstOrNull { it.tag == percent }
                            ?.id ?: View.NO_ID
                    syncingSelection = true
                    if (checked == View.NO_ID) presets.clearCheck() else presets.check(checked)
                    syncingSelection = false
                    hostToast("分辨率 $percent% 已保存，下次连接起效")
                    refreshScaleNote()
                }
            },
        )
        if (isPhonePanel) {
            PhonePanelUi.bindStepper(scaleSeek) { progress ->
                val percent = percentOf(progress)
                DisplayPrefs.setScalePercent(ctx, percent)
                scaleValue.text = ctx.getString(R.string.ui_percentage, percent.toString())
                refreshScaleNote()
            }
        }

        fun refreshFps(active30: Boolean) {
            if (isPhonePanel) {
                fps30.isSelected = active30
                fps60.isSelected = !active30
                fps30.stateDescription = if (active30) "已选择" else "未选择"
                fps60.stateDescription = if (active30) "未选择" else "已选择"
                return
            }
            // 未选中片用暗色底（与容器同色）而非 null 背景，避免背景重置内边距
            fps30.setAppearanceBackground(
                if (active30) R.drawable.bg_segment_sel else R.drawable.bg_segment,
            )
            fps30.setAppearanceTextColor(
                if (active30) R.color.m3_on_surface else R.color.m3_on_var,
            )
            fps60.setAppearanceBackground(
                if (active30) R.drawable.bg_segment else R.drawable.bg_segment_sel,
            )
            fps60.setAppearanceTextColor(
                if (active30) R.color.m3_on_var else R.color.m3_on_surface,
            )
        }

        val savedFps = DisplayPrefs.fps(ctx)
        refreshFps(savedFps < 60)

        fun applyFps(fps: Int) {
            DisplayPrefs.setFps(ctx, fps)
            refreshFps(fps < 60)
            val applied = VirtualLauncher.setStreamFps(fps)
            val live =
                SessionController.videoSender
                    ?.isRunning == true
            hostToast(if (live) "当前帧率上限 " + applied + "fps（受车机和编码器限制）" else "目标帧率已保存")
            if (live) SessionController.activeTargetFps = applied
            refreshScaleNote()
        }
        fps30.setOnClickListener { applyFps(30) }
        fps60.setOnClickListener { applyFps(60) }

        val extras = view.findViewById<LinearLayout>(R.id.displayExtraControls)

        fun addLabel(text: String): TextView =
            TextView(ctx).apply {
                this.text = text
                textSize = 13f
                setAppearanceTextColor(R.color.m3_on_surface)
                setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
                extras.addView(this)
            }
        val rates = listOf(0, 8, 12, 16, 20)
        val ratePicker = view.findViewById<RadioGroup>(R.id.bitrateOptions)
        val selectedRate = DisplayPrefs.bitrateMbps(ctx)
        rates.forEach { rate ->
            val option =
                RadioButton(ctx).apply {
                    id = View.generateViewId()
                    tag = rate
                    text =
                        if (rate == 0) {
                            if (isPhonePanel) "自动" else "自动（4～12 Mbps）"
                        } else {
                            "$rate Mbps"
                        }
                    textSize = 13f
                    minHeight = (40 * resources.displayMetrics.density).toInt()
                    setAppearanceTextColor(R.color.m3_on_surface)
                    io.github.shaonialife.buicklinkplus.ui.AppearanceBindings
                        .buttonTint(this)
                    if (isPhonePanel) PhonePanelUi.styleOption(this)
                }
            ratePicker.addView(
                option,
                RadioGroup.LayoutParams(
                    if (isPhonePanel) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (rate == selectedRate) ratePicker.check(option.id)
        }
        ratePicker.setOnCheckedChangeListener { group, checked ->
            if (syncingSelection) return@setOnCheckedChangeListener
            val rate = group.findViewById<RadioButton>(checked)?.tag as? Int
            if (rate != null) {
                DisplayPrefs.setBitrateMbps(ctx, rate)
                refreshScaleNote()
                hostToast("视频码率已保存，下次连接生效")
            }
        }
        addLabel(if (isPhonePanel) "画面边距" else "安全区自动适配；以下微调仅向内收缩，下次连接生效")
        val marginGrid =
            if (isPhonePanel) {
                addLabel("仅向内收缩 · 单位为原始像素 · 下次连接生效").apply {
                    setAppearanceTextColor(R.color.m3_on_var)
                    setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
                }
                android.widget.GridLayout(ctx).apply {
                    columnCount = 1
                    extras.addView(
                        this,
                        LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin =
                                (12 * resources.displayMetrics.density).toInt()
                        },
                    )
                }
            } else {
                null
            }
        val margins = DisplayPrefs.safeMargins(ctx)
        listOf("left" to "左", "top" to "上", "right" to "右", "bottom" to "下").forEachIndexed { index, side ->
            val cell =
                marginGrid?.let { grid ->
                    LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setAppearanceBackground(R.drawable.bg_phone_nav_active)
                        val inset = (8 * resources.displayMetrics.density).toInt()
                        setPadding(0, inset, 0, inset)
                        grid.addView(this)
                    }
                }
            val label =
                if (cell != null) {
                    TextView(ctx).apply {
                        text = ctx.getString(R.string.ui_margin_side, side.second)
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setAppearanceTextColor(R.color.m3_on_var)
                        cell.addView(this, LinearLayout.LayoutParams(-1, -2))
                    }
                } else {
                    addLabel(ctx.getString(R.string.ui_margin_value, side.second, margins[index].toString()))
                }
            val marginSeek =
                SeekBar(ctx).apply {
                    max = 120
                    progress = margins[index]
                    contentDescription = side.second + "侧画面留边"
                    if (isPhonePanel) minimumHeight = (48 * resources.displayMetrics.density).toInt()
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit

                            override fun onProgressChanged(
                                seekBar: SeekBar?,
                                progress: Int,
                                fromUser: Boolean,
                            ) {
                                if (isPhonePanel) seekBar?.let(PhonePanelUi::refreshStepper)
                                if (!fromUser) return
                                DisplayPrefs.setSafeMargin(ctx, side.first, progress)
                                if (!isPhonePanel) label.text = ctx.getString(R.string.ui_margin_value, side.second, progress.toString())
                            }
                        },
                    )
                }
            (cell ?: extras).addView(marginSeek, LinearLayout.LayoutParams(-1, -2))
            if (isPhonePanel) {
                PhonePanelUi.bindStepper(marginSeek, valueText = { it.toString() }) { progress ->
                    DisplayPrefs.setSafeMargin(ctx, side.first, progress)
                }
            }
        }
        marginGrid?.apply {
            // 使用实际可用宽度，旋转或大字体时自动切成单列，保证加减按钮不被压缩
            addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                val density = resources.displayMetrics.density
                val gap = (12 * density).toInt()
                val minCell = ((132 + 64 * (resources.configuration.fontScale - 1).coerceAtLeast(0f)) * density).toInt()
                val columns = if (right - left >= 2 * minCell + gap) 2 else 1
                if (columnCount != columns || getChildAt(0)?.layoutParams?.width != 0) {
                    columnCount = columns
                    for (index in 0 until childCount) {
                        getChildAt(index).layoutParams =
                            android.widget.GridLayout.LayoutParams().apply {
                                width = 0
                                height = ViewGroup.LayoutParams.WRAP_CONTENT
                                rowSpec = android.widget.GridLayout.spec(index / columns)
                                columnSpec = android.widget.GridLayout.spec(index % columns, 1f)
                                topMargin = if (index >= columns) gap else 0
                                marginEnd = if (columns == 2 && index % columns == 0) gap else 0
                            }
                    }
                }
            }
        }

        // 触摸注入节流（0=关闭，步进 8ms）：缓解「交互滞后但帧率正常」的事件堆积
        val throttleSeek = view.findViewById<SeekBar>(R.id.throttleSeek)
        val throttleValue = view.findViewById<TextView>(R.id.throttleValue)

        fun throttleText(ms: Int) = if (ms <= 0) "关闭" else "${ms}ms"

        val savedThrottle = DisplayPrefs.moveThrottleMs(ctx)
        throttleSeek.progress = savedThrottle / 8
        throttleValue.text = throttleText(savedThrottle)
        TouchRouter.moveThrottleMs = savedThrottle.toLong()

        throttleSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    if (isPhonePanel) PhonePanelUi.refreshStepper(throttleSeek)
                    if (!fromUser) return
                    val ms = progress * 8
                    throttleValue.text = throttleText(ms)
                    DisplayPrefs.setMoveThrottleMs(ctx, ms)
                    TouchRouter.moveThrottleMs = ms.toLong()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    hostToast(
                        "触摸节流 " + throttleText(DisplayPrefs.moveThrottleMs(ctx)),
                    )
                }
            },
        )
        if (isPhonePanel) {
            throttleValue.visibility = View.GONE
            PhonePanelUi.bindStepper(throttleSeek, valueText = { throttleText(it * 8) }) { progress ->
                val ms = progress * 8
                throttleValue.text = throttleText(ms)
                DisplayPrefs.setMoveThrottleMs(ctx, ms)
                TouchRouter.moveThrottleMs = ms.toLong()
            }
        }
        refreshSelection = {
            syncingSelection = true
            try {
                if (!trackingScale) {
                    val percent = DisplayPrefs.scalePercent(ctx)
                    scaleSeek.progress = progressOf(percent)
                    scaleValue.text = ctx.getString(R.string.ui_percentage, percent.toString())
                    val id =
                        (0 until presets.childCount)
                            .map { presets.getChildAt(it) }
                            .firstOrNull { it.tag == percent }
                            ?.id ?: View.NO_ID
                    if (id == View.NO_ID) presets.clearCheck() else presets.check(id)
                }
                val rate = DisplayPrefs.bitrateMbps(ctx)
                (0 until ratePicker.childCount)
                    .map { ratePicker.getChildAt(it) }
                    .firstOrNull { it.tag == rate }
                    ?.let { ratePicker.check(it.id) }
                refreshFps(DisplayPrefs.fps(ctx) < 60)
            } finally {
                syncingSelection = false
            }
        }
        refreshScaleNote()
        addCarLifeCompositionControls(view, ctx)
        // 动态滑条也在首次打开时采用所在显示屏的主题
        io.github.shaonialife.buicklinkplus.ui.AppearanceBindings
            .refresh(view)
    }

    /** CarLife 合成相关：壁纸背景开关 + 画面圆角开关（写入 CarLifePrefs） */
    private fun addCarLifeCompositionControls(
        root: View,
        ctx: android.content.Context,
    ) {
        val body = (root as? android.widget.ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        val density = resources.displayMetrics.density

        fun switchCard(
            title: String,
            hint: String,
            checked: Boolean,
            onChange: (Boolean) -> Unit,
        ) {
            val card =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    val pad = (18 * density).toInt()
                    setPadding(pad, (14 * density).toInt(), pad, (14 * density).toInt())
                    setAppearanceBackground(if (isPhonePanel) R.drawable.bg_phone_card else R.drawable.bg_card)
                }
            card.addView(
                TextView(ctx).apply {
                    text = title
                    textSize = 15f
                    setAppearanceTextColor(R.color.m3_on_surface)
                },
            )
            card.addView(
                TextView(ctx).apply {
                    text = hint
                    textSize = 12f
                    setAppearanceTextColor(R.color.m3_on_var)
                },
            )
            val switch =
                androidx.appcompat.widget.SwitchCompat(ctx).apply {
                    isChecked = checked
                    thumbDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.sw_thumb)
                    trackDrawable = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.sw_track)
                }
            switch.setOnCheckedChangeListener { _, value -> onChange(value) }
            card.addView(switch, LinearLayout.LayoutParams(-2, -2))
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
        }
        switchCard(
            "壁纸背景",
            "CarLife 画面合成时使用壁纸背景；关闭后使用纯色底",
            CarLifePrefs.wallpaperEnabled(ctx),
        ) { enabled ->
            CarLifePrefs.setWallpaperEnabled(ctx, enabled)
            hostToast(if (enabled) "壁纸背景已开启" else "壁纸背景已关闭")
        }
        switchCard(
            "画面圆角",
            "车机画面四角圆角 ${CarLifePrefs.CORNER_RADIUS_ON}dp；关闭为直角",
            CarLifePrefs.cornerEnabled(ctx),
        ) { enabled ->
            CarLifePrefs.setCornerEnabled(ctx, enabled)
            hostToast(if (enabled) "画面圆角已开启" else "画面圆角已关闭")
        }

        // CarLife 合成安全区内收边距（独立于 link_plus 旧的四边逐项安全区）
        val marginCard =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (18 * density).toInt()
                setPadding(pad, (14 * density).toInt(), pad, (14 * density).toInt())
                setAppearanceBackground(if (isPhonePanel) R.drawable.bg_phone_card else R.drawable.bg_card)
            }
        marginCard.addView(
            TextView(ctx).apply {
                text = "安全区边距（CarLife 合成）"
                textSize = 15f
                setAppearanceTextColor(R.color.m3_on_surface)
            },
        )
        marginCard.addView(
            TextView(ctx).apply {
                text = "画面四周内收，单位 dp；0=不使用安全区"
                textSize = 12f
                setAppearanceTextColor(R.color.m3_on_var)
            },
        )
        val marginValue =
            TextView(ctx).apply {
                text = "${CarLifePrefs.safeMargin(ctx)} dp"
                textSize = 12f
                setAppearanceTextColor(R.color.m3_on_var)
            }
        val marginSeek =
            SeekBar(ctx).apply {
                max = CarLifePrefs.SAFE_MARGIN_MAX / 4
                progress = CarLifePrefs.safeMargin(ctx) / 4
            }
        marginSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean,
                ) {
                    marginValue.text = "${progress * 4} dp"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val dp = (seekBar?.progress ?: 0) * 4
                    CarLifePrefs.setSafeMargin(ctx, dp)
                    hostToast("安全区边距已保存：$dp dp")
                }
            },
        )
        marginCard.addView(marginSeek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })
        marginCard.addView(marginValue, LinearLayout.LayoutParams(-1, -2))
        body.addView(marginCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * density).toInt() })
    }
}
