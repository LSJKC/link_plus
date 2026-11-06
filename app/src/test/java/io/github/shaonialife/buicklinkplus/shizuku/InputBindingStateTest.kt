package io.github.shaonialife.buicklinkplus.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputBindingStateTest {
    @Test fun slowBindingIsNotRestartedByRepeatedCalls() {
        val state = InputBindingState()
        val request = state.begin(0)!!
        for (now in 1L..9_999L) assertNull(state.begin(now))
        assertTrue(state.connected(request))
        assertNull(state.begin(50_000))
    }

    @Test fun expiredCallbacksCannotPublishAndRetriesAreBounded() {
        val state = InputBindingState()
        val old = state.begin(0)!!
        val current = state.begin(10_000)!!
        assertFalse(state.connected(old))
        assertNull(state.begin(20_000))
        assertEquals(InputBindingState.Phase.FAILED, state.phase)
        assertFalse(state.connected(current))
        assertNull(state.begin(500_000))
        state.retryFailed()
        assertNotNull(state.begin(500_001))
    }

    @Test fun binderDeathInvalidatesReadyAndOldCallbacks() {
        val state = InputBindingState()
        val old = state.begin(1)!!
        assertTrue(state.connected(old))
        state.reset()
        assertFalse(state.connected(old))
        assertTrue(state.connected(state.begin(2)!!))
    }
}
