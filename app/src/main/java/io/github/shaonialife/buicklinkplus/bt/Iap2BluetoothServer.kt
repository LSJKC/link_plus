package io.github.shaonialife.buicklinkplus.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import io.github.shaonialife.buicklinkplus.transport.DuplexStream
import io.github.shaonialife.buicklinkplus.transport.Iap2LinkChannel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 监听手机端 iAP2 RFCOMM 服务，等待车机回连
 * 连接后执行链路协商和发送端握手，通过 [onEndpoint] 返回热点和 AirPlay 端点
 */
class Iap2BluetoothServer(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 收到 0x5703 后立即加入热点并广播 ctrl，等待车机下发 0x4301 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** 迟到的 0x4301 交给主控更新端点 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：拒绝非目标设备的连入；null=来者不拒）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 入站连接也报告实际握手阶段 */
    private val onPhase: (String) -> Unit = {},
) {
    companion object {
        /** 手机端 iAP2 UUID，来源 Oligo Security，与 xcertplay 一致；车机端以 caff 结尾 */
        val IPHONE_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
        private const val SDP_NAME = "iAP2"
        private const val SESSION_TIMEOUT_MILLIS = 60_000L

        private const val HANDOVER_TIMEOUT_MILLIS = 20_000L

        // 逻辑会话可以退役，但物理关闭额度只能在 close 返回后归还
        private val socketBudget = java.util.concurrent.Semaphore(6)
        private val closingSockets =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<BluetoothSocket, Boolean>()),
            )
        private val closeExecutor =
            java.util.concurrent.ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(6),
                { task -> Thread(task, "iap2-bt-inbound-close").apply { isDaemon = true } },
            )

        /** 使用有界关闭池，避免阻塞 accept 或积压 socket */
        private fun closeSocketAsync(socket: BluetoothSocket) {
            if (!closingSockets.add(socket)) return
            closeExecutor.execute {
                try {
                    socket.close()
                } catch (_: IOException) {
                    // 对端已关链仍需归还实际套接字额度
                } finally {
                    socketBudget.release()
                }
            }
        }
    }

    private val serverSocket = AtomicReference<BluetoothServerSocket?>(null)
    private val activeConnection = AtomicReference<InboundConnection?>(null)
    private val generation = AtomicLong(0)

    /** 逻辑退役后仍等待旧写入退出，再开放新通道 */
    private class InboundConnection(
        val socket: BluetoothSocket,
        val lease: BluetoothSessionArbiter.Lease,
    ) {
        private val closing = AtomicBoolean(false)
        val isOpen: Boolean get() = !closing.get() && lease.isCurrent

        /** 不在界面线程或链路锁内等待系统关闭 */
        fun close() {
            if (!closing.compareAndSet(false, true)) return
            lease.close()
            closeSocketAsync(socket)
        }
    }

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log("BT: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT: 蓝牙不可用或未开启")
            return false
        }
        return try {
            val listener = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, IPHONE_IAP2_UUID)
            serverSocket.set(listener)
            running = true
            val myGeneration = generation.incrementAndGet()
            log("BT: 监听 $SDP_NAME，等待接收端回连…")
            Thread({ acceptLoop(listener, myGeneration) }, "iap2-bt-accept").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: SecurityException) {
            log("BT: 蓝牙权限已撤销，停止监听")
            false
        } catch (error: IOException) {
            log("BT: 监听失败 - ${error.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun acceptLoop(
        listener: BluetoothServerSocket,
        myGeneration: Long,
    ) {
        var waitingForSocketBudget = false
        while (running && generation.get() == myGeneration) {
            if (!socketBudget.tryAcquire()) {
                if (!waitingForSocketBudget) log("BT: 旧套接字关闭尚未结束，暂停接受新连接以限制资源占用")
                waitingForSocketBudget = true
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }
            waitingForSocketBudget = false
            val socket: BluetoothSocket =
                try {
                    listener.accept()
                } catch (error: IOException) {
                    socketBudget.release()
                    if (running && generation.get() == myGeneration) log("BT: accept 失败 - ${error.message}")
                    break
                }
            val peerName =
                try {
                    socket.remoteDevice?.name
                } catch (_: SecurityException) {
                    null
                } ?: "unknown"
            // 指定了目标车机时拒绝其它设备的连入（用户配对了多台车机：非目标车机
            // 的控制器也会周期回连，放行会抢占/误建会话）。地址比对，大小写不敏感。
            val target = targetProvider()
            val peerAddress =
                try {
                    socket.remoteDevice?.address
                } catch (_: SecurityException) {
                    null
                }
            if (target != null && !TargetDevicePrefs.matches(target, peerAddress)) {
                log(
                    "BT: 忽略非目标设备连入 name=$peerName addr=${peerAddress ?: "?"}" +
                        "（当前目标：「${target.name}」）",
                )
                closeSocketAsync(socket)
                continue
            }
            // 在启动工作线程前完成原子占用，不能先记“已连入”再异步设置布尔标记
            val connection =
                synchronized(this) {
                    if (!running || generation.get() != myGeneration) {
                        null
                    } else {
                        BtSessionGate.acquireInbound()?.let { lease ->
                            InboundConnection(socket, lease).also { activeConnection.set(it) }
                        }
                    }
                }
            if (connection == null) {
                log("BT: 当前控制通道正在连接或运行，关闭本次重复连入，保留现有通道")
                closeSocketAsync(socket)
                continue
            }
            log("BT: 接收端已连入 name=$peerName，开始 iAP2 发送端会话")
            Thread({ runSession(connection, peerAddress, myGeneration) }, "iap2-bt-session").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun runSession(
        connection: InboundConnection,
        address: String?,
        myGeneration: Long,
    ) {
        var channel: Iap2LinkChannel? = null
        var attemptedHandshake: BluetoothHandshakeRecovery.Attempt? = null
        try {
            if (!connection.lease.awaitQuiescent(HANDOVER_TIMEOUT_MILLIS)) {
                throw IOException("上一条蓝牙控制操作尚未退出，未启动新的回连协议")
            }
            val attempt =
                synchronized(this) {
                    if (!isCurrent(connection, myGeneration)) return
                    address?.let { BtHandshakeRecovery.attempts.attempt(it) }
                }
            attemptedHandshake = attempt
            val handshakeMode = attempt?.mode ?: BluetoothHandshakeRecovery.Mode.ACTIVE
            val socket = connection.socket
            val stream =
                object : DuplexStream {
                    override fun read(buffer: ByteArray): Int {
                        connection.lease.checkCurrent()
                        val count = socket.inputStream.read(buffer)
                        connection.lease.checkCurrent()
                        return count
                    }

                    override fun write(bytes: ByteArray) {
                        connection.lease.withOwnership {
                            socket.outputStream.write(bytes)
                            socket.outputStream.flush()
                        }
                    }

                    override fun close() = connection.close()
                }
            log("BT: iAP2 开场=${handshakeMode.title} addr=${address ?: "?"}")
            val currentChannel =
                Iap2LinkChannel(
                    stream,
                    name = "iap2-bt",
                    allowPeerRestart = true,
                    respondToPeerHandshake = handshakeMode == BluetoothHandshakeRecovery.Mode.RESPONDER,
                ) { line -> log(line) }
            channel = currentChannel
            if (!isCurrent(connection, myGeneration)) return
            onPhase("车机蓝牙通道已连入，正在协商连接…")
            currentChannel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                { if (isCurrent(connection, myGeneration)) onEndpoint(it) },
                { if (isCurrent(connection, myGeneration)) onWifiCredentials(it) },
                { if (isCurrent(connection, myGeneration)) onEndpointUpdate(it) },
                { if (isCurrent(connection, myGeneration)) onPhase(it) },
                challengeRecovery = address?.let { BtMfiChallengeRecovery.forTarget(it) },
                isCurrent = { isCurrentOperation(connection, myGeneration) },
            )
        } catch (error: IOException) {
            log("SESSION 失败: ${error.message}")
            // 先读通道状态再取生命周期锁，阻塞写入时仍允许 stop 发起套接字关闭
            val silentTimeout = channel?.silentNegotiationTimedOut == true
            val attempt = attemptedHandshake
            synchronized(this) {
                if (isCurrent(connection, myGeneration) &&
                    !Thread.currentThread().isInterrupted &&
                    silentTimeout &&
                    address != null &&
                    attempt != null
                ) {
                    BtHandshakeRecovery.attempts.recordSilence(address, attempt)?.let { nextMode ->
                        log("BT: 本轮初始协商零接收，下一轮仅对该目标切换${nextMode.title}")
                    }
                }
            }
            if (isCurrentOperation(connection, myGeneration)) onPhase("iAP2 会话失败：${error.message ?: "原因见日志"}")
        } finally {
            val attempt = attemptedHandshake
            if (address != null && attempt != null && BtHandshakeRecovery.attempts.finishAttempt(address, attempt)) {
                log("BT: 本轮应答握手已结束，后续恢复默认主动握手")
            }
            activeConnection.compareAndSet(connection, null)
            connection.close()
            channel?.close()
            log("SESSION 结束，等待接收端重连（BT 监听仍在运行）")
        }
    }

    /** 取消或重启监听后，旧工作线程不能交付端点、热点、状态或兼容结果 */
    private fun isCurrent(
        connection: InboundConnection,
        myGeneration: Long,
    ): Boolean = isCurrentOperation(connection, myGeneration) && connection.isOpen

    /** 物理传输结束不等于用户取消，当前操作仍需报告真实失败及临时换档 */
    private fun isCurrentOperation(
        connection: InboundConnection,
        myGeneration: Long,
    ): Boolean = running && generation.get() == myGeneration && activeConnection.get() === connection && connection.lease.isLatest

    /** 结束当前蓝牙控制会话，保留监听 */
    @Synchronized
    fun detachCurrentSession() {
        activeConnection.getAndSet(null)?.let { connection ->
            connection.close()
            log("BT: 已主动断开当前控制会话（模拟 tunnel handoff 后的退役）")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        generation.incrementAndGet()
        detachCurrentSession()
        serverSocket.getAndSet(null)?.let { runCatching { it.close() } }
        log("BT: 监听已停止")
    }
}
