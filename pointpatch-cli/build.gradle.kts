plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.pointpatch.cli.MainKt")
    applicationName = "pointpatch"
}

tasks.startScripts {
    doLast {
        val scriptsDir = outputDir ?: error("startScripts outputDir was not configured")
        val legacyUnixScript = scriptsDir.resolve("pointpatch-cli")
        unixScript.copyTo(legacyUnixScript, overwrite = true)
        legacyUnixScript.setExecutable(true, false)
        windowsScript.copyTo(scriptsDir.resolve("pointpatch-cli.bat"), overwrite = true)
    }
}

dependencies {
    implementation(project(":pointpatch-compose-core"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
