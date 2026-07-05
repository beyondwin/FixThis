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
        assertNoForbiddenImports(
            paths = listOf("fixthis-compose-core/src/main"),
            forbidden = Regex("""^import (android|androidx|io\.github\.beyondwin\.fixthis\.(mcp|cli|gradle|compose\.sidekick))"""),
            boundaryName = "compose-core purity",
            guidance = "compose-core owns pure domain/source/target/format policies and must not depend on " +
                "Android, MCP, CLI, Gradle plugin, sidekick runtime, browser DTOs, or .fixthis paths. " +
                "Move adapter code outward or add a lower pure model instead.",
        )
    }

    @Test
    fun composeCoreTargetPoliciesDoNotImportMcpSessionDtos() {
        assertNoForbiddenImports(
            paths = listOf("fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target"),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.mcp\.session\."""),
            boundaryName = "target policy purity",
            guidance = "target reliability/evidence policy must stay pure. Map MCP session DTOs at the outer module boundary instead of importing them into compose-core.",
        )
    }

    @Test
    fun composeCoreDomainDoesNotImportContractModels() {
        assertNoForbiddenImports(
            paths = listOf(coreDomainSourceRoot),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.compose\.core\.model\."""),
            boundaryName = "domain model independence",
            guidance = "compose-core/domain contains domain IDs, entities, and ports. Keep contract/export models in compose-core/model and translate at explicit boundaries.",
        )
    }

    @Test
    fun sidekickGradlePluginAndSampleDoNotImportMcpOrCli() {
        assertNoForbiddenImports(
            paths = listOf(
                "fixthis-compose-sidekick/src/main",
                "fixthis-gradle-plugin/src/main",
                "sample/src/main",
            ),
            forbidden = Regex("""^import io\.github\.beyondwin\.fixthis\.(mcp|cli)"""),
            boundaryName = "outer module direction",
            guidance = "sidekick, Gradle plugin, and sample code must not depend on MCP or CLI internals. Share only through compose-core or explicit bridge/CLI contracts.",
        )
    }

    private fun assertNoForbiddenImports(
        paths: List<String>,
        forbidden: Regex,
        boundaryName: String,
        guidance: String,
    ) {
        val offenders = paths.flatMap(::kotlinFiles)
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line)) "${file.relativeTo(root)}:${index + 1}: $line" else null
                }
            }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Module boundary violated: $boundaryName.")
                appendLine(guidance)
                appendLine("Read docs/architecture/adr/0001-use-clean-architecture-layering.md and docs/guides/project-map.md.")
                appendLine()
                append(offenders.joinToString(separator = "\n"))
            },
        )
    }

    private fun kotlinFiles(path: String): List<File> = File(root, path)
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()
}
