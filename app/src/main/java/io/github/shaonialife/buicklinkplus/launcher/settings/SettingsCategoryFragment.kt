package io.github.shaonialife.buicklinkplus.launcher.settings

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.carlife.CarLifeProtocol
import io.github.shaonialife.buicklinkplus.legal.DisclaimerDialog
import io.github.shaonialife.buicklinkplus.session.SessionCommands
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.BuiltinWallpapers
import io.github.shaonialife.buicklinkplus.ui.PanelWindow
import io.github.shaonialife.buicklinkplus.ui.WallpaperActivity
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity
import io.github.shaonialife.buicklinkplus.util.LogExporter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * CarLife 版设置分组：六类页面（连接与设备 / 显示与画面 / 音频 / CarLife /
 * 外观与壁纸 / 日志与关于）以及连接与设备下的权限页。
 *
 * 显示与画面使用 [DisplayPanelFragment]（画质面板），其余页面在本文件内构建。
 */
class SettingsCategoryFragment : Fragment() {
    private var status: TextView? = null
    private val tick =
        object : Runnable {
            override fun run() {
                status?.text =
                    when (arguments?.getInt("category")) {
                        SettingsMenus.CONNECTION -> SessionController.networkStatus()
                        SettingsMenus.AUDIO -> SessionController.audioStatus()
                        else -> ""
                    }
                view?.postDelayed(this, 2000)
            }
        }

    private fun category(): Int = arguments?.getInt("category") ?: SettingsMenus.CONNECTION

    /**
     * 构建页面内容，长说明默认收起
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

        fun add(view: View) {
            body.addView(view, LinearLayout.LayoutParams(-1, -2))
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

        /** 开关行：标题 + 说明 + Switch */
        fun switchRow(
            title: String,
            hint: String,
            checked: Boolean,
            onChange: (Boolean) -> Unit,
        ) {
            val card =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((18 * density).toInt(), (12 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
                    if (phone) setAppearanceBackground(R.drawable.bg_phone_card)
                }
            val label =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
            label.addView(
                TextView(context).apply {
                    text = title
                    textSize = 16f
                    setAppearanceTextColor(R.color.m3_on_surface)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            label.addView(
                TextView(context).apply {
                    text = hint
                    textSize = 12f
                    setAppearanceTextColor(R.color.m3_on_var)
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = (2 * density).toInt() },
            )
            card.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            val switch =
                SwitchCompat(context).apply {
                    isChecked = checked
                    thumbDrawable = ContextCompat.getDrawable(context, R.drawable.sw_thumb)
                    trackDrawable = ContextCompat.getDrawable(context, R.drawable.sw_track)
                }
            switch.setOnCheckedChangeListener { _, value -> onChange(value) }
            card.addView(switch, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (12 * density).toInt() })
            body.addView(
                card,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * density).toInt() },
            )
        }

        fun panel(id: Int) {
            action(SettingsMenus.titleOf(id)) {
                val car = activity as? io.github.shaonialife.buicklinkplus.launcher.SettingsActivity
                if (car != null) car.openPanel(id) else SettingsContentActivity.start(context, SettingsMenus.itemOf(id))
            }
        }

