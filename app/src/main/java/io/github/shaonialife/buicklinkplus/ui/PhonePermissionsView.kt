package io.github.shaonialife.buicklinkplus.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.isNotEmpty
import io.github.shaonialife.buicklinkplus.R
import io.github.shaonialife.buicklinkplus.media.MediaListenerService
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.session.ConnectionPreflight
import io.github.shaonialife.buicklinkplus.shizuku.ShellServiceGate

/** 权限页展示当前状态，授权和设置跳转交给宿主 */
class PhonePermissionsView(
    context: Context,
) : LinearLayout(context) {
    enum class Action {
        CHECK_SERVICE,
        GUIDE,
        DEVICE_PERMISSIONS,
        BLUETOOTH,
        WIFI,
        LOCATION,
        OVERLAY,
        BATTERY,
        BACKGROUND,
        NOTIFICATIONS,
        MEDIA_ACCESS,
        AUDIO,
        SIGNAL,
        SLEEP_HELP,
        DIM_NOW,
    }

    var onAction: ((Action) -> Unit)? = null
    var onCompatChanged: ((Boolean) -> Unit)? = null
    private val statuses = mutableMapOf<Action, TextView>()
    private val preferences = context.getSharedPreferences("phone_permissions", Context.MODE_PRIVATE)
    private var syncing = false
    private val compatSwitch: SwitchCompat

    val backgroundConfirmed: Boolean
        get() = preferences.getBoolean("background_confirmed", false)

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_phone_permissions, this, true)
        findViewById<View>(R.id.permissionCheck).setOnClickListener { onAction?.invoke(Action.CHECK_SERVICE) }
        addRow(
            R.id.permissionGuideGroup,
            Action.GUIDE,
            R.drawable.ic_info_circle,
            R.string.phone_permissions_guide,
            R.string.phone_permissions_guide_hint,
        )
        addRow(
            R.id.permissionRequiredGroup,
            Action.DEVICE_PERMISSIONS,
            R.drawable.ic_shizuku_shield,
            R.string.phone_permissions_devices,
            if (Build.VERSION.SDK_INT >=
                33
            ) {
                R.string.phone_permissions_devices_hint
            } else {
                R.string.phone_permissions_devices_legacy_hint
            },
        )
        addRow(
            R.id.permissionRequiredGroup,
            Action.BLUETOOTH,
            R.drawable.ic_bluetooth,
            R.string.phone_permissions_bluetooth,
            R.string.phone_permissions_bluetooth_hint,
        )
        addRow(
            R.id.permissionRequiredGroup,
            Action.WIFI,
            R.drawable.ic_wifi,
            R.string.phone_permissions_wifi,
            R.string.phone_permissions_wifi_hint,
        )
        if (Build.VERSION.SDK_INT < 33) {
            addRow(
                R.id.permissionRequiredGroup,
                Action.LOCATION,
                R.drawable.ic_phone_location,
                R.string.phone_permissions_location,
                R.string.phone_permissions_location_hint,
            )
        }
        addRow(
            R.id.permissionBackgroundGroup,
            Action.BATTERY,
            R.drawable.ic_power,
            R.string.phone_permissions_battery,
            R.string.phone_permissions_battery_hint,
        )
        addRow(
            R.id.permissionBackgroundGroup,
            Action.BACKGROUND,
            R.drawable.ic_brightness,
            R.string.phone_permissions_vendor,
            R.string.phone_permissions_vendor_hint,
        )
        addRow(
            R.id.permissionBackgroundGroup,
            Action.NOTIFICATIONS,
            R.drawable.ic_phone_notification,
            R.string.phone_permissions_notification,
            R.string.phone_permissions_notification_hint,
        )
        addRow(
            R.id.permissionOptionalGroup,
            Action.OVERLAY,
            R.drawable.ic_menu_display,
            R.string.phone_permissions_overlay,
            R.string.phone_permissions_overlay_hint,
        )
        addRow(
            R.id.permissionOptionalGroup,
            Action.MEDIA_ACCESS,
            R.drawable.ic_phone_notification,
            R.string.phone_permissions_media,
            R.string.phone_permissions_media_hint,
        )
        addRow(
            R.id.permissionOptionalGroup,
            Action.AUDIO,
            R.drawable.ic_speaker,
            R.string.phone_permissions_audio,
            R.string.phone_permissions_audio_hint,
        )
        compatSwitch = findViewById(R.id.permissionCompatSwitch)
        addRow(
            R.id.permissionOptionalGroup,
            Action.SIGNAL,
            R.drawable.ic_signal,
            R.string.phone_permissions_signal,
            R.string.phone_permissions_signal_hint,
        )
        compatSwitch.setOnCheckedChangeListener { _, checked ->
            if (!syncing) onCompatChanged?.invoke(checked)
        }
        findViewById<View>(R.id.permissionSleepHelp).setOnClickListener { onAction?.invoke(Action.SLEEP_HELP) }
        findViewById<View>(R.id.permissionDimNow).setOnClickListener { onAction?.invoke(Action.DIM_NOW) }
    }

    /**
     * 统一权限行尺寸，状态列单独测量，长说明只能在标题列换行
     */
    private fun addRow(
        groupId: Int,
        action: Action,
        icon: Int,
        title: Int,
        hint: Int,
    ) {
        val group = findViewById<LinearLayout>(groupId)
        if (group.isNotEmpty()) {
            group.addView(
                View(context).apply { setAppearanceBackground(R.drawable.bg_phone_divider) },
                LayoutParams(-1, maxOf(1, resources.displayMetrics.density.toInt())).apply {
                    marginStart = (64 * resources.displayMetrics.density).toInt()
                    marginEnd = (16 * resources.displayMetrics.density).toInt()
                },
            )
        }
        val row = LayoutInflater.from(context).inflate(R.layout.item_phone_permission, group, false)
        row.findViewById<ImageView>(R.id.permissionRowIcon).setAppearanceImageResource(icon)
        row.findViewById<TextView>(R.id.permissionRowTitle).setText(title)
        row.findViewById<TextView>(R.id.permissionRowHint).setText(hint)
        statuses[action] = row.findViewById(R.id.permissionRowStatus)
        row.setOnClickListener { onAction?.invoke(action) }
        group.addView(row)
    }

    /** 厂商后台设置仅记录用户确认 */
    fun setBackgroundConfirmed(confirmed: Boolean) {
        preferences.edit {
            putBoolean("background_confirmed", confirmed)
        }
        refresh()
    }

    /**
     * 隐藏页不轮询；检查过程不申请权限、不运行命令，也不发起连接
     */
    fun refresh() {
        if (!isShown) return
        val checks =
            ConnectionPreflight
                .inspect(context, requireAudio = false)
                .checks
                .associate { it.requirement to it.ready }
        val required =
            listOf(
                ConnectionPreflight.Requirement.SHELL_SERVICE,
                ConnectionPreflight.Requirement.BLUETOOTH_PERMISSION,
                ConnectionPreflight.Requirement.BLUETOOTH_ENABLED,
                ConnectionPreflight.Requirement.WIFI_PERMISSION,
                ConnectionPreflight.Requirement.WIFI_ENABLED,
                ConnectionPreflight.Requirement.LOCATION_ENABLED,
            ).filter { it in checks }
        findViewById<TextView>(R.id.permissionSummary).text =
            context.getString(
                R.string.phone_permissions_summary,
                required.count { checks[it] == true },
                required.size,
            )
        val (title, hint) =
            when (ShellServiceGate.state(context)) {
                ShellServiceGate.State.READY -> R.string.phone_permissions_shizuku_ready to R.string.phone_permissions_service_ready
                ShellServiceGate.State.NEEDS_PAIRING ->
                    R.string.phone_permissions_shizuku_missing to
                        R.string.phone_permissions_service_missing
                ShellServiceGate.State.STOPPED -> R.string.phone_permissions_shizuku_stopped to R.string.phone_permissions_service_stopped
                ShellServiceGate.State.ACTIVATING ->
                    R.string.phone_permissions_shizuku_unauthorized to
                        R.string.phone_permissions_service_unauthorized
                ShellServiceGate.State.INPUT_UNAVAILABLE ->
                    R.string.phone_permissions_input_pending to R.string.phone_permissions_input_retry
            }
        findViewById<TextView>(R.id.permissionServiceTitle).setText(title)
        findViewById<TextView>(R.id.permissionServiceHint).setText(hint)
        status(Action.GUIDE, R.string.phone_permissions_view)
        val deviceReady =
            checks[ConnectionPreflight.Requirement.BLUETOOTH_PERMISSION] == true &&
                checks[ConnectionPreflight.Requirement.WIFI_PERMISSION] == true
        status(
            Action.DEVICE_PERMISSIONS,
            if (deviceReady) R.string.phone_permissions_allowed else R.string.phone_permissions_missing,
            deviceReady,
        )
        val bluetoothPermission = checks[ConnectionPreflight.Requirement.BLUETOOTH_PERMISSION] == true
        val bluetoothReady = checks[ConnectionPreflight.Requirement.BLUETOOTH_ENABLED] == true
        status(
            Action.BLUETOOTH,
            when {
                !bluetoothPermission -> R.string.phone_permissions_first_authorize
                bluetoothReady -> R.string.phone_permissions_enabled
                else -> R.string.phone_permissions_disabled
            },
            bluetoothReady,
        )
        val wifi = checks[ConnectionPreflight.Requirement.WIFI_ENABLED] == true
        status(Action.WIFI, if (wifi) R.string.phone_permissions_enabled else R.string.phone_permissions_disabled, wifi)
        val location = checks[ConnectionPreflight.Requirement.LOCATION_ENABLED] == true
        status(Action.LOCATION, if (location) R.string.phone_permissions_enabled else R.string.phone_permissions_disabled, location)
        val overlay = Settings.canDrawOverlays(context)
        status(
            Action.OVERLAY,
            if (overlay) R.string.phone_permissions_allowed else R.string.phone_permissions_missing,
            if (overlay) true else null,
        )
        val exempt = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
        status(
            Action.BATTERY,
            if (exempt) R.string.phone_permissions_exempt else R.string.phone_permissions_not_exempt,
            if (exempt) true else null,
        )
        status(Action.BACKGROUND, if (backgroundConfirmed) R.string.phone_permissions_confirmed else R.string.phone_permissions_manual)
        val notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        status(
            Action.NOTIFICATIONS,
            if (notifications) R.string.phone_permissions_enabled else R.string.phone_permissions_disabled,
            if (notifications) true else null,
        )
        val media =
            runCatching {
                context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
                    ComponentName(context, MediaListenerService::class.java),
                ) == true
            }.getOrDefault(false)
        status(
            Action.MEDIA_ACCESS,
            if (media) R.string.phone_permissions_allowed else R.string.phone_permissions_missing,
            if (media) true else null,
        )
        val audio =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                CarPlayService.mediaProjection != null
        status(
            Action.AUDIO,
            if (audio) R.string.phone_permissions_allowed else R.string.phone_permissions_audio_missing,
            if (audio) true else null,
        )
        syncing = true
        status(
            Action.SIGNAL,
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                R.string.phone_permissions_allowed
            } else {
                R.string.phone_permissions_missing
            },
        )
        compatSwitch.isChecked = ScreenDimmer.enabled
        syncing = false
        findViewById<TextView>(R.id.permissionDimNow).apply {
            isEnabled = ScreenDimmer.enabled && ScreenDimmer.isAttached()
            setText(if (isEnabled) R.string.phone_permissions_dim else R.string.phone_permissions_dim_unavailable)
        }
    }

    /**
     * 通过项使用成功色，缺失的必备项使用警示色，可选项和手动确认使用中性色
     */
    private fun status(
        action: Action,
        label: Int,
        ready: Boolean? = null,
    ) {
        statuses[action]?.apply {
            setText(label)
            setAppearanceTextColor(
                when (ready) {
                    true -> R.color.m3_ok
                    false -> R.color.phone_badge_warning_text
                    null -> R.color.m3_on_var
                },
            )
        }
    }
}
