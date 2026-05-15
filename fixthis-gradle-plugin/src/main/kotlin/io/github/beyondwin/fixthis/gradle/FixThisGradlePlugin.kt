package io.github.beyondwin.fixthis.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.beyondwin.fixthis.gradle.task.FixThisSetupTask
import io.github.beyondwin.fixthis.gradle.task.GenerateFixThisSourceIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project

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
        task.kotlinSourceFiles.from(
            sourceSetNames.map { sourceSetName ->
                project.layout.projectDirectory.dir("src/$sourceSetName")
                    .asFileTree
                    .matching { pattern ->
                        pattern.include("**/*.kt")
                        pattern.include("**/*.kts")
                    }
            },
        )
        task.resourceXmlFiles.from(
            sourceSetNames.map { sourceSetName ->
                project.layout.projectDirectory.dir("src/$sourceSetName/res")
                    .asFileTree
                    .matching { pattern ->
                        pattern.include("**/*.xml")
                    }
            },
        )
        task.outputDirectory.set(project.layout.buildDirectory.dir("generated/fixthis/${variant.name}/assets"))
        task.projectPath.set(project.path)
        task.variantName.set(variant.name)
        task.runtimeVersion.set(extension.runtimeVersion)
        task.includeScreenshots.set(extension.includeScreenshots)
        task.redactEditableText.set(extension.redactEditableText)
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

internal fun fixThisSetupTaskName(variantName: String): String = if (variantName == "debug") {
    "fixthisSetup"
} else {
    "fixthisSetup${variantName.capitalized()}"
}

internal fun fixThisSidekickCoordinate(runtimeVersion: String): String = "io.github.beyondwin:fixthis-compose-sidekick:$runtimeVersion"

private fun String.capitalized(): String = replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase() else char.toString()
}
