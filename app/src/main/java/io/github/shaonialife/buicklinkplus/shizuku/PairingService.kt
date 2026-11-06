package io.github.shaonialife.buicklinkplus.shizuku

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DebugFileLog

/** 首次配对由用户发起，后续复用身份恢复服务，进入系统设置后仍继续 */
class PairingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var working = false
    private var automaticRestore = false

    @Volatile private var destroyed = false

    @Volatile private var cancelled = false

    @Volatile private var operationThread: Thread? = null
    private val expire =
        Runnable {
            synchronized(stateLock) {
                cancelled = true
                snapshot = snapshot.copy(phase = Phase.FAILED, message = R.string.activation_timeout)
            }
            EmbeddedAdb.cancel()
            operationThread?.interrupt()
            trace("配置等待超时")
            stopSelf()
        }

    /**
     * 配置或前台打开软件时启动临时服务，整个流程最多保留三分钟
     */
    override fun onCreate() {
        super.onCreate()
        running = true
        getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL, "本机配对", NotificationManager.IMPORTANCE_HIGH))
            createNotificationChannel(NotificationChannel(RESULT_CHANNEL, "激活结果", NotificationManager.IMPORTANCE_LOW))
            cancel(NOTIFICATION + 1)
        }
        snapshot = Snapshot(request = snapshot.request + 1, phase = Phase.FINDING)
        trace("开始配置 已有配对=${EmbeddedAdb.hasPairing} 输入就绪=${CarInputClient.ready}")
        handler.postDelayed(expire, 180_000)
    }

    /**
     * 只接收显式操作与通知输入，不提供可供其他应用访问的接口
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 拒绝重复任务和过期通知，配对码只在本次内存调用中使用
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == CANCEL) {
            synchronized(stateLock) {
                cancelled = true
                snapshot = snapshot.copy(phase = Phase.CANCELLED, message = R.string.activation_cancelled)
            }
            EmbeddedAdb.cancel()
            operationThread?.interrupt()
            trace("用户取消")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!working) automaticRestore = intent?.action == RESTORE
        if (automaticRestore && !working) trace("收到打开软件后的恢复请求")
        showNotification(snapshot)
        if (working || cancelled) return START_NOT_STICKY
        if (intent?.action == PAIR) {
            if (snapshot.phase != Phase.WAITING_CODE) return START_NOT_STICKY
            val code =
                RemoteInput
                    .getResultsFromIntent(intent)
                    ?.getCharSequence(CODE)
                    ?.toString()
                    .orEmpty()
                    .trim()
            if (!code.matches(Regex("[0-9]{6}"))) {
                report(Phase.WAITING_CODE, R.string.activation_pair_invalid)
                return START_NOT_STICKY
            }
            runOperation {
                report(Phase.PAIRING, R.string.activation_pair_working)
                val endpoint =
                    AdbDiscovery.find(applicationContext, pairing = true)
                        ?: error("未找到系统配对窗口，请保持配对码窗口开启后重新输入")
                checkActive()
                synchronized(stateLock) {
                    checkActive()
                    snapshot = snapshot.copy(host = endpoint.host, pairingPort = endpoint.port)
                }
                EmbeddedAdb.pair(applicationContext, endpoint.host, endpoint.port, code)
                activateAutomatically()
            }
        } else {
            runOperation {
                EmbeddedAdb.initialize(applicationContext)
                if (intent?.action == REPAIR) EmbeddedAdb.resetIdentity(applicationContext)
                if (CarInputClient.ready) {
                    report(Phase.READY, R.string.activation_ready)
                } else if (EmbeddedServiceProvider.isRegistered || EmbeddedAdb.hasPairing) {
                    activateAutomatically()
                } else {
                    report(
                        if (automaticRestore) Phase.FAILED else Phase.WAITING_CODE,
                        if (automaticRestore) R.string.activation_pairing_required else R.string.activation_notification_help,
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * 自动等待本机无线调试上线，确认配对被撤销时才改走重新配对
     */
    private fun activateAutomatically() {
        checkActive()
        if (EmbeddedServiceProvider.isRegistered) {
            report(Phase.STARTING, R.string.activation_starting_body)
            check(CarInputClient.awaitReady(applicationContext, retry = true)) { "权限服务在线，但触控检查尚未通过，请直接重试" }
            checkActive()
            report(Phase.READY, R.string.activation_ready)
            return
        }
        report(Phase.FINDING, R.string.activation_finding_body)
        val deadline = SystemClock.elapsedRealtime() + 90_000
        var retries = 0
        val finished =
            awaitAdbActivation(
                deadline = deadline,
                now = SystemClock::elapsedRealtime,
                checkActive = ::checkActive,
                attempt = {
                    val endpoint = AdbDiscovery.find(applicationContext)
                    checkActive()
                    if (endpoint == null) {
                        report(Phase.WAITING_SYSTEM, R.string.activation_system_body)
                        false
                    } else {
                        synchronized(stateLock) {
                            checkActive()
                            snapshot = snapshot.copy(host = endpoint.host, connectPort = endpoint.port)
                        }
                        report(Phase.STARTING, R.string.activation_starting_body)
                        try {
                            EmbeddedAdb.activate(applicationContext, endpoint.host, endpoint.port)
                            checkActive()
                            check(CarInputClient.ready) { "服务没有通过就绪检查" }
                            report(Phase.READY, R.string.activation_ready)
                        } catch (error: Exception) {
                            checkActive()
                            if (generateSequence<Throwable>(error) { it.cause }.any {
                                    it is AdbAuthenticationFailedException || it is AdbPairingRequiredException
                                }
                            ) {
                                report(
                                    if (automaticRestore) Phase.FAILED else Phase.WAITING_CODE,
                                    if (automaticRestore) R.string.activation_pairing_required else R.string.activation_notification_help,
                                )
                            } else {
                                throw error
                            }
                        }
                        true
                    }
                },
                pause = Thread::sleep,
                onConnectionFailure = {
                    retries++
                    trace("连接失败，重新发现端点 次数=$retries 剩余预算=${(deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)}ms")
                    report(Phase.WAITING_SYSTEM, R.string.activation_system_body)
                },
            )
        if (!finished) report(Phase.FAILED, R.string.activation_not_found)
    }

    /**
     * 串行执行有界后台操作，结束后的结果不能污染已经取消的请求
     */
    private fun runOperation(action: () -> Unit) {
        working = true
        Bg.run("automatic-activation") {
            operationThread = Thread.currentThread()
            try {
                checkActive()
                check(!SessionController.isSessionRunning() && !SessionController.sessionStarting.get()) {
                    "请先断开车机，再进行配对或激活"
                }
                action()
            } catch (error: Throwable) {
                // 原生配对或加密依赖加载失败也要结束等待，不能只被线程池记入不可导出的 logcat
                val types = generateSequence(error) { it.cause }.take(6).joinToString(" → ") { it.javaClass.simpleName }
                val transport = error.suppressed.joinToString { it.javaClass.simpleName }
                synchronized(stateLock) {
                    if (!destroyed && !cancelled) {
                        snapshot =
                            snapshot.copy(
                                diagnostic =
                                    (error.message ?: error.javaClass.simpleName) +
                                        "\n异常类型=$types 传输异常=${transport.ifEmpty { "无附加异常" }}",
                            )
                    }
                }
                if (!destroyed && !cancelled) trace("操作失败 异常类型=$types 传输异常=${transport.ifEmpty { "无附加异常" }}")
                report(Phase.FAILED, R.string.activation_failed_body)
            } finally {
                operationThread = null
                Thread.interrupted()
            }
            handler.post {
                if (!destroyed && !cancelled) {
                    working = false
                    if (snapshot.phase == Phase.READY || snapshot.phase == Phase.FAILED) {
                        if (snapshot.phase == Phase.READY) lastAutomaticAttempt = null
                        if (!automaticRestore || snapshot.phase != Phase.READY) showResult()
                        stopSelf()
                    }
                }
            }
        }
    }

    /**
     * 取消与销毁都能终止发现和启动，不允许迟到结果继续下一段流程
     */
    private fun checkActive() {
        check(!destroyed && !cancelled && !Thread.currentThread().isInterrupted) { "激活已取消" }
    }

    /**
     * 界面只读取不可变快照，不在界面线程调用网络或远程服务
     */
    private fun report(
        phase: Phase,
        message: Int,
    ) {
        val changed =
            synchronized(stateLock) {
                if (destroyed || cancelled) return
                val different = snapshot.phase != phase || snapshot.message != message
                snapshot = snapshot.copy(phase = phase, message = message)
                different
            }
        if (changed) trace("状态更新")
        handler.post { if (!destroyed && !cancelled) showNotification(snapshot) }
    }

    /** 诊断写入导出日志，不记录配对码、身份或地址 */
    private fun trace(message: String) {
        val state = snapshot
        val elapsed = SystemClock.elapsedRealtime()
        val paired = EmbeddedAdb.hasPairing
        val app = applicationContext
        Bg.run("pairing-diagnostic") {
            DebugFileLog.append(
                app,
                "PAIRING: 请求=${state.request} 事件时间=${elapsed}ms 阶段=${state.phase} " +
                    "配对端口=${state.pairingPort} 连接端口=${state.connectPort} 已配对=$paired 自动恢复=$automaticRestore $message",
            )
        }
    }

    /**
     * 通知输入保留在系统配对窗口之上，配对完成后自动进入激活
     */
    private fun showNotification(state: Snapshot) {
        val builder =
            NotificationCompat
                .Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_shizuku_shield)
                .setContentTitle(getString(R.string.activation_title))
                .setContentText(getString(state.message))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(state.message)))
                .setOnlyAlertOnce(true)
                .setSilent(automaticRestore)
                .setOngoing(true)
                .setContentIntent(openPage())
        if (state.phase == Phase.WAITING_CODE) {
            val submit =
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, PairingService::class.java).setAction(PAIR),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            builder.addAction(
                NotificationCompat.Action
                    .Builder(0, "输入配对码", submit)
                    .addRemoteInput(RemoteInput.Builder(CODE).setLabel("六位配对码").build())
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .build(),
            )
        }
        val cancel =
            PendingIntent.getService(
                this,
                2,
                Intent(this, PairingService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        builder.addAction(0, getString(R.string.activation_cancel), cancel)
        startForeground(NOTIFICATION, builder.build())
    }

    /**
     * 结果通知允许返回软件查看状态，不抢占系统前台页面
     */
    private fun showResult() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION + 1,
            NotificationCompat
                .Builder(this, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_shizuku_shield)
                .setContentTitle(getString(if (CarInputClient.ready) R.string.activation_ready_title else R.string.activation_failed_title))
                .setContentText(getString(snapshot.message))
                .setContentIntent(openPage())
                .setAutoCancel(true)
                .build(),
        )
    }

    /**
     * 复用激活页，防止从通知反复创建相同页面
     */
    private fun openPage(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, ServiceActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /**
     * 无论超时、取消还是退出，都释放发现等待与前台通知
     */
    override fun onDestroy() {
        synchronized(stateLock) { destroyed = true }
        trace("配置服务结束")
        handler.removeCallbacksAndMessages(null)
        if (working) {
            EmbeddedAdb.cancel()
            operationThread?.interrupt()
        }
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * 流程状态不保存配对码，端点只保存实际发现的只读结果
     */
    enum class Phase { IDLE, FINDING, WAITING_SYSTEM, WAITING_CODE, PAIRING, STARTING, READY, FAILED, CANCELLED }

    /**
     * 单次请求的界面快照，不包含可修改参数或敏感身份
     */
    data class Snapshot(
        val request: Long = 0,
        val phase: Phase = Phase.IDLE,
        val message: Int = R.string.activation_finding_body,
        val host: String? = null,
        val pairingPort: Int? = null,
        val connectPort: Int? = null,
        val diagnostic: String? = null,
    )

    companion object {
        internal const val CONFIGURE = "embedded.configure"
        internal const val RESTORE = "embedded.restore"
        internal const val REPAIR = "embedded.repair"
        internal const val CANCEL = "embedded.cancel"
        private const val PAIR = "embedded.pair"
        private const val CODE = "pairing_code"
        private const val CHANNEL = "embedded-pairing"
        private const val RESULT_CHANNEL = "embedded-activation-result"
        private const val NOTIFICATION = 7042
        private var lastAutomaticAttempt: Long? = null

        /** 页面恢复前台后，已有身份或在线服务才尝试恢复，取消后不自动重启 */
        fun restoreOnAppOpen(context: android.content.Context): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper()) { "自动恢复入口必须在前台界面线程调用" }
            if (CarInputClient.ready ||
                running ||
                EmbeddedAdb.activating ||
                SessionController.isSessionRunning() ||
                SessionController.sessionStarting.get() ||
                (!EmbeddedAdb.hasPairing && !EmbeddedServiceProvider.isRegistered) ||
                snapshot.phase == Phase.CANCELLED
            ) {
                return false
            }
            val now = SystemClock.elapsedRealtime()
            if (lastAutomaticAttempt?.let { now - it < 30_000 } == true) return false
            // 先登记请求，防止服务 onCreate 尚未到达时多个页面重复启动
            lastAutomaticAttempt = now
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java).setAction(RESTORE))
                true
            }.getOrElse { error ->
                val app = context.applicationContext
                Bg.run("automatic-restore-start-failed") {
                    DebugFileLog.append(app, "PAIRING: 前台自动恢复启动失败 类型=${error.javaClass.simpleName}")
                }
                false
            }
        }

        @Volatile var snapshot = Snapshot()
            private set

        @Volatile var running = false
            private set
    }
}
