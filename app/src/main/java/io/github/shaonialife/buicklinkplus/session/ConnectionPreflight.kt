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
import io.github.shaonialife.buicklinkplus.audio.AudioCaptureSupport
import io.github.shaonialife.buicklinkplus.audio.AudioPreference
import io.github.shaonialife.buicklinkplus.bt.TargetDevicePrefs
import io.github.shaonialife.buicklinkplus.service.CarPlayService
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity

/**
 * 连接前统一检查，热点凭据在握手后获取。
 *
 * 权限模型（CarLife 内容源）：
 * - 默认/音质优先（AUTO/QUALITY）：音频来自 CarLife 引擎 9240/9241 外部 PCM，**不要求任何权限**；
 * - 兼容优先（COMPATIBILITY，用户显式选择）：走原版 MediaProjection 播放捕获，需要
 *   RECORD_AUDIO + 屏幕录制（投屏）授权，因此这里恢复原版的音频/投屏检查项。
 */
object ConnectionPreflight {
    enum class Requirement(
        val title: String,
        val action: String,
    ) {
        /**
         * 旧「内置权限服务」项：内置无线 ADB/shell 激活已整体移除（compat.ShellServiceGate
         * 恒为 STOPPED）。保留枚举值只为恢复后的原版 MainActivity 编译兼容，**永不加入检查**
         * （不能因为它阻塞连接）。
         */
        SHELL_SERVICE("内置权限服务", "激活服务"),

        /** 旧「触控输入服务」项：同 SHELL_SERVICE，已移除、永不检查。 */
        INPUT_SERVICE("触控输入服务", "重试输入服务"),
        BLUETOOTH_PERMISSION("附近蓝牙设备权限", "申请设备权限"),
        BLUETOOTH_ENABLED("手机蓝牙开关", "打开蓝牙设置"),
        WIFI_PERMISSION("车机 WiFi 连接权限", "申请设备权限"),
        WIFI_ENABLED("手机 WiFi 开关", "打开 WLAN 设置"),
        LOCATION_ENABLED("Android 12 位置服务", "打开位置设置"),
        TARGET("已选择且已配对的目标车机", "选择目标车机"),
        IDENTITY("本机蓝牙身份", "重新检查身份"),

        /** 兼容模式（MediaProjection 播放捕获）才要求：录音权限。 */
        AUDIO_PERMISSION("声音捕获权限", "申请声音权限"),

        /** 兼容模式（MediaProjection 播放捕获）才要求：屏幕录制/投屏授权。 */
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
        fun beforeIdentity(): Report = Report(checks.filterNot { it.requirement in setOf(Requirement.IDENTITY, Requirement.INPUT_SERVICE) })

        /** UI 和日志共用准备状态，普通蓝牙配对不算车机就绪 */
        fun describe(): String = checks.joinToString("\n") { "${if (it.ready) "✓" else "○"} ${it.requirement.title}" }
    }

    /**
     * 只读取当前状态。
     *
     * @param requireAudio 本次连接是否需要在车机侧播放声音。只有「音频策略=兼容优先」时才进一步
     *   要求录音权限 + 投屏授权（原版 MediaProjection 采集）；默认/音质优先的 CarLife 外部 PCM
     *   路径不产生任何音频权限检查项。
     */
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
                // 注意：SHELL_SERVICE/INPUT_SERVICE 已随内置无线 ADB 移除，永不加入检查。
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
        // 原版语义：需要车机声音且本机采集能力不可用（无内置 shell 服务）时，兼容模式要求
        // 用户授权 RECORD_AUDIO + 屏幕录制（投屏）。CarLife 默认外部 PCM 策略（AUTO/QUALITY）
        // 不进入这个分支，因此默认路径不需要任何权限。
        if (requireAudio &&
            SessionController.audioPreference == AudioPreference.COMPATIBILITY &&
            !AudioCaptureSupport.available
        ) {
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
