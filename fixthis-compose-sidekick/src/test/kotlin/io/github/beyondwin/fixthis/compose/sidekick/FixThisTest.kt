package io.github.beyondwin.fixthis.compose.sidekick

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FixThisTest {
    @Before
    fun resetFixThis() {
        FixThis.resetForTest()
    }

    @Test
    fun installRegistersOnlyOnceForSameApplication() {
        val application = Application()
        var registrations = 0

        FixThis.installForTest(application) { registrations++ }
        FixThis.installForTest(application) { registrations++ }

        assertEquals(1, registrations)
    }

    @Test
    fun installRegistersFreshApplicationInSameVm() {
        var registrations = 0

        FixThis.installForTest(Application()) { registrations++ }
        FixThis.installForTest(Application()) { registrations++ }

        assertEquals(2, registrations)
    }
}
