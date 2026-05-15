package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.compose.core.source.SourceRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostSourcePathResolverTest {
    @Test
    fun `resolves repoFile before module file`() {
        val root = tempDir()
        root.resolve("sample/src/main/java/Sample.kt").writeSource("fun sample() {}")
        root.resolve("src/main/java/Sample.kt").writeSource("fun root() {}")
        val index = SourceIndex(
            sourceRoot = SourceRoot(gradlePath = ":app", projectDir = "sample"),
            entries = listOf(
                SourceIndexEntry(
                    file = "src/main/java/Sample.kt",
                    repoFile = "sample/src/main/java/Sample.kt",
                    line = 1,
                ),
            ),
        )

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("sample/src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.REPO_FILE, result.reason)
    }

    @Test
    fun `resolves sourceRoot plus module file when repoFile is absent`() {
        val root = tempDir()
        root.resolve("sample/src/main/java/Sample.kt").writeSource("fun sample() {}")
        val index = SourceIndex(
            sourceRoot = SourceRoot(gradlePath = ":app", projectDir = "sample"),
            entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)),
        )

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("sample/src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.SOURCE_ROOT, result.reason)
    }

    @Test
    fun `resolves legacy project root path when metadata is absent`() {
        val root = tempDir()
        root.resolve("src/main/java/Sample.kt").writeSource("fun root() {}")
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.LEGACY_ROOT, result.reason)
    }

    @Test
    fun `rejects path escape`() {
        val root = tempDir()
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "../Escape.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertFalse(result.found)
        assertTrue(result.failureReason!!.startsWith("path escapes project root"))
    }

    @Test
    fun `resolves unique suffix fallback`() {
        val root = tempDir()
        root.resolve("sample/src/main/java/Sample.kt").writeSource("fun sample() {}")
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertNotNull(result.file)
        assertEquals("sample/src/main/java/Sample.kt", result.displayPath)
        assertEquals(HostSourcePathResolutionReason.UNIQUE_SUFFIX, result.reason)
    }

    @Test
    fun `reports ambiguous suffix fallback`() {
        val root = tempDir()
        root.resolve("a/src/main/java/Sample.kt").writeSource("fun a() {}")
        root.resolve("b/src/main/java/Sample.kt").writeSource("fun b() {}")
        val index = SourceIndex(entries = listOf(SourceIndexEntry(file = "src/main/java/Sample.kt", line = 1)))

        val result = HostSourcePathResolver(root).resolve(index.entries.single(), index)

        assertFalse(result.found)
        assertEquals("file not found on host; multiple suffix matches", result.failureReason)
    }

    private fun tempDir(): File = kotlin.io.path.createTempDirectory(prefix = "fixthis-resolver-").toFile()
        .also { it.deleteOnExit() }

    private fun File.writeSource(text: String) {
        parentFile.mkdirs()
        writeText(text)
    }
}
