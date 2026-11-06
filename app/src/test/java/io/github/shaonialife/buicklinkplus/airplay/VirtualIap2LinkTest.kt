package io.github.shaonialife.buicklinkplus.airplay

import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import io.github.shaonialife.buicklinkplus.iap2.Iap2LinkEngine
import io.github.shaonialife.buicklinkplus.iap2.MfiCertificateHint
import io.github.shaonialife.buicklinkplus.iap2.MfiLengthMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按 AADisplay 记录的 MIB3 时序，检查探测、SYN、身份识别、MFi 和订阅应答 */

/** 按 FF5A 长度拆帧，跳过 6 字节 marker */
private fun splitLinkFrames(chunk: ByteArray): List<ByteArray> {
    val out = ArrayList<ByteArray>()
    var off = 0
    while (off + 9 <= chunk.size) {
        if (chunk[off] != 0xFF.toByte() || chunk[off + 1] != 0x5A.toByte()) {
            off += 1
            continue
        }
        // 长度字段 = 整帧长度（含 9B 头），非载荷长
        val frameLen = ((chunk[off + 2].toInt() and 0xFF) shl 8) or (chunk[off + 3].toInt() and 0xFF)
        // 帧结构：9B 头（含头校验）+ 载荷 + 1B 尾部载荷校验（len=整帧长度）；
        // 裸 ACK/回显帧无载荷（整帧恰 9B）——跳过而非中断，同块其后的数据帧照常解析
        if (frameLen < 9 || off + frameLen > chunk.size) break
        if (frameLen > 9) out.add(chunk.copyOfRange(off + 9, off + frameLen - 1))
        off += frameLen
    }
    return out
}

class VirtualIap2LinkTest {
    internal class Harness {
        val controlSent = ArrayList<ByteArray>()
        val allControl = ArrayList<ByteArray>()
        val eventSent = ArrayList<ByteArray>()
        val logs = ArrayList<String>()
        var car = Iap2LinkEngine()
        val link =
            VirtualIap2Link(
                log = { logs.add(it) },
                sendEventIap = {
                    eventSent.add(it)
                    true
                },
                sendControlIap = {
                    controlSent.add(it)
                    allControl.add(it)
                },
                tunnelWriter = { null },
                // 关闭节拍线程：测试内确定性驱动（无重传干扰）
                alive = { false },
                nowMillis = { clock },
                localBluetoothMac = "02:AA:BB:CC:DD:11",
                localDeviceName = "TestPhone",
            )
        var clock = 0L
        var now = 1L

        /** 车机→手机：喂字节并冲刷链路出站。 */
        fun carToLink(): Boolean {
            val out = car.takeOutput()
            if (out.isEmpty()) return false
            link.onCarBytes(out)
            return true
        }

        /** 手机→车机：把控制通道出站字节喂回车机引擎。 */
        fun linkToCar() {
            while (controlSent.isNotEmpty()) {
                val batch = controlSent.toList()
                controlSent.clear()
                batch.forEach { car.feed(it, now) }
            }
        }

        fun exchange() {
            carToLink()
            linkToCar()
            now += 600
            clock += 600
            // 车机侧驱动计时器：采纳对端表后按超时机制补裸 ACK（真机行为同此——
            // BT 日志里我们的 SYN|ACK 之后车机立即回 ACK）
            car.advanceTime(now)
            // 完整传递确认和确认后放行的积压数据，不能只传一轮就断言突发已全部送达
            // 确认均由真实引擎生成，不伪造确认或扩大模拟车机的窗口
            repeat(32) {
                linkToCar()
                if (!carToLink()) return
            }
            error("模拟链路交互超过上限，可能出现确认循环")
        }

        fun exchangeUntilNormal(rounds: Int = 8) {
            repeat(rounds) {
                exchange()
                if (car.state == Iap2LinkEngine.State.NORMAL) return
            }
        }

        /** 车机发一条 CSM，返回手机随后经控制通道发出的 CSM 消息号序列。 */
        fun carSends(
            messageId: Int,
            body: ByteArray = ByteArray(0),
        ): List<Int> {
            val before = controlFrames().size
            assertTrue(car.sendControl(Iap2Csm.encode(messageId, body), now))
            exchange()
            val ids = controlFrames().drop(before).map { it.second }
            return ids
        }

