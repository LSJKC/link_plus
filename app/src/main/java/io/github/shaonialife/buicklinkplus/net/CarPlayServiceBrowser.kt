package io.github.shaonialife.buicklinkplus.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/** 在车机热点发现 _airplay._tcp，解析端点和 TXT 能力信息 */
class CarPlayServiceBrowser(
    context: Context,
    private val log: (String) -> Unit,
    /** 只缓存并上报当前车机网段的端点 */
    private val accept: (NsdServiceInfo) -> Boolean = { true },
    private val onEndpoint: (NsdServiceInfo) -> Unit = {},
) {
    companion object {
        const val AIRPLAY_TYPE = "_airplay._tcp"
        const val CARPLAY_CTRL_TYPE = "_carplay-ctrl._tcp"
        private const val TAG = "NSD"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discovering = false

    /** 已解析的 AirPlay 服务缓存，入网后提前发现，配对时直接取用 */
    @Volatile
    private var resolved: NsdServiceInfo? = null

    /** 新会话开始时清掉上一轮缓存（车机可能换了地址/端口）。 */
    fun resetCache() {
        resolved = null
    }

    /** 等待发现缓存，超时返回 null */
    fun awaitEndpoint(timeoutMillis: Long): NsdServiceInfo? {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMillis
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            resolved?.let { return it }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return resolved
            }
        }
        return resolved
    }

    fun start(serviceType: String = AIRPLAY_TYPE) {
        if (discovering) {
            log("$TAG: 已在扫描中")
            return
        }
        log("$TAG: 开始发现 $serviceType")
        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        discovering = true
    }

    fun stop() {
        if (!discovering) return
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        discovering = false
        log("$TAG: 停止发现")
    }

    private val discoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                discovering = false
                log("$TAG: 发现启动失败 code=$errorCode")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int,
            ) {
                log("$TAG: 停止失败 code=$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                log("$TAG: 发现服务 ${serviceInfo.serviceName} (${serviceInfo.serviceType})，解析中…")
                resolveWithRetry(serviceInfo, attempts = 3)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                log("$TAG: 服务消失 ${serviceInfo.serviceName}")
            }
        }

    private fun resolveWithRetry(
        serviceInfo: NsdServiceInfo,
        attempts: Int,
    ) {
        val listener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    info: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && attempts > 1) {
                        Thread {
                            Thread.sleep(200)
                            resolveWithRetry(info, attempts - 1)
                        }.start()
                    } else {
                        log("$TAG: 解析失败 ${info.serviceName} code=$errorCode")
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.compatHost()?.hostAddress ?: "no-host"
                    val txt =
                        info.attributes.entries.joinToString(", ") { (key, value) ->
                            val text =
                                value
                                    ?.toString(Charsets.UTF_8)
                                    ?.trimEnd { it.code == 0 }
                                    .orEmpty()
                            "$key=$text"
                        }
                    // 先检查再缓存，避免把同网段的其他 AirPlay 设备当作车机
                    val accepted = accept(info)
                    log("$TAG: OK ${info.serviceName} -> $host:${info.port}${if (accepted) "" else "（非车机，忽略）"}")
                    if (txt.isNotEmpty()) log("$TAG: TXT $txt")
                    if (accepted) resolved = info
                    onEndpoint(info)
                }
            }
        // Android 12 和 13 仍需旧解析入口，按队列串行且在停止时拒绝旧结果
        @Suppress("DEPRECATION")
        nsdManager.resolveService(serviceInfo, listener)
    }
}
