package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import io.beyondwin.fixthis.compose.sidekick.lifecycle.FixThisActivityLifecycleCallbacks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class BridgeStatusAvailabilityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `legacy constructor populates new fields with null`() {
        val status = BridgeStatus(
            activity = "MainActivity",
            rootsCount = 1,
            sidekickVersion = "1",
            bridgeProtocolVersion = "1",
            sourceIndexAvailable = true,
        )
        assertNull(status.screenInteractive)
        assertNull(status.keyguardLocked)
        assertNull(status.appForeground)
        assertNull(status.pictureInPicture)
    }

    @Test
    fun `serializing populated status emits availability fields`() {
        val status = BridgeStatus(
            activity = "MainActivity",
            rootsCount = 2,
            sidekickVersion = "1",
            bridgeProtocolVersion = "1",
            sourceIndexAvailable = true,
            capabilities = BridgeCapabilities(),
            screenInteractive = true,
            keyguardLocked = false,
            appForeground = true,
            pictureInPicture = false,
        )
        val text = json.encodeToString(BridgeStatus.serializer(), status)
        assertTrue(text.contains("\"screenInteractive\":true"))
        assertTrue(text.contains("\"keyguardLocked\":false"))
        assertTrue(text.contains("\"appForeground\":true"))
        assertTrue(text.contains("\"pictureInPicture\":false"))
    }

    @Test
    fun `deserializing legacy payload yields null availability fields`() {
        val legacy = """
            {
              "activity": "MainActivity",
              "rootsCount": 1,
              "sidekickVersion": "1",
              "bridgeProtocolVersion": "1",
              "sourceIndexAvailable": true
            }
        """.trimIndent()
        val status = json.decodeFromString(BridgeStatus.serializer(), legacy)
        assertEquals("MainActivity", status.activity)
        assertNull(status.screenInteractive)
        assertNull(status.keyguardLocked)
        assertNull(status.appForeground)
        assertNull(status.pictureInPicture)
    }

    @Test
    fun `runtime status reports availability from collaborators`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguardManager = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val shadowPower = Shadows.shadowOf(powerManager)
        val shadowKeyguard = Shadows.shadowOf(keyguardManager)
        shadowPower.setIsInteractive(false)
        shadowKeyguard.setKeyguardLocked(true)

        val lifecycle = FixThisActivityLifecycleCallbacks()
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        lifecycle.onActivityResumed(activity)

        val environment = AndroidBridgeEnvironment(
            context = app,
            sidekickVersion = "test",
            lifecycleCallbacks = lifecycle,
        )

        val status = environment.status()
        assertEquals(false, status.screenInteractive)
        assertEquals(true, status.keyguardLocked)
        assertEquals(true, status.appForeground)
        assertNotNull(status.pictureInPicture)
    }
}