        /** 逐链路帧解析 CSM，每帧新建解析器，避免同步载荷污染消息边界 */
        fun controlFrames(): List<Pair<ByteArray, Int>> {
            val out = ArrayList<Pair<ByteArray, Int>>()
            for (chunk in allControl) {
                splitLinkFrames(chunk).forEach { payload ->
                    Iap2Csm.Parser().offer(payload).forEach { frame ->
                        out.add(payload to frame.messageId)
                    }
                }
            }
            return out
        }

        /** 最近一条指定消息号的完整 CSM 帧字节（Frida 常量逐字节比对用）。 */
        fun lastRawFrameOf(messageId: Int): ByteArray? = controlFrames().lastOrNull { it.second == messageId }?.first

        /** 已发 0xAA02 挑战的长度序列（param 0 长度；多条时按出现顺序）。 */
        fun challengeLengths(): List<Int> =
            controlFrames()
                .filter { it.second == 0xAA02 }
                .map { (raw, _) ->
                    Iap2Csm
                        .parseParams(raw.copyOfRange(6, raw.size))
                        .first()
                        .value.size
                }
    }

    @Test
    fun mib3ArmSendsExactlyOneDetectOnControlChannel() {
        val h = Harness()
        h.link.armMib3()
        h.link.armMib3()
        assertEquals(listOf(Iap2LinkEngine.MARKER.toList()), h.controlSent.map { it.toList() })
        assertTrue(h.eventSent.isEmpty())
    }

    @Test
    fun preArmMarkerGetsNoReply() {
        val h = Harness()
        h.link.begin()
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertTrue(h.controlSent.isEmpty())
        assertTrue(h.eventSent.isEmpty())
    }

