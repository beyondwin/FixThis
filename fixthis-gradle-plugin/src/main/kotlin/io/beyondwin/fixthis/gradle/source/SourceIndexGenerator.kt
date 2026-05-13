package io.beyondwin.fixthis.gradle.source

import kotlinx.serialization.json.Json
import java.io.File

internal class SourceIndexGenerator(
    private val projectDirectory: File,
    json: Json,
) {
    private val kotlinScanner = KotlinSourceScanner(projectDirectory, json)
    private val xmlScanner = XmlStringResourceScanner(projectDirectory)

    fun generate(kotlinFiles: List<File>, xmlFiles: List<File>): SourceIndexAsset = SourceIndexAsset(
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
