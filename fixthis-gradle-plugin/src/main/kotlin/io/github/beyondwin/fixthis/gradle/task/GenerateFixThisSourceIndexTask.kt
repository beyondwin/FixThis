package io.github.beyondwin.fixthis.gradle.task

import io.github.beyondwin.fixthis.gradle.source.FixThisBuildInfoAsset
import io.github.beyondwin.fixthis.gradle.source.SourceIndexGenerator
import io.github.beyondwin.fixthis.gradle.source.TestTagConventionPatternValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class GenerateFixThisSourceIndexTask : DefaultTask() {
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:Internal
    abstract val rootProjectDirectory: DirectoryProperty

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
    abstract val runtimeCompatibleSourceIndex: Property<Boolean>

    @get:Input
    abstract val generateSourceIndex: Property<Boolean>

    @get:Input
    abstract val generateProjectMetadata: Property<Boolean>

    @get:Input
    abstract val testTagConventionPatterns: ListProperty<String>

    @TaskAction
    fun generate() {
        val outputRoot = outputDirectory.get().asFile
        if (outputRoot.exists()) {
            outputRoot.deleteRecursively()
        }
        val assetRoot = outputRoot.resolve("fixthis")
        assetRoot.mkdirs()

        if (generateSourceIndex.get()) {
            val requestedConventions = testTagConventionPatterns.get()
            val invalid = requestedConventions.filterNot {
                TestTagConventionPatternValidator.validate(it).isValid
            }
            require(invalid.isEmpty()) {
                "FixThis: invalid testTagConventionPatterns $invalid; each must be anchored (^...\$), <=200 chars, " +
                    "have >=2 capture groups, and avoid backtracking-prone (nested/adjacent unbounded) quantifiers"
            }
            val generator = SourceIndexGenerator(
                projectDirectory = projectDirectory.get().asFile,
                rootProjectDirectory = rootProjectDirectory.get().asFile,
                projectPath = projectPath.get(),
                json = json,
                includeLayoutRendererSignals = runtimeCompatibleSourceIndex.orNull != true,
                conventionPatterns = requestedConventions,
            )
            val entries = generator.generate(
                kotlinFiles = kotlinSourceFiles.files.flatMap { it.kotlinFiles() },
                xmlFiles = resourceXmlFiles.files.flatMap { it.xmlFiles() },
            )
            assetRoot.resolve("fixthis-source-index.json").writeText(
                json.encodeToString(entries),
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

    private companion object {
        val json: Json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}
