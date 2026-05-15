package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DispatchArchitectureTest {
    private val sidekickHandlerPath =
        "fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/handlers"

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }

    @Test
    fun mcpToolHandlersDoNotUseCentralToolSwitches() {
        val offenders = kotlinFiles("fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/handlers")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (Regex("""when\s*\(\s*name\s*\)|"fixthis_[a-z_]+"\s*->""").containsMatchIn(line)) {
                        "${file.relativeTo(root)}:${index + 1}: $line"
                    } else {
                        null
                    }
                }
            }
        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun sidekickBridgeHandlersDoNotUseCentralMethodSwitches() {
        val offenders = kotlinFiles(sidekickHandlerPath).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val centralMethodSwitch = Regex(
                    """"(heartbeat|status|inspectCurrentScreen)"\s*->""",
                )
                if (Regex("""when\s*\(\s*method\s*\)""").containsMatchIn(line) ||
                    centralMethodSwitch.containsMatchIn(line)
                ) {
                    "${file.relativeTo(root)}:${index + 1}: $line"
                } else {
                    null
                }
            }
        }
        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun kotlinFiles(path: String): List<File> {
        val dir = File(root, path)
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
