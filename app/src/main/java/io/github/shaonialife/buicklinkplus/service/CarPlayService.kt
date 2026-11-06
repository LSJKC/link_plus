package io.github.shaonialife.buicklinkplus.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.MainActivity
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DebugFileLog

/** 连接期间的前台保活服务，会话逻辑由 SessionController 管理 */
class CarPlayService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var latencyLock: WifiManager.WifiLock? = null
    private var receiverRegistered = false
    private var networkRegistered = false
    private var audioModeRegistered = false
    private val audioModeListener =
        android.media.AudioManager.OnModeChangedListener { mode ->
            if (!destroyed) {
                fileLog("SVC: 系统音频模式变化 mode=$mode，模式不等于实际通话路由")
                io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
                    .recordInteractionDiagnostics("系统音频模式变化 mode=$mode")
            }
        }

    @Volatile
    private var destroyed = false
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (destroyed) return
                logPowerState(intent?.action.orEmpty())
                acquireLocks()
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    io.github.shaonialife.buicklinkplus.launcher.VirtualLauncher
                        .recordInteractionDiagnostics("手机息屏")
                }
            }
        }
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!destroyed) fileLog("SVC: Wi-Fi 网络可用 network=" + network)
            }

            override fun onLost(network: Network) {
                if (!destroyed) {
                    logPowerState("Wi-Fi 网络丢失 network=" + network)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        runCatching {
            getSystemService(android.media.AudioManager::class.java).addOnModeChangedListener(mainExecutor, audioModeListener)
            audioModeRegistered = true
        }.onFailure { fileLog("SVC: 音频模式监听注册失败：${it.javaClass.simpleName}") }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
        runCatching {
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }.onFailure { fileLog("SVC: 息屏监听注册失败：" + it.message) }
        runCatching {
            checkNotNull(getSystemService(ConnectivityManager::class.java)).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback,
            )
            networkRegistered = true
        }.onFailure { fileLog("SVC: Wi-Fi 监听注册失败：" + it.message) }
    }

    /** 分别获取各项保活锁，单项失败不影响其他锁，息屏时继续持有 */
    @Suppress("DEPRECATION")
    // 锁归前台会话服务所有，onDestroy 逐项释放，不能以固定时长中止有效推流
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val power = getSystemService(PowerManager::class.java)
        if (wakeLock?.isHeld != true) {
            runCatching {
                val lock =
                    wakeLock ?: checkNotNull(power) { "电源服务不可用" }
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session")
                        .also { wakeLock = it }
                lock.setReferenceCounted(false)
                lock.acquire()
            }.onFailure { fileLog("SVC: CPU 锁获取失败：" + it.message) }
        }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifiLock?.isHeld != true) {
            runCatching {
                val lock =
                    wifiLock ?: checkNotNull(wifi) { "Wi-Fi 服务不可用" }
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CarPlaySender:wifi-sleep")
                        .also { wifiLock = it }
                lock.setReferenceCounted(false)
                lock.acquire()
            }.onFailure { fileLog("SVC: 息屏 Wi-Fi 锁获取失败：" + it.message) }
        }
        if (latencyLock?.isHeld != true) {
            runCatching {
                val lock =
                    latencyLock ?: checkNotNull(wifi) { "Wi-Fi 服务不可用" }
                        .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CarPlaySender:wifi-interactive")
                        .also { latencyLock = it }
                lock.setReferenceCounted(false)
                lock.acquire()
            }.onFailure { fileLog("SVC: 亮屏 Wi-Fi 低延迟锁获取失败：" + it.message) }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            .onFailure { fileLog("SVC: CPU 锁释放失败：" + it.message) }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
            .onFailure { fileLog("SVC: 息屏 Wi-Fi 锁释放失败：" + it.message) }
        wifiLock = null
        runCatching { if (latencyLock?.isHeld == true) latencyLock?.release() }
            .onFailure { fileLog("SVC: 亮屏 Wi-Fi 锁释放失败：" + it.message) }
        latencyLock = null
    }

    private fun logPowerState(reason: String) {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        fileLog(
            "SVC: " + reason + " 亮屏=" + power?.isInteractive + " 锁屏=" + keyguard?.isKeyguardLocked +
                " 电池优化豁免=" + power?.isIgnoringBatteryOptimizations(packageName) +
                " CPU锁=" + (wakeLock?.isHeld == true) + " Wi-Fi锁=" + (wifiLock?.isHeld == true),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // 首次仅启用设备连接类型，已有投影时保留投影类型，重复启动不得先降级再升级
        // 没有有效授权时也不能直接使用清单中的全部前台类型
        val projectionType =
            if (mediaProjection != null) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or projectionType,
        )
        acquireLocks()
        logPowerState("前台服务启动")
        if (Build.VERSION.SDK_INT >= 34) fileLog("SVC: 当前系统可能将高性能 Wi-Fi 请求降级为低延迟锁，持锁状态不等于系统保证息屏网络不休眠")
        // 授权结果在（用户刚确认投屏）时：升级前台类型再创建投影（try/catch 兜底不崩）
        if (pendingProjectionResult != null) {
            upgradeToProjection()
            takeProjection()
        } else if (mediaProjection != null) {
            upgradeToProjection()
        }
        return START_STICKY
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection =
                manager.getMediaProjection(code, data) ?: run {
                    fileLog("SVC: 投屏授权已失效，无法创建音频投影")
                    return
                }
            // Android 14+：createVirtualDisplay 前必须注册回调（资源管理强制）
            projection.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        if (mediaProjection === projection) mediaProjection = null
                        Bg.run("projection-stopped") {
                            SessionController.onProjectionStopped(projection)
                        }
                        // 关键诊断：授权丢失会导致音频/镜像不可用（此前只写 logcat，HKS 加密看不到）
                        fileLog("SVC: MediaProjection 被系统停止（onStop）——音频/镜像需重新授权")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            fileLog("SVC: MediaProjection 就绪（投影创建成功）")
        } catch (error: Exception) {
            fileLog("SVC: getMediaProjection 失败 - ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun upgradeToProjection() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (error: Exception) {
            fileLog("SVC: 升级 mediaProjection FGS 类型失败 - ${error.message}")
        }
    }

    override fun onDestroy() {
        destroyed = true
        if (receiverRegistered) runCatching { unregisterReceiver(screenReceiver) }
        if (audioModeRegistered) {
            runCatching {
                getSystemService(android.media.AudioManager::class.java)?.removeOnModeChangedListener(audioModeListener)
            }
        }
        if (networkRegistered) runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        releaseLocks()
        // Android 14+：MediaProjection 与前台的 mediaProjection 类型绑定——
        // 保活服务停止（如用户点「断开连接」）时系统会连带停止投影。
        fileLog(
            "SVC: onDestroy（保活服务停止）mediaProjection=" +
                (if (mediaProjection != null) "仍在（即将被系统回收）" else "null"),
        )
        super.onDestroy()
    }

    /** 投影生命周期等关键事件：logcat + 文件双写（HKS 环境下文件是唯一可读通道）。 */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** 镜像模式：授权后由 MainActivity 写入；服务重声明 FGS 类型后方可创建虚拟屏。 */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /** 暂存投屏授权，Android 14 起先升级前台服务类型，再获取 MediaProjection */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Buick Link + 连接",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持与车机的连接"
                setShowBadge(false)
            },
        )
        // 点击通知 → 打开 App 主界面（此前缺 setContentIntent，点击无反应）
        val contentIntent =
            android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("Buick Link + 运行中")
            .setContentText("正在保持与车机的连接，点开应用可查看日志")
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
