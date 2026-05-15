package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.compose.core.source.SourceRoot
import java.io.File
import kotlin.test.Test
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
    fun `marks module relative candidate fresh through source root metadata`() {
        val tmp = tempDir()
        val file = File(tmp, "sample/src/main/java/Sample.kt")
        file.parentFile.mkdirs()
        file.writeText("package x\n\nfun greet() = \"hi\"\n")
        val index = SourceIndex(
            sourceRoot = SourceRoot(gradlePath = ":app", projectDir = "sample"),
            entries = listOf(
                SourceIndexEntry(
                    file = "src/main/java/Sample.kt",
                    line = 3,
                    excerpt = "fun greet() = \"hi\"",
                ),
            ),
        )
        val candidate = candidate(file = "src/main/java/Sample.kt", line = 3)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(false, result.stale)
        assertNull(result.staleReason)
    }

    @Test
    fun `marks legacy module relative candidate fresh through unique suffix and fills repo file`() {
        val tmp = tempDir()
        val file = File(tmp, "sample/src/main/java/Sample.kt")
        file.parentFile.mkdirs()
        file.writeText("package x\n\nfun greet() = \"hi\"\n")
        val index = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "src/main/java/Sample.kt",
                    line = 3,
                    excerpt = "fun greet() = \"hi\"",
                ),
            ),
        )
        val candidate = candidate(file = "src/main/java/Sample.kt", line = 3)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(false, result.stale)
        assertNull(result.staleReason)
        assertEquals("sample/src/main/java/Sample.kt", result.repoFile)
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

    @Test
    fun `marks candidate stale when host file exceeds 1 MB cap`() {
        val tmp = tempDir()
        val big = File(tmp, "Big.kt")
        // exactly 1 MB + 1 byte to trip the > MaxBytesToRead branch
        big.writeText("a".repeat(1_048_577))
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Big.kt", line = 1, excerpt = "a")),
        )
        val candidate = candidate(file = "Big.kt", line = 1)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        assertEquals(true, result.stale)
        assertEquals("file too large to verify", result.staleReason)
    }

    @Test
    fun `does not flag size when host file is exactly at 1 MB cap`() {
        val tmp = tempDir()
        val cap = File(tmp, "Cap.kt")
        // exactly 1 MB — should NOT trip > MaxBytesToRead (strict greater-than)
        cap.writeText("a".repeat(1_048_576))
        val index = SourceIndex(
            entries = listOf(SourceIndexEntry(file = "Cap.kt", line = 1, excerpt = "a".repeat(1_048_576))),
        )
        val candidate = candidate(file = "Cap.kt", line = 1)
        val checker = SourceCandidateStalenessChecker(tmp)

        val result = checker.annotate(listOf(candidate), index).single()

        // file is fine size-wise; line 1 matches excerpt → fresh
        assertEquals(false, result.stale)
    }

    private fun candidate(file: String, line: Int?): SourceCandidate = SourceCandidate(
        file = file,
        line = line,
        score = 1.0,
        confidence = SelectionConfidence.HIGH,
    )

    private fun tempDir(prefix: String = "fixthis-staleness-"): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }
}
