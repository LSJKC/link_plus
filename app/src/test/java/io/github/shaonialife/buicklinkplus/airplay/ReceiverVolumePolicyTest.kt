package io.github.shaonialife.buicklinkplus.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverVolumePolicyTest {
    /** 查询返回 501 后仍可设置一次，设置返回 456 后不再重试 */
    @Test fun queryAndSetRejectionAreIndependent() {
        val policy = ReceiverVolumePolicy()
        assertFalse(policy.observe(true, "RTSP/1.0 501 Not Implemented"))
        assertFalse(policy.queryAllowed)
        assertTrue(policy.setAllowed)
        assertTrue(policy.observe(false, "RTSP/1.0 200 OK"))
        assertFalse(policy.observe(false, "RTSP/1.0 456 Rejected"))
        assertFalse(policy.setAllowed)
        assertTrue(ReceiverVolumePolicy().queryAllowed)
    }
}
