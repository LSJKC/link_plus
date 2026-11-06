package io.github.shaonialife.buicklinkplus.launcher.settings

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.net.toUri
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.DesktopApps
import io.github.shaonialife.buicklinkplus.launcher.InstalledCarApp
import io.github.shaonialife.buicklinkplus.launcher.OfficialCarAppDiscovery
import io.github.shaonialife.buicklinkplus.launcher.OfficialCarApps
import io.github.shaonialife.buicklinkplus.launcher.PhoneCarPages
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.ui.CarUi
import io.github.shaonialife.buicklinkplus.ui.PhoneOptionGroup
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/** 官方车载应用安装引导和桌面选择，复用原组件启动流程 */
class OfficialCarAppsPanelFragment : SettingsPanelFragment() {
    private var content: LinearLayout? = null
    private var generation = 0L

    /**
     * 页面只管理安装和入口，不承载或仿制第三方播放界面
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        ScrollView(requireContext()).apply {
            content =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    setPadding(padding, padding, padding, padding)
                }
            addView(content)
        }

    /**
     * 复用应用清单变化和桌面偏好监听，证书检查放在后台
     */
    override fun onViewCreated(view: View) {
        content?.let { note(it, "正在检测已安装的车载应用…") }
        reloadWhenVisible { refresh() }
    }

    /** 刷新时检查包代次和页面状态，丢弃旧清单 */
    private fun refresh() {
        loadApps {
            val root = view ?: return@loadApps
            val context = root.context.applicationContext
            val request = ++generation
            val revision = VirtualLauncher.appsCacheVersion
            val snapshot = apps.toList()
            Bg.run("official-car-apps") {
                val installed = OfficialCarAppDiscovery.scan(context, snapshot)
                val qqCarAvailable = PhoneCarPages.qqCarComponent(context) != null
                root.post {
                    if (view !== root || !isResumed || generation != request) return@post
                    if (revision != VirtualLauncher.appsCacheVersion) {
                        refresh()
                        return@post
                    }
                    render(installed, qqCarAvailable)
                }
            }
        }
    }

