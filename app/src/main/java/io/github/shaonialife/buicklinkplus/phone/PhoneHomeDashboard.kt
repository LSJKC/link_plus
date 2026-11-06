package io.github.shaonialife.buicklinkplus.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.NavGuidance
import io.github.shaonialife.buicklinkplus.media.WeatherState
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.ui.WallpaperStore
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity

/** 首页按前台轮询读取已有数据，不持有后台服务 */
class PhoneHomeDashboard(
    private val context: Context,
    parent: LinearLayout,
    private val onOpen: (Int) -> Unit,
    onTarget: () -> Unit,
    onMac: () -> Unit,
    onWallpaper: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val preparation: TextView
    private val session: TextView
    private val music: TextView
    private val navigation: TextView
    private val weather: TextView
    private val target: TextView
    private val carLife: TextView
    private val mac: TextView
    private val wallpaper: TextView

    init {
        parent.setPadding(0, dp(12), 0, 0)
        // 用户指定顺序：CarLife → 本机蓝牙 MAC → 目标车辆 → 壁纸
        carLife = row(parent, "CarLife", R.drawable.ic_screen_rotation) { onOpen(SettingsMenus.CARLIFE) }
        mac = row(parent, "本机蓝牙 MAC", R.drawable.ic_bluetooth) { onMac() }
        target = row(parent, "目标车辆", R.drawable.ic_car, onTarget)
        wallpaper = row(parent, "壁纸", R.drawable.ic_wallpaper, onWallpaper)
        heading(parent, R.string.home_prepare)
        preparation = row(parent, "基础环境", R.drawable.ic_app_settings) { onOpen(SettingsMenus.PERMISSIONS) }
        heading(parent, R.string.home_shortcuts)
        val shortcuts =
            listOf(
                SettingsMenus.CONNECTION,
                SettingsMenus.DISPLAY,
                SettingsMenus.AUDIO,
                SettingsMenus.CARLIFE,
                SettingsMenus.APPEARANCE,
                SettingsMenus.ABOUT,
            )
        shortcuts.chunked(2).forEach { pair ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            parent.addView(line, LinearLayout.LayoutParams(-1, -2))
            pair.forEachIndexed { index, id ->
                val item = SettingsMenus.itemOf(id)
                val tile =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        tag = "home-shortcut:$id"
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(80)
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                        setAppearanceBackground(R.drawable.bg_phone_card)
                        isFocusable = true
                        setOnClickListener { onOpen(id) }
                    }
                tile.addView(
                    ImageView(context).apply {
                        setAppearanceImageResource(item.iconRes)
                        setAppearanceImageTint(R.color.m3_primary)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)),
                )
                tile.addView(
                    TextView(context).apply {
                        text = item.title
                        textSize = 14f
                        gravity = Gravity.START
                        paint.isFakeBoldText = true
                        setAppearanceTextColor(R.color.m3_on_surface)
                    },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
                )
                line.addView(
                    tile,
                    LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index == 0) marginEnd = dp(10)
                        bottomMargin = dp(10)
                    },
                )
            }
        }
        heading(parent, R.string.home_session)
        session = row(parent, "画面与声音", R.drawable.ic_menu_display) { onOpen(SettingsMenus.CONNECTION) }
        heading(parent, R.string.home_travel)
        music = row(parent, "音乐 · 暂无数据", R.drawable.ic_music) { onOpen(SettingsMenus.AUDIO) }
        navigation = row(parent, "导航 · 暂无数据", R.drawable.ic_menu_map) { onOpen(SettingsMenus.MAP) }
        weather = row(parent, "天气 · 暂无数据", R.drawable.ic_brightness) { onOpen(SettingsMenus.CAR_APPEARANCE) }
        refresh()
    }

    /** 读取会话、媒体和天气快照 */
    fun refresh() {
        val snapshot = SessionController.snapshot()
        target.text =
            TargetDevicePrefs.get(context)?.name?.takeIf { it.isNotBlank() } ?: "选择你的车机，开启无线连接"
        carLife.text =
            if (SessionController.isSessionRunning()) {
                "已连接 · 点这里查看引擎状态/重连/日志诊断"
            } else {
                "先在手机打开 CarLife+ → 无线连接，再回首页点「启动连接」"
            }
        val overrideMac = DeviceIdentity.overrideBluetoothMac(context)
        val resolvedMac = DeviceIdentity.cachedBluetoothMac()
        mac.text =
            when {
                overrideMac != null -> "已手填 $overrideMac"
                resolvedMac != null -> "自动解析 $resolvedMac"
                else -> "未解析（占位 ${DeviceIdentity.PLACEHOLDER_MAC}），车机可能不邀请；点此手填"
            }
        val hasWallpaper = WallpaperStore.userWallpaperFile(context) != null
        wallpaper.text =
            if (hasWallpaper) {
                "已设置壁纸 · 点此打开壁纸编辑器（内置壁纸 / 自定义图片 / 校准）"
            } else {
                "默认背景 · 点此选择内置壁纸或自定义图片"
            }
        val report = ConnectionPreflight.inspect(context, SessionController.audioEnabledForSession).beforeIdentity()
        val ready = report.checks.count { it.ready }
        preparation.text =
            context.getString(
                R.string.home_preparation_details,
                ready,
                report.checks.size,
                report.missing.firstOrNull()?.let { "下一步：${it.requirement.action} · ${it.requirement.title}" } ?: "基础环境已就绪，启动时核验输入与身份",
            )
        session.text =
            if (!snapshot.streaming) {
                "尚未建立视频流，连接后显示画面、音频和网络状态"
            } else {
                val profile = snapshot.video
                val image =
                    profile?.let { "${it.width} × ${it.height} · 协商 ${it.fps} fps · ${it.bitrate / 1_000_000.0} Mbps" }
                        ?: "编码器配置尚未确认"
                "画面：$image\n声音：${snapshot.audio}\n车机网络：${snapshot.network ?: "尚未确认"}"
            }
        val access = MediaSessionsState.accessGranted
        val playing = MediaSessionsState.nowPlaying
        music.text =
            when {
                !access -> "授权通知使用权后显示播放信息"
                playing == null -> "暂无播放器共享的信息"
                else ->
                    "${if (playing.playing) "播放中" else "已暂停"} · ${playing.title ?: "未提供曲名"}" +
                        playing.artist
                            ?.takeIf { it.isNotBlank() }
                            ?.let { " · $it" }
                            .orEmpty()
            }
        navigation.text =
            when {
                !access -> "授权通知使用权后显示导航提示"
                NavGuidance.current != null -> NavGuidance.current?.text.orEmpty()
                else -> "暂无应用共享的导航指引"
            }
        val current = WeatherState.current
        val choice = CarAppearancePrefs.weatherCity(context)
        val locationGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val age = if (WeatherState.updatedAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - WeatherState.updatedAt
        weather.text =
            when {
                current == null && choice == WeatherState.AUTO && !locationGranted -> "定位未授权，点击选择常用城市"
                current == null && WeatherState.lastRefreshFailed -> "刷新失败，暂无可用缓存"
                current == null -> "暂无天气缓存，组件刷新后同步显示"
                else ->
                    "${current.city} · ${current.temperatureC.toInt()}° · ${current.text}" +
                        if (WeatherState.lastRefreshFailed) {
                            " · 刷新失败，显示缓存"
                        } else if (age >=
                            30 * 60_000L
                        ) {
                            " · 数据已过期"
                        } else {
                            " · ${age.coerceAtLeast(0) / 60_000} 分钟前更新"
                        }
            }
    }

    /**
     * 分区标题随系统字号缩放
     */
    private fun heading(
        parent: LinearLayout,
        title: Int,
    ) {
        parent.addView(
            TextView(context).apply {
                setText(title)
                textSize = 17f
                paint.isFakeBoldText = true
                setAppearanceTextColor(R.color.m3_on_surface)
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
            },
        )
    }

    /**
     * 状态卡沿用设置行组件并允许多行自然测量
     */
    private fun row(
        parent: LinearLayout,
        title: String,
        icon: Int,
        action: () -> Unit,
    ): TextView {
        val row = LayoutInflater.from(context).inflate(R.layout.item_phone_category, parent, false)
        row.tag = "home-entry:$icon:$title"
        row.setAppearanceBackground(R.drawable.bg_phone_card)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageResource(icon)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageTint(R.color.m3_primary)
        row.findViewById<TextView>(R.id.categoryTitle).text = title.substringBefore(" · ")
        val label =
            row.findViewById<TextView>(R.id.categoryDescription).apply {
                visibility = View.VISIBLE
                textSize = 12f
                setLineSpacing(dp(2).toFloat(), 1f)
            }
        row.setOnClickListener { action() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return label
    }

    /**
     * 手机布局尺寸按当前窗口密度换算
     */
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
