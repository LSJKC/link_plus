package io.github.shaonialife.buicklinkplus.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 检查交接失败和旧回调不解除手机接管，也不影响新会话 */
class MapHandoffStateTest {
    @Test fun failedHandoffRemainsPausedAndAllowsExplicitRetry() {
        val state = MapHandoffState()
        val request = state.begin()!!
        assertNull(state.begin())
        state.pause(request)
        state.finish(request)
        assertTrue(state.paused)
        val retry = state.begin()!!
        assertTrue(state.verified(retry, false))
        state.finish(retry)
        assertFalse(state.paused)
        assertFalse(state.busy)
    }

    @Test fun oldCompletionCannotChangeNewSessionOrFinishNewOperation() {
        val state = MapHandoffState()
        val previous = state.begin()!!
        state.reset()
        val next = state.begin()!!
        state.pause(next)
        assertFalse(state.verified(previous, false))
        state.finish(previous)
        assertTrue(state.busy)
        assertTrue(state.paused)
        assertTrue(state.owns(next))
    }

    @Test fun phoneObservationNeverOverridesExplicitTransfer() {
        val state = MapHandoffState()
        assertTrue(state.observePhone())
        assertTrue(state.paused)
        val request = state.begin()!!
        assertFalse(state.observePhone())
        assertTrue(state.verified(request, false))
        state.finish(request)
        state.reset()
        assertFalse(state.paused)
    }
}
