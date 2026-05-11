plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.beyondwin.fixthis.mcp.McpServerKt")
    applicationName = "fixthis-mcp"
}

// Captured once at configuration time so BuildInfo.kt and the bundled console
// app.js share the exact same SHA/epoch within a single build. Otherwise the
// console staleness banner fires on freshly built JARs (the committed bundle's
// embedded SHA is always one commit behind the commit that contains it).
val resolvedGitSha: String =
    providers
        .exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull
        ?.trim()
        ?.ifBlank { "unknown" } ?: "unknown"
val resolvedBuildEpochMs: Long = (System.currentTimeMillis() / 60_000L) * 60_000L

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    val sha = resolvedGitSha
    val epoch = resolvedBuildEpochMs
    outputs.dir(outputDir)
    inputs.property("gitSha", sha)
    inputs.property("buildEpoch", epoch)
    doLast {
        val target = outputDir.get().file("io/beyondwin/fixthis/mcp/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.beyondwin.fixthis.mcp
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${epoch}L
                const val GIT_SHA: String = "$sha"
            }
            """.trimIndent(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}

tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

tasks.processResources {
    val sha = resolvedGitSha
    val epoch = resolvedBuildEpochMs
    inputs.property("consoleGitSha", sha)
    inputs.property("consoleBuildEpoch", epoch)
    filesMatching("console/app.js") {
        filter { line: String ->
            line
                .replace(
                    Regex("""(const\s+ConsoleBuildEpochMs\s*=\s*)\d+(\s*;)"""),
                    "$1$epoch$2",
                ).replace(
                    Regex("""(const\s+ConsoleBuildGitSha\s*=\s*)'[^']*'(\s*;)"""),
                    "$1'$sha'$2",
                )
        }
    }
}

dependencies {
    implementation(project(":fixthis-cli"))
    implementation(project(":fixthis-compose-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
