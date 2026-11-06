/*
 * Ported from hybrid63 inject/src/tl/CarLifeNav.java (this project's own prior injected implementation).
 * 0x10030 protobuf parsing ported from hybrid63 inject/src/com/turtlelink/carlife/CarLifeHuClient.java
 * (`parseNaviTurn` / `readProtoVarint`, same project's injected source).
 * 0x5201/0x5202 TLV layout kept byte-for-byte from the prior implementation; field types cross-checked
 * against the real-car logs listed in the KDoc below.
 */

package io.github.shaonialife.buicklinkplus.carlife

import io.github.shaonialife.buicklinkplus.iap2.Iap2Csm
import java.io.ByteArrayOutputStream

/**
 * CarLife → iAP2 导航上行（P4）：把 CarLife `0x10030 NAVI_NEXT_TURN`（以及可选的 AMap 结构化导航源，
 * 见 [CarLifeNavAmap]）归一成 HUD 快照，再编码成 iAP2 `0x5201`（路线指引）与 `0x5202`（机动点）
 * 的 TLV 负载，交给 [io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession] 发送。
 *
 * ## 字段依据（hybrid63 已验证 + 真车日志）
 *
 * - `0x5201`（RouteGuidanceUpdate，附录 ch16 §2.2）：`0x00` 组件表（u16[]）、`0x01` state(u8)、
 *   `0x02` maneuverState(**u64**，固件要求；写成 u8 会让车机整条拒绝)、`0x03` 当前路名、
 *   `0x04` 目的地、`0x05` ETA(u64 epoch 秒)、`0x06` 剩余秒(u64)、`0x07` 剩余总距离(u32 米)、
 *   `0x08` 距离文本、`0x09` 距离单位、`0x0A` 到下一机动(u32 米)、`0x0B` 距离文本、`0x0C` 单位、
 *   `0x0D` 机动索引表(u16[])、`0x0E` 机动数、`0x0F` beingShown、`0x13` 源名、`0x14` 源可用。
 * - `0x5202`（ManeuverUpdate，ch16 §2.3）：`0x00` 组件表、`0x01` 机动索引(u16)、`0x02` 机动描述、
 *   `0x03` 机动类型(u8)、`0x04` 机动后路名、`0x05` 距离(u32)、`0x06` 距离文本、`0x07` 单位、
 *   `0x08` 行驶侧(u8=0 中国右侧通行)、`0x09` junctionType(u8)。
 * - 组件号：`1`=仪表 Cluster、`2`=媒体卡 CardView；真车（Buick E2LB）的识别帧可收清单里同时有
 *   `0x5201/0x5202/0x5204`，订阅（`0x5200`）由车机发起，**收到订阅前不要发**（由
 *   [io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession] 的 `navSubscribed` 闸门保证）。
 *
 * ## 真车样例（2026-11-06 Audi MIB3，BT iAP2；来自 port_evidence 导出的 turtlelink 日志）
 *
 * ```
 * [iap2-bt-client] iap2 tx=0x5201 bytes=74 tlv=0:4B, 1:1B, 3:39B, 13:2B, 19:8B
 * [iap2-bt-client] iap2 tx=0x5201 bytes=48 tlv=0:4B, 1:1B, 3:13B, 13:2B, 19:8B
 * ```
 *
 * 注意：导出器把原始 hex 脱敏成了 `hex=[omitted]`，所以「字节结构」是按日志里的长度 + TLV 长度
 * 反推出来的，不是伪造的抓包；本类编码器对同一快照产出的参数表与上述长度一致。参数头格式为
 * `u16 总长(4+值长) + u16 参数 id`，与 [Iap2Csm] 一致。例（0x5201，单组件，路名 38 字节中文）：
 *
 * ```
 * 0x00: 0004 0000 0001 0002        // 组件 1、2
 * 0x01: 0005 0001 01               // state = 1
 * 0x03: 0027 0003 "…" 00           // 路名（39B = 38 字符 + NUL）
 * 0x0D: 0006 000D 0001             // 机动索引表
 * 0x13: 000C 0013 "CarLife" 00     // 源名（8B）
 * ```
 *
 * ## 数据来源
 *
 * 1. CarLife 手机内置导航：`CarLifeHuClient` 收到 `0x10030` → [parseNextTurn] → [submitNextTurn]；
 *    只有 CarLife 内置百度导航会发这条（0x10030 之外的高德导航需要第 2 条数据源）。
 * 2. AMap LinkSDK：[CarLifeNavAmap] 回调 → [submitHud]。
 *
 * 通知文本兜底（原版 `onNotification`/`detectManeuverType`/`hudTurnFromAppleType`）本阶段不移植：
 * 它依赖通知监听服务（`media/MediaListenerService`），属于媒体/通知链路，P4 导航先走上面两条结构化源。
 *
 * 接线（SessionController，下次改动）：
 * ```
 * CarLifeNav.log = ::appendLog
 * CarLifeNav.sink = { msg -> Iap2SenderSession.active?.sendNavMessage(msg.messageId, msg.payload) == true }
 * // 0x5200 到达后启动 AMap；0x5203/断链后停止：
 * session.onRouteGuidanceStart = { _, _ -> CarLifeNavAmap.start(context) }
 * session.onRouteGuidanceStop = { CarLifeNav.stop(); CarLifeNavAmap.stop() }
 * ```
 */
