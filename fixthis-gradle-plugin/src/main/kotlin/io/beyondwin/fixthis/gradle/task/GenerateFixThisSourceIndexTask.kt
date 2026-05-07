package io.beyondwin.fixthis.gradle.task

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element

abstract class GenerateFixThisSourceIndexTask : DefaultTask() {
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinSourceFiles: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceXmlFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:Input
    abstract val runtimeVersion: Property<String>

    @get:Input
    abstract val includeScreenshots: Property<Boolean>

    @get:Input
    abstract val redactEditableText: Property<Boolean>

    @get:Input
    abstract val generateSourceIndex: Property<Boolean>

    @get:Input
    abstract val generateProjectMetadata: Property<Boolean>

    @TaskAction
    fun generate() {
        val assetRoot = outputDirectory.get().asFile.resolve("fixthis")
        assetRoot.mkdirs()

        if (generateSourceIndex.get()) {
            val entries = buildList {
                kotlinSourceFiles.files
                    .flatMap { it.kotlinFiles() }
                    .sortedBy { it.relativePath() }
                    .forEach { addAll(scanKotlinFile(it)) }
                resourceXmlFiles.files
                    .flatMap { it.xmlFiles() }
                    .sortedBy { it.relativePath() }
                    .forEach { addAll(scanXmlStringResources(it)) }
            }
            assetRoot.resolve("fixthis-source-index.json").writeText(
                json.encodeToString(SourceIndexAsset(entries = entries)),
            )
        }

        if (generateProjectMetadata.get()) {
            assetRoot.resolve("fixthis-build-info.json").writeText(
                json.encodeToString(
                    FixThisBuildInfoAsset(
                        projectPath = projectPath.get(),
                        variantName = variantName.get(),
                        runtimeVersion = runtimeVersion.get(),
                        includeScreenshots = includeScreenshots.get(),
                        redactEditableText = redactEditableText.get(),
                    ),
                ),
            )
        }
    }

    private fun scanKotlinFile(file: File): List<SourceIndexEntryAsset> {
        val source = file.readText()
        val lines = source.lineSequence().toList()
        val lineStartOffsets = source.lineStartOffsets()
        val entriesByLine = linkedMapOf<Int, SourceIndexEntryBuilder>()
        var pendingComposable = false

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            if (line.contains("@Composable")) {
                pendingComposable = true
            }

            val functionMatch = functionRegex.find(line)
            if (functionMatch != null) {
                if (pendingComposable || line.contains("@Composable")) {
                    entriesByLine.entryFor(file, lineNumber, line)
                        .symbols += functionMatch.groupValues[1]
                }
                pendingComposable = false
            }
        }

        quotedStringRegex.findAll(source).forEach { match ->
            entriesByLine.entryFor(file, match.startLine(lineStartOffsets), lines)
                .text += decodeKotlinString(match)
        }
        textCallRegex.findAll(source).forEach { match ->
            entriesByLine.entryFor(file, match.startLine(lineStartOffsets), lines)
                .text += decodeKotlinString(match)
        }
        stringResourceRegex.findAll(source).forEach { match ->
            entriesByLine.entryFor(file, match.startLine(lineStartOffsets), lines)
                .stringResources += match.groupValues[1]
        }
        testTagRegex.findAll(source).forEach { match ->
            entriesByLine.entryFor(file, match.startLine(lineStartOffsets), lines)
                .testTags += decodeKotlinString(match)
        }
        contentDescriptionRegex.findAll(source).forEach { match ->
            entriesByLine.entryFor(file, match.startLine(lineStartOffsets), lines)
                .contentDescriptions += decodeKotlinString(match)
        }

