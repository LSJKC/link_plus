package io.github.shaonialife.buicklinkplus.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.util.Bg
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * 广播 _carplay-ctrl._tcp 并响应车机的 ctrl-int 邀请，探测返回 HTTP 200
 * 交互参考 xcertplay CarPlayBonjour
 */
class CarPlayCtrlAdvertiser(
    private val context: Context,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SERVICE_TYPE = "_carplay-ctrl._tcp"

        /** ctrl TXT 版本保持 950.7.1，避免车机跳过探测 */
        private const val SENDER_SRCVERS = "950.7.1"

        /** ctrl 服务实例名，保持 sender 兼容现有车机 */
        private const val SERVICE_NAME = "sender"

        /** 供测试检查 ctrl 名称和版本 */
        internal fun advertisedSrcversForTest(): String = SENDER_SRCVERS

        internal fun advertisedServiceNameForTest(): String = SERVICE_NAME

        /** 当前广播的服务类型，只有 ctrl */
        internal fun advertisedServiceTypesForTest(): List<String> = listOf(SERVICE_TYPE)

        private const val TAG = "CTRL"
        private const val MAX_HEAD_BYTES = 16 * 1024

        /** 同一 SSID 的强制重注册冷却（见 ensureForNetwork）。 */
        private const val FORCE_COOLDOWN_MILLIS = 2_000L
    }

    private var serverSocket: ServerSocket? = null
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var registered = false

    /** ctrl 邀请计数，收到邀请即触发 AirPlay 连接 */
    @Volatile
    var inviteCount: Int = 0
        private set

    /** 新一轮连接开始时清零（避免把上一轮的邀请当成本轮信号）。 */
    fun resetInviteCount() {
        inviteCount = 0
    }

    /** 注册时的 WiFi SSID：换网（2.4G→5G 或车机热点切换）后旧注册不可见，需重新注册。 */
    private var registeredSsid: String? = null

    /** 当前生效的注册监听器（每次 start 换新实例，避免 already-in-use）。 */
    private var activeListener: NsdManager.RegistrationListener? = null

    /** 本次注册时刻（force 冷却判定用）。 */
    @Volatile
    private var registeredAtMillis: Long = 0L

    /** 本次注册写入 TXT 的 MAC（占位值 = 车机不会邀请，需尽快重注册）。 */
    @Volatile
    private var registeredWithMac: String? = null

    /** 本次注册时进程所处的网络（用于判断「绑定后是否需要重注册」）。 */
    private var registeredNetwork: android.net.Network? = null

    val port: Int get() = serverSocket?.localPort ?: -1

    /** 当前广播的 TXT id 是否是占位 MAC（车机据此匹配手机，占位则永不邀请）。 */
    val macPlaceholder: Boolean
        get() = registeredWithMac == DeviceIdentity.PLACEHOLDER_MAC

    /**
     * 热点或绑定网络变化后重新注册 ctrl，确保广播使用当前网络
     *
     * @param force 是否跳过冷却强制注册
     * @param network 当前绑定网络，变化时必须重注册
     */
    @Synchronized
    fun ensureForNetwork(
        ssid: String,
        force: Boolean = false,
        network: android.net.Network? = null,
    ): Boolean {
        if (registered && registeredSsid == ssid && !force && !macPlaceholder) return true
        // 短时间内合并重复注册，避免撞上异步注销
        // MAC 还是占位值或网络变化时仍要立即更新
        val sameNetwork = network == null || network == registeredNetwork
        if (force &&
            registered &&
            registeredSsid == ssid &&
            !macPlaceholder &&
            sameNetwork &&
            SystemClock.uptimeMillis() - registeredAtMillis < FORCE_COOLDOWN_MILLIS
        ) {
            log("$TAG: 跳过重复的强制重注册（${SystemClock.uptimeMillis() - registeredAtMillis}ms 前刚注册）")
            return true
        }
        if (registered) {
            log("$TAG: 重新注册 mDNS 广播（${registeredSsid ?: "?"}${if (force) "，绑定后时机修正" else " → $ssid"}）")
            stop()
        }
        registeredSsid = ssid
        registeredNetwork = network ?: registeredNetwork
        var ok = start()
        if (!ok) {
            // 注销是异步的，遇到 listener 占用时稍后重试一次
            try {
                Thread.sleep(1_200)
            } catch (_: InterruptedException) {
            }
            ok = start()
        }
        return ok
    }

    @Synchronized
    fun start(): Boolean {
        if (registered) return true
        return try {
            val socket = ServerSocket(0)
            serverSocket = socket
            // 占位 MAC 时强制重试一次（多数机型 sysfs/Settings.Secure 可直读；
            // 仍失败则提示用户手填；广播内容错了车机就永远不会邀请我们）
            var mac = DeviceIdentity.bluetoothMac(context)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
                log(
                    "$TAG: 蓝牙 MAC 解析为占位值，已强制重试${if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（仍未成功——车机可能不会邀请，请在手机设置「连接与设备」里手填本机蓝牙地址）" else "成功"}",
                )
            }
            val info =
                NsdServiceInfo().apply {
                    serviceName = SERVICE_NAME
                    serviceType = SERVICE_TYPE
                    setPort(socket.localPort)
                    // TXT id = 本机真实蓝牙 MAC（catplay CarPlayInviter 实证：车机按此
                    // 匹配待邀请的手机，占位值会导致车机永远不邀请 → 0x4301 永不到达）。
                    setAttribute("id", mac)
                    setAttribute("srcvers", SENDER_SRCVERS)
                }
            // 每次注册使用新 listener，避免与尚未完成的注销冲突
            val freshListener = createListener()
            activeListener = freshListener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, freshListener)
            registered = true
            registeredAtMillis = SystemClock.uptimeMillis()
            registeredWithMac = mac
            // 记录广播中的身份字段，方便排查车机为何没有发出邀请
            log(
                "$TAG: 已监听 :${socket.localPort}，广播 $SERVICE_TYPE " +
                    "name=$SERVICE_NAME id=$mac srcvers=$SENDER_SRCVERS" +
                    if (mac == DeviceIdentity.PLACEHOLDER_MAC) "（⚠ 占位 MAC：车机不会邀请）" else "",
            )
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
                // 身份解析重试拿到真实 MAC 后重新注册，替换广播里的占位值
                Bg.run("ctrl-mac-repair") { repairPlaceholderMac() }
            }
            Thread(::acceptLoop, "ctrl-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("$TAG: 启动失败 - ${error.message}")
            false
        }
    }

    /** 占位 MAC 的自我修复：每 2s 重试解析，成功后按当前 SSID 重注册一次。 */
    private fun repairPlaceholderMac() {
        repeat(15) {
            try {
                Thread.sleep(2_000)
            } catch (_: InterruptedException) {
                return
            }
            if (!registered || !macPlaceholder) return
            val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
            if (mac == DeviceIdentity.PLACEHOLDER_MAC) return@repeat
            log("$TAG: 蓝牙 MAC 已解析为 $mac——重新注册 ctrl 广播（修复占位 id）")
            ensureForNetwork(registeredSsid ?: "", force = true)
            return
        }
        log("$TAG: 30s 内未能解析真实蓝牙 MAC——ctrl 广播 id 仍是占位值，车机可能不会邀请（请在手机设置「连接与设备」里手填本机蓝牙地址）")
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (registered) {
            val client =
                try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
            Bg.run("ctrl-client") { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: "?"
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val head = readUntilHeaderEnd(input)
            if (head.isEmpty()) {
                log("$TAG: $peer 连入但无请求（EOF）")
                return
            }
            val requestLine = head.lineSequence().firstOrNull() ?: ""
            val headers =
                head
                    .substringAfter("\r\n", "")
                    .lineSequence()
                    .filter { it.contains(':') }
                    .joinToString("; ") { it.trim() }
            log("$TAG: $peer -> [$requestLine] $headers")

            // 车机 ctrl 邀请：这是「车机已发现手机、AirPlay 窗口已开」的权威信号
            // （catplay CarPlayCtrlSession：收到即把邀请交给连接流程）。
            if (requestLine.startsWith("GET /ctrl-int/1/connect")) {
                val receiverId =
                    head
                        .lineSequence()
                        .firstOrNull {
                            it.substringBefore(':').trim().equals("AirPlay-Receiver-Device-ID", ignoreCase = true)
                        }?.substringAfter(':')
                        ?.trim()
                inviteCount++
                log("$TAG: ✓ 车机 ctrl 邀请（第 $inviteCount 次，receiver=${receiverId ?: "?"}）——AirPlay 窗口已开启")
            }

            val output = client.getOutputStream()
            val response =
                when {
                    requestLine.startsWith("GET /ctrl-int/") -> {
                        log("$TAG: ✓ 应答 ctrl-int 探测 200 OK")
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }

                    else -> {
                        log("$TAG: 未识别的请求，按 200 空应答处理（后续实现按需细化）")
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                }
            output.write(response.toByteArray(Charsets.US_ASCII))
            output.flush()

            // 对端 Connection: close 会自行断开；等待期间若还有字节（隧道/事件），转储出来
            client.soTimeout = 3_000
            val rest = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    rest.write(buffer, 0, count)
                    if (rest.size() > 64 * 1024) break
                }
            } catch (_: IOException) {
                // 读超时即停止转储
            }
            if (rest.size() > 0) {
                log(
                    "$TAG: 应答后收到 ${rest.size()} 字节：" +
                        rest.toByteArray().take(64).joinToString(" ") {
                            "%02x".format(it.toInt() and 0xFF)
                        },
                )
            }
            log("$TAG: $peer 连接结束")
        } catch (error: IOException) {
            log("$TAG: $peer 连接异常 - ${error.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val count =
                try {
                    input.read(chunk)
                } catch (_: IOException) {
                    break
                }
            if (count < 0) break
            buffer.write(chunk, 0, count)
            val text = buffer.toString("ISO-8859-1")
            if (text.contains("\r\n\r\n")) return text
        }
        return buffer.toString("ISO-8859-1")
    }

    @Synchronized
    fun stop() {
        registered = false
        registeredSsid = null
        registeredWithMac = null
        registeredNetwork = null
        activeListener?.let { runCatching { nsdManager.unregisterService(it) } }
        activeListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        log("$TAG: 已停止")
    }

    /** 每次注册新建 listener（见 start() 注释；复用同一实例会触发 already in use）。 */
    private fun createListener(): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log("$TAG: mDNS 注册成功 ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) {
                // 重置状态：下一次 ensureForNetwork（新会话的 0x5703）会重新注册
                registered = false
                log("$TAG: mDNS 注册失败 code=$errorCode（下次会话自动重试）")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

            override fun onUnregistrationFailed(
                serviceInfo: NsdServiceInfo,
                errorCode: Int,
            ) = Unit
        }
}