object CarLifeNav {
    /** iAP2 0x5201 路线指引更新。 */
    const val MSG_ROUTE_GUIDANCE_UPDATE = 0x5201

    /** iAP2 0x5202 机动点更新。 */
    const val MSG_MANEUVER_UPDATE = 0x5202

    /** 0x5200 组件号：仪表 Cluster。 */
    const val COMPONENT_CLUSTER = 1

    /** 0x5200 组件号：媒体卡 CardView。 */
    const val COMPONENT_CARD_VIEW = 2

    /** 默认组件表：两条组件各发一套 0x5201/0x5202（与 hybrid63 一致）。 */
    val DEFAULT_COMPONENTS = intArrayOf(COMPONENT_CLUSTER, COMPONENT_CARD_VIEW)

    /** HUD turn 索引 → Apple 机动类型（BOX OFF RgdProbe 对照表，原样移植）。 */
    private val HUD_TYPE_MAP =
        intArrayOf(
            -1, 3, 50, 2, 48, 4, 47, 1, 49, 13, 14, 13, 3, 14, 6, 15, 16, 3, 3, 3, 3, 3,
            3, 6, 12, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        )

    /** HUD turn 索引 → 机动描述（原样移植）。 */
    private val HUD_ACTION =
        arrayOf(
            "继续行驶", "直行", "右前方行驶", "右转", "右后方行驶", "掉头", "左后方行驶", "左转",
            "左前方行驶", "靠左行驶", "靠右行驶", "靠左行驶", "直行", "靠右行驶", "进入环岛",
            "乘坐渡口", "驶出渡口", "继续行驶", "继续行驶", "继续行驶", "继续行驶", "继续行驶",
            "继续行驶", "进入环岛", "到达目的地", "继续行驶", "继续行驶", "继续行驶", "继续行驶",
            "继续行驶", "继续行驶", "经过服务区", "继续行驶", "继续行驶", "继续行驶", "继续行驶",
            "继续行驶", "继续行驶", "继续行驶", "继续行驶", "继续行驶", "继续行驶", "继续行驶",
            "通过路口",
        )

    private const val HUD_DEDUPE_MS = 700L
    private const val ROAD_MAX_BYTES = 80
    private const val DIST_MAX_BYTES = 24
    private const val SOURCE_MAX_BYTES = 24
    private const val SOURCE_NAME = "CarLife"

    /**
     * CarLife `0x10030` `CarlifeNaviNextTurnInfo` 的解析结果。
     *
     * protobuf 字段号：1 action、2 nextTurn、3 roadName、4 total、5 remain、6 icon（字段 6 不用）。
     */
    data class NextTurn(
        /** 字段 1：CarLife 导航动作码（仅诊断用，HUD 映射只用 [nextTurn]）。 */
        val action: Int,
        /** 字段 2：转向/机动索引，直接当 HUD turn 索引查 [HUD_TYPE_MAP] / [HUD_ACTION]。 */
        val nextTurn: Int,
        /** 字段 3：道路名（UTF-8）。 */
        val road: String,
        /** 字段 4：路线总剩余距离（米）；<=0 视为没有导航数据。 */
        val totalDistanceMeters: Int,
        /** 字段 5：到下一机动的剩余距离（米）。 */
        val remainDistanceMeters: Int,
    )

