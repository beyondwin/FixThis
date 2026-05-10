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

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/source/buildinfo/main/kotlin")
    val gitShaProvider = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText
    val nowProvider = providers.provider {
        (System.currentTimeMillis() / 60_000L) * 60_000L
    }
    outputs.dir(outputDir)
    inputs.property("gitSha", gitShaProvider.map { it.trim().ifBlank { "unknown" } })
    inputs.property("buildEpoch", nowProvider)
    doLast {
        val sha = gitShaProvider.get().trim().ifBlank { "unknown" }
        val epoch = nowProvider.get()
        val target = outputDir.get().file("io/beyondwin/fixthis/mcp/BuildInfo.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(
            """
            package io.beyondwin.fixthis.mcp
            object BuildInfo {
                const val BUILD_EPOCH_MS: Long = ${epoch}L
                const val GIT_SHA: String = "$sha"
            }
            """.trimIndent()
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateBuildInfo.map { it.outputs.files })
}

tasks.named("compileKotlin") { dependsOn(generateBuildInfo) }

dependencies {
    implementation(project(":fixthis-cli"))
    implementation(project(":fixthis-compose-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
