package io.github.shaonialife.buicklinkplus.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeGestureGateTest {
    /**
     * 拖动或抬起时切换面板几何都只能终止旧手势，下次按下才使用新坐标
     */
    @Test
    fun changingLayoutCannotContinueOrRestartPhysicalGesture() {
        for (changeOnRelease in listOf(false, true)) {
            val gate = SafeGestureGate()
            val oldLayout = Any()
            val newLayout = Any()
            assertTrue(gate.accept(true, true, oldLayout))
            assertFalse(gate.accept(!changeOnRelease, true, newLayout))
            if (!changeOnRelease) {
                assertFalse(gate.accept(true, true, newLayout))
                assertFalse(gate.accept(false, true, newLayout))
            }
            assertTrue(gate.accept(true, true, newLayout))
            assertTrue(gate.accept(false, true, newLayout))
        }
    }

    @Test
    fun outsideDownCannotBecomeInsideClickBeforePhysicalRelease() {
        val gate = SafeGestureGate()
        assertFalse(gate.accept(true, false))
        assertFalse(gate.accept(true, true))
        assertFalse(gate.accept(false, true))
        assertTrue(gate.accept(true, true))
    }

    @Test
    fun leavingSafeAreaClosesGestureAtLastInjectedPointAndNeverReenters() {
        val gate = SafeGestureGate()
        val pointer = PointerGesture()
        val actions = mutableListOf<PointerGesture.Action>()
        val target = PointerGesture.Target(7, 120, 80)
        val inject: (PointerGesture.Action, PointerGesture.Target) -> Boolean = { action, actual ->
            assertEquals(target, actual)
            actions.add(action)
            true
        }
        if (gate.accept(true, true)) pointer.dispatch(true, target, inject)
        if (!gate.accept(true, false)) pointer.dispatch(false, checkNotNull(pointer.activeTarget), inject)
        assertFalse(gate.accept(true, true))
        assertFalse(gate.accept(false, false))
        assertEquals(listOf(PointerGesture.Action.DOWN, PointerGesture.Action.UP), actions)
        assertTrue(gate.accept(true, true))
    }
}