        return entriesByLine.values.map { it.toAsset() }
    }

    private fun scanXmlStringResources(file: File): List<SourceIndexEntryAsset> {
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
                        file = file.relativePath(),
                        line = lineIndex?.plus(1),
                        text = listOf(value),
                        stringResources = listOf(name),
                        excerpt = lineIndex?.let { lines[it].trim() } ?: "<string name=\"$name\">",
                    ),
                )
            }
        }
    }

    private fun decodeKotlinString(match: MatchResult): String {
        val singleLineLiteral = match.groups[2]?.value
        if (singleLineLiteral != null) {
            return runCatching { json.decodeFromString<String>("\"$singleLineLiteral\"") }
                .getOrElse { singleLineLiteral }
        }
        return match.groups[1]?.value.orEmpty()
    }

    private fun File.relativePath(): String =
        relativeToOrSelf(projectDirectory.get().asFile).invariantSeparatorsPath

    private fun File.kotlinFiles(): List<File> =
        matchingFiles("kt", "kts")

    private fun File.xmlFiles(): List<File> =
        matchingFiles("xml")

    private fun File.matchingFiles(vararg extensions: String): List<File> {
        val extensionSet = extensions.toSet()
        return when {
            isFile && extension in extensionSet -> listOf(this)
            isDirectory -> walkTopDown()
                .filter { it.isFile && it.extension in extensionSet }
                .toList()
            else -> emptyList()
        }
    }

    private fun MutableMap<Int, SourceIndexEntryBuilder>.entryFor(
        file: File,
        lineNumber: Int,
        line: String,
    ): SourceIndexEntryBuilder =
        getOrPut(lineNumber) {
            SourceIndexEntryBuilder(
                file = file.relativePath(),
                line = lineNumber,
                excerpt = line.trim(),
            )
        }

    private fun MutableMap<Int, SourceIndexEntryBuilder>.entryFor(
        file: File,
        lineNumber: Int,
        lines: List<String>,
    ): SourceIndexEntryBuilder =
        entryFor(
            file = file,
            lineNumber = lineNumber,
            line = lines.getOrNull(lineNumber - 1).orEmpty(),
        )

    private fun String.lineStartOffsets(): IntArray {
        val offsets = mutableListOf(0)
        forEachIndexed { index, char ->
            if (char == '\n' && index + 1 < length) {
                offsets += index + 1
            }
        }
        return offsets.toIntArray()
    }

    private fun MatchResult.startLine(lineStartOffsets: IntArray): Int {
        val offset = range.first
        val insertionPoint = lineStartOffsets.binarySearch(offset)
        return if (insertionPoint >= 0) {
            insertionPoint + 1
        } else {
            -insertionPoint - 1
        }
    }

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }

    private companion object {
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        val quotedStringRegex = Regex("\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\"")
        val textCallRegex = Regex("\\bText\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val stringResourceRegex = Regex("\\bstringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
        val testTagRegex = Regex("\\btestTag\\s*\\(\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val contentDescriptionRegex =
            Regex("\\bcontentDescription\\s*=\\s*(?:\"\"\"([\\s\\S]*?)\"\"\"|\"((?:\\\\.|[^\"\\\\])*)\")")
        val functionRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    }
}

private data class SourceIndexEntryBuilder(
    val file: String,
    val line: Int,
    val symbols: LinkedHashSet<String> = linkedSetOf(),
    val text: LinkedHashSet<String> = linkedSetOf(),
    val contentDescriptions: LinkedHashSet<String> = linkedSetOf(),
    val testTags: LinkedHashSet<String> = linkedSetOf(),
    val stringResources: LinkedHashSet<String> = linkedSetOf(),
    val excerpt: String,
) {
    fun toAsset(): SourceIndexEntryAsset =
        SourceIndexEntryAsset(
            file = file,
            line = line,
            symbols = symbols.toList(),
            text = text.toList(),
            contentDescriptions = contentDescriptions.toList(),
            testTags = testTags.toList(),
            stringResources = stringResources.toList(),
            excerpt = excerpt,
        )
}

@Serializable
private data class SourceIndexAsset(
    val schemaVersion: String = "1.0",
    val entries: List<SourceIndexEntryAsset> = emptyList(),
)

@Serializable
private data class SourceIndexEntryAsset(
    val file: String,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null,
)

@Serializable
private data class FixThisBuildInfoAsset(
    val schemaVersion: String = "1.0",
    val projectPath: String,
    val variantName: String,
    val runtimeVersion: String,
    val sourceIndexAsset: String = "fixthis/fixthis-source-index.json",
    val buildInfoAsset: String = "fixthis/fixthis-build-info.json",
    val includeScreenshots: Boolean,
    val redactEditableText: Boolean,
)
