package io.github.shaonialife.buicklinkplus.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupRetryPolicyTest {
    /** 500 响应允许完整重试一次，失败、失效网络和取消不追加次数 */
    @Test fun retriesOnlyRecoverableFailuresOnce() {
        val server = SetupRetryPolicy.classify("RTSP/1.0 500 Internal Server Error", null, false)
        assertEquals(SetupRetryPolicy.Failure.SERVER, server)
        assertTrue(SetupRetryPolicy.canRetry(server, 1, 20_000))
        assertFalse(SetupRetryPolicy.canRetry(server, 2, 20_000))
        assertFalse(SetupRetryPolicy.canRetry(server, 1, 1_000))
        for (failure in listOf(SetupRetryPolicy.Failure.NETWORK, SetupRetryPolicy.Failure.CANCELLED, SetupRetryPolicy.Failure.REJECTED)) {
            assertFalse(SetupRetryPolicy.canRetry(failure, 1, 20_000))
        }
        assertEquals(SetupRetryPolicy.Failure.CANCELLED, SetupRetryPolicy.classify(null, java.io.IOException("超时"), true))
        assertEquals(SetupRetryPolicy.Failure.NETWORK, SetupRetryPolicy.classify(null, java.io.IOException("ENONET"), false))
    }
}
