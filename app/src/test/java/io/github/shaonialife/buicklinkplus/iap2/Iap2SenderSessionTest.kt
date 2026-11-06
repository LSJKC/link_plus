package io.github.shaonialife.buicklinkplus.iap2

import io.github.shaonialife.buicklinkplus.transport.DuplexStream
import io.github.shaonialife.buicklinkplus.transport.Iap2LinkChannel
import io.github.shaonialife.buicklinkplus.transport.InMemoryStreamPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 用内存双工流模拟配件，检查身份识别、MFi、HID、热点和端点交付流程 */
class Iap2SenderSessionTest {
    /** 取消后不空转、不继续认证，也不修改长度记忆 */
    @Test
    fun interruptedChallengeStopsBeforeAsynchronousClose() {
        verifyCancelledChallenge(interruptOnly = true)
    }

    /** 本地主动关链不是车机明确拒绝，不能将当前挑战档位永久降序 */
    @Test
    fun closedChallengeDoesNotRecordRejectedLength() {
        verifyCancelledChallenge(interruptOnly = false)
    }

    /** 本地先撤销身份再异步关闭，期间收到 EOF 也不能把旧操作的结果用于换档 */
    @Test
    fun retiredChallengeDoesNotRotateEvenWhenPeerEofArrivesFirst() {
        verifyCancelledChallenge(interruptOnly = false, retireBeforePeerClose = true)
    }

