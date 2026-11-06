package io.github.shaonialife.buicklinkplus.launcher

import android.app.ActivityOptions
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.MusicPreviewView
import io.github.shaonialife.buicklinkplus.ui.setAppearanceTextColor
import io.github.shaonialife.buicklinkplus.util.Bg
import java.util.concurrent.Executors

/** 承载已授权的官方 RemoteViews */
class DashboardWidgetView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : FrameLayout(context, attrs) {
        private val message =
            TextView(context).apply {
                gravity = Gravity.CENTER
                setAppearanceTextColor(R.color.m3_on_var)
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
        private val lyrics =
            MusicPreviewView(context).apply {
                id = R.id.dashWidgetLyrics
                showLyricsOnly()
                setPadding(dp(12), 0, dp(12), dp(8))
                visibility = GONE
            }
        private var playback: MediaSessionsState.NowPlaying? = null
        private var lyricsSpaceAvailable = false
        private val lyricsTask = Runnable { refreshLyrics() }
        private var active = false
        private var generation = 0L
        private var loadedKey: Pair<Int, Long>? = null
        private var provider: DashboardWidgetProvider? = null
        private var host: MusicWidgetHost? = null
        private var hostView: AppWidgetHostView? = null
        private var lastSize: Pair<Int, Int>? = null
        private var failedKey: Pair<Int, Long>? = null
        private var closed = false
        private var sizeChanging = false
        private val resizeTask =
            Runnable {
                sizeChanging = false
                if (active && failedKey != loadedKey) synchronizeHost()
            }
        private val executor =
            Executors.newSingleThreadExecutor { work ->
                Thread(work, "dashboard-remote-views").apply { isDaemon = true }
            }

        init {
            addView(message, LayoutParams(-1, -1))
            addView(lyrics, LayoutParams(-1, -1))
        }

        /** 可见时检查签名和组件绑定，隐藏后停止监听并释放图片 */
        fun show(visible: Boolean) {
            if (closed) return
            val shouldShow = visible && isAttachedToWindow && windowVisibility == VISIBLE
            if (!shouldShow) {
                playback = null
                removeCallbacks(lyricsTask)
                lyrics.update(null, false)
                if (active) {
                    active = false
                    generation++
                    loadedKey = null
                    provider = null
                    releaseHost()
                }
                removeCallbacks(resizeTask)
                sizeChanging = false
                return
            }
            active = true
            val key = DashboardWidgets.selected(context) to VirtualLauncher.appsCacheVersion
            if (key == loadedKey) {
                if (key != failedKey && !sizeChanging) synchronizeHost()
                return
            }
            loadedKey = key
            failedKey = null
            provider = null
            releaseHost()
            showMessage("正在读取官方音乐组件…")
            val request = ++generation
            val app = context.applicationContext
            Bg.run("dashboard-widget-check") {
                val result =
                    runCatching {
                        val info = AppWidgetManager.getInstance(app).getAppWidgetInfo(key.first)
                        DashboardWidgets.discover(app).firstOrNull { it.info.provider == info?.provider }
                    }
                post {
                    if (!active || closed || generation != request || loadedKey != key) return@post
                    if (DashboardWidgets.selected(context) != key.first || VirtualLauncher.appsCacheVersion != key.second) {
                        loadedKey = null
                        show(true)
                        return@post
                    }
                    provider = result.getOrNull()
                    if (provider == null) {
                        showMessage("组件不可用或尚未授权\n请在手机设置的“仪表盘音乐组件”中重新选择；也可切回音乐卡")
                    } else {
                        synchronizeHost()
                        refreshLyrics()
                    }
                }
            }
        }

        /** 按实际区域报告尺寸，放不下时不缩放或裁切原组件 */
        private fun synchronizeHost() {
            val candidate = provider ?: return
            if (!active || width == 0 || height == 0) return
            val density = resources.displayMetrics.density
            val padding = AppWidgetHostView.getDefaultPaddingForWidget(context, candidate.info.provider, null)
            val widthDp = ((width - padding.left - padding.right) / density).toInt()
            val heightDp = ((height - padding.top - padding.bottom) / density).toInt()
            if (!widgetFits(widthDp, heightDp, candidate.widthDp, candidate.heightDp)) {
                releaseHost()
                showMessage("右侧区域不足以完整显示这个官方组件\n请拖动分隔条扩大音乐区，或切回音乐卡")
                return
            }
            try {
                if (host == null) {
                    val appWidgetId = DashboardWidgets.selected(context)
                    val routingContext =
                        WidgetIntentContext(context) { pkg ->
                            VirtualLauncher.onWidgetInteraction(pkg, display?.displayId ?: -1)
                        }
                    val createdHost = MusicWidgetHost(routingContext)
                    host = createdHost
                    hostView = createdHost.createView(routingContext, appWidgetId, candidate.info)
                    hostView?.minimumHeight = dp(candidate.heightDp) + padding.top + padding.bottom
                    addView(hostView, 0, LayoutParams(-1, -2, Gravity.TOP))
                    createdHost.startListening()
                }
                val view = hostView
                // 只报告官方组件实际占用的区域，不能把下方歌词的高度也传给播放器
                if (!sizeChanging && view != null && view.width > 0 && view.height > 0 && view.isVisible) {
                    val size = view.width to view.height
                    if (lastSize != size) {
                        view.updateAppWidgetSize(Bundle(), listOf(android.util.SizeF(view.width / density, view.height / density)))
                        lastSize = size
                    }
                }
                if (view == null || view.measuredHeight <= height) message.visibility = GONE
            } catch (error: Exception) {
                failedKey = loadedKey
                releaseHost()
                showMessage("无法显示官方组件，请切回音乐卡，或在手机重新选择组件")
                VirtualLauncher.logSink?.invoke("WIDGET: 原生组件加载失败 ${candidate.info.provider}：${error.javaClass.simpleName}")
            }
        }

        /** 隐藏时保留授权编号，重新显示后接收系统组件更新 */
        private fun releaseHost() {
            lyricsSpaceAvailable = false
            removeCallbacks(lyricsTask)
            lyrics.visibility = GONE
            lyrics.update(null, false)
            runCatching { host?.release() }
            hostView?.let {
                // 撤销框架的异步 RemoteViews 加载，旧回调最多落入已移除的旧视图
                it.updateAppWidget(null)
                it.removeAllViews()
                removeView(it)
            }
            hostView = null
            host = null
            lastSize = null
        }

        /** 只展示所选官方组件所属播放器的歌词，其他应用的媒体快照不能串入 */
        fun updateLyrics(value: MediaSessionsState.NowPlaying?) {
            if (closed) return
            playback = value.takeIf { active }
            refreshLyrics()
        }

        /** 复用原有歌词订阅和逐句调度，隐藏、空间不足时立即取消 */
        private fun refreshLyrics() {
            val visible = active && !closed && lyricsSpaceAvailable && lyrics.isShown && hostView?.visibility == VISIBLE
            val target = playback?.takeIf { it.pkg == provider?.info?.provider?.packageName }
            lyrics.update(target.takeIf { visible }, visible)
        }

        /** 组件按自身高度置顶，歌词使用剩余空间 */
        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val candidate = provider
            val minimumWidth = candidate?.widthDp?.let(::dp) ?: dp(120)
            val minimumHeight = candidate?.heightDp?.let(::dp) ?: dp(72)
            setMeasuredDimension(resolveSize(minimumWidth, widthMeasureSpec), resolveSize(minimumHeight, heightMeasureSpec))
            val exactWidth = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
            val view = hostView
            // 公开的子视图测量可读取真实高度，例如 QQ 声明最小 125dp，实际布局为 170dp
            view?.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val fits = view != null && view.measuredHeight <= measuredHeight
            if (view != null) {
                view.visibility = if (fits) VISIBLE else INVISIBLE
                if (fits) {
                    message.visibility = GONE
                } else {
                    showMessage(context.getString(R.string.dashboard_widget_too_short))
                }
            }
            message.measure(exactWidth, MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
            val remaining = (measuredHeight - (view?.measuredHeight ?: 0) - dp(8)).coerceAtLeast(0)
            val showLyrics = fits && remaining >= dp(96)
            lyrics.visibility = if (showLyrics) VISIBLE else GONE
            if (showLyrics) lyrics.measure(exactWidth, MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.EXACTLY))
            if (lyricsSpaceAvailable != showLyrics) {
                lyricsSpaceAvailable = showLyrics
                removeCallbacks(lyricsTask)
                post(lyricsTask)
            }
        }

