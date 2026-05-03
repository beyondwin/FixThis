package io.github.pointpatch.gradle.task

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

abstract class GeneratePointPatchSourceIndexTask : DefaultTask() {
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
        val assetRoot = outputDirectory.get().asFile.resolve("pointpatch")
        assetRoot.mkdirs()

        if (generateSourceIndex.get()) {
            val entries = buildList {
                kotlinSourceFiles.files
                    .filter { it.isFile }
                    .sortedBy { it.relativePath() }
                    .forEach { addAll(scanKotlinFile(it)) }
                resourceXmlFiles.files
                    .filter { it.isFile }
                    .sortedBy { it.relativePath() }
                    .forEach { addAll(scanXmlStringResources(it)) }
            }
            assetRoot.resolve("pointpatch-source-index.json").writeText(
                json.encodeToString(SourceIndexAsset(entries = entries)),
            )
        }

        if (generateProjectMetadata.get()) {
            assetRoot.resolve("pointpatch-build-info.json").writeText(
                json.encodeToString(
                    PointPatchBuildInfoAsset(
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
        val lines = file.readLines()
        val entries = mutableListOf<SourceIndexEntryAsset>()
        var pendingComposable = false

        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val text = linkedSetOf<String>()
            val stringResources = linkedSetOf<String>()
            val testTags = linkedSetOf<String>()
            val symbols = linkedSetOf<String>()
            val contentDescriptions = linkedSetOf<String>()

            quotedStringRegex.findAll(line).forEach { match ->
                text += decodeKotlinString(match)
            }
            textCallRegex.findAll(line).forEach { match ->
                text += decodeKotlinString(match)
            }
            stringResourceRegex.findAll(line).forEach { match ->
                stringResources += match.groupValues[1]
            }
            testTagRegex.findAll(line).forEach { match ->
                testTags += decodeKotlinString(match)
            }
            contentDescriptionRegex.findAll(line).forEach { match ->
                contentDescriptions += decodeKotlinString(match)
            }

            if (line.contains("@Composable")) {
                pendingComposable = true
            }

            val functionMatch = functionRegex.find(line)
            if (functionMatch != null) {
                if (pendingComposable || line.contains("@Composable")) {
                    symbols += functionMatch.groupValues[1]
                }
                pendingComposable = false
            }

            if (
                text.isNotEmpty() ||
                stringResources.isNotEmpty() ||
                testTags.isNotEmpty() ||
                symbols.isNotEmpty() ||
                contentDescriptions.isNotEmpty()
            ) {
                entries += SourceIndexEntryAsset(
                    file = file.relativePath(),
                    line = lineNumber,
                    symbols = symbols.toList(),
                    text = text.toList(),
                    contentDescriptions = contentDescriptions.toList(),
                    testTags = testTags.toList(),
                    stringResources = stringResources.toList(),
                    excerpt = line.trim(),
                )
            }
        }

        return entries
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
        val singleLineLiteral = match.groups[1]?.value
        if (singleLineLiteral != null) {
            return runCatching { json.decodeFromString<String>("\"$singleLineLiteral\"") }
                .getOrElse { singleLineLiteral }
        }
        return match.groups[2]?.value.orEmpty()
    }

    private fun File.relativePath(): String =
        relativeToOrSelf(projectDirectory.get().asFile).invariantSeparatorsPath

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

        val quotedStringRegex = Regex("\"((?:\\\\.|[^\"\\\\])*)\"|\"\"\"([\\s\\S]*?)\"\"\"")
        val textCallRegex = Regex("\\bText\\s*\\(\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|\"\"\"([\\s\\S]*?)\"\"\")")
        val stringResourceRegex = Regex("\\bstringResource\\s*\\(\\s*R\\.string\\.([A-Za-z0-9_]+)")
        val testTagRegex = Regex("\\btestTag\\s*\\(\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|\"\"\"([\\s\\S]*?)\"\"\")")
        val contentDescriptionRegex =
            Regex("\\bcontentDescription\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|\"\"\"([\\s\\S]*?)\"\"\")")
        val functionRegex = Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    }
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
private data class PointPatchBuildInfoAsset(
    val schemaVersion: String = "1.0",
    val projectPath: String,
    val variantName: String,
    val runtimeVersion: String,
    val sourceIndexAsset: String = "pointpatch/pointpatch-source-index.json",
    val buildInfoAsset: String = "pointpatch/pointpatch-build-info.json",
    val includeScreenshots: Boolean,
    val redactEditableText: Boolean,
)
