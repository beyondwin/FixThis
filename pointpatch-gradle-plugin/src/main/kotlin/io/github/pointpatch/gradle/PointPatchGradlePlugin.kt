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
                task.projectDirectory.set(project.layout.projectDirectory)
                task.kotlinSourceFiles.from(
                    project.layout.projectDirectory.asFileTree.matching { pattern ->
                        pattern.include("src/**/*.kt")
                        pattern.include("src/**/*.kts")
                        pattern.exclude("src/test/**")
                        pattern.exclude("src/androidTest/**")
                    },
                )
                task.resourceXmlFiles.from(
                    project.layout.projectDirectory.asFileTree.matching { pattern ->
                        pattern.include("src/**/res/**/*.xml")
                        pattern.exclude("src/test/**")
                        pattern.exclude("src/androidTest/**")
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
