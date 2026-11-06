package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import java.lang.reflect.Method

/**
 * 运行在 shell 进程的触摸和虚拟屏服务，参考 android-auto CarInputServiceImpl
 * Android 13 及以前使用 InputManager，14 起使用 InputManagerGlobal
 * 只用两参 injectInputEvent，目标屏通过 setDisplayId 指定；能力缺失时不报告就绪
 */
class CarInputServiceImpl(
    private val context: Context,
    private val clientUid: Int = context.applicationInfo.uid,
) : ICarInput.Stub() {
    private val audioCapture by lazy { ShellAudioCapture(context, ::fileLog) }

    /**
     * 输入 Binder 只接受当前安装包的调用
     */
    override fun onTransact(
        code: Int,
        data: android.os.Parcel,
        reply: android.os.Parcel?,
        flags: Int,
    ): Boolean {
        if (android.os.Binder.getCallingUid() != clientUid) throw SecurityException("拒绝其他应用调用输入服务")
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            super.onTransact(code, data, reply, flags)
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * 客户端消失时释放本进程持有的全部资源
     */
    fun dispose() {
        audioCapture.close()
        val ids = synchronized(virtualDisplays) { virtualDisplays.keys.toList() }
        ids.forEach { releaseDisplay(it) }
    }

    /**
     * 新服务显式报告支持的扩展能力，旧服务不会误用新事务
     */
    override fun capabilities(): Int = 2 or (if (audioCapture.available()) 1 else 0)

    /**
     * 采集和管道归属客户端会话，初始化失败不影响虚拟屏
     */
    override fun startAudioCapture(
        session: Long,
        sampleRate: Int,
        client: IBinder,
    ): android.os.ParcelFileDescriptor {
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            audioCapture.start(session, sampleRate, client)
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * 只停止匹配会话的媒体路由
     */
    override fun stopAudioCapture(session: Long) {
        val identity = android.os.Binder.clearCallingIdentity()
        try {
            audioCapture.stop(session)
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /**
     * 迁移原任务，目标必须是手机或当前服务创建的显示屏
     */
    // shell 身份迁移已有任务没有公开替代，先核验源屏及目标屏的会话归属
    @android.annotation.SuppressLint("PrivateApi")
    override fun moveTaskToDisplay(
        taskId: Int,
        sourceDisplayId: Int,
        displayId: Int,
    ): Boolean {
        if (taskId < 0 || sourceDisplayId < 0 || displayId < 0) return false
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            synchronized(virtualDisplays) {
                // 旧会话屏已释放时禁止继续迁移，手机交回也只能进入本服务仍持有的屏
                if ((sourceDisplayId != 0 && !virtualDisplays.containsKey(sourceDisplayId)) ||
                    (displayId != 0 && !virtualDisplays.containsKey(displayId))
                ) {
                    return false
                }
                val api = Class.forName("android.app.IActivityTaskManager")
                val manager = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
                val roots =
                    api
                        .getMethod(
                            "getAllRootTaskInfosOnDisplay",
                            Int::class.javaPrimitiveType,
                        ).invoke(manager, sourceDisplayId) as List<*>
                val root =
                    roots.filterNotNull().firstOrNull { info ->
                        info.javaClass.getField("taskId").getInt(info) == taskId ||
                            (info.javaClass.getField("childTaskIds").get(info) as? IntArray)?.contains(taskId) == true
                    } ?: return false
                val rootId = root.javaClass.getField("taskId").getInt(root)
                val children = root.javaClass.getField("childTaskIds").get(root) as? IntArray
                // 系统接口迁移整个根任务，分屏容器包含其他任务时不能连带迁移
                if (children?.any { it != taskId } == true) {
                    fileLog("地图任务迁移被拒绝：根任务=$rootId 包含其他子任务，请先退出分屏")
                    return false
                }
                api
                    .getMethod(
                        "moveRootTaskToDisplay",
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ).invoke(manager, rootId, displayId)
                true
            }
        } catch (error: Exception) {
            fileLog("地图任务迁移失败：${error.cause?.message ?: error.message}")
            false
        } finally {
            android.os.Binder.restoreCallingIdentity(identity)
        }
    }

    /** 定期检查安装时间，发现覆盖安装后退出旧服务，让系统回收其虚拟屏 */
    private val startupLastUpdateTime: Long =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(-1L)

    init {
        fileLog(
            "service start: pkg=${context.packageName} lastUpdate=$startupLastUpdateTime pid=${android.os.Process.myPid()}",
            startBlock = true,
        )
        Thread({
            while (true) {
                try {
                    Thread.sleep(STALE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now =
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
                    }.getOrDefault(-1L)
                if (startupLastUpdateTime > 0 && now > 0 && now != startupLastUpdateTime) {
                    fileLog(
                        "stale self-reap: lastUpdateTime $startupLastUpdateTime -> $now，" +
                            "exiting pid=${android.os.Process.myPid()}（其持有的虚拟屏随进程回收）",
                    )
                    android.os.Process.killProcess(android.os.Process.myPid())
                    return@Thread
                }
            }
        }, "carinput-stale-check").apply {
            isDaemon = true
            start()
        }
    }

    private class Injector(
        val receiver: Any,
        val method: Method,
    )

    // shell 服务使用系统输入注入接口，公开 SDK 不提供跨屏注入能力
    @Suppress("PrivateApi")
    private val injector: Injector? by lazy {
        val im =
            try {
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            } catch (t: Throwable) {
                Log.d(TAG, "probe getSystemService(INPUT_SERVICE) 异常", t)
                null
            }
        if (im != null) {
            val m = findInjectMethod(im.javaClass.methods)
            if (m != null) return@lazy Injector(im, m)
        }
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val global = checkNotNull(cls.getMethod("getInstance").invoke(null))
            val m = findInjectMethod(cls.methods)
            if (m != null) return@lazy Injector(global, m)
        } catch (t: Throwable) {
            Log.d(TAG, "probe InputManagerGlobal 异常", t)
        }
        null
    }

    /**
     * 固定选择官方两参接口，避免反射顺序选中限制目标 UID 的重载
     */
    private fun findInjectMethod(methods: Array<Method>): Method? =
        methods.firstOrNull {
            it.name == "injectInputEvent" &&
                it.returnType == java.lang.Boolean.TYPE &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == InputEvent::class.java &&
                it.parameterTypes[1] == Integer.TYPE
        }

    private val setDisplayIdMethod: Method? by lazy {
        (InputEvent::class.java.methods + MotionEvent::class.java.methods)
            .firstOrNull { it.name == "setDisplayId" && it.parameterTypes.size == 1 }
    }

    private var injectFailLogged = false

    private fun inject(
        event: MotionEvent,
        displayId: Int,
    ): Boolean {
        val inj = injector
        val setDisplay = setDisplayIdMethod
        if (inj == null || setDisplay == null) {
            event.recycle()
            return false
        }
        return try {
            setDisplay.invoke(event, displayId)
            val m = inj.method
            // 等待系统选定目标及接受事件，不等待应用处理结束，避免卡住后续触摸
            val waitForResult = 1
            val result = m.invoke(inj.receiver, event, waitForResult) as Boolean
            if (!result && !injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "injectInputEvent 返回 false method=$m")
                fileLog("输入注入被拒 display=$displayId action=${event.actionMasked} method=$m")
            }
            result
        } catch (t: Throwable) {
            if (!injectFailLogged) {
                injectFailLogged = true
                Log.d(TAG, "inject 异常 method=${inj.method}", t)
                fileLog("输入注入异常 display=$displayId action=${event.actionMasked} method=${inj.method} 原因=${t.cause ?: t}")
            }
            false
        } finally {
            event.recycle()
        }
    }

    @Suppress("DEPRECATION")
    // 返回事件的所有权转交 inject，其失败及 finally 路径均回收 MotionEvent
    @android.annotation.SuppressLint("Recycle")
    private fun build(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent? =
        try {
            val e =
                MotionEvent.obtain(
                    downTime,
                    eventTime,
                    action,
                    x,
                    y,
                    1f,
                    1f,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                )
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN)
            e
        } catch (t: Throwable) {
            Log.d(TAG, "build MotionEvent 异常", t)
            null
        }

    override fun tap(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val up = build(now, now + 50, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
        return inject(up, displayId)
    }

    override fun swipe(
        displayId: Int,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val duration = durationMs.coerceIn(50, 1500)
        val down = build(now, now, MotionEvent.ACTION_DOWN, startX.toFloat(), startY.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        val steps = 8
        for (i in 1 until steps) {
            val t = duration * i / steps
            val ix = startX + (endX - startX) * i / steps
            val iy = startY + (endY - startY) * i / steps
            val move = build(now, now + t, MotionEvent.ACTION_MOVE, ix.toFloat(), iy.toFloat()) ?: return false
            if (!inject(move, displayId)) return false
        }
        val up = build(now, now + duration, MotionEvent.ACTION_UP, endX.toFloat(), endY.toFloat()) ?: return false
        return inject(up, displayId)
    }

    private var dragDownTime = 0L
    private var dragDisplay = -1

    @Synchronized
    override fun pointerDown(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = build(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()) ?: return false
        if (!inject(down, displayId)) return false
        // 系统拒绝按下时不能留下伪活动手势，否则后续 MOVE 会持续失效
        dragDownTime = now
        dragDisplay = displayId
        return true
    }

    @Synchronized
    override fun pointerMove(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay != displayId) return false
        val now = SystemClock.uptimeMillis()
        val move = build(dragDownTime, now, MotionEvent.ACTION_MOVE, x.toFloat(), y.toFloat()) ?: return false
        return inject(move, displayId)
    }

    /** 收笔失败时清理本地手势状态，允许重复确认 */
    @Synchronized
    override fun pointerUp(
        displayId: Int,
        x: Int,
        y: Int,
    ): Boolean {
        if (dragDisplay < 0) return true
        if (dragDisplay != displayId) return false
        return try {
            val now = SystemClock.uptimeMillis()
            val up = build(dragDownTime, now, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()) ?: return false
            val accepted = inject(up, displayId)
            if (!accepted) {
                // 尽力取消系统中的旧手势，不把取消结果当成点击成功
                build(dragDownTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x.toFloat(), y.toFloat())
                    ?.let { inject(it, displayId) }
            }
            accepted
        } finally {
            dragDisplay = -1
            dragDownTime = 0L
        }
    }

    // ---- 受信虚拟屏 ----

    private val virtualDisplays = HashMap<Int, VirtualDisplay>()
    private val displayWakeLocks = HashMap<Int, PowerManager.WakeLock>()
    private val displayDiagnostics = HashMap<Int, String>()

    /**
     * 只维持独立虚拟显示组的亮屏状态，手机主屏仍可正常锁屏和息屏
     */
    @Suppress("DEPRECATION")
    // 显示锁转交 displayWakeLocks，释放屏幕及服务退出时释放；固定超时会中断长会话
    @android.annotation.SuppressLint("PrivateApi", "Wakelock", "WakelockTimeout")
    private fun keepDisplayAwake(
        display: Display,
        shellContext: Context,
    ): PowerManager.WakeLock? {
        if (Build.VERSION.SDK_INT < 33) return null
        var wakeLock: PowerManager.WakeLock? = null
        return try {
            val infoClass = Class.forName("android.view.DisplayInfo")
            val info = infoClass.getConstructor().newInstance()
            check(Display::class.java.getMethod("getDisplayInfo", infoClass).invoke(display, info) == true)
            val groupId = infoClass.getField("displayGroupId").getInt(info)
            check(groupId > 0) { "虚拟屏没有独立显示组，跳过显示唤醒锁" }
            val manager = shellContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val acquiredLock =
                PowerManager::class.java
                    .getMethod("newWakeLock", Integer.TYPE, String::class.java, Integer.TYPE)
                    .invoke(manager, PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Buick Link＋:虚拟屏:${display.displayId}", display.displayId)
                    as PowerManager.WakeLock
            wakeLock = acquiredLock
            acquiredLock.setReferenceCounted(false)
            acquiredLock.acquire()
            fileLog("虚拟屏电源 id=${display.displayId} group=$groupId 持锁=${acquiredLock.isHeld}")
            acquiredLock
        } catch (error: Exception) {
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            fileLog("虚拟屏独立电源不可用 id=${display.displayId}：${error.cause ?: error}")
            null
        }
    }

    /**
     * 地图屏优先使用显示级尺寸兼容配置，避免竖屏限制形成黑带
     */
    private fun createVirtualDisplay(
        manager: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay? {
        if (name == "CarPlayMap" && flags and FLAG_TRUSTED != 0) {
            runCatching {
                val configClass = Class.forName("android.hardware.display.VirtualDisplayConfig")
                val builderClass = Class.forName("android.hardware.display.VirtualDisplayConfig\$Builder")
                val builder =
                    builderClass
                        .getConstructor(String::class.java, Integer.TYPE, Integer.TYPE, Integer.TYPE)
                        .newInstance(name, width, height, dpi)
                builderClass.getMethod("setFlags", Integer.TYPE).invoke(builder, flags)
                builderClass.getMethod("setSurface", Surface::class.java).invoke(builder, surface)
                builderClass.getMethod("setIgnoreActivitySizeRestrictions", java.lang.Boolean.TYPE).invoke(builder, true)
                val config = builderClass.getMethod("build").invoke(builder)
                check(configClass.getMethod("isIgnoreActivitySizeRestrictions").invoke(config) == true)
                val display =
                    DisplayManager::class.java
                        .getMethod("createVirtualDisplay", configClass, android.os.Handler::class.java, VirtualDisplay.Callback::class.java)
                        .invoke(manager, config, null, null) as? VirtualDisplay
                if (display != null) {
                    fileLog("地图屏已申请显示级尺寸兼容 id=${display.display.displayId}，实际窗口仍需核对")
                    return display
                }
            }.onFailure { fileLog("地图屏尺寸兼容不可用，使用标准显示配置：${it.cause ?: it}") }
        }
        return manager.createVirtualDisplay(name, width, height, dpi, surface, flags, null, null)
    }

    /**
     * 通过窗口服务设置方向策略，兼容缺少对应 shell 子命令的系统
     */
    override fun setDisplayIgnoreOrientation(
        displayId: Int,
        enabled: Boolean,
    ): Boolean {
        if (synchronized(virtualDisplays) { !virtualDisplays.containsKey(displayId) }) return false
        return runCatching {
            val proxy = windowManagerProxy ?: return false
            val result =
                proxy.javaClass
                    .getMethod("setIgnoreOrientationRequest", Integer.TYPE, java.lang.Boolean.TYPE)
                    .invoke(proxy, displayId, enabled)
            val accepted = result !is Boolean || result
            fileLog("虚拟屏方向策略 id=$displayId 目标=$enabled 调用接受=$accepted，实际状态需独立回读")
            accepted
        }.onFailure { fileLog("虚拟屏方向策略失败 id=$displayId：${it.cause ?: it}") }.getOrDefault(false)
    }

    // ---- IME 展示策略 ----

    /**
     * 通过公开显示接口读取逻辑尺寸，方向策略被系统拒绝时仍可进行坐标换算
     */
    @Suppress("DEPRECATION")
    override fun displayGeometry(displayId: Int): IntArray {
        if (synchronized(virtualDisplays) { !virtualDisplays.containsKey(displayId) }) return intArrayOf()
        return runCatching {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = manager.getDisplay(displayId) ?: return intArrayOf()
            val point = android.graphics.Point()
            display.getRealSize(point)
            intArrayOf(point.x, point.y, display.rotation)
        }.getOrDefault(intArrayOf())
    }

    /**
     * 受信屏的 IME 设为 LOCAL，使键盘显示在虚拟屏
     * 枚举值运行时反射读取，失败回退 AOSP 值 0 并记日志
     */
    private val imePolicyLocal: Int by lazy {
        val value =
            try {
                Class
                    .forName("android.view.WindowManager")
                    .getField("DISPLAY_IME_POLICY_LOCAL")
                    .getInt(null)
            } catch (t: Throwable) {
                fileLog("DISPLAY_IME_POLICY_LOCAL 反射失败，回退 AOSP 值 0：$t")
                0
            }
        fileLog("DISPLAY_IME_POLICY_LOCAL=$value")
        value
    }

    /** 直接获取窗口服务，避开不兼容的 WindowManagerGlobal */
    // 受信虚拟屏的本地输入法策略只能经 shell 系统接口设置
    @Suppress("PrivateApi")
    private val windowManagerProxy: Any? by lazy {
        try {
            val binder =
                Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "window")
            Class
                .forName("android.view.IWindowManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (t: Throwable) {
            fileLog("IWindowManager 反射失败（IME 策略将不生效）：$t")
            null
        }
    }

    /** 把受信虚拟屏的 IME 策略设为 LOCAL，失败时保留主链路 */
    private fun applyImePolicyLocal(displayId: Int) {
        val proxy = windowManagerProxy ?: return
        try {
            proxy.javaClass
                .getMethod("setDisplayImePolicy", Integer.TYPE, Integer.TYPE)
                .invoke(proxy, displayId, imePolicyLocal)
            val readBack =
                runCatching {
                    proxy.javaClass
                        .getMethod("getDisplayImePolicy", Integer.TYPE)
                        .invoke(proxy, displayId)
                }.getOrNull()
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) ok, 读回=$readBack")
        } catch (t: Throwable) {
            fileLog("setDisplayImePolicy($displayId, LOCAL=$imePolicyLocal) 失败：$t")
        }
    }

    /** 将 shell 日志写入 /data/local/tmp，供 logcat 不可读的设备排查 */
    private fun fileLog(
        message: String,
        startBlock: Boolean = false,
    ) {
        try {
            java.io.FileWriter("/data/local/tmp/carinput_svc.log", true).use { w ->
                // 上代进程被终止可能只留下半行，新启动标记必须有独立的行边界
                if (startBlock) w.append('\n')
                w
                    .append(
                        android.text.format.DateFormat
                            .format("HH:mm:ss.SSS", System.currentTimeMillis())
                            .toString(),
                    ).append(' ')
                    .append(message)
                    .append('\n')
            }
        } catch (_: Throwable) {
        }
    }

    override fun createDisplay(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        trusted: Boolean,
    ): Int {
        var createdDisplay: VirtualDisplay? = null
        var createdDisplayId = -1
        var wakeLock: PowerManager.WakeLock? = null
        return try {
            fileLog("createDisplay $name ${width}x$height@$dpi start")
            // 关键：必须用「shell 包的 Context」——系统检查 packageName 与调用 uid(2000) 匹配，
            // 用本 app 的 Context 会报 SecurityException('packageName must match the owner uid')
            val shellContext =
                try {
                    context.createPackageContext("com.android.shell", 0)
                } catch (t: Throwable) {
                    fileLog("createPackageContext(com.android.shell) 失败：$t，回退本 app context")
                    context
                }
            fileLog("ctx package=${shellContext.packageName}")
            val dm = shellContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            // SUPPORTS_TOUCH 让应用按触屏布局显示
            // 受信应用屏还要声明 PUBLIC，允许第三方输入法窗口进入
            // OWN_CONTENT_ONLY 阻止自动镜像；Dock 保持私有，受信标记按系统能力设置
            val canTrust =
                android.os.Process.myUid() == 0 ||
                    shellContext.checkSelfPermission("android.permission.ADD_TRUSTED_DISPLAY") == PackageManager.PERMISSION_GRANTED
            val independentLock =
                Build.VERSION.SDK_INT >= 33 &&
                    canTrust &&
                    (
                        android.os.Process.myUid() == 0 ||
                            shellContext.checkSelfPermission(
                                "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY",
                            ) == PackageManager.PERMISSION_GRANTED
                    )
            val baseFlags =
                (if (trusted) DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    (if (trusted && canTrust) FLAG_TRUSTED else 0) or
                    FLAG_SUPPORTS_TOUCH or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val flags =
                (if (trusted) DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC else 0) or
                    (if (canTrust && (trusted || independentLock)) FLAG_TRUSTED else 0) or
                    (if (independentLock) FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED else 0) or
                    (
                        if (Build.VERSION.SDK_INT >= 34 && canTrust) {
                            FLAG_TRUSTED or FLAG_OWN_FOCUS or (if (!trusted) FLAG_STEAL_TOP_FOCUS_DISABLED else 0)
                        } else {
                            0
                        }
                    ) or
                    FLAG_SUPPORTS_TOUCH or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            fileLog("flags=0x${flags.toString(16)} 独立锁屏策略=$independentLock 公开屏=$trusted")
            val vd =
                try {
                    createVirtualDisplay(dm, name, width, height, dpi, surface, flags)
                        ?: if (flags != baseFlags) createVirtualDisplay(dm, name, width, height, dpi, surface, baseFlags) else null
                } catch (error: Exception) {
                    if (flags == baseFlags) throw error
                    fileLog("虚拟屏独立策略被系统拒绝，保留基础显示能力：${error.cause ?: error}")
                    createVirtualDisplay(dm, name, width, height, dpi, surface, baseFlags)
                }
            createdDisplay = vd
            val id = vd?.display?.displayId ?: -1
            createdDisplayId = id
            fileLog("createVirtualDisplay -> vd=$vd id=$id")
            if (vd == null || id < 0) {
                runCatching { vd?.release() }
                Log.w(TAG, "createDisplay $name -> null")
                return -1
            }
            if (independentLock) wakeLock = keepDisplayAwake(vd.display, shellContext)
            val actualUnlocked =
                runCatching {
                    val flag = Display::class.java.getField("FLAG_ALWAYS_UNLOCKED").getInt(null)
                    vd.display.flags and flag != 0
                }.getOrNull()
            synchronized(virtualDisplays) {
                virtualDisplays[id] = vd
                wakeLock?.let { displayWakeLocks[id] = it }
                displayDiagnostics[id] = "$name#$id 独立解锁=${actualUnlocked ?: "未知"} 创建持锁=${wakeLock?.isHeld == true}"
            }
            // 只有受信屏能设 IME 策略（WMS 对非受信屏抛 SecurityException），且正需要它：
            // dock 屏只承载自有 DockActivity，无输入场景
            if (trusted && canTrust) applyImePolicyLocal(id)
            Log.i(TAG, "createDisplay $name id=$id size=${width}x$height@$dpi flags=0x${flags.toString(16)}")
            id
        } catch (t: Throwable) {
            runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            runCatching { createdDisplay?.release() }
            if (createdDisplayId >= 0) {
                synchronized(virtualDisplays) {
                    virtualDisplays.remove(createdDisplayId)
                    displayWakeLocks.remove(createdDisplayId)
                    displayDiagnostics.remove(createdDisplayId)
                }
            }
            fileLog("createDisplay $name failed: $t")
            Log.w(TAG, "createDisplay $name failed: $t")
            -1
        }
    }

    override fun resizeDisplay(
        displayId: Int,
        width: Int,
        height: Int,
        dpi: Int,
    ): Boolean {
        val vd = synchronized(virtualDisplays) { virtualDisplays[displayId] } ?: return false
        return try {
            vd.resize(width, height, dpi)
            Log.i(TAG, "resizeDisplay id=$displayId -> ${width}x$height@$dpi")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "resizeDisplay $displayId failed: $t")
            false
        }
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val (vd, wakeLock) =
            synchronized(virtualDisplays) {
                displayDiagnostics.remove(displayId)
                virtualDisplays.remove(displayId) to displayWakeLocks.remove(displayId)
            }
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
            .onFailure { fileLog("虚拟屏显示唤醒锁释放失败 id=$displayId：$it") }
        if (vd == null) {
            // 只有创建进程能释放虚拟屏，其他旧进程由 CarInputClient 清理
            fileLog("releaseDisplay id=$displayId -> 非本进程持有（跨进程无法释放）")
            return false
        }
        return try {
            runCatching {
                windowManagerProxy?.let { proxy ->
                    proxy.javaClass
                        .getMethod("setIgnoreOrientationRequest", Integer.TYPE, java.lang.Boolean.TYPE)
                        .invoke(proxy, displayId, false)
                }
            }
            vd.release()
            Log.i(TAG, "releaseDisplay id=$displayId")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "releaseDisplay $displayId failed: $t")
            false
        }
    }

    /**
     * 注入接口与目标屏设置能力均存在时才通过预检
     */
    override fun ping(): Boolean = injector != null && setDisplayIdMethod != null

    override fun diag(): String =
        "injector=" + (injector?.let { "${it.receiver.javaClass.name} ${it.method}" } ?: "null") +
            " setDisplayId=${setDisplayIdMethod != null}" +
            synchronized(virtualDisplays) {
                " 虚拟屏=[" +
                    virtualDisplays.entries.joinToString("；") { (id, display) ->
                        "${displayDiagnostics[id]} 当前电源=${display.display.state} 实际持锁=${displayWakeLocks[id]?.isHeld == true}"
                    } + "]"
            }

    override fun pid(): Int = android.os.Process.myPid()

    companion object {
        private const val TAG = "CarInputSvc"

        /** 每 15 秒检查安装版本，覆盖安装后退出旧进程 */
        private const val STALE_CHECK_INTERVAL_MS = 15_000L

        /** 受信虚拟屏标志，AOSP 隐藏常量值为 1 << 10 */
        private const val FLAG_TRUSTED = 1 shl 10

        /** 虚拟屏支持触摸标志，AOSP 隐藏常量值为 1 << 6 */
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6

        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
