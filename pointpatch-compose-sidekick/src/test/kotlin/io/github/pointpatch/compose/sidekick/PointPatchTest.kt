package io.github.pointpatch.compose.sidekick

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PointPatchTest {
    @Before
    fun resetPointPatch() {
        PointPatch.resetForTest()
    }

    @Test
    fun installRegistersOnlyOnceForSameApplication() {
        val application = Application()
        var registrations = 0

        PointPatch.installForTest(application) { registrations++ }
        PointPatch.installForTest(application) { registrations++ }

        assertEquals(1, registrations)
    }

    @Test
    fun installRegistersFreshApplicationInSameVm() {
        var registrations = 0

        PointPatch.installForTest(Application()) { registrations++ }
        PointPatch.installForTest(Application()) { registrations++ }

        assertEquals(2, registrations)
    }
}
