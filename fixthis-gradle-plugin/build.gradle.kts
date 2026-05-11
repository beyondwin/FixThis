plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
}

kotlin {
    jvmToolchain(21)
}

val ktlintVersion = libs.versions.ktlint.get()

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**", "**/generated/**")
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
        targetExclude("**/build/**")
        ktlint(ktlintVersion)
    }
}

gradlePlugin {
    plugins {
        create("fixThisCompose") {
            id = "io.beyondwin.fixthis.compose"
            implementationClass = "io.beyondwin.fixthis.gradle.FixThisGradlePlugin"
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest") {
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
val sidekickInitializerSourceFile = sidekickDir.resolve(
    "src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/init/FixThisInitializer.kt",
)

val functionalTest = tasks.register<Test>("functionalTest") {
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
