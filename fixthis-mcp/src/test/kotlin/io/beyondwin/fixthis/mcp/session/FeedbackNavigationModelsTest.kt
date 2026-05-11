package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.cli.fixThisJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeedbackNavigationModelsTest {
    @Test
    fun tapNavigationRoundTrips() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.TAP, x = 10f, y = 20f)

        val decoded = fixThisJson.decodeFromString(
            FeedbackNavigationRequest.serializer(),
            fixThisJson.encodeToString(request),
        )

        assertEquals(request, decoded)
    }

    @Test
    fun requestValidationRejectsMissingTapCoordinates() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.TAP)

        assertFailsWith<IllegalArgumentException> {
            request.validate()
        }
    }

    @Test
    fun requestValidationRejectsMissingSwipeDirection() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.SWIPE)

        assertFailsWith<IllegalArgumentException> {
            request.validate()
        }
    }

    @Test
    fun requestValidationRejectsNonPositiveSwipeDistance() {
        val request = FeedbackNavigationRequest(
            action = FeedbackNavigationAction.SWIPE,
            direction = FeedbackSwipeDirection.UP,
            distance = 0f,
        )

        assertFailsWith<IllegalArgumentException> {
            request.validate()
        }
    }

    @Test
    fun requestValidationAcceptsBackWithoutCoordinates() {
        val request = FeedbackNavigationRequest(
            action = FeedbackNavigationAction.BACK,
            x = null,
            y = null,
            direction = null,
            distance = null,
        )

        request.validate()
    }
}
