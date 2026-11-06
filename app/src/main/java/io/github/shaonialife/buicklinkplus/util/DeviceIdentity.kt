package io.github.shaonialife.buicklinkplus.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit

/**
 * 本机身份解析：蓝牙 MAC 与设备名（无线 CarPlay 协议必需）。
 *
 * 背景（Audi MIB3 真车实测，2026-10-09）：Android 11+ 应用层调
 * `BluetoothAdapter.getAddress()` 只会拿到占位 `02:00:00:00:00:00`；
 * 而车机在解析 0x4300 的无线组（bluetoothTransportIdentifier）与后续
 * 0x4E0E 时会校验其是否与实际 RFCOMM 对端一致——占位值导致车机静默丢弃
 * （现象：链路层照常 ACK，CSM 层不再回应，永远等不到 0x4301）。
 *
 * 本版本已移除内置无线 ADB/shell 激活，解析顺序（全部无需 ADB 权限）：
 *  0. **用户手填**（`settings` 偏好里的 `bt_mac_override`）——最高优先。
 *     Honor / MagicOS 之类机型把 MAC 在所有接口上都脱敏成 `XX:XX:XX:XX:xx:xx`，
 *     软件侧没有任何合法途径拿到真实地址，只能由用户在设置里填一次。
 *  1. sysfs 直读 `/sys/class/bluetooth/hci0/address`（多数机型 world-readable）
 *  2. `Settings.Secure` 直读（普通应用多数机型读不到，兜底）
 *  3. `BluetoothAdapter.getAddress()` 反射（旧 ROM 不脱敏）
 * 成功结果永久缓存；失败 10 秒内不重试（避免每次连接阻塞）。
 *
 * 为什么必须真实地址：接收端（如 WinPlay/UxPlay 系）在 `automaticPhone=false`
 * 时按「用户选中的那台 iPhone 的蓝牙地址」过滤，`SETUP session` 命中不符
 * 即回 `403`；ctrl 广播 TXT id 也用它匹配邀请。
 *
 * 参考实现：CarPToCarL `util/DeviceIdentity.kt`（MIT, Copyright (c) 2026 lizhihua），
 * 本文件按无 shell 模型裁剪（去掉 `content query` 兜底）。
 */
object DeviceIdentity {
    const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    /** 与手机首页/设置共用同一个偏好文件（`settings`）。 */
    private const val PREFS = "settings"
    private const val KEY_MAC_OVERRIDE = "bt_mac_override"

    private val MAC_REGEX = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    /** 手填地址（进程内缓存，避免每次 SETUP 都读偏好）。 */
    @Volatile
    private var overrideMac: String? = null

    @Volatile
    private var overrideLoaded = false

    @Volatile
    private var cachedMac: String? = null

    @Volatile
    private var lastAttemptAtMillis = 0L

    /**
     * 真实蓝牙 MAC（大写冒号格式）；解析失败返回占位。
     *
     * @param forceRetry 忽略静默窗口立即重试。ctrl 广播注册时若拿到占位值，
     *   必须能立刻重试而不是等满 10 秒静默窗口。
     */
    fun bluetoothMac(
        context: Context,
        forceRetry: Boolean = false,
    ): String {
        // 手填地址优先：它是唯一能突破厂商脱敏的来源，且必须能覆盖任何自动解析结果
        readOverride(context)?.let { return it }
        cachedMac?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (!forceRetry && now - lastAttemptAtMillis < RETRY_QUIET_MILLIS) return PLACEHOLDER_MAC
        synchronized(this) {
            readOverride(context)?.let { return it }
            cachedMac?.let { return it }
            lastAttemptAtMillis = now
            val resolved = resolveViaSysfs() ?: resolveViaSettings(context) ?: resolveViaAdapter(context) ?: PLACEHOLDER_MAC
            if (resolved != PLACEHOLDER_MAC) cachedMac = resolved
            return resolved
        }
    }

    /** 已解析并缓存的本机蓝牙 MAC；尚未解析成功返回 null（不触发解析）。 */
    fun cachedBluetoothMac(): String? = overrideMac ?: cachedMac

    /** 当前是否在用「手填地址」（设置界面显示来源用）。 */
    fun hasOverride(context: Context): Boolean = readOverride(context) != null

    /** 手填手机本机蓝牙地址（大小写/分隔符随意，内部规范化）。 */
    fun setOverride(
        context: Context,
        raw: String?,
    ): String? {
        val text = raw.orEmpty().trim()
        if (text.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(KEY_MAC_OVERRIDE) }
            overrideMac = null
            overrideLoaded = true
            cachedMac = null
            return PLACEHOLDER_MAC
        }
        val normalized = normalize(text) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_MAC_OVERRIDE, normalized) }
        overrideMac = normalized
        overrideLoaded = true
        cachedMac = normalized
        return normalized
    }

    /** 手填地址；未设置或格式非法返回 null。 */
    fun overrideBluetoothMac(context: Context): String? = readOverride(context)

    private fun readOverride(context: Context): String? {
        if (overrideLoaded) return overrideMac
        synchronized(this) {
            if (overrideLoaded) return overrideMac
            val stored =
                context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_MAC_OVERRIDE, null)
            overrideMac = stored?.let { normalize(it) }
            overrideLoaded = true
            return overrideMac
        }
    }

    /** 把 `aabbccddeeff` / `AA-BB-CC-DD-EE-FF` / `AA:BB:CC:DD:EE:FF` 统一成大写冒号格式。 */
    fun normalize(raw: String): String? {
        val hex = raw.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        if (hex.length != 12) return null
        val mac =
            hex
                .chunked(2)
                .joinToString(":")
                .uppercase()
        if (!MAC_REGEX.matches(mac)) return null
        if (mac == PLACEHOLDER_MAC) return null
        return mac
    }

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

    /**
     * sysfs 直读：多数机型 `/sys/class/bluetooth/hci0/address` 是 world-readable
     * （不需要任何权限，纯文件读），这是无 shell 后最可靠的真实地址来源。
     * 有些 ROM 的 SELinux 会拦，读失败就继续往下走。
     */
    private fun resolveViaSysfs(): String? =
        SYSFS_MAC_PATHS.firstNotNullOfOrNull { path ->
            runCatching {
                val file = java.io.File(path)
                if (!file.canRead()) return@runCatching null
                file
                    .readText()
                    .trim()
                    .takeIf { MAC_REGEX.matches(it) && it != PLACEHOLDER_MAC }
                    ?.uppercase()
            }.getOrNull()
        }

    /** `BluetoothAdapter.getAddress()` 反射（部分旧 ROM / 定制 ROM 不脱敏）。 */
    private fun resolveViaAdapter(context: Context): String? =
        try {
            val adapter =
                context
                    .getSystemService(BluetoothManager::class.java)
                    ?.adapter
                    ?: return null
            val raw =
                adapter.javaClass
                    .getMethod("getAddress")
                    .invoke(adapter) as? String
            raw
                ?.takeIf { MAC_REGEX.matches(it) && it != PLACEHOLDER_MAC }
                ?.uppercase()
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

    /** sysfs 蓝牙地址候选（按命中概率排序）。 */
    private val SYSFS_MAC_PATHS =
        listOf(
            "/sys/class/bluetooth/hci0/address",
            "/sys/devices/virtual/bluetooth/hci0/address",
            "/sys/module/bluetooth/parameters/bdaddr",
        )
}
