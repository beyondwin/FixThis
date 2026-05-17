plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.gradle.plugin.publish)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt) apply false
    `maven-publish`
    signing
}

group = providers.gradleProperty("FIXTHIS_GROUP").orElse("io.github.beyondwin").get()
version = providers.gradleProperty("FIXTHIS_VERSION").orElse("0.2.3-SNAPSHOT").get()

kotlin {
    jvmToolchain(21)
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
    vcsUrl.set("https://github.com/beyondwin/FixThis")
    plugins {
        create("fixThisCompose") {
            id = "io.github.beyondwin.fixthis.compose"
            displayName = "FixThis Compose"
            description = "Adds the debug-only FixThis sidekick and source index to Jetpack Compose Android apps."
            tags.set(listOf("android", "compose", "debugging", "mcp", "ai"))
            implementationClass = "io.github.beyondwin.fixthis.gradle.FixThisGradlePlugin"
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("FixThis ${artifactId.orEmpty()}")
            description.set("Debug-only Jetpack Compose feedback sidekick for AI coding agents.")
            url.set("https://github.com/beyondwin/FixThis")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("beyondwin")
                    name.set("BeyondWin")
                    url.set("https://github.com/beyondwin")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/beyondwin/FixThis.git")
                developerConnection.set("scm:git:ssh://git@github.com/beyondwin/FixThis.git")
                url.set("https://github.com/beyondwin/FixThis")
            }
        }
    }
}

signing {
    val signingKey =
        providers
            .gradleProperty("signingKey")
            .orElse(providers.environmentVariable("SIGNING_KEY"))
    val signingPassword =
        providers
            .gradleProperty("signingPassword")
            .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
    isRequired = signingKey.isPresent && signingPassword.isPresent
    if (signingKey.isPresent && signingPassword.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.get())
        sign(publishing.publications)
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
        "src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/init/FixThisInitializer.kt",
    )

val releaseConsumerFixtureDir =
    layout.projectDirectory.dir("src/functionalTest/resources/release-consumer-fixture").asFile

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
        systemProperty("fixthis.releaseConsumerFixture.path", releaseConsumerFixtureDir.absolutePath)
        inputs.file(consumerRulesFile).withPropertyName("consumerRules")
        inputs.file(sidekickDebugManifestFile).withPropertyName("sidekickDebugManifest")
        inputs.file(sidekickMainManifestFile).withPropertyName("sidekickMainManifest")
        inputs.file(sidekickInitializerSourceFile).withPropertyName("sidekickInitializerSource")
        inputs.dir(releaseConsumerFixtureDir).withPropertyName("releaseConsumerFixture")
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
