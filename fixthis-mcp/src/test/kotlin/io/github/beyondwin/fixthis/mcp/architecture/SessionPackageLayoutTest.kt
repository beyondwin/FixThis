package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class SessionPackageLayoutTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val sessionRoot =
        File(root, "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session")
    private val allowedAtRoot = setOf("FeedbackSessionService.kt")

    @Test
    fun sessionRootHoldsOnlyTheFacade() {
        val strays = sessionRoot.listFiles { f -> f.isFile && f.extension == "kt" }
            .orEmpty()
            .map { it.name }
            .filterNot { it in allowedAtRoot }
            .sorted()
        assertTrue(strays.isEmpty(), "Unexpected files at session/ root:\n${strays.joinToString("\n")}")
    }
}
