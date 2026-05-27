package io.github.beyondwin.fixthis.mcp.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ModuleBoundaryTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile || File(it, "settings.gradle").isFile }
    private val coreDomainSourceRoot =
        "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain"

    @Test
    fun composeCoreDoesNotImportOuterModulesOrAndroid() {
        val forbidden = Regex(
            """^import (android|androidx|io\.github\.beyondwin\.fixthis\.(mcp|cli|gradle|compose\.sidekick))""",
        )
        val offenders = kotlinFiles("fixthis-compose-core/src/main")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun composeCoreTargetPoliciesDoNotImportMcpSessionDtos() {
        val forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\.""")
        val offenders = kotlinFiles("fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target")
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun composeCoreDomainDoesNotImportContractModels() {
        val forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.compose\.core\.model\.""")
        val offenders = kotlinFiles(coreDomainSourceRoot)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    @Test
    fun sidekickGradlePluginAndSampleDoNotImportMcpOrCli() {
        val forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.(mcp|cli)""")
        val offenders = listOf(
            "fixthis-compose-sidekick/src/main",
            "fixthis-gradle-plugin/src/main",
            "sample/src/main",
        ).flatMap(::kotlinFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(offenders.isEmpty(), offenders.joinToString(separator = "\n"))
    }

    private fun kotlinFiles(path: String): List<File> = File(root, path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}
