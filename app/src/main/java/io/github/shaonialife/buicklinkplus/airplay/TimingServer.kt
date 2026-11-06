package io.github.shaonialife.buicklinkplus.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * AirPlay NTP 计时服务，端口由初始 SETUP 通告，参考 catplay timing_server 和 rtcp_timesync
 * 应答中 t1 回显请求 t3，t2 为接收时刻，t3 为发送时刻
 * 大端格式：[0x80][pt][length=7][rtp_ts=0][t1(8)][t2(8)][t3(8)]
 * pt 为请求 0xD2 或应答 0xD3，时间使用单调时钟的 NTP 64 位格式
 */
class TimingServer(
    private val log: (String) -> Unit = {},
) {
    private var socket: DatagramSocket? = null

    @Volatile
    private var running = false

    /** 收到的 timesync 请求计数（诊断：车机是否真的来同步媒体时钟）。 */
    private var syncRequests = 0L
    private var lastSyncLogAt = 0L

    /** 非 timesync 包计数（诊断：有没有别的 UDP 报文打到这个端口）。 */
    private var unexpected = 0L

    val port: Int get() = socket?.localPort ?: 0

    fun start(): Boolean {
        if (port != 0 && running) return true
        return try {
            val s = DatagramSocket()
            s.reuseAddress = true
            socket = s
            running = true
            Thread({ loop(s) }, "airplay-timing").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            log("TIMING: 启动失败 - ${error.message}")
            false
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buffer = ByteArray(256)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                return // socket closed
            }
            val request = buffer.copyOfRange(0, packet.length)
            val response = buildResponse(request, System.nanoTime())
            if (response == null) {
                unexpected++
                if (unexpected <= 5) {
                    log("TIMING: 收到非 timesync 报文 ${request.size}B（累计 $unexpected）")
                }
                continue
            }
            syncRequests++
            val now = System.currentTimeMillis()
            if (syncRequests <= 3 || now - lastSyncLogAt >= 30_000L) {
                lastSyncLogAt = now
                log("TIMING: timesync 应答 #$syncRequests → ${packet.address.hostAddress}:${packet.port}")
            }
            try {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val PT_TIMESYNC_REQUEST: Byte = 0xD2.toByte()
        private const val PT_TIMESYNC_RESPONSE: Byte = 0xD3.toByte()
        private const val PACKET_BYTES = 32

        /**
         * 构造 timesync 应答，其他请求返回 null
         * [nowNanos] 使用单调时钟，可从任意起点计时
         */
        fun buildResponse(
            request: ByteArray,
            nowNanos: Long,
        ): ByteArray? {
            if (request.size < PACKET_BYTES) return null
            val version = (request[0].toInt() and 0xFF) shr 6
            if (version != 2) return null
            if (request[1] != PT_TIMESYNC_REQUEST) return null
            val length = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
            if (length != PACKET_BYTES / 4 - 1) return null

            val response = ByteArray(PACKET_BYTES)
            response[0] = (2 shl 6).toByte()
            response[1] = PT_TIMESYNC_RESPONSE
            response[2] = 0
            response[3] = (PACKET_BYTES / 4 - 1).toByte()
            // rtp_ts(4..8) = 0
            System.arraycopy(request, 24, response, 8, 8) // t1 = 请求的 t3（ntp_xmit 回显）
            val now = ntpFromNanos(nowNanos)
            System.arraycopy(now, 0, response, 16, 8) // t2 = 接收时刻
            System.arraycopy(now, 0, response, 24, 8) // t3 = 发送时刻
            return response
        }

        /** 单调纳秒 → NTP 64 位（秒<<32 | (纳秒分数 * 2^32 / 1e9)），大端 8 字节。 */
        fun ntpFromNanos(nanos: Long): ByteArray {
            val secs = Math.floorDiv(nanos, 1_000_000_000L)
            val fracNanos = Math.floorMod(nanos, 1_000_000_000L)
            val frac = (fracNanos shl 32) / 1_000_000_000L
            val packed = (secs shl 32) or (frac and 0xFFFF_FFFFL)
            return ByteArray(8) { index -> (packed ushr (8 * (7 - index))).toByte() }
        }
    }
}
