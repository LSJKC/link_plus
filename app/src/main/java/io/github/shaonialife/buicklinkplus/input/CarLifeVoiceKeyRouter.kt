/* P4 车机语音键 → CarLife 硬键（0x68008）路由。
 *
 * 改编自 CarPToCarL `carlife/VoiceKeyRouter.kt`（MIT，同作者）的状态机：
 * - AirPlay 事件通道 `/command` 的 `requestSiri`（siriAction：1=预热 / 2=按下 / 3=松开）
 *   是多数车机方向盘语音键的真实通路（参考实现实测：HID Consumer 描述符里没有 Voice Command）；
 * - HID Consumer `0xCF`（Voice Command usage）作为少数车机的兜底通路。
 *
 * 语义（参考实现实测结论）：
 * - 按下 → `HardKey.VR_START(0x21)`，让 CarLife+ 用手机自己的麦克风起语音识别；
 * - 松开只是物理按键状态。短按（< 1.5s）**不补 VR_STOP**，交给 CarLife+ 自己结束
 *   （CarPlay 里用户常按住等提示音、松手后才说话，松手就 STOP 会把识别掐掉）；
 * - 按住达到 1.5s（PTT 语义）松手才补 `HardKey.VR_STOP(0x22)` 收尾；
 * - 15s 还没等到松开视为释放帧丢失，作废当前状态、不补 STOP。
 *
 * 本类只做纯状态判定，不碰 socket：真正的下发由 [CarLifeHuClient.sendHardKeyCode] 入
 * carlife-cmd 队列完成。客户端引用用 [setSender] 由接线方（CarLifeScreenSource 会话
 * 连接/断开时）绑定，未绑定时所有调用安全返回 0。
 *
 * 接线（Lead 在 SessionController / CarLifeScreenSource 完成）：
 * - CarLifeScreenSource 增加 `@Volatile var voiceKeySink: CarLifeVoiceKeyRouter?`，在
 *   `touchSink?.setSender(client)` 旁边调用 `voiceKeySink?.setSender(client)`，断开时
 *   `setSender(null)`（参考 P3 触摸 sink 的接线方式）；
 * - SessionController 创建 router 并注入；
 * - AirPlay 事件命令分支：`"requestSiri"` → 取 `params.siriAction` 调 [onVoiceAction]；
 * - HID Consumer 分支：算完 `consumerUsage` 后调 [onConsumerUsage]（0xCF=按下、
 *   null=释放兜底；媒体键 usage 不受影响）。
 */
package io.github.shaonialife.buicklinkplus.input

import io.github.shaonialife.buicklinkplus.carlife.CarLifeHuClient
import io.github.shaonialife.buicklinkplus.carlife.CarLifeProtocol

/**
 * 车机语音键状态机：输入 AirPlay `requestSiri` / HID Consumer `0xCF`，输出 CarLife 硬键。
 *
 * 所有方法线程安全；[onVoiceAction]/[onHidDown]/[onHidUp]/[onConsumerUsage] 返回本次
 * 实际写入 CarLife 控制通道的硬键条数（0 或 1），便于诊断。
 */
