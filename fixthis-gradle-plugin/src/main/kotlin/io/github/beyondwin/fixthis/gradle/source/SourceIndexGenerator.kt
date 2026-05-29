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
        val callSites = composableCallSites(
            sources = sortedKotlinFiles.map { file ->
                CallSiteSource(
                    path = file.canonicalFile.relativeSourcePath(projectDirectory, rootProjectDirectory),
                    content = file.readText(),
                )
            },
            definitionNames = definitionNames,
        )
        return SourceIndexAsset(
            sourceRoot = SourceRootAsset(
                gradlePath = projectPath,
                projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                    .let { if (it == ".") "" else it },
            ),
            entries = entries.map { it.withSharedComponentSignal(callSites) },
        )
    }

    private fun SourceIndexEntryAsset.withSharedComponentSignal(
        callSites: Map<String, List<ComposableCallSite>>,
    ): SourceIndexEntryAsset {
        val best = signals
            .filter { it.kind == SourceSignalKindAsset.COMPOSABLE_SYMBOL }
            .mapNotNull { symbol -> callSites[symbol.value]?.let { symbol.value to it } }
            .maxByOrNull { it.second.size }
        val sites = best?.second.orEmpty()
        if (sites.size < SHARED_COMPONENT_FANIN_THRESHOLD) return this
        val callSiteSignals = sites.take(SHARED_COMPONENT_CALLSITE_LIMIT).map { site ->
            SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT_CALL_SITE, "${site.file}:${site.line}")
        }
        return copy(
            signals = signals +
                SourceSignalAsset(SourceSignalKindAsset.SHARED_COMPONENT, sites.size.toString()) +
                callSiteSignals,
        )
    }
}
