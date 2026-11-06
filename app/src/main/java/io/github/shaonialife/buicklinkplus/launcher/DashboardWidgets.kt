package io.github.shaonialife.buicklinkplus.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context

/** 官方组件安装信息，尺寸转成 dp 后跨屏使用 */
data class DashboardWidgetProvider(
    val info: AppWidgetProviderInfo,
    val title: String,
    val widthDp: Int,
    val heightDp: Int,
)

/** 持久绑定与未完成授权分别登记，取消新组件不会丢失原选择 */
object DashboardWidgets {
    const val HOST_ID = 0x42554D
    const val PREFERENCES = "dashboard_widget"
    private const val SELECTED = "selected"
    private const val PENDING = "pending"
    private const val PROVIDER = "provider"
    private const val PHASE = "phase"

    // 只列入已核对原版 APK 的通用桌面组件，不启用比亚迪等厂商专属接口
    private val verifiedProviders =
        setOf(
            "com.tencent.qqmusiccar.syswidget.WidgetProviderMiddle",
            "com.kugou.android.appwidget.KugouAppWidgetProvider",
            "com.kugou.android.appwidget.KugouAppMiniWidgetProvider",
            "com.netease.cloudmusic.module.appwidget.PlayerWidget",
            "com.netease.cloudmusic.module.appwidget.PlayerWidgetFourTwo",
            "com.netease.cloudmusic.module.appwidget.PlayerWidgetFourFour",
        )

    data class Pending(
        val id: Int,
        val provider: ComponentName,
        val phase: String,
    )

    /** 后台扫描包和签名，不只凭包名判断官方来源 */
    fun discover(context: Context): List<DashboardWidgetProvider> {
        val installed = OfficialCarAppDiscovery.scan(context, VirtualLauncher.cachedApps(context))
        val allowed = installed.filter { it.canAddToDesktop }.associateBy { it.definition.packageName }
        val density = context.resources.displayMetrics.density
        return AppWidgetManager
            .getInstance(context)
            .installedProviders
            .filter {
                it.provider.packageName in allowed &&
                    it.provider.className in verifiedProviders &&
                    it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
            }.map { info ->
                val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
                val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
                val minimumWidth = if (horizontal && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
                val minimumHeight = if (vertical && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
                DashboardWidgetProvider(
                    info,
                    "${allowed.getValue(info.provider.packageName).definition.title} · ${info.loadLabel(context.packageManager)}",
                    kotlin.math
                        .ceil(minimumWidth / density)
                        .toInt()
                        .coerceAtLeast(1),
                    kotlin.math
                        .ceil(minimumHeight / density)
                        .toInt()
                        .coerceAtLeast(1),
                )
            }.sortedBy { it.info.provider.flattenToString() }
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** 编号变更及同步持久化串行放入后台，界面不等待磁盘写入 */
    fun change(action: () -> Unit) {
        io.github.shaonialife.buicklinkplus.util.Bg
            .run("dashboard-widget-change") { synchronized(this) { action() } }
    }

    /** 选择为空时沿用现有音乐卡，不自动绑定或启动第三方应用 */
    fun selected(context: Context): Int = preferences(context).getInt(SELECTED, AppWidgetManager.INVALID_APPWIDGET_ID)

    /** 授权与配置页面被系统重建时继续使用同一编号 */
    fun pending(context: Context): Pending? {
        val values = preferences(context).all
        val id = values[PENDING] as? Int ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = (values[PROVIDER] as? String)?.let(ComponentName::unflattenFromString)
        return if (id > 0 && provider != null) Pending(id, provider, values[PHASE] as? String ?: "bind") else null
    }

    /** 分配前清理本宿主的孤立编号，绝不删除系统桌面或其他应用的小组件 */
    // 必须确认编号持久化成功后才交给系统授权，失败立即释放新编号
    @android.annotation.SuppressLint("UseKtx")
    fun prepare(
        context: Context,
        provider: ComponentName,
    ): Pending {
        check(pending(context) == null) { "请先取消未完成的组件授权" }
        val host = AppWidgetHost(context, HOST_ID)
        val selected = selected(context)
        host.appWidgetIds.filter { it != selected }.forEach { host.deleteAppWidgetId(it) }
        val id = host.allocateAppWidgetId()
        if (!preferences(
                context,
            ).edit().putInt(PENDING, id).putString(PROVIDER, provider.flattenToString()).putString(PHASE, "bind").commit()
        ) {
            host.deleteAppWidgetId(id)
            error("无法保存组件授权状态，请重试")
        }
        return checkNotNull(pending(context))
    }

    /** 配置前先落盘阶段，避免旋转或进程恢复后重复配置 */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun configuring(
        context: Context,
        id: Int,
    ) {
        if (pending(context)?.id == id) check(preferences(context).edit().putString(PHASE, "configure").commit())
    }

    /** 提交前检查授权仍有效，新绑定成功后再释放旧组件 */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun commit(
        context: Context,
        id: Int,
    ): Boolean {
        val pending = pending(context) ?: return false
        if (pending.id != id || AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider != pending.provider) return false
        val old = selected(context)
        if (!preferences(context)
                .edit()
                .putInt(SELECTED, id)
                .remove(PENDING)
                .remove(PROVIDER)
                .remove(PHASE)
                .commit()
        ) {
            return false
        }
        if (old > 0 && old != id) runCatching { AppWidgetHost(context, HOST_ID).deleteAppWidgetId(old) }
        return true
    }

    /** 用户取消或配置失败仅释放当前临时编号，保留原组件和播放状态 */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun cancel(
        context: Context,
        expectedId: Int? = null,
    ) {
        val id = pending(context)?.id
        if (expectedId != null && id != expectedId) return
        check(
            preferences(context)
                .edit()
                .remove(PENDING)
                .remove(PROVIDER)
                .remove(PHASE)
                .commit(),
        ) { "无法保存取消结果，请重试" }
        if (id != null) runCatching { AppWidgetHost(context, HOST_ID).deleteAppWidgetId(id) }
    }

    /** 移除绑定只恢复音乐卡，不停止播放器或清除第三方账号 */
    // 在后台事务中同步落盘并检查结果，成功后才释放旧资源或修改系统状态
    @android.annotation.SuppressLint("UseKtx", "ApplySharedPref")
    fun remove(context: Context) {
        cancel(context)
        val old = selected(context)
        check(preferences(context).edit().remove(SELECTED).commit()) { "无法保存组件移除结果，请重试" }
        if (old > 0) runCatching { AppWidgetHost(context, HOST_ID).deleteAppWidgetId(old) }
    }
}

/** 原组件不能压缩或裁掉按钮，宽高不足时提示调整分屏 */
internal fun widgetFits(
    availableWidth: Int,
    availableHeight: Int,
    minimumWidth: Int,
    minimumHeight: Int,
): Boolean = availableWidth > 0 && availableHeight > 0 && availableWidth >= minimumWidth && availableHeight >= minimumHeight
