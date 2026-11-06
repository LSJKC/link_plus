// 仅 JVM 单元测试使用的假 CarLife+ 引擎（非生产代码，无移植来源）。
package io.github.shaonialife.buicklinkplus.carlife

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 假 CarLife+ 引擎（JVM 测试替身）：在 127.0.0.1 上监听真实引擎会监听的 7 个端口，
 * 按真机实测时序应答握手，并在 8240 视频通道上推合成 H.264 码流。
 *
 * ## 为什么这样写
 *
 * 真机验证（2026-10-23）确定的握手闭环是
 * `98305 → 65538 → 98343 → 65540 → 98307 → 98386 → 65570 → 98311 → 65544 → 98313`，
 * 之后 8240 出帧。这里**逐字节手写** 8B 控制帧与 protobuf 编码（不调用生产
 * [CarLifeProtocol.packCommand] 等发送侧工具），这样测试验证的是「生产客户端 ↔ 线上线格式」，
 * 而不是「生产编解码器 ↔ 自己」。
 *
 * 帧格式来自 [CarLifeHuClient.videoReader]（读取侧才是权威）：
 * `[u32 BE length][u32 BE timestampMs][u32 BE type]` + Annex-B H.264 负载；
 * 客户端**不校验也不使用** type 字段，所以 [VIDEO_PKG_TYPE] 只是占位值。
 *
 * SPS/PPS/IDR 使用同包测试工具 [SyntheticH264]（与 H264SpsTest 同一套位写入器）合成
 * 1280×720 Baseline SPS + PPS + 裸 IDR，P 帧为裸非关键帧。
 *
 * ## 清理
 *
 * 所有 accept/命令线程均为 daemon；[stop] 先置 running=false，再关 ServerSocket 与所有
 * 已接受的 socket，并短暂 join 线程。测试里必须放在 finally 中调用。
 */
internal class FakeCarLifeEngine {
    private val running = AtomicBoolean(false)
    private val servers = LinkedHashMap<Int, ServerSocket>()
    private val acceptedSockets = CopyOnWriteArrayList<Socket>()
    private val acceptThreads = CopyOnWriteArrayList<Thread>()

    /** 客户端在控制通道（7240）上发来的消息 id，按到达顺序记录（观测用）。 */
    val receivedCmdTypes = CopyOnWriteArrayList<Int>()

    /** 客户端 98305 的 payload（协议 3.0 应为 `08 03 10 00`）；null = 还没收到。 */
    @Volatile
    var protocolVersionPayload: ByteArray? = null
        private set

    /** 客户端 98311 的申请值（宽, 高, 帧率）；null = 还没收到。 */
    @Volatile
    var requestedEncoderInfo: Triple<Int, Int, Int>? = null
        private set

    /** 客户端 98307 的 payload（应携带车机画布 display 字段，观测用）。 */
    @Volatile
    var huInfoPayload: ByteArray? = null
        private set

    /** 已写入 8240 的视频帧数。 */
    @Volatile
    var videoFramesSent: Int = 0
        private set

    /** 假引擎侧非预期异常（正常收尾的 IOException 不算）；测试末尾断言为 null。 */
    @Volatile
    var failure: Throwable? = null
        private set

    /** 首帧（SPS + PPS + IDR）负载，测试用它做逐字节比对。 */
    val idrFramePayload: ByteArray =
        SyntheticH264.spsAnnexB(WIDTH_MBS, HEIGHT_MBS) + SyntheticH264.idrAnnexB()

    /** 第二帧（裸 P 帧）负载。 */
    val pFramePayload: ByteArray = SyntheticH264.nonKeyAnnexB()

    private val writeLock = Any()

    @Volatile
    private var cmdOut: OutputStream? = null

    @Volatile
    private var videoSocket: Socket? = null

    @Volatile
    private var beatCount = 0

    private val videoPushStarted = AtomicBoolean(false)