    /** 归一化后的 HUD 快照：0x10030 与 AMap 两条数据源都先转成它。 */
    data class HudUpdate(
        val turn: Int,
        val remainMeters: Int,
        val totalMeters: Int,
        val road: String,
    )

    /** 一条待发送的 iAP2 导航消息（[messageId] 为 0x5201 或 0x5202）。 */
    class NavMessage(
        val messageId: Int,
        val payload: ByteArray,
    )

    /**
     * 导航消息出口。SessionController 接线时设置，返回 true 表示链路接收（已订阅且写入成功）。
     *
     * 用函数类型而不是直接持有 Iap2SenderSession：本类保持纯 JVM 可测，也避免 carlife↔iap2 反向依赖。
     */
    @Volatile
    var sink: ((NavMessage) -> Boolean)? = null

    /** 诊断日志出口（可选，接 appendLog 便于实车抓日志）。 */
    @Volatile
    var log: ((String) -> Unit)? = null

    private var lastHudAt = 0L
    private var lastHudTurn = -1
    private var lastHudTotal = -1

    /**
     * 解析 CarLife `0x10030`（protobuf：1=action、2=nextTurn、3=roadName、4=total、5=remain）。
     *
     * 与原版 `parseNaviTurn` 等价：只支持 wire type 0（varint）和 2（length-delimited，只认字段 3），
     * 其它 wire type / 越界 / 坏 varint 返回 null，调用方按「解析失败」只记日志、不构造导航帧。
     */
    fun parseNextTurn(payload: ByteArray?): NextTurn? {
        if (payload == null || payload.isEmpty()) return null
        var action = 0
        var nextTurn = 0
        var total = 0
        var remain = 0
        var road = ""
        var index = 0
        try {
            while (index < payload.size) {
                val tag = payload[index].toInt() and 0xFF
                index++
                val field = tag ushr 3
                when (tag and 0x07) {
                    0 -> {
                        val varint = readVarint(payload, index) ?: return null
                        index = varint.second
                        when (field) {
                            1 -> action = varint.first.toInt()
                            2 -> nextTurn = varint.first.toInt()
                            4 -> total = varint.first.toInt()
                            5 -> remain = varint.first.toInt()
                        }
                    }

                    2 -> {
                        val length = readVarint(payload, index) ?: return null
                        index = length.second
                        if (length.first < 0 || index + length.first > payload.size) return null
                        if (field == 3) {
                            road = payload.copyOfRange(index, index + length.first.toInt()).decodeToString()
                        }
                        index += length.first.toInt()
                    }

                    else -> return null
                }
            }
        } catch (_: RuntimeException) {
            return null
        }
        return NextTurn(action, nextTurn, road, total, remain)
    }

    /** 0x10030 解析结果 → HUD 快照（HUD turn 索引直接用字段 2，与原版 CarLifeBridge 一致）。 */
    fun hudUpdate(next: NextTurn): HudUpdate =
        HudUpdate(
            turn = next.nextTurn,
            remainMeters = next.remainDistanceMeters,
            totalMeters = next.totalDistanceMeters,
            road = next.road,
        )

    /**
     * 构造一次导航更新的全部 iAP2 消息：组件 1/2 各一条 0x5201 + 一条 0x5202。
     *
     * `totalMeters <= 0` 视为无有效导航数据 → 返回空列表。**空数据/全 -1 帧绝不能发**，
     * 否则会把车机上已有的 HUD 覆盖成空（旧工程踩坑记录）。
     */
    fun updatesFor(
        update: HudUpdate,
        components: IntArray = DEFAULT_COMPONENTS,
    ): List<NavMessage> {
        if (update.totalMeters <= 0) return emptyList()
        val messages = ArrayList<NavMessage>(components.size * 2)
        for (component in components) {
            messages += NavMessage(MSG_ROUTE_GUIDANCE_UPDATE, buildRouteGuidanceUpdate(component, update))
            messages += NavMessage(MSG_MANEUVER_UPDATE, buildManeuverUpdate(component, update))
        }
        return messages
    }

