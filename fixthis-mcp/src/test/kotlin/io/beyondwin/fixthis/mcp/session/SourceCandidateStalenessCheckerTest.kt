package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCandidateStalenessCheckerTest {

    @Test
    fun `marks candidate as fresh when excerpt matches host source`() {
        val tmp = tempDir()
        val file = File(tmp, "Sample.kt")
        file.writeText("package x\n\nfun greet() = \"hi\"\n")
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Sample.kt", line = 3, excerpt = "fun greet() = \"hi\"")),
        )
        val candidate = candidate(file = "Sample.kt", line = 3)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(false, result.stale)
        assertNull(result.staleReason)
    }

    @Test
    fun `marks candidate stale when excerpt does not match`() {
        val tmp = tempDir()
        val file = File(tmp, "Sample.kt")
        file.writeText("package x\n\nfun greet() = \"bye\"\n")
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Sample.kt", line = 3, excerpt = "fun greet() = \"hi\"")),
        )
        val candidate = candidate(file = "Sample.kt", line = 3)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(true, result.stale)
        assertEquals("excerpt mismatch", result.staleReason)
    }

    @Test
    fun `marks candidate stale when host file is missing`() {
        val tmp = tempDir()
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Missing.kt", line = 1, excerpt = "fun foo() {}")),
        )
        val candidate = candidate(file = "Missing.kt", line = 1)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(true, result.stale)
        assertEquals("file not found on host", result.staleReason)
    }

    @Test
    fun `marks candidate stale when line is out of range`() {
        val tmp = tempDir()
        val file = File(tmp, "Tiny.kt")
        file.writeText("package x\n")
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Tiny.kt", line = 99, excerpt = "fun foo() {}")),
        )
        val candidate = candidate(file = "Tiny.kt", line = 99)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(true, result.stale)
        assertEquals("line out of range", result.staleReason)
    }

    @Test
    fun `leaves stale null when entry has no excerpt or candidate has no line`() {
        val tmp = tempDir()
        val file = File(tmp, "Strings.xml")
        file.writeText("<resources><string name=\"x\">v</string></resources>")
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Strings.xml", line = null, excerpt = null)),
        )
        val candidate = candidate(file = "Strings.xml", line = null)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertNull(result.stale)
        assertNull(result.staleReason)
    }

    @Test
    fun `rejects paths that escape project root`() {
        val tmp = tempDir()
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "../escape.kt", line = 1, excerpt = "boom")),
        )
        val candidate = candidate(file = "../escape.kt", line = 1)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(true, result.stale)
        assertTrue(result.staleReason!!.startsWith("path escapes project root"))
    }

    private fun candidate(file: String, line: Int?): SourceCandidate =
        SourceCandidate(
            file = file,
            line = line,
            score = 1.0,
            confidence = SelectionConfidence.HIGH,
        )

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory(prefix = "fixthis-staleness-").toFile().also { it.deleteOnExit() }
}
