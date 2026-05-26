package io.github.beyondwin.fixthis.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.beyondwin.fixthis.gradle.task.FixThisSetupTask
import io.github.beyondwin.fixthis.gradle.task.GenerateFixThisSourceIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File

class FixThisGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "fixthis",
            FixThisExtension::class.java,
        )

        project.plugins.withId("com.android.application") {
            configureAndroidApplication(project, extension)
        }
    }

    private fun configureAndroidApplication(
        project: Project,
        extension: FixThisExtension,
    ) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!variant.debuggable || !extension.enabled.get()) {
                return@onVariants
            }

            if (extension.addDebugRuntime.get()) {
                addRuntimeDependency(project, variant, extension)
            }

            registerSetupTask(project, variant)

            if (!extension.generateSourceIndex.get() && !extension.generateProjectMetadata.get()) {
                return@onVariants
            }

            val taskProvider = registerSourceIndexTask(project, extension, variant)

            variant.sources.assets?.addGeneratedSourceDirectory(
                taskProvider,
                GenerateFixThisSourceIndexTask::outputDirectory,
            )
        }
    }

    private fun registerSourceIndexTask(
        project: Project,
        extension: FixThisExtension,
        variant: ApplicationVariant,
    ) = project.tasks.register(
        "generate${variant.name.capitalized()}FixThisSourceIndex",
        GenerateFixThisSourceIndexTask::class.java,
    ) { task ->
        val sourceSetNames = activeSourceSetNames(
            variantName = variant.name,
            buildType = variant.buildType,
            productFlavorNames = variant.productFlavors.map { it.second },
            flavorName = variant.flavorName,
        )
        task.projectDirectory.set(project.layout.projectDirectory)
        task.rootProjectDirectory.set(project.rootProject.layout.projectDirectory)
        val additionalProjectDirectories = project.providers.provider {
            sourceIndexProjectDirectories(
                project = project,
                dependencyProjectPaths = projectDependencyPaths(
                    configurations = listOf(
                        variant.compileConfiguration,
                        variant.runtimeConfiguration,
                    ),
                    ownerProjectPath = project.path,
                ),
            )
        }
        task.kotlinSourceFiles.from(
            additionalProjectDirectories.map { directories ->
                indexedSourceSetRoots(
                    projectDirectory = project.projectDir,
                    additionalProjectDirectories = directories,
                    sourceSetNames = sourceSetNames,
                ).map { sourceRoot ->
                    project.files(sourceRoot)
                        .asFileTree
                        .matching { pattern ->
                            pattern.include("**/*.kt")
                            pattern.include("**/*.kts")
                        }
                }
            },
        )
        task.resourceXmlFiles.from(
            additionalProjectDirectories.map { directories ->
                indexedSourceSetRoots(
                    projectDirectory = project.projectDir,
                    additionalProjectDirectories = directories,
                    sourceSetNames = sourceSetNames,
                    childPath = "res",
                ).map { sourceRoot ->
                    project.files(sourceRoot)
                        .asFileTree
                        .matching { pattern ->
                            pattern.include("**/*.xml")
                        }
                }
            },
        )
        task.outputDirectory.set(project.layout.buildDirectory.dir("generated/fixthis/${variant.name}/assets"))
        task.projectPath.set(project.path)
        task.variantName.set(variant.name)
        task.runtimeVersion.set(extension.runtimeVersion)
        task.includeScreenshots.set(extension.includeScreenshots)
        task.redactEditableText.set(extension.redactEditableText)
        task.runtimeCompatibleSourceIndex.set(
            project.providers.gradleProperty(RuntimeCompatibleSourceIndexProperty)
                .map { value -> value.toBoolean() }
                .orElse(false),
        )
        task.generateSourceIndex.set(extension.generateSourceIndex)
        task.generateProjectMetadata.set(extension.generateProjectMetadata)
    }

    private fun registerSetupTask(
        project: Project,
        variant: ApplicationVariant,
    ) {
        project.tasks.register(
            fixThisSetupTaskName(variant.name),
            FixThisSetupTask::class.java,
        ) { task ->
            task.description = "Writes FixThis project metadata and agent setup next steps."
            task.group = "fixthis"
            task.rootProjectDirectory.set(project.rootProject.layout.projectDirectory)
            task.projectPath.set(project.path)
            task.variantName.set(variant.name)
            task.applicationId.set(variant.applicationId)
        }
    }

    private fun addRuntimeDependency(
        project: Project,
        variant: ApplicationVariant,
        extension: FixThisExtension,
    ) {
        val configurationName = "${variant.name}Implementation"
        val dependency = project.rootProject.findProject(":fixthis-compose-sidekick")
            ?.let { sidekickProject -> project.dependencies.project(mapOf("path" to sidekickProject.path)) }
            ?: fixThisSidekickCoordinate(extension.runtimeVersion.get())

        project.dependencies.add(configurationName, dependency)
    }

    private fun String.capitalized(): String = replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
}

