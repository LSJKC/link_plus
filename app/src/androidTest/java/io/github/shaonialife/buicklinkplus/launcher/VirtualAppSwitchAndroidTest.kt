package io.github.shaonialife.buicklinkplus.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.input.TouchReports
import io.github.shaonialife.buicklinkplus.input.TouchRouter
import io.github.shaonialife.buicklinkplus.render.CARD_MARGIN_PX
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.PixelArea
import io.github.shaonialife.buicklinkplus.shizuku.CarInputClient
import io.github.shaonialife.buicklinkplus.shizuku.EmbeddedServiceMain
import io.github.shaonialife.buicklinkplus.shizuku.EmbeddedServiceProvider
import io.github.shaonialife.buicklinkplus.shizuku.Shell
import io.github.shaonialife.buicklinkplus.util.shellQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** 在装有官方应用的测试模拟器上检查虚拟屏切换 */
class VirtualAppSwitchAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 组件打开酷狗后隐藏地图，返回仪表盘保留导航，再从图标复用音乐任务 */
    @Test
    fun officialWidgetAndIconOpenFullPlayerWithoutMapOverlay() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realAppSwitch") == "true")
        val player = "com.kugou.android.auto"
        val useRealMap = InstrumentationRegistry.getArguments().getString("realMap") == "true"
        val map = if (useRealMap) "com.autonavi.amapauto" else context.packageName
        val component = checkNotNull(context.packageManager.getLaunchIntentForPackage(player)?.component)
        val provider =
            DashboardWidgets.discover(context).first {
                it.info.provider.packageName == player &&
                    it.info.provider.className == "com.kugou.android.appwidget.KugouAppMiniWidgetProvider"
            }
        assertEquals("专项不得覆盖已有组件选择", 0, DashboardWidgets.selected(context))
        assertTrue(DashboardWidgets.pending(context) == null)
        if (useRealMap) checkNotNull(context.packageManager.getLaunchIntentForPackage(map))
        val previousMap = DefaultMapPrefs.manualPackage(context)
        val previousMode = SupersamplePrefs.mode(context, component)
        val previousLogger = VirtualLauncher.logSink
        val logs = ConcurrentLinkedQueue<String>()
        val folder = File(context.getExternalFilesDir(null), "virtual-app-switch").apply { mkdirs() }
        val capture = AtomicReference<File?>()
        val frames = AtomicInteger()
        val captureThread = HandlerThread("switch-test-output").apply { start() }
        val output = ImageReader.newInstance(1600, 720, PixelFormat.RGBA_8888, 3)
        var grid: Activity? = null
        val monitor = instrumentation.addMonitor(GridActivity::class.java.name, null, false)
        VirtualLauncher.logSink = { message ->
            logs.add(message)
            previousLogger?.invoke(message)
        }
        output.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.use { image ->
                frames.incrementAndGet()
                capture.getAndSet(null)?.let { file ->
                    val plane = image.planes.first()
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    val pending = File(file.parentFile, file.name + ".pending")
                    pending.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    check(pending.renameTo(file))
                    if (cropped !== padded) cropped.recycle()
                    padded.recycle()
                }
            }
        }, Handler(captureThread.looper))
        try {
            // 测试器仅负责启动真实 shell 服务，不把本专项计为无线调试配对验证
            assertFalse(EmbeddedServiceProvider.isRegistered)
            val token = EmbeddedServiceProvider.issueToken()
            val command =
                "CLASSPATH=${shellQuote(context.applicationInfo.sourceDir)} /system/bin/setsid /system/bin/app_process /system/bin " +
                    "--nice-name=${shellQuote(context.packageName + ":car_input")} ${EmbeddedServiceMain::class.java.name} " +
                    "${shellQuote(context.packageName)} ${shellQuote(token)} > /data/local/tmp/virtual-app-switch.log 2>&1 < /dev/null &"
            val bootstrap = File(folder, "bootstrap.sh")
            try {
                bootstrap.writeText(command + "\n")
                ParcelFileDescriptor
                    .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("sh ${bootstrap.absolutePath}"))
                    .use { it.readBytes() }
            } finally {
                bootstrap.delete()
            }
            assertTrue("真实 shell 服务未交接", EmbeddedServiceProvider.awaitBinder(10000))
            assertTrue(CarInputClient.awaitReady(context, retry = true))
            assertEquals("2000", Shell.exec("id -u").output)
            instrumentation.startActivitySync(
                Intent(context, DashboardWidgetBindActivity::class.java)
                    .putExtra("provider", provider.info.provider.flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val bindDeadline = SystemClock.uptimeMillis() + 12000
            var accepted = false
            while (!accepted && SystemClock.uptimeMillis() < bindDeadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val button = root?.findAccessibilityNodeInfosByViewId("android:id/button1")?.firstOrNull { it.isEnabled && it.isClickable }
                accepted = button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!accepted) SystemClock.sleep(100)
            }
            assertTrue("未出现真实系统组件确认窗口", accepted)
            await { DashboardWidgets.pending(context) == null && DashboardWidgets.selected(context) > 0 }
            DefaultMapPrefs.set(context, map.takeIf { useRealMap })
            SupersamplePrefs.setMode(context, component, SupersamplePrefs.MODE_AUTO)
            val geometry = DisplayGeometry.create(1600, 720, 1600, 720, PixelArea(0, 0, 1600, 720))
            assertTrue(VirtualLauncher.attach(context, output.surface, 1600, 720, 420, geometry, 30) { logs.add(it) })
            grid = checkNotNull(monitor.waitForActivityWithTimeout(12000))
            if (!useRealMap) {
                // 没有 ARM 翻译时，用测试页面检查地图层遮挡
                val page = "${context.packageName}/${MapStatusActivity::class.java.name}"
                assertTrue(
                    Shell
                        .exec(
                            "am start -W -n $page --display ${VirtualLauncher.mapDisplayId} " +
                                "--es message '地图覆盖回归页面（非高德）' -f 0x18000000",
                            8000,
                        ).ok,
                )
            }
            instrumentation.runOnMainSync { VirtualLauncher.showDashboard() }
            await { VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.SPLIT && frames.get() > 10 }
            var mapBefore = ""
            var mapTask: Int? = null
            val mapDeadline = SystemClock.uptimeMillis() + 15000
            while (mapTask == null && SystemClock.uptimeMillis() < mapDeadline) {
                mapBefore = Shell.exec("dumpsys activity activities").output
                mapTask =
                    MapLaunchResolver
                        .taskStates(mapBefore, "", VirtualLauncher.mapDisplayId, map, detailedOnly = true)
                        .firstOrNull { it.ready }
                        ?.taskId
                if (mapTask == null) SystemClock.sleep(100)
            }
            File(folder, "map-before.txt").writeText(mapBefore)
            assertTrue("地图层页面未恢复到真实地图屏", mapTask != null)
            verifyMapResizeContinuity(folder)
            verifyMapTapAfterRejectedRelease(geometry, folder)
            assertTrue("不经过组件也必须能首次打开酷狗", VirtualLauncher.launchApp(component))
            await {
                !VirtualLauncher.dashboardMode &&
                    VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.HIDDEN &&
                    VirtualLauncher.displayedContent.page == DockDisplayState.Page.APP
            }
            var firstOpen = ""
            var firstPage: String? = null
            val playerDeadline = SystemClock.uptimeMillis() + 15000
            while ((firstPage == null || firstPage.contains("Splash")) && SystemClock.uptimeMillis() < playerDeadline) {
                firstOpen = Shell.exec("dumpsys activity activities").output
                firstPage = MapLaunchResolver.activePage(firstOpen, VirtualLauncher.activeAppDisplayId(), player)
                if (firstPage == null || firstPage.contains("Splash")) SystemClock.sleep(100)
            }
            assertTrue("酷狗须先在测试主屏完成首次配置，不能把声明页算作音乐首页", firstPage != null && !firstPage.contains("Splash"))
            File(folder, "first-icon-tasks.txt").writeText(firstOpen)
            screenshot(capture, folder, "00-first-icon-full-player.png")
            instrumentation.runOnMainSync { VirtualLauncher.showDashboard() }
            await {
                VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.SPLIT &&
                    VirtualLauncher.displayedContent.page == DockDisplayState.Page.DASHBOARD
            }
            var widget: View? = null
            await {
                widget =
                    allViews(checkNotNull(grid).window.decorView).firstOrNull {
                        it.isShown &&
                            it.isClickable &&
                            runCatching { it.resources.getResourceName(it.id) }.getOrNull() == "$player:id/widget_root"
                    }
                widget != null
            }
            screenshot(capture, folder, "01-dashboard.png")
            instrumentation.runOnMainSync { assertTrue(checkNotNull(widget).performClick()) }
            await {
                VirtualLauncher.refreshDisplayedContent()
                !VirtualLauncher.dashboardMode &&
                    VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.HIDDEN &&
                    VirtualLauncher.displayedContent.page == DockDisplayState.Page.APP
            }
            assertEquals("组件页面应保留真实所在屏", VirtualLauncher.gridDisplayId, VirtualLauncher.activeAppDisplayId())
            assertTrue(logs.any { it.contains("官方组件已打开任务") })
            screenshot(capture, folder, "02-widget-full-player.png")
            instrumentation.runOnMainSync { VirtualLauncher.showDashboard() }
            await {
                VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.SPLIT &&
                    VirtualLauncher.displayedContent.page == DockDisplayState.Page.DASHBOARD
            }
            val mapAfter =
                MapLaunchResolver.taskStates(
                    Shell.exec("dumpsys activity activities").output,
                    "",
                    VirtualLauncher.mapDisplayId,
                    map,
                )
            assertTrue("返回仪表盘不能重建导航任务", mapAfter.any { it.taskId == mapTask })
            await {
                allViews(checkNotNull(grid).window.decorView).any {
                    it.isShown &&
                        it.isClickable &&
                        runCatching { it.resources.getResourceName(it.id) }.getOrNull() == "$player:id/widget_root"
                }
            }
            screenshot(capture, folder, "02-return-dashboard.png")
            assertTrue("桌面图标必须能重新打开酷狗", VirtualLauncher.launchApp(component))
            await {
                VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.HIDDEN &&
                    VirtualLauncher.displayedContent.page == DockDisplayState.Page.APP
            }
            val finalTasks = Shell.exec("dumpsys activity activities").output
            assertEquals(player, MapLaunchResolver.activeComponent(finalTasks, VirtualLauncher.activeAppDisplayId())?.substringBefore('/'))
            File(folder, "final-tasks.txt").writeText(finalTasks)
            screenshot(capture, folder, "03-icon-full-player.png")
        } finally {
            val finalDump = Shell.exec("dumpsys activity activities").output
            File(folder, "before-cleanup-tasks.txt").writeText(finalDump)
            logs.add(
                "专项最终观察：页面=${VirtualLauncher.displayedContent} 仪表盘=${VirtualLauncher.dashboardMode} " +
                    "前台=${MapLaunchResolver.activeComponent(finalDump, VirtualLauncher.activeAppDisplayId())} " +
                    "任务=${AppLaunchRecovery.reusableTask(finalDump, player, VirtualLauncher.gridDisplayId, -1)}",
            )
            File(folder, "launcher.log").writeText(logs.joinToString("\n"))
            VirtualLauncher.detach()
            grid?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            DashboardWidgets.remove(context)
            DefaultMapPrefs.set(context, previousMap)
            SupersamplePrefs.setMode(context, component, previousMode)
            VirtualLauncher.logSink = previousLogger
            instrumentation.removeMonitor(monitor)
            output.setOnImageAvailableListener(null, null)
            output.close()
            captureThread.quitSafely()
            runCatching { EmbeddedServiceProvider.shell?.shutdown() }
        }
    }

    /** 笔迹屏不匹配时不全屏，轻点和拖动保持原有处理 */
    private fun verifyMapTapAfterRejectedRelease(
        geometry: DisplayGeometry,
        folder: File,
    ) {
        // Android 16 的尺寸转场快照会挡住触摸，等它退出后再操作
        val transitionDeadline = SystemClock.uptimeMillis() + 15000
        var transitionFinished = false
        while (!transitionFinished && SystemClock.uptimeMillis() < transitionDeadline) {
            val layers = Shell.exec("dumpsys SurfaceFlinger --list", timeoutMs = 2000)
            transitionFinished = layers.ok && !layers.output.contains("transition snapshot: Display{#${VirtualLauncher.mapDisplayId} ")
            if (!transitionFinished) SystemClock.sleep(100)
        }
        assertTrue("地图显示转场遮挡尚未退出，不能开始触控验证", transitionFinished)
        val input = checkNotNull(CarInputClient.service)
        val panels = checkNotNull(VirtualLauncher.floatingPanelLayout())
        val logicalX = VirtualLauncher.dockWidthPx + CARD_MARGIN_PX + VirtualLauncher.mapTileWidth / 2
        val logicalY = CARD_MARGIN_PX + VirtualLauncher.mapTileHeight / 2
        val x = panels.displayX(panels.app, logicalX.toFloat()).roundToInt()
        val y = panels.displayY(panels.app, logicalY.toFloat()).roundToInt()
        TouchRouter.reset()
        TouchRouter.setGeometry(geometry)
        try {
            TouchRouter.onContact(context, TouchReports.Contact(0, x, y, true))
            assertTrue("路由按下必须已进入真实地图屏", input.pointerMove(VirtualLauncher.mapDisplayId, 1, 1))
            assertTrue(input.pointerUp(VirtualLauncher.mapDisplayId, 1, 1))
            // 仅在专项内主动造成两端状态不一致，不把此操作当成真实车机故障来源
            assertTrue(input.pointerDown(VirtualLauncher.gridDisplayId, 0, 0))
            assertFalse("不同屏的收笔必须真实拒绝", input.pointerUp(VirtualLauncher.mapDisplayId, 1, 1))
            TouchRouter.onContact(context, TouchReports.Contact(0, x, y, false))
            SystemClock.sleep(500)
            assertFalse("被拒收笔不能继续触发地图全屏", VirtualLauncher.mapFullscreen)
            assertEquals(DualDisplayComposer.TileLayout.SPLIT, VirtualLauncher.presentedMapLayout())
            assertTrue(input.pointerUp(VirtualLauncher.gridDisplayId, 0, 0))
            TouchRouter.reset()
            TouchRouter.setGeometry(geometry)

            TouchRouter.onContact(context, TouchReports.Contact(0, x, y, true))
            TouchRouter.onContact(context, TouchReports.Contact(0, x + 80, y, true))
            TouchRouter.onContact(context, TouchReports.Contact(0, x + 80, y, false))
            SystemClock.sleep(500)
            assertFalse("地图拖动不能触发全屏", VirtualLauncher.mapFullscreen)

            TouchRouter.onContact(context, TouchReports.Contact(0, x, y, true))
            TouchRouter.onContact(context, TouchReports.Contact(0, x, y, false))
            await { VirtualLauncher.mapFullscreen && VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.FULLSCREEN }
            instrumentation.runOnMainSync { VirtualLauncher.setMapFullscreen(false) }
            await { VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.SPLIT }
            File(folder, "map-touch-result.txt").writeText("真实跨屏收笔拒绝后保持分屏；拖动保持分屏；正常轻点仍可全屏并返回分屏\n")
        } finally {
            TouchRouter.reset()
            runCatching { input.pointerUp(VirtualLauncher.gridDisplayId, 0, 0) }
        }
    }

    /** 三轮切换全屏和分屏，检查显示尺寸与密度不导致导航页面重建 */
    private fun verifyMapResizeContinuity(folder: File) {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val tileDensity =
            MapContentScale.density(
                VirtualLauncher.appAreaWidth,
                VirtualLauncher.appAreaHeight,
                VirtualLauncher.mapTileWidth,
                VirtualLauncher.mapTileHeight,
                DefaultMapPrefs.contentScale(context),
            )
        val resizeResults = StringBuilder()
        repeat(3) { round ->
            for (fullscreen in listOf(true, false)) {
                instrumentation.runOnMainSync { VirtualLauncher.setMapFullscreen(fullscreen) }
                val width = if (fullscreen) VirtualLauncher.appAreaWidth else VirtualLauncher.mapTileWidth
                val height = if (fullscreen) VirtualLauncher.appAreaHeight else VirtualLauncher.mapTileHeight
                val metrics = DisplayMetrics()
                // 检查真实系统配置，不能用合成布局已切换代替显示屏尺寸已生效
                await {
                    @Suppress("DEPRECATION")
                    displays.getDisplay(VirtualLauncher.mapDisplayId).getRealMetrics(metrics)
                    metrics.widthPixels == width && metrics.heightPixels == height
                }
                resizeResults.append(
                    "轮次=${round + 1} 全屏=$fullscreen 实际=${metrics.widthPixels}×${metrics.heightPixels}@${metrics.densityDpi}\n",
                )
                File(folder, "map-resize-result.txt").writeText(resizeResults.toString())
                assertEquals("全屏、小屏往返不能改变密度而触发高德重建", tileDensity, metrics.densityDpi)
            }
        }
        await { VirtualLauncher.presentedMapLayout() == DualDisplayComposer.TileLayout.SPLIT }
    }

    /** 等待页面状态，视图读取放在主线程 */
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(100)
        }
        error("真实虚拟屏页面未在时限内完成切换")
    }

    /** 保存 GL 已提交的车机画布 */
    private fun screenshot(
        capture: AtomicReference<File?>,
        folder: File,
        name: String,
    ) {
        val file = File(folder, name)
        file.delete()
        capture.set(file)
        val deadline = SystemClock.uptimeMillis() + 5000
        while ((!file.exists() || file.length() == 0L || capture.get() != null) &&
            SystemClock.uptimeMillis() < deadline
        ) {
            SystemClock.sleep(50)
        }
        assertTrue("合成输出截图缺失", file.exists() && file.length() > 0L)
    }

    /** 只遍历当前组件视图 */
    private fun allViews(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
}