    /** 绑定全部 7 个端口并开始 accept。端口被占用时抛 [IOException]，由测试转成 assume 跳过。 */
    fun start() {
        check(!running.get()) { "假引擎已经启动" }
        val bound = LinkedHashMap<Int, ServerSocket>()
        try {
            for (port in PORTS) {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(HOST, port))
                bound[port] = server
            }
        } catch (error: IOException) {
            for (server in bound.values) runCatching { server.close() }
            throw error
        }
        servers.putAll(bound)
        running.set(true)
        for ((port, server) in servers) {
            val thread = Thread({ acceptLoop(port, server) }, "fake-carlife-accept-$port")
            thread.isDaemon = true
            acceptThreads += thread
            thread.start()
        }
    }

    private fun acceptLoop(
        port: Int,
        server: ServerSocket,
    ) {
        while (running.get()) {
            val socket =
                try {
                    server.accept()
                } catch (_: IOException) {
                    if (running.get()) continue else return
                }
            socket.tcpNoDelay = true
            acceptedSockets += socket
            when (port) {
                CarLifeProtocol.PORT_CMD -> {
                    cmdOut = socket.getOutputStream()
                    commandLoop(socket)
                }

                CarLifeProtocol.PORT_VIDEO -> videoSocket = socket

                // 其余通道（9240/9241/9340/9242/9440）本测试里客户端只读或不用：
                // 保持连接即可，关 socket 的收尾由 stop() 统一做。
                else -> Unit
            }
        }
    }

    /** 7240 控制通道读循环：8B 头 `[u16 len][u16 rsv][u32 type]` + payload。 */
    private fun commandLoop(socket: Socket) {
        val data = DataInputStream(socket.getInputStream())
        try {
            while (running.get()) {
                val header = ByteArray(8)
                data.readFully(header)
                val length = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                val serviceType = readU32(header, 4)
                val payload = ByteArray(length)
                if (length > 0) data.readFully(payload)
                receivedCmdTypes += serviceType
                handleCommand(serviceType, payload)
            }
        } catch (error: Exception) {
            // 客户端 stop()/测试收尾会关 socket，这里读到的 EOF/RST 属于正常结束，不算假引擎故障。
            if (running.get() && error !is IOException) failure = error
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleCommand(
        serviceType: Int,
        payload: ByteArray,
    ) {
        when (serviceType) {
            // 车机上报协议版本（要求 3.0，4.0 会触发加密鉴权）→ 引擎回 match=1。
            CarLifeProtocol.HU_PROTOCOL_VERSION -> {
                protocolVersionPayload = payload.copyOf()
                sendCommand(CarLifeProtocol.MD_VERSION_MATCH, protoVarint(1, 1))
            }

            // 车机统计信息 → 引擎回前台状态 + 手机设备信息 + 模块状态（真机时序）。
            CarLifeProtocol.HU_STATISTICS -> {
                sendCommand(MD_FOREGROUND_STATUS, EMPTY)
                sendCommand(
                    CarLifeProtocol.MD_INFO,
                    protoString(1, "Android") + protoString(7, "FakeCarLifeMD"),
                )
                sendCommand(CarLifeProtocol.MD_MODULE_STATUS, moduleStatus(listOf(1 to 1, 2 to 1)))
            }

            // 车机设备信息（HU_INFO）→ 引擎回模块状态；故意留一个 0，驱动客户端回 98344。
            CarLifeProtocol.HU_INFO -> {
                huInfoPayload = payload.copyOf()
                sendCommand(CarLifeProtocol.MD_MODULE_STATUS, moduleStatus(listOf(1 to 1, 2 to 0)))
            }

            // 特征配置：引擎接受后回 0 字节的 FEATURE_CONFIG_DONE（客户端有序初始化在等它）。
            CarLifeProtocol.HU_FEATURE_CONFIG -> {
                sendCommand(CarLifeProtocol.MD_FEATURE_CONFIG_DONE, EMPTY)
            }

            // 编码参数申请：解析请求的宽/高/帧率，原样回显为「引擎确认值」。
            CarLifeProtocol.HU_VIDEO_ENC_INIT -> {
                val request = parseEncoderInfo(payload) ?: return
                requestedEncoderInfo = request
                val (width, height, fps) = request
                sendCommand(
                    CarLifeProtocol.MD_VIDEO_ENC_INIT,
                    protoVarint(1, width) + protoVarint(2, height) + protoVarint(3, fps),
                )
            }

            // 启动推流：开始往 8240 写 H.264（只推一次，测试只关心首帧序列）。
            CarLifeProtocol.VIDEO_ENC_START -> pushVideoFramesOnce()

            // 帧率变更：真机引擎回 65549；本测试 requestedFps=20 不会触发，保留以贴近真机。
            CarLifeProtocol.HU_VIDEO_FRAME_RATE -> sendCommand(CarLifeProtocol.MD_VIDEO_FRAME_RATE_DONE, EMPTY)

            // 模块控制（客户端对 status=0 的回应）：无需回包。
            CarLifeProtocol.HU_MODULE_CONTROL -> Unit

            // 心跳保活：无需回包，只计数。
            CarLifeProtocol.HEARTBEAT -> beatCount++

            else -> Unit
        }
    }

    private fun pushVideoFramesOnce() {
        if (!videoPushStarted.compareAndSet(false, true)) return
        val thread =
            Thread({
                val socket = awaitVideoSocket(VIDEO_SOCKET_WAIT_MS) ?: return@Thread
                try {
                    val out = socket.getOutputStream()
                    writeVideoFrame(out, IDR_TIMESTAMP_MS, idrFramePayload)
                    writeVideoFrame(out, P_TIMESTAMP_MS, pFramePayload)
                } catch (error: Exception) {
                    // 客户端已停止/关闭视频通道时的 IOException 属正常收尾。
                    if (running.get() && error !is IOException) failure = error
                }
            }, "fake-carlife-video-push")
        thread.isDaemon = true
        thread.start()
    }

    /** 视频 socket 由 accept 线程异步挂上；等它出现（超时返回 null，不无限挂起）。 */
    private fun awaitVideoSocket(timeoutMs: Long): Socket? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (running.get() && System.nanoTime() < deadline) {
            val socket = videoSocket
            if (socket != null && !socket.isClosed) return socket
            Thread.sleep(10)
        }
        return null
    }

    /** 8240 帧格式：[u32 len][u32 timestampMs][u32 type] + Annex-B payload。 */
    private fun writeVideoFrame(
        out: OutputStream,
        timestampMs: Long,
        payload: ByteArray,
    ) {
        val header = ByteArray(12)
        writeU32(header, 0, payload.size)
        writeU32(header, 4, (timestampMs and 0xFFFF_FFFFL).toInt())
        writeU32(header, 8, VIDEO_PKG_TYPE)
        synchronized(writeLock) {
            out.write(header)
            out.write(payload)
            out.flush()
        }
        videoFramesSent++
    }

    /** 7240 控制帧发送：独立手写 8B 头，避免复用生产发送工具（避免测试自证）。 */
    private fun sendCommand(
        serviceType: Int,
        payload: ByteArray,
    ) {
        val out = cmdOut ?: return
        val frame = ByteArray(8 + payload.size)
        frame[0] = ((payload.size ushr 8) and 0xFF).toByte()
        frame[1] = (payload.size and 0xFF).toByte()
        // frame[2..3] = reserved，发送恒为 0（接收侧忽略）。
        writeU32(frame, 4, serviceType)
        System.arraycopy(payload, 0, frame, 8, payload.size)
        synchronized(writeLock) {
            try {
                out.write(frame)
                out.flush()
            } catch (_: IOException) {
                // 客户端已关闭控制通道（测试清理阶段）：不是假引擎的故障。
            }
        }
    }

    fun stop() {
        running.set(false)
        for (server in servers.values) runCatching { server.close() }
        servers.clear()
        for (socket in acceptedSockets) runCatching { socket.close() }
        acceptedSockets.clear()
        cmdOut = null
        videoSocket = null
        val deadline = System.currentTimeMillis() + THREAD_JOIN_MS
        for (thread in acceptThreads) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            runCatching { thread.join(remaining) }
        }
        acceptThreads.clear()
    }

    /** 解析 98311 的 `{1 width, 2 height, 3 fps, ...}`（本消息全部是 varint 字段）。 */
    private fun parseEncoderInfo(payload: ByteArray): Triple<Int, Int, Int>? {
        var width = 0
        var height = 0
        var fps = 0
        var index = 0
        while (index < payload.size) {
            val tag = readVarint(payload, index) ?: return null
            index = tag.second
            if ((tag.first and 0x07) != 0L) return null
            val value = readVarint(payload, index) ?: return null
            index = value.second
            when ((tag.first ushr 3).toInt()) {
                1 -> width = value.first.toInt()
                2 -> height = value.first.toInt()
                3 -> fps = value.first.toInt()
            }
        }
        if (width <= 0 || height <= 0) return null
        return Triple(width, height, if (fps > 0) fps else 30)
    }

    /** 读一个 protobuf varint，返回 (值, 新下标)；越界/超长返回 null。 */
    private fun readVarint(
        data: ByteArray,
        from: Int,
    ): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = from
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xFF
            index++
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    // ---- 手写 protobuf 编码（假引擎侧独立实现，不复用生产 CarLifeProtocol） ----

    private fun moduleStatus(modules: List<Pair<Int, Int>>): ByteArray {
        var out = protoVarint(1, modules.size)
        for ((id, status) in modules) {
            out += protoBytes(2, protoVarint(1, id) + protoVarint(2, status))
        }
        return out
    }

    private fun protoVarint(
        field: Int,
        value: Int,
    ): ByteArray = protoTag(field, 0) + rawVarint(value.toLong())

    private fun protoString(
        field: Int,
        value: String,
    ): ByteArray = protoBytes(field, value.toByteArray(Charsets.UTF_8))

    private fun protoBytes(
        field: Int,
        value: ByteArray,
    ): ByteArray = protoTag(field, 2) + rawVarint(value.size.toLong()) + value

    private fun protoTag(
        field: Int,
        wireType: Int,
    ): ByteArray = rawVarint((field.toLong() shl 3) or wireType.toLong())

    private fun rawVarint(input: Long): ByteArray {
        val out = ArrayList<Byte>(10)
        var value = input
        while (true) {
            val low = (value and 0x7F).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.add(low.toByte())
                return out.toByteArray()
            }
            out.add((low or 0x80).toByte())
        }
    }

    private fun readU32(
        data: ByteArray,
        offset: Int,
    ): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeU32(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    companion object {
        /** 客户端固定连 127.0.0.1；假引擎只绑回环地址，不暴露到局域网。 */
        const val HOST = "127.0.0.1"

        /** 首帧 IDR 的帧头毫秒时间戳（测试断言 pts 原样透传）。 */
        const val IDR_TIMESTAMP_MS = 1_234_567L

        /** 第二帧 P 帧的帧头毫秒时间戳。 */
        const val P_TIMESTAMP_MS = 1_234_607L

        /** 合成 1280×720：80 个宏块列 / 45 个宏块行（frame_mbs_only=1，无裁剪）。 */
        private const val WIDTH_MBS = 80
        private const val HEIGHT_MBS = 45

        /** 视频包头 [8..11] 的包类型：客户端不读/不校验，这里取一个与音频 0x3/0x4 段不冲突的占位值。 */
        private const val VIDEO_PKG_TYPE = 0x30005

        /** 65563（0x1001B）前台状态广播：真机在版本匹配后下发，客户端在 MD_IGNORED_IDS 里忽略。 */
        private const val MD_FOREGROUND_STATUS = 65563

        /** 等待客户端 accept 视频通道的上限（本地回环，正常几十毫秒内就位；留足慢机余量）。 */
        private const val VIDEO_SOCKET_WAIT_MS = 5_000L

        /** stop() 时等待 daemon 线程收尾的总时长。 */
        private const val THREAD_JOIN_MS = 1_000L

        private val EMPTY = ByteArray(0)

        private val PORTS =
            intArrayOf(
                CarLifeProtocol.PORT_CMD,
                CarLifeProtocol.PORT_VIDEO,
                CarLifeProtocol.PORT_MEDIA,
                CarLifeProtocol.PORT_TTS,
                CarLifeProtocol.PORT_VR,
                CarLifeProtocol.PORT_TOUCH,
                CarLifeProtocol.PORT_DATA,
            )
    }
}
