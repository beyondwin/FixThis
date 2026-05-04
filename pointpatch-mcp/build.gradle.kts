plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.pointpatch.mcp.McpServerKt")
    applicationName = "pointpatch-mcp"
}

dependencies {
    implementation(project(":pointpatch-cli"))
    implementation(project(":pointpatch-compose-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
