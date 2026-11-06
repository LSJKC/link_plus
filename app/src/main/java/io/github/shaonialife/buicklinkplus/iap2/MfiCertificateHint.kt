package io.github.shaonialife.buicklinkplus.iap2

import java.security.MessageDigest

/** 按证书记录本进程成功的挑战长度，只调整顺序；已有隧道成功记忆优先 */
object MfiCertificateHint {
    private val observedLengths = LinkedHashMap<String, Int>()

    /** 收到非空签名应答后记录证书与挑战长度，不保存证书原文 */
    @Synchronized
    fun recordResponse(
        certificateBody: ByteArray,
        length: Int,
    ) {
        if (length !in listOf(20, 32, 16)) return
        val key = fingerprint(certificateBody) ?: return
        observedLengths[key] = length
        if (observedLengths.size > 32) observedLengths.remove(observedLengths.keys.first())
    }

    /** 优先使用本链路成功记录，否则把同证书的应答长度放在基线首位 */
    @Synchronized
    fun orderAttempts(
        certificateBody: ByteArray,
        baseline: List<Int>,
        memory: MfiLengthMemory.Entry,
    ): List<Int> {
        val observed = fingerprint(certificateBody)?.let { observedLengths[it] }
        val preferred = observed?.takeIf { it in baseline && it !in memory.rejected }
        val ordered = MfiLengthMemory.orderAttempts(baseline, memory)
        if (memory.succeeded != null || preferred == null) return ordered
        // 静默记录不能盖过已收到的应答，避免首次档位选错后一直选错
        return listOf(preferred) + ordered.filter { it != preferred }
    }

    /** 证书参数缺失时不建立提示，其他参数不参与匹配 */
    private fun fingerprint(body: ByteArray): String? {
        val certificate = Iap2Csm.parseParams(body).firstOrNull { it.id == 0 }?.value ?: return null
        if (certificate.isEmpty()) return null
        return MessageDigest.getInstance("SHA-256").digest(certificate).joinToString("") { "%02x".format(it) }
    }
}
