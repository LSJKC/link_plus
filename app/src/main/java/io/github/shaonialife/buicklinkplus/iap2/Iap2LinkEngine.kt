package io.github.shaonialife.buicklinkplus.iap2

import java.io.ByteArrayOutputStream

/** 回显发送窗口为 0x7F，参考 iPhone 和 AADisplay */
private const val PHONE_DECLARED_MAX_OUTGOING = 0x7F

/**
 * iAP2 链路引擎，协议参考 xcertplay transport/Iap2LinkEngine，独立实现
 * 发起方发送 marker 和 SYN，双方确认会话表后进入 NORMAL
 * 数据帧携带 ACK，序号按 u8 回绕；采用对端协商的会话 ID
 * RFCOMM 可靠有序，不做乱序缓存和主动 EAK，保留重复检测、发送窗口和重传
 */
class Iap2LinkEngine(
    private val maxOutgoing: Int = 4,
    private val retransmissionTimeoutMillis: Long = 4_000L,
    private val maxRetransmissions: Int = 4,
    private val maxPeerRestarts: Int = 0,
    /** 控制通道需对端确认回显后才开放 CSM，直连和隧道保持各自流程 */
    private val waitForPeerAcknowledgement: Boolean = false,
) {
    enum class State { IDLE, NEGOTIATING, NORMAL, DEAD }

    data class SessionDescriptor(
        val id: Int,
        val kind: Int,
        val version: Int,
    )

    data class SynchronizationPayload(
        val maxOutgoing: Int,
        val maxLength: Int,
        val retransmissionTimeoutMillis: Int,
        val acknowledgementTimeoutMillis: Int,
        val maxRetransmissions: Int,
        val maxAcknowledgements: Int,
        val sessions: List<SessionDescriptor>,
    ) {
        fun encode(): ByteArray {
            val out = ByteArrayOutputStream(10 + sessions.size * 3)
            out.write(SYNCHRONIZATION_VERSION)
            out.write(maxOutgoing)
            writeU16(out, maxLength)
            writeU16(out, retransmissionTimeoutMillis)
            writeU16(out, acknowledgementTimeoutMillis)
            out.write(maxRetransmissions)
            out.write(maxAcknowledgements)
            sessions.forEach {
                out.write(it.id)
                out.write(it.kind)
                out.write(it.version)
            }
            return out.toByteArray()
        }

        companion object {
            fun decode(bytes: ByteArray): SynchronizationPayload? {
                if (bytes.size < 10 || u8(bytes[0]) != SYNCHRONIZATION_VERSION) return null
                val sessions = ArrayList<SessionDescriptor>()
                var offset = 10
                while (offset + 3 <= bytes.size) {
                    sessions +=
                        SessionDescriptor(
                            u8(bytes[offset]),
                            u8(bytes[offset + 1]),
                            u8(bytes[offset + 2]),
                        )
                    offset += 3
                }
                return SynchronizationPayload(
                    maxOutgoing = u8(bytes[1]),
                    maxLength = readU16(bytes, 2),
                    retransmissionTimeoutMillis = readU16(bytes, 4),
                    acknowledgementTimeoutMillis = readU16(bytes, 6),
                    maxRetransmissions = u8(bytes[8]),
                    maxAcknowledgements = u8(bytes[9]),
                    sessions = sessions,
                )
            }
        }
    }

    sealed class Event {
        class Control(
            val bytes: ByteArray,
        ) : Event()

        class Dead(
            val reason: String?,
        ) : Event()
    }

    private class Packet(
        var sequence: Int,
        val payload: ByteArray,
        var retransmissions: Int = 0,
        var deadlineMillis: Long = 0,
        val awaitAck: Boolean = true,
    )

    var state: State = State.IDLE
        private set
    var peerSynchronization: SynchronizationPayload? = null
        private set

    private var sentSequence = INITIAL_SEQUENCE

    /** 交接依据对端确认，不使用本地零重传放行状态 */
    private var lastPeerAcknowledgement: Int? = null
    private var lastReliableSequence: Int? = null
    private var lastSentAcknowledged: Int? = null

    /** 队列已发出，且可靠控制帧全部获对端 ACK */
    val controlOutputAcknowledged: Boolean
        get() {
            val reliable = lastReliableSequence
            val peerAck = lastPeerAcknowledgement
            val confirmed =
                reliable == null ||
                    (peerAck != null && sequenceDistance(peerAck, reliable) <= sequenceDistance(sentSequence, reliable))
            return state == State.NORMAL && sendBacklog.isEmpty() && unacknowledged.isEmpty() && confirmed
        }
    private var lastReceivedInOrder = 0

    /** 控制会话 ID 取自对端同步表，不能固定使用本端默认值 */
    private var adoptedPeerControlSessionId: Int? = null

    /** 采纳的对端 sync 载荷（协商回显/诊断用）。 */
    private var adoptedSynchronization: SynchronizationPayload? = null

    /**
     * type-130 隧道先发 SYN|ACK 回显，再发裸 ACK；不等待对端 ACK 即完成协商
     * 蓝牙和普通事件通道仍等待最终 ACK，参考 DiPlay openTunnel
     */
    var echoFirst: Boolean = false

    /** 完整探测后才接受新同步，上层同时更新会话代次 */
    private var peerDetectSeen = false

    /** 探测应答后尚未收到新同步，暂不交接蓝牙链路 */
    val awaitingPeerSynchronization: Boolean
        get() = peerDetectSeen

    private var adoptedPeerSequence: Int? = null
    var peerRestartCount: Int = 0
        private set

    /** 重新协商前通知上层清理旧控制会话，回调内不能发送业务消息 */
    var onPeerRestarted: ((Int) -> Unit)? = null

    /** 记录链路重新探测以及同步被拒绝的原因 */
    var onPeerRestartNotice: ((String) -> Unit)? = null

    /** 完整探测逐次通知上层，参数表示新一轮已建链探测，由上层限制应答频率 */
    var onPeerDetect: ((Boolean) -> Unit)? = null

    /** 首次采用对端会话表时回调，供记录协商参数 */
    var onPeerSynchronizationAdopted: ((SynchronizationPayload) -> Unit)? = null

    /** 诊断：丢弃未知会话 ID 的数据帧时回调（(sessionId, bytes)）。 */
    var onUnknownSessionData: ((Int, Int) -> Unit)? = null

    /** EA 数据帧回调，供虚拟链路接收触摸 HID */
    var onEaData: ((bytes: ByteArray) -> Unit)? = null

    /** 入站帧摘要：control、seq、ack、sessionId 和载荷长度 */
    var onFrameReceived: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    /** 出站链路帧摘要，供对照协商时序 */
    var onFrameSent: ((control: Int, sequence: Int, ack: Int, sessionId: Int, size: Int) -> Unit)? = null

    private var unknownSessionDropped = 0

    private val unacknowledged = ArrayList<Packet>()
    private val sendBacklog = ArrayDeque<Packet>()
    private val pendingEvents = ArrayDeque<Event>()
    private val output = ByteArrayOutputStream()
    private val receive = ReceiveBuffer()

    private var markerDeadline: Long? = null
    private var syncDeadline: Long? = null
    private var retransmitDeadline: Long? = null

    /** 上一次回显 SYN 时对端的 SYN 序号与我方序号（重传同一 SYN 时原样重放）。 */
    private var lastEchoPeerSequence: Int? = null
    private var lastEchoOwnSequence = 0

    val isDead: Boolean get() = state == State.DEAD

    /** 本轮是否由本端发起 SYN，响应方不置位 */
    private var originatedSyn = false

    /** 本端发送上限仍受对端窗口限制，0x7F 回显不代表对端容量 */
    private var declaredMaxOutgoing = maxOutgoing

    /** 进入协商：发 marker + 同步包（发送端=发起方，不等待对端 marker）。 */
    fun start(nowMillis: Long) {
        if (state != State.IDLE) return
        originatedSyn = true
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /** 补发 marker 和本端同步表，供车机只重发 marker 时继续协商 */
    fun initiate(nowMillis: Long) {
        if (state == State.DEAD) return
        originatedSyn = true
        if (state == State.IDLE) state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        sendSynchronization()
        syncDeadline = nowMillis + SYNC_RESEND_MILLIS
    }

    /** 停止自动重发 marker 和同步包，保留当前协商状态 */
    fun stopNegotiationRetries() {
        markerDeadline = null
        syncDeadline = null
    }

    /** 响应方只发 marker，收到车机 SYN 后再 ACK 和回显 */
    fun startResponder(nowMillis: Long) {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
        appendOutput(MARKER)
        markerDeadline = nowMillis + MARKER_RESEND_MILLIS
        // syncDeadline 保持 null：不主动发 SYN、不重发 SYN
    }

    /** 静默进入协商，收到 DETECT 才回应，不主动重发，参考 catplay LinkLayer server */
    fun enterNegotiating() {
        if (state != State.IDLE) return
        state = State.NEGOTIATING
    }

    fun feed(
        bytes: ByteArray,
        nowMillis: Long,
    ) {
        if (state == State.DEAD) return
        var offset = 0
        while (offset < bytes.size && state != State.DEAD) {
            val room = receive.remainingCapacity()
            if (room == 0) {
                die("inbound frame exceeds limit")
                return
            }
            val count = minOf(room, bytes.size - offset)
            receive.append(bytes, offset, count)
            offset += count
            while (parseAvailable(nowMillis) && state != State.DEAD) {
                // 排空所有完整帧
            }
        }
    }

    fun feedEof() = die(null)

    fun takeOutput(): ByteArray {
        val bytes = output.toByteArray()
        output.reset()
        return bytes
    }

    fun pollEvent(): Event? = if (pendingEvents.isEmpty()) null else pendingEvents.removeFirst()

    fun nextDeadlineMillis(): Long? = listOfNotNull(markerDeadline, syncDeadline, retransmitDeadline).minOrNull()

    fun advanceTime(nowMillis: Long) {
        if (state == State.DEAD) return
        if (markerDeadline != null && markerDeadline!! <= nowMillis) {
            markerDeadline = null
            if (state == State.NEGOTIATING && appendOutput(MARKER)) {
                markerDeadline = nowMillis + MARKER_RESEND_MILLIS
            }
        }
        if (syncDeadline != null && syncDeadline!! <= nowMillis) {
            syncDeadline = null
            if (state == State.NEGOTIATING) {
                // 已采纳对端表：只补裸 ACK（回显仅首次发一次，避免协商轮次膨胀）；
                // 未采纳：继续重发本表 SYN（测试台时序）
                if (adoptedSynchronization != null) {
                    sendAcknowledgement()
                } else {
                    sendSynchronization()
                }
                syncDeadline = nowMillis + SYNC_RESEND_MILLIS
            }
        }
        if (retransmitDeadline != null && retransmitDeadline!! <= nowMillis) {
            retransmitDeadline = null
            if (state == State.NORMAL) retransmitDue(nowMillis)
        }
    }

    /**
     * 排入完整控制消息，窗口满时等待 ACK 或 pump，链路已关闭返回 false
     * [awaitAck] 为 false 时不跟踪确认，也不因缺少 ACK 重传或拆链
     */
    fun sendControl(
        bytes: ByteArray,
        nowMillis: Long,
        awaitAck: Boolean = true,
    ): Boolean {
        if (state == State.DEAD) return false
        val limit = (peerSynchronization?.maxLength ?: 0xFFFF) - HEADER_BYTES - 1
        if (limit <= 0 || bytes.size > limit) {
            die("control payload ${bytes.size} exceeds negotiated limit $limit")
            return false
        }
        sendBacklog += Packet(0, bytes.copyOf(), awaitAck = awaitAck)
        drainSendWindow(nowMillis)
        return true
    }

    // ---- 接收解析 ----

    private fun parseAvailable(nowMillis: Long): Boolean {
        // 完整识别跨分片的探测标记，不能把 FF55 前缀或任意六字节误当作重新探测
        while (receive.size >= 2) {
            if (receive[0] == 0xFF.toByte() && receive[1] == 0x5A.toByte()) break
            if (receive[0] == MARKER[0] && receive[1] == MARKER[1]) {
                if (receive.size < MARKER.size) return false
                if (MARKER.indices.all { receive[it] == MARKER[it] }) {
                    receive.discard(MARKER.size)
                    if (maxPeerRestarts > 0 && (state == State.NORMAL || state == State.NEGOTIATING)) {
                        val newProbe = state == State.NORMAL && !peerDetectSeen
                        if (state == State.NORMAL) peerDetectSeen = true
                        onPeerDetect?.invoke(newProbe)
                        if (newProbe) {
                            onPeerRestartNotice?.invoke("已建链后收到完整 DETECT，已请求探测应答，等待有效的新 SYN")
                        }
                    }
                    continue
                }
            }
            receive.discard(1)
        }
        if (receive.size < HEADER_BYTES) return false
        val header = receive.peek(HEADER_BYTES)
        if (!checksumValid(header)) {
            receive.discard(1)
            return true
        }
        val length = readU16(header, 2)
        if (length < HEADER_BYTES) {
            receive.discard(1)
            return true
        }
        if (receive.size < length) return false
        val frame = receive.take(length)
        val payloadWithChecksum = frame.copyOfRange(HEADER_BYTES, length)
        if (payloadWithChecksum.isNotEmpty() && !checksumValid(payloadWithChecksum)) {
            // 整个坏帧已经取出，不能再吞掉紧随其后的有效帧首字节
            return true
        }
        val payload =
            if (payloadWithChecksum.isEmpty()) {
                null
            } else {
                payloadWithChecksum.copyOf(payloadWithChecksum.size - 1)
            }
        processFrame(
            control = u8(frame[4]),
            sequence = u8(frame[5]),
            ack = u8(frame[6]),
            sessionId = u8(frame[7]),
            payload = payload,
            nowMillis = nowMillis,
        )
        return true
    }

    private fun processFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        sessionId: Int,
        payload: ByteArray?,
        nowMillis: Long,
    ) {
        onFrameReceived?.invoke(control, sequence, ack, sessionId, payload?.size ?: 0)
        if (control and CONTROL_RESET != 0) {
            die("peer sent reset")
            return
        }
        if (control and CONTROL_SYNCHRONIZE != 0) {
            val sync = SynchronizationPayload.decode(payload ?: ByteArray(0))
            if (maxPeerRestarts > 0 && state == State.NORMAL) {
                // 同一同步的重传只重放回显，不倒退接收水位，也不重复启动控制会话
                if (!peerDetectSeen &&
                    control == CONTROL_SYNCHRONIZE &&
                    sessionId == 0 &&
                    sequence == adoptedPeerSequence &&
                    sync != null &&
                    sync == adoptedSynchronization
                ) {
                    peerDetectSeen = false
                    sendSynchronizationEcho(sync, sequence)
                    return
                }
                val hasDetect = peerDetectSeen
                peerDetectSeen = false
                if (!hasDetect ||
                    control != CONTROL_SYNCHRONIZE ||
                    sessionId != 0 ||
                    sync == null ||
                    !validRestartSynchronization(sync, payload?.size ?: 0)
                ) {
                    onPeerRestartNotice?.invoke("忽略建链后的同步：探测=$hasDetect ctl=$control session=$sessionId seq=$sequence")
                    return
                }
                if (peerRestartCount >= maxPeerRestarts) {
                    onPeerRestartNotice?.invoke("重新协商已达 $maxPeerRestarts 次上限，停止恢复")
                    return
                }
                restartPeerSession()
            }
            // 隧道可能晚于首个 SYN 就绪，NORMAL 状态仍需回应重发
            // 只回应不带 ACK 的 SYN，避免双方反复互回 SYN|ACK
            val isPureSyn = (control and CONTROL_ACKNOWLEDGEMENT) == 0
            if (sync != null && isPureSyn && (state == State.NEGOTIATING || echoFirst)) {
                val firstAdoption = adoptedSynchronization == null
                peerSynchronization = sync
                if (firstAdoption || maxPeerRestarts > 0) {
                    adoptedSynchronization = sync
                    adoptedPeerSequence = sequence
                }
                // 回显对端会话表，后续 CSM 使用采纳后的控制会话 ID
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                lastReceivedInOrder = sequence
                // 每个 SYN 只回一帧 SYN|ACK，避免额外 ACK 复用序号
                // 相同 SYN 的重传原样回放，不再推进序号
                sendSynchronizationEcho(sync, sequence)
                if (firstAdoption) onPeerSynchronizationAdopted?.invoke(sync)
                // 响应方（未发过 SYN）回显即视为协商完成：对端见到 SYN|ACK 后即认为
                // 链路可用（MIB3 真机 iPhone 发完 SYN|ACK 立即发 0x1D00，不等 ACK）；
                // 发起方仍等对端 ACK/数据；网络控制通道可要求最终确认后再启动 CSM
                if (echoFirst || (!originatedSyn && !waitForPeerAcknowledgement)) completeNegotiationIfPending(nowMillis)
            } else if (sync != null &&
                !isPureSyn &&
                adoptedSynchronization == null &&
                state == State.NEGOTIATING
            ) {
                // 发起方收到 SYN|ACK（对端对我们 SYN 的应答，载荷=对端表）：
                // 只采纳、**不回显**（回显会形成互回 SYN|ACK 的乒乓）。此前只在纯
                // SYN 上采纳，对端是「纯响应方」时发起方永远到不了 NORMAL——
                // 收到应答后立即确认对端 SYN 序号，不能等已被取消的协商计时器补 ACK
                peerSynchronization = sync
                adoptedSynchronization = sync
                adoptedPeerSequence = sequence
                sync.sessions.firstOrNull { it.kind == CONTROL_KIND }?.let {
                    adoptedPeerControlSessionId = it.id
                }
                // 与纯 SYN 分支一样推进接收水位，避免把对端首个数据帧误判为过期帧
                lastReceivedInOrder = sequence
                sendAcknowledgement()
                completeNegotiationIfPending(nowMillis)
            }
        }
        if (control and CONTROL_ACKNOWLEDGEMENT != 0) {
            handleAcknowledgement(ack, nowMillis)
        }
        val isDataFrame =
            payload != null &&
                (control and CONTROL_ACKNOWLEDGEMENT) != 0 &&
                (control and CONTROL_SYNCHRONIZE) == 0 &&
                (control and CONTROL_EXTENDED_ACKNOWLEDGEMENT) == 0
        if (isDataFrame) {
            // 网络响应方只接受确认了本轮同步的数据，错误或旧代次的 ACK 不能绕过最终确认
            if (waitForPeerAcknowledgement && !echoFirst && !originatedSyn && state == State.NEGOTIATING) return
            // 控制消息的会话 ID：本国表 10 或采纳表（Audi=1；测试台两者一致）
            if (sessionId == SESSION_CONTROL || sessionId == adoptedPeerControlSessionId) {
                // 收到对端的数据即代表对端认为链路可用；ACK 帧若丢失/被跳过，这里兜底进入 NORMAL
                completeNegotiationIfPending(nowMillis)
                handleData(sequence, payload)
            } else if (sessionId == SESSION_EA && onEaData != null) {
                // EA 会话数据（含触摸 HID）：对端序号账法与控制会话独立，直接上抛
                onEaData?.invoke(payload)
                sendAcknowledgement()
            } else {
                reportUnknownSessionData(sessionId, payload.size)
            }
        }
    }

    /** 只用完整同步参数、有效窗口和唯一控制会话恢复链路 */
    private fun validRestartSynchronization(
        sync: SynchronizationPayload,
        payloadSize: Int,
    ): Boolean =
        payloadSize == 10 + sync.sessions.size * 3 &&
            sync.maxOutgoing in 1..127 &&
            sync.maxLength >= 64 &&
            sync.sessions.count { it.kind == CONTROL_KIND } == 1 &&
            sync.sessions.all { it.id > 0 && it.kind in 0..2 && it.version > 0 } &&
            sync.sessions
                .map { it.id }
                .distinct()
                .size == sync.sessions.size

    /** 仅重置链路层旧状态，接收缓冲中紧随新 SYN 的字节仍属于新会话并继续解析 */
    private fun restartPeerSession() {
        state = State.NEGOTIATING
        peerRestartCount++
        peerDetectSeen = false
        originatedSyn = false
        peerSynchronization = null
        adoptedSynchronization = null
        adoptedPeerControlSessionId = null
        adoptedPeerSequence = null
        sentSequence = INITIAL_SEQUENCE
        lastSentAcknowledged = null
        lastPeerAcknowledgement = null
        lastReliableSequence = null
        lastReceivedInOrder = 0
        lastEchoPeerSequence = null
        lastEchoOwnSequence = 0
        declaredMaxOutgoing = maxOutgoing
        unknownSessionDropped = 0
        unacknowledged.clear()
        sendBacklog.clear()
        pendingEvents.clear()
        output.reset()
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        onPeerRestarted?.invoke(peerRestartCount)
    }

    private fun completeNegotiationIfPending(nowMillis: Long) {
        if (state == State.NEGOTIATING && peerSynchronization != null) {
            state = State.NORMAL
            markerDeadline = null
            syncDeadline = null
            drainSendWindow(nowMillis)
        }
    }

    private fun reportUnknownSessionData(
        sessionId: Int,
        size: Int,
    ) {
        unknownSessionDropped++
        if (unknownSessionDropped <= 5) onUnknownSessionData?.invoke(sessionId, size)
    }

    /** 同步回显生成回调，隧道需确认其发送后再开放 CSM */
    var onSynchronizationEchoSent: (() -> Unit)? = null

    /**
     * 回显对端同步表并使用新的发送序号；同一 SYN 的重发复用原回显帧
     * 序号规则参考 catplay NegotiationTx::SynAck
     */

    /** 回显沿用对端会话表、超时和最大长度；maxOutgoing 声明为 0x7F，但仍遵守对端发送窗口 */
    private fun ownSyncOf(peer: SynchronizationPayload): SynchronizationPayload =
        peer.copy(maxOutgoing = PHONE_DECLARED_MAX_OUTGOING).also {
            declaredMaxOutgoing = PHONE_DECLARED_MAX_OUTGOING
        }

    private fun sendSynchronizationEcho(
        sync: SynchronizationPayload,
        peerSequence: Int,
    ) {
        if (lastEchoPeerSequence == peerSequence) {
            writePacket(
                ownSyncOf(sync).encode(),
                lastEchoOwnSequence,
                CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT,
                0,
                acknowledgement = peerSequence,
            )
            onSynchronizationEchoSent?.invoke()
            return
        }
        val ownSequence = nextSequence(sentSequence).also { sentSequence = it }
        writePacket(
            ownSyncOf(sync).encode(),
            ownSequence,
            CONTROL_SYNCHRONIZE or CONTROL_ACKNOWLEDGEMENT,
            0,
            acknowledgement = peerSequence,
        )
        lastEchoPeerSequence = peerSequence
        lastEchoOwnSequence = ownSequence
        onSynchronizationEchoSent?.invoke()
    }

    private fun handleAcknowledgement(
        acknowledgement: Int,
        nowMillis: Long,
    ) {
        // 网络响应方必须确认当前同步回显，迟到的旧 ACK 不得打开新会话
        if (waitForPeerAcknowledgement &&
            !echoFirst &&
            !originatedSyn &&
            state == State.NEGOTIATING &&
            lastEchoPeerSequence != null &&
            acknowledgement != lastEchoOwnSequence
        ) {
            return
        }
        val previousAcknowledgement = lastPeerAcknowledgement
        if (waitForPeerAcknowledgement &&
            state == State.NORMAL &&
            previousAcknowledgement != null &&
            sequenceDistance(acknowledgement, previousAcknowledgement) > sequenceDistance(sentSequence, previousAcknowledgement)
        ) {
            return
        }
        completeNegotiationIfPending(nowMillis)
        lastPeerAcknowledgement = acknowledgement
        lastSentAcknowledged = acknowledgement
        val window = (peerSynchronization?.maxOutgoing ?: maxOutgoing) + ACK_SLACK
        unacknowledged.removeAll { sequenceDistance(it.sequence, acknowledgement) !in 1..window }
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
        drainSendWindow(nowMillis)
    }

    private fun handleData(
        sequence: Int,
        payload: ByteArray,
    ) {
        val distance = sequenceDistance(sequence, lastReceivedInOrder)
        val peerWindow = peerSynchronization?.maxOutgoing ?: maxOutgoing
        if (distance == 0 || distance > peerWindow + ACK_SLACK) {
            sendAcknowledgement() // 重复或过期：重新确认当前序号
            return
        }
        // RFCOMM 为可靠有序流，不会丢包；序号跳变只可能是对端把 ACK/SYN 也计入了序号
        // （各家实现账法不一致）。因此接受任意前进量并推进水位，避免死锁式丢帧。
        lastReceivedInOrder = sequence
        // 裸确认、重复包及未知会话不能撤销探测，只有实际接受的控制业务才表明旧会话续传
        peerDetectSeen = false
        if (pendingEvents.size >= 256) {
            die("event queue overflow")
            return
        }
        pendingEvents += Event.Control(payload)
        sendAcknowledgement()
    }

    // ---- 发送 ----

    private fun drainSendWindow(nowMillis: Long) {
        if (state != State.NORMAL) return
        while (sendBacklog.isNotEmpty()) {
            val packet = sendBacklog.first()
            val peer = peerSynchronization
            // 两个公开链路实现均用对端 maxOutgoing 约束未确认帧
            // 别克声明 5，但旧逻辑回显 127 后允许连续发 7 包，启动帧可能超出接收窗口
            // 零重传隧道沿用原有本端限额与隐式确认语义，避免无 ACK 场景永久积压
            val window =
                if (peer != null && peer.maxRetransmissions > 0) {
                    minOf(declaredMaxOutgoing, peer.maxOutgoing.coerceAtLeast(1))
                } else {
                    declaredMaxOutgoing
                }
            if (lastSentAcknowledged != null &&
                sequenceDistance(sentSequence, lastSentAcknowledged!!) >= window
            ) {
                return
            }
            sendBacklog.removeFirst()
            sentSequence = nextSequence(sentSequence)
            packet.sequence = sentSequence
            packet.deadlineMillis = nowMillis + retransmissionTimeoutMillis
            writePacket(packet.payload, packet.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
            if (!packet.awaitAck) continue // 发后不管：不追踪、不重传（见 sendControl 注释）
            lastReliableSequence = packet.sequence
            if (peer == null || peer.maxRetransmissions > 0) {
                unacknowledged += packet
                retransmitDeadline = packet.deadlineMillis
            } else {
                lastSentAcknowledged = sentSequence
            }
        }
    }

    /** 出向控制消息的会话 ID：采纳了对端表就用其 control ID（Audi=1），否则本表 10。 */
    private fun controlSessionId(): Int = adoptedPeerControlSessionId ?: SESSION_CONTROL

    private fun retransmitDue(nowMillis: Long) {
        val next = unacknowledged.minByOrNull { it.deadlineMillis } ?: return
        next.deadlineMillis = nowMillis + retransmissionTimeoutMillis
        next.retransmissions += 1
        if (next.retransmissions > maxRetransmissions) {
            die("packet ${next.sequence} not acknowledged")
            return
        }
        writePacket(next.payload, next.sequence, CONTROL_ACKNOWLEDGEMENT, controlSessionId())
        retransmitDeadline = unacknowledged.minOfOrNull { it.deadlineMillis }
    }

    private fun sendSynchronization() {
        val payload =
            SynchronizationPayload(
                maxOutgoing = maxOutgoing,
                maxLength = 0xFFFF,
                retransmissionTimeoutMillis = retransmissionTimeoutMillis.toInt().coerceIn(0, 0xFFFF),
                acknowledgementTimeoutMillis = 500,
                maxRetransmissions = maxRetransmissions,
                maxAcknowledgements = 3,
                sessions =
                    listOf(
                        SessionDescriptor(SESSION_CONTROL, 0, 2),
                        SessionDescriptor(SESSION_EA, 2, 1),
                        SessionDescriptor(SESSION_FILE_TRANSFER, 1, 2),
                    ),
            ).encode()
        writePacket(payload, sentSequence, CONTROL_SYNCHRONIZE, 0)
    }

    private fun sendAcknowledgement() {
        writePacket(null, sentSequence, CONTROL_ACKNOWLEDGEMENT, 0)
    }

    private fun writePacket(
        payload: ByteArray?,
        sequence: Int,
        control: Int,
        sessionId: Int,
        acknowledgement: Int = lastReceivedInOrder,
    ) {
        if (state == State.DEAD) return
        onFrameSent?.invoke(control, sequence, acknowledgement, sessionId, payload?.size ?: 0)
        val length = payload?.size?.plus(HEADER_BYTES + 1) ?: HEADER_BYTES
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = acknowledgement.toByte()
        frame[7] = sessionId.toByte()
        var sum = 0
        for (index in 0 until HEADER_BYTES - 1) sum += u8(frame[index])
        frame[HEADER_BYTES - 1] = (-sum and 0xFF).toByte()
        if (payload != null) {
            payload.copyInto(frame, HEADER_BYTES)
            var payloadSum = 0
            payload.forEach { payloadSum += u8(it) }
            frame[frame.size - 1] = (-payloadSum and 0xFF).toByte()
        }
        appendOutput(frame)
    }

    private fun die(reason: String?) {
        if (state == State.DEAD) return
        state = State.DEAD
        markerDeadline = null
        syncDeadline = null
        retransmitDeadline = null
        output.reset()
        pendingEvents.clear()
        sendBacklog.clear()
        pendingEvents += Event.Dead(reason)
    }

    private fun appendOutput(bytes: ByteArray): Boolean {
        if (state == State.DEAD) return false
        output.write(bytes)
        return true
    }

    private class ReceiveBuffer {
        private val bytes = ByteArray(0xFFFF + 8)
        private var head = 0
        private var tail = 0

        val size: Int get() = tail - head

        fun remainingCapacity(): Int {
            compact()
            return bytes.size - tail
        }

        operator fun get(index: Int): Byte = bytes[head + index]

        fun append(
            source: ByteArray,
            offset: Int,
            count: Int,
        ) {
            source.copyInto(bytes, tail, offset, offset + count)
            tail += count
        }

        fun discard(count: Int) {
            head += count
            if (head == tail) {
                head = 0
                tail = 0
            }
        }

        fun peek(count: Int): ByteArray = bytes.copyOfRange(head, head + count)

        fun take(count: Int): ByteArray {
            val out = peek(count)
            discard(count)
            return out
        }

        private fun compact() {
            if (head == 0) return
            if (head < tail) bytes.copyInto(bytes, 0, head, tail)
            tail -= head
            head = 0
        }
    }

    companion object {
        const val SESSION_CONTROL = 10
        const val SESSION_EA = 11
        const val SESSION_FILE_TRANSFER = 12

        /** 会话描述符里的类型字段：0 = 控制会话（xcertplay/extractor/catplay 一致）。 */
        private const val CONTROL_KIND = 0

        val MARKER = byteArrayOf(0xFF.toByte(), 0x55, 0x02, 0x00, 0xEE.toByte(), 0x10)
        const val HEADER_BYTES = 9
        private const val SYNCHRONIZATION_VERSION = 1
        private const val CONTROL_SYNCHRONIZE = 0x80
        private const val CONTROL_ACKNOWLEDGEMENT = 0x40
        private const val CONTROL_EXTENDED_ACKNOWLEDGEMENT = 0x20
        private const val CONTROL_RESET = 0x10
        private const val INITIAL_SEQUENCE = 99
        private const val MARKER_RESEND_MILLIS = 1_000L
        private const val SYNC_RESEND_MILLIS = 500L
        private const val ACK_SLACK = 10

        private fun sequenceDistance(
            sequence: Int,
            previous: Int,
        ): Int = (sequence - previous) and 0xFF

        private fun nextSequence(sequence: Int): Int = (sequence + 1) and 0xFF

        private fun checksumValid(bytes: ByteArray): Boolean {
            var sum = 0
            bytes.forEach { sum += u8(it) }
            return sum and 0xFF == 0
        }

        private fun readU16(
            bytes: ByteArray,
            offset: Int,
        ): Int = (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

        private fun writeU16(
            out: ByteArrayOutputStream,
            value: Int,
        ) {
            out.write(value ushr 8)
            out.write(value)
        }

        private fun u8(byte: Byte): Int = byte.toInt() and 0xFF
    }
}
