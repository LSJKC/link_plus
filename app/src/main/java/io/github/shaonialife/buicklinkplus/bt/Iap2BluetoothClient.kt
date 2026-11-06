package io.github.shaonialife.buicklinkplus.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import io.github.shaonialife.buicklinkplus.transport.DuplexStream
import io.github.shaonialife.buicklinkplus.transport.Iap2LinkChannel
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 主动连接车机 iAP2 RFCOMM 服务，复用 [Iap2SenderSession] 完成握手
 * 与车机回连共用占用凭据，成功后通过 [onEndpoint] 返回热点和 AirPlay 端点
 * 连接方向参考 carplay-wifi-extractor
 */
class Iap2BluetoothClient(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 收到 0x5703 后立即加入热点并广播 ctrl，等待车机下发 0x4301 */
    private val onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit = {},
    /** 迟到的 0x4301 交给主控更新端点 */
    private val onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit = {},
    /** 目标车机（动态读取：选择后无需重启链路，下一轮尝试即生效）。 */
    private val targetProvider: () -> TargetDevicePrefs.Target? = { null },
    /** 按实际握手阶段更新界面 */
    private val onPhase: (String) -> Unit = {},
) {
    companion object {
        /** 车机 iAP2 服务 UUID 以 caff 结尾，手机端以 cafe 结尾，参考 xcertplay */
        val ACCESSORY_IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

        private const val SESSION_TIMEOUT_MILLIS = 90_000L
        private const val RETRY_DELAY_MILLIS = 3_000L
        private const val FAILURE_LOG_THROTTLE_MILLIS = 15_000L

        /** 「蓝牙已连接→已断开」日志节流（车机可能反复自动重连，避免刷屏）。 */
        private const val PROFILE_LOG_THROTTLE_MILLIS = 15_000L

        /** SDP 服务探测等待窗（fetchUuidsWithSdp → ACTION_UUID 广播）。 */
        private const val SDP_PROBE_TIMEOUT_MILLIS = 2_500L

        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private val connectBudget = BluetoothConnectBudget()

        // 额度在真实关闭结束后释放，即使系统关闭阻塞也不能继续堆积套接字
        private val socketBudget = java.util.concurrent.Semaphore(6)
        private val closingSockets =
            java.util.Collections.synchronizedSet(
                java.util.Collections.newSetFromMap(java.util.WeakHashMap<BluetoothSocket, Boolean>()),
            )
        private val socketLeases =
            java.util.Collections.synchronizedMap(
                java.util.IdentityHashMap<BluetoothSocket, BluetoothSessionArbiter.Lease>(),
            )
        private val closeExecutor =
            java.util.concurrent.ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(6),
                { task -> Thread(task, "iap2-bt-close").apply { isDaemon = true } },
            )

        private val channelCloser =
            java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                java.util.concurrent.ArrayBlockingQueue(6),
                { task -> Thread(task, "iap2-channel-close").apply { isDaemon = true } },
            )

        /** 超时只关闭对应 socket，避免误关新会话 */
        private val connectTimer =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "iap2-bt-timeout").apply { isDaemon = true }
            }
    }

    private var thread: Thread? = null
    private val currentSocket = AtomicReference<BluetoothSocket?>(null)
    private val currentLease = AtomicReference<BluetoothSessionArbiter.Lease?>(null)

    /** start 和 stop 都递增代次，让解除阻塞的旧连接线程退出 */
    private val generation =
        java.util.concurrent.atomic
            .AtomicLong(0)

    @Volatile
    private var activeChannel: Iap2LinkChannel? = null

    @Volatile
    private var running = false

    /** 目标设备不在已配对列表的提示节流（避免每 3 秒刷屏）。 */
    private var lastTargetMissingLogAt = 0L

    /** 「无 iAP2 服务→跳过」日志去重（每地址一次即可，重复轮询不刷屏）。 */
    private val skippedNoService = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 只缓存已发现的服务，探测失败后仍允许重试 */
    private val iap2ServiceCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 先查缓存中的 iAP2 UUID，缺失时发起 SDP 查询并等待 ACTION_UUID
     * 成功结果保留到客户端停止，超时或权限异常只跳过本次尝试
     */
    @SuppressLint("MissingPermission")
    private fun awaitIap2Service(device: BluetoothDevice): Boolean {
        iap2ServiceCache[device.address]?.let { return it }

        fun cachedHas(): Boolean =
            runCatching {
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }.getOrDefault(false)

        if (cachedHas()) {
            iap2ServiceCache[device.address] = true
            return true
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(
                    receiverContext: Context,
                    intent: android.content.Intent,
                ) {
                    @Suppress("DEPRECATION")
                    val hit = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (hit?.address == device.address) latch.countDown()
                }
            }
        val started =
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    android.content.IntentFilter(BluetoothDevice.ACTION_UUID),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                device.fetchUuidsWithSdp()
            }.getOrDefault(false)
        if (started) {
            try {
                latch.await(SDP_PROBE_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        runCatching { context.unregisterReceiver(receiver) }
        val has = cachedHas()
        // 查询超时不代表车机没有服务，避免本轮启动中永久跳过刚打开的服务窗口
        if (has) iap2ServiceCache[device.address] = true
        return has
    }

    /** 「蓝牙已连接→已断开」日志节流时间戳。 */
    private var lastProfileLogAt = 0L

    val isRunning: Boolean get() = running

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission()) {
            log("BT-C: 缺少 BLUETOOTH_CONNECT 权限")
            return false
        }
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            }
        if (adapter == null || !adapter.isEnabled) {
            log("BT-C: 蓝牙不可用或未开启")
            return false
        }
        // 每轮启动重置 SDP 结论与跳过日志（新配对/换固件后重新探测）
        iap2ServiceCache.clear()
        skippedNoService.clear()
        BtHandshakeRecovery.attempts.clear()
        BtMfiChallengeRecovery.clear()
        running = true
        val myGeneration = generation.incrementAndGet()
        val target = targetProvider()
        log(
            "BT-C: 启动——将主动连入车机的无线 iAP2 服务（…caff）" +
                if (target != null) {
                    "，目标：「${target.name}」${target.address}（只连该设备）"
                } else {
                    "（未指定目标：依次尝试全部候选设备，可在「连接准备」中指定）"
                },
        )
        Thread({ connectLoop(myGeneration) }, "iap2-bt-client").apply {
            isDaemon = true
            thread = this
            start()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun connectLoop(myGeneration: Long) {
        val adapter =
            try {
                context.getSystemService(BluetoothManager::class.java)?.adapter
            } catch (_: Exception) {
                null
            } ?: return
        var lastFailureLogAt = 0L
        var consecutiveFailures = 0

        // 记录一次失败；达到阈值只切换到慢速重试并提示一次，不放弃自动重连
        // （强杀后台后车机侧旧会话过期前连不上，停试只会表现为「无法连接」）
        fun registerFailure() {
            consecutiveFailures++
            if (consecutiveFailures == Iap2RetryBackoff.MAX_CONSECUTIVE_FAILURES) {
                log(
                    "BT-C: 连续 $consecutiveFailures 次连接失败——转为慢速重试" +
                        "（每 ${Iap2RetryBackoff.SLOW_RETRY_DELAY / 1000} 秒一次），请确认车机已开机并进入投屏界面",
                )
                onPhase("蓝牙连接连续失败，已转慢速重试；请确认车机状态")
            }
        }
        while (running && generation.get() == myGeneration) {
            // 已有入站连接或 CarPlay 会话时暂停出站探测，避免两条 RFCOMM 互相干扰
            // 会话结束后恢复探测
            if (BtSessionGate.inboundActive || BtSessionGate.sessionActive) {
                sleepQuietly(2_000)
                continue
            }
            val candidates = candidateDevices(adapter)
            if (candidates.isEmpty()) {
                log("BT-C: 没有已配对的蓝牙设备——请先在系统设置里与车机完成蓝牙配对")
                sleepQuietly(RETRY_DELAY_MILLIS * 3)
                continue
            }
            var connected: BluetoothSocket? = null
            for (device in candidates) {
                if (!running || generation.get() != myGeneration) return
                val name =
                    try {
                        device.name
                    } catch (_: SecurityException) {
                        null
                    } ?: device.address
                runCatching { adapter.cancelDiscovery() }
                // 自动模式（未选目标）先过 SDP 闸门：候选=全部已配对设备，耳机/手表
                // 没有 iAP2 服务，连不上还白白被断 HFP/A2DP（设备级断开兜底更会波及
                // 其它在线设备）——确认提供 …caff 才进入「断开+连接」流程。选定目标
                // 的设备不过闸（用户显式指定，保持原路径与时序）。
                if (targetProvider() == null && !awaitIap2Service(device)) {
                    if (skippedNoService.add(device.address)) {
                        log("BT-C: 暂未发现「$name」的 iAP2 服务，保持音频连接，后续重新探测")
                    }
                    continue
                }
                if (!running || generation.get() != myGeneration) return
                val socket = tryConnect(device, myGeneration)
                if (socket != null) {
                    log("BT-C: ✓ 已连入 $name 的无线 iAP2 服务（RFCOMM 建立）")
                    connected = socket
                    break
                } else {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastFailureLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                        lastFailureLogAt = now
                        log("BT-C: 连接 $name 失败，具体原因见连接异常日志，稍后自动重试…")
                    }
                }
            }
            if (connected == null) {
                registerFailure()
                sleepQuietly(Iap2RetryBackoff.nextDelay(consecutiveFailures))
                continue
            }
            if (generation.get() != myGeneration) {
                // stop() 与 connect() 成功竞争：本线程已过期，不建会话
                currentSocket.compareAndSet(connected, null)
                closeSocketAsync(connected, "过期连接")
                return
            }
            if (runSession(connected, myGeneration)) {
                consecutiveFailures = 0
            } else {
                registerFailure()
            }
            sleepQuietly(Iap2RetryBackoff.nextDelay(consecutiveFailures))
        }
        log("BT-C: 客户端已停止")
    }

    /** 指定目标时只返回该车机；否则优先带 iAP2 UUID 的已配对设备，再尝试其余设备 */
    @SuppressLint("MissingPermission")
    private fun candidateDevices(adapter: android.bluetooth.BluetoothAdapter): List<BluetoothDevice> {
        val bonded =
            try {
                adapter.bondedDevices?.toList().orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        targetProvider()?.let { target ->
            // 目标策略：地址命中即唯一候选——不再按 SDP 缓存过滤（缓存可能缺失，
            // 而用户显式指定就该尝试）；目标不在已配对列表时给一次提示并空转等待。
            val hit = bonded.filter { TargetDevicePrefs.matches(target, it.address) }
            if (hit.isEmpty()) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTargetMissingLogAt > FAILURE_LOG_THROTTLE_MILLIS) {
                    lastTargetMissingLogAt = now
                    log(
                        "BT-C: 目标设备「${target.name}」(${target.address}) 不在已配对列表" +
                            "——请到系统蓝牙设置完成配对，或在「连接准备」改选目标",
                    )
                }
                return emptyList()
            }
            return hit
        }
        val withService =
            bonded.filter { device ->
                device.uuids?.any { it.uuid == ACCESSORY_IAP2_UUID } == true
            }
        return if (withService.isNotEmpty()) withService else bonded
    }

    @SuppressLint("MissingPermission")
    private fun tryConnect(
        device: BluetoothDevice,
        myGeneration: Long,
    ): BluetoothSocket? {
        val target = device.address
        val lease = BtSessionGate.acquireOutbound()
        if (lease == null) {
            log("BT-C: 已有控制通道或车机回连正在处理，本轮不叠加出站连接")
            return null
        }
        synchronized(this) {
            if (!running || generation.get() != myGeneration) {
                lease.close()
                return null
            }
            currentLease.set(lease)
        }
        lease.onPreempted {
            val retired =
                synchronized(this) {
                    currentLease.compareAndSet(lease, null)
                    val socket = currentSocket.get()
                    if (socket != null && socketLeases[socket] === lease) {
                        currentSocket.compareAndSet(socket, null)
                        val channel = activeChannel
                        activeChannel = null
                        socket to channel
                    } else {
                        null
                    }
                }
            log("BT-C: 车机已主动回连，撤销尚未协商成功的出站尝试")
            retired?.let { (socket, channel) ->
                closeSocketAsync(socket, "转交车机回连")
                channel?.let { closeChannelAsync(it) }
            }
        }
        if (!connectBudget.acquire(target)) {
            currentLease.compareAndSet(lease, null)
            lease.close()
            log("BT-C: 遗留连接线程尚未结束，暂不叠加同目标连接")
            return null
        }
        if (!socketBudget.tryAcquire()) {
            connectBudget.release(target)
            currentLease.compareAndSet(lease, null)
            lease.close()
            log("BT-C: 套接字关闭尚未完成，本轮不再创建连接")
            return null
        }
        var preparedSocket: BluetoothSocket? = null
        var handedOff = false
        try {
            if (!lease.awaitQuiescent(CONNECT_TIMEOUT_MILLIS)) return null
            // 音频策略也在取得控制通道所有权后执行，避免检查后车机回连又被断开音频
            if (!running || generation.get() != myGeneration) return null
            val name = runCatching { device.name }.getOrNull() ?: target
            val mode = BtConnectionPrefs.get(context, targetProvider()?.address)
            val profiles = lease.withOwnership { BtProfiles.connectedNames(context, target) }
            log("BT-C: 目标=$name 策略=${mode.title} 配置文件=${profiles.joinToString("/").ifEmpty { "无" }}")
            if (mode == BtConnectionPrefs.Mode.PAIR_ONLY) {
                val dropped = lease.withOwnership { BtProfiles.disconnect(context, device) }
                if (dropped.isNotEmpty()) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastProfileLogAt > PROFILE_LOG_THROTTLE_MILLIS) {
                        lastProfileLogAt = now
                        val left = lease.withOwnership { BtProfiles.stillConnected(context, target) }
                        log(
                            "BT-C: 已请求断开 $name 的 ${dropped.joinToString("/")} 连接" +
                                if (left.isEmpty()) "" else "（仍有 ${left.joinToString("/")} 在线）",
                        )
                        log("BT-C: 已按所选策略尝试保持仅配对，实际结果以上述配置文件状态为准")
                    }
                }
            }
            if (!running || generation.get() != myGeneration) return null
            lease.checkCurrent()
            val socket = device.createRfcommSocketToServiceRecord(ACCESSORY_IAP2_UUID)
            preparedSocket = socket
            socketLeases[socket] = lease
            // 连接前就登记：connect() 是阻塞调用（SDP 查询 + 建链最长可达数十秒），
            // stop() 只有能拿到 socket 引用才能 close() 打断它，否则线程会滞留。
            val registered =
                synchronized(this) {
                    if (!running || generation.get() != myGeneration || !lease.isCurrent) {
                        false
                    } else {
                        currentSocket.set(socket)
                        true
                    }
                }
            if (!registered) {
                return null
            }
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val connecting = AtomicBoolean(true)
            val timedOut = AtomicBoolean(false)
            var timeout: java.util.concurrent.ScheduledFuture<*>? = null
            return try {
                timeout =
                    connectTimer.schedule({
                        if (connecting.compareAndSet(true, false)) {
                            timedOut.set(true)
                            log("BT-C: 超时触发 耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms")
                            try {
                                lease.withOwnership {
                                    if (running && generation.get() == myGeneration) {
                                        onPhase("蓝牙通道连接超时，正在重试；可导出日志查看原因")
                                    }
                                }
                            } catch (_: IOException) {
                                // 已被回连接替或用户取消，不回写旧连接提示
                            } finally {
                                closeSocketAsync(socket, "超时")
                            }
                        }
                    }, CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                lease.checkCurrent()
                lease.withOwnership {
                    if (running && generation.get() == myGeneration) onPhase("正在连接车机蓝牙通道…")
                }
                log("BT-C: 开始连接 addr=$target UUID=$ACCESSORY_IAP2_UUID 超时=${CONNECT_TIMEOUT_MILLIS}ms")
                socket.connect()
                if (connectBudget.expired(startedAt, android.os.SystemClock.elapsedRealtime(), CONNECT_TIMEOUT_MILLIS)) {
                    timedOut.set(true)
                    throw IOException("蓝牙连接结果已超过截止时间")
                }
                if (!connecting.compareAndSet(true, false)) throw IOException("蓝牙连接超过 ${CONNECT_TIMEOUT_MILLIS}ms")
                if (!running || generation.get() != myGeneration) throw IOException("本次连接已取消")
                lease.checkCurrent()
                if (targetProvider()?.address?.let { !it.equals(target, true) } == true) throw IOException("目标车机已切换")
                lease.withOwnership { onPhase("蓝牙通道已建立，正在协商 iAP2…") }
                handedOff = true
                socket
            } catch (error: Exception) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                log(
                    "BT-C: 连接异常 addr=$target UUID=$ACCESSORY_IAP2_UUID 耗时=${elapsed}ms " +
                        "超时=${timedOut.get()} ${error.javaClass.simpleName}: ${error.message}",
                )
                if (running && generation.get() == myGeneration && lease.isCurrent) {
                    onPhase("蓝牙通道连接失败，正在重试；可导出日志查看原因")
                }
                null
            } finally {
                connecting.set(false)
                timeout?.cancel(false)
                log("BT-C: 连接调用线程结束 耗时=${android.os.SystemClock.elapsedRealtime() - startedAt}ms 超时=${timedOut.get()}")
            }
        } catch (error: Exception) {
            log("BT-C: 准备连接失败 addr=$target ${error.javaClass.simpleName}: ${error.message}")
            return null
        } finally {
            connectBudget.release(target)
            val socket = preparedSocket
            if (socket == null) {
                socketBudget.release()
                currentLease.compareAndSet(lease, null)
                lease.close()
            } else if (!handedOff) {
                currentSocket.compareAndSet(socket, null)
                closeSocketAsync(socket, "连接未交付")
            }
        }
    }

    /** 在有界工作池关闭 socket，避免阻塞定时器和界面 */
    private fun closeSocketAsync(
        socket: BluetoothSocket,
        reason: String,
    ) {
        // 先撤销协议身份；旧写入由门闸跟踪，实际关闭卡住也不占住空闲控制通道
        socketLeases.remove(socket)?.let { lease ->
            currentLease.compareAndSet(lease, null)
            lease.close()
        }
        if (!closingSockets.add(socket)) return
        try {
            closeExecutor.execute {
                log("BT-C: 套接字关闭开始 原因=$reason")
                try {
                    socket.close()
                } catch (error: IOException) {
                    log("BT-C: 套接字关闭异常 ${error.message}")
                } finally {
                    // 保留已关闭身份，避免会话 finally 再次关闭并重复释放额度
                    socketBudget.release()
                    log("BT-C: 套接字关闭结束 原因=$reason")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            closingSockets.remove(socket)
            log("BT-C: 关闭线程已达上限，保留连接额度直到原线程退出")
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSession(
        socket: BluetoothSocket,
        myGeneration: Long,
    ): Boolean {
        var channel: Iap2LinkChannel? = null
        var attemptedHandshake: BluetoothHandshakeRecovery.Attempt? = null
        val address = runCatching { socket.remoteDevice.address }.getOrNull()
        val operationLease = socketLeases[socket]
        return try {
            val lease = operationLease ?: return false
            val attempt =
                synchronized(this) {
                    if (!isCurrentSession(socket, myGeneration)) return false
                    address?.let { BtHandshakeRecovery.attempts.attempt(it) }
                }
            attemptedHandshake = attempt
            val handshakeMode = attempt?.mode ?: BluetoothHandshakeRecovery.Mode.ACTIVE
            val stream =
                object : DuplexStream {
                    override fun read(buffer: ByteArray): Int {
                        lease.checkCurrent()
                        val count = socket.inputStream.read(buffer)
                        lease.checkCurrent()
                        return count
                    }

                    override fun write(bytes: ByteArray) {
                        lease.withOwnership {
                            socket.outputStream.write(bytes)
                            socket.outputStream.flush()
                        }
                    }

                    override fun close() = closeSocketAsync(socket, "链路关闭")
                }
            log("BT-C: iAP2 开场=${handshakeMode.title} addr=$address")
            val currentChannel =
                Iap2LinkChannel(
                    stream,
                    name = "iap2-bt-car",
                    allowPeerRestart = true,
                    respondToPeerHandshake = handshakeMode == BluetoothHandshakeRecovery.Mode.RESPONDER,
                    onReady = { lease.confirm() },
                ) { line -> log(line) }
            channel = currentChannel
            synchronized(this) {
                if (!isCurrentSession(socket, myGeneration)) return false
                activeChannel = currentChannel
            }
            currentChannel.runSenderSession(
                context,
                SESSION_TIMEOUT_MILLIS,
                log,
                { if (isCurrentSession(socket, myGeneration)) onEndpoint(it) },
                { if (isCurrentSession(socket, myGeneration)) onWifiCredentials(it) },
                { if (isCurrentSession(socket, myGeneration)) onEndpointUpdate(it) },
                { if (isCurrentSession(socket, myGeneration)) onPhase(it) },
                challengeRecovery = address?.let { BtMfiChallengeRecovery.forTarget(it) },
                isCurrent = { isCurrentOperation(socket, myGeneration, lease) },
            )
            true
        } catch (error: IOException) {
            log("BT-C: SESSION 失败 - ${error.message}")
            // 读取链路状态可能等待写入，不能持有生命周期锁，以免取消无法关闭套接字
            val silentTimeout = channel?.silentNegotiationTimedOut == true
            val attempt = attemptedHandshake
            synchronized(this) {
                if (isCurrentSession(socket, myGeneration) &&
                    !Thread.currentThread().isInterrupted &&
                    silentTimeout &&
                    address != null &&
                    attempt != null
                ) {
                    BtHandshakeRecovery.attempts.recordSilence(address, attempt)?.let { nextMode ->
                        log("BT-C: 本轮初始协商零接收，下一轮仅对该目标切换${nextMode.title}，不修改认证或音频连接策略")
                    }
                }
            }
            if (isCurrentOperation(socket, myGeneration, operationLease)) onPhase("iAP2 会话失败：${error.message ?: "原因见日志"}")
            false
        } finally {
            val attempt = attemptedHandshake
            if (address != null && attempt != null && BtHandshakeRecovery.attempts.finishAttempt(address, attempt)) {
                log("BT-C: 本轮应答握手已结束，后续恢复默认主动握手")
            }
            synchronized(this) {
                if (activeChannel === channel) activeChannel = null
            }
            currentSocket.compareAndSet(socket, null)
            closeSocketAsync(socket, "会话结束")
            channel?.close()
            log("BT-C: 本轮会话结束（客户端仍在运行，将自动重连）")
        }
    }

    /** 退役或取消后清除 socket 身份，旧回调不再交付结果 */
    private fun isCurrentSession(
        socket: BluetoothSocket,
        myGeneration: Long,
    ): Boolean = socketLeases[socket]?.let { isCurrentOperation(socket, myGeneration, it) && it.isCurrent } == true

    /** 自然 EOF 仍可报告本轮失败，本地撤销或重新启动后的旧线程不能回写 */
    private fun isCurrentOperation(
        socket: BluetoothSocket,
        myGeneration: Long,
        lease: BluetoothSessionArbiter.Lease?,
    ): Boolean = running && generation.get() == myGeneration && currentSocket.get() === socket && lease?.isLatest == true

    /** 结束当前蓝牙控制会话，保留重连循环 */
    @Synchronized
    fun detachCurrentSession() {
        currentLease.getAndSet(null)?.close()
        activeChannel?.let { channel ->
            activeChannel = null
            closeChannelAsync(channel)
            log("BT-C: 已主动断开当前控制会话")
        }
        currentSocket.getAndSet(null)?.let { closeSocketAsync(it, "退役") }
    }

    /** 链路锁也可能等待底层写入，清理不能阻塞界面或套接字关闭池 */
    private fun closeChannelAsync(channel: Iap2LinkChannel) {
        try {
            channelCloser.execute { channel.close() }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            log("BT-C: 链路清理队列已满，等待已提交的套接字关闭唤醒读线程")
        }
    }

    @Synchronized
    fun stop() {
        running = false
        // 递增世代：即便有线程卡在 connect()，事后也会因世代不符自杀，不会在下次启动时复活
        generation.incrementAndGet()
        currentLease.getAndSet(null)?.close()
        currentSocket.getAndSet(null)?.let { closeSocketAsync(it, "停止") }
        val channel = activeChannel
        activeChannel = null
        // 链路关闭只发出流关闭请求，底层 socket 已由有界关闭池处理
        channel?.let { closeChannelAsync(it) }
        thread?.interrupt()
        thread = null
        log("BT-C: 已停止")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }
}
