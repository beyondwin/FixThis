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
                "UNKNOWN_ERROR",
            ),
            FirstRunReadinessState.entries.map { it.name },
        )
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
