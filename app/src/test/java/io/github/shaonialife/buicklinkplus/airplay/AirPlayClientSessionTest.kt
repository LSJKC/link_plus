package io.github.shaonialife.buicklinkplus.airplay

import io.github.shaonialife.buicklinkplus.iap2.Iap2LinkEngine
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 使用内存模拟控制连接 */
class AirPlayClientSessionTest {
    /** 手机取回主屏后重新启用已协商的 HID，借屏或请求被拒时跳过 */
    @Test
    fun screenReturnRearmsHidButCarOwnershipAndRejectedModesDoNot() {
        val client = client()
        val control = MemorySocket()
        val event = MemorySocket()
        val resources = field(client, "resourceCommands") as ExecutorService
        val keyFrames = AtomicInteger()
        try {
            setSocket(client, control)
            registerEvent(client, event, listOf("3", "2"))
            client.onPhoneScreenReady = { keyFrames.incrementAndGet() }
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("3", "2"), event.commands.map { (it["params"] as Map<*, *>)["uuid"] })
            assertTrue(event.commands.all { it["type"] == "hidSetInputMode" && (it["params"] as Map<*, *>)["hidInputMode"] == 0L })
            assertEquals(1, keyFrames.get())
            val takeScreen =
                mapOf(
                    "type" to "changeModes",
                    "params" to mapOf("resources" to listOf(mapOf("resourceID" to 1L, "transferType" to 1L))),
                )
            client.dispatchEventCommand(takeScreen, control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(!client.phoneOwnsScreen)
            assertEquals(2, event.commands.size)
            control.applicationStatus = -1L
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(2, event.commands.size)
            assertEquals(1, keyFrames.get())
            control.applicationStatus = 0L
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(4, event.commands.size)
            assertEquals(2, keyFrames.get())
        } finally {
            client.close()
            resources.shutdownNow()
            executor(client).shutdownNow()
        }
    }

