package io.github.pointpatch.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.pointpatch.gradle.task.GeneratePointPatchSourceIndexTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class PointPatchGradlePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "pointpatch",
            PointPatchExtension::class.java,
        )

        project.plugins.withId("com.android.application") {
            configureAndroidApplication(project, extension)
        }
    }

    private fun configureAndroidApplication(
        project: Project,
        extension: PointPatchExtension,
    ) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!variant.debuggable || !extension.enabled.get()) {
                return@onVariants
            }

            if (extension.addDebugRuntime.get()) {
                addRuntimeDependency(project, variant, extension)
            }

            if (!extension.generateSourceIndex.get() && !extension.generateProjectMetadata.get()) {
                return@onVariants
            }

            val taskProvider = project.tasks.register(
                "generate${variant.name.capitalized()}PointPatchSourceIndex",
                GeneratePointPatchSourceIndexTask::class.java,
            ) { task ->
                val sourceSetNames = activeSourceSetNames(
                    variantName = variant.name,
                    buildType = variant.buildType,
                    productFlavorNames = variant.productFlavors.map { it.second },
                    flavorName = variant.flavorName,
                )
                task.projectDirectory.set(project.layout.projectDirectory)
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
                task.outputDirectory.set(
                    project.layout.buildDirectory.dir("generated/pointpatch/${variant.name}/assets"),
                )
                task.projectPath.set(project.path)
                task.variantName.set(variant.name)
                task.runtimeVersion.set(extension.runtimeVersion)
                task.includeScreenshots.set(extension.includeScreenshots)
                task.redactEditableText.set(extension.redactEditableText)
                task.generateSourceIndex.set(extension.generateSourceIndex)
                task.generateProjectMetadata.set(extension.generateProjectMetadata)
            }

            variant.sources.assets?.addGeneratedSourceDirectory(
                taskProvider,
                GeneratePointPatchSourceIndexTask::outputDirectory,
            )
        }
    }

    private fun addRuntimeDependency(
        project: Project,
        variant: ApplicationVariant,
        extension: PointPatchExtension,
    ) {
        val configurationName = "${variant.name}Implementation"
        val dependency = project.rootProject.findProject(":pointpatch-compose-sidekick")
            ?.let { sidekickProject -> project.dependencies.project(mapOf("path" to sidekickProject.path)) }
            ?: "io.github.pointpatch:pointpatch-compose-sidekick:${extension.runtimeVersion.get()}"

        project.dependencies.add(configurationName, dependency)
    }

    private fun String.capitalized(): String =
        replaceFirstChar { char ->
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
