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
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "New.kt", line = 1, excerpt = "a"),
                SourceIndexEntry(file = "Old.kt", line = 1, excerpt = "b"),
            ),
        )
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
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
            ),
        )
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertFalse(result.installStale)
        assertEquals(0, result.newerFileCount)
    }

    @Test
    fun `inconclusive when install epoch is null`() {
        val tmp = tempDir()
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "X.kt", line = 1, excerpt = "x"),
            ),
        )
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
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "Dup.kt", line = 1, excerpt = "a"),
                SourceIndexEntry(file = "Dup.kt", line = 5, excerpt = "b"),
            ),
        )
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertEquals(1, result.newerFileCount)
        assertEquals(1, result.totalIndexedFiles)
    }

    @Test
    fun `flags possible projectRoot misconfiguration when zero indexed files exist on host`() {
        val tmp = tempDir()
        // tmp는 비어있음 — 인덱스가 가리키는 파일은 하나도 존재하지 않는다.
        val installed = 1_700_000_000_000L
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "missing/A.kt", line = 1, excerpt = "a"),
                SourceIndexEntry(file = "missing/B.kt", line = 1, excerpt = "b"),
                SourceIndexEntry(file = "missing/C.kt", line = 1, excerpt = "c"),
            ),
        )
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertFalse(result.installStale)
        assertEquals(0, result.newerFileCount)
        assertEquals(3, result.totalIndexedFiles)
        assertTrue(
            result.reason!!.startsWith("projectRoot may be misconfigured"),
            "got: ${result.reason}",
        )
    }

    @Test
    fun `does not flag misconfiguration when at least one indexed file exists`() {
        val tmp = tempDir()
        val installed = 1_700_000_000_000L
        // 한 파일만 존재 — 부분 dirty라 misconfig 아님
        val one = File(tmp, "Exists.kt").also { it.writeText("a") }
        one.setLastModified(installed - 60_000)
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(file = "Exists.kt", line = 1, excerpt = "a"),
                SourceIndexEntry(file = "Missing.kt", line = 1, excerpt = "b"),
            ),
        )
        val probe = HostSourceFreshnessProbe(tmp)

        val result = probe.evaluate(index, installEpochMillis = installed)

        assertFalse(result.installStale)
        assertFalse(result.reason?.startsWith("projectRoot may be misconfigured") == true)
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory(prefix = "fixthis-freshness-").toFile().also { it.deleteOnExit() }
}
