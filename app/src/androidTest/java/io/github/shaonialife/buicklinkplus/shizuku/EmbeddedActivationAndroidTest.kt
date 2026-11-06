package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.MainActivity
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.launcher.MapStatusActivity
import io.github.shaonialife.buicklinkplus.legal.DisclaimerPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** 检查激活入口；配对测试需传入系统配对窗口的参数 */
class EmbeddedActivationAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** 首次配对前打开首页不自动配对，状态保持未激活 */
    @Test
    fun openingHomeWithoutPairingDoesNotStartActivation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedAdb") != "true")
        assumeTrue(!EmbeddedServiceProvider.isRegistered && !PairingService.running)
        val prefs = context.getSharedPreferences("embedded_service", 0)
        val paired = prefs.getBoolean("paired", false)
        val disclaimer = context.getSharedPreferences("disclaimer", 0)
        val previousAcceptance = disclaimer.getInt("accepted_version", 0)
        var activity: android.app.Activity? = null
        try {
            prefs.edit().putBoolean("paired", false).commit()
            EmbeddedAdb.initialize(context)
            DisclaimerPrefs.accept(context)
            val request = PairingService.snapshot.request
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            SystemClock.sleep(1000)
            assertEquals("未配对时首页不能发起新配置", request, PairingService.snapshot.request)
            assertFalse(PairingService.running)
            assertFalse(CarInputClient.ready)
        } finally {
            activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
            prefs.edit().putBoolean("paired", paired).commit()
            disclaimer.edit().putInt("accepted_version", previousAcceptance).commit()
            EmbeddedAdb.initialize(context)
        }
    }

    /** 服务未就绪时拦截连接，首页保留单一主按钮，端点不可编辑 */
    @Test
    fun activationPageDoesNotCreateFakeReadyState() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedAdb") != "true")
        val before = CarInputClient.ready
        val activity =
            instrumentation.startActivitySync(
                Intent(context, ServiceActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val primary = activity.findViewById<TextView>(R.id.activationPrimary)
                assertTrue(primary.isEnabled)
                assertTrue(primary.minimumHeight >= (60 * activity.resources.displayMetrics.density).toInt())
                assertTrue(activity.findViewById<View>(R.id.activationHelp).isEnabled)
                val pending = java.util.ArrayDeque<View>()
                pending.add(activity.window.decorView)
                while (pending.isNotEmpty()) {
                    val view = pending.removeFirst()
                    assertFalse("极简激活页不能出现输入框", view is EditText)
                    if (view is ViewGroup) for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
                }
                assertFalse(activity.findViewById<TextView>(R.id.activationConnectionInfo).isTextSelectable)
                assertEquals(before, CarInputClient.ready)
                val root = activity.window.decorView
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                val folder = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
                File(folder, "embedded-activation.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** 重复开始不叠加请求，等待配对时取消会停止临时服务 */
    @Test
    fun repeatedConfigurationCanBeCancelledWithoutPairing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("embeddedAdb") != "true")
        assumeTrue(!CarInputClient.ready)
        val prefs = context.getSharedPreferences("embedded_service", 0)
        val paired = prefs.getBoolean("paired", false)
        try {
            prefs.edit().putBoolean("paired", false).commit()
            EmbeddedAdb.initialize(context)
            context.startForegroundService(Intent(context, PairingService::class.java).setAction(PairingService.CONFIGURE))
            val deadline = SystemClock.uptimeMillis() + 5000
            while (PairingService.snapshot.phase != PairingService.Phase.WAITING_CODE &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(50)
            }
            assertEquals(PairingService.Phase.WAITING_CODE, PairingService.snapshot.phase)
            val request = PairingService.snapshot.request
            context.startForegroundService(Intent(context, PairingService::class.java).setAction(PairingService.CONFIGURE))
            instrumentation.waitForIdleSync()
            assertEquals(request, PairingService.snapshot.request)
            context.startService(Intent(context, PairingService::class.java).setAction(PairingService.CANCEL))
            val stopDeadline = SystemClock.uptimeMillis() + 5000
            while (PairingService.running && SystemClock.uptimeMillis() < stopDeadline) SystemClock.sleep(50)
            assertFalse(PairingService.running)
            assertFalse(EmbeddedAdb.activating)
            assertEquals(PairingService.Phase.CANCELLED, PairingService.snapshot.phase)
            assertFalse(CarInputClient.ready)
        } finally {
            context.stopService(Intent(context, PairingService::class.java))
            prefs.edit().putBoolean("paired", paired).commit()
            EmbeddedAdb.initialize(context)
        }
    }

    /** 完成 TLS 配对和启动，关闭 ADB 后检查显示、触控及清理 */
    @Test
    fun actualWirelessPairingBootstrapsIndependentService() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("仅在项目专用模拟器的真实无线调试专项执行", arguments.getString("embeddedAdb") == "true")
        val restoreOnAppOpen = arguments.getString("restoreOnAppOpen") == "true"
        var home: android.app.Activity? = null
        var keepHome = false
        val previousAcceptance = DisclaimerPrefs.acceptedVersion(context)
        if (restoreOnAppOpen) {
            EmbeddedAdb.initialize(context)
            assertTrue("专项前须完成真实配对", EmbeddedAdb.hasPairing)
            assertFalse("专项前旧服务必须已退出", EmbeddedServiceProvider.isRegistered)
            val disclaimer = context.getSharedPreferences("disclaimer", 0)
            DisclaimerPrefs.accept(context)
            try {
                home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (arguments.getString("cancelAutomaticRestore") == "true") {
                    val waitingDeadline = SystemClock.uptimeMillis() + 15000
                    while (PairingService.snapshot.phase != PairingService.Phase.WAITING_SYSTEM &&
                        SystemClock.uptimeMillis() < waitingDeadline
                    ) {
                        SystemClock.sleep(50)
                    }
                    assertEquals(PairingService.Phase.WAITING_SYSTEM, PairingService.snapshot.phase)
                    assertEquals(ShellServiceGate.State.ACTIVATING, ShellServiceGate.state(context))
                    assertFalse("系统端点不可用时不能伪造输入就绪", CarInputClient.ready)
                    val request = PairingService.snapshot.request
                    val manager = context.getSystemService(android.app.NotificationManager::class.java)
                    val cancel =
                        manager.activeNotifications
                            .flatMap {
                                it.notification.actions
                                    ?.toList()
                                    .orEmpty()
                            }.single { it.title.toString() == context.getString(R.string.activation_cancel) }
                    cancel.actionIntent.send()
                    val stopDeadline = SystemClock.uptimeMillis() + 5000
                    while (PairingService.running && SystemClock.uptimeMillis() < stopDeadline) SystemClock.sleep(50)
                    assertFalse(PairingService.running)
                    assertEquals(PairingService.Phase.CANCELLED, PairingService.snapshot.phase)
                    instrumentation.runOnMainSync { checkNotNull(home).finish() }
                    home =
                        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    SystemClock.sleep(1000)
                    assertEquals("用户取消后再次进入不能马上自动重启", request, PairingService.snapshot.request)
                    assertFalse(PairingService.running)
                    assertFalse(CarInputClient.ready)
                    assertTrue("取消不能删除已有配对", EmbeddedAdb.hasPairing)
                    File(context.getExternalFilesDir(null), "app-open-restore-cancel.txt").writeText(
                        "无线调试关闭时等待系统=true\n未伪造就绪=true\n取消后再次打开未重启=true\n配对保留=true\n",
                    )
                    return
                }
                val readyDeadline = SystemClock.uptimeMillis() + 60000
                while ((!CarInputClient.ready || PairingService.running) &&
                    SystemClock.uptimeMillis() < readyDeadline
                ) {
                    SystemClock.sleep(100)
                }
                assertTrue("只打开首页后没有自动恢复真实服务", CarInputClient.ready)
                assertEquals(PairingService.Phase.READY, PairingService.snapshot.phase)
                val request = PairingService.snapshot.request
                val owner = EmbeddedServiceProvider.shell
                instrumentation.runOnMainSync {
                    repeat(4) { assertFalse("重复进入不能重启在线服务", PairingService.restoreOnAppOpen(context)) }
                }
                assertEquals(request, PairingService.snapshot.request)
                assertTrue(owner === EmbeddedServiceProvider.shell)
                val root = checkNotNull(home).window.decorView
                val redrawDeadline = SystemClock.uptimeMillis() + 5000
                var readyDrawn = false
                while (!readyDrawn && SystemClock.uptimeMillis() < redrawDeadline) {
                    instrumentation.runOnMainSync {
                        readyDrawn = root.findViewById<TextView>(R.id.shizukuChip).text == "内置服务已就绪" && checkNotNull(home).hasWindowFocus()
                    }
                    if (!readyDrawn) SystemClock.sleep(50)
                }
                assertTrue("首页没有显示实际恢复结果", readyDrawn)
                instrumentation.runOnMainSync {
                    val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(context.getExternalFilesDir(null), "app-open-restore.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                keepHome = true
            } finally {
                if (!keepHome) {
                    disclaimer.edit().putInt("accepted_version", previousAcceptance).commit()
                    home?.let { target -> instrumentation.runOnMainSync { target.finish() } }
                }
            }
        } else if (arguments.getString("notificationPairing") == "true") {
            val code = checkNotNull(arguments.getString("pairingCode"))
            context
                .getSharedPreferences("embedded_service", 0)
                .edit()
                .putBoolean("paired", false)
                .commit()
            EmbeddedAdb.initialize(context)
            context.startForegroundService(Intent(context, PairingService::class.java))
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            val notificationDeadline = SystemClock.uptimeMillis() + 5000
            var action: android.app.Notification.Action? = null
            while (action == null && SystemClock.uptimeMillis() < notificationDeadline) {
                action =
                    manager.activeNotifications
                        .flatMap {
                            it.notification.actions
                                ?.toList()
                                .orEmpty()
                        }.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
                if (action == null) SystemClock.sleep(50)
            }
            val submit = checkNotNull(action) { "未收到真实配对输入通知" }
            val reply = Intent()
            val remoteInput = submit.remoteInputs.first()
            android.app.RemoteInput.addResultsToIntent(
                submit.remoteInputs,
                reply,
                android.os.Bundle().apply {
                    putCharSequence(remoteInput.resultKey, code)
                },
            )
            submit.actionIntent.send(context, 0, reply)
            if (arguments.getString("expectPairingFailure") == "true") {
                // 专项传入故意错误的码，验证真实失败与可导出诊断，不将错误码本身写入报告
                val failureDeadline = SystemClock.uptimeMillis() + 45_000
                while (PairingService.running && SystemClock.uptimeMillis() < failureDeadline) SystemClock.sleep(100)
                assertFalse("配对失败后临时服务必须结束", PairingService.running)
                assertEquals(PairingService.Phase.FAILED, PairingService.snapshot.phase)
                assertFalse(EmbeddedAdb.hasPairing)
                assertFalse(CarInputClient.ready)
                var log = ""
                val logDeadline = SystemClock.uptimeMillis() + 5000
                while (!log.contains("操作失败") && SystemClock.uptimeMillis() < logDeadline) {
                    log =
                        io.github.shaonialife.buicklinkplus.util.DebugFileLog
                            .read(context, currentSessionOnly = true)
                            .orEmpty()
                    SystemClock.sleep(50)
                }
                assertTrue("配对阶段必须进入普通导出日志", log.contains("PAIRING:") && log.contains("PAIRING-NSD:") && log.contains("操作失败"))
                assertFalse("导出诊断不能包含输入的配对码", log.contains(code))
                File(context.getExternalFilesDir(null), "embedded-pairing-failure-result.txt").writeText(
                    "阶段=${PairingService.snapshot.phase} 已配对=${EmbeddedAdb.hasPairing} 输入就绪=${CarInputClient.ready} " +
                        "诊断=${PairingService.snapshot.diagnostic}\n",
                )
                return
            }
            val pairDeadline = SystemClock.uptimeMillis() + 60000
            while ((!CarInputClient.ready || PairingService.running) && SystemClock.uptimeMillis() < pairDeadline) SystemClock.sleep(100)
            assertTrue("通知配对未成功", EmbeddedAdb.hasPairing)
            assertTrue("通知配对后没有自动激活", CarInputClient.ready)
        } else if (arguments.getString("skipPairing") == "true") {
            context.startForegroundService(Intent(context, PairingService::class.java).setAction(PairingService.CONFIGURE))
            val readyDeadline = SystemClock.uptimeMillis() + 60000
            while ((!CarInputClient.ready || PairingService.running) && SystemClock.uptimeMillis() < readyDeadline) SystemClock.sleep(100)
            assertTrue("已有配对没有自动重新激活", CarInputClient.ready)
        } else {
            EmbeddedAdb.pair(
                context,
                checkNotNull(arguments.getString("pairingHost")),
                checkNotNull(arguments.getString("pairingPort")).toInt(),
                checkNotNull(arguments.getString("pairingCode")),
            )
            EmbeddedAdb.activate(
                context,
                checkNotNull(arguments.getString("pairingHost")),
                checkNotNull(arguments.getString("connectPort")).toInt(),
            )
        }
        assertTrue(CarInputClient.ready)
        assertFalse(EmbeddedAdb.activating)
        assertEquals("2000", Shell.exec("id -u").output)
        val input = checkNotNull(CarInputClient.service)
        val clicks = AtomicInteger()
        val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
        // 自动激活完成时系统配对窗口仍可能收尾，先复现用户返回软件再连接车机的真实顺序
        val activationPage =
            home ?: instrumentation.startActivitySync(
                Intent(context, ServiceActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        var displayId = -1
        var activity: android.app.Activity? = null
        try {
            val returnDeadline = SystemClock.uptimeMillis() + 5000
            var returned = false
            while (!returned && SystemClock.uptimeMillis() < returnDeadline) {
                instrumentation.runOnMainSync { returned = activationPage.hasWindowFocus() }
                SystemClock.sleep(50)
            }
            assertTrue("系统配对后未能返回软件", returned)
            displayId = input.createDisplay("CarPlayEmbeddedTest", 640, 360, 160, reader.surface, true)
            assertTrue("未创建真实受信虚拟屏", displayId > 0)
            // 与正式会话一致，由 shell 首次启动到受信屏，普通应用不能替代此权限路径
            val monitor = instrumentation.addMonitor(MapStatusActivity::class.java.name, null, false)
            try {
                val launch =
                    Shell.exec(
                        "am start -W --display $displayId -n ${context.packageName}/${MapStatusActivity::class.java.name} -f 0x18000000",
                    )
                assertTrue("shell 启动虚拟屏页面失败：${launch.output}", launch.ok)
                activity = checkNotNull(monitor.waitForActivityWithTimeout(5000)) { "虚拟屏页面未创建" }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            val target = activity
            val committed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                target.setContentView(
                    TextView(target).apply {
                        text = "等待真实虚拟屏点击"
                        gravity = Gravity.CENTER
                        setBackgroundColor(android.graphics.Color.DKGRAY)
                        setTextColor(android.graphics.Color.WHITE)
                        setOnClickListener {
                            clicks.incrementAndGet()
                            text = "已接收真实点击"
                            setBackgroundColor(android.graphics.Color.BLUE)
                        }
                    },
                )
                target.window.decorView.viewTreeObserver
                    .registerFrameCommitCallback { committed.countDown() }
                target.window.decorView.invalidate()
            }
            val deadline = SystemClock.uptimeMillis() + 8000
            var frame = false
            var focused = false
            while (SystemClock.uptimeMillis() < deadline) {
                // 外观宿主可能调整背景色，使用真实绘制提交回调排除启动预览帧
                reader.acquireLatestImage()?.use { if (committed.count == 0L) frame = true }
                instrumentation.runOnMainSync { focused = target.hasWindowFocus() }
                if (frame && focused) break
                SystemClock.sleep(50)
            }
            assertTrue("虚拟屏没有产生测试页面实际帧", frame)
            assertTrue("虚拟屏页面尚未取得窗口焦点", focused)
            assertEquals(displayId, target.display?.displayId)
            assertTrue("系统未接受触控", input.tap(displayId, 320, 180))
            val tapDeadline = SystemClock.uptimeMillis() + 3000
            while (clicks.get() == 0 && SystemClock.uptimeMillis() < tapDeadline) SystemClock.sleep(50)
            assertTrue("虚拟屏页面未收到真实触控", clicks.get() > 0)
            assertTrue(input.resizeDisplay(displayId, 800, 400, 160))
            var geometry = input.displayGeometry(displayId)
            val resizeDeadline = SystemClock.uptimeMillis() + 3000
            while (geometry.getOrNull(0) != 800 && SystemClock.uptimeMillis() < resizeDeadline) {
                SystemClock.sleep(50)
                geometry = input.displayGeometry(displayId)
            }
            assertEquals(800, geometry[0])
            assertEquals(400, geometry[1])
            assertTrue("服务未报告地图能力", input.capabilities() and 2 != 0)
            repeat(40) { assertEquals("正常", Shell.exec("printf 正常").output) }
            val timeoutStart = SystemClock.elapsedRealtime()
            assertEquals(-2, Shell.exec("sleep 20", 100).exitCode)
            assertTrue("命令超时未及时清理管道", SystemClock.elapsedRealtime() - timeoutStart < 4000)
            assertEquals("已恢复", Shell.exec("printf 已恢复").output)
            val result =
                "shellUid=2000\nwirelessTlsPairing=${arguments.getString(
                    "skipPairing",
                ) != "true" &&
                    !restoreOnAppOpen}\nappOpenRestored=$restoreOnAppOpen\nadbDisconnected=true\ndisplay=$displayId\n" +
                    "realFrame=$frame\ntouchClicks=${clicks.get()}\naudioCapability=${input.capabilities() and 1}\n"
            File(context.getExternalFilesDir(null), "embedded-activation-result.txt").writeText(result)
        } finally {
            activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
            instrumentation.runOnMainSync { activationPage.finish() }
            if (restoreOnAppOpen) {
                context
                    .getSharedPreferences(
                        "disclaimer",
                        0,
                    ).edit()
                    .putInt("accepted_version", previousAcceptance)
                    .commit()
            }
            // 退出专项由外部测试器强制停止应用，核验客户端死亡后服务和虚拟屏均被回收
            if (displayId > 0 && arguments.getString("verifyClientDeath") != "true") assertTrue(input.releaseDisplay(displayId))
            reader.close()
            if (arguments.getString("verifyClientDeath") != "true") {
                runCatching { EmbeddedServiceProvider.shell?.shutdown() }
            }
        }
    }
}