class CarLifeVoiceKeyRouter(
    private val log: (String) -> Unit = {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** CarLife 客户端；由接线方在内容源握手成功/断开时绑定/解绑。 */
    @Volatile
    private var sender: CarLifeHuClient? = null

    /** 本次按下的时刻（0 = 当前没有按下状态）。 */
    private var downAt = 0L

    /** 本次按下来自哪条通路，只用于日志。 */
    private var downSource = ""

    /** 本次按下是否已经发过 VR_START（HID 报告是状态式的，只认边沿）。 */
    private var startSent = false

    /** 已成功下发的硬键条数（诊断用）。 */
    private var sentCount = 0L

    /** 绑定/解绑 CarLife 客户端；切换时作废旧手势状态，避免把上一轮的按下带过来。 */
    @Synchronized
    fun setSender(client: CarLifeHuClient?) {
        if (sender === client) return
        sender = client
        reset()
        if (client == null) {
            log("CARLIFE-VOICE: 解绑 CarLife 客户端（语音键不再下发）")
        } else {
            log("CARLIFE-VOICE: 绑定 CarLife 客户端，语音键走 0x68008（VR_START=0x21 / VR_STOP=0x22）")
        }
    }

    /**
     * AirPlay 事件通道 `requestSiri` 入口。
     *
     * @param action `AirPlaySiriAction`：1=预热、2=按下、3=松开；其他值只记日志
     * @return 实际下发条数（0/1）
     */
    @Synchronized
    fun onVoiceAction(action: Int): Int =
        when (action) {
            SIRI_ACTION_PREWARM -> {
                log("CARLIFE-VOICE: 车机语音键 requestSiri 预热（siriAction=1，不触发 CarLife）")
                0
            }

            SIRI_ACTION_BUTTON_DOWN -> down("AirPlay requestSiri")
            SIRI_ACTION_BUTTON_UP -> up("AirPlay requestSiri")
            else -> {
                log("CARLIFE-VOICE: 车机语音键 requestSiri siriAction=$action（未定义，忽略）")
                0
            }
        }

    /** HID Consumer `0xCF` 语音键按下（少数车机走这条路）。 */
    @Synchronized
    fun onHidDown(source: String = "HID Consumer 0xCF"): Int = down(source)

    /** HID Consumer `0xCF` 语音键松开。 */
    @Synchronized
    fun onHidUp(source: String = "HID Consumer 0xCF"): Int = up(source)

    /**
     * 车机 Consumer HID 报告统一入口（接线方无需自己判释放）。
     *
     * - `usage == 0xCF`：语音键按下（HID 状态式报告会重复推，这里按边沿去重）；
     * - `usage == null` 且当前处于按下态：视为释放兜底（车机无按键时的 0 值报告）；
     * - 其他 usage（媒体键等）：忽略，不影响原有媒体键分发。
     *
     * @return 实际下发条数（0/1）
     */
    @Synchronized
    fun onConsumerUsage(usage: Int?): Int =
        when {
            usage == USAGE_VOICE_COMMAND -> down("HID Consumer 0xCF")
            usage == null && startSent -> up("HID Consumer 0xCF")
            else -> 0
        }

    /** 新一轮会话：别把上一轮没收到释放帧的按下状态带过来。 */
    @Synchronized
    fun reset() {
        downAt = 0L
        downSource = ""
        startSent = false
    }

    /** 诊断摘要（会话日志/自检用）。 */
    @Synchronized
    fun statusText(): String {
        val client = sender ?: return "未绑定客户端（已发 $sentCount 条）"
        return "client=${System.identityHashCode(client)} 状态=${if (startSent) "按下" else "空闲"} 已发硬键=$sentCount"
    }

    // ---- 内部状态机（全部在对象锁内）----

    private fun down(source: String): Int {
        val now = nowMillis()
        if (startSent && now - downAt < STALE_PRESS_MILLIS) {
            // HID 报告是状态式的，按住时连推同一个位图；requestSiri 也可能重复 ButtonDown。
            // 只认边沿，否则一次长按会连发几十条 VR_START。
            return 0
        }
        downAt = now
        downSource = source
        startSent = true
        val sent = sendHardKey(CarLifeProtocol.HardKey.VR_START)
        log(
            "CARLIFE-VOICE: 车机语音键（$source 按下）→ CarLife 0x68008 " +
                "keycode=0x${CarLifeProtocol.HardKey.VR_START.toString(16)} VR_START（下发=$sent）",
        )
        return sent
    }

    private fun up(source: String): Int {
        if (!startSent) return 0
        val held = nowMillis() - downAt
        val pressSource = downSource
        startSent = false
        downAt = 0L
        downSource = ""
        if (held >= STALE_PRESS_MILLIS) {
            // 释放帧丢了太久，不知道用户什么时候松的手：不猜，直接作废。
            log("CARLIFE-VOICE: 车机语音键（$pressSource 释放超时 ${held}ms）→ 作废，不补 VR_STOP")
            return 0
        }
        if (held >= PTT_HOLD_MILLIS) {
            val sent = sendHardKey(CarLifeProtocol.HardKey.VR_STOP)
            log(
                "CARLIFE-VOICE: 车机语音键（$pressSource 按下→$source 松开，按住 ${held}ms）" +
                    "→ CarLife 0x68008 keycode=0x${CarLifeProtocol.HardKey.VR_STOP.toString(16)} " +
                    "VR_STOP（PTT 收尾，下发=$sent）",
            )
            return sent
        }
        log(
            "CARLIFE-VOICE: 车机语音键（$pressSource 按下→$source 松开，短按 ${held}ms）" +
                "→ 不发 VR_STOP，交给 CarLife+ 自己结束识别",
        )
        return 0
    }

    /** 下发一条硬键；未绑定客户端/写失败返回 0。 */
    private fun sendHardKey(keycode: Int): Int {
        val client = sender ?: return 0
        val ok = runCatching { client.sendHardKeyCode(keycode) }.getOrDefault(false)
        if (!ok) {
            log(
                "CARLIFE-VOICE: 硬键 0x${keycode.toString(16)} 下发失败" +
                    "（CarLife 控制通道未就绪或已断开）",
            )
            return 0
        }
        sentCount++
        return 1
    }

    companion object {
        /** Apple AirPlaySiriAction：预热，不是按键，别起识别。 */
        const val SIRI_ACTION_PREWARM = 1

        /** Apple AirPlaySiriAction：语音键按下。 */
        const val SIRI_ACTION_BUTTON_DOWN = 2

        /** Apple AirPlaySiriAction：语音键松开。 */
        const val SIRI_ACTION_BUTTON_UP = 3

        /** 按住超过这个时长才按 PTT 语义在松手时补 VR_STOP。 */
        const val PTT_HOLD_MILLIS = 1_500L

        /** 超过这个时长还没等到松开就当「丢了释放帧」，状态作废，不补发 VR_STOP。 */
        const val STALE_PRESS_MILLIS = 15_000L

        /** HID Consumer Page usage：Voice Command（车机方向盘语音键）。 */
        const val USAGE_VOICE_COMMAND = 0xCF
    }
}
