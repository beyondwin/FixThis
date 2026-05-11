plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.beyondwin.fixthis.compose.sidekick"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":fixthis-compose-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.startup)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.foundation)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

abstract class GenerateSidekickBuildInfoTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val gitSha: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildEpoch: org.gradle.api.provider.Property<Long>

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val target = outputDir.get().file("io/beyondwin/fixthis/compose/sidekick/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.beyondwin.fixthis.compose.sidekick
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${buildEpoch.get()}L
                const val GIT_SHA: String = "${gitSha.get()}"
            }
            """.trimIndent()
        )
    }
}

val generateBuildInfo = tasks.register<GenerateSidekickBuildInfoTask>("generateBuildInfo") {
    outputDir.set(layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin"))
    gitSha.set(
        providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim().ifBlank { "unknown" } },
    )
    buildEpoch.set(providers.provider { (System.currentTimeMillis() / 60_000L) * 60_000L })
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            generateBuildInfo,
            GenerateSidekickBuildInfoTask::outputDir,
        )
    }
}
