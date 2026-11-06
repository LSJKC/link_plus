package io.github.shaonialife.buicklinkplus.session

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity

/** 连接前统一检查，热点凭据在握手后获取 */
object ConnectionPreflight {
    enum class Requirement(
        val title: String,
        val action: String,
    ) {
        BLUETOOTH_PERMISSION("附近蓝牙设备权限", "申请设备权限"),
        BLUETOOTH_ENABLED("手机蓝牙开关", "打开蓝牙设置"),
        WIFI_PERMISSION("车机 WiFi 连接权限", "申请设备权限"),
        WIFI_ENABLED("手机 WiFi 开关", "打开 WLAN 设置"),
        LOCATION_ENABLED("Android 12 位置服务", "打开位置设置"),
        TARGET("已选择且已配对的目标车机", "选择目标车机"),
        IDENTITY("本机蓝牙身份", "重新检查身份"),
        AUDIO_PERMISSION("声音捕获权限", "申请声音权限"),
        PROJECTION("声音捕获的投屏授权", "申请投屏权限"),
    }

    data class Check(
        val requirement: Requirement,
        val ready: Boolean,
    )

    data class Report(
        val checks: List<Check>,
    ) {
        val missing: List<Check> get() = checks.filterNot { it.ready }
        val ready: Boolean get() = missing.isEmpty()

        /** 未解析身份时先完成可直接检查的准备，避免未授权就执行身份查询 */
        fun beforeIdentity(): Report = Report(checks.filterNot { it.requirement == Requirement.IDENTITY })

        /** UI 和日志共用准备状态，普通蓝牙配对不算车机就绪 */
        fun describe(): String = checks.joinToString("\n") { "${if (it.ready) "✓" else "○"} ${it.requirement.title}" }
    }

    /** 只读取当前状态 */
    @android.annotation.SuppressLint("MissingPermission")
    fun inspect(
        context: Context,
        requireAudio: Boolean,
    ): Report {
        // 自定义权限检查由此处收口，下面所有蓝牙读取都先经过这一条件
        val bluetoothPermission =
            granted(context, Manifest.permission.BLUETOOTH_CONNECT) &&
                granted(context, Manifest.permission.BLUETOOTH_SCAN)
        val adapter =
            if (bluetoothPermission) {
                runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
            } else {
                null
            }
        val target = TargetDevicePrefs.get(context)
        val wifiPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted(context, Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
            }
        val checks =
            mutableListOf(
                Check(Requirement.BLUETOOTH_PERMISSION, bluetoothPermission),
                Check(Requirement.BLUETOOTH_ENABLED, runCatching { adapter?.isEnabled == true }.getOrDefault(false)),
                Check(Requirement.WIFI_PERMISSION, wifiPermission),
                Check(
                    Requirement.WIFI_ENABLED,
                    runCatching {
                        context.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true
                    }.getOrDefault(false),
                ),
            )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            checks +=
                Check(
                    Requirement.LOCATION_ENABLED,
                    runCatching {
                        val manager = context.getSystemService(LocationManager::class.java)
                        manager != null && LocationManagerCompat.isLocationEnabled(manager)
                    }.getOrDefault(false),
                )
        }
        checks +=
            Check(
                Requirement.TARGET,
                target != null &&
                    runCatching {
                        adapter?.bondedDevices?.any { TargetDevicePrefs.matches(target, it.address) } == true
                    }.getOrDefault(false),
            )
        checks +=
            Check(
                Requirement.IDENTITY,
                DeviceIdentity.cachedBluetoothMac()?.let { it != DeviceIdentity.PLACEHOLDER_MAC } == true,
            )
        if (requireAudio && !io.github.shaonialife.buicklinkplus.audio.AudioCaptureSupport.available) {
            checks += Check(Requirement.AUDIO_PERMISSION, granted(context, Manifest.permission.RECORD_AUDIO))
            checks += Check(Requirement.PROJECTION, CarPlayService.mediaProjection != null)
        }
        return Report(checks)
    }

    /** 按运行时授权检查权限 */
    private fun granted(
        context: Context,
        permission: String,
    ): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
