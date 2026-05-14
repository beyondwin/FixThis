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
        consumerProguardFiles("consumer-rules.pro")
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
    implementation(libs.androidx.core.ktx)
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

abstract class GenerateSidekickBuildInfoResourcesTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val gitSha: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val buildEpoch: org.gradle.api.provider.Property<Long>

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val target = outputDir.get().file("values/fixthis_build_info.xml").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="fixthis_sidekick_build_epoch_ms" translatable="false">${buildEpoch.get()}</string>
                <string name="fixthis_sidekick_git_sha" translatable="false">${gitSha.get()}</string>
            </resources>
            """.trimIndent(),
        )
    }
}

val gitStatusProvider =
    providers
        .exec {
            commandLine("git", "status", "--porcelain")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }

val gitShortShaProvider =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifBlank { "unknown" } }

val gitCommitEpochProvider =
    providers
        .exec {
            commandLine("git", "log", "-1", "--format=%ct")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim() }

val generateBuildInfoResources =
    tasks.register<GenerateSidekickBuildInfoResourcesTask>("generateBuildInfoResources") {
        outputDir.set(layout.buildDirectory.dir("generated/res/buildinfo/main"))
        gitSha.set(
            gitShortShaProvider.zip(gitStatusProvider) { sha, status ->
                if (status.isEmpty()) sha else "$sha-dirty"
            },
        )
        buildEpoch.set(
            gitCommitEpochProvider.zip(gitStatusProvider) { commitEpochSeconds, status ->
                if (status.isEmpty() && commitEpochSeconds.isNotBlank()) {
                    commitEpochSeconds.toLong() * 1000L
                } else {
                    System.currentTimeMillis()
                }
            },
        )
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateBuildInfoResources,
            GenerateSidekickBuildInfoResourcesTask::outputDir,
        )
    }
}
