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
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.WeatherState
import io.github.shaonialife.buicklinkplus.render.LeftWidgetColumn
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.CarUi
import io.github.shaonialife.buicklinkplus.ui.PhoneOptionGroup
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/**
 * 车机外观：只保留安全区左侧只读小组件与天气位置。
 *
 * 原版的外观预设、背景透明度/模糊、面板轮廓与音乐岛等虚拟桌面设置已移除；
 * 左侧只读小组件（时间/日历/天气/停车计时/快捷应用等）保留给用户选择。
 */
class CarAppearancePanelFragment : SettingsPanelFragment() {
    /**
     * 控件在创建视图时完成绑定
     */
    override fun onViewCreated(view: View) = Unit

    private val widgetSwitches = mutableListOf<Pair<SwitchCompat, LeftWidgetColumn.Widget>>()
    private val cityButtons = mutableListOf<Pair<Button, String>>()
    private var weatherChoices: PhoneOptionGroup? = null

    /**
     * 与手机端使用同一套偏好和控件风格
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
                text = "调整车机安全区左侧的只读小组件，以及天气组件使用的城市位置。"
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_var)
                setPadding(0, 0, 0, (16 * density).toInt())
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
     * 返回页面后同步当前已保存的选择
     */
    private fun refreshValues() {
        val context = context ?: return
        val chosen = CarAppearancePrefs.leftWidgets(context)
        widgetSwitches.forEach { (toggle, widget) -> toggle.isChecked = widget.id in chosen }
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
    }

    /**
     * 页面重新可见时同步一次外部修改
     */
    override fun onResume() {
        super.onResume()
        refreshValues()
    }

    /**
     * 释放旧控件，防止面板重建后重复更新
     */
    override fun onDestroyView() {
        cityButtons.clear()
        weatherChoices = null
        widgetSwitches.clear()
        super.onDestroyView()
    }
}
