package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostSourceFreshnessProbeTest {

    @Test
    fun `flags newer files compared to install epoch`() {
        val tmp = tempDir()
        val installed = 1_700_000_000_000L
        val newer = File(tmp, "New.kt").also { it.writeText("a") }
        newer.setLastModified(installed + 60_000)
        val older = File(tmp, "Old.kt").also { it.writeText("b") }
        older.setLastModified(installed - 60_000)
        val index = SourceIndex(entries = listOf(
            SourceIndexEntry(file = "New.kt", line = 1, excerpt = "a"),
            SourceIndexEntry(file = "Old.kt", line = 1, excerpt = "b"),
        ))
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertTrue(result.installStale)
        assertEquals(1, result.newerFileCount)
        assertEquals(2, result.totalIndexedFiles)
        assertEquals(listOf("New.kt"), result.sampleNewerFiles)
    }

    @Test
    fun `not stale when no file is newer`() {
        val tmp = tempDir()
        val installed = 1_700_000_000_000L
        val file = File(tmp, "X.kt").also { it.writeText("x") }
        file.setLastModified(installed - 60_000)
        val index = SourceIndex(entries = listOf(
            SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
        ))
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertFalse(result.installStale)
        assertEquals(0, result.newerFileCount)
    }

    @Test
    fun `inconclusive when install epoch is null`() {
        val tmp = tempDir()
        val index = SourceIndex(entries = listOf(
            SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
        ))
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = null)

        assertFalse(result.installStale)
        assertEquals("install epoch unavailable; older sidekick", result.reason)
    }

    @Test
    fun `deduplicates by entry file path`() {
        val tmp = tempDir()
        val installed = 1_700_000_000_000L
        val file = File(tmp, "Dup.kt").also { it.writeText("a") }
        file.setLastModified(installed + 60_000)
        val index = SourceIndex(entries = listOf(
            SourceIndexEntry(file = "Dup.kt", line = 1, excerpt = "a"),
            SourceIndexEntry(file = "Dup.kt", line = 5, excerpt = "b"),
        ))
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertEquals(1, result.newerFileCount)
        assertEquals(1, result.totalIndexedFiles)
    }

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory(prefix = "fixthis-freshness-").toFile().also { it.deleteOnExit() }
}
