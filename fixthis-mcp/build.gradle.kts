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

dependencies {
    implementation(project(":fixthis-cli"))
    implementation(project(":fixthis-compose-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
}
