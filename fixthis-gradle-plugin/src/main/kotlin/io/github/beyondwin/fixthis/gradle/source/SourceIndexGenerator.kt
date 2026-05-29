package io.github.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class SourceIndexGenerator(
    projectDirectory: File,
    rootProjectDirectory: File,
    private val projectPath: String,
    json: Json,
    includeLayoutRendererSignals: Boolean = true,
) {
    private val projectDirectory = projectDirectory.canonicalFile
    private val rootProjectDirectory = rootProjectDirectory.canonicalFile
    private val kotlinScanner = KotlinSourceScanner(
        projectDirectory,
        rootProjectDirectory,
        json,
        includeLayoutRendererSignals = includeLayoutRendererSignals,
    )
    private val xmlScanner = XmlStringResourceScanner(projectDirectory, rootProjectDirectory)

    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset {
        val stringResourceResolver = xmlScanner.resolveDefaults(xmlFiles)
        val sortedKotlinFiles = kotlinFiles
            .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
        val entries = buildList {
            sortedKotlinFiles.forEach { addAll(kotlinScanner.scan(it, stringResourceResolver)) }
            xmlFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(xmlScanner.scan(it)) }
        }
        val definitionNames = entries
            .flatMap { entry -> entry.signals }
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .map { it.value }
            .toSet()
        val callSiteCounts = composableCallSiteCounts(
            sources = sortedKotlinFiles.map { it.readText() },
            definitionNames = definitionNames,
        )
        return SourceIndexAsset(
            sourceRoot = SourceRootAsset(
                gradlePath = projectPath,
                projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                    .let { if (it == ".") "" else it },
            ),
            entries = entries.map { it.withSharedComponentSignal(callSiteCounts) },
        )
    }

    private fun SourceIndexEntryAsset.withSharedComponentSignal(
        callSiteCounts: Map<String, Int>,
    ): SourceIndexEntryAsset {
        val maxFanIn = signals
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .mapNotNull { callSiteCounts[it.value] }
            .maxOrNull()
        return if (maxFanIn != null && maxFanIn >= SHARED_COMPONENT_FANIN_THRESHOLD) {
            copy(signals = signals + SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT, maxFanIn.toString()))
        } else {
            this
        }
    }
}