        /** 组件固定在顶部，歌词接续在下方，尺寸通知只在实际布局变化后合并发送 */
        override fun onLayout(
            changed: Boolean,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) {
            message.layout(0, 0, measuredWidth, measuredHeight)
            hostView?.let { view ->
                view.layout(0, 0, measuredWidth, view.measuredHeight)
                if (lyrics.isVisible) {
                    val start = view.measuredHeight + dp(8)
                    lyrics.layout(0, start, measuredWidth, start + lyrics.measuredHeight)
                }
                if (lastSize != (view.width to view.height) && !sizeChanging) scheduleResize()
            }
        }

        private fun showMessage(value: String) {
            if (message.text != value) message.text = value
            message.visibility = VISIBLE
        }

        /** 分屏拖动和方向变化后重新核对实际尺寸，不改地图尺寸算法 */
        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int,
        ) {
            super.onSizeChanged(w, h, oldw, oldh)
            scheduleResize()
        }

        /** 拖动和组件高度变化共用合并后的尺寸通知 */
        private fun scheduleResize() {
            sizeChanging = true
            removeCallbacks(resizeTask)
            postDelayed(resizeTask, 150)
        }

        /** 日夜变化沿用原歌词语义配色，不修改官方组件布局或底板 */
        fun refreshAppearance() {
            lyrics.refreshAppearance()
        }

        override fun onDetachedFromWindow() {
            show(false)
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility != VISIBLE) show(false)
        }

        /** 页面销毁使异步签名结果失效，并停止组件布局执行器 */
        fun close() {
            show(false)
            closed = true
            generation++
            lyrics.close()
            executor.shutdownNow()
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

        /** 每个可见窗口独立持有监听和视图，清理不影响持久绑定 */
        private inner class MusicWidgetHost(
            context: Context,
        ) : AppWidgetHost(context, DashboardWidgets.HOST_ID) {
            override fun onCreateView(
                context: Context,
                appWidgetId: Int,
                appWidget: AppWidgetProviderInfo,
            ): AppWidgetHostView =
                object : AppWidgetHostView(context) {
                    override fun getDefaultView(): View =
                        TextView(context).apply {
                            setText(R.string.dashboard_widget_waiting_notice)
                            gravity = Gravity.CENTER
                            setAppearanceTextColor(R.color.m3_primary)
                            minimumHeight = dp(48)
                            setPadding(dp(12), dp(8), dp(12), dp(8))
                            contentDescription = "打开车载应用以初始化音乐组件"
                            setOnClickListener {
                                val pkg = appWidget.provider.packageName
                                val app = context.applicationContext
                                Bg.run("widget-open-player") {
                                    val component = app.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return@run
                                    VirtualLauncher.launchApp(component)
                                }
                            }
                        }

                    override fun getErrorView(): View =
                        TextView(context).apply {
                            text = "官方组件加载失败，请切回音乐卡，或在手机重新选择组件"
                            gravity = Gravity.CENTER
                            setAppearanceTextColor(R.color.m3_on_var)
                        }
                }.apply { setExecutor(executor) }

            fun release() {
                try {
                    stopListening()
                } finally {
                    clearViews()
                }
            }
        }
    }

