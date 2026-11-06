package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不对齐的 PCM 短读拼接后保持完整 */
class PcmFrameBufferTest {
    @Test fun arbitraryFragmentsPreserveEverySampleAcrossFrames() {
        val expected = ByteArray(4096 * 3) { (it % 251).toByte() }
        val frame = PcmFrameBuffer(4096)
        val actual = java.io.ByteArrayOutputStream()
        var source = 0
        val fragments = listOf(1, 3, 1408, 17, 4096, 31)
        var index = 0
        while (source < expected.size) {
            val size = minOf(fragments[index++ % fragments.size], frame.remaining, expected.size - source)
            expected.copyInto(frame.bytes, frame.offset, source, source + size)
            source += size
            if (frame.accept(size)) actual.write(frame.bytes)
        }
        assertArrayEquals(expected, actual.toByteArray())
        assertEquals(0, frame.offset)
    }

    @Test fun emptyAndFailedReadsNeverPublishPartialOldData() {
        val frame = PcmFrameBuffer(1408)
        frame.bytes.fill(7, 0, 100)
        assertFalse(frame.accept(100))
        assertFalse(frame.accept(0))
        assertFalse(frame.accept(-1))
        assertEquals(100, frame.offset)
        assertTrue(frame.accept(1308))
    }
}
