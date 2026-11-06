package io.github.shaonialife.buicklinkplus.iap2

import org.junit.Assert.assertEquals
import org.junit.Test

class MfiCertificateHintTest {
    /** 已有隧道成功记录优先，不用蓝牙提示覆盖另一认证后端的结果 */
    @Test
    fun tunnelSuccessOverridesCertificateHint() {
        val certificate = Iap2Csm.param(0, ByteArray(610) { 8 })
        MfiCertificateHint.recordResponse(certificate, 32)
        val memory = MfiLengthMemory.Entry(succeeded = 20)
        assertEquals(listOf(20, 32, 16), MfiCertificateHint.orderAttempts(certificate, listOf(20, 32, 16), memory))
    }

    /** 不同证书不能仅凭同车型借用提示，同证书应答可以覆盖旧版本的静默排序 */
    @Test
    fun onlyIdenticalCertificateProvidesHint() {
        val certificate = Iap2Csm.param(0, ByteArray(611) { 9 })
        val different = Iap2Csm.param(0, ByteArray(611) { 10 })
        MfiCertificateHint.recordResponse(certificate, 32)
        val memory = MfiLengthMemory.Entry(silent = setOf(20, 32, 16))
        assertEquals(listOf(32, 20, 16), MfiCertificateHint.orderAttempts(certificate, listOf(20, 32, 16), memory))
        assertEquals(listOf(20, 32, 16), MfiCertificateHint.orderAttempts(different, listOf(20, 32, 16), memory))
    }

    /** 缺失证书与本链路明确拒绝的长度不能提供优先提示 */
    @Test
    fun missingCertificateAndRejectedLengthDoNotOverrideOrder() {
        MfiCertificateHint.recordResponse(ByteArray(0), 32)
        val certificate = Iap2Csm.param(0, ByteArray(609) { 11 })
        MfiCertificateHint.recordResponse(certificate, 32)
        val memory = MfiLengthMemory.Entry(rejected = setOf(32))
        assertEquals(listOf(20, 16, 32), MfiCertificateHint.orderAttempts(certificate, listOf(20, 32, 16), memory))
        assertEquals(listOf(20, 32, 16), MfiCertificateHint.orderAttempts(ByteArray(0), listOf(20, 32, 16), MfiLengthMemory.Entry()))
    }
}
