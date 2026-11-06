package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Context
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.util.Bg
import java.io.ByteArrayOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 内置服务状态只读取已完成后台检查的快照
 */
object ShellServiceGate {
    /** 分别展示权限服务在线和输入就绪状态 */
    enum class State { NEEDS_PAIRING, STOPPED, ACTIVATING, INPUT_UNAVAILABLE, READY }

    /**
     * 不在界面线程发起 Binder 调用
     */
    fun state(context: Context): State =
        when {
            CarInputClient.ready -> State.READY
            PairingService.running || EmbeddedAdb.activating -> State.ACTIVATING
            EmbeddedServiceProvider.isRegistered -> State.INPUT_UNAVAILABLE
            EmbeddedAdb.hasPairing -> State.STOPPED
            else -> State.NEEDS_PAIRING
        }

    /**
     * 根据状态提供可执行的手机侧指引
     */
    internal fun guidanceOf(state: State): ShellServiceGuidance =
        when (state) {
            State.NEEDS_PAIRING -> ShellServiceGuidance("需要首次配对", "请开启开发者选项和无线调试，在本软件完成配对后激活服务。未激活时连接会失败。无需安装其他应用。", "激活服务")
            State.STOPPED ->
                ShellServiceGuidance(
                    "内置服务未运行",
                    "打开软件后会尝试自动重新启动服务，已有配对可继续使用。若尚未恢复，请确认已连接 WLAN 并开启无线调试；若系统撤销授权则需重新配对。未激活时连接会失败。",
                    "查看恢复方式",
                )
            State.ACTIVATING -> ShellServiceGuidance("内置服务正在激活", "请等待当前配对或激活完成，也可在激活页面取消。未激活时连接会失败。", "查看激活进度")
            State.INPUT_UNAVAILABLE -> ShellServiceGuidance("触控服务待检查", "权限服务仍在线，请点击重新检查。无需重新配对；检查通过后才能连接车机。", "重新检查")
            State.READY -> ShellServiceGuidance("内置服务已就绪", "", null)
        }
}

/**
 * 手机侧服务状态说明
 */
internal data class ShellServiceGuidance(
    /**
     * 当前状态标题
     */
    val title: String,
    /**
     * 当前状态的操作说明
     */
    val message: String,
    /**
     * 可执行的主操作标题
     */
    val primaryLabel: String?,
)

/**
 * 通过自有 Binder 服务执行命令，正常会话不依赖 ADB 连接
 */
object Shell {
    /** 保留业务层使用的命令结果格式 */
    class Result(
        val exitCode: Int,
        val output: String,
    ) {
        val ok: Boolean get() = exitCode == 0
    }

    @Volatile
    var mainThreadViolations: Int = 0
        private set

    @Volatile
    var violationSink: ((String) -> Unit)? = null

    /**
     * 并行消费输出并由服务端执行有界等待
     */
    fun exec(
        cmd: String,
        timeoutMs: Long = 6000L,
    ): Result {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mainThreadViolations++
            violationSink?.invoke("SHELL: 主线程调用 shell 已阻止，累计 $mainThreadViolations 次")
            return Result(-1, "shell 命令不能阻塞界面线程")
        }
        val service = EmbeddedServiceProvider.shell ?: return Result(-1, "内置服务未连接")
        var process: IEmbeddedProcess? = null
        var stdout: ParcelFileDescriptor? = null
        var stderr: ParcelFileDescriptor? = null
        try {
            process = service.execute(cmd)
            stdout = process.stdout()
            stderr = process.stderr()
            val output = ByteArrayOutputStream()
            val error = ByteArrayOutputStream()
            val outTask = FutureTask { ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.copyTo(output) } }
            val errTask = FutureTask { ParcelFileDescriptor.AutoCloseInputStream(stderr).use { it.copyTo(error) } }
            Bg.run("shell-output-read") { outTask.run() }
            Bg.run("shell-error-read") { errTask.run() }
            val exit = process.waitFor(timeoutMs)
            if (exit == -2) process.destroy()
            outTask.get(2, TimeUnit.SECONDS)
            errTask.get(2, TimeUnit.SECONDS)
            val text = output.toString(Charsets.UTF_8.name()).trim()
            val failure = error.toString(Charsets.UTF_8.name()).trim()
            return Result(exit, listOf(text, failure).filter { it.isNotEmpty() }.joinToString("\n"))
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return Result(-1, "命令执行失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { process?.destroy() }
            runCatching { stdout?.close() }
            runCatching { stderr?.close() }
        }
    }
}

/**
 * 内置服务的输入 Binder 绑定与有界后台探活
 */
object CarInputClient {
    private val health =
        InputServiceHealth<IEmbeddedShell, ICarInput>(
            currentOwner = { EmbeddedServiceProvider.shell },
            inputAlive = { it.asBinder().isBinderAlive },
            probe = { candidate ->
                check(candidate.uid() == 2000) { "内置服务身份异常" }
                check(candidate.protocolVersion() == EmbeddedServiceProvider.PROTOCOL) { "内置服务版本不兼容" }
                candidate.input().also { check(it.ping()) { "系统输入能力检查未通过" } }
            },
            log = { logSink?.invoke(it) },
        )

    val service: ICarInput? get() = health.service

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var logSink: ((String) -> Unit)? = null

    val ready: Boolean get() = service != null

    /**
     * 界面只触发后台任务，不阻塞等待输入服务
     */
    fun ensure(context: Context) {
        appContext = context.applicationContext
        health.ensure()
    }

    /** 检查 UID、协议和输入能力后再发布就绪 */
    fun awaitReady(
        context: Context,
        retry: Boolean = false,
    ): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "输入服务检查不能阻塞界面线程" }
        appContext = context.applicationContext
        return health.await(retry)
    }

    /**
     * 服务死亡时立即失效旧输入并在后台结束旧会话
     */
    fun invalidate(dead: IEmbeddedShell) {
        if (!health.invalidate(dead)) return
        val context = appContext ?: return
        Bg.run("embedded-service-lost") {
            synchronized(SessionController) {
                // 新服务已经交接时不能让旧服务的异步收尾中断后续连接
                if (EmbeddedServiceProvider.isRegistered) return@run
                logSink?.invoke("SERVICE: 内置服务已退出，正在结束旧会话")
                SessionController.disconnect(context)
            }
        }
    }

    /** 只回收当前安装包的旧服务进程 */
    fun reapStaleUserServices(): Int {
        val current = service ?: return 0
        val packageName = appContext?.packageName ?: return 0
        val keep = current.pid()
        val result = Shell.exec("ps -A -o UID,PID,ARGS", 5000)
        if (!result.ok) return 0
        val stale =
            result.output
                .lineSequence()
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"), limit = 3)
                    val uid = parts.getOrNull(0)
                    val pid = parts.getOrNull(1)?.toIntOrNull()
                    if ((uid == "2000" || uid == "shell") &&
                        pid != null &&
                        pid != keep &&
                        parts.getOrNull(2) == "$packageName:car_input"
                    ) {
                        pid
                    } else {
                        null
                    }
                }.toList()
        return stale.count { service === current && Shell.exec("kill $it", 3000).ok }
    }
}