        when (category()) {
            SettingsMenus.CONNECTION -> connectionPage(context, ::add, ::note, ::action, ::panel) { message -> hostToast(message) }
            SettingsMenus.PERMISSIONS -> permissionsPage(context, ::note, ::action)
            SettingsMenus.AUDIO -> audioPage(context, ::add, ::note, ::switchRow)
            SettingsMenus.CARLIFE -> carLifePage(context, ::add, ::note, ::action, ::switchRow)
            SettingsMenus.APPEARANCE -> wallpaperPage(context, ::add, ::note, ::action)
            SettingsMenus.ABOUT -> aboutPage(context, ::note, ::action)
            else -> note("该设置页已下线，请返回设置列表")
        }
        if (category() in listOf(SettingsMenus.CONNECTION, SettingsMenus.AUDIO)) {
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

    // ==================== 连接与设备 ====================

    private fun connectionPage(
        context: Context,
        add: (View) -> Unit,
        note: (String) -> Unit,
        action: (String, () -> Unit) -> Unit,
        panel: (Int) -> Unit,
        macDialog: (String) -> Unit,
    ) {
        note("配好手机与车机的蓝牙后，在首页点「启动连接」。CarLife 需要你手动打开 CarLife+ 并进入「无线连接」。")
        val target =
            io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
                .get(context)
        add(
            TextView(context).apply {
                text = "目标车机：${target?.name?.takeIf { it.isNotBlank() } ?: "尚未选择（请在手机首页选择）"}"
                textSize = 14f
                setAppearanceTextColor(R.color.m3_on_surface)
            },
        )
        action("选择/更换目标车机（返回首页操作）") {
            val intent = Intent(context, io.github.shaonialife.buicklinkplus.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
        }
        action(context.getString(R.string.phone_connection_bt_mac_title)) {
            showBluetoothMacDialog(context, macDialog)
        }
        action("权限与后台") { panel(SettingsMenus.PERMISSIONS) }
        action("系统 Wi-Fi 设置") {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        note("网络：自动连接会请求车机本地 Wi-Fi；手机上网默认继续使用手机流量。并发能力取决于设备与系统。")
    }

    private fun permissionsPage(
        context: Context,
        note: (String) -> Unit,
        action: (String, () -> Unit) -> Unit,
    ) {
        note("CarLife 版本只需要普通 Android 运行时权限，不需要开发者选项、无线调试或 ADB。")
        action("应用权限设置") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:${context.packageName}").toUri()),
            )
        }
        action("通知使用权（媒体信息）") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        action("显示在其他应用上层（暗幕）") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:${context.packageName}").toUri()))
        }
        action("电池优化设置") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        note("后台运行、自启动等厂商设置需在手机系统设置中手动确认。")
    }

    // ==================== 音频 ====================

