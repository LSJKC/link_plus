package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.MainActivity
import io.github.shaonialife.buicklinkplus.input.PointerGesture
import io.github.shaonialife.buicklinkplus.launcher.MapStatusActivity
import io.github.shaonialife.buicklinkplus.legal.DisclaimerPrefs
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.util.LogExporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 检查 shell 服务交接、在线重检和虚拟屏触控 */
class EmbeddedInputRecoveryAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /** 重建两代服务，检查缓存恢复和页面点击 */
    @Test
    fun onlineInputRecoveryAndRestartKeepRealTouchWorking() {
        val prefs = context.getSharedPreferences("embedded_service", 0)
        val paired = prefs.getBoolean("paired", false)
        val folder = File(context.getExternalFilesDir(null), "input-recovery").apply { mkdirs() }
        val results = StringBuilder()
        try {
            prefs.edit().putBoolean("paired", false).commit()
            EmbeddedAdb.initialize(context)
            repeat(2) { generation ->
                assertFalse(EmbeddedServiceProvider.isRegistered)
                val token = EmbeddedServiceProvider.issueToken()
                val script = File(folder, "bootstrap.sh")
                try {
                    script.writeText(
                        "CLASSPATH=${EmbeddedAdb.quote(
                            context.applicationInfo.sourceDir,
                        )} /system/bin/setsid /system/bin/app_process /system/bin " +
                            "--nice-name=${EmbeddedAdb.quote(
                                context.packageName + ":car_input",
                            )} ${EmbeddedServiceMain::class.java.name} " +
                            "${EmbeddedAdb.quote(context.packageName)} ${EmbeddedAdb.quote(token)} " +
                            "> /data/local/tmp/input-recovery.log 2>&1 < /dev/null &\n",
                    )
                    ParcelFileDescriptor
                        .AutoCloseInputStream(
                            instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"),
                        ).use { it.readBytes() }
                } finally {
                    script.delete()
                }
                assertTrue("真实服务未交接", EmbeddedServiceProvider.awaitBinder(10_000))
                val owner = checkNotNull(EmbeddedServiceProvider.shell)
                assertEquals(ShellServiceGate.State.INPUT_UNAVAILABLE, ShellServiceGate.state(context))
                val preparation = ConnectionPreflight.inspect(context, requireAudio = false)
                assertTrue(preparation.checks.first { it.requirement == ConnectionPreflight.Requirement.SHELL_SERVICE }.ready)
                assertFalse(preparation.checks.first { it.requirement == ConnectionPreflight.Requirement.INPUT_SERVICE }.ready)

                // 没有配对记录也必须直接复用在线服务，不应等待无线调试发现
                context.startForegroundService(Intent(context, PairingService::class.java).setAction(PairingService.CONFIGURE))
                await { CarInputClient.ready && !PairingService.running }
                assertEquals(PairingService.Phase.READY, PairingService.snapshot.phase)
                assertSame(owner, EmbeddedServiceProvider.shell)
                assertEquals("2000", Shell.exec("id -u").output)

                if (generation == 0) {
                    // 在线服务不依赖配对记录，打开首页也必须自动恢复输入检查
                    CarInputClient.invalidate(owner)
                    val disclaimer = context.getSharedPreferences("disclaimer", 0)
                    val previousAcceptance = disclaimer.getInt("accepted_version", 0)
                    DisclaimerPrefs.accept(context)
                    val home =
                        instrumentation.startActivitySync(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    try {
                        await { CarInputClient.ready && !PairingService.running }
                        assertSame("首页恢复不能重启在线服务", owner, EmbeddedServiceProvider.shell)
                        assertFalse(EmbeddedAdb.hasPairing)
                        val startup = MainActivity::class.java.getDeclaredField("startupCheckRunning").apply { isAccessible = true }
                        await {
                            var scanning = true
                            instrumentation.runOnMainSync { scanning = startup.getBoolean(home) }
                            !scanning
                        }
                        // 启动扫描之后才产生真实残留，验证实际连接前的补扫，而非依赖启动恰好已清理
                        val residualReader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 2)
                        val unrelatedReader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 2)
                        val input = checkNotNull(CarInputClient.service)
                        var residual = -1
                        var unrelated = -1
                        try {
                            residual = input.createDisplay("CarPlayAppPreflightResidualTest", 640, 360, 160, residualReader.surface, true)
                            unrelated = input.createDisplay("BuickUnrelatedDisplayTest", 640, 360, 160, unrelatedReader.surface, true)
                            assertTrue(residual > 0 && unrelated > 0)
                            val before = Shell.exec("dumpsys display").output
                            File(folder, "preflight-before.txt").writeText(before)
                            // 使用本机真实逻辑显示段验证视口尚未同步的场景，不靠额外等待掩盖漏扫
                            val parse =
                                MainActivity::class.java.getDeclaredMethod("parseCarPlayDisplays", String::class.java).apply {
                                    isAccessible =
                                        true
                                }
                            val withoutViewports = before.lineSequence().filterNot { it.contains("DisplayViewport{") }.joinToString("\n")

                            @Suppress("UNCHECKED_CAST")
                            val parsed = parse.invoke(home, withoutViewports) as List<Pair<Int, String>>
                            assertTrue("没有视口时仍须识别真实逻辑残留屏", parsed.any { it.first == residual })
                            assertFalse("逻辑显示解析不能包含无关屏", parsed.any { it.first == unrelated })
                            val prepare =
                                MainActivity::class.java.getDeclaredMethod("prepareConnectionDisplays").apply {
                                    isAccessible =
                                        true
                                }
                            assertTrue("连接前残留检查未通过", prepare.invoke(home) as Boolean)
                            File(folder, "preflight-after.txt").writeText(Shell.exec("dumpsys display").output)
                            assertTrue("残留屏必须实际释放", input.displayGeometry(residual).isEmpty())
                            assertFalse("不能清理非 CarPlay 屏", input.displayGeometry(unrelated).isEmpty())
                        } finally {
                            if (residual > 0) runCatching { input.releaseDisplay(residual) }
                            if (unrelated > 0) runCatching { input.releaseDisplay(unrelated) }
                            residualReader.close()
                            unrelatedReader.close()
                        }
                    } finally {
                        instrumentation.runOnMainSync { home.finish() }
                        disclaimer.edit().putInt("accepted_version", previousAcceptance).commit()
                    }
                }

                val callers = Executors.newFixedThreadPool(8)
                try {
                    val checks = (1..16).map { callers.submit<Boolean> { CarInputClient.awaitReady(context, retry = true) } }
                    checks.forEach { assertTrue("并发重检不能被队列拒绝", it.get(15, TimeUnit.SECONDS)) }
                } finally {
                    callers.shutdownNow()
                    assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS))
                }
                // 主动失效缓存模拟重新检查入口，不将此操作计为真实 Binder 死亡
                CarInputClient.invalidate(owner)
                assertFalse(CarInputClient.ready)
                EmbeddedAdb.activate(context, "本次不应读取端点", -1)
                assertTrue(CarInputClient.ready)
                assertSame(owner, EmbeddedServiceProvider.shell)
                verifyRealTouch()
                // 使用真实 shell 文件和 MediaStore 导出，不能只验证片段解析函数
                val exported = checkNotNull(LogExporter.export(context, "输入恢复专项"))
                try {
                    val content =
                        context.contentResolver
                            .openInputStream(exported.uri)!!
                            .bufferedReader()
                            .use { it.readText() }
                    File(folder, "input-export-${generation + 1}.txt").writeText(content.substringAfter("SHELL-INPUT 输入诊断"))
                    assertTrue(content.contains("SHELL-INPUT 输入诊断"))
                    assertTrue("必须读取到本代真实 shell 启动标记", content.contains("包=${context.packageName} pid=${owner.pid()}"))
                    assertTrue(content.contains("可能跨日期或投屏会话"))
                    assertFalse(content.contains("未采集："))
                } finally {
                    context.contentResolver.delete(exported.uri, null, null)
                }
                results.appendLine("代次=${generation + 1} UID=2000 在线恢复成功 并发检查=16 两参注入及重复收笔恢复成功 真实点击计数=3")
                if (generation == 0) {
                    // 稳定复现上代只留下半行，新代启动记录仍必须成为可识别的独立行
                    assertTrue(Shell.exec("printf '%s' '启动边界专项残行' >> /data/local/tmp/carinput_svc.log").ok)
                }
                runCatching { owner.shutdown() }
                await { !EmbeddedServiceProvider.isRegistered && !CarInputClient.ready }
            }
            val disconnectedExport = checkNotNull(LogExporter.export(context, "服务退出后的导出专项"))
            try {
                val content =
                    context.contentResolver
                        .openInputStream(disconnectedExport.uri)!!
                        .bufferedReader()
                        .use { it.readText() }
                assertTrue(content.contains("未采集：内置服务未连接，未读取历史 shell 文件"))
            } finally {
                context.contentResolver.delete(disconnectedExport.uri, null, null)
            }
            File(folder, "result.txt").writeText(results.toString())
        } finally {
            context.stopService(Intent(context, PairingService::class.java))
            runCatching { EmbeddedServiceProvider.shell?.shutdown() }
            EmbeddedServiceProvider.cancelPending()
            prefs.edit().putBoolean("paired", paired).commit()
            EmbeddedAdb.initialize(context)
        }
    }

    /** 受信屏需取得焦点并绘制，点击后回读计数 */
    private fun verifyRealTouch() {
        val input = checkNotNull(CarInputClient.service)
        val diagnostics = input.diag()
        assertTrue("必须选择两参注入方法：$diagnostics", diagnostics.contains("injectInputEvent(android.view.InputEvent,int)"))
        assertTrue("必须具备目标屏设置能力：$diagnostics", diagnostics.contains("setDisplayId=true"))
        val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 3)
        var display = -1
        var activity: android.app.Activity? = null
        try {
            display = input.createDisplay("CarPlayInputRecovery", 640, 360, 160, reader.surface, true)
            assertTrue(display > 0)
            val monitor = instrumentation.addMonitor(MapStatusActivity::class.java.name, null, false)
            try {
                assertTrue(
                    Shell
                        .exec(
                            "am start -W --display $display -n ${context.packageName}/${MapStatusActivity::class.java.name} -f 0x18000000",
                        ).ok,
                )
                activity = checkNotNull(monitor.waitForActivityWithTimeout(8000))
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            val target = checkNotNull(activity)
            val clicks = AtomicInteger()
            val committed = CountDownLatch(1)
            lateinit var touchTarget: TextView
            instrumentation.runOnMainSync {
                touchTarget =
                    TextView(target).apply {
                        text = "触控恢复测试"
                        gravity = Gravity.CENTER
                        setBackgroundColor(android.graphics.Color.DKGRAY)
                        setOnClickListener {
                            clicks.incrementAndGet()
                            text = "已收到触控"
                        }
                    }
                target.setContentView(touchTarget)
                target.window.decorView.viewTreeObserver
                    .registerFrameCommitCallback { committed.countDown() }
                target.window.decorView.invalidate()
            }
            var focused = false
            var frame = false
            try {
                await {
                    reader.acquireLatestImage()?.use { if (committed.count == 0L) frame = true }
                    instrumentation.runOnMainSync { focused = target.hasWindowFocus() }
                    frame
                }
            } catch (error: AssertionError) {
                val folder = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
                File(folder, "input-recovery-failed.txt").writeText(
                    "显示=$display 绘制提交=${committed.count == 0L} 取得图像=$frame 焦点=$focused\n" +
                        Shell.exec("dumpsys window displays").output + "\n" + Shell.exec("dumpsys activity activities").output,
                )
                throw AssertionError("显示=$display 绘制提交=${committed.count == 0L} 取得图像=$frame 焦点=$focused", error)
            }
            assertEquals(display, target.display?.displayId)
            assertTrue(input.tap(display, 320, 180))
            // 持续消费输出，并等 View 完成异步收笔，避免上一笔的清除按压回调打断下一笔
            var released = false
            await {
                reader.acquireLatestImage()?.close()
                instrumentation.runOnMainSync { released = !touchTarget.isPressed }
                clicks.get() == 1 && released
            }
            assertEquals(1, clicks.get())
            // 模拟客户端未收到已完成收笔的响应，旧状态不得永久阻塞下一笔真实点击
            val gesture = PointerGesture()
            val point = PointerGesture.Target(display, 320, 180)
            val actions = ArrayList<PointerGesture.Action>()
            val inject: (PointerGesture.Action, PointerGesture.Target) -> Boolean = { action, target ->
                actions += action
                when (action) {
                    PointerGesture.Action.DOWN -> input.pointerDown(target.displayId, target.x, target.y)
                    PointerGesture.Action.MOVE -> input.pointerMove(target.displayId, target.x, target.y)
                    PointerGesture.Action.UP -> input.pointerUp(target.displayId, target.x, target.y)
                }
            }
            assertTrue(gesture.dispatch(true, point, inject))
            assertTrue(input.pointerUp(display, 320, 180))
            await {
                reader.acquireLatestImage()?.close()
                instrumentation.runOnMainSync { released = !touchTarget.isPressed }
                clicks.get() == 2 && released
            }
            gesture.deferRelease()
            assertTrue("重复收笔必须允许客户端恢复下一笔按下", gesture.dispatch(true, point, inject))
            assertTrue(gesture.dispatch(false, point, inject))
            await {
                reader.acquireLatestImage()?.close()
                instrumentation.runOnMainSync { released = !touchTarget.isPressed }
                clicks.get() == 3 && released
            }
            assertEquals(
                listOf(PointerGesture.Action.DOWN, PointerGesture.Action.UP, PointerGesture.Action.DOWN, PointerGesture.Action.UP),
                actions,
            )
            assertTrue(input.pointerUp(display, 320, 180))
            SystemClock.sleep(100)
            assertEquals("重复抬起不能产生额外点击", 3, clicks.get())
            // 旧系统未受信屏不会因程序启动取得焦点，先按用户真实操作发送首笔触摸
            await {
                instrumentation.runOnMainSync { focused = target.hasWindowFocus() }
                focused
            }
            var captured = false
            instrumentation.runOnMainSync { target.window.decorView.invalidate() }
            await {
                reader.acquireLatestImage()?.use { image ->
                    val plane = image.planes.first()
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    val folder = File(context.getExternalFilesDir(null), "test-screenshots").apply { mkdirs() }
                    File(folder, "input-recovery-virtual-display.png").outputStream().use {
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    if (cropped !== padded) cropped.recycle()
                    padded.recycle()
                    captured = true
                }
                captured
            }
        } finally {
            activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
            if (display > 0) assertTrue(input.releaseDisplay(display))
            reader.close()
        }
    }

    /** 异步等待均设置超时，失败时报告断言位置 */
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("真实输入恢复检查超时", condition())
    }
}
