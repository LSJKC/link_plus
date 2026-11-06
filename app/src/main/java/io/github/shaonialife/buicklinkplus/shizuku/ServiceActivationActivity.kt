package io.github.shaonialife.buicklinkplus.shizuku

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.session.SessionController
import io.github.shaonialife.buicklinkplus.ui.DarkMessageDialog
import io.github.shaonialife.buicklinkplus.ui.GlassActivity
import io.github.shaonialife.buicklinkplus.ui.setAppearanceImageResource
import io.github.shaonialife.buicklinkplus.util.Bg

/** 主按钮发起配置，授权交给用户，端点只读 */
class ServiceActivationActivity : GlassActivity() {
    private lateinit var status: TextView
    private lateinit var description: TextView
    private lateinit var primary: TextView
    private lateinit var connectionInfo: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var openSystemWhenNeeded = false
    private var permissionRepair = false
    private var permissionNeeded = false
    private var localMessage: String? = null
    private var lastRendered: Triple<PairingService.Snapshot, Boolean, Boolean>? = null
    private val refresh =
        object : Runnable {
            override fun run() {
                if (!resumed || isDestroyed || isFinishing) return
                render()
                handler.postDelayed(this, 250)
            }
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startConfiguration(permissionRepair)
            } else {
                permissionNeeded = true
                localMessage = getString(R.string.activation_notification_required)
                render(force = true)
            }
        }

    /**
     * 只绑定主操作、返回和帮助，页面没有地址端口输入控件
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_service_activation)
        status = findViewById(R.id.activationStatus)
        description = findViewById(R.id.activationDescription)
        primary = findViewById(R.id.activationPrimary)
        connectionInfo = findViewById(R.id.activationConnectionInfo)
        findViewById<View>(R.id.activationBack).setOnClickListener { finish() }
        findViewById<View>(R.id.activationHelp).setOnClickListener { showHelp() }
        primary.setOnClickListener {
            when {
                CarInputClient.ready -> {
                    setResult(RESULT_OK)
                    finish()
                }
                PairingService.running -> {
                    if (PairingService.snapshot.phase in listOf(PairingService.Phase.WAITING_CODE, PairingService.Phase.WAITING_SYSTEM)) {
                        openSystemSettings()
                    } else {
                        startService(Intent(this, PairingService::class.java).setAction(PairingService.CANCEL))
                    }
                }
                permissionNeeded -> {
                    openSystemWhenNeeded = true
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
                else -> requestConfiguration()
            }
        }
        applyContentWidth()
        render(force = true)
    }

    /**
     * 用户点击后才申请通知权限，重复进入页面不会自动发起新的配对
     */
    private fun requestConfiguration(repair: Boolean = false) {
        if (SessionController.isSessionRunning() || SessionController.sessionStarting.get()) {
            localMessage = "请先断开车机，再进行配对或激活"
            render(force = true)
            return
        }
        permissionRepair = repair
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            permissionNeeded = true
            localMessage = getString(R.string.activation_notification_required)
            render(force = true)
        } else {
            startConfiguration(repair)
        }
    }

    /**
     * 临时前台服务持有流程，系统设置在前台时也能继续自动激活
     */
    private fun startConfiguration(repair: Boolean = false) {
        if (PairingService.running || EmbeddedAdb.activating) return
        permissionNeeded = false
        localMessage = null
        openSystemWhenNeeded = true
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PairingService::class.java)
                    .setAction(if (repair) PairingService.REPAIR else PairingService.CONFIGURE),
            )
        }.onFailure {
            openSystemWhenNeeded = false
            localMessage = getString(R.string.activation_failed_body)
        }
        render(force = true)
    }

    /** 一起更新状态和按钮，只显示本次发现的端口 */
    private fun render(force: Boolean = false) {
        val state = PairingService.snapshot
        val ready = CarInputClient.ready
        val current = Triple(state, ready, PairingService.running)
        if (force || current != lastRendered) {
            lastRendered = current
            val active = PairingService.running
            val waiting = active && state.phase in listOf(PairingService.Phase.WAITING_CODE, PairingService.Phase.WAITING_SYSTEM)
            val busy =
                active &&
                    !ready &&
                    state.phase in listOf(PairingService.Phase.FINDING, PairingService.Phase.PAIRING, PairingService.Phase.STARTING)
            val title =
                when {
                    ready -> R.string.activation_ready_title
                    permissionNeeded -> R.string.activation_failed_title
                    active && state.phase == PairingService.Phase.WAITING_CODE -> R.string.activation_pairing_title
                    active && state.phase == PairingService.Phase.WAITING_SYSTEM -> R.string.activation_system_title
                    active && state.phase == PairingService.Phase.STARTING -> R.string.activation_starting_title
                    active && state.phase == PairingService.Phase.PAIRING -> R.string.activation_pair_working
                    busy -> R.string.activation_finding_title
                    state.phase == PairingService.Phase.FAILED -> R.string.activation_failed_title
                    state.phase == PairingService.Phase.CANCELLED -> R.string.activation_cancelled
                    else -> R.string.activation_idle_title
                }
            status.setText(title)
            description.text = localMessage ?: getString(
                when {
                    ready -> R.string.activation_ready
                    active || state.phase == PairingService.Phase.FAILED -> state.message
                    else -> R.string.activation_idle_body
                },
            )
            primary.setText(
                when {
                    ready -> R.string.activation_done
                    permissionNeeded -> R.string.activation_allow_notifications
                    waiting -> R.string.activation_continue
                    busy -> R.string.activation_cancel
                    state.phase == PairingService.Phase.FAILED -> R.string.activation_retry
                    else -> R.string.activation_one_click
                },
            )
            findViewById<View>(R.id.activationProgress).visibility = if (busy) View.VISIBLE else View.GONE
            findViewById<ImageView>(
                R.id.activationIcon,
            ).setAppearanceImageResource(if (ready) R.drawable.ic_check_circle else R.drawable.ic_shizuku_shield)
            connectionInfo.text =
                getString(
                    R.string.activation_connection_values,
                    state.host ?: getString(R.string.activation_unknown),
                    state.pairingPort?.toString() ?: getString(R.string.activation_unknown),
                    state.connectPort?.toString() ?: getString(R.string.activation_unknown),
                )
        }
        if (openSystemWhenNeeded &&
            PairingService.running &&
            state.phase in listOf(PairingService.Phase.WAITING_CODE, PairingService.Phase.WAITING_SYSTEM)
        ) {
            openSystemWhenNeeded = false
            openSystemSettings()
        }
    }

    /** 打开系统公开设置入口 */
    private fun openSystemSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }.onFailure {
            localMessage = getString(R.string.activation_settings_unavailable)
            render(force = true)
        }
    }

    /** 恢复和法律说明放入帮助入口 */
    private fun showHelp() {
        val actions =
            mutableListOf(
                DarkMessageDialog.Action(getString(R.string.activation_open_settings)) { openSystemSettings() },
                DarkMessageDialog.Action(getString(R.string.activation_licenses)) { showLicenses() },
                DarkMessageDialog.Action(getString(R.string.activation_diagnostics)) {
                    DarkMessageDialog.show(
                        this,
                        getString(R.string.activation_diagnostics),
                        connectionInfo.text.toString() + "\n\n" + (PairingService.snapshot.diagnostic ?: "暂无异常记录"),
                        listOf(DarkMessageDialog.Action("关闭") {}),
                    )
                },
            )
        if (!PairingService.running && !CarInputClient.ready) {
            actions.add(
                DarkMessageDialog.Action(getString(R.string.activation_repair)) {
                    DarkMessageDialog.show(
                        this,
                        getString(R.string.activation_repair),
                        "将重新进行系统配对，其他设置会保留。",
                        listOf(
                            DarkMessageDialog.Action("取消") {
                            },
                            DarkMessageDialog.Action("重新配对", DarkMessageDialog.Emphasis.DANGER) { requestConfiguration(repair = true) },
                        ),
                    )
                },
            )
        }
        if (PairingService.running) {
            actions.add(
                DarkMessageDialog.Action(getString(R.string.activation_cancel)) {
                    startService(Intent(this, PairingService::class.java).setAction(PairingService.CANCEL))
                },
            )
        }
        actions.add(DarkMessageDialog.Action("关闭") {})
        DarkMessageDialog.show(this, "需要帮助？", "首次使用需在系统开启无线调试，并在通知栏输入系统六位码。配对后自动激活；重启后点一次即可重新激活。", actions)
    }

    /**
     * 后台读取许可，避免长文本读盘阻塞界面
     */
    private fun showLicenses() {
        Bg.run("embedded-licenses") {
            val content =
                runCatching { assets.open("embedded-third-party-notices.txt").bufferedReader().use { it.readText() } }
                    .getOrDefault("无法读取开源许可，请查看随测试包提供的对应源码与许可文件")
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    DarkMessageDialog.show(
                        this,
                        getString(R.string.activation_licenses),
                        content,
                        listOf(DarkMessageDialog.Action("关闭") {}),
                    )
                }
            }
        }
    }

    /**
     * 横屏和平板限制内容行宽，保留大按钮与原有滚动行为
     */
    private fun applyContentWidth() {
        val density = resources.displayMetrics.density
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        findViewById<View>(R.id.activationContent).layoutParams =
            FrameLayout.LayoutParams(
                ((resources.configuration.screenWidthDp - 40).coerceAtLeast(240).coerceAtMost(520) * density).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER_HORIZONTAL,
            )
        findViewById<View>(R.id.activationIcon).visibility = if (landscape) View.GONE else View.VISIBLE
        findViewById<View>(R.id.activationFooter).visibility = if (landscape) View.GONE else View.VISIBLE
        val padding = ((if (landscape) 18 else 24) * density).toInt()
        findViewById<View>(R.id.activationCard).setPadding(padding, padding, padding, padding)
    }

    /**
     * 配置切换只更新内容宽度，不重建正在系统设置中运行的服务
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyContentWidth()
    }

    /**
     * 从通知设置返回后继续已授权的点击，普通返回不额外启动任务
     */
    override fun onResume() {
        super.onResume()
        resumed = true
        if (permissionNeeded && openSystemWhenNeeded && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            openSystemWhenNeeded = false
            startConfiguration(permissionRepair)
        }
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    /**
     * 隐藏页面停止轮询，配对与激活由临时前台服务持有
     */
    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    /** 退出页面不取消已发起的配对，服务负责超时和取消 */
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
