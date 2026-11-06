package io.github.shaonialife.buicklinkplus.bt

import io.github.shaonialife.buicklinkplus.iap2.MfiChallengeRecovery
import java.util.Locale

/** 按蓝牙地址记录本轮临时换档，同型号车辆互不影响 */
internal object BtMfiChallengeRecovery {
    private val targets = mutableMapOf<String, MfiChallengeRecovery>()

    /** 双向连接同一目标时沿用本次恢复范围 */
    @Synchronized
    fun forTarget(address: String): MfiChallengeRecovery = targets.getOrPut(address.uppercase(Locale.ROOT)) { MfiChallengeRecovery() }

    /** 每次操作使用新范围，旧线程只更新原对象 */
    @Synchronized
    fun clear() {
        targets.clear()
    }
}
