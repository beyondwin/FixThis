import java.util.Properties

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.plugin.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
}

val releaseProperties =
    Properties().also { properties ->
        rootProject.projectDir
            .resolve("../gradle.properties")
            .inputStream()
            .use(properties::load)
    }

fun releaseProperty(name: String): String =
    releaseProperties.getProperty(name)
        ?: error("Missing $name in ../gradle.properties")

group = releaseProperty("fixthis.group")
version = releaseProperty("fixthis.version")

abstract class GenerateFixThisPluginVersionTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.Input
    abstract val pluginVersion: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val versionLiteral =
            pluginVersion
                .get()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        val target =
            outputDir
                .get()
                .file("io/beyondwin/fixthis/gradle/FixThisPluginVersion.kt")
                .asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.beyondwin.fixthis.gradle

            internal object FixThisPluginVersion {
                const val VERSION = "$versionLiteral"

                fun defaultRuntimeVersion(): String =
                    FixThisPluginVersion::class.java.`package`.implementationVersion
                        ?.takeIf { it.isNotBlank() }
                        ?: VERSION
            }
            """.trimIndent(),
        )
    }
}

val generateFixThisPluginVersion =
    tasks.register<GenerateFixThisPluginVersionTask>("generateFixThisPluginVersion") {
        pluginVersion.set(project.version.toString())
        outputDir.set(layout.buildDirectory.dir("generated/source/fixthisVersion/main/kotlin"))
    }

kotlin {
    jvmToolchain(21)
    sourceSets.named("main") {
        kotlin.srcDir(generateFixThisPluginVersion)
    }
}

val ktlintVersion = libs.versions.ktlint.get()

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude(
            "**/build/**",
            "**/generated/**",
            "**/.worktrees/**",
            "**/.claude/worktrees/**",
            "**/worktrees/**",
        )
        ktlint(ktlintVersion).editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "enabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_comment-wrapping" to "disabled",
                "ktlint_standard_type-argument-comment" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude(
            "**/build/**",
            "**/.worktrees/**",
            "**/.claude/worktrees/**",
            "**/worktrees/**",
        )
        ktlint(ktlintVersion)
    }
}

fun requestedDetektTask(taskName: String): Boolean {
    val task = taskName.substringAfterLast(":")
    return task == "build" ||
        task == "check" ||
        task.startsWith("detekt", ignoreCase = true)
}

if (gradle.startParameter.taskNames.any(::requestedDetektTask)) {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(files("${rootProject.projectDir}/../config/detekt/detekt.yml"))
        // See note in root build.gradle.kts about per-module baseline files.
        baseline = file("${rootProject.projectDir}/../config/detekt/baseline-fixthis-gradle-plugin.xml")
        parallel = true
    }
}

gradlePlugin {
    website.set("https://github.com/beyondwin/FixThis")
    vcsUrl.set("https://github.com/beyondwin/FixThis.git")

    plugins {
        create("fixThisCompose") {
            id = "io.beyondwin.fixthis.compose"
            implementationClass = "io.beyondwin.fixthis.gradle.FixThisGradlePlugin"
            displayName = "FixThis Compose"
            description = "Adds the FixThis debug-only Jetpack Compose sidekick and source index generation."
            tags.set(listOf("android", "compose", "debugging", "mcp", "ai"))
        }
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Implementation-Version" to project.version.toString())
    }
}

val functionalTestSourceSet =
    sourceSets.create("functionalTest") {
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += output + compileClasspath
    }

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// The gradle-plugin is consumed via `pluginManagement { includeBuild(...) }`
// so it cannot resolve sibling subprojects. Instead, locate sidekick artefacts
// relative to the root included build's projectDir.
val sidekickDir = rootProject.projectDir.resolve("../fixthis-compose-sidekick")
val consumerRulesFile = sidekickDir.resolve("consumer-rules.pro")
val sidekickDebugManifestFile = sidekickDir.resolve("src/debug/AndroidManifest.xml")
val sidekickMainManifestFile = sidekickDir.resolve("src/main/AndroidManifest.xml")
val sidekickInitializerSourceFile =
    sidekickDir.resolve(
        "src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/init/FixThisInitializer.kt",
    )

val functionalTest =
    tasks.register<Test>("functionalTest") {
        description = "Runs functional tests for the FixThis Gradle plugin."
        group = "verification"
        testClassesDirs = functionalTestSourceSet.output.classesDirs
        classpath = functionalTestSourceSet.runtimeClasspath
        useJUnit()
        systemProperty("fixthis.consumerRules.path", consumerRulesFile.absolutePath)
        systemProperty("fixthis.sidekick.debugManifest.path", sidekickDebugManifestFile.absolutePath)
        systemProperty("fixthis.sidekick.mainManifest.path", sidekickMainManifestFile.absolutePath)
        systemProperty("fixthis.sidekick.initializerSource.path", sidekickInitializerSourceFile.absolutePath)
        inputs.file(consumerRulesFile).withPropertyName("consumerRules")
        inputs.file(sidekickDebugManifestFile).withPropertyName("sidekickDebugManifest")
        inputs.file(sidekickMainManifestFile).withPropertyName("sidekickMainManifest")
        inputs.file(sidekickInitializerSourceFile).withPropertyName("sidekickInitializerSource")
    }

tasks.named("check") {
    dependsOn(functionalTest)
}

dependencies {
    compileOnly("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(gradleTestKit())
    testImplementation(libs.kotlinx.serialization.json)

    "functionalTestImplementation"(libs.junit)
    "functionalTestImplementation"(gradleTestKit())
}
