package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgetBindActivity
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgetView
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgets
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.media.MediaSessionsState
import io.github.shaonialife.buicklinkplus.media.MusicPreviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 在装有官方应用的测试环境中检查音乐组件 */
class OfficialWidgetsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun officialProvidersBindThroughSystemAndSurviveVisibilityChanges() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realWidgets") == "true")
        val context = instrumentation.targetContext
        val providers = DashboardWidgets.discover(context)
        assertTrue("专用环境未安装官方组件", providers.isNotEmpty())
        assertEquals("专用环境必须从未绑定状态开始", 0, DashboardWidgets.selected(context))
        assertTrue(DashboardWidgets.pending(context) == null)
        val folder = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
        // 首次使用实际系统取消按钮，不能创建虚假绑定或残留临时编号
        val authorizing =
            instrumentation.startActivitySync(
                Intent(context, DashboardWidgetBindActivity::class.java)
                    .putExtra(
                        "provider",
                        providers
                            .first()
                            .info.provider
                            .flattenToString(),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        await { DashboardWidgets.pending(context) != null }
        val cancelledId = checkNotNull(DashboardWidgets.pending(context)).id
        instrumentation.runOnMainSync { authorizing.recreate() }
        awaitSystemButton("android:id/button2")
        await { DashboardWidgets.pending(context) == null }
        assertEquals(0, DashboardWidgets.selected(context))
        assertTrue(cancelledId !in android.appwidget.AppWidgetHost(context, DashboardWidgets.HOST_ID).appWidgetIds)
        val previousLogger = VirtualLauncher.logSink
        val interactions = ConcurrentLinkedQueue<String>()
        VirtualLauncher.logSink = { value ->
            previousLogger?.invoke(value)
            if (value.startsWith("WIDGET: 官方组件交互已派发")) interactions.add(value)
        }

        try {
            providers.filter { it.info.provider.packageName != "com.netease.cloudmusic.iot" }.forEachIndexed { index, provider ->
                instrumentation.startActivitySync(
                    Intent(context, DashboardWidgetBindActivity::class.java)
                        .putExtra("provider", provider.info.provider.flattenToString())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                awaitSystemButton("android:id/button1")
                await { DashboardWidgets.pending(context) == null && DashboardWidgets.selected(context) > 0 }
                val id = DashboardWidgets.selected(context)
                assertEquals(provider.info.provider, AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider)
                val activity =
                    instrumentation.startActivitySync(
                        Intent(context, SettingsContentActivity::class.java)
                            .putExtra("menu", SettingsMenus.DASHBOARD_WIDGET)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ) as SettingsContentActivity
                lateinit var widget: DashboardWidgetView
                try {
                    instrumentation.runOnMainSync {
                        val frame = FrameLayout(activity)
                        // 使用真实仪表盘布局与右侧卡片比例，不能再用整屏居中的宿主窗口代替适配
                        val panel = activity.layoutInflater.inflate(R.layout.view_dashboard, frame, false)
                        val density = activity.resources.displayMetrics.density
                        panel.findViewById<View>(R.id.dashWidgetToolbar).visibility = View.VISIBLE
                        panel.findViewById<View>(R.id.dashMediaFallback).visibility = View.GONE
                        panel.setAppearanceBackground(R.drawable.bg_card)
                        widget = panel.findViewById(R.id.dashWidgetContent)
                        widget.visibility = View.VISIBLE
                        frame.addView(panel, FrameLayout.LayoutParams((280 * density).toInt(), (380 * density).toInt(), Gravity.RIGHT))
                        activity.setContentView(frame)
                        widget.show(true)
                    }
                    awaitHost(widget)
                    await {
                        val host = allViews(widget).filterIsInstance<AppWidgetHostView>().single()
                        val lyrics = widget.findViewById<MusicPreviewView>(R.id.dashWidgetLyrics)
                        assertEquals("官方组件应位于顶部", 0, host.top)
                        val content = host.getChildAt(0)
                        assertTrue("官方内容不能超出宿主边界", content.top >= 0 && content.bottom <= host.height && content.right <= host.width)
                        lyrics.isShown && lyrics.height > 0 && lyrics.top >= host.bottom && lyrics.bottom <= widget.height
                    }
                    // 仅注入无曲目信息的实时测试句，验证同包隔离，不发起歌词网络搜索
                    val sample =
                        MediaSessionsState.NowPlaying(
                            null,
                            null,
                            false,
                            pkg = provider.info.provider.packageName,
                            lyricLine = "当前播放器的布局测试歌词",
                        )
                    instrumentation.runOnMainSync { widget.updateLyrics(sample) }
                    await { allViews(widget).filterIsInstance<TextView>().any { it.isShown && it.text == sample.lyricLine } }
                    instrumentation.runOnMainSync { widget.updateLyrics(sample.copy(pkg = "test.other.player", lyricLine = "其他播放器的测试歌词")) }
                    await {
                        allViews(widget).filterIsInstance<TextView>().none {
                            it.isShown &&
                                (it.text == sample.lyricLine || it.text == "其他播放器的测试歌词")
                        }
                    }
                    instrumentation.runOnMainSync { widget.updateLyrics(null) }
                    repeat(3) {
                        instrumentation.runOnMainSync { widget.show(false) }
                        instrumentation.runOnMainSync { widget.show(true) }
                        awaitHost(widget)
                    }
                    screenshot(activity, folder, "official-widget-$index.png")
                    val names = StringBuilder()
                    instrumentation.runOnMainSync {
                        allViews(widget).forEach { view ->
                            val resource =
                                if (view.id == View.NO_ID) "" else runCatching { view.resources.getResourceName(view.id) }.getOrDefault("")
                            val text = (view as? TextView)?.text?.toString().orEmpty()
                            names.appendLine(
                                "$resource ${view.javaClass.simpleName} ${view.width}×${view.height} clickable=${view.isClickable} $text",
                            )
                        }
                    }
                    File(folder, "official-widget-$index-views.txt").writeText(names.toString())
                    repeat(12) {
                        instrumentation.runOnMainSync { widget.show(false) }
                        instrumentation.runOnMainSync { widget.show(true) }
                    }
                    awaitHost(widget)
                    val interactionCount = interactions.size
                    val tapPosition = IntArray(2)
                    instrumentation.runOnMainSync {
                        widget.layoutParams =
                            widget.layoutParams.apply { height = (150 * activity.resources.displayMetrics.density).toInt() }
                        widget.requestLayout()
                    }
                    await { !widget.findViewById<MusicPreviewView>(R.id.dashWidgetLyrics).isShown }
                    instrumentation.runOnMainSync {
                        widget.layoutParams = widget.layoutParams.apply { height = -1 }
                        widget.requestLayout()
                    }
                    awaitHost(widget)
                    await { widget.findViewById<MusicPreviewView>(R.id.dashWidgetLyrics).isShown }
                    instrumentation.runOnMainSync {
                        assertEquals(1, allViews(widget).filterIsInstance<AppWidgetHostView>().size)
                        widget.layoutParams =
                            widget.layoutParams.apply {
                                width = (100 * activity.resources.displayMetrics.density).toInt()
                            }
                        widget.requestLayout()
                    }
                    await {
                        allViews(widget).filterIsInstance<AppWidgetHostView>().isEmpty() &&
                            allViews(widget).filterIsInstance<TextView>().any { it.text.contains("请拖动分隔条") }
                    }
                    screenshot(activity, folder, "official-widget-$index-small.png")
                    instrumentation.runOnMainSync {
                        widget.layoutParams = widget.layoutParams.apply { width = -1 }
                        widget.requestLayout()
                    }
                    awaitHost(widget)
                    instrumentation.runOnMainSync {
                        val button =
                            allViews(widget).firstOrNull {
                                val name = runCatching { it.resources.getResourceEntryName(it.id) }.getOrNull()
                                it.isClickable && name in setOf("widget_play_stop", "control_play_layout")
                            }
                        assertTrue("官方播放按钮缺失", button != null)
                        checkNotNull(button).getLocationOnScreen(tapPosition)
                        tapPosition[0] += button.width / 2
                        tapPosition[1] += button.height / 2
                    }
                    // 从屏幕坐标注入真实点击，覆盖上方组件与下方歌词的触摸区域是否重叠
                    val downAt = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event =
                            MotionEvent.obtain(
                                downAt,
                                SystemClock.uptimeMillis(),
                                action,
                                tapPosition[0].toFloat(),
                                tapPosition[1].toFloat(),
                                0,
                            )
                        try {
                            event.source = InputDevice.SOURCE_TOUCHSCREEN
                            assertTrue("官方按钮触摸派发失败", instrumentation.uiAutomation.injectInputEvent(event, true))
                        } finally {
                            event.recycle()
                        }
                    }
                    await { interactions.drop(interactionCount).any { it.contains("package=${provider.info.provider.packageName}") } }
                    File(folder, "official-widget-interactions.txt").writeText(interactions.joinToString("\n"))
                } finally {
                    instrumentation.runOnMainSync {
                        widget.close()
                        activity.finish()
                    }
                }
            }
        } finally {
            VirtualLauncher.logSink = previousLogger
            instrumentation.runOnMainSync {
                DashboardWidgets.cancel(context)
                DashboardWidgets.remove(context)
            }
        }
    }

    private fun awaitHost(widget: DashboardWidgetView) {
        await {
            widget.show(true)
            allViews(widget).filterIsInstance<AppWidgetHostView>().any { host ->
                host.isShown &&
                    host.width > 0 &&
                    host.height > 0 &&
                    !host.isLayoutRequested &&
                    host.childCount > 0 &&
                    allViews(host).any {
                        val resourcePackage = runCatching { it.resources.getResourcePackageName(it.id) }.getOrNull()
                        it.id != View.NO_ID && resourcePackage == host.appWidgetInfo.provider.packageName
                    }
            }
        }
    }

    /** 点击系统确认窗口完成绑定 */
    private fun awaitSystemButton(id: String) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root?.packageName?.toString() != instrumentation.targetContext.packageName) {
                val button = root?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isEnabled && it.isClickable }
                if (button != null && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            }
            SystemClock.sleep(100)
        }
        error("未出现系统小组件授权按钮 $id")
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("真实官方组件状态未在时限内完成")
    }

    private fun screenshot(
        activity: Activity,
        folder: File,
        name: String,
    ) {
        await { activity.hasWindowFocus() }
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            activity.window.decorView.viewTreeObserver
                .registerFrameCommitCallback { committed.countDown() }
            activity.window.decorView.invalidate()
        }
        check(committed.await(3, TimeUnit.SECONDS)) { "官方组件页面没有提交绘制帧" }
        instrumentation.uiAutomation.waitForIdle(200, 3000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun allViews(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