    /** 输入恢复发送中途更换事件连接，剩余旧任务不能发到新通道或唤醒视频 */
    @Test
    fun rearmingCannotContinueIntoReplacementEventConnection() {
        val client = client()
        val control = MemorySocket()
        val event = MemorySocket()
        val nextEvent = MemorySocket()
        val resources = field(client, "resourceCommands") as ExecutorService
        val keyFrames = AtomicInteger()
        try {
            setSocket(client, control)
            registerEvent(client, event, listOf("3", "2"))
            client.onPhoneScreenReady = { keyFrames.incrementAndGet() }
            event.onCommandWritten = { registerEvent(client, nextEvent, listOf("4")) }
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, event.commands.size)
            assertTrue(nextEvent.commands.isEmpty())
            assertEquals(listOf("4"), field(client, "eventHidDeviceUuids"))
            assertEquals(0, keyFrames.get())
        } finally {
            client.close()
            event.close()
            resources.shutdownNow()
            executor(client).shutdownNow()
        }
    }

    /** 模式应答之前事件连接已更换时，连第一条输入武装也不能发送 */
    @Test
    fun replacedEventDuringModesResponseCannotRestoreHid() {
        val client = client()
        val control = MemorySocket()
        val event = MemorySocket()
        val nextEvent = MemorySocket()
        val resources = field(client, "resourceCommands") as ExecutorService
        try {
            setSocket(client, control)
            registerEvent(client, event, listOf("3", "2"))
            control.onCommandWritten = { registerEvent(client, nextEvent, listOf("4")) }
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(event.commands.isEmpty())
            assertTrue(nextEvent.commands.isEmpty())
        } finally {
            client.close()
            event.close()
            resources.shutdownNow()
            executor(client).shutdownNow()
        }
    }

    /** 后续实际启用的设备也应参与屏幕恢复，重复启用不能重复加入设备列表 */
    @Test
    fun laterEnabledHidDeviceIsRestoredOnceAlongsideSetupDevices() {
        val client = client()
        val control = MemorySocket()
        val event = MemorySocket()
        val resources = field(client, "resourceCommands") as ExecutorService
        try {
            setSocket(client, control)
            registerEvent(client, event, listOf("3"))
            assertTrue(client.sendHidSetInputMode("2"))
            assertTrue(client.sendHidSetInputMode("2"))
            event.commands.clear()
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(listOf("3", "2"), event.commands.map { (it["params"] as Map<*, *>)["uuid"] })
        } finally {
            client.close()
            resources.shutdownNow()
            executor(client).shutdownNow()
        }
    }

    /** 交接前检查资源业务响应及请求代次 */
    @Test
    fun handoffRequiresStableNetworkScreenAndApplicationAcceptance() {
        val client = client()
        val socket = MemorySocket()
        val h = VirtualIap2LinkTest.Harness()
        val macField =
            io.github.shaonialife.buicklinkplus.util.DeviceIdentity::class.java.getDeclaredField("cachedMac").apply {
                isAccessible =
                    true
            }
        val originalMac = macField.get(null)
        try {
            macField.set(null, "02:AA:BB:CC:DD:11")
            setSocket(client, socket)
            AirPlayClient::class.java
                .getDeclaredField("iapLink")
                .apply { isAccessible = true }
                .set(client, h.link)
            assertTrue(client.handleModeCommand(mapOf("type" to "requestUI")))
            h.link.armMib3()
            h.car.start(0)
            h.exchangeUntilNormal()
            h.carSends(
                0x1D01,
                io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
                    .string(0, "交接测试车机 HandoffTestCar") +
                    io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
                        .u16List(6, intArrayOf(0x6800, 0x6802, 0x4C00, 0x4C02, 0x5000, 0x4154)) +
                    io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
                        .u16List(7, intArrayOf(0x6801, 0x4E09, 0x4E0A, 0x4E0B, 0x4E0C)),
            )
            assertTrue(!client.requestBluetoothHandoff())
            h.carSends(
                0xAA01,
                io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
                    .param(0, ByteArray(608) { 17 }),
            )
            h.carSends(
                0xAA03,
                io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
                    .param(0, ByteArray(64) { 1 }),
            )
            assertTrue(!client.requestBluetoothHandoff())
            h.clock += 1_500L
            socket.applicationStatus = -1L
            assertTrue(!client.requestBluetoothHandoff())
            assertTrue(!socket.isClosed)
            socket.applicationStatus = 0L
            assertTrue(client.requestBluetoothHandoff())
            val request = socket.commands.last()
            assertEquals("disableBluetooth", request["type"])
            assertEquals("02:AA:BB:CC:DD:11", (request["params"] as Map<*, *>)["deviceID"])
            // 车机在交接响应前重新协商时，旧响应不能授权关闭当前引导连接
            socket.onCommandWritten = { command ->
                if (command["type"] == "disableBluetooth") {
                    val fresh = Iap2LinkEngine()
                    fresh.start(0)
                    val syn = fresh.takeOutput()
                    syn[11] = 150.toByte()
                    syn[14] = (-syn.slice(6..13).sumOf { it.toInt() and 255 }).toByte()
                    h.link.onCarBytes(syn)
                }
            }
            assertTrue(!client.requestBluetoothHandoff())
            assertEquals(1, h.link.controlGeneration)
            assertTrue("网络重协商不能清掉外层主屏归属", client.phoneOwnsScreen)
            assertTrue(!socket.isClosed)
        } finally {
            macField.set(null, originalMac)
            client.close()
            h.link.close()
            executor(client).shutdownNow()
        }
    }

    /** 资源响应等待不阻塞链路协商和保活 */
    @Test
    fun slowModesResponseDoesNotBlockIncomingIapOrControlTraffic() {
        val client = client()
        val control = MemorySocket().apply { pauseModes = true }
        val event = MemorySocket()
        try {
            setSocket(client, control)
            registerEvent(client, event)
            client.dispatchEventCommand(mapOf("type" to "requestUI"), control, event) {}
            assertTrue(control.modesWritten.await(3, TimeUnit.SECONDS))
            assertTrue(!client.phoneOwnsScreen)
            client.onIapEventBytes(ByteArray(0))
            val link = field(client, "iapLink") as VirtualIap2Link
            link.armMib3()
            val car = Iap2LinkEngine()
            car.start(0)
            client.dispatchEventCommand(mapOf("type" to "iAPSendMessage"), control, event) {
                client.onIapEventBytes(car.takeOutput())
            }
            assertTrue("资源请求还在等响应，网络同步已继续", link.usesControlTransport)
            executor(client).submit {}.get(3, TimeUnit.SECONDS)
            assertTrue("资源慢响应不能占住控制通道写锁", control.requests.get() >= 3)
            assertTrue(field(client, "socket") === control)
        } finally {
            client.close()
            executor(client).shutdownNow()
            (field(client, "resourceCommands") as ExecutorService).shutdownNow()
        }
    }

    /** 已排队的旧资源事件不能在重连后借用新连接宣布主屏归属 */
    @Test
    fun queuedOldResourceCommandIsDroppedAfterReconnect() {
        val client = client()
        val resources = field(client, "resourceCommands") as ExecutorService
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = MemorySocket()
        val next = MemorySocket()
        val event = MemorySocket()
        try {
            resources.execute {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            setSocket(client, old)
            registerEvent(client, event)
            client.dispatchEventCommand(mapOf("type" to "requestUI"), old, event) {}
            client.close()
            setSocket(client, next)
            registerEvent(client, MemorySocket())
            release.countDown()
            resources.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(0, next.requests.get())
            assertTrue(!client.phoneOwnsScreen)
        } finally {
            release.countDown()
            client.close()
            resources.shutdownNow()
            executor(client).shutdownNow()
        }
    }

    /** 丢弃旧队列后新连接仍可发送 */
    @Test
    fun queuedOldFramesAreDroppedAndNewSessionCanSend() {
        val client = client()
        val executor = executor(client)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = MemorySocket()
        val next = MemorySocket()
        try {
            executor.execute {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            setSocket(client, old)
            client.postControlIap(Iap2LinkEngine.MARKER)
            client.close()
            setSocket(client, next)
            release.countDown()
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(0, old.requests.get())
            assertEquals(0, next.requests.get())
            client.postControlIap(Iap2LinkEngine.MARKER)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, next.requests.get())
        } finally {
            release.countDown()
            client.close()
            executor.shutdownNow()
        }
    }

    /** 同一控制连接内重新协商也必须丢弃旧队列，同时允许新代次继续发送 */
    @Test
    fun peerRestartDropsQueuedFramesOnSameSocket() {
        val client = client()
        val executor = executor(client)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = MemorySocket()
        try {
            executor.execute {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            setSocket(client, socket)
            client.onIapEventBytes(ByteArray(0))
            val link = field(client, "iapLink") as VirtualIap2Link
            link.armMib3()
            val car = Iap2LinkEngine()
            car.start(0)
            val first = car.takeOutput()
            client.onIapEventBytes(first)
            // 本用例隔离异步队列；显式模拟首轮最终确认，使旧会话进入正常态
            val acknowledgement = byteArrayOf(0xFF.toByte(), 0x5A, 0, 9, 0x40, 100, 100, 0, 0)
            acknowledgement[8] = (-acknowledgement.take(8).sumOf { it.toInt() and 255 }).toByte()
            client.onIapEventBytes(acknowledgement)
            val restart = first.copyOf()
            restart[11] = 150.toByte()
            restart[14] = (-restart.slice(6..13).sumOf { it.toInt() and 255 }).toByte()
            client.onIapEventBytes(restart)
            assertEquals(1, link.controlGeneration)
            release.countDown()
            executor.submit {}.get(3, TimeUnit.SECONDS)
            // 旧 detect、旧回显和旧身份请求均丢弃；新身份请求仍须等新回显被确认
            assertEquals(1, socket.requests.get())
            acknowledgement[5] = 151.toByte()
            acknowledgement[8] = (-acknowledgement.take(8).sumOf { it.toInt() and 255 }).toByte()
            client.onIapEventBytes(acknowledgement)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(2, socket.requests.get())
            assertTrue(field(client, "socket") === socket)
            client.postControlIap(Iap2LinkEngine.MARKER)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(3, socket.requests.get())
        } finally {
            release.countDown()
            client.close()
            executor.shutdownNow()
        }
    }

    /** 对端 EOF 不会自动改变 Socket.isClosed，读线程必须主动退役连接与虚拟链路 */
    @Test
    fun remoteEofClosesSocketAndVirtualLink() {
        val client = client()
        val executor = executor(client)
        val socket = MemorySocket()
        try {
            setSocket(client, socket)
            client.onIapEventBytes(Iap2LinkEngine.MARKER)
            client.postControlIap(Iap2LinkEngine.MARKER)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            socket.endInput()
            assertTrue(socket.closedSignal.await(3, TimeUnit.SECONDS))
            // 关闭信号在同一把锁内发出，读取字段前等待完整退役
            val writeLock = field(client, "rtspWriteLock")!!
            synchronized(writeLock) {
                assertNull(field(client, "socket"))
                assertNull(field(client, "iapLink"))
            }
            val sent = socket.requests.get()
            client.postControlIap(Iap2LinkEngine.MARKER)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(sent, socket.requests.get())
        } finally {
            client.close()
            executor.shutdownNow()
        }
    }

    /** 旧读线程的收尾操作不能清掉重连后的控制连接 */
    @Test
    fun lateCloseForOldSocketLeavesNewConnectionIntact() {
        val client = client()
        val old = MemorySocket()
        val next = MemorySocket()
        try {
            setSocket(client, next)
            val close = AirPlayClient::class.java.getDeclaredMethod("closeSession", Socket::class.java)
            close.isAccessible = true
            close.invoke(client, old)
            assertTrue(field(client, "socket") === next)
            assertTrue(!next.isClosed)
        } finally {
            client.close()
            executor(client).shutdownNow()
        }
    }

    /** SETUP 失败后释放计时端口，下一次成功时清除旧错误 */
    @Test fun setupServerFailureCleansResourcesAndNextRequestCanRecover() {
        val client = client()
        val failed = MemorySocket().apply { responseStatus = 500 }
        try {
            setSocket(client, failed)
            assertNull(client.runSetup())
            assertEquals(SetupRetryPolicy.Failure.SERVER, client.lastSetupFailure)
            assertEquals(1, failed.requests.get())
            val timing = field(client, "timingServer") as TimingServer
            assertTrue(timing.port > 0)
            client.close()
            assertEquals(0, timing.port)
            assertTrue(failed.isClosed)
            val next = MemorySocket().apply { responseFields = mapOf("eventPort" to 5000L, "timingPort" to 5001L) }
            setSocket(client, next)
            assertTrue(client.runSetup() != null)
            assertEquals(2, client.initialSetupAttempts)
            assertEquals(SetupRetryPolicy.Failure.NONE, client.lastSetupFailure)
        } finally {
            client.close()
            executor(client).shutdownNow()
        }
    }

    /** 响应已排队但总预算耗尽时仍判超时 */
    @Test fun setupDeadlineCoversTimeBeforeResponseWait() {
        val clock =
            java.util.concurrent.atomic
                .AtomicLong(100)
        val client = client { clock.get() }
        val control = MemorySocket().apply { beforeReply = { clock.addAndGet(2000) } }
        try {
            setSocket(client, control)
            assertNull(client.runSetup(1000))
            assertEquals(SetupRetryPolicy.Failure.TIMEOUT, client.lastSetupFailure)
            assertEquals(1, control.requests.get())
        } finally {
            client.close()
            assertEquals(0, (field(client, "timingServer") as TimingServer).port)
            executor(client).shutdownNow()
        }
    }

    /** 取消后不发送 SETUP，也不计为可恢复超时 */
    @Test fun cancelledSetupDoesNotWriteOrRetry() {
        val client = client()
        val control = MemorySocket()
        try {
            setSocket(client, control)
            client.cancelled = true
            assertNull(client.runSetup())
            assertEquals(0, control.requests.get())
            assertEquals(SetupRetryPolicy.Failure.CANCELLED, client.lastSetupFailure)
        } finally {
            client.close()
            executor(client).shutdownNow()
        }
    }

    /** 创建可注入时钟的测试客户端 */
    private fun client(clock: () -> Long = { System.nanoTime() / 1_000_000L }): AirPlayClient =
        AirPlayClient(
            initialEndpoint =
                Iap2SenderSession.CarPlayEndpoint(
                    ssid = "内存车机",
                    passphrase = "",
                    channel = 0,
                    securityType = 0,
                    airPlayPort = 0,
                    deviceIdentifier = null,
                    publicKey = null,
                    sourceVersion = null,
                    ipAddresses = emptyList(),
                ),
            log = {},
            nowMillis = clock,
        )

    /** 注入内存连接，跳过地址解析 */
    private fun setSocket(
        client: AirPlayClient,
        socket: Socket,
    ) {
        AirPlayClient::class.java
            .getDeclaredField("socket")
            .apply { isAccessible = true }
            .set(client, socket)
    }

    /** 内存测试沿用生产连接登记流程 */
    private fun registerEvent(
        client: AirPlayClient,
        socket: Socket,
        hidDeviceUuids: List<String> = emptyList(),
    ) {
        AirPlayClient::class.java
            .getDeclaredMethod("registerEventCommandChannel", Socket::class.java, ControlCipher::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(client, socket, null, hidDeviceUuids)
    }

    /** 检查会话状态以确认清理完成 */
    private fun field(
        client: AirPlayClient,
        name: String,
    ): Any? =
        AirPlayClient::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(client)

    /** 等待异步发送任务完成，避免靠睡眠猜测执行顺序 */
    private fun executor(client: AirPlayClient): ExecutorService = field(client, "controlIapTx") as ExecutorService

    /** 根据写入的 CSeq 立即回空 RTSP 应答，可独立模拟远端 EOF */
    private class MemorySocket : Socket() {
        val requests = AtomicInteger()
        val closedSignal = CountDownLatch(1)
        val modesWritten = CountDownLatch(1)
        var pauseModes = false
        var applicationStatus = 0L
        var responseStatus = 200
        var responseFields: Map<String, Any?>? = null
        var beforeReply: (() -> Unit)? = null
        val commands = ArrayList<Map<*, *>>()
        var onCommandWritten: ((Map<*, *>) -> Unit)? = null
        private val closed = AtomicBoolean()
        private val incoming = LinkedBlockingQueue<Int>()
        private val input =
            object : InputStream() {
                override fun read(): Int = incoming.take()

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (length == 0) return 0
                    val first = incoming.take()
                    if (first == -1) return -1
                    bytes[offset] = first.toByte()
                    var count = 1
                    while (count < length) {
                        val next = incoming.poll() ?: break
                        if (next == -1) {
                            incoming.offer(-1)
                            break
                        }
                        bytes[offset + count] = next.toByte()
                        count++
                    }
                    return count
                }
            }
        private val output =
            object : OutputStream() {
                override fun write(value: Int) = error("模拟连接应整块写入")

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    check(!closed.get())
                    val request = String(bytes, offset, length, Charsets.ISO_8859_1)
                    val cseq = Regex("CSeq: (\\d+)").find(request)!!.groupValues[1]
                    requests.incrementAndGet()
                    val bodyOffset = request.indexOf("\r\n\r\n") + 4
                    val command =
                        runCatching {
                            BinaryPlist.parse(
                                bytes.copyOfRange(offset + bodyOffset, offset + length),
                            ) as? Map<*, *>
                        }.getOrNull()
                    if (pauseModes && command?.get("type") == "modesChanged") {
                        modesWritten.countDown()
                        return
                    }
                    if (command != null) {
                        commands.add(command)
                        onCommandWritten?.invoke(command)
                    }
                    beforeReply?.invoke()
                    val body = BinaryPlist.encode(responseFields ?: mapOf("status" to applicationStatus))
                    val reply = "RTSP/1.0 $responseStatus 模拟状态\r\nCSeq: $cseq\r\nContent-Length: ${body.size}\r\n\r\n"
                    (reply.toByteArray(Charsets.US_ASCII) + body).forEach { incoming.put(it.toInt() and 255) }
                }
            }

        override fun getInputStream(): InputStream = input

        override fun getOutputStream(): OutputStream = output

        override fun isClosed(): Boolean = closed.get()

        override fun isConnected(): Boolean = true

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                incoming.offer(-1)
                closedSignal.countDown()
            }
        }

        /** 模拟远端结束输入，先保持本地 Socket 未关闭以重现原缺陷 */
        fun endInput() {
            incoming.offer(-1)
        }
    }
}
