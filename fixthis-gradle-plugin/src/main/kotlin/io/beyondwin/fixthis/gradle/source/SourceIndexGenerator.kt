package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class SourceIndexGenerator(
    projectDirectory: File,
    rootProjectDirectory: File,
    private val projectPath: String,
    json: Json,
) {
    private val projectDirectory = projectDirectory.canonicalFile
    private val rootProjectDirectory = rootProjectDirectory.canonicalFile
    private val kotlinScanner = KotlinSourceScanner(projectDirectory, rootProjectDirectory, json)
    private val xmlScanner = XmlStringResourceScanner(projectDirectory, rootProjectDirectory)

    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset = SourceIndexAsset(
        sourceRoot = SourceRootAsset(
            gradlePath = projectPath,
            projectDir = projectDirectory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath
                .let { if (it == ".") "" else it },
        ),
        entries = buildList {
            kotlinFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(kotlinScanner.scan(it)) }
            xmlFiles
                .sortedBy { it.relativeToOrSelf(projectDirectory).invariantSeparatorsPath }
                .forEach { addAll(xmlScanner.scan(it)) }
        },
    )
}
