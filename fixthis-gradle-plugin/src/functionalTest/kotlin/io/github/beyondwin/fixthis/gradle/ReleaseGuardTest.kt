package io.github.beyondwin.fixthis.gradle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Structural release-guard test for BR-1.
 *
 * **Scope downgrade rationale.** The plan's BR-1 ideal exercises an Android
 * `assembleRelease` against a fixture project with the sidekick promoted to
 * `implementation`, then inspects the merged release manifest and verifies the
 * `FixThisInitializer` entry is absent. That requires an AGP classpath, an
 * Android SDK reachable from the gradleTestKit-isolated runner, Compose BOM
 * resolution, and several minutes per run — same blockers as BR-2. The plan
 * grants explicit latitude:
 *
 *   > Per orchestrator latitude (same as BR-2 functional test): if a full
 *   > Android-fixture `assembleRelease` test inside `gradleTestKit` proves
 *   > intractable, downgrade to a STRUCTURAL test.
 *
 * This test asserts the structural split that BR-1 relies on:
 *
 *  1. `fixthis-compose-sidekick/src/debug/AndroidManifest.xml` exists and
 *     contains the `androidx.startup` `InitializationProvider` and the
 *     `FixThisInitializer` meta-data — so debug consumers still get the
 *     startup entry.
 *  2. `fixthis-compose-sidekick/src/main/AndroidManifest.xml` does NOT
 *     reference `androidx.startup` or `FixThisInitializer` — so release
 *     consumers do not merge those provider/meta-data tags.
 *  3. `FixThisInitializer.kt` source contains the release-build warning
 *     `Log.w(...)` call — the runtime defence-in-depth fallback.
 *
 * File paths are injected via system properties set by the Gradle
 * `functionalTest` task; the same wiring pattern as `SidekickConsumerRulesTest`.
 */
class ReleaseGuardTest {

    private lateinit var debugManifest: File
    private lateinit var mainManifest: File
    private lateinit var initializerSource: File

    @Before
    fun setUp() {
        debugManifest = requireFile("fixthis.sidekick.debugManifest.path")
        mainManifest = requireFile("fixthis.sidekick.mainManifest.path")
        initializerSource = requireFile("fixthis.sidekick.initializerSource.path")
    }

    @Test
    fun `debug manifest exists and registers FixThisInitializer via androidx startup`() {
        assertTrue(
            "Debug AndroidManifest.xml must exist at ${debugManifest.absolutePath}",
            debugManifest.isFile,
        )
        val contents = debugManifest.readText()
        assertTrue(
            "Debug manifest must register androidx.startup InitializationProvider; got:\n$contents",
            contents.contains("androidx.startup.InitializationProvider"),
        )
        assertTrue(
            "Debug manifest must reference FixThisInitializer; got:\n$contents",
            contents.contains("io.github.beyondwin.fixthis.compose.sidekick.init.FixThisInitializer"),
        )
    }

    @Test
    fun `main manifest does not register the startup entry for release consumers`() {
        assertTrue(
            "Main AndroidManifest.xml must exist at ${mainManifest.absolutePath}",
            mainManifest.isFile,
        )
        val contents = mainManifest.readText()
        assertFalse(
            "Main manifest must NOT reference androidx.startup (move it to src/debug); got:\n$contents",
            contents.contains("androidx.startup"),
        )
        assertFalse(
            "Main manifest must NOT reference FixThisInitializer (move it to src/debug); got:\n$contents",
            contents.contains("FixThisInitializer"),
        )
    }

    @Test
    fun `initializer source contains a release-build warning log`() {
        assertTrue(
            "FixThisInitializer.kt must exist at ${initializerSource.absolutePath}",
            initializerSource.isFile,
        )
        val contents = initializerSource.readText()
        assertTrue(
            "FixThisInitializer.kt must import android.util.Log; got:\n$contents",
            contents.contains("import android.util.Log"),
        )
        assertTrue(
            "FixThisInitializer.kt must emit a Log.w warning on release builds; got:\n$contents",
            contents.contains("Log.w("),
        )
        assertTrue(
            "FixThisInitializer.kt must keep the FLAG_DEBUGGABLE early-return; got:\n$contents",
            contents.contains("FLAG_DEBUGGABLE"),
        )
    }

    private fun requireFile(property: String): File {
        val path = System.getProperty(property)
        assertNotNull(
            "$property system property is not set; ensure the Gradle functionalTest task wires it.",
            path,
        )
        return File(path!!)
    }
}
