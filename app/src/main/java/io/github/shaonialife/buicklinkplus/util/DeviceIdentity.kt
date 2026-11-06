package io.github.shaonialife.buicklinkplus.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import io.github.shaonialife.buicklinkplus.shizuku.Shell

/**
 * 解析本机蓝牙 MAC 和名称，先查 shell 安全设置，再直读 Settings.Secure
 * 成功后缓存，失败 10 秒内不重复查询
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * 获取大写冒号格式的蓝牙 MAC，失败返回占位地址
     *
     * @param forceRetry 是否跳过失败后的等待窗口
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            val resolved = resolveViaShell() ?: resolveViaSettings(context) ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /** 只读已缓存的蓝牙 MAC，未成功解析时返回 null，不触发 shell 查询 */
    fun cachedBluetoothMac(): String? = cachedMac

    /** 设备名（0x4E09 DeviceInformationUpdate 用；对齐真机发的是本机蓝牙名）。 */
    fun deviceName(context: Context): String =
        try {
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                "iPhone"
            } else {
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: "iPhone"
            }
        } catch (_: Exception) {
            "iPhone"
        }

    private fun resolveViaShell(): String? =
        try {
            val result =
                Shell.exec(
                    "content query --uri content://settings/secure/bluetooth_address",
                    timeoutMs = 4_000,
                )
            MAC_REGEX.find(result.output)?.value?.uppercase()
        } catch (_: Exception) {
            null
        }

    private fun resolveViaSettings(context: Context): String? =
        try {
            Settings.Secure
                .getString(context.contentResolver, "bluetooth_address")
                ?.takeIf { MAC_REGEX.matches(it) }
                ?.uppercase()
        } catch (_: Exception) {
            null
        }

    private const val RETRY_QUIET_MILLIS = 10_000L
}