/** 复用安卓公开 IntentSender 派发，保留官方播放广播并显式指定用户点击所在屏 */
internal class WidgetIntentContext(
    context: Context,
    private val onSending: (String) -> Unit,
) : ContextWrapper(context) {
    private val widgetInflater by lazy { LayoutInflater.from(applicationContext).cloneInContext(this) }

    /** 用标准控件加载官方 RemoteViews，避免 AppCompat 替换控件 */
    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) widgetInflater else super.getSystemService(name)

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
    ) {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)
    }

    override fun startIntentSender(
        intent: IntentSender,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?,
    ) {
        val displayId = display?.displayId ?: -1
        val actualOptions = Bundle(options ?: Bundle())
        if (displayId >= 0) {
            val displayOptions = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }.toBundle()
            // 只合并公开 API 生成的屏幕选项，保留官方 PendingIntent 的动画和后台启动授权
            ActivityOptions
                .makeBasic()
                .toBundle()
                .keySet()
                .forEach { displayOptions.remove(it) }
            actualOptions.putAll(displayOptions)
        }
        // 先记录本次点击的输入边界，避免快速页面在派发返回前已出帧而被误判为无新图像
        intent.creatorPackage?.let(onSending)
        super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, actualOptions)
        intent.creatorPackage?.let { creator ->
            VirtualLauncher.logSink?.invoke("WIDGET: 官方组件交互已派发 package=$creator display=$displayId")
        }
    }
}
