package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.DashboardWidgets
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 检查系统组件编号、取消绑定和空环境 */
class DashboardWidgetsAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun cancelledOrUnboundWidgetNeverReplacesSelection() {
        val context = instrumentation.targetContext
        assumeTrue(DashboardWidgets.pending(context) == null)
        val before = DashboardWidgets.selected(context)
        val host = AppWidgetHost(context, DashboardWidgets.HOST_ID)
        instrumentation.runOnMainSync {
            val pending = DashboardWidgets.prepare(context, ComponentName("test.not.installed", "test.not.installed.Widget"))
            try {
                assertTrue(pending.id in host.appWidgetIds)
                assertFalse(DashboardWidgets.commit(context, pending.id))
                assertFalse(DashboardWidgets.commit(context, pending.id + 1))
                assertEquals(before, DashboardWidgets.selected(context))
            } finally {
                DashboardWidgets.cancel(context)
            }
            assertNull(DashboardWidgets.pending(context))
            assertTrue(pending.id !in host.appWidgetIds)
            assertEquals(before, DashboardWidgets.selected(context))
        }
    }

    @Test fun widgetSettingsShowsRealProvidersWithoutChangingPreferences() {
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(DashboardWidgets.PREFERENCES, Activity.MODE_PRIVATE)
        val before = prefs.all.toMap()
        val providers = DashboardWidgets.discover(context)
        val activity =
            instrumentation.startActivitySync(
                Intent(
                    context,
                    SettingsContentActivity::class.java,
                ).putExtra("menu", SettingsMenus.DASHBOARD_WIDGET).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SettingsContentActivity
        try {
            await { activity.window.decorView.findViewWithTag<View>("dashboard-widgets-ready") != null && activity.hasWindowFocus() }
            instrumentation.runOnMainSync {
                val body = activity.window.decorView.findViewWithTag<LinearLayout>("dashboard-widgets-ready")
                val buttons = (0 until body.childCount).map { body.getChildAt(it) }.filterIsInstance<Button>()
                assertEquals(providers.size, buttons.count { it.text == "使用此组件" })
                providers.forEach { provider -> assertTrue(buttons.any { it.tag == provider.info.provider.flattenToString() }) }
                assertEquals(before, prefs.all)
            }
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                activity.window.decorView.viewTreeObserver
                    .registerFrameCommitCallback { committed.countDown() }
                activity.window.decorView.invalidate()
            }
            check(committed.await(3, TimeUnit.SECONDS)) { "组件设置页面没有提交绘制帧" }
            instrumentation.uiAutomation.waitForIdle(200, 3000)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val folder = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
                File(
                    folder,
                    "dashboard-widget-settings.png",
                ).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var result = false
            instrumentation.runOnMainSync { result = condition() }
            if (result) return
            SystemClock.sleep(100)
        }
        error("等待组件设置页面超时")
    }
}