    /** 导航结束：组件 1/2 各一条 0x5201 清理帧（state=0、距离归零）。 */
    fun clearMessages(components: IntArray = DEFAULT_COMPONENTS): List<NavMessage> =
        components.map { component -> NavMessage(MSG_ROUTE_GUIDANCE_UPDATE, buildRouteGuidanceClear(component)) }

    /**
     * 0x5201 路线指引更新负载（参数表见类 KDoc）。
     *
     * 文本长度上限（路名 80B、距离文本 24B、源名 24B）与 hybrid63 完全一致；超长按字节截断后补 NUL，
     * 与旧实现一样不做 UTF-8 边界回退（真车路名远小于上限，保持行为一致优先）。
     */
    fun buildRouteGuidanceUpdate(
        component: Int,
        update: HudUpdate,
    ): ByteArray {
        val remain = update.remainMeters.coerceAtLeast(0)
        val total = update.totalMeters.coerceAtLeast(0)
        val seconds = total / 11
        val etaEpochSeconds = System.currentTimeMillis() / 1000L + seconds
        val params = ArrayList<ByteArray>(20)
        params += Iap2Csm.u16List(0x00, intArrayOf(component))
        params += Iap2Csm.u8(0x01, 1)
        params += u64(0x02, maneuverState(remain))
        params += str(0x03, update.road, ROAD_MAX_BYTES)
        params += str(0x04, update.road, ROAD_MAX_BYTES)
        params += u64(0x05, etaEpochSeconds)
        params += u64(0x06, seconds.toLong())
        params += Iap2Csm.u32(0x07, total.toLong())
        params += str(0x08, hudDistance(total), DIST_MAX_BYTES)
        params += Iap2Csm.u8(0x09, hudUnits(total))
        params += Iap2Csm.u32(0x0A, remain.toLong())
        params += str(0x0B, hudDistance(remain), DIST_MAX_BYTES)
        params += Iap2Csm.u8(0x0C, hudUnits(remain))
        params += Iap2Csm.u16(0x0D, 1)
        params += Iap2Csm.u16(0x0E, 1)
        params += Iap2Csm.u8(0x0F, 1)
        params += str(0x13, SOURCE_NAME, SOURCE_MAX_BYTES)
        params += Iap2Csm.u8(0x14, 1)
        return concat(params)
    }

    /** 0x5202 机动点更新负载（参数表见类 KDoc）。 */
    fun buildManeuverUpdate(
        component: Int,
        update: HudUpdate,
    ): ByteArray {
        val remain = update.remainMeters.coerceAtLeast(0)
        val params = ArrayList<ByteArray>(12)
        params += Iap2Csm.u16List(0x00, intArrayOf(component))
        params += Iap2Csm.u16(0x01, 1)
        params += str(0x02, hudAction(update.turn), ROAD_MAX_BYTES)
        params += Iap2Csm.u8(0x03, hudType(update.turn))
        params += str(0x04, update.road, ROAD_MAX_BYTES)
        params += Iap2Csm.u32(0x05, remain.toLong())
        params += str(0x06, hudDistance(remain), DIST_MAX_BYTES)
        params += Iap2Csm.u8(0x07, hudUnits(remain))
        params += Iap2Csm.u8(0x08, 0)
        params += Iap2Csm.u8(0x09, 0)
        return concat(params)
    }

    /**
     * 0x5201 导航结束清理帧。
     *
     * 注意：hybrid63 原版把参数 `0x02` 写成 u8=0，但固件对 0x5201 的参数 2 要求 u64，读写布局不符
     * 会让整条被拒；这里改成 u64=0（与正常更新帧一致），其余字段保持原样。
     */
    fun buildRouteGuidanceClear(component: Int): ByteArray {
        val params = ArrayList<ByteArray>(16)
        params += Iap2Csm.u16List(0x00, intArrayOf(component))
        params += Iap2Csm.u8(0x01, 0)
        params += u64(0x02, 0L)
        params += u64(0x05, 0L)
        params += u64(0x06, 0L)
        params += Iap2Csm.u32(0x07, 0L)
        params += str(0x08, "0", DIST_MAX_BYTES)
        params += Iap2Csm.u8(0x09, 2)
        params += Iap2Csm.u32(0x0A, 0L)
        params += str(0x0B, "0", DIST_MAX_BYTES)
        params += Iap2Csm.u8(0x0C, 2)
        params += Iap2Csm.u16(0x0E, 0)
        params += Iap2Csm.u8(0x0F, 0)
        params += str(0x13, SOURCE_NAME, SOURCE_MAX_BYTES)
        params += Iap2Csm.u8(0x14, 1)
        return concat(params)
    }

