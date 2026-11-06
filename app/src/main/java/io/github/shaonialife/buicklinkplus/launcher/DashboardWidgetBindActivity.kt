package io.github.shaonialife.buicklinkplus.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg

/** 手机主屏专用授权宿主，系统授权和厂商配置不会落入车机虚拟屏 */
class DashboardWidgetBindActivity : GlassActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var completed = false
    private var generation = 0L
    private val preferencesChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> checkCompletedBinding() }
    private val bindResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) afterBound() else finishWithMessage("已取消授权，保留原音乐组件")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                setText(R.string.dashboard_widget_binding_notice)
                gravity = Gravity.CENTER
                setAppearanceTextColor(R.color.m3_on_surface)
                textSize = 18f
            },
        )
        widgetId = savedInstanceState?.getInt("widgetId") ?: AppWidgetManager.INVALID_APPWIDGET_ID
        getSharedPreferences(DashboardWidgets.PREFERENCES, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferencesChanged)
        if (widgetId > 0 && DashboardWidgets.selected(this) == widgetId) {
            checkCompletedBinding()
            return
        }
        if (savedInstanceState != null && DashboardWidgets.pending(this)?.id == widgetId) return
        val component = intent.getStringExtra("provider")?.let(ComponentName::unflattenFromString)
        if (display?.displayId != 0 || component == null || DashboardWidgets.pending(this) != null) {
            finishWithMessage("请先在手机取消未完成的授权，再重新选择组件")
            return
        }
        validate(component) { provider ->
            val app = applicationContext
            DashboardWidgets.change {
                val prepared = runCatching { DashboardWidgets.prepare(app, provider.info.provider) }
                runOnUiThread {
                    val pending = prepared.getOrNull()
                    if (isFinishing || isDestroyed) {
                        if (pending != null) DashboardWidgets.change { DashboardWidgets.cancel(app, pending.id) }
                        return@runOnUiThread
                    }
                    if (pending == null) {
                        finishWithMessage("无法保存组件授权状态，请重试")
                    } else {
                        widgetId = pending.id
                        requestBinding(component)
                    }
                }
            }
        }
    }

    /** 系统确认窗口在主线程发起，磁盘写入已由后台完成 */
    private fun requestBinding(component: ComponentName) {
        try {
            val options =
                Bundle().apply {
                    putInt(
                        AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                    )
                }
            if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, component, options)) {
                afterBound()
            } else {
                bindResult.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options)
                    },
                )
            }
        } catch (_: Exception) {
            finishWithMessage("系统未能完成组件授权，请重试")
        }
    }

    /** 按已登记编号检查系统绑定，不直接使用返回 Intent 的编号 */
    private fun afterBound() {
        val pending = DashboardWidgets.pending(this)
        if (pending?.id != widgetId || AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider != pending.provider) {
            finishWithMessage("未确认组件绑定，保留原选择")
            return
        }
        validate(pending.provider) { provider ->
            val optional = provider.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
            if (provider.info.configure != null && !optional && pending.phase != "configure") {
                try {
                    val app = applicationContext
                    val id = widgetId
                    DashboardWidgets.change {
                        val saved = runCatching { DashboardWidgets.configuring(app, id) }.isSuccess
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            try {
                                if (!saved || DashboardWidgets.pending(app)?.id != id) {
                                    finishWithMessage("组件授权已取消，请重新选择")
                                } else {
                                    AppWidgetHost(this, DashboardWidgets.HOST_ID)
                                        .startAppWidgetConfigureActivityForResult(this, id, 0, CONFIGURE_REQUEST, null)
                                }
                            } catch (_: Exception) {
                                finishWithMessage("无法打开官方组件的配置页面，保留原选择")
                            }
                        }
                    }
                } catch (_: Exception) {
                    finishWithMessage("无法打开官方组件的配置页面，保留原选择")
                }
            } else {
                completeBinding()
            }
        }
    }

    /** 官方配置使用系统宿主入口，兼容不对外导出的配置 Activity */
    @Deprecated("系统小组件配置仍通过请求码返回")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONFIGURE_REQUEST) {
            if (resultCode == RESULT_OK) afterBound() else finishWithMessage("已取消配置，保留原音乐组件")
        }
    }

    /** 包更新或卸载期间的迟到授权不能覆盖当前选择 */
    private fun validate(
        component: ComponentName,
        ready: (DashboardWidgetProvider) -> Unit,
    ) {
        val request = ++generation
        val app = applicationContext
        Bg.run("widget-binding-verify") {
            val provider = runCatching { DashboardWidgets.discover(app).firstOrNull { it.info.provider == component } }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                if (provider == null) finishWithMessage("组件未安装或官方签名不匹配，请重新检测") else ready(provider)
            }
        }
    }

    private fun completeBinding() {
        val app = applicationContext
        val id = widgetId
        DashboardWidgets.change {
            val saved = runCatching { DashboardWidgets.commit(app, id) }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                completed = saved
                finishWithMessage(if (completed) "已设置官方音乐组件，返回仪表盘即可查看" else "未能保存组件绑定，保留原选择")
            }
        }
    }

    /** 旋转或进程恢复发生在后台提交期间时，新窗口仍能观察到已完成的持久绑定 */
    private fun checkCompletedBinding() {
        if (isFinishing || isDestroyed || widgetId <= 0 || DashboardWidgets.selected(this) != widgetId) return
        completed = true
        finishWithMessage("已设置官方音乐组件，返回仪表盘即可查看")
    }

    private fun finishWithMessage(message: String) {
        android.widget.Toast
            .makeText(this, message, android.widget.Toast.LENGTH_LONG)
            .show()
        setResult(if (completed) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("widgetId", widgetId)
        super.onSaveInstanceState(outState)
    }

    /** 正常退出清理未完成编号，旋转及系统重建交给原结果注册恢复 */
    override fun onDestroy() {
        generation++
        getSharedPreferences(DashboardWidgets.PREFERENCES, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferencesChanged)
        if (isFinishing && !completed) {
            val app = applicationContext
            val id = widgetId
            DashboardWidgets.change { DashboardWidgets.cancel(app, id) }
        }
        super.onDestroy()
    }

    companion object {
        private const val CONFIGURE_REQUEST = 731
    }
}
