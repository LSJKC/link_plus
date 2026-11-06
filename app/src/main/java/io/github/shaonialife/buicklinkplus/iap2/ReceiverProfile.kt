package io.github.shaonialife.buicklinkplus.iap2

import io.github.shaonialife.buicklinkplus.net.AirPlayFeatures

/**
 * 汇总身份、mDNS 和 /info 信号，生成可记录依据的车机特征
 * 优先按能力和已观察到的行为分支，品牌及版本只作提示
 */
object ReceiverProfile {
    /** 按 catplay 区分三代接收端，信号不足时保留原流程 */
    enum class Era { PRE_HOMEKIT, OPTIONAL_PAIRING, MODERN, UNKNOWN }

    /** WiFi 凭据交换的协议类型 */
    enum class WifiGen { LEGACY, NEW, UNKNOWN }

    /** 画像输入：全字段可空——信号到多少算多少。 */
    data class Signals(
        val name: String? = null,
        val model: String? = null,
        val manufacturer: String? = null,
        /** AirPlay features 位图（mDNS TXT / /info）；null = 未取到。 */
        val features: Long? = null,
        /** AirPlay 栈版本（mDNS TXT srcvers / 0x4301 sourceVersion）。 */
        val srcvers: String? = null,
        /** 0x1D01 参数 6：车机声明「会发送」的消息号清单；null = 未提供。 */
        val sendIds: Set<Int>? = null,
        /** 0x1D01 参数 7：车机声明「可接收」的消息号清单；null = 未提供。 */
        val receiveIds: Set<Int>? = null,
    )

    data class Profile(
        val era: Era,
        val wifiGen: WifiGen,
        /** 车机身份键，与 MFi 和音频档位记忆使用同一来源 */
        val identityKey: String,
        /** 各项判断及对应的原始信号 */
        val reasons: List<String>,
    )

    fun classify(signals: Signals): Profile {
        val reasons = ArrayList<String>()
        val pairing = AirPlayFeatures.supportsPairing(signals.features)
        val era =
            when {
                pairing == null -> Era.UNKNOWN
                !pairing -> {
                    reasons += "features(${signals.features?.let { "0x${it.toString(16)}" } ?: "?"}) 无 bit38=HK_PAIRING_AND_ENCRYPT"
                    Era.PRE_HOMEKIT
                }

                else -> {
                    reasons += "features bit38=HK_PAIRING_AND_ENCRYPT 已声明"
                    Era.MODERN
                }
            }
        if (signals.srcvers != null) reasons += "srcvers=${signals.srcvers}"
        if (era != Era.PRE_HOMEKIT && signals.srcvers != null) {
            // 先验提示（不改变判定）：老版本号 + 未声明配对 → v280 可选配对档
            val v = signals.srcvers.toDoubleOrNull()?.let { (it * 100).toInt() } ?: 0
            if (v in 1..36599 && era == Era.UNKNOWN) reasons += "srcvers 提示 v280 可选配对档（仅先验）"
        }

        val wifiGen =
            when {
                signals.sendIds == null && signals.receiveIds == null -> {
                    reasons += "0x1D01 未提供收/发清单"
                    WifiGen.UNKNOWN
                }

                signals.sendIds?.contains(Iap2MessageIds.ACCESSORY_WIFI_CONFIGURATION) == true ||
                    signals.receiveIds?.contains(Iap2MessageIds.REQUEST_ACCESSORY_WIFI_CONFIGURATION) == true -> {
                    reasons += "清单声明 0x5703/0x5702（旧协议在场）"
                    WifiGen.LEGACY
                }

                (signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0) >= 10 -> {
                    // 只有完整清单都缺少旧热点消息时才判新版
                    // 稀疏清单按 UNKNOWN 处理，继续尝试旧协议
                    reasons += "清单完整（${(signals.sendIds?.size ?: 0) + (signals.receiveIds?.size ?: 0)} 项）仍无 0x5703/0x5702——AP 信息由 0x4301 提供"
                    WifiGen.NEW
                }

                (signals.sendIds != null || signals.receiveIds != null) -> {
                    reasons += "清单稀疏且未声明 0x5703/0x5702——不构成「新协议」证据，保守按旧协议（超时兜底）"
                    WifiGen.UNKNOWN
                }

                else -> WifiGen.UNKNOWN
            }
        return Profile(era, wifiGen, identityKeyOf(signals), reasons)
    }

    /** 身份原文（name+model+manufacturer，与 0x1D01 逐字一致；空档跳过）。 */
    fun identityKeyOf(signals: Signals): String =
        listOfNotNull(
            signals.name?.takeIf { it.isNotBlank() },
            signals.model?.takeIf { it.isNotBlank() },
            signals.manufacturer?.takeIf { it.isNotBlank() },
        ).joinToString(" ").ifBlank { "unknown" }

    /** 一行可读判定（进文件日志）。 */
    fun describe(profile: Profile): String =
        "PROFILE: era=${profile.era} wifiGen=${profile.wifiGen} key=${MfiLengthMemory.receiverKey(profile.identityKey)}" +
            " 依据[" + profile.reasons.joinToString("；") + "]"
}
