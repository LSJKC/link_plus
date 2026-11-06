package io.github.shaonialife.buicklinkplus.shizuku

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import io.github.shaonialife.buicklinkplus.util.DebugFileLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 发现本机无线调试服务，拒绝将局域网其他设备用于配对或激活
 */
internal object AdbDiscovery {
    data class Endpoint(
        val host: String,
        val port: Int,
    )

    /**
     * 后台限时发现本机服务，不连接扫描到的其他设备
     */
    @Suppress("DEPRECATION")
    fun find(
        context: Context,
        pairing: Boolean = false,
    ): Endpoint? {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        val done = CountDownLatch(1)
        val closed = AtomicBoolean()
        val resolving = AtomicBoolean()
        val result = AtomicReference<Endpoint?>()
        val queue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val diagnostics = ConcurrentLinkedQueue<String>()
        val started = SystemClock.elapsedRealtime()
        val resolver =
            object {
                /**
                 * 旧系统一次只能解析一个服务，其余候选必须排队，不能丢掉本机广播
                 */
                fun next() {
                    if (closed.get() || !resolving.compareAndSet(false, true)) return
                    val info = queue.poll()
                    if (info == null) {
                        resolving.set(false)
                        if (queue.isNotEmpty()) next()
                        return
                    }
                    try {
                        nsd.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    service: NsdServiceInfo,
                                    code: Int,
                                ) {
                                    diagnostics.add("解析失败=$code")
                                    finish()
                                }

                                override fun onServiceResolved(service: NsdServiceInfo) {
                                    try {
                                        if (closed.get()) return
                                        val host =
                                            service.host?.hostAddress ?: run {
                                                diagnostics.add("解析未返回地址")
                                                return
                                            }
                                        val local =
                                            runCatching { EmbeddedAdb.validateHost(host) }.getOrNull() ?: run {
                                                diagnostics.add("已过滤非本机或无效地址")
                                                return
                                            }
                                        if (service.port !in 1..65535) return
                                        result.set(Endpoint(local, service.port))
                                        done.countDown()
                                    } finally {
                                        finish()
                                    }
                                }
                            },
                        )
                    } catch (error: Exception) {
                        diagnostics.add("解析异常=${error.javaClass.simpleName}")
                        finish()
                    }
                }

                /**
                 * 当前候选结束后继续检查剩余服务
                 */
                fun finish() {
                    resolving.set(false)
                    next()
                }
            }
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit

                override fun onDiscoveryStopped(type: String) = Unit

                override fun onServiceLost(info: NsdServiceInfo) = Unit

                override fun onStartDiscoveryFailed(
                    type: String,
                    code: Int,
                ) {
                    diagnostics.add("发现启动失败=$code")
                    done.countDown()
                }

                override fun onStopDiscoveryFailed(
                    type: String,
                    code: Int,
                ) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (closed.get() || queue.size >= 64) return
                    queue.add(info)
                    resolver.next()
                }
            }
        try {
            nsd.discoverServices(if (pairing) "_adb-tls-pairing._tcp." else "_adb-tls-connect._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            done.await(5, TimeUnit.SECONDS)
        } finally {
            closed.set(true)
            runCatching { nsd.stopServiceDiscovery(listener) }
            DebugFileLog.append(
                context.applicationContext,
                "PAIRING-NSD: 用途=${if (pairing) "配对" else "连接"} 耗时=${SystemClock.elapsedRealtime() - started}ms " +
                    "本机端口=${result.get()?.port ?: "未发现"} 诊断=${diagnostics.take(8)}",
            )
        }
        return result.get()
    }
}