    /** 检查分片探测完整到达后再回应 */
    @Test
    fun firstFragmentedDetectRepliesBeforeAnyFallbackSynchronization() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.controlSent.clear()
            h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOfRange(0, 2))
            assertTrue(h.controlSent.isEmpty())
            h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOfRange(2, 6))
            assertEquals(1, h.controlSent.size)
            assertTrue(h.controlSent.single().contentEquals(Iap2LinkEngine.MARKER))
            assertTrue(h.controlFrames().isEmpty())
            assertTrue(h.eventSent.isEmpty())
        } finally {
            h.link.close()
        }
    }

    /** 首次应答丢失后允许重探测重试，但连续回显有频率及总次数上限 */
    @Test
    fun repeatedNormalDetectCanRecoverLostReplyWithoutEchoFlood() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            val before = h.allControl.size
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            h.controlSent.clear()
            repeat(100) { h.link.onCarBytes(Iap2LinkEngine.MARKER) }
            assertEquals(before + 1, h.allControl.size)
            h.clock += 999L
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertEquals(before + 1, h.allControl.size)
            h.clock++
            h.link.onCarBytes(Iap2LinkEngine.MARKER)
            assertEquals(before + 2, h.allControl.size)
            repeat(10) {
                h.clock += 1_000L
                h.link.onCarBytes(Iap2LinkEngine.MARKER)
            }
            assertEquals(before + 3, h.allControl.size)
            assertTrue(h.allControl.drop(before).all { it.contentEquals(Iap2LinkEngine.MARKER) })
            assertEquals(0, h.link.controlGeneration)
            assertEquals(1, h.controlFrames().count { it.second == 0x1D00 })
            assertTrue(h.eventSent.isEmpty())
        } finally {
            h.link.close()
        }
    }

    /** 车机重复 marker 时补发本端同步表，出站仍使用控制通道 */
    @Test
    fun repeatedMarkersUpgradeToControlChannelHandshake() {
        val h = Harness()
        h.link.armMib3()
        val detect = h.controlSent.size
        // 第一个完整探测先回标记，等车机发同步，不抢发本端会话表
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(detect + 1, h.controlSent.size)
        assertTrue(h.controlSent.last().contentEquals(Iap2LinkEngine.MARKER))
        // 第 2 个：升级为 marker + 同步包
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        val sent = h.controlSent.drop(detect + 1)
        assertTrue("重复 marker 后必须补发协商字节", sent.isNotEmpty())
        val payload = sent.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        assertTrue(
            "补发的第一批必须含 marker（FF 55）",
            payload.size >= 6 && payload[0] == 0xFF.toByte() && payload[1] == 0x55.toByte(),
        )
        assertTrue(
            "marker 之后必须紧跟 FF5A 会话表（车机等的就是它）",
            payload.size >= 12 && payload[6] == 0xFF.toByte() && payload[7] == 0x5A.toByte(),
        )
        assertTrue(
            "会话表必须带 SYN 位（0x80）",
            (payload[10].toInt() and 0x80) != 0,
        )
        assertTrue("出站绝不能落到事件通道", h.eventSent.isEmpty())
    }

    @Test
    fun fullMib3HandshakeWithBurstAndSubscriptions() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        assertEquals(Iap2LinkEngine.State.NORMAL, h.car.state)
        // detect(1) + SYN|ACK + 0x1D00 已走控制通道；车机已收到 0x1D00
        assertTrue(h.controlFrames().map { it.second }.contains(0x1D00))

        // 0x1D01 → 0x1D02 + 0xAA00
        assertEquals(
            listOf(0x1D02, 0xAA00),
            h.carSends(0x1D01, Iap2Csm.string(0, "Audi_MMI_0001") + Iap2Csm.string(1, "AUDI 5163")),
        )

        // 0xAA06（auth serial）→ 再发 0xAA00
        assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.string(0, "serial")))

        // 0xAA01（证书）→ 0xAA02 挑战 20B（MIB3 真机长度；DiPlay 形态为 32B）
        val challengeFrames = h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(0xAA02), challengeFrames)
        val challengeBody =
            h
                .lastRawFrameOf(0xAA02)!!
                .copyOfRange(6, h.lastRawFrameOf(0xAA02)!!.size)
                .let { Iap2Csm.parseParams(it).first() }
        assertEquals(20, challengeBody.value.size)

        // 0xAA03（签名应答）→ 0xAA05 + 突发（0x4E0C/0xA100/0x4E0A/0x4E0B/0x4E09/0xFFFA/0xFFF0）
        assertEquals(
            listOf(0xAA05, 0x4E0C, 0xA100, 0x4E0A, 0x4E0B, 0x4E09, 0xFFFA, 0xFFF0),
            h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96))),
        )

        // 订阅应答：0x5000 → 0x5001 空态；0x4157 → 0x4158；0x4154 → 0x4155；0x4170 → 0x4171
        assertEquals(listOf(0x5001), h.carSends(0x5000))
        assertEquals(listOf(0x4158), h.carSends(0x4157))
        assertEquals(listOf(0x4155), h.carSends(0x4154))
        assertEquals(listOf(0x4171), h.carSends(0x4170))
    }

    /** 最终 ACK 后才发送身份请求 */
    @Test
    fun networkIdentificationWaitsForFinalAcknowledgement() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.car.start(0)
            h.carToLink()
            assertTrue(h.controlFrames().isEmpty())
            h.linkToCar()
            h.carToLink()
            assertEquals(listOf(0x1D00), h.controlFrames().map { it.second })
            assertTrue(h.eventSent.isEmpty())
        } finally {
            h.link.close()
        }
    }

    /** 检查新版网络能力、序列号换证书和认证后状态顺序 */
    @Test
    fun modernNetworkAuthenticationUsesSerialAndOnlyFourDeviceUpdates() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            val identity = modernIdentity()
            assertEquals(listOf(0x1D02, 0xAA00), h.carSends(0x1D01, identity))
            assertEquals(listOf(Iap2Csm.Param(0, ByteArray(0))), Iap2Csm.parseParams(h.lastRawFrameOf(0xAA00)!!.drop(6).toByteArray()))
            assertEquals(listOf(0xAA00), h.carSends(0xAA06, Iap2Csm.param(0, ByteArray(16))))
            assertEquals(6, h.lastRawFrameOf(0xAA00)!!.size)
            assertTrue(h.carSends(0xAA06, Iap2Csm.param(0, ByteArray(16))).isEmpty())
            val certificate = Iap2Csm.param(0, ByteArray(608) { (it * 17 + 19).toByte() })
            MfiCertificateHint.recordResponse(certificate, 32)
            assertEquals(listOf(0xAA02), h.carSends(0xAA01, certificate))
            assertEquals(listOf(32), h.challengeLengths())
            assertTrue(h.controlFrames().none { it.second == 0xAA05 })
            assertEquals(
                listOf(0xAA05, 0x4E0C, 0x4E0B, 0x4E0A, 0x4E09),
                h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(64) { 1 })),
            )
            val expectedUuid =
                java.util.UUID
                    .nameUUIDFromBytes(
                        "02:AA:BB:CC:DD:11".encodeToByteArray(),
                    ).toString()
                    .uppercase(java.util.Locale.ROOT)
            for ((id, value) in listOf(0x4E0C to expectedUuid, 0x4E0A to "zh", 0x4E09 to "TestPhone")) {
                assertEquals(
                    Iap2Csm.encode(id, Iap2Csm.string(0, value)).toList(),
                    h.lastRawFrameOf(id)!!.toList(),
                )
            }
            assertTrue(h.controlFrames().none { it.second in listOf(0x4300, 0xA100, 0xFFFA, 0xFFF0) })
            assertEquals(listOf(0x5001), h.carSends(0x5000))
            assertTrue(h.eventSent.isEmpty())
        } finally {
            h.link.close()
        }
    }

    @Test
    fun nowPlayingIdleReplyIsTheFridaConstantVerbatim() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "x"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        h.carSends(0x5000)
        // 逐字节等于 Frida 抓包常量——字段布局只存在于真机行为里，重新编码极易走样
        assertEquals(
            VirtualIap2Link.MIB3_NOW_PLAYING_UPDATE_IDLE.toList(),
            h.lastRawFrameOf(0x5001)!!.toList(),
        )
        assertEquals(
            VirtualIap2Link.MIB3_COMMUNICATIONS_UPDATE_IDLE.toList(),
            run {
                h.carSends(0x4157)
                h.lastRawFrameOf(0x4158)!!.toList()
            },
        )
    }

    @Test
    fun tunnelMfiUsesRememberedLengthPerCar() {
        // 与其它测试不同身份，避免进程内记忆串扰
        val identity = "MemCar MemModel MemMfr tunnel"
        MfiLengthMemory.recordSuccess(null, identity, 32)
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "MemCar") + Iap2Csm.string(1, "MemModel") + Iap2Csm.string(2, "MemMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        // 记忆命中：32B 直接排第一，不再从 20B 试起
        assertEquals(listOf(32), h.challengeLengths())
    }

    @Test
    fun silentChallengeRetriesNextLengthWithinSession() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "SilCar") + Iap2Csm.string(1, "SilMfr"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(642)))
        assertEquals(listOf(20), h.challengeLengths())
        // 20B 静默：推进时钟越过 6s 窗口，喂一个 marker 触发节拍检查（drainLocked）
        h.clock += 7_000
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(listOf(20, 32), h.challengeLengths())
        // 32B 得到应答 → 0xAA05 + 突发照常
        val after = h.controlFrames().size
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(96)))
        assertTrue(
            "frames=${h.controlFrames().drop(after).map { it.second }}",
            h
                .controlFrames()
                .drop(after)
                .map { it.second }
                .containsAll(listOf(0xAA05, 0x4E0C, 0xFFFA)),
        )
    }

    /** 检查 0x6802 从参数 1 读取 HID 数据，跳过参数 0 的报告类型 */
    @Test
    fun hidReportComesFromParamOneNotParamZero() {
        // 单行书写：ktlint 对该规则要求「多行则一参数一行」，单行更贴近原始帧的可读形式
        val body = byteArrayOf(0x00, 0x06, 0x00, 0x00, 0x00, 0x03, 0x00, 0x05, 0x00, 0x01, 0x04)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x04), report?.toList())
    }

    @Test
    fun hidReportFallsBackToLastShortParamWhenOnlyOneGiven() {
        val body = byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x08)
        val report = VirtualIap2Link.hidReportFromParams(Iap2Csm.parseParams(body))
        assertEquals(listOf<Byte>(0x08), report?.toList())
    }

    /** 同一证书的蓝牙签名应答可以提示首试长度，但仍必须在隧道上重新挑战 */
    @Test
    fun matchingCertificatePrioritizesObservedLengthWithoutSkippingAuthentication() {
        val certificate = Iap2Csm.param(0, ByteArray(612) { (it * 13 + 7).toByte() })
        MfiCertificateHint.recordResponse(certificate, 32)
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "同证书测试车机"))
        h.carSends(0xAA01, certificate)
        assertEquals(listOf(32), h.challengeLengths())
        assertTrue(h.controlFrames().none { it.second == 0xAA05 })
        // 重复证书与空应答都不能提前换档或标记成功
        h.carSends(0xAA01, certificate)
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(0)))
        assertEquals(listOf(32), h.challengeLengths())
        assertTrue(h.controlFrames().none { it.second == 0xAA05 })
        h.link.close()
    }

    /** 挑战全部超时后不报认证成功，迟到签名不恢复旧挑战 */
    @Test
    fun silentAuthenticationDoesNotSendSuccessOrInitializationBurst() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        h.carSends(0x1D01, Iap2Csm.string(0, "静默认证测试车机"))
        h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(613) { 4 }))
        listOf(7_000L, 4_000L, 4_000L).forEach { delay ->
            h.clock += delay
            h.link.onCarBytes(ByteArray(0))
            h.exchange()
        }
        assertEquals(listOf(20, 32, 16), h.challengeLengths())
        assertEquals("认证无应答", h.link.controlSessionStatus)
        h.carSends(0xAA03, Iap2Csm.param(0, ByteArray(64) { 1 }))
        assertTrue(h.controlFrames().none { it.second in listOf(0xAA05, 0x4E0C, 0xFFFA, 0xFFF0) })
        h.link.close()
    }

    /** 退役后即使宿主条件又变为存活，旧事件也不能重新启动链路 */
    @Test
    fun retiredLinkIgnoresLateEventsAndArm() {
        val h = Harness()
        h.link.armMib3()
        h.link.close()
        val before = h.allControl.size
        h.link.armMib3()
        h.car.start(0)
        h.link.onCarBytes(h.car.takeOutput())
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        h.link.onCarBytes(Iap2LinkEngine.MARKER)
        assertEquals(before, h.allControl.size)
    }

    /** 新探测与同步到来后清理半截消息及旧认证状态，重新获取身份和证书 */
    @Test
    fun peerRestartStartsFreshControlSessionWithoutOldParserOrChallenge() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val identity = Iap2Csm.string(0, "重协商恢复测试车机")
        val certificate = Iap2Csm.param(0, ByteArray(613) { 9 })
        h.carSends(0x1D01, identity)
        h.carSends(0xAA01, certificate)
        assertEquals(1, h.challengeLengths().size)
        // 旧控制会话留下一个声明长度为 256 的半截 CSM
        h.car.sendControl(byteArrayOf(0x40, 0x40, 0x01, 0x00, 0xAA.toByte(), 0x01), h.now)
        h.exchange()
        val before = h.controlFrames().size
        val oldChallengeCount = h.challengeLengths().size
        val freshPeer = Iap2LinkEngine()
        freshPeer.start(0)
        val start = freshPeer.takeOutput()
        val syncPayload = start.copyOfRange(15, start.lastIndex)
        // 探测跨分片到达，新 SYN 与探测尾部在同一个分片
        h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOfRange(0, 3))
        h.link.onCarBytes(
            Iap2LinkEngine.MARKER.copyOfRange(3, 6) + networkPeerFrame(0x80, 150, 0, 0, syncPayload),
        )
        assertTrue("重协商也必须等最终确认", h.controlFrames().drop(before).isEmpty())
        h.link.onCarBytes(networkPeerFrame(0x40, 150, 100, 0, null))
        assertEquals(listOf(0x1D00), h.controlFrames().drop(before).map { it.second })
        assertTrue(h.logs.any { it.contains("对端重新协商 1/3") })
        h.link.onCarBytes(networkPeerFrame(0x40, 151, 101, 10, Iap2Csm.encode(0x1D01, identity)), expectedGeneration = 0)
        assertEquals("旧请求的身份应答不能回灌新控制会话", listOf(0x1D00), h.controlFrames().drop(before).map { it.second })
        h.clock += 60_000
        h.link.onCarBytes(ByteArray(0))
        assertEquals("旧挑战计时器不能继续换档", oldChallengeCount, h.challengeLengths().size)
        // 身份和证书尚未重新取得，迟到的旧签名不能恢复认证
        h.link.onCarBytes(networkPeerFrame(0x40, 151, 101, 10, Iap2Csm.encode(0xAA03, Iap2Csm.param(0, ByteArray(64)))))
        assertTrue(h.controlFrames().none { it.second == 0xAA05 })
        h.link.onCarBytes(networkPeerFrame(0x40, 152, 101, 10, Iap2Csm.encode(0x1D01, identity)))
        assertEquals(listOf(0x1D00, 0x1D02, 0xAA00), h.controlFrames().drop(before).map { it.second })
        h.link.onCarBytes(networkPeerFrame(0x40, 153, 103, 10, Iap2Csm.encode(0xAA01, certificate)))
        assertEquals(oldChallengeCount + 1, h.challengeLengths().size)
        assertTrue(h.eventSent.isEmpty())
        h.link.close()
    }

    /** 正常连接中的孤立探测只回标记，不能主动发起同步或重复启动身份流程 */
    @Test
    fun markersAfterNormalDoNotStartFallbackNegotiation() {
        val h = Harness()
        h.link.armMib3()
        h.car.start(0)
        h.exchangeUntilNormal()
        val before = h.allControl.size
        repeat(3) { h.link.onCarBytes(Iap2LinkEngine.MARKER) }
        assertEquals(before + 1, h.allControl.size)
        assertTrue(h.allControl.last().contentEquals(Iap2LinkEngine.MARKER))
        assertEquals(0, h.link.controlGeneration)
        assertEquals(1, h.controlFrames().count { it.second == 0x1D00 })
        assertTrue(h.logs.none { it.contains("对端重新协商 1/3") })
        h.link.close()
    }

    /** 检查车机收到探测应答后重新同步，认证完成后恢复状态确认 */
    @Test
    fun peerWaitsForDetectReplyThenReauthenticatesOverControlTransport() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            val identity = modernIdentity("探测闭环测试车机 DetectReply")
            val certificate = Iap2Csm.param(0, ByteArray(608) { 11 })
            val signature = Iap2Csm.param(0, ByteArray(64) { 1 })
            h.carSends(0x1D01, identity)
            h.carSends(0xAA01, certificate)
            h.carSends(0xAA03, signature)
            h.clock += 1_500L
            assertTrue(h.link.networkStable)
            assertTrue(h.controlSent.isEmpty())
            val before = h.controlFrames().size
            // 新车机栈已经准备同步，但必须先看到手机的六字节探测应答
            h.car = Iap2LinkEngine()
            h.car.start(h.now)
            h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOfRange(0, 2))
            assertTrue(h.controlSent.isEmpty())
            h.link.onCarBytes(Iap2LinkEngine.MARKER.copyOfRange(2, 6))
            assertEquals(1, h.controlSent.size)
            assertTrue(h.controlSent.single().contentEquals(Iap2LinkEngine.MARKER))
            assertEquals(before, h.controlFrames().size)
            assertEquals(0, h.link.controlGeneration)
            assertTrue("仅探测不能丢掉当前认证", h.link.networkReady)
            assertTrue("探测尚未收敛时不能交接蓝牙", !h.link.networkStable)
            h.linkToCar()
            h.exchange()
            assertEquals(1, h.link.controlGeneration)
            assertTrue(!h.link.networkReady)
            assertEquals(listOf(0x1D00), h.controlFrames().drop(before).map { it.second })
            h.carSends(0x1D01, identity)
            h.carSends(0xAA01, certificate)
            h.carSends(0xAA03, signature)
            assertTrue(h.link.networkReady)
            h.clock += 1_500L
            assertTrue(h.link.networkStable)
            assertEquals(2, h.controlFrames().count { it.second == 0xAA05 })
            assertTrue(h.eventSent.isEmpty())
            assertEquals(listOf(0x5001), h.carSends(0x5000))
        } finally {
            h.link.close()
        }
    }

    private fun modernIdentity(name: String = "新版状态测试车机 ModernNetwork"): ByteArray =
        Iap2Csm.string(0, name) +
            Iap2Csm.u16List(
                6,
                intArrayOf(
                    0xAA06,
                    0x6800,
                    0x6802,
                    0x6803,
                    0x4C00,
                    0x4C02,
                    0x4C03,
                    0x4C05,
                    0x5000,
                    0x5002,
                    0x4154,
                    0x4157,
                    0x4170,
                    0x5200,
                    0xFFFB,
                    0xA101,
                ),
            ) +
            Iap2Csm.u16List(
                7,
                intArrayOf(
                    0x6801,
                    0x4C01,
                    0x4C04,
                    0x5001,
                    0x4E09,
                    0x4E0A,
                    0x4E0B,
                    0x4E0C,
                    0x4155,
                    0x4158,
                    0x4171,
                    0x5201,
                    0xA100,
                    0xFFFA,
                ),
            )

    /** 签名成功后仍需状态逐帧确认，确认完成前保留蓝牙 */
    @Test
    fun networkReadinessWaitsForStatusAckAndResetsOnPeerRestart() {
        val h = Harness()
        try {
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            h.carSends(0x1D01, modernIdentity("网络状态测试车机 NetworkReadiness"))
            h.carSends(0xAA06, Iap2Csm.param(0, ByteArray(16)))
            h.carSends(0xAA01, Iap2Csm.param(0, ByteArray(608) { 23 }))
            assertTrue(h.link.supportsBootstrapHandoff)
            assertTrue(h.link.usesControlTransport)
            assertTrue(!h.link.networkReady)
            // 只交付签名，故意扣住手机发送后的车机确认
            h.car.sendControl(Iap2Csm.encode(0xAA03, Iap2Csm.param(0, ByteArray(64) { 1 })), h.now)
            h.carToLink()
            h.linkToCar()
            assertTrue(!h.link.networkReady)
            h.clock += 5_000L
            assertTrue(!h.link.networkStable)
            h.exchange()
            assertTrue(h.link.networkReady)
            assertTrue(!h.link.networkStable)
            h.clock += 1_499L
            assertTrue(!h.link.networkStable)
            h.clock++
            assertTrue(h.link.networkStable)
            // 虚拟时钟跨过三十分钟，正常订阅仍可继续，不重新报告认证成功
            h.clock += 30 * 60 * 1_000L
            assertEquals(listOf(0x5001), h.carSends(0x5000))
            assertTrue(h.link.networkStable)
            assertEquals(1, h.controlFrames().count { it.second == 0xAA05 })
            val fresh = Iap2LinkEngine()
            fresh.start(0)
            val syn = fresh.takeOutput()
            h.link.onCarBytes(Iap2LinkEngine.MARKER + networkPeerFrame(0x80, 150, 0, 0, syn.copyOfRange(15, syn.lastIndex)))
            assertTrue(!h.link.networkReady)
            assertTrue(!h.link.networkStable)
            assertEquals(1, h.link.controlGeneration)
            h.link.onCarBytes(networkPeerFrame(0x40, 150, 100, 0, null))
            h.link.onCarBytes(networkPeerFrame(0x40, 151, 100, 10, Iap2Csm.encode(0xAA03, Iap2Csm.param(0, ByteArray(64)))))
            assertTrue(!h.link.networkReady)
        } finally {
            h.link.close()
        }
    }

    /** 构造新网络会话的对端帧，显式控制序号和确认值以覆盖恢复后的边界 */
    private fun networkPeerFrame(
        control: Int,
        sequence: Int,
        ack: Int,
        session: Int,
        payload: ByteArray?,
    ): ByteArray {
        val length = if (payload == null) 9 else 10 + payload.size
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
}
