package io.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SessionTokenStoreTest {
    @Test
    fun writesSessionJsonUnderFixThisFilesDirectory() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionTokenStore(
            context = application,
            tokenGenerator = { "token-256-bit-base64" },
            clock = { 1777786212000L },
            sidekickVersion = "0.1.0-test",
        )

        val session = store.createAndWrite(packageName = "io.beyondwin.fixthis.sample")

        val file = File(application.filesDir, "fixthis/session.json")
        assertTrue(file.exists())
        assertEquals("fixthis_io.beyondwin.fixthis.sample", session.socketName)
        assertEquals("localabstract:fixthis_io.beyondwin.fixthis.sample", session.socketAddress)
        assertEquals("token-256-bit-base64", session.token)
        assertEquals("1.3", session.bridgeProtocolVersion)
        assertEquals(1777786212000L, session.createdAtEpochMillis)
        assertTrue(file.readText().contains(""""createdAtEpochMillis": 1777786212000"""))
        assertTrue(file.readText().contains(""""processStartEpochMillis": 1777786212000"""))
        assertTrue(file.readText().contains(""""sidekickVersion": "0.1.0-test""""))
    }

    @Test
    fun usesFixThisDefaultVersionAndSocketContract() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = SessionTokenStore(
            context = application,
            tokenGenerator = { "token-256-bit-base64" },
            clock = { 1777786212000L },
        )

        val session = store.create(packageName = "io.beyondwin.fixthis.sample")

        assertEquals(FixThisSidekickVersion, session.sidekickVersion)
        assertEquals("0.1.0", session.sidekickVersion)
        assertEquals(
            "fixthis_io.beyondwin.fixthis.sample",
            SessionTokenStore.socketNameForPackage("io.beyondwin.fixthis.sample"),
        )
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
