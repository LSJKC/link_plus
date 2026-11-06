package io.github.shaonialife.buicklinkplus.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证分组快照不会被后续选择或源列表修改污染
 */
class ApplicationSnapshotTest {
    @Test fun selectionAndSourceChangesDoNotMutatePublishedSnapshot() {
        val apps = mutableListOf("A", "B", "C")
        val selected = mutableSetOf("B")
        val first = applicationSnapshot(apps, selected) { it }
        selected += "C"
        apps.remove("A")
        val second = applicationSnapshot(apps, selected) { it }
        assertEquals(listOf("B"), first.desktop)
        assertEquals(listOf("A", "C"), first.others)
        assertEquals(listOf("B", "C"), second.desktop)
        assertTrue(second.others.isEmpty())
    }

    @Test fun missingSelectedAppsDoNotCreatePhantomRows() {
        val snapshot = applicationSnapshot(listOf("A", "B"), setOf("removed")) { it }
        assertTrue(snapshot.desktop.isEmpty())
        assertEquals(listOf("A", "B"), snapshot.others)
    }
}