    /**
     * 展示实际安装状态，用户主动添加时才修改桌面选择
     */
    private fun render(
        installed: List<InstalledCarApp>,
        qqCarAvailable: Boolean,
    ) {
        val body = content ?: return
        body.removeAllViews()
        note(body, "独立车载版需从官方网站另行安装；手机版沿用手机已有应用。账号、内容和声音以实际应用为准。")
        val selected = DesktopApps.selected(requireContext(), apps)
        var previousCategory: OfficialCarApps.Category? = null
        installed.sortedBy { it.definition.category.ordinal }.forEach { app ->
            if (previousCategory != app.definition.category) {
                previousCategory = app.definition.category
                note(body, app.definition.category.title, 20f)
            }
            val section =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = app.definition.packageName
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                }
            body.addView(section)
            note(section, app.definition.title, 18f)
            val status =
                if (app.versionCode == null) {
                    app.problem.orEmpty()
                } else {
                    val versionStatus =
                        if (app.definition.contentObserved &&
                            app.signatureMatches &&
                            app.versionCode == app.definition.observedVersionCode
                        ) {
                            "此版本已观察到内容页面，登录播放和车机声音仍待验证"
                        } else {
                            "仅核对安装身份，实际页面、导航或播放仍需验证"
                        }
                    "已安装 ${app.versionName}\n${app.problem ?: "官网包签名匹配"}\n$versionStatus"
                }
            note(section, status)
            note(section, app.definition.note)
            if (app.canAddToDesktop) {
                val component = checkNotNull(app.component)
                val key = component.flattenToString()
                action(section, if (key in selected) "从桌面移除" else "添加到车机桌面") {
                    // 每次操作读取最新选择，保留其他页面的修改及未安装应用的已有偏好
                    val selection = DesktopApps.selected(requireContext(), apps).toMutableSet()
                    if (!selection.remove(key)) selection.add(key)
                    DesktopApps.save(requireContext(), selection)
                    refresh()
                }
                action(section, if (isPhonePanel) "在手机打开" else "打开应用") {
                    if (isPhonePanel) {
                        openOnPhone(Intent.makeMainActivity(component))
                    } else {
                        val root = view ?: return@action
                        Bg.run("open-official-car-app") {
                            val opened = VirtualLauncher.launchApp(component)
                            root.post {
                                if (view === root && isResumed && !opened) hostToast("未确认应用在车机显示，请查看手机是否需要首次授权或登录")
                            }
                        }
                    }
                }
            }
            if (isPhonePanel) {
                action(section, "前往官方网站") { openOnPhone(Intent(Intent.ACTION_VIEW, (app.definition.website).toUri())) }
            } else {
                note(section, "下载安装请在手机设置的“车载应用与手机版”中操作")
            }
        }
        note(body, "手机版应用", 20f)
        note(body, "沿用手机已安装的应用和账号。车载通道取决于应用实际入口及接收平台；普通页面仍可从车机桌面打开。")
        PhoneCarPages.channels.forEach { channel ->
            val section =
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
                }
            body.addView(section)
            note(section, channel.title, 18f)
            val candidates = apps.filter { it.component.packageName == channel.packageName }
            note(section, if (candidates.isEmpty()) "未检测到可启动的手机版" else "已检测到手机版")
            note(section, channel.status)
            val entry = candidates.singleOrNull()
            if (entry != null) {
                val key = entry.component.flattenToString()
                action(section, if (key in selected) "从桌面移除" else "添加到车机桌面") {
                    val selection = DesktopApps.selected(requireContext(), apps).toMutableSet()
                    if (!selection.remove(key)) selection.add(key)
                    DesktopApps.save(requireContext(), selection)
                    refresh()
                }
            } else if (candidates.size > 1) {
                note(section, "该应用有多个桌面入口，请在“管理桌面应用”中选择")
            }
            if (channel.packageName == PhoneCarPages.QQ_MUSIC && candidates.isNotEmpty()) {
                note(section, if (qqCarAvailable) "当前安装包签名和 UCAR 入口已核对；实际内容、触控和声音仍需车机验证" else "当前安装包未通过官方签名及 UCAR 入口核对")
                val normalId = View.generateViewId()
                val carId = View.generateViewId()
                val modes =
                    (if (isPhonePanel) PhoneOptionGroup(requireContext()) else RadioGroup(requireContext())).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            RadioButton(context).apply {
                                id = normalId
                                text = "普通页"
                                if (isPhonePanel) PhonePanelUi.styleOption(this) else setAppearanceTextColor(R.color.m3_on_surface)
                            },
                            RadioGroup.LayoutParams(if (isPhonePanel) -2 else 0, -2).apply { if (!isPhonePanel) weight = 1f },
                        )
                        addView(
                            RadioButton(context).apply {
                                id = carId
                                text = "车载页（试用）"
                                isEnabled = qqCarAvailable
                                if (isPhonePanel) PhonePanelUi.styleOption(this) else setAppearanceTextColor(R.color.m3_on_surface)
                            },
                            RadioGroup.LayoutParams(if (isPhonePanel) -2 else 0, -2).apply { if (!isPhonePanel) weight = 1f },
                        )
                        check(if (PhoneCarPages.qqCarEnabled(requireContext())) carId else normalId)
                        setOnCheckedChangeListener { _, checkedId ->
                            PhoneCarPages.setQqCarEnabled(requireContext(), checkedId == carId)
                            hostToast("QQ 音乐显示模式已保存，下次打开生效")
                        }
                    }
                section.addView(modes)
            }
        }
        action(body, "重新检测") {
            VirtualLauncher.invalidateAppsCache()
        }
        body.tag = "official-car-apps-ready"
    }

    /**
     * 手机入口显式选择主屏，避免车机会话抢走浏览器或首次登录页面
     */
    private fun openOnPhone(intent: Intent) {
        try {
            startActivity(intent, ActivityOptions.makeBasic().apply { launchDisplayId = 0 }.toBundle())
        } catch (_: Exception) {
            hostToast("无法打开，请在手机浏览器或应用桌面手动操作")
        }
    }

    /**
     * 说明使用两端已有的语义颜色，宽度随宿主自适应
     */
    private fun note(
        parent: LinearLayout,
        value: String,
        size: Float = 14f,
    ) {
        parent.addView(
            TextView(requireContext()).apply {
                text = value
                textSize = size
                setAppearanceTextColor(if (size > 14f) R.color.m3_on_surface else R.color.m3_on_var)
                setPadding(0, 0, 0, (8 * resources.displayMetrics.density).toInt())
            },
        )
    }

    /** 按钮纵向排列，点击高度至少 48dp */
    private fun action(
        parent: LinearLayout,
        title: String,
        click: () -> Unit,
    ) {
        parent.addView(
            Button(requireContext()).apply {
                text = title
                minHeight = (48 * resources.displayMetrics.density).toInt()
                if (isPhonePanel) {
                    setAppearanceBackground(R.drawable.bg_phone_card)
                    setAppearanceTextColor(R.color.m3_on_surface)
                } else {
                    CarUi.styleAction(this)
                }
                setOnClickListener { click() }
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() },
        )
    }

    /**
     * 隐藏后使包检查和打开结果失效，不暂停第三方播放器
     */
    override fun onPause() {
        generation++
        super.onPause()
    }

    /**
     * 销毁页面释放视图引用，后台结果不再更新旧页面
     */
    override fun onDestroyView() {
        generation++
        content = null
        super.onDestroyView()
    }
}
