package io.github.shaonialife.buicklinkplus.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMuteLeaseTest {
    @Test
    fun zeroVolumeAndOriginalMuteArePreserved() {
        for (muted in listOf(false, true)) {
            val lease = MediaMuteLease()
            val owner = Any()
            lease.acquire(owner, MediaMuteLease.Snapshot(0, muted))
            assertEquals(MediaMuteLease.Snapshot(0, muted), lease.release(owner))
            assertNull(lease.release(owner))
        }
    }

    @Test
    fun lateOldSessionCannotRestoreOrReplaceNewSessionState() {
        val lease = MediaMuteLease()
        val old = Any()
        val current = Any()
        lease.acquire(old, MediaMuteLease.Snapshot(6, false))
        lease.acquire(current, MediaMuteLease.Snapshot(0, true))
        assertNull(lease.release(old))
        assertEquals(MediaMuteLease.Snapshot(6, false), lease.release(current))
        assertNull(lease.release(current))
    }

    @Test
    fun stoppedCaptureCannotAcquireAfterNewSessionOrAfterEarlyRelease() {
        val lease = MediaMuteLease()
        val old = Any()
        val current = Any()
        assertNull(lease.release(old))
        assertFalse(lease.acquire(old, MediaMuteLease.Snapshot(4, false), active = false))
        assertNull(lease.owner)
        lease.acquire(current, MediaMuteLease.Snapshot(6, false))
        assertFalse(lease.acquire(old, MediaMuteLease.Snapshot(0, true), active = false))
        assertEquals(current, lease.owner)
        assertNull(lease.release(old))
        assertEquals(MediaMuteLease.Snapshot(6, false), lease.release(current))
    }
}
