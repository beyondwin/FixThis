package io.github.beyondwin.fixthis.mcp.session.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FeedbackNavigationRequest(
    val action: FeedbackNavigationAction,
    val x: Float? = null,
    val y: Float? = null,
    val direction: FeedbackSwipeDirection? = null,
    val distance: Float? = null,
    val captureAfter: Boolean = true,
) {
    fun validate() {
        when (action) {
            FeedbackNavigationAction.BACK -> Unit
            FeedbackNavigationAction.TAP -> {
                require(x != null && y != null) { "Tap navigation requires x and y" }
                require(x.isFinite() && y.isFinite()) { "Tap coordinates must be finite" }
            }
            FeedbackNavigationAction.SWIPE -> {
                require(direction != null) { "Swipe navigation requires direction" }
                distance?.let { require(it.isFinite() && it > 0f) { "Swipe distance must be greater than 0" } }
            }
        }
    }
}

@Serializable
enum class FeedbackNavigationAction {
    @SerialName("back")
    BACK,

    @SerialName("tap")
    TAP,

    @SerialName("swipe")
    SWIPE,
}

@Serializable
enum class FeedbackSwipeDirection {
    @SerialName("up")
    UP,

    @SerialName("down")
    DOWN,

    @SerialName("left")
    LEFT,

    @SerialName("right")
    RIGHT,
}

@Serializable
data class FeedbackNavigationResult(
    val performed: Boolean,
    val action: FeedbackNavigationAction,
    val activityName: String? = null,
    val message: String? = null,
    val screen: SnapshotDto? = null,
    val captureError: String? = null,
)
