package io.github.beyondwin.fixthis.gradle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FixThisGradlePluginTest {
    @Test
    fun `active source sets include main build type flavors flavor combo and variant`() {
        assertEquals(
            listOf("main", "debug", "free", "us", "freeUs", "freeUsDebug"),
            activeSourceSetNames(
                variantName = "freeUsDebug",
                buildType = "debug",
                productFlavorNames = listOf("free", "us"),
                flavorName = "freeUs",
            ),
        )
    }

    @Test
    fun `active source sets exclude inactive flavors and build types`() {
        val sourceSets = activeSourceSetNames(
            variantName = "paidEuRelease",
            buildType = "release",
            productFlavorNames = listOf("paid", "eu"),
            flavorName = "paidEu",
        )

        assertEquals(
            listOf("main", "release", "paid", "eu", "paidEu", "paidEuRelease"),
            sourceSets,
        )
    }

    @Test
    fun `active source sets collapse duplicates for single flavor variants`() {
        assertEquals(
            listOf("main", "debug", "demo", "demoDebug"),
            activeSourceSetNames(
                variantName = "demoDebug",
                buildType = "debug",
                productFlavorNames = listOf("demo"),
                flavorName = "demo",
            ),
        )
    }

    @Test
    fun `external runtime dependency uses github maven namespace`() {
        assertEquals(
            "io.github.beyondwin:fixthis-compose-sidekick:0.2.3",
            fixThisSidekickCoordinate("0.2.3"),
        )
    }

    @Test
    fun `indexed source roots include sibling feature modules by default`() {
        val root = Files.createTempDirectory("fixthis-indexed-roots").toFile().canonicalFile
        val app = root.resolve("app")
        val feature = root.resolve("feature/station-list")
        app.resolve("src/main/java").mkdirs()
        app.resolve("src/debug/java").mkdirs()
        feature.resolve("src/main/java").mkdirs()
        feature.resolve("src/debug/java").mkdirs()

        val roots = indexedSourceSetRoots(
            projectDirectory = app,
            additionalProjectDirectories = listOf(feature),
            sourceSetNames = listOf("main", "debug"),
            childPath = "java",
        ).map { it.relativeTo(root).invariantSeparatorsPath }

        assertEquals(
            listOf(
                "app/src/main/java",
                "app/src/debug/java",
                "feature/station-list/src/main/java",
                "feature/station-list/src/debug/java",
            ),
            roots,
        )
    }

    @Test
    fun `default runtime version matches current public patch release`() {
        assertEquals(fixThisVersion(), DefaultFixThisRuntimeVersion)
    }

    @Test
    fun `default runtime version is generated from release metadata`() {
        val source = File(
            repoRoot(),
            "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisExtension.kt",
        ).readText()

        assertEquals(false, Regex("""DefaultFixThisRuntimeVersion\s*(?::\s*String)?\s*=\s*"[0-9]""").containsMatchIn(source))
    }

    private fun fixThisVersion(): String = System.getProperty("fixthis.version")
        ?: File(repoRoot(), "gradle.properties")
            .readLines()
            .first { it.startsWith("FIXTHIS_VERSION=") }
            .substringAfter("=")

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "gradle.properties").isFile && File(it, "settings.gradle.kts").isFile }
}
