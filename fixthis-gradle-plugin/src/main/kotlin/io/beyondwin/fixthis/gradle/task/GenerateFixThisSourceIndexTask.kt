package io.beyondwin.fixthis.gradle.task

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
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

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
        val packageName = packageRegex.find(source)?.groupValues?.get(1)
        val classDeclarations = classRegex.findAll(source)
            .map { match -> match.range.first to match.groupValues[2] }
            .toList()
        val recognizedStringRanges = recognizedUiStringRanges(source)
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
                    val symbol = functionMatch.groupValues[1]
                    entriesByLine.entryFor(
                        file = file,
                        lineNumber = lineNumber,
                        line = line,
                        packageName = packageName,
                        className = classNameAt(lineStartOffsets.getOrElse(index) { 0 }, classDeclarations),
                    ).apply {
                        symbols += symbol
                        addSignal(SourceSignalKindAsset.COMPOSABLE_SYMBOL, symbol)
                    }
                }
                pendingComposable = false
            }
        }

        quotedStringRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    text += value
                    if (recognizedStringRanges.none { it.contains(match.range) }) {
                        addSignal(SourceSignalKindAsset.ARBITRARY_STRING_LITERAL, value)
                    }
                }
        }
        textCallRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    text += value
                    addSignal(SourceSignalKindAsset.UI_TEXT, value)
                }
        }
        stringResourceRegex.findAll(source).forEach { match ->
            val resourceName = match.groupValues[1]
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    stringResources += resourceName
                    addSignal(SourceSignalKindAsset.STRING_RESOURCE, resourceName)
                }
        }
        testTagRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    testTags += value
                    if (value.isStrictCompTestTag()) {
                        addSignal(SourceSignalKindAsset.STRICT_COMP_TEST_TAG, value)
                    }
                    addSignal(SourceSignalKindAsset.TEST_TAG, value)
                }
        }
        contentDescriptionRegex.findAll(source).forEach { match ->
            val value = decodeKotlinString(match)
            entriesByLine.entryFor(
                file = file,
                lineNumber = match.startLine(lineStartOffsets),
                lines = lines,
                packageName = packageName,
                className = classNameAt(match.range.first, classDeclarations),
            )
                .apply {
                    contentDescriptions += value
                    addSignal(SourceSignalKindAsset.CONTENT_DESCRIPTION, value)
                }
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

    private fun decodeKotlinString(match: MatchResult): String {
        val singleLineLiteral = match.groups[2]?.value
        if (singleLineLiteral != null) {
            return runCatching { json.decodeFromString<String>("\"$singleLineLiteral\"") }
                .getOrElse { singleLineLiteral }
        }
        return match.groups[1]?.value.orEmpty()
    }

    private fun File.relativePath(): String = relativeToOrSelf(projectDirectory.get().asFile).invariantSeparatorsPath

    private fun File.kotlinFiles(): List<File> = matchingFiles("kt", "kts")

    private fun File.xmlFiles(): List<File> = matchingFiles("xml")

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
        packageName: String? = null,
        className: String? = null,
    ): SourceIndexEntryBuilder = getOrPut(lineNumber) {
        SourceIndexEntryBuilder(
            file = file.relativePath(),
            line = lineNumber,
            excerpt = line.trim(),
            packageName = packageName,
            className = className,
        )
    }

    private fun MutableMap<Int, SourceIndexEntryBuilder>.entryFor(
        file: File,
        lineNumber: Int,
        lines: List<String>,
        packageName: String? = null,
        className: String? = null,
    ): SourceIndexEntryBuilder = entryFor(
        file = file,
        lineNumber = lineNumber,
        line = lines.getOrNull(lineNumber - 1).orEmpty(),
        packageName = packageName,
        className = className,
    )

    private fun classNameAt(offset: Int, classDeclarations: List<Pair<Int, String>>): String? = classDeclarations.lastOrNull { (classOffset, _) -> classOffset <= offset }?.second

    private fun recognizedUiStringRanges(source: String): List<IntRange> = (textCallRegex.findAll(source) + testTagRegex.findAll(source) + contentDescriptionRegex.findAll(source))
        .map { it.range }
        .toList()

    private fun IntRange.contains(other: IntRange): Boolean = first <= other.first && last >= other.last

    private fun String.isStrictCompTestTag(): Boolean = strictCompTestTagRegex.matches(this)

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

    private fun newDocumentBuilderFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
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
        val packageRegex = Regex("\\bpackage\\s+([A-Za-z_][A-Za-z0-9_.]*)")
        val classRegex = Regex("\\b(class|object|interface)\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val strictCompTestTagRegex = Regex("""comp:[A-Za-z_][A-Za-z0-9_]*:.+""")
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
    val signals: LinkedHashSet<SourceSignalAsset> = linkedSetOf(),
    val excerpt: String,
    val packageName: String? = null,
    val className: String? = null,
) {
    fun addSignal(kind: SourceSignalKindAsset, value: String) {
        if (value.isNotBlank()) {
            signals += SourceSignalAsset(kind = kind, value = value)
        }
    }

    fun toAsset(): SourceIndexEntryAsset = SourceIndexEntryAsset(
        file = file,
        line = line,
        symbols = symbols.toList(),
        text = text.toList(),
        contentDescriptions = contentDescriptions.toList(),
        testTags = testTags.toList(),
        stringResources = stringResources.toList(),
        signals = signals.toList(),
        excerpt = excerpt,
        packageName = packageName,
        className = className,
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
    val signals: List<SourceSignalAsset> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)

@Serializable
private data class SourceSignalAsset(
    val kind: SourceSignalKindAsset,
    val value: String,
    val confidenceWeight: Double = 1.0,
)

@Serializable
private enum class SourceSignalKindAsset {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL,
}

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