internal fun projectDependencyPaths(
    configurations: List<Configuration>,
    ownerProjectPath: String,
): Set<String> {
    val paths = linkedSetOf<String>()
    configurations.forEach { configuration ->
        configuration.allDependencies
            .withType(ProjectDependency::class.java)
            .mapTo(paths) { dependency -> dependency.path }

        runCatching {
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                (component.id as? ProjectComponentIdentifier)?.let { identifier ->
                    paths += identifier.projectPath
                }
            }
        }
    }
    return paths.filterTo(linkedSetOf()) { path -> path != ownerProjectPath }
}

internal fun sourceIndexProjectDirectories(
    project: Project,
    dependencyProjectPaths: Set<String>,
): List<File> {
    val projectDirectory = project.projectDir.canonicalFile
    val rootProjectDirectory = project.rootProject.projectDir.canonicalFile
    return dependencyProjectPaths
        .asSequence()
        .filterNot { it == project.path }
        .filterNot { it == ":fixthis-compose-sidekick" }
        .mapNotNull { path -> project.rootProject.findProject(path) }
        .map { dependencyProject -> dependencyProject.projectDir.canonicalFile }
        .filterNot { directory -> directory == projectDirectory }
        .distinctBy { directory -> directory.absolutePath }
        .sortedBy { directory -> directory.relativeToOrSelf(rootProjectDirectory).invariantSeparatorsPath }
        .toList()
}

internal fun activeSourceSetNames(
    variantName: String,
    buildType: String?,
    productFlavorNames: List<String>,
    flavorName: String?,
): List<String> = buildList {
    add("main")
    buildType?.takeIf { it.isNotBlank() }?.let(::add)
    productFlavorNames.filterTo(this) { it.isNotBlank() }
    flavorName?.takeIf { it.isNotBlank() }?.let(::add)
    variantName.takeIf { it.isNotBlank() }?.let(::add)
}.distinct()

internal fun indexedSourceSetRoots(
    projectDirectory: File,
    additionalProjectDirectories: List<File>,
    sourceSetNames: List<String>,
    childPath: String = "",
): List<File> {
    val projectDirectories = (listOf(projectDirectory) + additionalProjectDirectories)
        .map { it.canonicalFile }
        .distinctBy { it.absolutePath }
    return projectDirectories.flatMap { candidateProject ->
        sourceSetNames.mapNotNull { sourceSetName ->
            val sourceRoot = candidateProject
                .resolve("src")
                .resolve(sourceSetName)
                .let { if (childPath.isBlank()) it else it.resolve(childPath) }
            sourceRoot.takeIf { it.isDirectory }?.canonicalFile
        }
    }
}

internal fun fixThisSetupTaskName(variantName: String): String = if (variantName == "debug") {
    "fixthisSetup"
} else {
    "fixthisSetup${variantName.capitalized()}"
}

internal fun fixThisSidekickCoordinate(runtimeVersion: String): String = "io.github.beyondwin:fixthis-compose-sidekick:$runtimeVersion"

internal const val RuntimeCompatibleSourceIndexProperty: String = "fixthis.runtimeCompatibleSourceIndex"

private fun String.capitalized(): String = replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase() else char.toString()
}
