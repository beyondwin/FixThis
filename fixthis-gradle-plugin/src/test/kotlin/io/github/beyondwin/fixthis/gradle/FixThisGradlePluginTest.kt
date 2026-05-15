package io.github.beyondwin.fixthis.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

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
            "io.github.beyondwin:fixthis-compose-sidekick:0.2.1",
            fixThisSidekickCoordinate("0.2.1"),
        )
    }

    @Test
    fun `default runtime version matches current public patch release`() {
        assertEquals("0.2.1", DefaultFixThisRuntimeVersion)
    }
}
