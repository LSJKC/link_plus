package io.github.shaonialife.buicklinkplus.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.MainActivity
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.launcher.DesktopApps
import io.github.shaonialife.buicklinkplus.launcher.OfficialCarAppDiscovery
import io.github.shaonialife.buicklinkplus.launcher.OfficialCarApps
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.launcher.settings.SettingsMenus
import io.github.shaonialife.buicklinkplus.session.SessionController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 检查首页旋转后的布局 */
class AppScreensAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** 检查旧分类和二级面板仍能通过原 Intent 参数打开 */
    @Test fun allRegisteredSettingsTargetsCreateVisibleContent() {
        (SettingsMenus.ALL + SettingsMenus.PANELS).forEach { item ->
            val activity =
                instrumentation.startActivitySync(
                    Intent(instrumentation.targetContext, SettingsContentActivity::class.java)
                        .putExtra("menu", item.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as SettingsContentActivity
            try {
                await {
                    activity.hasWindowFocus() &&
                        activity.supportFragmentManager
                            .findFragmentById(R.id.settingsContainer)
                            ?.view
                            ?.isLaidOut == true
                }
                instrumentation.runOnMainSync {
                    val expected = SettingsMenus.directPhonePanel(item.id) ?: item.id
                    assertEquals(SettingsMenus.titleOf(expected), activity.findViewById<TextView>(R.id.contentTitle).text.toString())
                    val fragment = activity.supportFragmentManager.findFragmentById(R.id.settingsContainer)
                    assertTrue("设置面板未显示：${item.id}", fragment?.view?.isShown == true)
                    val back = activity.findViewById<View>(R.id.btnBack)
                    assertTrue("返回按钮触达区域不足", back.height >= (48 * activity.resources.displayMetrics.density).toInt())
                }
                screenshot(activity, "settings-target-${item.id}")
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    /** 检查启动、取消和断开按钮的显示 */
    @Test fun homeConnectionStatesExposeDistinctReachableActions() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            acceptFirstUseIfShown(activity)
            instrumentation.runOnMainSync {
                val handler =
                    activity.javaClass
                        .getDeclaredField(
                            "mainHandler",
                        ).apply { isAccessible = true }
                        .get(activity) as android.os.Handler
                val tick =
                    activity.javaClass
                        .getDeclaredField("heroTick")
                        .apply { isAccessible = true }
                        .get(activity) as Runnable
                handler.removeCallbacks(tick)
            }
            val stateClass = activity.javaClass.declaredClasses.single { it.simpleName == "HeroState" }
            val render = activity.javaClass.getDeclaredMethod("applyHeroState", stateClass).apply { isAccessible = true }
            val states = stateClass.enumConstants.orEmpty().associateBy { it.toString() }
            listOf("IDLE" to "启动连接", "CONNECTING" to "取消连接", "CONNECTED" to "断开连接").forEach { (state, action) ->
                instrumentation.runOnMainSync {
                    render.invoke(activity, states.getValue(state))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertEquals("状态操作文案不匹配", action, activity.findViewById<TextView>(R.id.ctaLabel).text.toString())
                    checkConnectionButton(activity)
                }
                screenshot(activity, "home-render-$state")
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 安装引导按缺失状态展示，保留用户桌面选择 */
    @Test fun officialCarAppGuidePreservesDesktopAndShowsInstalledState() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra("menu", SettingsMenus.OFFICIAL_CAR_APPS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SettingsContentActivity
        try {
            await {
                activity.window.decorView.findViewWithTag<View>("official-car-apps-ready") != null && activity.hasWindowFocus()
            }
            val apps = VirtualLauncher.cachedApps(context)
            val installed = OfficialCarAppDiscovery.scan(context, apps)
            instrumentation.runOnMainSync {
                OfficialCarApps.entries.forEach { definition ->
                    val section = activity.window.decorView.findViewWithTag<LinearLayout>(definition.packageName)
                    checkNotNull(section) { "车载应用条目缺失" }
                    val buttons = (0 until section.childCount).map { section.getChildAt(it) }.filterIsInstance<Button>()
                    assertTrue("缺少官网安装入口", buttons.any { it.text == "前往官方网站" })
                    if (apps.none { it.component.packageName == definition.packageName }) {
                        assertTrue("未安装应用出现虚假启动按钮", buttons.none { it.text == "在手机打开" || it.text == "添加到车机桌面" })
                    } else {
                        val actual = installed.single { it.definition == definition }
                        assertTrue("已安装包的签名或真实入口未确认", actual.canAddToDesktop)
                        val component = checkNotNull(actual.component)
                        val selected = DesktopApps.selected(context, apps)
                        val toggle = buttons.single { it.text == "添加到车机桌面" || it.text == "从桌面移除" }
                        assertEquals("桌面选择状态与按钮不一致", component.flattenToString() in selected, toggle.text == "从桌面移除")
                    }
                    buttons.forEach { assertTrue("按钮触达高度不足", it.height >= (48 * activity.resources.displayMetrics.density).toInt()) }
                }
                assertEquals("进入页面不应修改现有偏好", before, preferences.all)
            }
            screenshot(activity, "official-car-apps")
            installed.firstOrNull { it.canAddToDesktop }?.let { actual ->
                val component = checkNotNull(actual.component)
                val key = component.flattenToString()
                val originalSelection = DesktopApps.selected(context, apps)
                val hadPreference = preferences.contains("desktopApps")
                val savedPreference = preferences.getStringSet("desktopApps", emptySet()).orEmpty().toSet()
                try {
                    instrumentation.runOnMainSync {
                        val section = activity.window.decorView.findViewWithTag<LinearLayout>(actual.definition.packageName)
                        val toggle =
                            (0 until section.childCount)
                                .map { section.getChildAt(it) }
                                .filterIsInstance<Button>()
                                .single { it.text == "添加到车机桌面" || it.text == "从桌面移除" }
                        assertTrue("桌面选择按钮无法点击", toggle.performClick())
                        val expected = originalSelection.toMutableSet().apply { if (!remove(key)) add(key) }
                        assertEquals("添加车载版时破坏了其他桌面选择", expected, DesktopApps.selected(context, apps))
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        preferences
                            .edit()
                            .apply {
                                if (hadPreference) putStringSet("desktopApps", savedPreference) else remove("desktopApps")
                            }.commit()
                    }
                }
                assertEquals("测试后未恢复专用环境的原偏好", before, preferences.all)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 旋转后复用首页控件，主按钮仍可见可点 */
    @Test fun homeSurvivesRotationWithReachableConnectionAction() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            acceptFirstUseIfShown(activity)
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            await {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                    activity.findViewById<View>(R.id.ctaButton).isLaidOut
            }
            var original: View? = null
            instrumentation.runOnMainSync {
                original = activity.findViewById(R.id.ctaButton)
                checkConnectionButton(activity)
            }
            screenshot(activity, "home-portrait")
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            await {
                activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    activity.findViewById<View>(R.id.ctaButton).isLaidOut
            }
            instrumentation.runOnMainSync {
                assertSame(original, activity.findViewById(R.id.ctaButton))
                checkConnectionButton(activity)
            }
            screenshot(activity, "home-landscape")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 手机流量设置保留选择，旋转后仍可操作 */
    @Test fun cellularPreferenceSurvivesRotationWithReachableAction() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        val hadPreference = preferences.contains("useCellularForInternet")
        val originalValue = preferences.getBoolean("useCellularForInternet", true)
        val activity =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            acceptFirstUseIfShown(activity)
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.phoneSettingsTab).performClick()
                val settings = activity.findViewById<View>(R.id.phoneSettingsIndex).parent as PhoneSettingsView
                settings.showSection(SettingsMenus.CONNECTION)
            }
            val toggle = activity.findViewById<SwitchCompat>(R.id.switchCellular)
            instrumentation.runOnMainSync {
                assertEquals("首页未读取已保存的流量偏好", originalValue, toggle.isChecked)
                assertTrue("流量开关未启用", toggle.isEnabled && toggle.isClickable)
                // CompoundButton 先切换，再返回监听器的处理结果；返回 false 不表示状态未切换
                toggle.performClick()
                assertEquals("流量开关未切换", !originalValue, toggle.isChecked)
                assertEquals("流量选择未保存", !originalValue, preferences.getBoolean("useCellularForInternet", originalValue))
            }
            listOf(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to Configuration.ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to Configuration.ORIENTATION_LANDSCAPE,
            ).forEach { (requested, actual) ->
                instrumentation.runOnMainSync { activity.requestedOrientation = requested }
                await { activity.resources.configuration.orientation == actual && activity.hasWindowFocus() }
                instrumentation.runOnMainSync {
                    assertSame("旋转后流量开关被替换", toggle, activity.findViewById(R.id.switchCellular))
                    assertEquals("旋转后流量选择丢失", !originalValue, toggle.isChecked)
                    activity.findViewById<ScrollView>(R.id.phoneSettingsDetail).fullScroll(View.FOCUS_DOWN)
                }
                await {
                    val visible = Rect()
                    activity.findViewById<View>(R.id.switchCellular).getGlobalVisibleRect(visible) &&
                        visible.height() >= (48 * activity.resources.displayMetrics.density).toInt()
                }
                screenshot(activity, "cellular-${if (actual == Configuration.ORIENTATION_PORTRAIT) "portrait" else "landscape"}")
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                preferences
                    .edit()
                    .apply {
                        if (hadPreference) putBoolean("useCellularForInternet", originalValue) else remove("useCellularForInternet")
                    }.commit()
            }
        }
    }

    /** 搜索直达原分类，返回和恢复页面后保留查询 */
    @Test fun settingsSearchRetainsQueryAndStableDestination() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            acceptFirstUseIfShown(activity)
            instrumentation.runOnMainSync {
                activity.findViewById<View>(R.id.phoneSettingsTab).performClick()
                val settings = activity.findViewById<View>(R.id.phoneSettingsIndex).parent as PhoneSettingsView
                assertEquals(6, activity.findViewById<LinearLayout>(R.id.phoneExperienceGroup).childCount)
                activity.findViewById<android.widget.EditText>(R.id.settingsSearch).setText("省电")
                val results = activity.findViewById<LinearLayout>(R.id.phoneSearchResults)
                assertEquals(1, results.childCount)
                assertTrue(results.getChildAt(0).performClick())
                assertEquals(SettingsMenus.PERMISSIONS, settings.currentSection)
                settings.showIndex()
                assertEquals("省电", activity.findViewById<android.widget.EditText>(R.id.settingsSearch).text.toString())
                val saved = settings.saveNavigation()
                activity.findViewById<android.widget.EditText>(R.id.settingsSearch).setText("")
                settings.restoreNavigation(saved)
                assertEquals("省电", activity.findViewById<android.widget.EditText>(R.id.settingsSearch).text.toString())
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            instrumentation.runOnMainSync {
                assertEquals("省电", activity.findViewById<android.widget.EditText>(R.id.settingsSearch).text.toString())
            }
            screenshot(activity, "settings-search-landscape")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 首页快捷设置返回首页，设置内详情返回设置列表 */
    @Test fun homeShortcutReturnsToHomeForToolbarAndSystemBack() {
        val activity =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            acceptFirstUseIfShown(activity)
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<TextView>(R.id.deviceSubtitle).text.contains(Build.MODEL))
                assertTrue(
                    activity.window.decorView
                        .findViewWithTag<View>("home-shortcut:${SettingsMenus.APPEARANCE}")
                        .performClick(),
                )
                assertTrue(activity.findViewById<View>(R.id.phoneSettingsDetail).isShown)
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            await { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<View>(R.id.phoneCategoryBack).performClick())
                assertTrue("首页快捷入口的顶部返回必须回首页", activity.findViewById<View>(R.id.mainScroll).isShown)
                activity.window.decorView
                    .findViewWithTag<View>("home-shortcut:${SettingsMenus.APPS}")
                    .performClick()
                activity.onBackPressed()
                assertTrue("首页快捷入口的系统返回必须回首页", activity.findViewById<View>(R.id.mainScroll).isShown)
                activity.findViewById<View>(R.id.phoneSettingsTab).performClick()
                val settings = activity.findViewById<View>(R.id.phoneSettingsIndex).parent as PhoneSettingsView
                settings.showSection(SettingsMenus.APPEARANCE)
                activity.findViewById<View>(R.id.phoneCategoryBack).performClick()
                assertTrue("设置标签进入的详情仍须返回设置索引", activity.findViewById<View>(R.id.phoneSettingsIndex).isShown)
            }
            screenshot(activity, "shortcut-back-origin")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 天气按钮随宽度和字号换行，保持单选并写入原偏好 */
    @Test fun weatherCityChoicesWrapAndPersistWithoutOverlap() {
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("settings", Activity.MODE_PRIVATE)
        val hadPreference = preferences.contains("weatherCity")
        val original = preferences.getString("weatherCity", null)
        val activity =
            instrumentation.startActivitySync(
                Intent(context, SettingsContentActivity::class.java)
                    .putExtra("menu", SettingsMenus.CAR_APPEARANCE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SettingsContentActivity
        try {
            await {
                val group = activity.window.decorView.findViewWithTag<View>("weather-city-options")
                group?.isLaidOut == true && activity.hasWindowFocus()
            }
            instrumentation.runOnMainSync {
                val group = activity.window.decorView.findViewWithTag<PhoneOptionGroup>("weather-city-options")
                val buttons = (0 until group.childCount).map { group.getChildAt(it) as RadioButton }
                assertEquals("定位和八个城市必须完整保留", 9, buttons.size)
                val gap = (7 * activity.resources.displayMetrics.density).toInt()
                buttons.forEachIndexed { index, button ->
                    assertTrue("天气选项热区不足", button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue("天气选项越出卡片", button.left >= 0 && button.right <= group.width)
                    buttons.drop(index + 1).forEach { other ->
                        val horizontalGap = maxOf(other.left - button.right, button.left - other.right)
                        val verticalGap = maxOf(other.top - button.bottom, button.top - other.bottom)
                        assertTrue("天气选项重叠或间距不足", horizontalGap >= gap || verticalGap >= gap)
                    }
                }
                buttons.last().performClick()
                assertEquals("城市必须保存到原偏好", buttons.last().tag, CarAppearancePrefs.weatherCity(context))
                assertEquals("天气城市只能单选", 1, buttons.count { it.isChecked })
                val fragment = activity.supportFragmentManager.findFragmentById(R.id.settingsContainer)
                (fragment?.view as ScrollView).fullScroll(View.FOCUS_DOWN)
            }
            screenshot(activity, "weather-city-choices")
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                preferences
                    .edit()
                    .apply {
                        if (hadPreference) putString("weatherCity", original) else remove("weatherCity")
                    }.commit()
            }
        }
    }

    /** 热点弹窗只返回用户选择，保留或取消不打开 WLAN */
    @Test fun wifiPromptChoicesDoNotOpenSettingsInUiCallback() {
        val context = instrumentation.targetContext
        val activity =
            instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        val monitor =
            instrumentation.addMonitor(
                IntentFilter(android.provider.Settings.ACTION_WIFI_SETTINGS),
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
                true,
            )
        val observations = StringBuilder()
        try {
            acceptFirstUseIfShown(activity)
            val cases =
                listOf(
                    SessionController.DialogSpec("已保存该车机热点", "热点选择专项", "清除保存", "保留"),
                    SessionController.DialogSpec("WiFi 未开启", "热点选择专项", "打开 WLAN 设置", "取消"),
                    SessionController.DialogSpec("未能加入车机热点", "热点选择专项", "重试", "打开 WLAN 设置"),
                    SessionController.DialogSpec("连接车机热点", "热点选择专项", "继续自动加入", "打开 WLAN 设置"),
                )
            for (spec in cases) {
                for (positive in listOf(false, true)) {
                    val received = mutableListOf<Boolean?>()
                    instrumentation.runOnMainSync { checkNotNull(SessionController.dialogSink).invoke(spec) { received += it } }
                    lateinit var actions: LinearLayout
                    await {
                        val root = WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<View>(R.id.msgActions) != null }
                        actions = root?.findViewById(R.id.msgActions) ?: return@await false
                        actions.isLaidOut && root.hasWindowFocus()
                    }
                    instrumentation.runOnMainSync {
                        val button = actions.getChildAt(if (positive) 1 else 0) as TextView
                        assertEquals(if (positive) spec.positive else spec.negative, button.text.toString())
                        assertTrue("真实弹窗按钮不能被禁用", button.isEnabled && button.isClickable)
                        button.performClick()
                        assertEquals(listOf(positive), received)
                    }
                    instrumentation.waitForIdleSync()
                    observations.appendLine(
                        "提示=${spec.title} 选择=${if (positive) spec.positive else spec.negative} WLAN启动次数=${monitor.hits}",
                    )
                    File(context.getExternalFilesDir(null), "wifi-dialog-choice-result.txt").writeText(observations.toString())
                    assertEquals("界面只能回传选择，跳转应由原调用分支处理", 0, monitor.hits)
                    await { activity.hasWindowFocus() }
                }
            }
            // 原调用方的系统跳转仍须可用，不能靠移除手动连接功能使前面断言通过
            val openWlan = SessionController::class.java.getDeclaredMethod("openWlanSettings").apply { isAccessible = true }
            openWlan.invoke(SessionController)
            instrumentation.waitForIdleSync()
            assertEquals("会话控制器仍须能够发起真实 WLAN 设置 Intent", 1, monitor.hits)
            observations.appendLine("原控制器跳转 WLAN启动次数=${monitor.hits}")
            File(context.getExternalFilesDir(null), "wifi-dialog-choice-result.txt").writeText(observations.toString())
            screenshot(activity, "wifi-dialog-keeps-home")
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 检查按钮点击范围和底栏贴边位置 */
    private fun checkConnectionButton(activity: Activity) {
        val button = activity.findViewById<View>(R.id.ctaButton)
        val visible = Rect()
        assertTrue("首页被其他窗口遮挡", activity.hasWindowFocus())
        assertTrue("连接按钮不可见", button.getGlobalVisibleRect(visible))
        assertTrue("连接按钮触达区域不足", visible.height() >= (48 * activity.resources.displayMetrics.density).toInt())
        val page = Rect()
        val navigation = Rect()
        assertTrue(activity.findViewById<View>(R.id.pageColumn).getGlobalVisibleRect(page))
        assertTrue(activity.findViewById<View>(R.id.phoneBottomNavigation).getGlobalVisibleRect(navigation))
        assertEquals("底栏必须铺满页面左缘", page.left, navigation.left)
        assertEquals("底栏必须铺满页面右缘", page.right, navigation.right)
        assertEquals("底栏必须贴合页面底部", page.bottom, navigation.bottom)
        listOf(R.id.phoneHomeTab, R.id.phoneSettingsTab).forEach { id ->
            val area = Rect()
            assertTrue(activity.findViewById<View>(id).getGlobalVisibleRect(area))
            assertTrue("导航触达区域不足", area.height() >= (48 * activity.resources.displayMetrics.density).toInt())
        }
        assertTrue("首页启动后已退出", !activity.isFinishing)
    }

    /** 等声明倒计时结束并滚到底部后确认 */
    private fun acceptFirstUseIfShown(activity: Activity) {
        var scroll: ScrollView? = null
        var accept: TextView? = null
        await {
            val window = WindowInspector.getGlobalWindowViews().firstOrNull { it.findViewById<View>(R.id.disclaimerScroll) != null }
            scroll = window?.findViewById(R.id.disclaimerScroll)
            accept = window?.findViewById(R.id.btnAccept)
            scroll != null || activity.hasWindowFocus()
        }
        scroll?.let { content ->
            await { content.isLaidOut && content.height > 0 }
            instrumentation.runOnMainSync { content.fullScroll(View.FOCUS_DOWN) }
            await(15_000L) { accept?.isEnabled == true }
            instrumentation.runOnMainSync { check(accept?.performClick() == true) }
        }
        await { activity.hasWindowFocus() }
    }

    /** 主线程读取视图条件，等待设置超时 */
    private fun await(
        timeoutMs: Long = 8_000L,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(100)
        }
        error("页面未在截止时间内就绪")
    }

    /** 按系统版本保存截图，供宿主脚本收集 */
    private fun screenshot(
        activity: Activity,
        name: String,
    ) {
        await { activity.hasWindowFocus() }
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val root = activity.window.decorView
            root.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            root.invalidate()
        }
        check(committed.await(3, TimeUnit.SECONDS)) { "页面未在截止时间内提交绘制帧" }
        instrumentation.uiAutomation.waitForIdle(200L, 3_000L)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "未能读取模拟器截图" }
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
            File(folder, "api${Build.VERSION.SDK_INT}-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "截图写入失败" }
            }
        } finally {
            bitmap.recycle()
        }
    }
}