    private fun audioPage(
        context: Context,
        add: (View) -> Unit,
        note: (String) -> Unit,
        switchRow: (String, String, Boolean, (Boolean) -> Unit) -> Unit,
    ) {
        note("CarLife 媒体音频与 TTS 导航播报会混入 CarPlay 音频通道；关闭后车机侧无声。")
        switchRow("CarLife 音源", "把 CarLife 的媒体声音送到车机", CarLifePrefs.audioEnabled(context)) { enabled ->
            CarLifePrefs.setAudioEnabled(context, enabled)
            hostToast(if (enabled) "CarLife 音源已开启" else "CarLife 音源已关闭")
        }
        switchRow("TTS 导航播报", "把导航播报声音混入车机音频", CarLifePrefs.ttsEnabled(context)) { enabled ->
            CarLifePrefs.setTtsEnabled(context, enabled)
            hostToast(if (enabled) "TTS 播报已开启" else "TTS 播报已关闭")
        }
        switchRow("导航指引上行", "把 CarLife 导航信息同步给车机仪表", CarLifePrefs.navEnabled(context)) { enabled ->
            CarLifePrefs.setNavEnabled(context, enabled)
            hostToast(if (enabled) "导航指引已开启" else "导航指引已关闭")
        }
        // 音量：直接调系统媒体音量（CarPlay 音频走媒体通道）
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val density = resources.displayMetrics.density
            val card =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = (18 * density).toInt()
                    setPadding(padding, (12 * density).toInt(), padding, (12 * density).toInt())
                    setAppearanceBackground(R.drawable.bg_phone_card)
                }
            card.addView(
                TextView(context).apply {
                    text = "媒体音量"
                    textSize = 16f
                    setAppearanceTextColor(R.color.m3_on_surface)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            val valueText =
                TextView(context).apply {
                    textSize = 12f
                    setAppearanceTextColor(R.color.m3_on_var)
                }

            fun currentPercent(): Int = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maxVolume).coerceIn(0, 100)

            valueText.text = "当前 ${currentPercent()}%"
            val seek =
                SeekBar(context).apply {
                    max = 100
                    progress = currentPercent()
                }
            seek.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        bar: SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        valueText.text = "当前 $progress%"
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar?) {
                        val percent = (bar?.progress ?: currentPercent()).coerceIn(0, 100)
                        val index = (percent * maxVolume + 50) / 100
                        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
                        valueText.text = "当前 ${currentPercent()}%"
                    }
                },
            )
            card.addView(seek, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * density).toInt() })
            card.addView(valueText, LinearLayout.LayoutParams(-1, -2))
            add(card)
        }
        note("音频通道状态见页面底部实时状态。")
    }

    // ==================== CarLife ====================

    private fun carLifePage(
        context: Context,
        add: (View) -> Unit,
        note: (String) -> Unit,
        action: (String, () -> Unit) -> Unit,
        switchRow: (String, String, Boolean, (Boolean) -> Unit) -> Unit,
    ) {
        note("本页面检查手机本机 CarLife+ 引擎的 TCP 端口，并管理当前投屏会话。")
        val density = resources.displayMetrics.density
        val engineStatus =
            TextView(context).apply {
                textSize = 15f
                setAppearanceTextColor(R.color.m3_on_surface)
                text = "正在检测 CarLife+ 引擎…"
            }
        add(engineStatus)
        val portStatus =
            TextView(context).apply {
                textSize = 13f
                setLineSpacing(2 * density, 1f)
                setAppearanceTextColor(R.color.m3_on_var)
                text = "端口自检：未开始"
            }
        add(portStatus)
        val diag =
            TextView(context).apply {
                textSize = 12f
                setAppearanceTextColor(R.color.m3_on_var)
                text = "诊断信息：点「刷新状态」读取最近 CARLIFE 日志"
            }
        add(diag)
        val appContext = context.applicationContext

        fun refresh() {
            portStatus.text = "端口自检：检测中…"
            Bg.run("carlife-port-check") {
                val probes =
                    CARLIFE_PORTS.map { (label, port) -> label to (port to isPortOpen(port)) }
                val lines =
                    probes.joinToString("\n") { (label, result) ->
                        val (port, open) = result
                        "${if (open) "✓" else "✗"} $label $port ${if (open) "监听中" else "未监听"}"
                    }
                val recent =
                    SessionController
                        .logText()
                        .lineSequence()
                        .filter { it.contains("CARLIFE") }
                        .toList()
                        .takeLast(12)
                        .joinToString("\n")
                        .ifBlank { "暂无 CARLIFE 日志" }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    val source = SessionController.currentScreenSource as? io.github.shaonialife.buicklinkplus.capture.CarLifeScreenSource
                    portStatus.text = "端口自检：\n$lines"
                    diag.text =
                        buildString {
                            append("诊断信息（最近 CARLIFE 日志）：\n$recent")
                            source?.lastErrorText?.takeIf { it.isNotBlank() }?.let { append("\n最后错误：$it") }
                        }
                    engineStatus.text =
                        buildString {
                            if (probes.firstOrNull()?.second?.second == true) {
                                append("CarLife+ 引擎已进入无线连接态")
                            } else {
                                append("未检测到 CarLife+ 引擎（打开 CarLife+ 并点「无线连接」）")
                            }
                            append("\n画面源：")
                            if (source == null) {
                                append("未挂载（尚未开始投屏会话）")
                            } else {
                                append(if (source.isEngineConnected) "已连接" else "连接中/重试中")
                                if (source.streamWidth > 0 && source.streamHeight > 0) {
                                    append(" · 码流 ${source.streamWidth}×${source.streamHeight}")
                                }
                                append(" · 已解码 ${source.deliveredFrames} 帧")
                            }
                            append("\n会话：")
                            append(if (SessionController.isSessionRunning()) "进行中" else "未运行")
                            SessionController.phaseHint?.takeIf { it.isNotBlank() }?.let { append("\n阶段：$it") }
                            append("\n${SessionController.statusLine()}")
                        }
                }
            }
        }

        fun reconnect() {
            if (!SessionController.isSessionRunning()) {
                hostToast("会话未运行，请先在手机首页点「启动连接」")
                return
            }
            hostToast("正在断开并重连…")
            SessionCommands.disconnect(context)
            Bg.run("carlife-reconnect") {
                Thread.sleep(1_500)
                SessionController.startConnection(appContext)
            }
        }
        action("刷新状态 / 端口自检") { refresh() }
        action("重连 CarLife 会话") { reconnect() }
        note("CarLife+ 引擎断开时画面源每 2 秒自动重试；会话重连需要车机连接环境仍然就绪。")
        switchRow("导航指引上行", "把 CarLife 导航信息同步给车机仪表", CarLifePrefs.navEnabled(context)) { enabled ->
            CarLifePrefs.setNavEnabled(context, enabled)
            hostToast(if (enabled) "导航指引已开启" else "导航指引已关闭")
        }
        refresh()
    }

    // ==================== 外观与壁纸 ====================

    private fun wallpaperPage(
        context: Context,
        add: (View) -> Unit,
        note: (String) -> Unit,
        action: (String, () -> Unit) -> Unit,
    ) {
        note("内置壁纸来自旧版龟壳；点选即应用到本机壁纸（合成器下次出帧生效）。")
        val density = resources.displayMetrics.density
        val appContext = context.applicationContext
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        BuiltinWallpapers.TITLES.indices.chunked(3).forEach { indices ->
            val line =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
            indices.forEach { index ->
                val cell =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER
                        isFocusable = true
                        isClickable = true
                        setPadding(
                            (4 * density).toInt(),
                            (4 * density).toInt(),
                            (4 * density).toInt(),
                            (4 * density).toInt(),
                        )
                        setOnClickListener {
                            BuiltinWallpapers.applyAsync(context, index) { error ->
                                if (isAdded) hostToast(error ?: "已应用内置壁纸：${BuiltinWallpapers.TITLES[index]}")
                            }
                        }
                    }
                val image =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(0xFF161326.toInt())
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                cell.addView(image, LinearLayout.LayoutParams(-1, (86 * density).toInt()))
                cell.addView(
                    TextView(context).apply {
                        text = BuiltinWallpapers.TITLES[index]
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        setAppearanceTextColor(R.color.m3_on_var)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * density).toInt() },
                )
                line.addView(
                    cell,
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (6 * density).toInt() },
                )
                Bg.run("builtin-wallpaper-thumb") {
                    val bitmap = BuiltinWallpapers.loadThumb(appContext, index, 240)
                    if (bitmap != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (isAdded) image.setImageBitmap(bitmap)
                        }
                    }
                }
            }
            grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * density).toInt() })
        }
        add(grid)
        action("打开壁纸编辑器（自定义图片）") {
            startActivity(Intent(context, WallpaperActivity::class.java))
        }
        action("恢复默认壁纸") {
            Bg.run("wallpaper-reset") {
                val ok = WallpaperStore.resetToDefault(appContext)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (isAdded) hostToast(if (ok) "已恢复默认壁纸" else "恢复默认壁纸失败")
                }
            }
        }
        note("壁纸背景开关在「显示与画面」中；关闭后使用纯色背景。")
    }

    // ==================== 日志与关于 ====================

    private fun aboutPage(
        context: Context,
        note: (String) -> Unit,
        action: (String, () -> Unit) -> Unit,
    ) {
        note(
            "Buick Link + ${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_NAME}（${io.github.shaonialife.buicklinkplus.BuildConfig.VERSION_CODE}）",
        )
        action("导出本次启动日志") { exportLog(context, LogExporter.Scope.CURRENT_SESSION) }
        action("导出全部历史日志") { exportLog(context, LogExporter.Scope.ALL) }
        action("清空界面日志") {
            SessionController.clearLog()
            hostToast("界面日志已清空")
        }
        action("查看免责声明") {
            DisclaimerDialog.show(
                activity = requireActivity(),
                requireAcceptance = false,
            )
        }
        note("日志导出到「下载/Buick Link＋」，可分享给开发者排查问题。本项目基于 MIT 许可的开源项目，第三方归属见应用内声明。")
    }

    private fun exportLog(
        context: Context,
        scope: LogExporter.Scope,
    ) {
        hostToast(if (scope == LogExporter.Scope.ALL) "正在导出全部日志…" else "正在导出本次启动的日志…")
        val appContext = context.applicationContext
        Bg.run("export-log") {
            val result = LogExporter.export(appContext, SessionController.logText(), scope)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (isAdded) {
                    hostToast(if (result == null) "日志导出失败，请检查存储空间" else "日志已导出：${result.displayPath}")
                }
            }
        }
    }

    // ==================== 生命周期与提示 ====================

    override fun onResume() {
        super.onResume()
        if (status != null) view?.post(tick)
    }

    override fun onPause() {
        view?.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        view?.removeCallbacks(tick)
        status = null
        super.onDestroyView()
    }

    /** 页面轻提示：车机页内提示 / 手机 Toast */
    private fun hostToast(text: String) {
        val host = activity as? SettingsToastHost
        if (host != null) {
            host.showToast(text)
        } else {
            android.widget.Toast
                .makeText(requireContext(), text, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    companion object {
        private val CARLIFE_PORTS =
            listOf(
                "控制" to CarLifeProtocol.PORT_CMD,
                "视频" to CarLifeProtocol.PORT_VIDEO,
                "媒体" to CarLifeProtocol.PORT_MEDIA,
                "TTS" to CarLifeProtocol.PORT_TTS,
                "触摸" to CarLifeProtocol.PORT_TOUCH,
                "数据" to CarLifeProtocol.PORT_DATA,
            )

        /** 端口是否在本机监听（250ms 超时的 TCP 连接探测，不发送任何数据）。 */
        private fun isPortOpen(port: Int): Boolean =
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    true
                }
            } catch (_: Exception) {
                false
            }

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
