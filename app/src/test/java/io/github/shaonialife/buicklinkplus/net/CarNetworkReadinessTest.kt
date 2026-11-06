package io.github.shaonialife.buicklinkplus.net

import org.junit.Assert.assertEquals
import org.junit.Test

/** 分别检查接口迟到和网络失效的错误分类 */
class CarNetworkReadinessTest {
    @Test fun missingPropertiesOnExistingNetworkNeverMeanLost() {
        assertEquals(CarNetworkReadiness.State.WAITING, CarNetworkReadiness.classify(true, false, false))
        assertEquals(CarNetworkReadiness.State.WAITING, CarNetworkReadiness.classify(true, true, false))
        assertEquals(CarNetworkReadiness.State.WAITING, CarNetworkReadiness.classify(true, false, true))
        assertEquals(CarNetworkReadiness.State.READY, CarNetworkReadiness.classify(true, true, true))
    }

    @Test fun removedNetworkCannotBecomeReadyFromStaleInterface() {
        assertEquals(CarNetworkReadiness.State.LOST, CarNetworkReadiness.classify(false, true, true))
        assertEquals(CarNetworkReadiness.State.LOST, CarNetworkReadiness.classify(false, false, false))
    }
}
