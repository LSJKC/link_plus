package io.github.shaonialife.buicklinkplus.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FaultCategoryTest {
    /** 取消优先于派生关闭，失效网络与真实超时保持不同类别 */
    @Test fun distinguishesCancellationNetworkCloseAndDeadline() {
        assertEquals(FaultCategory.CANCELLED, FaultCategory.classify("socket closed", true))
        assertEquals(FaultCategory.NETWORK, FaultCategory.classify("ENONET"))
        assertEquals(FaultCategory.PEER_CLOSED, FaultCategory.classify("控制EOF"))
        assertEquals(FaultCategory.TIMEOUT, FaultCategory.classify("等待超时"))
        assertEquals(FaultCategory.REJECTED, FaultCategory.classify("RTSP/1.0 500 Server Error"))
    }
}
