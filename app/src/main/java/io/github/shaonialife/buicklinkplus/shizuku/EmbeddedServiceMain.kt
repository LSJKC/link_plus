package io.github.shaonialife.buicklinkplus.shizuku

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 由应用内 ADB 启动的 shell 入口，不初始化手机端 Application
 */
object EmbeddedServiceMain {
    /**
     * 脱离启动通道并向本应用交接服务
     */
    @JvmStatic
    // 独立 app_process 以 shell 身份取得系统上下文，没有 Application 公开入口
    @android.annotation.SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun main(args: Array<String>) {
        check(Process.myUid() == 2000 && args.size == 2) { "内置服务需要 shell 启动" }
        System.err.println("内置服务启动：uid=${Process.myUid()} pid=${Process.myPid()}")
        // app_process 的独立 shell 入口没有 Android 应用主循环，必须显式初始化
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getDeclaredMethod("systemMain").invoke(null)
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(thread) as Context
        val app = system.createPackageContext(args[0], Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val service = ShellService(app)
        System.err.println("内置服务准备交接：package=${app.packageName} uid=${app.applicationInfo.uid}")
        val response = register(app, args[1], service)
        val client = response?.getBinder(EmbeddedServiceProvider.CLIENT)
        if (client == null) {
            System.err.println("内置服务交接被拒绝，本次启动已退出")
            service.stop()
            return
        }
        try {
            service.watchClient(client)
        } catch (_: android.os.RemoteException) {
            service.stop()
            return
        }
        System.err.println("内置服务交接完成")
        Looper.loop()
    }

    /** 使用 shell 可访问的外部 Provider 接口，避免系统 Context 误报包名 */
    // shell 服务通过系统 Provider 接口向同 UID 应用交接 Binder，校验令牌及调用方
    @android.annotation.SuppressLint("PrivateApi")
    private fun register(
        app: Context,
        token: String,
        service: IBinder,
    ): Bundle? {
        val authority = EmbeddedServiceProvider.authority(app)
        val user = app.applicationInfo.uid / 100000
        val api = Class.forName("android.app.IActivityManager")
        val manager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val handle = Binder()
        val holder =
            api
                .getMethod(
                    "getContentProviderExternal",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    IBinder::class.java,
                    String::class.java,
                ).invoke(manager, authority, user, handle, "BuickLinkPlus")
                ?: error("系统未返回本应用的服务入口")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)
            val extras =
                Bundle().apply {
                    putString(EmbeddedServiceProvider.TOKEN, token)
                    putBinder(EmbeddedServiceProvider.SERVICE, service)
                }
            return Class
                .forName("android.content.IContentProvider")
                .getMethod(
                    "call",
                    AttributionSource::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Bundle::class.java,
                ).invoke(
                    provider,
                    AttributionSource.Builder(2000).setPackageName("com.android.shell").build(),
                    authority,
                    EmbeddedServiceProvider.REGISTER,
                    null,
                    extras,
                ) as? Bundle
        } finally {
            api
                .getMethod(
                    "removeContentProviderExternalAsUser",
                    String::class.java,
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ).invoke(manager, authority, handle, user)
        }
    }

    /**
     * 将命令和输入服务限制到实际安装的应用 UID
     */
    private class ShellService(
        private val app: Context,
    ) : IEmbeddedShell.Stub() {
        private val appUid = app.applicationInfo.uid
        private val inputService = CarInputServiceImpl(app, appUid)
        private val children = ConcurrentHashMap.newKeySet<RemoteProcess>()
        private val stopped = AtomicBoolean(false)
        private var clientLifetime: IBinder? = null

        /**
         * 持有客户端 Binder，保证整个服务生命周期持续监听应用退出
         */
        fun watchClient(client: IBinder) {
            clientLifetime = client
            client.linkToDeath({ stop() }, 0)
        }

        /**
         * 所有跨进程事务统一验证调用方
         */
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (Binder.getCallingUid() != appUid) throw SecurityException("拒绝其他应用调用内置 shell 服务")
            val identity = Binder.clearCallingIdentity()
            return try {
                super.onTransact(code, data, reply, flags)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        /**
         * 返回握手协议版本
         */
        override fun protocolVersion(): Int = EmbeddedServiceProvider.PROTOCOL

        /**
         * 返回真实权限身份
         */
        override fun uid(): Int = Process.myUid()

        /**
         * 返回当前服务进程编号
         */
        override fun pid(): Int = Process.myPid()

        /**
         * 返回复用的输入与虚拟屏服务
         */
        override fun input(): ICarInput = inputService

        /**
         * 启动命令并通过独立管道交付输出
         */
        @Synchronized
        override fun execute(command: String): IEmbeddedProcess {
            check(!stopped.get()) { "内置服务已停止" }
            require(command.length <= 65536 && children.size < 32) { "命令过长或并发命令过多" }
            val child = RemoteProcess(ProcessBuilder("/system/bin/setsid", "sh", "-c", command).start(), appUid) { children.remove(it) }
            children.add(child)
            return child
        }

        /**
         * 主动停止与客户端死亡共用清理流程
         */
        override fun shutdown() = stop()

        /**
         * 释放子进程、音频和显示资源后退出
         */
        fun stop() {
            if (!stopped.compareAndSet(false, true)) return
            children.forEach { runCatching { it.destroy() } }
            children.clear()
            try {
                inputService.dispose()
            } finally {
                Process.killProcess(Process.myPid())
            }
        }
    }

    /**
     * 使用文件描述符避免命令输出超过 Binder 上限
     */
    private class RemoteProcess(
        private val child: java.lang.Process,
        private val appUid: Int,
        private val onClosed: (RemoteProcess) -> Unit,
    ) : IEmbeddedProcess.Stub() {
        private val out = ParcelFileDescriptor.createPipe()
        private val err = ParcelFileDescriptor.createPipe()
        private val closed = AtomicBoolean()
        private val childPid =
            runCatching {
                child.javaClass
                    .getDeclaredField("pid")
                    .apply { isAccessible = true }
                    .getInt(child)
            }.getOrDefault(-1)

        init {
            child.outputStream.close()
            Thread({
                runCatching {
                    child.inputStream.use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(out[1]).use { input.copyTo(it) }
                    }
                }
            }, "shell-output").start()
            Thread({
                runCatching {
                    child.errorStream.use { input ->
                        ParcelFileDescriptor.AutoCloseOutputStream(err[1]).use { input.copyTo(it) }
                    }
                }
            }, "shell-error").start()
        }

        /**
         * 子进程接口同样拒绝其他 UID
         */
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (Binder.getCallingUid() != appUid) throw SecurityException("拒绝其他应用访问命令管道")
            return super.onTransact(code, data, reply, flags)
        }

        /**
         * 交付标准输出管道
         */
        override fun stdout(): ParcelFileDescriptor = ParcelFileDescriptor.dup(out[0].fileDescriptor)

        /**
         * 交付标准错误管道
         */
        override fun stderr(): ParcelFileDescriptor = ParcelFileDescriptor.dup(err[0].fileDescriptor)

        /**
         * 有界等待子进程，超时返回专用退出码
         */
        override fun waitFor(timeoutMs: Long): Int =
            if (child.waitFor(timeoutMs.coerceIn(1, 60_000), TimeUnit.MILLISECONDS)) child.exitValue() else -2

        /**
         * 清理进程及服务端持有的管道
         */
        override fun destroy() {
            if (!closed.compareAndSet(false, true)) return
            if (childPid > 0) runCatching { Os.kill(-childPid, android.system.OsConstants.SIGKILL) }
            child.destroyForcibly()
            runCatching { child.inputStream.close() }
            runCatching { child.errorStream.close() }
            out.forEach { runCatching { it.close() } }
            err.forEach { runCatching { it.close() } }
            onClosed(this)
        }
    }
}
