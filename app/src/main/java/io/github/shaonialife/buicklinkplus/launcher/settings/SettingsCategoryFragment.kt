package io.github.shaonialife.buicklinkplus.launcher.settings

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.audio.AudioPreference
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.PanelWindow
import io.github.shaonialife.buicklinkplus.ui.PhoneOptionGroup
import io.github.shaonialife.buicklinkplus.ui.PhonePanelUi
import io.github.shaonialife.buicklinkplus.ui.WallpaperActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity

/**
 * 两端共用的设置分组，二级功能继续使用原面板
 */
class SettingsCategoryFragment : Fragment() {
    private var status: TextView? = null
    private var strategyLabel: TextView? = null
    private var strategyOptions: PhoneOptionGroup? = null
    private val tick =
        object : Runnable {
            override fun run() {
                context?.let { context ->
                    val selected = AudioPreference.read(context)
                    strategyLabel?.text = context.getString(R.string.ui_audio_strategy_next_connection, selected.title)
                    strategyOptions?.let { options ->
                        for (index in 0 until options.childCount) {
                            val option = options.getChildAt(index) as RadioButton
                            if (option.tag == selected && options.checkedRadioButtonId != option.id) options.check(option.id)
                        }
                    }
                }
                status?.text =
                    when (arguments?.getInt("category")) {
                        SettingsMenus.CONNECTION -> SessionController.networkStatus()
                        SettingsMenus.AUDIO -> SessionController.audioStatus()
                        else -> ""
                    }
                view?.postDelayed(this, 2000)
            }
        }

