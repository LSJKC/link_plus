package io.github.shaonialife.buicklinkplus.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStallGateTest {
    /** 长时间静止只诊断一次，不循环恢复 */
    @Test fun oneDiagnosticPerStaleEpisode() {
        val gate = MapStallGate()
        assertFalse(gate.inspect(0, true, 1))
        assertFalse(gate.inspect(9_999, true, 1))
        assertTrue(gate.inspect(10_000, true, 1))
        assertFalse(gate.inspect(600_000, true, 1))
        assertFalse(gate.inspect(600_001, true, 2))
        assertTrue(gate.inspect(610_001, true, 2))
    }

    /** 隐藏时不诊断，重新显示后必须重新等待 */
    @Test fun visibilityResetsDeadline() {
        val gate = MapStallGate()
        assertFalse(gate.inspect(0, true, 0))
        assertFalse(gate.inspect(11_000, false, 0))
        assertFalse(gate.inspect(50_000, true, 0))
        assertTrue(gate.inspect(60_000, true, 0))
    }
}
