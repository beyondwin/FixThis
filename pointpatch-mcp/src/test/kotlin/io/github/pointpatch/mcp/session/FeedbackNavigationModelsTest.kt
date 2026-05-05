package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.encodeToString

class FeedbackNavigationModelsTest {
    @Test
    fun tapNavigationRoundTrips() {
        val request = FeedbackNavigationRequest(action = FeedbackNavigationAction.TAP, x = 10f, y = 20f)

        val decoded = pointPatchJson.decodeFromString(
            FeedbackNavigationRequest.serializer(),
            pointPatchJson.encodeToString(request),
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
