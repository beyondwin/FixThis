package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BridgeNavigationRequest(
    val action: BridgeNavigationAction,
    val x: Float? = null,
    val y: Float? = null,
    val direction: BridgeSwipeDirection? = null,
    val distance: Float? = null,
)

@Serializable
enum class BridgeNavigationAction {
    @SerialName("back")
    BACK,

    @SerialName("tap")
    TAP,

    @SerialName("swipe")
    SWIPE,
}

@Serializable
enum class BridgeSwipeDirection {
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
data class BridgeNavigationResult(
    val performed: Boolean,
    val action: BridgeNavigationAction,
    val activity: String? = null,
    val message: String? = null,
)

interface NavigationPerformer {
    suspend fun perform(request: BridgeNavigationRequest): BridgeNavigationResult
}

class AndroidNavigationPerformer(
    private val activityProvider: () -> Activity?,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val touchEventDispatcher: (View, MotionEvent) -> Boolean = { view, event -> view.dispatchTouchEvent(event) },
    private val keyEventDispatcher: (View, KeyEvent) -> Boolean = { view, event -> view.dispatchKeyEvent(event) },
) : NavigationPerformer {
    override suspend fun perform(request: BridgeNavigationRequest): BridgeNavigationResult = withContext(mainDispatcher) {
        val activity = activityProvider()
            ?: error("No resumed Activity is available for navigation")
        val decorView = activity.window?.decorView
            ?: error("Current Activity has no decorView")
        val width = decorView.width
        val height = decorView.height
        require(width > 0 && height > 0) { "Current Activity decorView has no bounds" }

        when (request.action) {
            BridgeNavigationAction.BACK -> dispatchBack(decorView)
            BridgeNavigationAction.TAP -> {
                val x = request.x ?: error("Tap navigation requires x")
                val y = request.y ?: error("Tap navigation requires y")
                require(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x < width && y < height) {
                    "Tap coordinates are outside the current window"
                }
                dispatchTap(decorView, x, y)
            }
            BridgeNavigationAction.SWIPE -> {
                val direction = request.direction ?: error("Swipe navigation requires direction")
                val distance = request.distance ?: (minOf(width, height) * DefaultSwipeDistanceFraction)
                require(distance.isFinite() && distance > 0f) { "Swipe distance must be greater than 0" }
                dispatchSwipe(
                    view = decorView,
                    startX = width / 2f,
                    startY = height / 2f,
                    direction = direction,
                    distance = distance,
                )
            }
        }

        BridgeNavigationResult(
            performed = true,
            action = request.action,
            activity = activity::class.java.name,
        )
    }

    private fun dispatchBack(view: View) {
        val now = SystemClock.uptimeMillis()
        keyEventDispatcher(view, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
        keyEventDispatcher(view, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
    }

    private fun dispatchTap(view: View, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        dispatchTouchEvent(view, MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        dispatchTouchEvent(view, MotionEvent.obtain(downTime, downTime + TapDurationMillis, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun dispatchSwipe(
        view: View,
        startX: Float,
        startY: Float,
        direction: BridgeSwipeDirection,
        distance: Float,
    ) {
        val endX: Float
        val endY: Float
        when (direction) {
            BridgeSwipeDirection.UP -> {
                endX = startX
                endY = startY - distance
            }
            BridgeSwipeDirection.DOWN -> {
                endX = startX
                endY = startY + distance
            }
            BridgeSwipeDirection.LEFT -> {
                endX = startX - distance
                endY = startY
            }
            BridgeSwipeDirection.RIGHT -> {
                endX = startX + distance
                endY = startY
            }
        }

        val downTime = SystemClock.uptimeMillis()
        dispatchTouchEvent(view, MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0))
        dispatchTouchEvent(view, MotionEvent.obtain(downTime, downTime + SwipeMoveMillis, MotionEvent.ACTION_MOVE, endX, endY, 0))
        dispatchTouchEvent(view, MotionEvent.obtain(downTime, downTime + SwipeUpMillis, MotionEvent.ACTION_UP, endX, endY, 0))
    }

    private fun dispatchTouchEvent(view: View, event: MotionEvent) {
        try {
            touchEventDispatcher(view, event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val DefaultSwipeDistanceFraction = 0.6f
        const val TapDurationMillis = 50L
        const val SwipeMoveMillis = 80L
        const val SwipeUpMillis = 160L
    }
}
