package io.github.shaonialife.buicklinkplus.launcher.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.WeatherState
import io.github.shaonialife.buicklinkplus.render.LeftWidgetColumn
import io.github.shaonialife.buicklinkplus.ui.AppearanceBindings
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.CarUi
import io.github.shaonialife.buicklinkplus.ui.PhoneOptionGroup
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.UiAppearance
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/**
 * 两端共用的车机背景设置，读取旧偏好但不在初始化时保存
 */
class CarAppearancePanelFragment : SettingsPanelFragment() {
    /**
     * 控件在创建视图时完成绑定
     */
    override fun onViewCreated(view: View) = Unit

    private val sliders = mutableListOf<Pair<SeekBar, TextView>>()
    private val presets = mutableListOf<Pair<Button, CarAppearancePrefs.Preset>>()
    private var panelOutline: SwitchCompat? = null
    private var musicIsland: SwitchCompat? = null
    private val widgetSwitches = mutableListOf<Pair<SwitchCompat, LeftWidgetColumn.Widget>>()
    private val cityButtons = mutableListOf<Pair<Button, String>>()
    private var weatherChoices: PhoneOptionGroup? = null
    private val appearanceChanged: () -> Unit = { refreshValues() }

    /**
     * 使用同一套参数和预设，手机端保留加减微调与原有卡片风格
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = ((if (isPhonePanel) 16 else 20) * density).toInt()
                setPadding(padding, padding, padding, padding)
            }
        body.addView(
            TextView(context).apply {
                text = "统一调整车机侧栏、桌面、音乐卡和设置背景，拖动立即生效。透明度越高，壁纸越清楚；文字、图标与地图保持清晰"
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, 0, 0, (16 * density).toInt())
            },
        )
        val presetSection = section(body, "外观预设")
        val presetOptions =
            if (isPhonePanel) {
                PhoneOptionGroup(context).also {
                    presetSection.addView(it, LinearLayout.LayoutParams(-1, -2))
                }
            } else {
                presetSection
            }
        CarAppearancePrefs.Preset.entries.forEach { preset ->
            val button =
                Button(context).apply {
                    text = preset.title
                    if (isPhonePanel) stylePhoneChoice(this) else CarUi.styleAction(this)
                    setOnClickListener {
                        CarAppearancePrefs.applyPreset(context, preset)
                        refreshValues()
                    }
                }
            presets += button to preset
            if (isPhonePanel) {
                presetOptions.addView(button, RadioGroup.LayoutParams(-2, -2))
            } else {
                presetOptions.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * density).toInt() })
            }
        }
        listOf("背景透明度", "背景模糊").forEachIndexed { index, title ->
            val card =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (16 * density).toInt()
                    setPadding(padding, padding, padding, padding)
                    if (isPhonePanel) setAppearanceBackground(R.drawable.bg_phone_card)
                }
            val value =
                TextView(context).apply {
                    textSize = 16f
                    setAppearanceTextColor(R.color.m3_on_surface)
                }
            val slider =
                SeekBar(context).apply {
                    max = 100
                    contentDescription = title
                }
            sliders += slider to value
            card.addView(value)
            card.addView(slider, LinearLayout.LayoutParams(-1, (48 * density).toInt()))
            slider.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    /**
                     * 只保存真实拖动，程序同步数值不会反向覆盖配置
                     */
                    override fun onProgressChanged(
                        seekBar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (isPhonePanel) PhonePanelUi.refreshStepper(slider)
                        if (!fromUser) return
                        if (index == 0) {
                            CarAppearancePrefs.setTransparency(context, progress)
                        } else {
                            CarAppearancePrefs.setBlur(context, progress)
                        }
                        refreshValues()
                    }

