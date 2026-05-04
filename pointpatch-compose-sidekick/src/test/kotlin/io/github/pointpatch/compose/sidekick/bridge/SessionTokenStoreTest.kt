package io.github.pointpatch.compose.sidekick.bridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionTokenStoreTest {
    @Test
    fun writesSessionJsonUnderPointPatchFilesDirectory() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionTokenStore(
            context = application,
            tokenGenerator = { "token-256-bit-base64" },
            clock = { 1777786212000L },
            sidekickVersion = "0.1.0-test",
        )

        val session = store.createAndWrite(packageName = "io.github.pointpatch.sample")

        val file = File(application.filesDir, "pointpatch/session.json")
        assertTrue(file.exists())
        assertEquals("pointpatch_io.github.pointpatch.sample", session.socketName)
        assertEquals("localabstract:pointpatch_io.github.pointpatch.sample", session.socketAddress)
        assertEquals("token-256-bit-base64", session.token)
        assertEquals("1.0", session.bridgeProtocolVersion)
        assertEquals(1777786212000L, session.createdAtEpochMillis)
        assertTrue(file.readText().contains(""""createdAtEpochMillis": 1777786212000"""))
        assertTrue(file.readText().contains(""""processStartEpochMillis": 1777786212000"""))
        assertTrue(file.readText().contains(""""sidekickVersion": "0.1.0-test""""))
    }

    @Test
    fun generatesDifferentBase64Tokens() {
        val first = SessionTokenStore.generateToken()
        val second = SessionTokenStore.generateToken()

        assertFalse(first == second)
        assertTrue(first.length >= 43)
        assertFalse(first.contains("\n"))
    }
}