    /**
     * 构建分组中的操作与说明，长说明默认收起
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?,
    ): View {
        val context = requireContext()
        val phone =
            activity is PhoneSettingsHost &&
                !io.github.shaonialife.buicklinkplus.ui.UiAppearance
                    .isCar(context)
        val density = resources.displayMetrics.density
        val body =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val padding = (20 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }

        fun note(text: String) {
            body.addView(
                TextView(context).apply {
                    this.text = text
                    textSize = 14f
                    setAppearanceTextColor(R.color.m3_on_var)
                    val padding = if (phone) (18 * density).toInt() else 0
                    val verticalPadding = (if (phone) 14 else 10) * density
                    setPadding(padding, verticalPadding.toInt(), padding, verticalPadding.toInt())
                    if (phone) setAppearanceBackground(R.drawable.bg_phone_card)
                },
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = ((if (phone) 14 else 12) * density).toInt()
                },
            )
        }

        fun action(
            text: String,
            run: () -> Unit,
        ) {
            val button =
                if (phone) {
                    TextView(context).apply {
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        minimumHeight = (64 * density).toInt()
                        val padding = (18 * density).toInt()
                        setPadding(padding, padding, padding, padding)
                        setAppearanceBackground(R.drawable.bg_phone_card)
                        isFocusable = true
                        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chev_right, 0)
                        io.github.shaonialife.buicklinkplus.ui.AppearanceBindings
                            .compoundTint(this, R.color.m3_on_var)
                    }
                } else {
                    Button(context).apply {
                        io.github.shaonialife.buicklinkplus.ui.CarUi
                            .styleAction(this)
                    }
                }
            body.addView(
                button.apply {
                    this.text = text
                    if (phone) setAppearanceTextColor(R.color.m3_on_surface)
                    setOnClickListener { run() }
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() },
            )
        }

        fun panel(id: Int) =
            action(SettingsMenus.titleOf(id)) {
                val car = activity as? io.github.shaonialife.buicklinkplus.launcher.SettingsActivity
                if (car != null) car.openPanel(id) else SettingsContentActivity.start(context, SettingsMenus.itemOf(id))
            }
        when (arguments?.getInt("category")) {
            SettingsMenus.APPEARANCE -> {
                panel(SettingsMenus.DISPLAY)
                action("编辑壁纸") { startActivity(Intent(context, WallpaperActivity::class.java)) }
                panel(SettingsMenus.CAR_APPEARANCE)
                panel(SettingsMenus.DOCK)
                note("界面采用蓝紫色强调色；手机跟随系统，车机优先跟随壁纸明暗")
            }
            SettingsMenus.APPS -> {
                panel(SettingsMenus.OFFICIAL_CAR_APPS)
                panel(SettingsMenus.DESKTOP)
                panel(SettingsMenus.SUPERSAMPLE)
                panel(SettingsMenus.ORIENTATION)
            }
            SettingsMenus.LAYOUT -> panel(SettingsMenus.DISPLAY)
            SettingsMenus.MAP -> panel(SettingsMenus.DEFAULT_MAP)
            SettingsMenus.AUDIO -> {
                strategyLabel =
                    TextView(context)
                        .apply {
                            text = context.getString(R.string.ui_audio_strategy_next_connection, AudioPreference.read(context).title)
                            setAppearanceTextColor(R.color.m3_on_surface)
                        }.also {
                            body.addView(
                                it,
                                LinearLayout.LayoutParams(-1, -2).apply {
                                    if (!phone) bottomMargin = (12 * density).toInt()
                                },
                            )
                        }
                if (phone) {
                    val options = PhoneOptionGroup(context)
                    AudioPreference.entries.forEach { value ->
                        val option =
                            RadioButton(context).apply {
                                id = View.generateViewId()
                                tag = value
                                text = value.title
                                PhonePanelUi.styleOption(this)
                            }
                        options.addView(
                            option,
                            android.widget.RadioGroup.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        if (value == AudioPreference.read(context)) options.check(option.id)
                    }
                    options.setOnCheckedChangeListener { group, checked ->
                        val selected =
                            group.findViewById<RadioButton>(checked)?.tag as? AudioPreference ?: return@setOnCheckedChangeListener
                        if (selected != AudioPreference.read(context)) {
                            AudioPreference.write(context, selected)
                            strategyLabel?.text = context.getString(R.string.ui_audio_strategy_next_connection, selected.title)
                            (activity as? SettingsToastHost)?.showToast("已选择${selected.title}，下次连接生效")
                        }
                    }
                    strategyOptions = options
                    body.addView(
                        options,
                        LinearLayout.LayoutParams(-1, -2).apply {
                            topMargin = (16 * density).toInt()
                            bottomMargin = (16 * density).toInt()
                        },
                    )
                    note(AudioPreference.entries.joinToString("\n\n") { "${it.title}：${it.detail}" })
                } else {
                    AudioPreference.entries.forEach { value ->
                        action(value.title + " · " + value.detail) {
                            AudioPreference.write(context, value)
                            strategyLabel?.text = context.getString(R.string.ui_audio_strategy_next_connection, value.title)
                            (activity as? SettingsToastHost)?.showToast("已选择${value.title}，下次连接生效")
                        }
                    }
                }
                note("自动/音质优先使用 CarLife 音源，无需授权；兼容模式使用系统屏幕录制（投屏授权）采集声音，导航提示音不保证可捕获。")
            }
            SettingsMenus.CONNECTION -> {
                panel(SettingsMenus.PERMISSIONS)
                note("自动连接会请求车机本地 Wi-Fi，普通上网目标是继续使用手机流量。手机仍需建立 Wi-Fi 连接，并发能力取决于设备与系统")
                action(context.getString(R.string.phone_connection_bt_mac_title)) {
                    showBluetoothMacDialog(context) { message -> (activity as? SettingsToastHost)?.showToast(message) }
                }
                action("系统 Wi-Fi 设置 · 手动兼容连接") { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) }
                note("车辆选择、蓝牙连接策略及清除已保存热点请在手机设置中操作")
            }
            SettingsMenus.PERMISSIONS -> {
                note("新包需重新授予附近设备与通知使用权。请在手机首页启动连接，按缺失项目授权；后台省电选项在手机设置中保留")
                action("应用权限设置") {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:${context.packageName}").toUri()),
                    )
                }
            }
            SettingsMenus.ABOUT -> {
                note("Buick Link + ${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_NAME}")
                note("基于上游开源项目开发，保留许可与作者归属。日志导出、系统状态和完整免责声明位于手机的关于与诊断")
            }
        }
        if (arguments?.getInt("category") in listOf(SettingsMenus.AUDIO, SettingsMenus.CONNECTION)) {
            status =
                TextView(context).apply {
                    setAppearanceTextColor(R.color.m3_on_var)
                    textSize = 13f
                }
            body.addView(status)
        }
        return ScrollView(context).apply {
            if (phone) {
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            addView(body)
        }
    }

    /**
     * 仅当前可见页面定时刷新
     */
    override fun onResume() {
        super.onResume()
        if (status != null) view?.post(tick)
    }

