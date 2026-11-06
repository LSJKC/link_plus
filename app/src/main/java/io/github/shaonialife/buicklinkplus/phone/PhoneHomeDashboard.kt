package io.github.shaonialife.buicklinkplus.phone

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.setAppearanceBackground
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageTint
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor

/** 首页按前台轮询读取已有数据，不持有后台服务 */
class PhoneHomeDashboard(
    private val context: Context,
    parent: LinearLayout,
    private val onOpen: (Int) -> Unit,
    onTarget: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val preparation: TextView
    private val session: TextView
    private val target: TextView

    init {
        parent.setPadding(0, dp(12), 0, 0)
        target = row(parent, "目标车辆", R.drawable.ic_car, onTarget)
        heading(parent, R.string.home_prepare)
        preparation = row(parent, "连接与设备", R.drawable.ic_wifi) { onOpen(SettingsMenus.CONNECTION) }
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
        session = row(parent, "CarLife 会话与诊断", R.drawable.ic_screen_rotation) { onOpen(SettingsMenus.CARLIFE) }
        refresh()
    }

    /** 读取会话与连接准备快照 */
    fun refresh() {
        val snapshot = SessionController.snapshot()
        target.text =
            TargetDevicePrefs.get(context)?.name?.takeIf { it.isNotBlank() } ?: "选择你的车机，开启无线连接"
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
                val hint = SessionController.phaseHint?.takeIf { it.isNotBlank() }
                if (hint != null) {
                    "尚未建立视频流 · $hint"
                } else {
                    "尚未建立视频流，连接后显示画面、音频和网络状态"
                }
            } else {
                val profile = snapshot.video
                val image =
                    profile?.let { "${it.width} × ${it.height} · 协商 ${it.fps} fps · ${it.bitrate / 1_000_000.0} Mbps" }
                        ?: "编码器配置尚未确认"
                "画面：$image\n声音：${snapshot.audio}\n投屏网络：${snapshot.network ?: "尚未确认"}"
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