                    /**
                     * 拖动开始不额外写入参数
                     */
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    /**
                     * 参数已实时保存，松手不重复通知
                     */
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                },
            )
            if (isPhonePanel) {
                PhonePanelUi.bindStepper(slider) { progress ->
                    if (index == 0) {
                        CarAppearancePrefs.setTransparency(context, progress)
                    } else {
                        CarAppearancePrefs.setBlur(context, progress)
                    }
                    refreshValues()
                }
            }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
        }
        val effects = section(body, "面板与浮层")
        panelOutline =
            SwitchCompat(context).apply {
                id = R.id.panelOutlineSwitch
                setText(R.string.car_panel_outline_title)
                setAppearanceTextColor(R.color.m3_on_surface)
                textSize = 16f
                minHeight = (52 * density).toInt()
                isChecked = CarAppearancePrefs.panelOutlineVisible(context)
                setOnCheckedChangeListener { _, checked ->
                    if (checked != CarAppearancePrefs.panelOutlineVisible(context)) {
                        CarAppearancePrefs.setPanelOutlineVisible(context, checked)
                    }
                }
            }
        effects.addView(panelOutline, LinearLayout.LayoutParams(-1, -2))
        effects.addView(
            TextView(context).apply {
                setText(R.string.car_panel_outline_hint)
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            },
        )
        musicIsland =
            SwitchCompat(context).apply {
                id = R.id.musicIslandSwitch
                setText(R.string.car_music_island_title)
                setAppearanceTextColor(R.color.m3_on_surface)
                textSize = 16f
                minHeight = (52 * density).toInt()
                isChecked = CarAppearancePrefs.musicIslandVisible(context)
                setOnCheckedChangeListener { _, checked ->
                    if (checked != CarAppearancePrefs.musicIslandVisible(context)) {
                        CarAppearancePrefs.setMusicIslandVisible(context, checked)
                    }
                }
            }
        effects.addView(musicIsland, LinearLayout.LayoutParams(-1, -2))
        effects.addView(
            TextView(context).apply {
                setText(R.string.car_music_island_hint)
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            },
        )
        val widgets = section(body, "左侧小组件")
        widgetSwitches.clear()
        val chosen = CarAppearancePrefs.leftWidgets(context)
        for (widget in LeftWidgetColumn.Widget.displayOrder) {
            val label =
                when (widget) {
                    LeftWidgetColumn.Widget.MUSIC -> "歌名与封面"
                    LeftWidgetColumn.Widget.LYRICS -> "歌词"
                    LeftWidgetColumn.Widget.NAV -> "导航指向"
                    LeftWidgetColumn.Widget.TRIP -> "行程信息"
                    LeftWidgetColumn.Widget.MUSIC_CONTROL -> "音乐控制"
                    LeftWidgetColumn.Widget.CLOCK -> "时间"
                    LeftWidgetColumn.Widget.CALENDAR -> "日历"
                    LeftWidgetColumn.Widget.WEATHER -> "天气"
                    LeftWidgetColumn.Widget.PARKING -> "停车计时"
                    LeftWidgetColumn.Widget.QUICK_APPS -> "快捷应用"
                }
            val toggle =
                SwitchCompat(context).apply {
                    text = label
                    setAppearanceTextColor(R.color.m3_on_surface)
                    textSize = 16f
                    minHeight = (52 * density).toInt()
                    isChecked = widget.id in chosen
                    setOnCheckedChangeListener { _, _ -> saveLeftWidgets() }
                }
            widgets.addView(toggle, LinearLayout.LayoutParams(-1, -2))
            widgetSwitches += toggle to widget
        }
        widgets.addView(
            TextView(context).apply {
                text = "组件显示在车机安全区左侧，按上方顺序排列。建议选择不超过 5 个，保留清楚的阅读空间"
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            },
        )
        val weather = section(body, "天气位置")
        val locations = listOf(WeatherState.AUTO to "当前定位") + WeatherState.presetCities.map { it.name to it.name }
        if (isPhonePanel) {
            weatherChoices =
                PhoneOptionGroup(context)
                    .apply {
                        tag = "weather-city-options"
                        locations.forEach { (value, label) ->
                            val option =
                                RadioButton(context).apply {
                                    id = View.generateViewId()
                                    tag = value
                                    text = label
                                    PhonePanelUi.styleOption(this)
                                }
                            addView(option, RadioGroup.LayoutParams(-2, -2))
                            if (value == CarAppearancePrefs.weatherCity(context)) check(option.id)
                        }
                        setOnCheckedChangeListener { group, checked ->
                            val value = group.findViewById<RadioButton>(checked)?.tag as? String ?: return@setOnCheckedChangeListener
                            if (value != CarAppearancePrefs.weatherCity(context)) {
                                CarAppearancePrefs.setWeatherCity(context, value)
                                WeatherState.refresh(context, value)
                            }
                        }
                    }.also { weather.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        } else {
            locations.forEach { (value, label) ->
                val button = Button(context).apply { text = label }
                bindWeatherChoice(button, value, cityButtons)
                weather.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * density).toInt() })
            }
        }
        weather.addView(
            TextView(context).apply {
                setText(R.string.weather_refresh_explanation)
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
            },
        )
        restyleCityButtons(cityButtons)
        refreshValues()
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        }
    }

    /**
     * 手机按功能划分独立卡片，车机保留原面板滚动和密度
     */
    private fun section(
        parent: LinearLayout,
        title: String,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val group =
            if (isPhonePanel) {
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (16 * density).toInt()
                    setPadding(padding, padding, padding, padding)
                    setAppearanceBackground(R.drawable.bg_phone_card)
                    parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
                }
            } else {
                parent
            }
        group.addView(
            TextView(requireContext()).apply {
                text = title
                textSize = 16f
                paint.isFakeBoldText = true
                setAppearanceTextColor(R.color.m3_on_surface)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() },
        )
        return group
    }

    /** 手机预设使用圆角按钮，去掉系统默认边框和内缩 */
    private fun stylePhoneChoice(button: Button) {
        val density = resources.displayMetrics.density
        AppearanceBindings.clearBackgroundTint(button)
        button.setAppearanceBackground(R.drawable.bg_phone_option)
        button.setAppearanceTextColor(R.color.phone_option_text)
        button.stateListAnimator = null
        button.isAllCaps = false
        button.minimumWidth = (72 * density).toInt()
        button.minimumHeight = (48 * density).toInt()
        button.textSize = 14f
        val padding = (16 * density).toInt()
        button.setPadding(padding, (10 * density).toInt(), padding, (10 * density).toInt())
    }

    /** 汇总勾选的组件并保存（全不选时左列整体隐藏） */
    private fun saveLeftWidgets() {
        val context = context ?: return
        val ids =
            widgetSwitches
                .filter { (toggle, _) -> toggle.isChecked }
                .map { (_, widget) -> widget.id }
                .toSet()
        if (ids != CarAppearancePrefs.leftWidgets(context)) {
            CarAppearancePrefs.setLeftWidgets(context, ids)
        }
    }

    /** 当前天气位置高亮，按钮统一走车机操作样式 */
    private fun restyleCityButtons(buttons: List<Pair<Button, String>>) {
        val context = context ?: return
        val current = CarAppearancePrefs.weatherCity(context)
        buttons.forEach { (button, value) ->
            button.setAppearanceTextColor(if (value == current) R.color.m3_primary else R.color.car_action_text)
        }
    }

    private fun bindWeatherChoice(
        button: Button,
        value: String,
        buttons: MutableList<Pair<Button, String>>,
    ) {
        val context = context ?: return
        CarUi.styleAction(button)
        button.setOnClickListener {
            CarAppearancePrefs.setWeatherCity(context, value)
            WeatherState.refresh(context, value)
            restyleCityButtons(buttons)
        }
        buttons += button to value
    }

    /**
     * 当前值与预设同时对账，自定义组合不误标为某个预设
     */
    private fun refreshValues() {
        val context = context ?: return
        panelOutline?.isChecked = CarAppearancePrefs.panelOutlineVisible(context)
        musicIsland?.isChecked = CarAppearancePrefs.musicIslandVisible(context)
        val chosen = CarAppearancePrefs.leftWidgets(context)
        widgetSwitches.forEach { (toggle, widget) -> toggle.isChecked = widget.id in chosen }
        val values = listOf(CarAppearancePrefs.transparency(context), CarAppearancePrefs.blur(context))
        weatherChoices?.let { group ->
            val current = CarAppearancePrefs.weatherCity(context)
            (0 until group.childCount)
                .map {
                    group.getChildAt(
                        it,
                    ) as RadioButton
                }.firstOrNull { it.tag == current }
                ?.let { group.check(it.id) }
        }
        restyleCityButtons(cityButtons)
        sliders.forEachIndexed { index, (slider, label) ->
            slider.progress = values[index]
            val title = if (index == 0) "背景透明度" else "背景模糊"
            label.text = context.getString(R.string.car_appearance_value, title, values[index])
            if (isPhonePanel) PhonePanelUi.refreshStepper(slider)
        }
        presets.forEach { (button, preset) ->
            button.isSelected = preset.opacity == 100 - values[0] && preset.blur == values[1]
        }
    }

    /**
     * 返回页面或外部修改参数后刷新，隐藏面板不持有监听
     */
    override fun onResume() {
        super.onResume()
        UiAppearance.addListener(appearanceChanged)
        refreshValues()
    }

    /**
     * 页面隐藏时释放通知
     */
    override fun onPause() {
        UiAppearance.removeListener(appearanceChanged)
        super.onPause()
    }

    /**
     * 释放旧控件，防止面板重建后重复更新
     */
    override fun onDestroyView() {
        UiAppearance.removeListener(appearanceChanged)
        sliders.clear()
        presets.clear()
        cityButtons.clear()
        weatherChoices = null
        widgetSwitches.clear()
        musicIsland = null
        panelOutline = null
        super.onDestroyView()
    }
}
