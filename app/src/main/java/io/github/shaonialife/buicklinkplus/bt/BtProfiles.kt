package io.github.shaonialife.buicklinkplus.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 按用户选择断开蓝牙通话和媒体连接，只保留配对及独立 iAP2 连接
 * 通过 getProfileProxy 查询状态，反射调用 SystemApi disconnect
 * 查询必须在应用进程执行，不能放到无主 Looper 的 shell 服务
 */
object BtProfiles {
    private const val TAG = "BT-PROFILE"

    /**
     * 按原优先级查询配置文件，低功耗音频仅在系统提供对应接口时启用
     */
    private val profiles: List<Int> =
        buildList {
            add(BluetoothProfile.HEADSET)
            add(BluetoothProfile.A2DP)
            if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO)
        }

    private fun profileName(profile: Int): String =
        when (profile) {
            BluetoothProfile.HEADSET -> "通话(HFP)"
            BluetoothProfile.A2DP -> "音频(A2DP)"
            BluetoothProfile.LE_AUDIO -> "低功耗音频(LE)"
            else -> "profile#$profile"
        }

    /** profile id → 已获取的代理（进程内复用；代理本身无会话状态）。 */
    private val proxies = HashMap<Int, BluetoothProfile>()

    /** 该地址当前在线（已连接）的配置文件名称。 */
    fun connectedNames(
        context: Context,
        address: String,
    ): List<String> =
        profiles.mapNotNull { id ->
            val p = proxy(context, id) ?: return@mapNotNull null
            val connected =
                try {
                    @Suppress("DEPRECATION")
                    p.connectedDevices.any { it.address.equals(address, ignoreCase = true) }
                } catch (_: Throwable) {
                    false
                }
            if (connected) profileName(id) else null
        }

    /**
     * 尝试断开设备的所有蓝牙配置文件
     *
     * @return 断开前仍在线的配置文件
     */
    fun disconnect(
        context: Context,
        device: BluetoothDevice,
    ): List<String> {
        val address =
            try {
                device.address
            } catch (_: SecurityException) {
                return emptyList()
            }
        val before = connectedNames(context, address)
        if (before.isEmpty()) return emptyList()
        for (id in profiles) {
            if (profileName(id) !in before) continue
            proxy(context, id)?.let { disconnectProfile(it, device) }
        }
        // 不再用设备级断开兜底，避免配对回调或入站连接竞争时拆掉整个控制通道
        awaitDisconnected(context, address, 1_500L)
        return before
    }

    /** 断开后仍在线的配置文件（用于日志判断是否真的断开）。 */
    fun stillConnected(
        context: Context,
        address: String,
    ): List<String> = connectedNames(context, address)

    private fun awaitDisconnected(
        context: Context,
        address: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (connectedNames(context, address).isEmpty()) return true
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return connectedNames(context, address).isEmpty()
    }

    private fun proxy(
        context: Context,
        profile: Int,
    ): BluetoothProfile? {
        synchronized(proxies) { proxies[profile]?.let { return it } }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter
                ?: return null
        val latch = CountDownLatch(1)
        val ref = AtomicReference<BluetoothProfile?>(null)
        val requested =
            try {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(
                            id: Int,
                            service: BluetoothProfile,
                        ) {
                            ref.set(service)
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(id: Int) {
                            // 蓝牙服务重启后旧代理失效：清缓存，下次重新获取
                            synchronized(proxies) { proxies.remove(id) }
                            latch.countDown()
                        }
                    },
                    profile,
                )
            } catch (_: Throwable) {
                false
            }
        if (!requested) return null
        // `getProfileProxy` 的回调经**主线程 Handler** 投递：若本方法跑在主线程，
        // 回调永远无法执行 → 必然白等到超时（1.2s）并返回 null。宁可显式失败也不
        // 静默卡顿——调用方必须转到后台线程（本对象所有入口都是后台调用）。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "proxy() 在主线程调用——getProfileProxy 回调会被阻塞，返回 null")
            return null
        }
        if (!latch.await(1_200, TimeUnit.MILLISECONDS)) return null
        return ref.get()?.also { synchronized(proxies) { proxies[profile] = it } }
    }

    /** 反射调用 SystemApi disconnect，失败不抛异常，由调用方检查最终状态 */
    private fun disconnectProfile(
        proxy: BluetoothProfile,
        device: BluetoothDevice,
    ): Boolean =
        try {
            proxy.javaClass
                .getMethod("disconnect", BluetoothDevice::class.java)
                .invoke(proxy, device)
                ?.let { it as? Boolean }
                ?: true
        } catch (_: Throwable) {
            false
        }
}
