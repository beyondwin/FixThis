package io.github.beyondwin.fixthis.cli.readiness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunReadinessTest {
    @Test
    fun stateNamesMatchV03Contract() {
        assertEquals(
            listOf(
                "READY",
                "NEEDS_INSTALL",
                "NEEDS_APP_LAUNCH",
                "DEVICE_BLOCKED",
                "UNSUPPORTED_BUILD",
                "CONFIG_RECOVERABLE",
                "ENV_BLOCKER",
                "STALE_PREVIEW",
                "SESSION_MISMATCH",
                "CAPTURE_UNAVAILABLE",
                "UNKNOWN_ERROR",
            ),
            FirstRunReadinessState.entries.map { it.name },
        )
    }

    @Test
    fun catalogCaptureUnavailableUsesRetryCapturePrimaryAction() {
        val readiness = FirstRunReadinessFailureCatalog.captureUnavailable(
            cause = "Screenshot bytes unavailable.",
            details = mapOf("rawError" to "screenshot 404"),
        )

        assertEquals(FirstRunReadinessState.CAPTURE_UNAVAILABLE, readiness.state)
        assertEquals("Retry capture", readiness.nextAction)
        assertEquals(
            "Open the app foreground and tap Capture, or open doctor for the bridge log.",
            readiness.verify,
        )
        assertEquals("Screenshot bytes unavailable.", readiness.cause)
        assertEquals("screenshot 404", readiness.details.getValue("rawError"))
    }

    @Test
    fun catalogCaptureUnavailableDefaultsToEmptyDetails() {
        val readiness = FirstRunReadinessFailureCatalog.captureUnavailable("Capture unavailable.")
        assertEquals(emptyMap<String, String>(), readiness.details)
    }

    @Test
    fun catalogProvidesOnePrimaryNextAction() {
        val unsupported = FirstRunReadinessCatalog.unsupportedBuild(
            cause = "run-as denied",
            details = mapOf("rawError" to "run-as: package not debuggable"),
        )

        assertEquals(FirstRunReadinessState.UNSUPPORTED_BUILD, unsupported.state)
        assertEquals("Install a debuggable build with FixThis enabled.", unsupported.nextAction)
        assertTrue(unsupported.fix.contains("debuggable"))
        assertEquals("run-as: package not debuggable", unsupported.details.getValue("rawError"))
    }

    @Test
    fun classifyBridgeErrorMapsKnownErrors() {
        assertEquals(
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            classifyBridgeFailure("run-as: package not debuggable").state,
        )
        assertEquals(
            FirstRunReadinessState.UNSUPPORTED_BUILD,
            classifyBridgeFailure("run-as: permission denied").state,
        )
        assertEquals(
            FirstRunReadinessState.NEEDS_APP_LAUNCH,
            classifyBridgeFailure("Could not connect to FixThis bridge").state,
        )
        assertEquals(
            FirstRunReadinessState.UNKNOWN_ERROR,
            classifyBridgeFailure("Socket failed for unexpected reason").state,
        )
    }
}
