package io.github.beyondwin.fixthis.cli.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GlobalScopeGuardTest {
    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun emptyDirIsNotAndroidProject() {
        val root = tempFolder.newFolder("empty")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithOnlyApplicationIdButNoSettingsGradleIsNotAndroidProject() {
        val root = tempFolder.newFolder("just-build-file")
        java.io.File(root, "build.gradle.kts").writeText("""android { defaultConfig { applicationId = "x" } }""")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithSettingsGradleButNoApplicationIdIsNotAndroidProject() {
        val root = tempFolder.newFolder("just-settings")
        java.io.File(root, "settings.gradle.kts").writeText("""include(":app")""")
        assertFalse(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun dirWithBothSettingsGradleAndApplicationIdIsAndroidProject() {
        val root = tempFolder.newFolder("real-android")
        java.io.File(root, "settings.gradle.kts").writeText("""include(":app")""")
        val appDir = java.io.File(root, "app").apply { mkdirs() }
        java.io.File(appDir, "build.gradle.kts").writeText("""android { defaultConfig { applicationId = "com.example" } }""")
        assertTrue(GlobalScopeGuard.isAndroidProject(root))
    }

    @Test
    fun guardDecisionRefusesGlobalWriteWithoutAndroidProject() {
        val root = tempFolder.newFolder("empty")
        val decision = GlobalScopeGuard.decide(root, allowGlobal = false)
        assertEquals(GlobalScopeGuard.Decision.SKIP_GLOBAL_WRITES, decision)
    }

    @Test
    fun guardDecisionAllowsGlobalWriteWithExplicitFlag() {
        val root = tempFolder.newFolder("empty")
        val decision = GlobalScopeGuard.decide(root, allowGlobal = true)
        assertEquals(GlobalScopeGuard.Decision.PROCEED, decision)
    }
}
