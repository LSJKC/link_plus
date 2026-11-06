package io.github.shaonialife.buicklinkplus.shizuku

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import io.github.shaonialife.buicklinkplus.SettingsContentActivity
import io.github.shaonialife.buicklinkplus.audio.AudioCaptureSupport
import io.github.shaonialife.buicklinkplus.audio.ShellCapture
import io.github.shaonialife.buicklinkplus.input.TouchReports
import io.github.shaonialife.buicklinkplus.input.TouchRouter
import io.github.shaonialife.buicklinkplus.launcher.GridActivity
import io.github.shaonialife.buicklinkplus.launcher.MapStatusActivity
import io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
import io.github.shaonialife.buicklinkplus.render.DisplayGeometry
import io.github.shaonialife.buicklinkplus.render.DualDisplayComposer
import io.github.shaonialife.buicklinkplus.render.PixelArea
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.ui.CarAppearancePrefs
import io.github.shaonialife.buicklinkplus.util.DebugFileLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** 检查息屏后 shell、虚拟屏和触控是否正常 */
class SessionContinuityAndroidTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val folder = File(context.getExternalFilesDir(null), "session-continuity").apply { mkdirs() }

    /** 分别检查自动和手动息屏后的连接状态 */
    @Test
    fun virtualDisplaySurvivesAutomaticAndExplicitMainScreenSleep() =
        withRealShell { input ->
            org.junit.Assume.assumeTrue("独立显示组电源策略从 Android 13 提供，此项不把 Android 12 算作已验证", android.os.Build.VERSION.SDK_INT >= 33)
            val previousTimeout = Shell.exec("settings get system screen_off_timeout").output.trim()
            val previousStayOn = Shell.exec("settings get global stay_on_while_plugged_in").output.trim()
            val reader = ImageReader.newInstance(640, 360, PixelFormat.RGBA_8888, 4)
            var display = -1
            var activity: Activity? = null
            try {
                assertTrue(Shell.exec("input keyevent KEYCODE_WAKEUP").ok)
                assertTrue(Shell.exec("wm dismiss-keyguard").ok)
                assertTrue(Shell.exec("settings put global stay_on_while_plugged_in 0").ok)
                assertTrue(Shell.exec("settings put system screen_off_timeout 15000").ok)
                display = input.createDisplay("CarPlayContinuity", 640, 360, 160, reader.surface, true)
                assertTrue(display > 0)
                activity = launchOnDisplay(display)
                val target = checkNotNull(activity)
                val probe = DrawingProbe(target)
                instrumentation.runOnMainSync { target.setContentView(probe) }
                await("初始画面必须真实绘制", reader) { probe.draws.get() >= 3 }
                var focused = false
                await("初始窗口必须实际取得焦点", reader) {
                    instrumentation.runOnMainSync { focused = target.hasWindowFocus() }
                    focused
                }
                val initialCommit = java.util.concurrent.CountDownLatch(1)
                instrumentation.runOnMainSync {
                    target.window.decorView.viewTreeObserver
                        .registerFrameCommitCallback { initialCommit.countDown() }
                    probe.invalidate()
                }
                await("取得焦点后的窗口必须提交画面", reader) { initialCommit.count == 0L }
                snapshot("00-initial-ready", input)
                assertTrue("窗口就绪后实际点击必须被系统接受", input.tap(display, 320, 180))
                await("初始触摸必须到达窗口", reader) { probe.clicks.get() == 1 }
                // 先形成真实息屏再唤醒，避免设备原来已亮却沿用测试前的过期用户活动时间
                assertTrue(Shell.exec("input keyevent KEYCODE_SLEEP").ok)
                await("重置自动超时前先真正息屏", reader) { mainDisplayState() == Display.STATE_OFF }
                assertTrue(Shell.exec("input keyevent KEYCODE_WAKEUP").ok)
                await("自动超时起点主屏必须亮屏", reader) { mainDisplayState() == Display.STATE_ON }
                Shell.exec("wm dismiss-keyguard")
                val awakeAt = SystemClock.uptimeMillis()
                snapshot("01-awake", input)

                // 对应暂停视频后不再操作，静止画面本身不能刷新主屏用户活动
                instrumentation.runOnMainSync { probe.animating = false }
                await("主屏必须因系统超时真正息屏", reader, 25_000) { mainDisplayState() == Display.STATE_OFF }
                val automaticElapsed = SystemClock.uptimeMillis() - awakeAt
                assertTrue("不能把测试前已过期的超时当作暂停后自动息屏", automaticElapsed >= 10_000)
                snapshot("02-auto-sleep", input)
                val automaticBefore = probe.draws.get()
                instrumentation.runOnMainSync {
                    probe.animating = true
                    probe.invalidate()
                }
                await("自动息屏后应用必须继续提交新画面", reader) { probe.draws.get() >= automaticBefore + 5 }
                assertTrue("自动息屏后注入必须接受", input.tap(display, 320, 180))
                await("自动息屏后真实窗口必须收到点击", reader) { probe.clicks.get() == 2 }
                assertEquals("虚拟屏活动不能点亮手机主屏", Display.STATE_OFF, mainDisplayState())
                snapshot("03-auto-sleep-active", input)

                assertTrue(Shell.exec("input keyevent KEYCODE_WAKEUP").ok)
                await("唤醒手机主屏", reader) { mainDisplayState() == Display.STATE_ON }
                assertTrue(Shell.exec("input keyevent KEYCODE_SLEEP").ok)
                await("手动息屏必须真实生效", reader) { mainDisplayState() == Display.STATE_OFF }
                snapshot("04-explicit-sleep", input)
                val explicitBefore = probe.draws.get()
                await("手动息屏后应用必须继续提交新画面", reader) { probe.draws.get() >= explicitBefore + 5 }
                assertTrue("手动息屏后注入必须接受", input.tap(display, 320, 180))
                await("手动息屏后真实窗口必须收到点击", reader) { probe.clicks.get() == 3 }
                assertEquals(Display.STATE_OFF, mainDisplayState())
                snapshot("05-explicit-sleep-active", input)
                File(folder, "power-result.txt").writeText(
                    "自动息屏及手动息屏通过 自动等待=${automaticElapsed}ms 主屏保持OFF 虚拟屏=$display " +
                        "绘制=${probe.draws.get()} 实际点击=${probe.clicks.get()}\n",
                )
            } finally {
                snapshot("06-finally", input)
                activity?.let { target -> instrumentation.runOnMainSync { target.finish() } }
                if (display > 0) assertTrue(input.releaseDisplay(display))
                reader.close()
                restoreSetting("system", "screen_off_timeout", previousTimeout)
                restoreSetting("global", "stay_on_while_plugged_in", previousStayOn)
                Shell.exec("input keyevent KEYCODE_WAKEUP")
                Shell.exec("wm dismiss-keyguard")
            }
        }

    /** 检查混音与 PCM 管道跨任务、暂停和音频模式切换 */
    @Test
    fun mediaCaptureSurvivesSwitchFocusAndCommunicationMode() =
        withRealShell { input ->
            val routingAvailable = input.capabilities() and 1 != 0
            File(folder, "audio-capability.txt").writeText(
                "SDK=${android.os.Build.VERSION.SDK_INT} 实际媒体路由=$routingAvailable\n" +
                    Shell.exec("dumpsys package com.android.shell").output,
            )
            if (android.os.Build.VERSION.SDK_INT == 31 && !routingAvailable) {
                assertTrue("没有路由权限时生产能力探测必须保留兼容授权路径", !AudioCaptureSupport.refresh())
                org.junit.Assume.assumeTrue("AOSP Android 12 的 shell 未声明 MODIFY_AUDIO_ROUTING，本项不计为兼容授权音频已验", false)
            }
            assertTrue("当前系统必须提供真实媒体路由", routingAvailable)
            val audio = context.getSystemService(AudioManager::class.java)
            val previousMode = audio.mode
            val lines = StringBuilder()
            var player: AudioTrack? = null
            var capture: ShellCapture? = null
            var reader: Thread? = null
            val reading = AtomicBoolean(true)
            val nonZero = AtomicInteger()
            val reads = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val activities = ArrayList<Activity>()
            val focusEvents = java.util.concurrent.ConcurrentLinkedQueue<Int>()
            val realEmulatorCall = InstrumentationRegistry.getArguments().getString("emulatorPhoneCall") == "true"
            val mediaFocus =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener { change ->
                        focusEvents.add(change)
                        if (change == AudioManager.AUDIOFOCUS_GAIN) player?.play() else player?.pause()
                    }.build()
            val navigationFocus =
                AudioFocusRequest
                    .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
                    .setOnAudioFocusChangeListener { }
                    .build()

            fun observe(
                name: String,
                expectSignal: Boolean,
            ) {
                val start = nonZero.get()
                val before = reads.get()
                val deadline = SystemClock.uptimeMillis() + 6_000
                while (reads.get() < before + 12 && failure.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                failure.get()?.let { throw AssertionError("真实 PCM 管道失败", it) }
                assertTrue("必须持续读到实际 PCM", reads.get() >= before + 12)
                val signal = nonZero.get() - start
                lines.appendLine("阶段=$name 读取=${reads.get() - before} 非零块=$signal mode=${audio.mode}")
                File(folder, "audio-result.txt").writeText(lines.toString())
                for (service in listOf("audio", "media.audio_policy", "media.audio_flinger")) {
                    File(folder, "audio-$name-$service.txt").writeText(Shell.exec("dumpsys $service").output)
                }
                assertEquals("阶段=$name 真实 PCM 信号不符合预期", expectSignal, signal > 0)
            }
            try {
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(mediaFocus))
                capture = ShellCapture.open(input, 48000) { reading.get() }
                val activeCapture = checkNotNull(capture)
                reader =
                    Thread({
                        try {
                            val bytes = ByteArray(4096)
                            while (reading.get()) {
                                val count = activeCapture.read(bytes, 0, bytes.size)
                                check(count > 0) { "真实采集管道结束" }
                                if ((0 until count).any { bytes[it] != 0.toByte() }) nonZero.incrementAndGet()
                                reads.incrementAndGet()
                            }
                        } catch (error: Throwable) {
                            if (reading.get()) failure.set(error)
                        }
                    }, "continuity-pcm-probe").apply { start() }
                player = tone(AudioAttributes.USAGE_MEDIA).apply { play() }
                observe("01-media", true)
                for (page in listOf(MapStatusActivity::class.java, SettingsContentActivity::class.java)) {
                    activities += instrumentation.startActivitySync(Intent(context, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    observe("02-switch-${activities.size}", true)
                }
                // singleTop 的返回走 onNewIntent，不能再用只等待新 Activity 的 startActivitySync
                instrumentation.runOnMainSync {
                    context.startActivity(Intent(context, MapStatusActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                val returnAt = SystemClock.uptimeMillis() + 4000
                var returned = false
                while (!returned && SystemClock.uptimeMillis() < returnAt) {
                    instrumentation.runOnMainSync { returned = activities.first().hasWindowFocus() }
                    if (!returned) SystemClock.sleep(25)
                }
                assertTrue("任务返回必须实际取得焦点", returned)
                val serviceLogStart = DebugFileLog.read(context, false).orEmpty().length
                CarPlayService.start(context)
                val serviceAt = SystemClock.uptimeMillis() + 5000
                while (!DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(serviceLogStart)
                        .contains("SVC: 前台服务启动") &&
                    SystemClock.uptimeMillis() < serviceAt
                ) {
                    SystemClock.sleep(25)
                }
                assertTrue(
                    "真实保活服务必须启动",
                    DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(serviceLogStart)
                        .contains("SVC: 前台服务启动"),
                )
                observe("02-switch-return", true)
                player.pause()
                SystemClock.sleep(500)
                observe("03-paused", false)
                player.play()
                observe("04-resumed", true)
                assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(navigationFocus))
                val lostAt = SystemClock.uptimeMillis() + 4000
                while (!focusEvents.contains(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) &&
                    SystemClock.uptimeMillis() < lostAt
                ) {
                    SystemClock.sleep(25)
                }
                assertTrue("系统必须真实派发焦点丢失", focusEvents.contains(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
                SystemClock.sleep(500)
                observe("05-focus-loss", false)
                audio.abandonAudioFocusRequest(navigationFocus)
                val gainedAt = SystemClock.uptimeMillis() + 4000
                while (!focusEvents.contains(AudioManager.AUDIOFOCUS_GAIN) && SystemClock.uptimeMillis() < gainedAt) SystemClock.sleep(25)
                assertTrue("系统必须真实恢复媒体焦点", focusEvents.contains(AudioManager.AUDIOFOCUS_GAIN))
                observe("06-focus-gain", true)
                player.release()
                player = tone(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).apply { play() }
                observe("07-navigation", true)
                player.release()
                player = tone(AudioAttributes.USAGE_VOICE_COMMUNICATION).apply { play() }
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                SystemClock.sleep(500)
                observe("08-communication-excluded", false)
                lines.appendLine("通信设备=${audio.availableCommunicationDevices.map { it.type }} 当前通信设备=${audio.communicationDevice?.type}")
                player.release()
                audio.mode = AudioManager.MODE_NORMAL
                player = tone(AudioAttributes.USAGE_MEDIA).apply { play() }
                observe("09-normal-media", true)
                val modeAt = SystemClock.uptimeMillis() + 4000
                while (!DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(serviceLogStart)
                        .contains("SVC: 系统音频模式变化 mode=0") &&
                    SystemClock.uptimeMillis() < modeAt
                ) {
                    SystemClock.sleep(25)
                }
                val modeLog = DebugFileLog.read(context, false).orEmpty().drop(serviceLogStart)
                assertTrue("服务必须实际收到通信模式变化", modeLog.contains("SVC: 系统音频模式变化 mode=3"))
                assertTrue("服务必须实际收到恢复模式变化", modeLog.contains("SVC: 系统音频模式变化 mode=0"))
                if (realEmulatorCall) {
                    // 外部测试器只操作已核验专用模拟器的虚拟 GSM，经过真实 Telecom 与电话音频策略
                    val control = File(folder, "phone-control.txt")
                    control.writeText("准备来电")
                    val callAt = SystemClock.uptimeMillis() + 20_000
                    while (audio.mode != AudioManager.MODE_IN_CALL && SystemClock.uptimeMillis() < callAt) SystemClock.sleep(50)
                    assertEquals("必须是 Telecom 真实通话模式，不能仅手动设置 mode", AudioManager.MODE_IN_CALL, audio.mode)
                    File(folder, "phone-active-telecom.txt").writeText(Shell.exec("dumpsys telecom").output)
                    SystemClock.sleep(600)
                    observe("10-phone-active", false)
                    control.writeText("准备挂断")
                    val endAt = SystemClock.uptimeMillis() + 15_000
                    while (audio.mode != AudioManager.MODE_NORMAL && SystemClock.uptimeMillis() < endAt) SystemClock.sleep(50)
                    assertEquals(AudioManager.MODE_NORMAL, audio.mode)
                    // 真正的电话焦点恢复可能延后到 Telecom 已回正常模式之后
                    val playbackAt = SystemClock.uptimeMillis() + 5000
                    while (player.playState != AudioTrack.PLAYSTATE_PLAYING &&
                        SystemClock.uptimeMillis() < playbackAt
                    ) {
                        SystemClock.sleep(25)
                    }
                    observe("11-phone-ended", true)
                    control.writeText("结束")
                    lines.appendLine("虚拟 GSM 经真实 Telecom 接通和挂断，未验证 HFP、真实对话或车机扬声器")
                }
                // 只重建真实采集，播放器继续播放，核验路由注销后再次注册
                reading.set(false)
                capture.close()
                reader.join(3000)
                assertTrue("关闭管道必须解除读取阻塞", reader.isAlive != true)
                capture = null
                repeat(3) { generation ->
                    val next = ShellCapture.open(input, 48000) { true }
                    try {
                        val task =
                            java.util.concurrent.Executors
                                .newSingleThreadExecutor()
                        try {
                            assertTrue(
                                "重建后必须采到真实媒体",
                                task
                                    .submit<Boolean> {
                                        val data = ByteArray(4096)
                                        (1..30).any {
                                            val count = next.read(data, 0, data.size)
                                            count > 0 && (0 until count).any { data[it] != 0.toByte() }
                                        }
                                    }.get(5, java.util.concurrent.TimeUnit.SECONDS),
                            )
                        } finally {
                            next.close()
                            task.shutdownNow()
                        }
                    } finally {
                        next.close()
                    }
                    lines.appendLine("采集重建代次=${generation + 1} 非零成功")
                }
                CarPlayService.stop(context)
                val stoppedAt = SystemClock.uptimeMillis() + 5000
                while (!DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(serviceLogStart)
                        .contains("SVC: onDestroy") &&
                    SystemClock.uptimeMillis() < stoppedAt
                ) {
                    SystemClock.sleep(25)
                }
                assertTrue(
                    "真实服务必须停止",
                    DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(serviceLogStart)
                        .contains("SVC: onDestroy"),
                )
                val stoppedLogStart = DebugFileLog.read(context, false).orEmpty().length
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                SystemClock.sleep(250)
                audio.mode = AudioManager.MODE_NORMAL
                SystemClock.sleep(250)
                assertTrue(
                    "服务停止后不能继续记录模式事件",
                    !DebugFileLog
                        .read(context, false)
                        .orEmpty()
                        .drop(stoppedLogStart)
                        .contains("SVC: 系统音频模式变化"),
                )
                lines.appendLine("真实服务监听模式 3→0，销毁后不再记录变化")
                lines.appendLine("焦点事件=$focusEvents 虚拟电话分支=$realEmulatorCall 不含真实运营商对话、微信、蓝牙 HFP 或车机扬声器验收")
                File(folder, "audio-result.txt").writeText(lines.toString())
            } finally {
                CarPlayService.stop(context)
                reading.set(false)
                player?.release()
                capture?.close()
                reader?.join(3000)
                audio.abandonAudioFocusRequest(navigationFocus)
                audio.abandonAudioFocusRequest(mediaFocus)
                audio.mode = previousMode
                activities.distinct().forEach { activity -> instrumentation.runOnMainSync { activity.finish() } }
            }
        }

    /** 通过 AudioTrack 播放循环波形 */
    private fun tone(usage: Int): AudioTrack {
        val data = ShortArray(48000 * 2) { index -> (sin((index / 2) * 2 * Math.PI * 997 / 48000) * 5000).toInt().toShort() }
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(
                        48000,
                    ).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            ).setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
            .also {
                assertEquals(data.size, it.write(data, 0, data.size))
                assertEquals(AudioTrack.SUCCESS, it.setLoopPoints(0, 48000, -1))
            }
    }

    /** 检查别克通告尺寸和安全区缩放后的触摸落点 */
    @Test
    fun realTouchRoutingMatchesBuickSafeAreaAndScaledCanvas() =
        withRealShell { input ->
            val geometry = DisplayGeometry.create(1972, 520, 1480, 390, PixelArea(278, 44, 1686, 520))
            val output = ImageReader.newInstance(1480, 390, PixelFormat.RGBA_8888, 3)
            val outputThread = HandlerThread("continuity-composed-output").apply { start() }
            val frames = AtomicInteger()
            output.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use {
                    frames.incrementAndGet()
                }
            }, Handler(outputThread.looper))
            val oldOutline = CarAppearancePrefs.panelOutlineVisible(context)
            val outlineWasSet = context.getSharedPreferences("settings", 0).contains("panelOutlineVisible")
            val monitor = instrumentation.addMonitor(GridActivity::class.java.name, null, false)
            var grid: Activity? = null
            val received = java.util.concurrent.ConcurrentLinkedQueue<Pair<Float, Float>>()
            val lines = StringBuilder()
            val launcherTrace = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val previousLogger = VirtualLauncher.logSink
            VirtualLauncher.logSink = { message ->
                launcherTrace.add(message)
                previousLogger?.invoke(message)
            }
            try {
                CarAppearancePrefs.setPanelOutlineVisible(context, false)
                assertTrue(VirtualLauncher.attach(context, output.surface, 1480, 390, 315, geometry, 30) { launcherTrace.add(it) })
                grid = checkNotNull(monitor.waitForActivityWithTimeout(10_000))
                val target = checkNotNull(grid)
                instrumentation.runOnMainSync { VirtualLauncher.showDesktop() }
                val visibleAt = SystemClock.uptimeMillis() + 8000
                // 回桌面异步启动任务并等 GL 提交，旧帧数量和初始隐藏地图不代表切屏已完成
                while ((
                        VirtualLauncher.presentedMapLayout() != DualDisplayComposer.TileLayout.HIDDEN ||
                            frames.get() < 5 ||
                            VirtualLauncher.launchInProgress ||
                            VirtualLauncher.activeAppDisplayId() != target.display?.displayId
                    ) &&
                    SystemClock.uptimeMillis() < visibleAt
                ) {
                    SystemClock.sleep(30)
                }
                assertEquals(DualDisplayComposer.TileLayout.HIDDEN, VirtualLauncher.presentedMapLayout())
                assertTrue(frames.get() >= 5)
                assertTrue("回桌面必须完成实际画面提交", !VirtualLauncher.launchInProgress)
                val display = VirtualLauncher.activeAppDisplayId()
                assertEquals("实际窗口必须属于路由目标屏", display, target.display?.displayId)
                val committed = java.util.concurrent.CountDownLatch(1)
                instrumentation.runOnMainSync {
                    target.setContentView(
                        object : View(target) {
                            override fun onDraw(canvas: Canvas) {
                                canvas.drawColor(Color.DKGRAY)
                            }

                            override fun onTouchEvent(event: MotionEvent): Boolean {
                                if (event.actionMasked == MotionEvent.ACTION_UP) received.add(event.x to event.y)
                                return true
                            }
                        },
                    )
                }
                instrumentation.runOnMainSync {
                    target.window.decorView.viewTreeObserver
                        .registerFrameCommitCallback { committed.countDown() }
                    target.window.decorView.invalidate()
                }
                assertTrue("坐标探针窗口必须实际提交绘制", committed.await(4, java.util.concurrent.TimeUnit.SECONDS))
                TouchRouter.reset()
                TouchRouter.setGeometry(geometry)
                // 第一层：直接发送屏内坐标，确认真实注入链路与目标窗口可达
                assertTrue(input.tap(display, 120, 120))
                val directAt = SystemClock.uptimeMillis() + 4000
                while (received.isEmpty() && SystemClock.uptimeMillis() < directAt) SystemClock.sleep(25)
                val direct = checkNotNull(received.poll())
                assertTrue(abs(direct.first - 120f) <= 1 && abs(direct.second - 120f) <= 1)
                lines.appendLine("直接注入链路通过 落点=$direct")
                val dock = VirtualLauncher.dockWidthPx
                // 第二层：输入是协议视图坐标，独立算术给出缩放、安全区及 Dock 扣除后的预期落点
                for (row in 1..3) {
                    for (column in 1..3) {
                        val streamX = geometry.content.left + dock + (geometry.content.width - dock) * column / 4
                        val streamY = geometry.content.top + geometry.content.height * row / 4
                        val viewX = (streamX * 1972.0 / 1480).roundToInt()
                        val viewY = (streamY * 520.0 / 390).roundToInt()
                        val scale = if (display == VirtualLauncher.appHiDisplayId) 1.5 else 1.0
                        val expectedX = kotlin.math.floor((kotlin.math.floor(viewX * 1480.0 / 1972) - geometry.content.left - dock) * scale)
                        val expectedY = kotlin.math.floor((kotlin.math.floor(viewY * 390.0 / 520) - geometry.content.top) * scale)
                        TouchRouter.onContact(context, TouchReports.Contact(0, viewX, viewY, true))
                        TouchRouter.onContact(context, TouchReports.Contact(0, viewX, viewY, false))
                        val deadline = SystemClock.uptimeMillis() + 4000
                        while (received.isEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                        val actual = checkNotNull(received.poll()) { "坐标矩阵未收到真实窗口事件 row=$row column=$column" }
                        lines.appendLine("视图=($viewX,$viewY) 预期=($expectedX,$expectedY) 实际=$actual")
                        assertTrue("X 坐标不符", abs(actual.first - expectedX) <= 1.5)
                        assertTrue("Y 坐标不符", abs(actual.second - expectedY) <= 1.5)
                    }
                }
                lines.appendLine("直接链路=1 全链路矩阵=9 实际合成帧=${frames.get()}")
                // 主动制造服务手势归属冲突，使真实 UP 返回拒绝，不伪称这是小米现场根因
                val conflictX = ((geometry.content.left + dock + 100) * 1972.0 / 1480).roundToInt()
                val conflictY = ((geometry.content.top + 100) * 520.0 / 390).roundToInt()
                TouchRouter.onContact(context, TouchReports.Contact(0, conflictX, conflictY, true))
                assertTrue(input.pointerDown(VirtualLauncher.dockDisplayId, 10, 10))
                TouchRouter.onContact(context, TouchReports.Contact(0, conflictX, conflictY, false))
                assertTrue(input.pointerUp(VirtualLauncher.dockDisplayId, 10, 10))
                // 故障注入跨屏留下原系统指针，测试器显式清理，不能把这一步冒充应用自动恢复
                val cleanup = Shell.exec("input touchscreen -d $display motionevent CANCEL 150 148")
                assertTrue("测试器必须成功取消人为残留指针：${cleanup.output}", cleanup.ok && !cleanup.output.contains("Error"))
                TouchRouter.reset()
                TouchRouter.setGeometry(geometry)
                val diagnosticAt = SystemClock.uptimeMillis() + 30_000
                while (!launcherTrace.any { it.startsWith("INTERACTION-SYSTEM: 类型=input") } &&
                    SystemClock.uptimeMillis() < diagnosticAt
                ) {
                    SystemClock.sleep(25)
                }
                assertTrue("真实拒绝后必须自动留下系统摘要", launcherTrace.any { it.contains("原因=触控注入被拒") })
                assertTrue(launcherTrace.any { it.startsWith("INTERACTION-ROUTE:") && it.contains("当前电源=") && it.contains("实际持锁=") })
                assertTrue(launcherTrace.any { it.startsWith("INTERACTION-SYSTEM: 类型=input") && it.contains("成功=true") })
                repeat(12) { VirtualLauncher.recordInteractionDiagnostics("专项重复触发") }
                SystemClock.sleep(200)
                assertEquals("短时间连续触发必须合并", 1, launcherTrace.count { it.startsWith("INTERACTION:") })
                received.clear()
                TouchRouter.onContact(context, TouchReports.Contact(0, conflictX, conflictY, true))
                TouchRouter.onContact(context, TouchReports.Contact(0, conflictX, conflictY, false))
                val recoveredAt = SystemClock.uptimeMillis() + 4000
                while (received.isEmpty() && SystemClock.uptimeMillis() < recoveredAt) SystemClock.sleep(25)
                assertTrue("诊断不能破坏后续实际点击", received.isNotEmpty())
                lines.appendLine("真实 UP 拒绝自动采集成功 重复触发合并=12 测试器清理人为跨屏指针后实际点击成功")
                File(folder, "touch-result.txt").writeText(lines.toString())
                snapshot("touch-complete", input)
            } finally {
                File(folder, "touch-diagnostics.txt").writeText(launcherTrace.joinToString("\n") + "\n" + lines)
                snapshot("touch-finally", input)
                TouchRouter.reset()
                instrumentation.removeMonitor(monitor)
                grid?.let { target -> instrumentation.runOnMainSync { target.finish() } }
                VirtualLauncher.detach()
                VirtualLauncher.logSink = previousLogger
                CarAppearancePrefs.setPanelOutlineVisible(context, oldOutline)
                if (!outlineWasSet) {
                    context
                        .getSharedPreferences("settings", 0)
                        .edit()
                        .remove("panelOutlineVisible")
                        .commit()
                }
                output.setOnImageAvailableListener(null, null)
                outputThread.quitSafely()
                outputThread.join(3000)
                output.close()
            }
        }

    /** 测试器启动 shell 服务，显示和输入仍走生产 Binder */
    private fun withRealShell(action: (ICarInput) -> Unit) {
        EmbeddedAdb.initialize(context)
        assertTrue("开始时不应残留旧服务", !EmbeddedServiceProvider.isRegistered)
        val token = EmbeddedServiceProvider.issueToken()
        val script = File(folder, "bootstrap.sh")
        try {
            script.writeText(
                "CLASSPATH=${EmbeddedAdb.quote(context.applicationInfo.sourceDir)} /system/bin/setsid " +
                    "/system/bin/app_process /system/bin --nice-name=${EmbeddedAdb.quote(context.packageName + ":car_input")} " +
                    "${EmbeddedServiceMain::class.java.name} ${EmbeddedAdb.quote(context.packageName)} ${EmbeddedAdb.quote(token)} " +
                    "> /data/local/tmp/session-continuity.log 2>&1 < /dev/null &\n",
            )
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand("sh ${script.absolutePath}"),
                ).use { it.readBytes() }
            assertTrue(EmbeddedServiceProvider.awaitBinder(10_000))
            assertTrue(CarInputClient.awaitReady(context, retry = true))
            assertEquals("2000", Shell.exec("id -u").output)
            action(checkNotNull(CarInputClient.service))
        } finally {
            script.delete()
            runCatching { EmbeddedServiceProvider.shell?.shutdown() }
            EmbeddedServiceProvider.cancelPending()
        }
    }

    /** 用 Activity 窗口确认应用进入目标屏 */
    private fun launchOnDisplay(display: Int): Activity {
        val monitor = instrumentation.addMonitor(MapStatusActivity::class.java.name, null, false)
        return try {
            assertTrue(
                Shell
                    .exec(
                        "am start -W --display $display -n ${context.packageName}/${MapStatusActivity::class.java.name} -f 0x18000000",
                    ).ok,
            )
            checkNotNull(monitor.waitForActivityWithTimeout(8000)).also { assertEquals(display, it.display?.displayId) }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** 保留各阶段系统状态，失败时也采集 */
    private fun snapshot(
        name: String,
        input: ICarInput,
    ) {
        File(folder, "$name-service.txt").writeText(input.diag())
        for (service in listOf("power", "display", "window displays", "input")) {
            File(folder, "$name-${service.replace(' ', '-')}.txt").writeText(Shell.exec("dumpsys $service").output)
        }
        File(folder, "$name-shell.txt").writeText(Shell.exec("tail -c 32768 /data/local/tmp/carinput_svc.log").output)
    }

    /** 单独读取手机主屏状态，避免独立显示组影响 interactive 判断 */
    private fun mainDisplayState(): Int = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(0).state

    /** 恢复测试前设置，原来未设置的键直接删除 */
    private fun restoreSetting(
        namespace: String,
        key: String,
        previous: String,
    ) {
        if (previous == "null") {
            Shell.exec("settings delete $namespace $key")
        } else {
            Shell.exec("settings put $namespace $key ${EmbeddedAdb.quote(previous)}")
        }
    }

    /** 及时消费 Surface 缓冲，避免测试产生背压 */
    private fun await(
        message: String,
        reader: ImageReader,
        timeout: Long = 8_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            reader.acquireLatestImage()?.close()
            SystemClock.sleep(30)
        }
        assertTrue(message, condition())
        reader.acquireLatestImage()?.close()
    }

    /** 按窗口绘制和点击计数，测试场景控制动画暂停 */
    private class DrawingProbe(
        context: Context,
    ) : View(context) {
        val draws = AtomicInteger()
        val clicks = AtomicInteger()
        var animating = true
        private val paint =
            Paint().apply {
                color = Color.WHITE
                textSize = 24f
            }

        init {
            setOnClickListener {
                clicks.incrementAndGet()
                invalidate()
            }
        }

        /** 每次提交不同画面，区分应用刷新和编码重复帧 */
        override fun onDraw(canvas: Canvas) {
            val frame = draws.incrementAndGet()
            canvas.drawColor(Color.rgb(frame % 200, 40, 80))
            canvas.drawText("实际帧=$frame 点击=${clicks.get()}", 32f, 120f, paint)
            if (animating) postInvalidateOnAnimation()
        }
    }
}
