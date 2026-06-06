package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionPackageBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val session = "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session"

    private fun offenders(group: String, forbidden: Regex): List<String> = File(root, "$session/$group").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                if (forbidden.containsMatchIn(line)) "${f.relativeTo(root)}:${i + 1}: $line" else null
            }
        }.toList()

    @Test
    fun editsurfaceDoesNotImportStoreHandoffPreviewOrConnection() {
        val bad = offenders(
            "editsurface",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun handoffDoesNotImportStorePreviewOrConnection() {
        val bad = offenders(
            "handoff",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun previewDoesNotImportStoreHandoffOrTarget() {
        // Relaxed: lifecycle.store and target removed — see ADR-0008 Exceptions E1.
        val bad = offenders(
            "preview",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(handoff)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun targetDoesNotImportStoreHandoffPreviewOrConnection() {
        // Relaxed: handoff removed — see ADR-0008 Exceptions E2.
        val bad = offenders(
            "target",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun sourceDoesNotImportStoreHandoffPreviewOrTarget() {
        val bad = offenders(
            "source",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(lifecycle\.store|handoff|preview|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun lifecycleEventDoesNotImportHandoffPreviewOrConnection() {
        // Relaxed: handoff removed — see ADR-0008 Exceptions E3.
        val bad = offenders(
            "lifecycle/event",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(preview|connection)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun lifecycleStoreDoesNotImportHandoffConnectionOrTarget() {
        // Relaxed: handoff removed — see ADR-0008 Exceptions E4.
        val bad = offenders(
            "lifecycle/store",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(connection|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun draftDoesNotImportConnection() {
        val bad = offenders(
            "draft",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.connection\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun connectionDoesNotImportHandoffPreviewOrTarget() {
        val bad = offenders(
            "connection",
            Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.(handoff|preview|target)\."""),
        )
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }
}
