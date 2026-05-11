plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
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
// so it cannot resolve sibling subprojects. Instead, locate the sidekick's
// consumer-rules.pro relative to the root included build's projectDir.
val consumerRulesFile = rootProject.projectDir
    .resolve("../fixthis-compose-sidekick/consumer-rules.pro")

val functionalTest = tasks.register<Test>("functionalTest") {
    description = "Runs functional tests for the FixThis Gradle plugin."
    group = "verification"
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    useJUnit()
    systemProperty("fixthis.consumerRules.path", consumerRulesFile.absolutePath)
    inputs.file(consumerRulesFile).withPropertyName("consumerRules")
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
