package io.github.shaonialife.buicklinkplus.phone

import android.content.Context
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

/**
 * 手机首页：只保留「目标车机」与按需出现的「权限与后台」入口。
 *
 * 首页其它状态由宿主连接卡（连接状态 + 启动/断开按钮）与最近日志卡承担；
 * 设置项统一在底部「设置」的六类页面里，这里不再重复。
 */
class PhoneHomeDashboard(
    private val context: Context,
    parent: LinearLayout,
    private val onOpen: (Int) -> Unit,
    onTarget: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private val target: TextView
    private val permissionRow: View
    private val permissionDesc: TextView

    init {
        parent.setPadding(0, dp(12), 0, 0)
        target = row(parent, "目标车机", R.drawable.ic_car, onTarget)
        permissionRow = rowView(parent, "权限与后台", R.drawable.ic_app_settings) { onOpen(SettingsMenus.PERMISSIONS) }
        permissionDesc = permissionRow.findViewById(R.id.categoryDescription)
        refresh()
    }

    /**
     * 每秒刷新首页：目标车机名称 + 缺失权限时才显示权限入口。
     */
    fun refresh() {
        val base =
            TargetDevicePrefs.get(context)?.name?.takeIf { it.isNotBlank() } ?: "尚未选择，点击选择已配对的车机"
        val hint =
            SessionController.phaseHint
                ?.takeIf { it.isNotBlank() && !SessionController.isSessionRunning() }
        target.text = if (hint != null) "$base · $hint" else base
        val report = ConnectionPreflight.inspect(context, requireAudio = false).beforeIdentity()
        val permissionChecks =
            setOf(
                ConnectionPreflight.Requirement.BLUETOOTH_PERMISSION,
                ConnectionPreflight.Requirement.BLUETOOTH_ENABLED,
                ConnectionPreflight.Requirement.WIFI_PERMISSION,
                ConnectionPreflight.Requirement.WIFI_ENABLED,
                ConnectionPreflight.Requirement.LOCATION_ENABLED,
            )
        val missing = report.checks.filterNot { it.ready }.filter { it.requirement in permissionChecks }
        if (missing.isEmpty()) {
            permissionRow.visibility = View.GONE
        } else {
            permissionRow.visibility = View.VISIBLE
            permissionDesc.text =
                "缺少 ${missing.size} 项：" +
                missing.joinToString("、") { it.requirement.title }
        }
    }

    /** 带描述的状态行（返回行视图，描述文本由 [refresh] 更新）。 */
    private fun rowView(
        parent: LinearLayout,
        title: String,
        icon: Int,
        action: () -> Unit,
    ): View {
        val row = LayoutInflater.from(context).inflate(R.layout.item_phone_category, parent, false)
        row.tag = "home-entry:$icon:$title"
        row.setAppearanceBackground(R.drawable.bg_phone_card)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageResource(icon)
        row.findViewById<ImageView>(R.id.categoryIcon).setAppearanceImageTint(R.color.m3_primary)
        row.findViewById<TextView>(R.id.categoryTitle).text = title
        row.findViewById<TextView>(R.id.categoryDescription).apply {
            visibility = View.VISIBLE
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        row.setOnClickListener { action() }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        return row
    }

    /** 单行文本状态（目标车机）。 */
    private fun row(
        parent: LinearLayout,
        title: String,
        icon: Int,
        action: () -> Unit,
    ): TextView {
        val row = rowView(parent, title, icon, action)
        return row.findViewById<TextView>(R.id.categoryDescription)
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
}
