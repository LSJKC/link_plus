package io.github.shaonialife.buicklinkplus.iap2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两个链路引擎通过直接交换输出字节完成握手与数据往返（无线程、无 I/O）。 */
class Iap2LinkEngineTest {
    /** 本地窗口放行不等于对端 ACK，不能提前关闭蓝牙 */
    @Test
    fun zeroRetransmissionStillNeedsRealAcknowledgementForHandoff() {
        val phone = Iap2LinkEngine(waitForPeerAcknowledgement = true)
        val car = Iap2LinkEngine(maxRetransmissions = 0)
        phone.enterNegotiating()
        car.start(0)
        exchangeUntilNormal(phone, car)
        phone.sendControl(byteArrayOf(1, 2, 3), 10)
        val output = phone.takeOutput()
        assertTrue(!phone.controlOutputAcknowledged)
        car.feed(output, 11)
        phone.feed(car.takeOutput(), 12)
        assertTrue(phone.controlOutputAcknowledged)
    }

    /** 携带数据的旧确认也不能绕过网络同步的最终确认 */
    @Test
    fun staleOrFutureAcknowledgementCannotCompleteNetworkNegotiation() {
        val phone = Iap2LinkEngine(waitForPeerAcknowledgement = true)
        phone.enterNegotiating()
        phone.feed(synFrame(77), 1)
        val ownSequence = parseFrames(phone.takeOutput()).single().sequence
        phone.feed(ackFrame((ownSequence - 1) and 255, byteArrayOf(1, 2)), 2)
        phone.feed(ackFrame((ownSequence + 1) and 255), 3)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertTrue(phone.pollEvent() == null)
        assertTrue(!phone.controlOutputAcknowledged)
        phone.feed(ackFrame(ownSequence), 4)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
    }

    /** 只接受本轮真实发送范围内的累积确认，错误确认不能把待确认状态清空 */
    @Test
    fun networkReadinessRejectsFutureAndOldAcknowledgements() {
        val phone = Iap2LinkEngine(waitForPeerAcknowledgement = true)
        val car = Iap2LinkEngine()
        phone.enterNegotiating()
        car.start(0)
        exchangeUntilNormal(phone, car)
        assertTrue(phone.controlOutputAcknowledged)
        phone.sendControl(byteArrayOf(1, 2, 3), 10)
        val outgoing = phone.takeOutput()
        val sequence = parseFrames(outgoing).single().sequence
        assertTrue(!phone.controlOutputAcknowledged)
        phone.feed(ackFrame((sequence + 1) and 255), 11)
        assertTrue(!phone.controlOutputAcknowledged)
        phone.feed(ackFrame((sequence - 2) and 255), 12)
        assertTrue(!phone.controlOutputAcknowledged)
        car.feed(outgoing, 13)
        phone.feed(car.takeOutput(), 14)
        assertTrue(phone.controlOutputAcknowledged)
    }

    /** 网络控制链路必须等最终确认，直连与回显先行隧道仍保留既有握手 */
    @Test
    fun controlLinkWaitsForPeerAcknowledgementOfSynchronizationEcho() {
        val phone = Iap2LinkEngine(waitForPeerAcknowledgement = true)
        val car = Iap2LinkEngine()
        phone.enterNegotiating()
        car.start(0)
        phone.feed(car.takeOutput(), 1)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        val echo = phone.takeOutput()
        assertEquals(0xC0, parseFrames(echo).single().control)
        car.feed(echo, 2)
        phone.feed(car.takeOutput(), 3)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)

