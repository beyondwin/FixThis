package io.github.beyondwin.fixthis.gradle.source

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class XmlStringResourceScanner(
    projectDirectory: File,
    rootProjectDirectory: File,
) {
    private val projectDirectory = projectDirectory.canonicalFile
    private val rootProjectDirectory = rootProjectDirectory.canonicalFile

    fun scan(file: File): List<SourceIndexEntryAsset> {
        val sourceFile = file.canonicalFile
        val document = runCatching {
            newDocumentBuilderFactory().newDocumentBuilder().parse(sourceFile)
        }.getOrNull() ?: return emptyList()
        val lines = sourceFile.readLines()
        val strings = document.getElementsByTagName("string")

        return buildList {
            for (index in 0 until strings.length) {
                val element = strings.item(index) as? Element ?: continue
                val name = element.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                val value = element.textContent?.trim().orEmpty()
                if (value.isEmpty()) continue
                val lineIndex = lines.indexOfFirst { line ->
                    line.contains("<string") && line.contains("name=\"$name\"")
                }.takeIf { it >= 0 }
                add(
                    SourceIndexEntryAsset(
                        file = sourceFile.relativeSourcePath(projectDirectory, rootProjectDirectory),
                        repoFile = sourceFile.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath,
                        line = lineIndex?.plus(1),
                        text = listOf(value),
                        stringResources = listOf(name),
                        signals = listOf(
                            SourceSignalAsset(SourceSignalKindAsset.UI_TEXT, value),
                            SourceSignalAsset(SourceSignalKindAsset.STRING_RESOURCE, name),
                        ),
                        excerpt = lineIndex?.let { lines[it].trim() } ?: "<string name=\"$name\">",
                    ),
                )
            }
        }
    }

    fun resolveDefaults(files: List<File>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (file in files) {
            val sourceFile = file.canonicalFile
            if (sourceFile.parentFile?.name != "values") continue
            val document = runCatching {
                newDocumentBuilderFactory().newDocumentBuilder().parse(sourceFile)
            }.getOrNull() ?: continue
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val element = strings.item(index) as? Element ?: continue
                val name = element.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                val value = element.textContent?.trim().orEmpty()
                if (value.isEmpty()) continue
                out.putIfAbsent(name, value)
            }
        }
        return out
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
}
