package io.beyondwin.fixthis.compose.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OverlayStateMachineTest {
    @Test
    fun idleCanEnterSelectMode() {
        val machine = OverlayStateMachine()

        machine.transition(OverlayMode.Select(requestId = "request-1"))

        assertTrue(machine.state.value is OverlayMode.Select)
    }

    @Test
    fun idleCannotJumpToExported() {
        val machine = OverlayStateMachine()

        assertInvalidTransition {
            machine.transition(OverlayMode.Exported(annotationId = "annotation-1"))
        }
    }

    @Test
    fun loadingCanMoveToRecoverableError() {
        val machine = OverlayStateMachine(OverlayMode.Select(requestId = "request-1"))

        machine.transition(OverlayMode.Loading(OverlayMode.LoadingReason.SCREENSHOT_CAPTURING))
        machine.transition(
            OverlayMode.Error(
                cause = OverlayMode.OverlayError.ScreenshotFailed("capture failed"),
                recoverable = true,
            ),
        )

        assertTrue(machine.state.value is OverlayMode.Error)
    }

    @Test
    fun transitionMatrixAllowsExpectedForwardPaths() {
        val draft = FixThisDraft()

        assertTrue(OverlayMode.Idle.canTransitionTo(OverlayMode.MenuOpen))
        assertTrue(OverlayMode.Idle.canTransitionTo(OverlayMode.Select(requestId = null)))
        assertTrue(OverlayMode.MenuOpen.canTransitionTo(OverlayMode.Idle))
        assertTrue(OverlayMode.MenuOpen.canTransitionTo(OverlayMode.Select(requestId = "request-1")))
        assertTrue(
            OverlayMode.Select(requestId = "request-1").canTransitionTo(
                OverlayMode.Loading(OverlayMode.LoadingReason.INSPECTOR_QUERYING),
            ),
        )
        assertTrue(OverlayMode.Select(requestId = "request-1").canTransitionTo(OverlayMode.ReviewingSelection(draft)))
        assertTrue(OverlayMode.Select(requestId = "request-1").canTransitionTo(OverlayMode.Commenting(draft)))
        assertTrue(
            OverlayMode.Select(requestId = "request-1").canTransitionTo(
                OverlayMode.Error(OverlayMode.OverlayError.PermissionDenied, recoverable = true),
            ),
        )
        assertTrue(
            OverlayMode.Loading(OverlayMode.LoadingReason.SCREENSHOT_CAPTURING).canTransitionTo(
                OverlayMode.ReviewingSelection(draft),
            ),
        )
        assertTrue(
            OverlayMode.Loading(OverlayMode.LoadingReason.BRIDGE_CONNECTING).canTransitionTo(
                OverlayMode.Commenting(draft),
            ),
        )
        assertTrue(
            OverlayMode.Loading(OverlayMode.LoadingReason.SCREENSHOT_CAPTURING).canTransitionTo(
                OverlayMode.Error(
                    cause = OverlayMode.OverlayError.Timeout(operation = "capture", timeoutMillis = 1_000L),
                    recoverable = false,
                ),
            ),
        )
        assertTrue(OverlayMode.ReviewingSelection(draft).canTransitionTo(OverlayMode.Commenting(draft)))
        assertTrue(OverlayMode.ReviewingSelection(draft).canTransitionTo(OverlayMode.Select(requestId = "retry")))
        assertTrue(OverlayMode.ReviewingSelection(draft).canTransitionTo(OverlayMode.Idle))
        assertTrue(OverlayMode.Commenting(draft).canTransitionTo(OverlayMode.Idle))
        assertTrue(OverlayMode.Commenting(draft).canTransitionTo(OverlayMode.Select(requestId = "retry")))
        assertTrue(OverlayMode.Commenting(draft).canTransitionTo(OverlayMode.Exported(annotationId = "annotation-1")))
        assertTrue(OverlayMode.Exported(annotationId = "annotation-1").canTransitionTo(OverlayMode.Idle))
        assertTrue(OverlayMode.Exported(annotationId = "annotation-1").canTransitionTo(OverlayMode.Select(requestId = "request-2")))
    }

    @Test
    fun transitionMatrixAllowsPayloadRefreshSelfTransitions() {
        val draft = FixThisDraft()

        assertTrue(OverlayMode.Select(requestId = "request-1").canTransitionTo(OverlayMode.Select(requestId = "request-2")))
        assertTrue(OverlayMode.ReviewingSelection(draft).canTransitionTo(OverlayMode.ReviewingSelection(draft)))
        assertTrue(OverlayMode.Commenting(draft).canTransitionTo(OverlayMode.Commenting(draft.copy(userComment = "updated"))))
    }

    @Test
    fun transitionMatrixRejectsUnsupportedPaths() {
        val draft = FixThisDraft()

        assertFalse(OverlayMode.Idle.canTransitionTo(OverlayMode.Exported(annotationId = "annotation-1")))
        assertFalse(OverlayMode.MenuOpen.canTransitionTo(OverlayMode.Commenting(draft)))
        assertFalse(
            OverlayMode.Loading(OverlayMode.LoadingReason.BRIDGE_CONNECTING).canTransitionTo(
                OverlayMode.Select(requestId = "request-1"),
            ),
        )
        assertFalse(OverlayMode.Exported(annotationId = "annotation-1").canTransitionTo(OverlayMode.Commenting(draft)))
        assertFalse(
            OverlayMode.Error(
                cause = OverlayMode.OverlayError.BridgeUnreachable("not connected"),
                recoverable = false,
            ).canTransitionTo(OverlayMode.Select(requestId = "request-1")),
        )
        assertFalse(
            OverlayMode.Error(
                cause = OverlayMode.OverlayError.ScreenshotFailed("capture failed"),
                recoverable = true,
            ).canTransitionTo(
                OverlayMode.Error(
                    cause = OverlayMode.OverlayError.ScreenshotFailed("failed again"),
                    recoverable = true,
                ),
            ),
        )
        assertFalse(
            OverlayMode.Loading(OverlayMode.LoadingReason.SCREENSHOT_CAPTURING).canTransitionTo(
                OverlayMode.Loading(OverlayMode.LoadingReason.INSPECTOR_QUERYING),
            ),
        )
        assertFalse(
            OverlayMode.Exported(annotationId = "annotation-1").canTransitionTo(
                OverlayMode.Exported(annotationId = "annotation-2"),
            ),
        )
    }

    @Test
    fun recoverableErrorCanTransitionToNonErrorMode() {
        val error = OverlayMode.Error(
            cause = OverlayMode.OverlayError.ScreenshotFailed("capture failed"),
            recoverable = true,
        )

        assertTrue(error.canTransitionTo(OverlayMode.Idle))
        assertTrue(error.canTransitionTo(OverlayMode.Select(requestId = "retry")))
        assertTrue(error.canTransitionTo(OverlayMode.Commenting(FixThisDraft())))
    }

    private inline fun assertInvalidTransition(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid transition to throw IllegalStateException")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("invalid overlay transition"))
        }
    }
}
