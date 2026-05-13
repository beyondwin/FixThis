package io.beyondwin.fixthis.gradle.source

import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

internal class XmlStringResourceScanner(
    private val projectDirectory: File,
) {
    fun scan(file: File): List<SourceIndexEntryAsset> {
        val document = runCatching {
            newDocumentBuilderFactory().newDocumentBuilder().parse(file)
        }.getOrNull() ?: return emptyList()
        val lines = file.readLines()
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
                        file = file.relativeToOrSelf(projectDirectory).invariantSeparatorsPath,
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

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        isExpandEntityReferences = false
    }
}
