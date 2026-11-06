package io.github.shaonialife.buicklinkplus.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按同车成功日志的初始归属、车机接管、请求界面和倒车借屏顺序回归 */
class AirPlayResourceStateTest {
    @Test
    fun initialScreenBelongsToCarUntilRequested() {
        val state = AirPlayResourceState()
        state.initialize(mapOf("initialPermanentEntity" to listOf(resource(1, "permanentEntity" to 2L, "takeConstraint" to 500L))))
        state.claimAudio()
        assertFalse(state.phoneOwnsScreen)
        state.requestPhoneScreen()
        assertTrue(state.phoneOwnsScreen)
        state.applyCarChange(change(1))
        assertEquals(2L, screen(state)["entity"])
        assertEquals(2L, screen(state)["permanentEntity"])
        state.requestPhoneScreen()
        assertTrue(state.phoneOwnsScreen)
    }

    @Test
    fun screenBorrowingSurvivesRequestUiUntilEveryBorrowerReturns() {
        val state = AirPlayResourceState()
        state.applyCarChange(change(3, "borrowID" to "倒车"))
        state.applyCarChange(change(3, "borrowID" to "告警"))
        state.applyCarChange(change(3, "borrowID" to "倒车"))
        assertEquals(2L, screen(state)["entity"])
        assertEquals(1L, screen(state)["permanentEntity"])
        state.requestPhoneScreen()
        assertFalse(state.phoneOwnsScreen)
        state.applyCarChange(change(4, "borrowID" to "倒车"))
        assertFalse(state.phoneOwnsScreen)
        state.applyCarChange(change(4, "borrowID" to "告警"))
        assertTrue(state.phoneOwnsScreen)
    }

    @Test
    fun permanentTakeAndBorrowingAreIndependentAndSnapshotsDoNotChange() {
        val state = AirPlayResourceState()
        val before = state.command()
        state.applyCarChange(change(1))
        state.applyCarChange(change(3, "borrowID" to "临时界面"))
        state.applyCarChange(change(4, "borrowID" to "临时界面"))
        assertFalse(state.phoneOwnsScreen)
        state.applyCarChange(change(99))
        assertFalse(state.phoneOwnsScreen)
        state.applyCarChange(change(2))
        assertTrue(state.phoneOwnsScreen)
        assertEquals(before, state.command())
    }

    @Test
    fun unknownResourcesAndTakeConstraintsDoNotStealScreen() {
        val state = AirPlayResourceState()
        state.applyCarChange(change(1, "takeConstraint" to 999L))
        state.requestPhoneScreen()
        assertFalse(state.phoneOwnsScreen)
        state.applyCarChange(mapOf("resources" to listOf(resource(999, "transferType" to 2L))))
        assertFalse(state.phoneOwnsScreen)
        state.initialize(null)
        assertTrue(state.phoneOwnsScreen)
    }

    @Test
    fun transportSuccessDoesNotHideApplicationFailure() {
        fun accepted(
            status: String = "RTSP/1.0 200 OK",
            value: Any? = mapOf("status" to 0L),
        ): Boolean = AirPlayCommandResult.accepted(status, BinaryPlist.encode(value))
        assertTrue(accepted())
        assertTrue(accepted(value = emptyMap<String, Any?>()))
        assertTrue(AirPlayCommandResult.accepted("RTSP/1.0 200 OK", ByteArray(0)))
        assertFalse(accepted(value = mapOf("status" to -1L)))
        assertFalse(accepted(value = mapOf("status" to "0")))
        assertFalse(accepted(value = mapOf("status" to 0.5)))
        assertFalse(accepted(value = listOf(0L)))
        assertFalse(accepted(status = "RTSP/1.0 500 error 200"))
        assertFalse(accepted(status = "RTSP/1.0 2000 error"))
        assertFalse(AirPlayCommandResult.accepted("RTSP/1.0 200 OK", byteArrayOf(1, 2, 3)))
    }

    private fun resource(
        id: Int,
        vararg values: Pair<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>("resourceID" to id.toLong(), *values)

    private fun change(
        type: Int,
        vararg values: Pair<String, Any?>,
    ): Map<String, Any?> = mapOf("resources" to listOf(resource(1, "transferType" to type.toLong(), *values)))

    private fun screen(state: AirPlayResourceState): Map<*, *> =
        ((state.command()["params"] as Map<*, *>)["resources"] as List<*>).first() as Map<*, *>
}