    /** 双工链路停在首个挑战，分别检查中断和关闭 */
    private fun verifyCancelledChallenge(
        interruptOnly: Boolean,
        retireBeforePeerClose: Boolean = false,
    ) {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "取消测试手机")
        val car = Iap2LinkChannel(pair.sideB, name = "取消测试车机")
        val identityName =
            when {
                retireBeforePeerClose -> "MockCancelRetired"
                interruptOnly -> "MockCancelInterrupted"
                else -> "MockCancelClosed"
            }
        val receiverKey = "$identityName MU-1 Mock Manufacturer"
        val challengeSent = CountDownLatch(1)
        val phoneFailure = AtomicReference<Throwable?>()
        val carFailure = AtomicReference<Throwable?>()
        val interruptedAtExit = AtomicBoolean(false)
        val current = AtomicBoolean(true)
        val lines = mutableListOf<String>()
        val recovery = MfiChallengeRecovery()
        val accessory = MockAccessory(car, identityName = identityName, respondToMfiChallenge = false)
        val carWorker =
            Thread({
                try {
                    accessory.run()
                } catch (error: Throwable) {
                    carFailure.set(error)
                }
            }, "取消测试配件").apply { isDaemon = true }
        val phoneWorker =
            Thread({
                try {
                    Iap2SenderSession(
                        phone,
                        log = { line ->
                            lines += line
                            if (line.startsWith("iap2 tx=0xaa02 challenge=")) challengeSent.countDown()
                        },
                        challengeRecovery = recovery,
                        isSessionCurrent = { current.get() },
                    ).run(10_000)
                } catch (error: Throwable) {
                    phoneFailure.set(error)
                } finally {
                    interruptedAtExit.set(Thread.currentThread().isInterrupted)
                }
            }, "取消测试会话").apply { isDaemon = true }
        try {
            assertTrue(MfiLengthMemory.load(null, receiverKey).isEmpty)
            carWorker.start()
            phoneWorker.start()
            assertTrue("未到达挑战阶段", challengeSent.await(5, TimeUnit.SECONDS))
            carWorker.join(1_000)
            carFailure.get()?.let { throw AssertionError("模拟车机认证交换失败", it) }
            assertFalse("模拟车机应停在签名应答之前", carWorker.isAlive)
            when {
                retireBeforePeerClose -> {
                    current.set(false)
                    car.close()
                }
                interruptOnly -> phoneWorker.interrupt()
                else -> phone.close()
            }
            phoneWorker.join(1_000)
            assertFalse("取消后不能继续等完挑战窗口", phoneWorker.isAlive)
            assertTrue(phoneFailure.get() is IOException)
            if (interruptOnly) {
                assertTrue("异步关闭尚未执行时链路仍应存活", phone.isAlive)
                assertTrue(phoneFailure.get() is InterruptedIOException)
                assertTrue("退出时必须保留中断标记", interruptedAtExit.get())
            }
            assertTrue("取消不应修改成功、静默或被拒记忆", MfiLengthMemory.load(null, receiverKey).isEmpty)
            assertEquals(listOf(32, 20, 16), recovery.begin(receiverKey, listOf(32, 20, 16)).lengths)
            assertEquals(1, lines.count { it.startsWith("iap2 tx=0xaa02 challenge=") })
            assertFalse("取消后不能自报认证成功", lines.any { it.startsWith("iap2 tx=0xaa05") })
        } finally {
            phone.close()
            car.close()
            phoneWorker.interrupt()
            carWorker.interrupt()
            phoneWorker.join(1_000)
            carWorker.join(1_000)
        }
    }

    /** 首档 EOF 后在新链路换档完成握手，不保存为永久拒绝 */
    @Test
    fun remoteDisconnectAdvancesChallengeOnNextConnection() {
        val recovery = MfiChallengeRecovery()
        val identity = "MockRemoteDisconnectRecovery"
        val receiverKey = "$identity MU-1 Mock Manufacturer"
        val attempted = mutableListOf<Int>()
        repeat(2) { round ->
            val pair = InMemoryStreamPair()
            val phone = Iap2LinkChannel(pair.sideA, name = "换档手机-$round")
            val car = Iap2LinkChannel(pair.sideB, name = "换档车机-$round")
            val carFailure = AtomicReference<Throwable?>()
            val accessory =
                MockAccessory(
                    car,
                    identityName = identity,
                    onChallenge = { size ->
                        attempted += size
                        if (round == 0) {
                            car.close()
                            false
                        } else {
                            assertEquals(20, size)
                            true
                        }
                    },
                )
            val worker =
                Thread({
                    try {
                        accessory.run()
                    } catch (error: Throwable) {
                        carFailure.set(error)
                    }
                }, "换档测试配件-$round").apply { isDaemon = true }
            try {
                worker.start()
                val session = Iap2SenderSession(phone, {}, challengeRecovery = recovery)
                if (round == 0) {
                    assertThrows(IOException::class.java) { session.run(10_000) }
                    assertTrue(MfiLengthMemory.load(null, receiverKey).isEmpty)
                } else {
                    assertEquals("MockAP", session.run(10_000).ssid)
                    val memory = MfiLengthMemory.load(null, receiverKey)
                    assertEquals(20, memory.succeeded)
                    assertTrue(memory.rejected.isEmpty())
                }
                worker.join(2_000)
                assertFalse("配件脚本未结束", worker.isAlive)
                carFailure.get()?.let { throw AssertionError("配件脚本失败", it) }
            } finally {
                phone.close()
                car.close()
                worker.interrupt()
                worker.join(1_000)
            }
        }
        assertEquals(listOf(32, 20), attempted)
    }

    @Test
    fun fullBringUpAgainstMockAccessory() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "02:AA:BB:CC:DD:33",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        assertEquals("MockAP", endpoint.ssid)
        assertEquals("secret-passphrase", endpoint.passphrase)
        assertEquals(36, endpoint.channel)
        assertEquals(2, endpoint.securityType)
        assertEquals(49152, endpoint.airPlayPort)
        assertEquals("mock-device-id", endpoint.deviceIdentifier)
        assertNotNull(endpoint.publicKey)
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 车机未声明 0x4301 时不等待，保活阶段迟到的消息仍应解析并回调 */
    @Test
    fun lateStartSessionAfterPhaseOneTriggersEndpointUpdate() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, audiDeclarations = true, lateStartSession = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val updates = AtomicReference<Iap2SenderSession.CarPlayEndpoint?>()
        val updateLatch = CountDownLatch(1)
        val session =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "02:AA:BB:CC:DD:33",
                deviceName = "test-device",
                onEndpointUpdate = {
                    updates.set(it)
                    updateLatch.countDown()
                },
            )

        val phaseOne = session.run(timeoutMillis = 10_000L)
        // 车机声明不发 0x4301：Phase 1 必须立即返回空端点，而不是白等 3s 窗口
        assertTrue(phaseOne.ipAddresses.isEmpty())

        val pump = Thread({ session.pumpIncomingForever() }, "phone-pump")
        pump.isDaemon = true
        pump.start()

        assertTrue("晚到 0x4301 未触发端点回调", updateLatch.await(5, TimeUnit.SECONDS))
        val late = updates.get()
        assertNotNull(late)
        assertEquals("MockAP", late!!.ssid)
        assertEquals(49152, late.airPlayPort)
        assertEquals(listOf("192.168.49.1", "fd00::1"), late.ipAddresses)
        assertEquals("mock-device-id", late.deviceIdentifier)

        phoneChannel.close()
        accessoryChannel.close()
        pump.join(2_000)
    }

    /** 检查地址字段去掉全部尾部 NUL，避免定长填充导致解析失败 */
    @Test
    fun nulPaddedAddressesAreStrippedCompletely() {
        val pair = InMemoryStreamPair()
        val phoneChannel = Iap2LinkChannel(pair.sideA, name = "phone")
        val accessoryChannel = Iap2LinkChannel(pair.sideB, name = "accessory")
        val accessory = MockAccessory(accessoryChannel, padAddresses = true)

        val accessoryThread = Thread({ accessory.run() }, "mock-accessory")
        accessoryThread.isDaemon = true
        accessoryThread.start()

        val endpoint =
            Iap2SenderSession(
                phoneChannel,
                log = { line -> println("PHONE $line") },
                bluetoothMac = "02:AA:BB:CC:DD:33",
                deviceName = "test-device",
            ).run(timeoutMillis = 10_000L)

        // 每个地址都必须干净：没有 NUL、没有空白
        endpoint.ipAddresses.forEach { address ->
            check(address.none { it == '\u0000' }) { "地址含 NUL：${address.toByteArray().toList()}" }
            check(address.trim() == address) { "地址含首尾空白：'$address'" }
        }
        assertEquals(listOf("192.168.49.1", "fd00::1"), endpoint.ipAddresses)
        // 端口解析不受填充影响（同一帧里的 u32 参数）
        assertEquals(49152, endpoint.airPlayPort)

        phoneChannel.close()
        accessoryChannel.close()
    }

    /** 稀疏清单遗漏旧协议消息时，仍应请求并取得热点凭据 */
    @Test
    fun sparseDeclarationsStillTryLegacyWifi() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x4301, 0xea03),
            receiveIds = intArrayOf(0x4300, 0x4e09),
            expectCredentialsCallback = true,
        )
    }

    /** 只声明旧协议的一侧也构成支持证据，不能被另一侧遗漏否决 */
    @Test
    fun oneSidedLegacyDeclarationStillRequestsWifi() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x5703, 0x4301),
            receiveIds = intArrayOf(0x4300),
            expectCredentialsCallback = true,
        )
    }

    /** 完整清单为新版时直接启动交换，不发送对端不支持的旧协议请求 */
    @Test
    fun modernDeclarationsSkipLegacyWifi() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x4301, 0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157),
            receiveIds = intArrayOf(0x4300, 0x4e09, 0xaa00, 0xaa02, 0x5001, 0x5201, 0x4158),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
        )
    }

    /** 检查新版认证、四条状态和双标识启动不被 HID 等待阻塞 */
    @Test
    fun modernBootstrapMatchesSuccessfulBuickSequenceWithoutWaitingForHid() {
        verifyWifiScenario(
            sendIds = intArrayOf(0xaa06, 0x6800, 0x6802, 0x6803, 0x4c00, 0x4c02, 0x4c03, 0x4c05, 0x5000, 0x5002, 0x4301),
            receiveIds = intArrayOf(0x6801, 0x4c01, 0x4c04, 0x5001, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4300),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
            maximumBootstrapMillis = 1_500L,
        )
    }

    /** 新版清单明确支持通知时，也在等待车机启动前提供设备身份 */
    @Test
    fun modernDeclarationsKeepSupportedNotifications() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x4301, 0x5000, 0x5002, 0x6800, 0x6802, 0x6803),
            receiveIds = intArrayOf(0x4300, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c, 0x4e0d, 0x4e0e),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
        )
    }

    /** 热点消息晚于 8 秒时继续等待健康链路 */
    @Test
    fun delayedModernStartSessionSurvivesShortWindow() {
        verifyWifiScenario(
            sendIds = intArrayOf(0xaa06, 0x4301, 0x5000, 0x5002, 0x6800, 0x6802, 0x6803),
            receiveIds = intArrayOf(0x4300, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
            startSessionDelayMillis = 8_500L,
        )
    }

    /** 启动消息早于触控初始化时必须保留，后续不能再次等待已被丢弃的唯一热点信息 */
    @Test
    fun startSessionBeforeHidIsPreserved() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x4301, 0x5000, 0x5002, 0x6800, 0x6802, 0x6803),
            receiveIds = intArrayOf(0x4300, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
            earlyStartSession = true,
        )
    }

    /** 丢掉第一次启动请求后，车机仍须在链路重传后拿到请求并交付热点 */
    @Test
    fun modernAvailabilityRecoversFromUnacknowledgedFirstRequest() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x4301, 0x5000, 0x5002, 0x6800, 0x6802, 0x6803),
            receiveIds = intArrayOf(0x4300, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c),
            skipLegacyExchange = true,
            expectModernBootstrap = true,
            dropFirstAvailability = true,
        )
    }

    /** 延长等待不能突破调用方的总截止时间 */
    @Test
    fun unansweredModernStartSessionHonorsDeadline() {
        verifySilentModernSession(cancelDuringExtendedWait = false)
    }

    /** 用户取消后及时结束等待，不占满 90 秒 */
    @Test
    fun cancelledModernStartSessionStopsExtendedWait() {
        verifySilentModernSession(cancelDuringExtendedWait = true)
    }

    /** 旧协议始终不回复且链路存活时，回退必须仍有时间接收新版热点信息 */
    @Test
    fun unansweredLegacyWifiLeavesTimeForModernFallback() {
        verifyWifiScenario(
            sendIds = intArrayOf(0x5703, 0x4301),
            receiveIds = intArrayOf(0x5702, 0x4300),
            respondToWifiRequest = false,
        )
    }

    /** 未取得热点时，不能因稀疏清单遗漏启动消息而直接跳过回退等待 */
    @Test
    fun sparseDeclarationsWithoutStartSessionStillAllowFallback() {
        verifyWifiScenario(
            sendIds = intArrayOf(0xea03),
            receiveIds = intArrayOf(0x4300),
            respondToWifiRequest = false,
        )
    }

    /** 链路回环检查消息顺序和热点交付，配件异常传回测试线程 */
    private fun verifyWifiScenario(
        sendIds: IntArray,
        receiveIds: IntArray,
        skipLegacyExchange: Boolean = false,
        respondToWifiRequest: Boolean = true,
        expectCredentialsCallback: Boolean = false,
        expectModernBootstrap: Boolean = false,
        startSessionDelayMillis: Long = 0L,
        earlyStartSession: Boolean = false,
        dropFirstAvailability: Boolean = false,
        maximumBootstrapMillis: Long? = null,
    ) {
        val pair = InMemoryStreamPair()
        val lossyStream = if (dropFirstAvailability) DropFirstAvailabilityStream(pair.sideA) else null
        val phone = Iap2LinkChannel(lossyStream ?: pair.sideA, name = "热点测试手机")
        val car = Iap2LinkChannel(pair.sideB, name = "热点测试车机")
        val failure = AtomicReference<Throwable?>()
        val credentials = AtomicReference<Iap2SenderSession.WifiConfiguration?>()
        val accessory =
            MockAccessory(
                car,
                sendIds = sendIds,
                receiveIds = receiveIds,
                skipLegacyExchange = skipLegacyExchange,
                respondToWifiRequest = respondToWifiRequest,
                expectModernBootstrap = expectModernBootstrap,
                startSessionDelayMillis = startSessionDelayMillis,
                earlyStartSession = earlyStartSession,
                expectedBluetoothMac = "02:AA:BB:CC:DD:11",
                maximumBootstrapMillis = maximumBootstrapMillis,
            )
        val worker =
            Thread({
                try {
                    accessory.run()
                } catch (error: Throwable) {
                    failure.set(error)
                    car.close()
                }
            }, "热点测试配件").apply { isDaemon = true }
        try {
            worker.start()
            val endpoint =
                Iap2SenderSession(
                    phone,
                    log = {},
                    bluetoothMac = "02:AA:BB:CC:DD:11",
                    onWifiCredentials = { credentials.set(it) },
                ).run(timeoutMillis = 12_000L)
            assertEquals("MockAP", endpoint.ssid)
            assertEquals("secret-passphrase", endpoint.passphrase)
            assertEquals(49152, endpoint.airPlayPort)
            assertEquals(expectCredentialsCallback, credentials.get() != null)
            if (lossyStream != null) assertTrue("测试必须实际丢弃过首次启动请求", lossyStream.dropped)
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("模拟车机交换失败", it) }
            assertFalse("模拟车机线程未结束", worker.isAlive)
        } finally {
            phone.close()
            car.close()
            worker.join(2_000)
        }
    }

    /** 仅在测试流中丢弃首次完整启动帧，其余控制帧和后续同序号重传均原样传递 */
    private class DropFirstAvailabilityStream(
        private val delegate: DuplexStream,
    ) : DuplexStream by delegate {
        @Volatile
        var dropped = false
            private set

        override fun write(bytes: ByteArray) {
            val output = ByteArrayOutputStream()
            var offset = 0
            while (offset < bytes.size) {
                if (offset + 9 > bytes.size || bytes[offset] != 0xff.toByte() || bytes[offset + 1] != 0x5a.toByte()) {
                    output.write(bytes[offset].toInt())
                    offset++
                    continue
                }
                val length = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
                check(length >= 9 && offset + length <= bytes.size) { "测试输出必须包含完整链路帧" }
                val isAvailability =
                    length >= 16 &&
                        bytes[offset + 9] == 0x40.toByte() &&
                        bytes[offset + 10] == 0x40.toByte() &&
                        bytes[offset + 13] == 0x43.toByte() &&
                        bytes[offset + 14] == 0.toByte()
                if (!dropped && isAvailability) {
                    dropped = true
                } else {
                    output.write(bytes, offset, length)
                }
                offset += length
            }
            if (output.size() > 0) delegate.write(output.toByteArray())
        }
    }

    /** 模拟车机只确认链路、不返回启动消息，分别验证截止和取消的退出条件 */
    private fun verifySilentModernSession(cancelDuringExtendedWait: Boolean) {
        val pair = InMemoryStreamPair()
        val phone = Iap2LinkChannel(pair.sideA, name = "静默测试手机")
        val car = Iap2LinkChannel(pair.sideB, name = "静默测试车机")
        val failure = AtomicReference<Throwable?>()
        var extendedWaitReached = false
        val accessory =
            MockAccessory(
                car,
                sendIds = intArrayOf(0x4301, 0x5000, 0x5002, 0x6802, 0x6803),
                receiveIds = intArrayOf(0x4300, 0x4e09, 0x4e0a, 0x4e0b, 0x4e0c),
                skipLegacyExchange = true,
                expectModernBootstrap = true,
                suppressStartSession = true,
            )
        val worker =
            Thread({
                try {
                    accessory.run()
                } catch (error: Throwable) {
                    failure.set(error)
                    car.close()
                }
            }, "静默测试配件").apply { isDaemon = true }
        try {
            worker.start()
            val timeoutMillis = if (cancelDuringExtendedWait) 15_000L else 2_000L
            val started = System.nanoTime()
            val error =
                assertThrows(IOException::class.java) {
                    Iap2SenderSession(
                        phone,
                        log = {},
                        onPhase = { phase ->
                            if (phase.contains("等待车机启动 CarPlay")) {
                                extendedWaitReached = true
                                if (cancelDuringExtendedWait) phone.close()
                            }
                        },
                    ).run(timeoutMillis)
                }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertTrue("静默退出应明确报告缺失热点凭据", error.message.orEmpty().contains("未取得车机热点凭据"))
            assertEquals(cancelDuringExtendedWait, extendedWaitReached)
            val maximumMillis = if (cancelDuringExtendedWait) 12_000L else timeoutMillis + 1_500L
            assertTrue("等待没有在截止或取消后及时结束：${elapsedMillis}ms", elapsedMillis < maximumMillis)
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("静默车机交换失败", it) }
            assertFalse("静默车机线程未结束", worker.isAlive)
        } finally {
            phone.close()
            car.close()
            worker.join(2_000)
        }
    }

    /** 按 DiPlay Iap2WirelessControlClient 的时序脚本化应答。 */
    private class MockAccessory(
        private val channel: Iap2LinkChannel,
        /** 奥迪形态：识别帧声明只发 0x5703/0xea03（不发 StartHID/0x4301）。 */
        private val audiDeclarations: Boolean = false,
        /** Phase 1 结束后再补发 0x4301（车机窗口晚开的真实时序）。 */
        private val lateStartSession: Boolean = false,
        /** 构造带定长 NUL 填充的地址，覆盖 IPv4 和 IPv6 */
        private val padAddresses: Boolean = false,
        /** 覆盖能力清单，验证稀疏清单与完整清单的实际连接分派 */
        private val sendIds: IntArray? = null,
        private val receiveIds: IntArray? = null,
        /** 新版车机不接受旧协议请求，消息顺序错误会让测试立即失败 */
        private val skipLegacyExchange: Boolean = false,
        /** 保持链路存活但不回旧协议凭据，复现回退等待预算耗尽 */
        private val respondToWifiRequest: Boolean = true,
        /** 新版使用双标识和能力约束的通知，旧版验证既有顺序 */
        private val expectModernBootstrap: Boolean = false,
        /** 模拟车机确认后才返回启动消息 */
        private val startSessionDelayMillis: Long = 0L,
        /** 模拟启动消息先于触控初始化到达 */
        private val earlyStartSession: Boolean = false,
        /** 模拟车机一直不返回启动消息，但保留链路 ACK */
        private val suppressStartSession: Boolean = false,
        /** 核对两种身份通知携带的真实蓝牙地址一致 */
        private val expectedBluetoothMac: String? = null,
        /** 限制认证到启动的时间，复现声明 HID 却不先发送时的互等 */
        private val maximumBootstrapMillis: Long? = null,
        /** 取消用例独立身份，避免与其他用例共享认证长度记忆 */
        private val identityName: String = "MockCarPlay",
        /** 只确认挑战传输但不回签名，供取消和主动关链用例使用 */
        private val respondToMfiChallenge: Boolean = true,
        /** 收到挑战时选择断链或签名，检查跨连接恢复 */
        private val onChallenge: ((Int) -> Boolean)? = null,
    ) {
        private val parser = Iap2Csm.Parser()
        private val pending = ArrayDeque<Iap2Csm.Frame>()

        fun run() {
            check(channel.awaitReady(10_000)) { "mock link not ready" }

            expect(Iap2MessageIds.START_IDENTIFICATION)
            send(
                Iap2MessageIds.IDENTIFICATION_INFORMATION,
                Iap2Csm.string(0, identityName),
                Iap2Csm.string(1, "MU-1"),
                Iap2Csm.string(2, "Mock Manufacturer"),
                if (sendIds != null) {
                    Iap2Csm.u16List(6, sendIds)
                } else if (audiDeclarations) {
                    Iap2Csm.u16List(6, intArrayOf(0x5703, 0xea03))
                } else {
                    ByteArray(0)
                },
                receiveIds?.let { Iap2Csm.u16List(7, it) } ?: ByteArray(0),
            )
            expect(Iap2MessageIds.IDENTIFICATION_ACCEPTED)

            val certificateRequest = expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE)
            if (expectModernBootstrap && sendIds?.contains(Iap2MessageIds.MFI_AUTH_SERIAL) == true) {
                assertEquals(listOf(Iap2Csm.Param(0, ByteArray(0))), Iap2Csm.parseParams(certificateRequest.body))
                send(Iap2MessageIds.MFI_AUTH_SERIAL, Iap2Csm.param(0, ByteArray(16)))
                assertTrue(expect(Iap2MessageIds.MFI_REQUEST_CERTIFICATE).body.isEmpty())
            } else {
                assertTrue(certificateRequest.body.isEmpty())
            }
            send(Iap2MessageIds.MFI_CERTIFICATE, Iap2Csm.param(0, ByteArray(64) { it.toByte() }))
            val challenge = expect(Iap2MessageIds.MFI_REQUEST_CHALLENGE)
            val size =
                Iap2Csm
                    .parseParams(challenge.body)
                    .single { it.id == 0 }
                    .value.size
            if (onChallenge?.invoke(size) == false) return
            if (!respondToMfiChallenge) return
            send(Iap2MessageIds.MFI_RESPONSE, Iap2Csm.param(0, ByteArray(70) { (it * 7).toByte() }))
            expect(Iap2MessageIds.MFI_AUTHENTICATION_SUCCEEDED)
            val authenticatedAt = System.nanoTime()

            if (earlyStartSession) sendStartSession()

            // 订阅 + StartHID（奥迪形态不发，发送端应直接跳过等待）
            val sendHid = sendIds?.contains(Iap2MessageIds.START_HID) ?: !audiDeclarations
            if (sendHid && !expectModernBootstrap) {
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                send(Iap2MessageIds.START_POWER_UPDATES)
                send(
                    Iap2MessageIds.START_HID,
                    Iap2Csm.u16(0, 0),
                    Iap2Csm.u16(1, 2),
                    Iap2Csm.u16(2, 1),
                    Iap2Csm.param(4, byteArrayOf(0x05, 0x0c)),
                )
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }

            // WiFi 配置
            if (!skipLegacyExchange) {
                expect(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION)
                // 提前到达的订阅请求应暂存，不能在等待热点时反复取出同一帧
                send(Iap2MessageIds.START_NOW_PLAYING_UPDATES)
                if (respondToWifiRequest) {
                    send(
                        Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION,
                        Iap2Csm.string(1, "MockAP"),
                        Iap2Csm.string(2, "secret-passphrase"),
                        Iap2Csm.u8(3, 2),
                        Iap2Csm.u8(4, 36),
                    )
                }
            }

            // 设备状态问候（真机顺序：0x4E0D/0x4E0A/0x4E09/0x4E0B/0x4E0C → 0x4300）
            if (expectModernBootstrap && receiveIds?.contains(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER) == true) {
                val identity = expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
                expectedBluetoothMac?.let {
                    assertEquals(it, Iap2Csm.parseParams(identity.body).single { param -> param.id == 0 }.asString())
                }
            }
            if (!expectModernBootstrap || receiveIds?.contains(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE) == true) {
                val wireless = expect(Iap2MessageIds.WIRELESS_CARPLAY_UPDATE)
                assertEquals(
                    1,
                    Iap2Csm
                        .parseParams(wireless.body)
                        .single { it.id == 0 }
                        .value
                        .single()
                        .toInt(),
                )
            }
            if (expectModernBootstrap) {
                expect(Iap2MessageIds.DEVICE_UUID_UPDATE)
                expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
            }
            val language = expect(Iap2MessageIds.DEVICE_LANGUAGE_UPDATE)
            assertEquals("zh", Iap2Csm.parseParams(language.body).single { it.id == 0 }.asString())
            expect(Iap2MessageIds.DEVICE_INFORMATION_UPDATE)
            if (!expectModernBootstrap) {
                expect(Iap2MessageIds.DEVICE_TIME_UPDATE)
                expect(Iap2MessageIds.DEVICE_UUID_UPDATE)
            }

            // CarPlay 启动
            val availability = expect(Iap2MessageIds.CARPLAY_AVAILABILITY)
            maximumBootstrapMillis?.let {
                val elapsed = (System.nanoTime() - authenticatedAt) / 1_000_000L
                assertTrue("新版启动不应先等可选 HID：耗时 ${elapsed}ms", elapsed < it)
            }
            val groups = Iap2Csm.parseParams(availability.body)
            assertEquals(if (expectModernBootstrap) listOf(0, 1) else listOf(1), groups.map { it.id })
            val wirelessGroup = Iap2Csm.parseParams(availability.body).single { it.id == 1 }
            val attributes = Iap2Csm.parseParams(wirelessGroup.value)
            assertEquals(
                1,
                attributes
                    .single { it.id == 0 }
                    .value
                    .single()
                    .toInt(),
            )
            expectedBluetoothMac?.let {
                assertEquals(
                    if (expectModernBootstrap) it.lowercase(java.util.Locale.ROOT) else it,
                    attributes
                        .single { param ->
                            param.id ==
                                1
                        }.asString(),
                )
                if (expectModernBootstrap) {
                    val wired = Iap2Csm.parseParams(groups.single { param -> param.id == 0 }.value)
                    assertEquals(listOf(0, 1), wired.map { param -> param.id })
                    assertArrayEquals(byteArrayOf(1), wired[0].value)
                    assertEquals("0000811002AABBCCDD110000", wired[1].asString())
                    val expectedBody =
                        byteArrayOf(0, 38, 0, 0, 0, 5, 0, 0, 1, 0, 29, 0, 1) +
                            "0000811002AABBCCDD110000".encodeToByteArray() + byteArrayOf(0) +
                            byteArrayOf(0, 31, 0, 1, 0, 5, 0, 0, 1, 0, 22, 0, 1) +
                            "02:aa:bb:cc:dd:11".encodeToByteArray() + byteArrayOf(0)
                    assertArrayEquals(expectedBody, availability.body)
                    assertEquals(75, Iap2Csm.encode(availability.messageId, availability.body).size)
                }
            }
            // 新版车机可等启动请求后才发 HID；此时手机必须立即应答而不丢启动信息
            if (expectModernBootstrap && sendHid && !earlyStartSession) {
                send(Iap2MessageIds.START_HID, Iap2Csm.u16(0, 0))
                expect(Iap2MessageIds.DEVICE_HID_REPORT)
            }
            if (!lateStartSession && !earlyStartSession && !suppressStartSession) {
                if (startSessionDelayMillis > 0) Thread.sleep(startSessionDelayMillis)
                sendStartSession()
            }

            if (!expectModernBootstrap) {
                expect(Iap2MessageIds.DEVICE_TRANSPORT_IDENTIFIER)
            } else {
                check(recvFrame(200) == null) { "已提前通知传输标识，不应在启动后重复发送" }
            }
            if (lateStartSession) {
                // 车机打开 CarPlay 窗口后补发（Phase 1 早已结束）
                Thread.sleep(150)
                sendStartSession()
            }
        }

        private fun sendStartSession() {
            send(
                Iap2MessageIds.CARPLAY_START_SESSION,
                Iap2Csm.group(
                    1,
                    Iap2Csm.string(0, "MockAP"),
                    Iap2Csm.string(1, "secret-passphrase"),
                    Iap2Csm.u8(2, 36),
                    addressParam(3, "192.168.49.1", padTo = 17),
                    addressParam(3, "fd00::1", padTo = 47),
                    Iap2Csm.u8(4, 2),
                ),
                Iap2Csm.u32(2, 49152L),
                Iap2Csm.string(3, "mock-device-id"),
                Iap2Csm.string(4, "a".repeat(64) + "publickeyhex"),
                Iap2Csm.string(5, "560.20"),
            )
        }

        /** 构造可选 NUL 填充的地址参数 */
        private fun addressParam(
            id: Int,
            value: String,
            padTo: Int,
        ): ByteArray {
            val raw = value.encodeToByteArray()
            if (!padAddresses) return Iap2Csm.param(id, raw + byteArrayOf(0))
            // 至少保留一个 NUL 结尾（CSM 字符串语义），其余补到固定宽度
            val width = maxOf(padTo, raw.size + 1)
            val padded = raw + ByteArray(width - raw.size)
            return Iap2Csm.param(id, padded)
        }

        private fun send(
            messageId: Int,
            vararg body: ByteArray,
        ) {
            val joined =
                when (body.size) {
                    0 -> ByteArray(0)
                    1 -> body[0]
                    else -> body.reduce { acc, bytes -> acc + bytes }
                }
            val frame = Iap2Csm.encode(messageId, joined)
            var offset = 0
            val limit = channel.peerMaxControlPayload() ?: 2048
            while (offset < frame.size) {
                val end = minOf(offset + limit, frame.size)
                check(channel.sendControl(frame.copyOfRange(offset, end))) { "mock send failed" }
                offset = end
            }
        }

        private fun expect(messageId: Int): Iap2Csm.Frame {
            println("MOCK expect 0x${messageId.toString(16)}")
            val frame =
                recvFrame(10_000)
                    ?: error("mock timed out waiting 0x${messageId.toString(16)}")
            check(frame.messageId == messageId) {
                "mock expected 0x${messageId.toString(16)} got 0x${frame.messageId.toString(16)}"
            }
            println("MOCK got 0x${frame.messageId.toString(16)}")
            return frame
        }

        private fun recvFrame(timeoutMillis: Long): Iap2Csm.Frame? {
            pending.firstOrNull()?.let {
                pending.removeFirst()
                return it
            }
            val bytes = channel.recvControl(timeoutMillis) ?: return null
            val frames = parser.offer(bytes)
            if (frames.isEmpty()) return null
            frames.forEachIndexed { index, frame -> if (index > 0) pending += frame }
            return frames.first()
        }
    }
}
