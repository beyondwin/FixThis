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
        return SourceIndexAsset(
            sourceRoot = SourceRootAsset(
                gradlePath = projectPath,
                projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                    .let { if (it == ".") "" else it },
            ),
            entries = buildList {
                kotlinFiles
                    .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                    .forEach { addAll(kotlinScanner.scan(it, stringResourceResolver)) }
                xmlFiles
                    .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                    .forEach { addAll(xmlScanner.scan(it)) }
            },
        )
    }
}
