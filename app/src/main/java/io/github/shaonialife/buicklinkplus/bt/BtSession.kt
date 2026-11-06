package io.github.shaonialife.buicklinkplus.bt

import android.content.Context
import io.github.shaonialife.buicklinkplus.iap2.Iap2SenderSession
import io.github.shaonialife.buicklinkplus.iap2.MfiChallengeRecovery
import io.github.shaonialife.buicklinkplus.transport.Iap2LinkChannel
import io.github.shaonialife.buicklinkplus.util.DeviceIdentity
import java.io.IOException

/**
 * 收发两条蓝牙路径共用的会话流程：身份识别、交付端点、保活直到断开
 * socket 登记和收尾由各路径负责
 */
internal fun Iap2LinkChannel.runSenderSession(
    context: Context,
    timeoutMillis: Long,
    log: (String) -> Unit,
    onEndpoint: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onWifiCredentials: (Iap2SenderSession.WifiConfiguration) -> Unit,
    onEndpointUpdate: (Iap2SenderSession.CarPlayEndpoint) -> Unit,
    onPhase: (String) -> Unit,
    challengeRecovery: MfiChallengeRecovery? = null,
    isCurrent: () -> Boolean = { true },
) {
    val bluetoothMac = resolveBluetoothMac(context, log)
    val deviceName = DeviceIdentity.deviceName(context)
    // 启动阶段的恢复共用原截止时间，不能每次重新探测都把整轮无限延长
    val bootstrapDeadline = System.nanoTime() + timeoutMillis * 1_000_000L
    var endpointDelivered = false
    while (isAlive) {
        val remaining =
            if (endpointDelivered) timeoutMillis else (bootstrapDeadline - System.nanoTime()) / 1_000_000L
        if (remaining <= 0) throw IOException("蓝牙启动恢复超过本轮截止时间，尚未收到车机启动参数")
        val session =
            Iap2SenderSession(
                this,
                { line -> log(line) },
                bluetoothMac,
                deviceName,
                onWifiCredentials,
                onEndpointUpdate,
                context.applicationContext,
                onPhase,
                challengeRecovery,
                isCurrent,
            )
        try {
            val endpoint = session.run(remaining)
            checkGeneration(session.linkGeneration)
            if (endpointDelivered) {
                // 已交付过端点时走既有热更新入口，不重建正在工作的显示和视频
                onEndpointUpdate(endpoint)
            } else {
                onEndpoint(endpoint)
                endpointDelivered = true
            }
            session.pumpIncomingForever()
            return
        } catch (_: Iap2LinkChannel.PeerRestartedException) {
            if (!isAlive) return
            log("BT-SESSION: 旧会话已退役，等待新同步确认后重新识别与认证；不复用旧半帧、能力或热点缓存")
            onPhase("车机正在重新协商蓝牙，恢复启动握手…")
        }
    }
}

/**
 * 启动时强制重查蓝牙 MAC，跳过失败缓存。
 *
 * 无内置无线 ADB/shell 后，部分机型（Honor/MagicOS 等）把所有接口的蓝牙地址都脱敏，
 * 软件侧拿不到真实值。此时不再抛异常中断会话，而是继续用占位值启动并明确提示：
 * 车机会按真实地址校验 0x4300/0x4E0E 和 ctrl 广播 id，占位值可能导致不下发 0x4301；
 * 用户可在手机设置里手填本机蓝牙地址（`settings` 偏好的 `bt_mac_override`）。
 */
private fun resolveBluetoothMac(
    context: Context,
    log: (String) -> Unit,
): String {
    val cached = DeviceIdentity.cachedBluetoothMac()
    if (cached != null) return cached
    val mac = DeviceIdentity.bluetoothMac(context, forceRetry = true)
    if (mac == DeviceIdentity.PLACEHOLDER_MAC) {
        log(
            "BT: ⚠ 未能解析真实蓝牙 MAC（仍是占位值）——车机会校验 0x4300 的传输标识，" +
                "占位值可能导致它不下发 0x4301；请在手机设置「连接与设备」里手填本机蓝牙地址",
        )
    } else {
        log("BT: 本机蓝牙 MAC=$mac（0x4300/0x4E0E 与 ctrl 广播 TXT id 同源）")
    }
    return mac
}