    /**
     * 隐藏或退出时停止刷新
     */
    override fun onPause() {
        view?.removeCallbacks(tick)
        super.onPause()
    }

    /**
     * 释放旧页面控件
     */
    override fun onDestroyView() {
        view?.removeCallbacks(tick)
        status = null
        strategyLabel = null
        strategyOptions = null
        super.onDestroyView()
    }

    companion object {
        /**
         * 手填本机蓝牙地址：Honor/MagicOS 等脱敏机型无法自动解析时使用。
         *
         * 地址是无线 CarPlay 的设备身份，占位值会让车机静默丢弃；这里只是软设置，
         * 保存后立即触发一次解析，不阻断连接流程。手机首页连接页与设置分类页共用。
         *
         * @param toast 轻提示出口（车机页内提示 / 手机 Snackbar / 系统 Toast）
         */
        fun showBluetoothMacDialog(
            context: Context,
            toast: (String) -> Unit,
        ) {
            // 打开时先尝试一次自动解析，避免把「尚未解析」误报成占位
            runCatching { DeviceIdentity.bluetoothMac(context, forceRetry = true) }
            val overrideMac =
                if (DeviceIdentity.hasOverride(context)) DeviceIdentity.overrideBluetoothMac(context) else null
            val cached = DeviceIdentity.cachedBluetoothMac()
            val status =
                when {
                    overrideMac != null -> context.getString(R.string.phone_connection_bt_mac_status_override, overrideMac)
                    cached != null -> context.getString(R.string.phone_connection_bt_mac_status_auto, cached)
                    else -> context.getString(R.string.phone_connection_bt_mac_status_missing, DeviceIdentity.PLACEHOLDER_MAC)
                }
            val density = context.resources.displayMetrics.density
            val input =
                EditText(context).apply {
                    hint = context.getString(R.string.phone_connection_bt_mac_hint)
                    setText(overrideMac.orEmpty())
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    keyListener = DigitsKeyListener.getInstance("0123456789ABCDEFabcdef:-")
                    setSingleLine()
                }
            val container =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (20 * density).toInt()
                    setPadding(padding, (8 * density).toInt(), padding, 0)
                    addView(input, LinearLayout.LayoutParams(-1, -2))
                }
            val dialog =
                AlertDialog
                    .Builder(context)
                    .setTitle(R.string.phone_connection_bt_mac_title)
                    .setMessage(status + "\n\n" + context.getString(R.string.phone_connection_bt_mac_explain))
                    .setView(container)
                    .setPositiveButton(R.string.phone_connection_bt_mac_save, null)
                    .setNeutralButton(R.string.phone_connection_bt_mac_clear, null)
                    .setNegativeButton(R.string.phone_connection_bt_mac_cancel, null)
                    .create()
            dialog.setOnShowListener {
                (context as? Activity)?.let { PanelWindow.apply(dialog, it) }
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val saved = DeviceIdentity.setOverride(context, input.text?.toString())
                    if (saved == null) {
                        toast(context.getString(R.string.phone_connection_bt_mac_invalid))
                        return@setOnClickListener
                    }
                    if (saved == DeviceIdentity.PLACEHOLDER_MAC) {
                        toast(context.getString(R.string.phone_connection_bt_mac_cleared))
                    } else {
                        toast(context.getString(R.string.phone_connection_bt_mac_saved, saved))
                    }
                    DeviceIdentity.bluetoothMac(context, forceRetry = true)
                    dialog.dismiss()
                }
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                    DeviceIdentity.setOverride(context, null)
                    DeviceIdentity.bluetoothMac(context, forceRetry = true)
                    toast(context.getString(R.string.phone_connection_bt_mac_cleared))
                    dialog.dismiss()
                }
            }
            dialog.show()
        }
    }
}
