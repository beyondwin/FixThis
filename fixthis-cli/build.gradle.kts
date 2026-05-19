plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

val fixthisVersion = providers.gradleProperty("FIXTHIS_VERSION")
val generatedFixThisVersionDir = layout.buildDirectory.dir("generated/fixthisVersion/kotlin")

val generateFixThisVersion =
    tasks.register("generateFixThisVersion") {
        val version = fixthisVersion
        inputs.property("fixthisVersion", version)
        outputs.dir(generatedFixThisVersionDir)
        doLast {
            val output =
                generatedFixThisVersionDir
                    .get()
                    .file("io/github/beyondwin/fixthis/cli/FixThisRelease.kt")
                    .asFile
            output.parentFile.mkdirs()
            output.writeText(
                """
                package io.github.beyondwin.fixthis.cli

                internal object FixThisRelease {
                    const val VERSION: String = "${version.get()}"
                }
                """.trimIndent() + "\n",
            )
        }
    }

kotlin {
    jvmToolchain(21)
}

sourceSets.named("main") {
    java.srcDir(generatedFixThisVersionDir)
}

tasks.named("compileKotlin") {
    dependsOn(generateFixThisVersion)
}

application {
    mainClass.set("io.github.beyondwin.fixthis.cli.MainKt")
    applicationName = "fixthis"
}

dependencies {
    implementation(project(":fixthis-compose-core"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}
