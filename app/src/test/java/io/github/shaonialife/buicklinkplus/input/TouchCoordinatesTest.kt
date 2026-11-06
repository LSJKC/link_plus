package io.github.shaonialife.buicklinkplus.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchCoordinatesTest {
    @Test
    fun failedMapResizeStillMapsVisibleCenterIntoActualDisplayCenter() {
        assertEquals(600, scaleTouchCoordinate(300, 600, 1200))
        assertEquals(400, scaleTouchCoordinate(200, 400, 800))
        assertEquals(300, scaleTouchCoordinate(600, 1200, 600))
    }

    @Test
    fun equalSizeKeepsCoordinatesAndEdgesStayInsideTarget() {
        assertEquals(123, scaleTouchCoordinate(123, 476, 476))
        assertEquals(0, scaleTouchCoordinate(-1, 476, 952))
        assertEquals(950, scaleTouchCoordinate(476, 476, 952))
        assertEquals(0, scaleTouchCoordinate(0, 1, 1))
    }
}
