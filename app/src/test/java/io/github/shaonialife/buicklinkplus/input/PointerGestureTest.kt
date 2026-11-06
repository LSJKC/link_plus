package io.github.shaonialife.buicklinkplus.input

import io.github.shaonialife.buicklinkplus.input.PointerGesture.Action
import io.github.shaonialife.buicklinkplus.input.PointerGesture.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回放输入状态，检查注入边界 */
class PointerGestureTest {
    @Test
    fun sameDisplayKeepsCompleteGestureIncludingOriginRelease() {
        val gesture = PointerGesture()
        val calls = ArrayList<Pair<Action, Target>>()
        val inject: (Action, Target) -> Boolean = { action, target ->
            calls.add(action to target)
            true
        }
        gesture.dispatch(true, Target(7, 20, 30), inject)
        gesture.dispatch(true, Target(7, 40, 50), inject)
        gesture.dispatch(false, Target(7, 0, 0), inject)
        assertEquals(listOf(Action.DOWN, Action.MOVE, Action.UP), calls.map { it.first })
        assertEquals(Target(7, 0, 0), calls.last().second)
        assertNull(gesture.activeTarget)
    }

    @Test
    fun rejectedDownRetriesAsDownInsteadOfMove() {
        val gesture = PointerGesture()
        val actions = ArrayList<Action>()
        assertFalse(
            gesture.dispatch(true, Target(7, 20, 30)) { action, _ ->
                actions.add(action)
                false
            },
        )
        assertNull(gesture.activeTarget)
        assertTrue(
            gesture.dispatch(true, Target(7, 25, 35)) { action, _ ->
                actions.add(action)
                true
            },
        )
        assertEquals(listOf(Action.DOWN, Action.DOWN), actions)
    }

    @Test
    fun crossedReleaseUsesOldDisplayLastSuccessfulCoordinates() {
        val gesture = PointerGesture()
        val calls = ArrayList<Pair<Action, Target>>()
        gesture.dispatch(true, Target(7, 400, 300)) { _, _ -> true }
        gesture.dispatch(true, Target(7, 500, 350)) { _, _ -> false }
        gesture.dispatch(false, Target(8, 15, 40)) { action, target ->
            calls.add(action to target)
            true
        }
        assertEquals(listOf(Action.UP to Target(7, 400, 300)), calls)
        assertNull(gesture.activeTarget)
    }

    @Test
    fun crossingWhilePressedClosesOldDisplayBeforeStartingNewDisplay() {
        val gesture = PointerGesture()
        val calls = ArrayList<Pair<Action, Target>>()
        gesture.dispatch(true, Target(7, 400, 300)) { _, _ -> true }
        gesture.dispatch(true, Target(8, 15, 40)) { action, target ->
            calls.add(action to target)
            true
        }
        assertEquals(listOf(Action.UP to Target(7, 400, 300), Action.DOWN to Target(8, 15, 40)), calls)
        assertEquals(Target(8, 15, 40), gesture.activeTarget)
    }

    @Test
    fun rejectedCrossingReleaseCannotStartAnotherDisplay() {
        val gesture = PointerGesture()
        val calls = ArrayList<Action>()
        gesture.dispatch(true, Target(7, 400, 300)) { _, _ -> true }
        assertFalse(
            gesture.dispatch(true, Target(8, 15, 40)) { action, _ ->
                calls.add(action)
                false
            },
        )
        assertEquals(listOf(Action.UP), calls)
        assertEquals(Target(7, 400, 300), gesture.activeTarget)
        assertTrue(gesture.awaitingRelease)
        assertTrue(
            gesture.dispatch(true, Target(8, 15, 40)) { action, _ ->
                calls.add(action)
                true
            },
        )
        assertEquals(listOf(Action.UP, Action.UP, Action.DOWN), calls)
        assertFalse(gesture.awaitingRelease)
    }

    @Test
    fun rejectedReleaseIsRetriedBeforeNextDownOnSameDisplay() {
        val gesture = PointerGesture()
        val calls = ArrayList<Action>()
        gesture.dispatch(true, Target(7, 20, 30)) { _, _ -> true }
        assertFalse(gesture.dispatch(false, Target(7, 25, 35)) { _, _ -> false })
        assertTrue(gesture.awaitingRelease)
        gesture.dispatch(true, Target(7, 40, 50)) { action, _ ->
            calls.add(action)
            true
        }
        assertEquals(listOf(Action.UP, Action.DOWN), calls)
    }

    @Test
    fun throwingReleaseKeepsRecoveryState() {
        val gesture = PointerGesture()
        gesture.dispatch(true, Target(7, 20, 30)) { _, _ -> true }
        runCatching { gesture.dispatch(false, Target(7, 25, 35)) { _, _ -> error("模拟注入服务断开") } }
        assertEquals(Target(7, 20, 30), gesture.activeTarget)
        assertTrue(gesture.awaitingRelease)
    }

    @Test
    fun failedNewDisplayDownDoesNotLeavePressedState() {
        val gesture = PointerGesture()
        gesture.dispatch(true, Target(7, 20, 30)) { _, _ -> true }
        assertFalse(gesture.dispatch(true, Target(8, 10, 15)) { action, _ -> action == Action.UP })
        assertNull(gesture.activeTarget)
        assertFalse(gesture.awaitingRelease)
    }

    @Test
    fun releaseDuringUnavailableLayoutIsCompletedBeforeAnotherDown() {
        val gesture = PointerGesture()
        val calls = ArrayList<Action>()
        gesture.dispatch(true, Target(7, 20, 30)) { _, _ -> true }
        gesture.deferRelease()
        gesture.dispatch(true, Target(7, 40, 50)) { action, _ ->
            calls.add(action)
            true
        }
        assertEquals(listOf(Action.UP, Action.DOWN), calls)
    }

    @Test
    fun idleReleaseDoesNotInjectAndResetReturnsCleanupTarget() {
        val gesture = PointerGesture()
        assertTrue(gesture.dispatch(false, Target(7, 0, 0)) { _, _ -> error("空闲占位不能注入") })
        gesture.dispatch(true, Target(7, 20, 30)) { _, _ -> true }
        assertEquals(Target(7, 20, 30), gesture.reset())
        assertNull(gesture.activeTarget)
        assertFalse(gesture.awaitingRelease)
    }
}
