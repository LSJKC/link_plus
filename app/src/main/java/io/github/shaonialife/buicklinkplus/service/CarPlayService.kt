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
import io.github.shaonialife.buicklinkplus.util.DebugFileLog

/**
 * 连接期间的前台保活服务（「已连接设备」类型），会话逻辑由会话控制器管理。
 *
 * 不持有/申请 MediaProjection：CarLife 音频来自引擎 9240/9241 外部 PCM，无需投屏授权。
 */
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
        // CarLife 音频走引擎 9240/9241 外部 PCM，不需要 MediaProjection/投屏授权；
        // 前台服务只需「已连接设备」类型（不再声明/升级 mediaProjection 类型）。
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        acquireLocks()
        logPowerState("前台服务启动")
        if (Build.VERSION.SDK_INT >= 34) fileLog("SVC: 当前系统可能将高性能 Wi-Fi 请求降级为低延迟锁，持锁状态不等于系统保证息屏网络不休眠")
        return START_STICKY
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
        fileLog("SVC: onDestroy（保活服务停止）")
        super.onDestroy()
    }

    /** 保活服务关键事件：logcat + 文件双写（HKS 环境下文件是唯一可读通道）。 */
    private fun fileLog(message: String) {
        android.util.Log.i("CarPlayService", message)
        DebugFileLog
            .append(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

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
