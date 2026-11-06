package io.github.shaonialife.buicklinkplus.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.util.Bg
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 通过车机热点承载投屏，优先 WifiNetworkSpecifier 专属网络，手动入网时绑定现有 WiFi
 * 不请求互联网能力，SSID 精确匹配；绑定进程后再建 socket 和注册 mDNS
 */
class WifiCarPlayConnector(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    /** 应用专属网络的连接结果。 */
    sealed interface JoinResult {
        /** 已连接并绑定到本进程；[network] 可用于 NSD/端点判断。 */
        data class Joined(
            val network: Network,
        ) : JoinResult

        /** 系统未允许（用户拒绝/热点不在范围/超时）。可重试。 */
        data object Unavailable : JoinResult

        /** 该安全类型无法用 WifiNetworkSpecifier 表达（WEP）——调用方走旧路径。 */
        data object Unsupported : JoinResult
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val networkLock = Any()
    private val requests =
        io.github.shaonialife.buicklinkplus.util
            .RequestEpoch()

    @Volatile private var connectionMode = "未连接"

    @Volatile private var defaultNetwork: Network? = connectivityManager.activeNetwork
    private val defaultCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) defaultNetwork = null
            }
        }

    init {
        runCatching { connectivityManager.registerDefaultNetworkCallback(defaultCallback) }
    }

    /** 展示系统默认网络和本进程绑定状态，不据此推断其他应用的网络路径 */
    fun status(): String {
        val state = statusSnapshot()
        return "投屏：${state.connectionMode} · 本应用默认网络：${state.defaultTransport}${if (state.internetValidated) "（已验证）" else "（未验证互联网）"}" +
            " · ${if (state.cellularAvailable) "已检测到蜂窝网络" else "未检测到蜂窝网络"}"
    }

    /** 首页使用的网络状态，不含热点名称和密码 */
    data class Status(
        val connectionMode: String,
        val defaultTransport: String,
        val internetValidated: Boolean,
        val cellularAvailable: Boolean,
        val hotspotBound: Boolean,
    )

    /**
     * 从现有网络回调和系统能力中读取界面状态，不发起连接或修改网络绑定
     */
    fun statusSnapshot(): Status {
        val caps = defaultNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val transport =
            when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "手机流量"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "系统 Wi-Fi"
                else -> "未检测到默认网络"
            }
        val cellular =
            connectivityManager.existingNetworks().any {
                connectivityManager.getNetworkCapabilities(it)?.let { capability ->
                    capability.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        capability.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                } == true
            }
        return Status(connectionMode, transport, internet, cellular, currentNetwork != null)
    }

    /**
     * 绑定与所有权检查串行，绑定失败不能报告已连接
     */
    private fun activate(
        ticket: Long,
        network: Network,
        mode: String,
        ssid: String,
    ): Boolean =
        synchronized(networkLock) {
            if (!requests.owns(ticket)) return@synchronized false
            val bound = runCatching { connectivityManager.bindProcessToNetwork(network) }.getOrDefault(false)
            if (bound) {
                boundNetwork.set(network)
                boundSsid = ssid
                connectionMode = mode
            }
            bound
        }

    /**
     * 只有当前请求丢失的网络可以撤销绑定和通知会话层
     */
    private fun lost(
        ticket: Long,
        network: Network,
        ssid: String,
    ) {
        synchronized(networkLock) {
            if (!requests.owns(ticket) || !boundNetwork.compareAndSet(network, null)) return
            runCatching { connectivityManager.bindProcessToNetwork(null) }
            release(ticket)
            connectionMode = "已断开"
            onNetworkLost?.invoke(ssid)
        }
    }

    @Volatile private var boundSsid: String? = null

    val currentNetwork: Network? get() = boundSsid?.let { validNetwork(it) }

    /** 校验缓存网络在系统中仍存活，不要求车机热点提供互联网 */
    fun validNetwork(expectedSsid: String): Network? {
        val (network, ownerSsid) =
            synchronized(networkLock) {
                (boundNetwork.get() ?: return null) to boundSsid
            }
        // 旧目标的调用不能让新目标的健康网络失效
        if (ownerSsid != expectedSsid) return null
        val usable =
            runCatching {
                val caps = connectivityManager.getNetworkCapabilities(network)
                val link = connectivityManager.getLinkProperties(network)
                val observed =
                    (caps?.transportInfo as? WifiInfo)
                        ?.ssid
                        ?.removeSurrounding("\"")
                        ?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", true) }
                NetworkReusePolicy.usable(
                    expectedSsid,
                    ownerSsid,
                    observed,
                    connectivityManager.existingNetworks().contains(network),
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                    !link?.interfaceName.isNullOrEmpty() && link.linkAddresses.isNotEmpty() == true,
                )
            }.getOrDefault(false)
        if (!usable) {
            // 已存在但属性尚未提交的网络继续等待，不能由状态查询释放当前请求
            val present = runCatching { connectivityManager.existingNetworks().contains(network) }.getOrDefault(true)
            val observed =
                runCatching {
                    (connectivityManager.getNetworkCapabilities(network)?.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
                }.getOrNull()
            if (!present || (!observed.isNullOrBlank() && observed != "<unknown ssid>" && observed != expectedSsid)) invalidate(network)
        }
        return network.takeIf { usable && boundNetwork.get() == it }
    }

    /** 只清除发生错误的原网络，保留后来建立的新绑定 */
    fun invalidate(network: Network) {
        synchronized(networkLock) {
            if (boundNetwork.get() != network) return
            release()
            log("WiFi: 原绑定网络已失效，下一轮重新入网")
        }
    }

    private val boundNetwork = AtomicReference<Network?>(null)

    /** 专属网络断开回调，供会话提示热点丢失 */
    @Volatile
    var onNetworkLost: ((ssid: String) -> Unit)? = null

    /**
     * 当前绑定网络的网关地址（车机热点场景 = 车机 IP）。
     * mDNS 发现失败时的兜底路径。
     */
    fun gatewayAddress(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    /** 当前绑定网络的本机 IPv4，供过滤其他网段的 mDNS 结果 */
    fun localIpv4Address(): String? {
        val network = boundNetwork.get() ?: return null
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.firstOrNull { it.contains('.') && !it.contains(':') }
                ?.substringBefore('/')
        } catch (_: Exception) {
            null
        }
    }

    /** 当前绑定网络的本机 IPv6 地址，供 mDNS 网段检查 */
    fun localIpv6Addresses(): List<String> {
        val network = boundNetwork.get() ?: return emptyList()
        return try {
            connectivityManager
                .getLinkProperties(network)
                ?.linkAddresses
                ?.mapNotNull { it.address.hostAddress }
                ?.filter { it.contains(':') }
                ?.map { it.substringBefore('%').substringBefore('/') }
                .orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 通过 WifiNetworkSpecifier 加入热点并绑定进程，每 200ms 检查取消
     * 安全类型参考 xcertplay：0 开放、1 WEP、2 WPA/WPA2、3 WPA3 过渡、4 WPA3
     * WEP 返回 Unsupported，由调用方使用手动入网
     */
    fun joinCarPlayNetwork(
        ssid: String,
        passphrase: String,
        securityType: Int,
        timeoutMillis: Long = 120_000L,
        isCancelled: () -> Boolean = { false },
    ): JoinResult {
        val specifier =
            buildSpecifier(ssid, passphrase, securityType)
                ?: return JoinResult.Unsupported
        val ticket =
            synchronized(networkLock) {
                release()
                requests.current
            }
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
        val latch = CountDownLatch(1)
        val result = AtomicReference<Network?>(null)
        val unavailable = AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (activate(ticket, network, "应用专属热点", ssid)) {
                        log("WiFi: 应用专属网络已到达，等待接口与地址就绪")
                        val link = connectivityManager.getLinkProperties(network)
                        if (!link?.interfaceName.isNullOrEmpty() && !link.linkAddresses.isNullOrEmpty()) {
                            result.set(network)
                            latch.countDown()
                        }
                    } else {
                        unavailable.set(true)
                        log("WiFi: 绑定失败或请求已取消，不能报告连接成功")
                    }
                    if (unavailable.get()) latch.countDown()
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: android.net.LinkProperties,
                ) {
                    if (requests.owns(ticket) &&
                        boundNetwork.get() == network &&
                        !linkProperties.interfaceName.isNullOrEmpty() &&
                        linkProperties.linkAddresses.isNotEmpty()
                    ) {
                        result.set(network)
                        latch.countDown()
                    }
                }

                override fun onUnavailable() {
                    unavailable.set(true)
                    latch.countDown()
                    if (requests.owns(ticket)) log("WiFi: 系统未允许加入热点，可重试或使用手动兼容连接")
                }

                override fun onLost(network: Network) {
                    lost(ticket, network, ssid)
                }
            }
        val accepted =
            try {
                synchronized(networkLock) {
                    if (!requests.owns(ticket)) {
                        false
                    } else {
                        callback.set(newCallback)
                        connectivityManager.requestNetwork(request, newCallback)
                        true
                    }
                }
            } catch (error: Exception) {
                log("WiFi: 发起应用专属网络请求失败（${error.message}）——可能需要位置权限或系统不支持")
                false
            }
        if (!accepted) {
            release(ticket)
            return JoinResult.Unavailable
        }
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled() || !requests.owns(ticket)) {
                release(ticket)
                return JoinResult.Unavailable
            }
            try {
                if (latch.await(200, TimeUnit.MILLISECONDS)) break
            } catch (_: InterruptedException) {
                release(ticket)
                return JoinResult.Unavailable
            }
        }
        val network = result.get()
        if (network == null || !requests.owns(ticket) || boundNetwork.get() != network || isCancelled()) {
            val reason =
                when {
                    isCancelled() || !requests.owns(ticket) -> "网络请求已取消或失效"
                    unavailable.get() -> "系统已判定本次入网请求不可用"
                    network != null -> "网络绑定已失效"
                    else -> "等待加入热点超时（${timeoutMillis / 1000}s），系统确认框可能仍在前台"
                }
            log("WiFi: $reason")
            release(ticket)
            return JoinResult.Unavailable
        }
        return JoinResult.Joined(network)
    }

    /** 构造 specifier；WEP 或参数不合法返回 null（走旧路径）。 */
    private fun buildSpecifier(
        ssid: String,
        passphrase: String,
        securityType: Int,
    ): WifiNetworkSpecifier? {
        return try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            when (securityType) {
                // 开放热点：无密码
                0 -> Unit
                // WEP：WifiNetworkSpecifier 无对应 API（现代车机不再使用）
                1 -> return null
                // WPA/WPA2 与 WPA3 过渡模式：过渡模式 AP 同时接受 WPA2-PSK
                2, 3 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
                // WPA3 仅
                4 -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa3Passphrase(passphrase)
                }
                // 未知类型：按有密码的 WPA2 尝试（比直接放弃更可能成功）
                else -> {
                    if (passphrase.isEmpty()) return null
                    builder.setWpa2Passphrase(passphrase)
                }
            }
            builder.build()
        } catch (error: Exception) {
            log("WiFi: 构造应用专属网络参数失败（${error.message}）")
            null
        }
    }

    /** 观察并绑定已连接 WiFi，调用方在绑定前后检查目标热点身份，不主动切换网络 */
    fun bindCurrentWifi(
        expectedSsid: String,
        onResult: (Boolean) -> Unit = {},
    ) {
        val ticket =
            synchronized(networkLock) {
                release()
                requests.current
            }
        // 多路 Wi-Fi 必须明确匹配目标；系统隐藏名称时只允许复用唯一的实际 Wi-Fi
        val observedNetworks =
            runCatching {
                connectivityManager.existingNetworks().mapNotNull { network ->
                    val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                    val ssid =
                        (caps.transportInfo as? WifiInfo)
                            ?.ssid
                            ?.removeSurrounding("\"")
                            ?.takeUnless { it.isEmpty() || it.equals("<unknown ssid>", ignoreCase = true) }
                    network to ssid
                }
            }.getOrDefault(emptyList())
        val expectedNetwork =
            observedNetworks.filter { it.second == expectedSsid }.singleOrNull()?.first
                ?: observedNetworks.singleOrNull()?.takeIf { it.second == null }?.first
        if (expectedNetwork == null) {
            log("WiFi: 无法唯一确认目标对应的系统网络，跳过当前网络复用")
            release(ticket)
            onResult(false)
            return
        }
        val request =
            NetworkRequest
                .Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        // 网络回调线程与 Bg 看门狗线程并发读写——原子标志保证可见性与单次投递
        val reported =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val newCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(networkLock) {
                        if (!requests.owns(ticket) || reported.get() || network != expectedNetwork) return
                        val bound = activate(ticket, network, "系统级热点（手动兼容）", expectedSsid)
                        if (reported.compareAndSet(false, true)) onResult(bound)
                        if (!bound) release(ticket)
                    }
                }

                override fun onUnavailable() {
                    synchronized(networkLock) {
                        if (!requests.owns(ticket)) return
                        if (reported.compareAndSet(false, true)) onResult(false)
                        release(ticket)
                    }
                }

                override fun onLost(network: Network) {
                    lost(ticket, network, "手动热点")
                }
            }
        try {
            synchronized(networkLock) {
                if (!requests.owns(ticket)) return
                callback.set(newCallback)
                connectivityManager.registerNetworkCallback(request, newCallback)
            }
        } catch (error: Exception) {
            synchronized(networkLock) {
                if (requests.owns(ticket)) {
                    if (reported.compareAndSet(false, true)) onResult(false)
                    release(ticket)
                }
            }
            log("WiFi: 请求手动兼容连接失败：${error.message}")
            return
        }
        // 无实际 Wi-Fi 时继续观察系统连接，五秒只作提示
        // 最终等待时限和取消后的释放由调用方负责
        Bg.run("wifi-bind-watchdog") {
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return@run
            }
            if (requests.owns(ticket) && !reported.get()) {
                log("WiFi: 5 秒尚未绑定（手机可能还没连上热点）——请到 设置>WLAN 加入接收端热点，加入后本轮绑定会自动完成")
            }
        }
    }

    /**
     * 撤销请求前先使回调失效，旧超时不能释放后来建立的连接
     */
    fun release(expectedTicket: Long? = null) {
        synchronized(networkLock) {
            if (expectedTicket != null && !requests.owns(expectedTicket)) return
            requests.advance()
            callback.getAndSet(null)?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
            if (boundNetwork.getAndSet(null) != null) runCatching { connectivityManager.bindProcessToNetwork(null) }
            connectionMode = "未连接"
            boundSsid = null
        }
    }
}
