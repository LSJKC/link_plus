package io.github.shaonialife.buicklinkplus.launcher.settings

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgetBindActivity
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgetProvider
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgets
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.ui.CarUi
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/** 手机选择并授权官方组件，车机只管理已存在的绑定 */
class DashboardWidgetPanelFragment : SettingsPanelFragment() {
    private var content: LinearLayout? = null
    private var generation = 0L
    private var bindingLaunched = false
    private val bindingResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            bindingLaunched = false
            refresh()
        }
    private val preferencesChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

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

    override fun onViewCreated(view: View) {
        reloadWhenVisible { refresh() }
    }

    /** 后台读取包、名称和证书，丢弃旧页面结果 */
    private fun refresh() {
        val root = view ?: return
        val app = root.context.applicationContext
        val request = ++generation
        val revision = VirtualLauncher.appsCacheVersion
        Bg.run("dashboard-widget-providers") {
            val result = runCatching { DashboardWidgets.discover(app) }
            val selected = DashboardWidgets.selected(app)
            val bound = runCatching { AppWidgetManager.getInstance(app).getAppWidgetInfo(selected)?.provider }.getOrNull()
            root.post {
                if (view !== root || !isResumed || generation != request) return@post
                if (revision != VirtualLauncher.appsCacheVersion || selected != DashboardWidgets.selected(app)) {
                    refresh()
                    return@post
                }
                val body = content ?: return@post
                body.removeAllViews()
                note(body, "仪表盘右侧上方显示官方车载应用的小组件，下方显示当前播放器的歌词。组件内布局、按钮和底板由原应用提供；歌词沿用现有来源，空间不足时可切回“音乐卡 / 歌词”查看。")
                note(body, "请先在车载应用中完成首次授权和登录。QQ 车载版 3.16.0.2 在本地测试中还需要文件权限；无组件内容时可点击等待提示打开原应用，再返回仪表盘。")
                val providers = result.getOrDefault(emptyList())
                note(
                    body,
                    if (selected >
                        0
                    ) {
                        "当前组件：${providers.firstOrNull { it.info.provider == bound }?.title ?: "绑定已失效，请重新选择"}"
                    } else {
                        "当前使用原音乐卡，尚未绑定官方组件"
                    },
                )
                if (selected > 0) {
                    action(body, "恢复原音乐卡") {
                        DashboardWidgets.change { DashboardWidgets.remove(app) }
                    }
                }
                if (DashboardWidgets.pending(app) != null) {
                    note(body, "有一项未完成的授权，请先取消再重新选择；原组件继续保留")
                    action(body, "取消未完成授权") {
                        DashboardWidgets.change { DashboardWidgets.cancel(app) }
                    }
                }
                if (!isPhonePanel || activity?.display?.displayId != 0) {
                    note(body, "首次选择和系统授权请在手机完成")
                    action(body, "在手机配置音乐组件") {
                        runCatching {
                            SettingsContentActivity.start(
                                requireContext(),
                                SettingsMenus.Item(SettingsMenus.DISPLAY, "仪表盘音乐组件", "已从设置菜单移除", R.drawable.ic_music),
                                onPhone = true,
                            )
                        }.onFailure { hostToast("请在手机设置中打开“仪表盘音乐组件”") }
                    }
                } else {
                    renderProviders(body, providers, result.isFailure)
                }
                action(body, "重新检测") { VirtualLauncher.invalidateAppsCache() }
                body.tag = "dashboard-widgets-ready"
            }
        }
    }

    private fun renderProviders(
        body: LinearLayout,
        providers: List<DashboardWidgetProvider>,
        failed: Boolean,
    ) {
        if (providers.isEmpty()) note(body, if (failed) "检测失败，请稍后重试" else "未找到已核对的通用官方组件。请先安装 QQ 音乐、酷狗或网易云的独立车载版；手机版不会自动转换为车载组件。")
        providers.forEach { provider ->
            note(body, "${provider.title}\n最小内容区域 ${provider.widthDp} × ${provider.heightDp} dp，右侧过窄时需拖动分隔条扩大音乐区")
            action(body, "使用此组件") {
                if (bindingLaunched) return@action
                if (DashboardWidgets.pending(requireContext()) != null) {
                    hostToast("请先取消未完成的授权")
                    return@action
                }
                try {
                    bindingLaunched = true
                    bindingResult.launch(
                        Intent(
                            requireContext(),
                            DashboardWidgetBindActivity::class.java,
                        ).putExtra("provider", provider.info.provider.flattenToString()),
                    )
                } catch (_: Exception) {
                    bindingLaunched = false
                    hostToast("无法打开系统授权，请在手机重试")
                }
            }.tag = provider.info.provider.flattenToString()
        }
    }

    private fun note(
        parent: LinearLayout,
        value: String,
    ) {
        parent.addView(
            TextView(requireContext()).apply {
                text = value
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            },
        )
    }

    private fun action(
        parent: LinearLayout,
        title: String,
        click: () -> Unit,
    ): Button =
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
            parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() })
        }

    override fun onPause() {
        generation++
        requireContext()
            .getSharedPreferences(DashboardWidgets.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        requireContext()
            .getSharedPreferences(DashboardWidgets.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(preferencesChanged)
    }

    override fun onDestroyView() {
        generation++
        content = null
        super.onDestroyView()
    }
}