    /**
     * 提交一次 HUD 快照（0x10030 或 AMap）。700ms 内相同 (turn,total) 去重，等价原版 `onHud`；
     * `totalMeters <= 0` 不发（见 [updatesFor]）。
     *
     * @return 实际通过 [sink] 发出的消息条数；未接线/未订阅/无数据时为 0。
     */
    @Synchronized
    fun submitHud(
        turn: Int,
        remainMeters: Int,
        totalMeters: Int,
        road: String,
    ): Int {
        val now = System.currentTimeMillis()
        if (now - lastHudAt < HUD_DEDUPE_MS && turn == lastHudTurn && totalMeters == lastHudTotal) return 0
        lastHudAt = now
        lastHudTurn = turn
        lastHudTotal = totalMeters
        if (totalMeters <= 0) return 0
        val sent = deliver(updatesFor(HudUpdate(turn, remainMeters, totalMeters, road)))
        log?.invoke(
            "nav hud turn=$turn remain=${remainMeters.coerceAtLeast(0)} total=$totalMeters road=$road sent=$sent",
        )
        return sent
    }

    /** 提交一条 CarLife 0x10030 的解析结果（HUD turn 用字段 2，距离用字段 4/5）。 */
    fun submitNextTurn(next: NextTurn): Int = submitHud(next.nextTurn, next.remainDistanceMeters, next.totalDistanceMeters, next.road)

    /** 导航结束：重置去重状态并发送 0x5201 清理帧。@return 实际发出条数。 */
    @Synchronized
    fun stop(): Int {
        lastHudAt = 0L
        lastHudTurn = -1
        lastHudTotal = -1
        val sent = deliver(clearMessages())
        log?.invoke("nav hud stop sent=$sent")
        return sent
    }

    /** 调试/日志用十六进制（大写，无分隔；与 hybrid63 `CarLifeNav.hex` 一致）。 */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) append("%02X".format(byte))
    }

    private fun deliver(messages: List<NavMessage>): Int {
        val target = sink ?: return 0
        var sent = 0
        for (message in messages) {
            if (target(message)) sent++
        }
        return sent
    }

    private fun maneuverState(remainMeters: Int): Long =
        when {
            remainMeters < 100 -> 3L
            remainMeters < 500 -> 2L
            remainMeters < 2_000 -> 1L
            else -> 0L
        }

    private fun hudType(turn: Int): Int = if (turn < 0 || turn >= HUD_TYPE_MAP.size) 3 else HUD_TYPE_MAP[turn] and 0xFF

    private fun hudAction(turn: Int): String = if (turn < 0 || turn >= HUD_ACTION.size) "继续行驶" else HUD_ACTION[turn]

    private fun hudUnits(meters: Int): Int = if (meters < 1000) 2 else 0

    private fun hudDistance(meters: Int): String =
        if (meters < 1000) {
            meters.toString()
        } else {
            (meters / 1000).toString() + "." + ((meters % 1000) / 100)
        }

    /** u64 参数（[Iap2Csm] 只有 u8/u16/u32 辅助函数，按 ch16 需要 u64 的参数 2/5/6 在这里补）。 */
    private fun u64(
        id: Int,
        value: Long,
    ): ByteArray {
        val bytes = ByteArray(8)
        for (index in 0..7) bytes[index] = (value ushr (56 - index * 8)).toByte()
        return Iap2Csm.param(id, bytes)
    }

    /** NUL 结尾 UTF-8 字符串参数，按 [maxBytes] 截断（hybrid63 `tlvStr` 行为）。 */
    private fun str(
        id: Int,
        value: String,
        maxBytes: Int,
    ): ByteArray {
        val raw = value.encodeToByteArray()
        val length = minOf(raw.size, maxBytes)
        val bytes = ByteArray(length + 1)
        raw.copyInto(bytes, 0, 0, length)
        return Iap2Csm.param(id, bytes)
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun readVarint(
        data: ByteArray,
        start: Int,
    ): Pair<Long, Int>? {
        var index = start
        var value = 0L
        var shift = 0
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xFF
            index++
            value = value or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }
}