        val tunnel = Iap2LinkEngine(waitForPeerAcknowledgement = true).apply { echoFirst = true }
        tunnel.enterNegotiating()
        tunnel.feed(synFrame(77), 1)
        assertEquals(Iap2LinkEngine.State.NORMAL, tunnel.state)
    }

    @Test
    fun handshakeReachesNormalOnBothSides() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()

        phone.start(0)
        accessory.start(0)

        exchange(phone, accessory)
        exchange(phone, accessory)
        exchange(phone, accessory)

        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(Iap2LinkEngine.State.NORMAL, accessory.state)
        assertEquals(4, accessory.peerSynchronization?.maxOutgoing)
        assertTrue(phone.peerSynchronization?.sessions?.any { it.id == Iap2LinkEngine.SESSION_CONTROL } == true)
    }

    /** 车机只重发 marker 时，允许在 NEGOTIATING 中补发本端 SYN 完成协商 */
    @Test
    fun initiateFromNegotiatingSendsMarkerAndSynThenReachesNormal() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        // 双方都是静默待命（只发 marker 不发起协商）
        phone.enterNegotiating()
        accessory.enterNegotiating()

        phone.initiate(0)
        val out = phone.takeOutput()
        assertArrayEquals("首帧必须是 marker", Iap2LinkEngine.MARKER, out.copyOfRange(0, 6))
        val frames = parseFrames(out)
        assertEquals("marker 之后必须紧跟一条同步包", 1, frames.size)
        assertTrue("同步包 control 必须带 SYN 位（0x80）", frames[0].control and 0x80 != 0)

        // 车机侧收到「marker + 本表 SYN」→ 回 SYN|ACK（这就是日志里应当出现的推进）
        accessory.feed(out, tick())
        assertEquals("车机侧收到我们的会话表后必须进入 NORMAL", Iap2LinkEngine.State.NORMAL, accessory.state)
        phone.feed(accessory.takeOutput(), tick())
        assertEquals("实际 phone=${phone.state}", Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(
            "双方都必须采纳对方的会话表",
            phone.peerSynchronization != null && accessory.peerSynchronization != null,
        )
    }

    /** 协商轮次用尽后停止自动发送，保留 NEGOTIATING 等待车机消息 */
    @Test
    fun stopNegotiationRetriesSilencesResends() {
        val phone = Iap2LinkEngine()
        phone.enterNegotiating()
        phone.initiate(0)
        phone.takeOutput()

        phone.advanceTime(1_000L)
        assertTrue("未停手时必须仍在重发 marker/同步包", phone.takeOutput().isNotEmpty())

        phone.stopNegotiationRetries()
        phone.advanceTime(60_000L)
        assertTrue("停手后不得再有任何出站字节", phone.takeOutput().isEmpty())
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
    }

    @Test
    fun controlPayloadRoundTripBothDirections() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val phoneToAccessory = Iap2Csm.encode(0x1D00)
        assertTrue(phone.sendControl(phoneToAccessory, 1))
        exchange(phone, accessory)
        val receivedAtAccessory = pollControls(accessory)
        assertEquals(1, receivedAtAccessory.size)
        assertArrayEquals(phoneToAccessory, receivedAtAccessory[0])

        val accessoryToPhone = Iap2Csm.encode(0x1D01, Iap2Csm.string(0, "CarPlay HU"))
        assertTrue(accessory.sendControl(accessoryToPhone, 2))
        exchange(phone, accessory)
        val receivedAtPhone = pollControls(phone)
        assertEquals(1, receivedAtPhone.size)
        assertArrayEquals(accessoryToPhone, receivedAtPhone[0])
    }

    @Test
    fun duplicateFrameIsReAcknowledgedNotRedelivered() {
        val phone = Iap2LinkEngine()
        val accessory = Iap2LinkEngine()
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val payload = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(payload, 1))
        // 截获 phone 输出的数据帧，重复喂给 accessory 两次
        val dataFrame = phone.takeOutput()
        accessory.feed(dataFrame, 2)
        accessory.feed(dataFrame, 3)

        assertEquals(1, pollControls(accessory).size)
    }

    @Test
    fun windowBacklogFlushesAfterAcknowledgement() {
        val phone = Iap2LinkEngine(maxOutgoing = 2)
        val accessory = Iap2LinkEngine(maxOutgoing = 8)
        phone.start(0)
        accessory.start(0)
        exchangeUntilNormal(phone, accessory)

        val frames = (0 until 5).map { Iap2Csm.encode(0x5000 + it) }
        frames.forEach { assertTrue(phone.sendControl(it, 10)) }
        // 窗口 2：只有前 2 帧立即发出，其余积压；ACK 后由 handleAcknowledgement 冲刷
        var now = 11L
        while (pollControls(accessory).size < 5) {
            exchange(phone, accessory)
            now += 1
            phone.advanceTime(now)
            accessory.advanceTime(now)
            assertTrue(!phone.isDead && !accessory.isDead)
        }
    }

    // ---- 协商细节回归 ----

    /** 本端回显 127，发送仍遵守车机的 5 包窗口 */
    @Test
    fun peerWindowStillLimitsBurstAfterLargeLocalEcho() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine(maxOutgoing = 5)
        phone.start(0)
        phone.takeOutput()
        car.start(0)
        phone.feed(car.takeOutput(), 1)
        val echo = phone.takeOutput()
        val echoFrame = parseFrames(echo).single { it.control == 0xc0 }
        assertEquals(
            127,
            Iap2LinkEngine.SynchronizationPayload.decode(echo.copyOfRange(9, echoFrame.frameLength - 1))?.maxOutgoing,
        )
        car.feed(echo, 2)
        // 车机接受回显后发送控制消息，用真实引擎的捎带确认完成手机侧握手
        assertTrue(car.sendControl(Iap2Csm.encode(0x5000), 3))
        phone.feed(car.takeOutput(), 4)
        assertEquals(1, pollControls(phone).size)
        car.feed(phone.takeOutput(), 5)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertEquals(5, phone.peerSynchronization?.maxOutgoing)

        repeat(7) { index -> phone.sendControl(Iap2Csm.encode(0x5000 + index), 610) }
        val firstBurst = phone.takeOutput()
        assertEquals("未确认数据不得超过车机声明的五包窗口", 5, parseFrames(firstBurst).size)
        car.feed(firstBurst, 611)
        assertEquals(5, pollControls(car).size)
        phone.feed(car.takeOutput(), 612)
        val remainder = phone.takeOutput()
        assertEquals("确认后应继续发送积压的两包", 2, parseFrames(remainder).size)
        car.feed(remainder, 613)
        assertEquals(2, pollControls(car).size)
    }

    /** 新版启动帧未确认时使用同一序号重传，不能只等待永远不会返回的启动信息 */
    @Test
    fun unacknowledgedAvailabilityIsRetransmittedWithSameSequence() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        val request = Iap2Csm.encode(Iap2MessageIds.CARPLAY_AVAILABILITY, Iap2Csm.group(1, Iap2Csm.u8(0, 1)))
        phone.sendControl(request, 100)
        val original = phone.takeOutput()
        phone.advanceTime(4_100)
        assertArrayEquals("未确认启动帧应按原序号重传", original, phone.takeOutput())
    }

    /** 检查 SYN|ACK 使用新的发送序号 */
    @Test
    fun synAckUsesFreshSequenceNumber() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput() // marker + 我方 SYN（seq=99）

        phone.feed(synFrame(peerSequence = 161), 1)
        val frames = parseFrames(phone.takeOutput())
        val synAck = frames.single { it.control == 0xC0 }

        assertEquals("ack 必须回显对端 SYN 序号", 161, synAck.ack)
        assertEquals("SYN-ACK 序号必须比 SYN 大 1", 100, synAck.sequence)
    }

    /** 协商期间每条新 SYN 都需回显 SYN|ACK */
    @Test
    fun everySynGetsASynAckReply() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        // 车机连续三轮用不同序号重开协商（复刻日志 161 → 191 → 40）
        val sequences = listOf(161, 191, 40)
        val replies = ArrayList<Pair<Int, Int>>() // (synAckSeq, ack)
        sequences.forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            parseFrames(phone.takeOutput())
                .filter { it.control == 0xC0 }
                .forEach { replies += it.sequence to it.ack }
        }

        assertEquals("每条 SYN 都应有 SYN|ACK", sequences.size, replies.size)
        assertEquals(sequences, replies.map { it.second })
        // 序号逐次递增（99 → 100 → 101 → 102）
        assertEquals(listOf(100, 101, 102), replies.map { it.first })
    }

    /** 同一 SYN 重传时原样回显，不推进序号 */
    @Test
    fun retransmittedSynKeepsTheSameEcho() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        phone.feed(synFrame(peerSequence = 77), 1)
        val first = phone.takeOutput()
        phone.feed(synFrame(peerSequence = 77), 2)
        val again = phone.takeOutput()

        assertArrayEquals("重传同一 SYN 应原样重放", first, again)
        assertEquals(100, parseFrames(first).single { it.control == 0xC0 }.sequence)
    }

    /** 裸 ACK 与 SYN|ACK 使用不同序号 */
    @Test
    fun ackSequenceNeverDuplicatesTheSynAck() {
        val phone = Iap2LinkEngine()
        phone.start(0)
        phone.takeOutput()

        val emitted = ArrayList<Int>()
        listOf(161, 191, 40).forEachIndexed { index, peerSeq ->
            phone.feed(synFrame(peerSequence = peerSeq), (index + 1).toLong())
            emitted += parseFrames(phone.takeOutput()).map { it.sequence }
        }
        assertEquals("序号必须严格递增且无重复", emitted.size, emitted.distinct().size)
        assertEquals(emitted.sorted(), emitted)
    }

    /** 重新探测可以跨任意分片，新同步后只能保留新会话的数据和计时器 */
    @Test
    fun peerRestartClearsOldQueuesAndAcceptsFragmentedDetect() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3)
        var restarts = 0
        phone.onPeerRestarted = { restarts = it }
        phone.enterNegotiating()
        phone.feed(synFrame(77), 1)
        phone.takeOutput()
        phone.feed(peerFrame(0x40, 78, 100), 2)
        repeat(7) { phone.sendControl(Iap2Csm.encode(0x5000 + it), 3) }
        phone.feed(peerFrame(0x40, 79, 100, Iap2LinkEngine.SESSION_CONTROL, Iap2Csm.encode(0x5000)), 3)
        // 不取出旧输出，同时保留未确认帧和窗口后的积压帧
        val restart = synFrame(150)
        phone.feed(restart.copyOfRange(0, 2), 4)
        phone.feed(restart.copyOfRange(2, 5), 5)
        assertEquals(0, restarts)
        phone.feed(restart.copyOfRange(5, restart.size), 6)
        assertEquals(1, restarts)
        assertTrue("旧会话尚未投递的事件不能进入新控制会话", pollControls(phone).isEmpty())
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        val output = parseFrames(phone.takeOutput())
        assertEquals(1, output.size)
        assertEquals(0xC0, output.single().control)
        assertEquals(150, output.single().ack)
        phone.advanceTime(100_000)
        assertTrue("旧未确认包和计时器不能在恢复后继续重传", phone.takeOutput().isEmpty())
        val message = Iap2Csm.encode(0x1D01)
        phone.feed(peerFrame(0x40, 151, 100, Iap2LinkEngine.SESSION_CONTROL, message), 100_001)
        assertArrayEquals(message, pollControls(phone).single())
    }

    /** 旧同步重传不能重置会话，也不能倒退已经接收业务数据的序号水位 */
    @Test
    fun repeatedSynAfterNormalReplaysEchoWithoutRestartOrDuplicateDelivery() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3)
        phone.enterNegotiating()
        val syn = synFrame(77)
        phone.feed(syn, 1)
        val echo = phone.takeOutput()
        val data = peerFrame(0x40, 78, 100, Iap2LinkEngine.SESSION_CONTROL, Iap2Csm.encode(0x1D01))
        phone.feed(data, 2)
        assertEquals(1, pollControls(phone).size)
        phone.takeOutput()
        phone.feed(syn.copyOfRange(6, syn.size), 3)
        assertArrayEquals(echo, phone.takeOutput())
        assertEquals(0, phone.peerRestartCount)
        phone.feed(data, 4)
        assertTrue("重放 SYN 后也不能重复交付旧业务帧", pollControls(phone).isEmpty())
    }

    /** 独立新 SYN、伪标记、非法会话表和 SYN|ACK 都不能触发恢复 */
    @Test
    fun restartRequiresExactDetectAndValidPureSyn() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3)
        phone.enterNegotiating()
        phone.feed(synFrame(77), 1)
        phone.takeOutput()
        phone.feed(synFrame(150).copyOfRange(6, synFrame(150).size), 2)
        val falseDetect = Iap2LinkEngine.MARKER.copyOf().also { it[5] = 0x11 }
        phone.feed(falseDetect + synFrame(151).drop(6).toByteArray(), 3)
        val validPayload = synFrame(152).let { it.copyOfRange(15, it.lastIndex) }
        phone.feed(Iap2LinkEngine.MARKER + peerFrame(0xC0, 152, 100, 0, validPayload), 4)
        phone.feed(Iap2LinkEngine.MARKER + peerFrame(0x80, 153, 0, 0, validPayload.copyOf(10)), 5)
        val zeroWindow = validPayload.copyOf().also { it[1] = 0 }
        phone.feed(Iap2LinkEngine.MARKER + peerFrame(0x80, 154, 0, 0, zeroWindow), 6)
        phone.feed(Iap2LinkEngine.MARKER + peerFrame(0x80, 155, 0, 10, validPayload), 7)
        val duplicateSession = validPayload.copyOf().also { it[13] = it[10] }
        phone.feed(Iap2LinkEngine.MARKER + peerFrame(0x80, 156, 0, 0, duplicateSession), 8)
        phone.feed(Iap2LinkEngine.MARKER, 9)
        phone.feed(peerFrame(0x40, 78, 100, Iap2LinkEngine.SESSION_CONTROL, Iap2Csm.encode(0x5000)), 10)
        phone.takeOutput()
        phone.feed(synFrame(157).drop(6).toByteArray(), 11)
        assertEquals(0, phone.peerRestartCount)
        assertTrue(phone.takeOutput().isEmpty())
        phone.feed(synFrame(158), 12)
        assertEquals(1, phone.peerRestartCount)
    }

    /** 默认蓝牙模式不启用网络重协商，网络模式的恢复次数有明确上限 */
    @Test
    fun peerRestartIsOptInAndBounded() {
        val bluetooth = Iap2LinkEngine()
        val network = Iap2LinkEngine(maxPeerRestarts = 3)
        listOf(bluetooth, network).forEach { engine ->
            engine.enterNegotiating()
            engine.feed(synFrame(77), 1)
            engine.takeOutput()
            listOf(150, 160, 170, 180).forEachIndexed { index, sequence ->
                engine.feed(synFrame(sequence), index + 2L)
                engine.takeOutput()
            }
        }
        assertEquals(0, bluetooth.peerRestartCount)
        assertEquals(3, network.peerRestartCount)
        assertEquals(Iap2LinkEngine.State.NORMAL, network.state)
    }

    /** 完整探测才通知回应，虚拟链路限制回应频率 */
    @Test
    fun exactDetectReportsEachMarkerWithoutResetAndIgnoresLateAck() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3)
        var detects = 0
        var probeRounds = 0
        phone.onPeerDetect = { newProbe ->
            detects++
            if (newProbe) probeRounds++
        }
        phone.enterNegotiating()
        phone.feed(synFrame(77), 1)
        phone.takeOutput()
        phone.feed(Iap2LinkEngine.MARKER.copyOfRange(0, 3), 2)
        assertEquals(1, detects)
        phone.feed(Iap2LinkEngine.MARKER.copyOfRange(3, 6), 3)
        repeat(100) { phone.feed(Iap2LinkEngine.MARKER, 4) }
        assertEquals(102, detects)
        assertEquals(1, probeRounds)
        assertEquals(0, phone.peerRestartCount)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        assertTrue(phone.takeOutput().isEmpty())
        phone.feed(peerFrame(0x40, 78, 100), 5)
        assertTrue(phone.awaitingPeerSynchronization)
        phone.feed(Iap2LinkEngine.MARKER, 6)
        assertEquals(103, detects)
        assertEquals(1, probeRounds)
        phone.feed(synFrame(150).drop(6).toByteArray(), 7)
        assertEquals(1, phone.peerRestartCount)
        val bluetooth = Iap2LinkEngine()
        bluetooth.onPeerDetect = { error("蓝牙不应启用网络探测应答") }
        bluetooth.enterNegotiating()
        bluetooth.feed(synFrame(77), 1)
        bluetooth.feed(Iap2LinkEngine.MARKER, 2)
    }

    /** 明确探测后的车机重启可沿用同一序号和同一会话表，迟到确认不能吞掉恢复 */
    @Test
    fun freshDetectWithLateAckAllowsIdenticalSynchronizationRestart() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3, waitForPeerAcknowledgement = true)
        phone.enterNegotiating()
        val syn = synFrame(77)
        phone.feed(syn, 1)
        phone.takeOutput()
        phone.feed(peerFrame(0x40, 78, 100), 2)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
        phone.feed(Iap2LinkEngine.MARKER, 3)
        phone.feed(peerFrame(0x40, 78, 100), 4)
        phone.feed(syn.copyOfRange(6, syn.size), 5)
        assertEquals(1, phone.peerRestartCount)
        assertEquals(Iap2LinkEngine.State.NEGOTIATING, phone.state)
        assertEquals(0xC0, parseFrames(phone.takeOutput()).single().control)
        phone.feed(peerFrame(0x40, 78, 100), 6)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
    }

    /** 重复业务和未知会话都不证明旧会话正在续传，不能吞掉新的探测意图 */
    @Test
    fun lateDuplicateAndUnknownSessionCannotCancelPendingRestart() {
        val phone = Iap2LinkEngine(maxPeerRestarts = 3)
        phone.enterNegotiating()
        val syn = synFrame(77)
        phone.feed(syn, 1)
        val data = peerFrame(0x40, 78, 100, Iap2LinkEngine.SESSION_CONTROL, Iap2Csm.encode(0x5000))
        phone.feed(data, 2)
        pollControls(phone)
        phone.takeOutput()
        phone.feed(Iap2LinkEngine.MARKER, 3)
        phone.feed(data, 4)
        phone.feed(peerFrame(0x40, 79, 100, 99, Iap2Csm.encode(0x5000)), 5)
        assertTrue(phone.awaitingPeerSynchronization)
        phone.feed(syn.copyOfRange(6, syn.size), 6)
        assertEquals(1, phone.peerRestartCount)
        assertTrue(pollControls(phone).isEmpty())
    }

    // ---- 工具 ----

    /** 构造具有正确校验和的对端帧，用于重传、异常同步及新会话数据检查 */
    private fun peerFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int = 0,
        payload: ByteArray? = null,
    ): ByteArray {
        val length = 9 + (payload?.size?.plus(1) ?: 0)
        val frame = ByteArray(length)
        frame[0] = 0xFF.toByte()
        frame[1] = 0x5A
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        frame[4] = control.toByte()
        frame[5] = sequence.toByte()
        frame[6] = ack.toByte()
        frame[7] = session.toByte()
        frame[8] = (-frame.take(8).sumOf { it.toInt() and 0xFF }).toByte()
        if (payload != null) {
            payload.copyInto(frame, 9)
            frame[frame.lastIndex] = (-payload.sumOf { it.toInt() and 0xFF }).toByte()
        }
        return frame
    }

    /** 发起方必须立即确认同步应答，不能依赖后续业务数据捎带确认 */
    @Test
    fun initiatorImmediatelyAcknowledgesSynAck() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.enterNegotiating()
        car.feed(phone.takeOutput(), 1)
        val reply = car.takeOutput()
        val peerSequence = parseFrames(reply).single().sequence
        phone.feed(reply, 2)
        val ack = parseFrames(phone.takeOutput()).single()
        assertEquals(0x40, ack.control)
        assertEquals(9, ack.frameLength)
        assertEquals(peerSequence, ack.ack)
        assertEquals(Iap2LinkEngine.State.NORMAL, phone.state)
    }

    /** 损坏载荷后紧跟有效帧时，有效帧必须保留并正常投递 */
    @Test
    fun invalidPayloadDoesNotConsumeFollowingFrameHeader() {
        val phone = Iap2LinkEngine()
        val car = Iap2LinkEngine()
        phone.start(0)
        car.start(0)
        exchangeUntilNormal(phone, car)
        exchange(phone, car)
        phone.takeOutput()
        assertTrue(phone.sendControl(Iap2Csm.encode(0x1D00), 10))
        val corrupt = phone.takeOutput().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val expected = Iap2Csm.encode(0x1D02)
        assertTrue(phone.sendControl(expected, 11))
        car.feed(corrupt + phone.takeOutput(), 12)
        val received = pollControls(car)
        assertEquals(1, received.size)
        assertArrayEquals(expected, received.single())
    }

    private class ParsedFrame(
        val control: Int,
        val sequence: Int,
        val ack: Int,
        val frameLength: Int,
    )

    /** 解析一段输出里的所有 FF5A 帧（跳过前置 marker）。 */
    private fun parseFrames(bytes: ByteArray): List<ParsedFrame> {
        val frames = ArrayList<ParsedFrame>()
        var offset = 0
        while (offset + 9 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte() || bytes[offset + 1] != 0x5A.toByte()) {
                offset++
                continue
            }
            val frame = parseFrame(bytes, offset)
            frames += frame
            offset += frame.frameLength
        }
        return frames
    }

    /** 解析一帧 FF5A（长度含 9 字节头 + 载荷 + 载荷校验和）。 */
    private fun parseFrame(
        bytes: ByteArray,
        offset: Int,
    ): ParsedFrame {
        require(bytes[offset] == 0xFF.toByte() && bytes[offset + 1] == 0x5A.toByte()) {
            "offset=$offset 不是 FF5A 帧头"
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        return ParsedFrame(
            control = bytes[offset + 4].toInt() and 0xFF,
            sequence = bytes[offset + 5].toInt() and 0xFF,
            ack = bytes[offset + 6].toInt() and 0xFF,
            frameLength = length,
        )
    }

    /** 构造带 marker 的 SYN，9 字节头中 seq 偏移为 5，头校验和偏移为 8 */
    private fun ackFrame(
        ack: Int,
        payload: ByteArray? = null,
    ): ByteArray {
        val length = if (payload == null) 9 else payload.size + 10
        val frame = byteArrayOf(0xFF.toByte(), 0x5A, 0, length.toByte(), 0x40, 78, ack.toByte(), 10, 0)
        frame[8] = (-frame.take(8).sumOf { it.toInt() and 255 }).toByte()
        return if (payload == null) frame else frame + payload + byteArrayOf((-payload.sumOf { it.toInt() and 255 }).toByte())
    }

    private fun synFrame(peerSequence: Int): ByteArray {
        val engine = Iap2LinkEngine()
        engine.start(0)
        val raw = engine.takeOutput()
        val synOffset = 6 // 前置 marker 6 字节
        raw[synOffset + 5] = peerSequence.toByte()
        // 头校验和 = 前 8 字节之和的补码
        var sum = 0
        for (index in synOffset until synOffset + 8) sum += raw[index].toInt() and 0xFF
        raw[synOffset + 8] = (-sum and 0xFF).toByte()
        return raw
    }

    private fun exchange(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        b.feed(a.takeOutput(), tick())
        a.feed(b.takeOutput(), tick())
    }

    private fun exchangeUntilNormal(
        a: Iap2LinkEngine,
        b: Iap2LinkEngine,
    ) {
        var guard = 0
        while ((a.state != Iap2LinkEngine.State.NORMAL || b.state != Iap2LinkEngine.State.NORMAL) &&
            guard++ < 10
        ) {
            exchange(a, b)
        }
    }

    private fun pollControls(engine: Iap2LinkEngine): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            when (val event = engine.pollEvent() ?: return out) {
                is Iap2LinkEngine.Event.Control -> out += event.bytes
                is Iap2LinkEngine.Event.Dead -> error("engine died: ${event.reason}")
            }
        }
    }

    private var clock = 0L

    private fun tick(): Long = ++clock
}
