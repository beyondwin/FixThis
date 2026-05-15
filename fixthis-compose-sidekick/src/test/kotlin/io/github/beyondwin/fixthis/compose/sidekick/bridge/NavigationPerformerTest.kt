package io.github.beyondwin.fixthis.compose.sidekick.bridge

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NavigationPerformerTest {
    @Test
    fun tapRequiresCoordinatesInsideBounds() = runBlocking {
        val activity = measuredActivity(width = 100, height = 200)
        val touches = mutableListOf<RecordedTouch>()
        val performer = AndroidNavigationPerformer(
            activityProvider = { activity },
            touchEventDispatcher = recordingTouchDispatcher(touches),
        )

        val result = performer.perform(
            BridgeNavigationRequest(action = BridgeNavigationAction.TAP, x = 10f, y = 20f),
        )

        assertEquals(true, result.performed)
        assertEquals(BridgeNavigationAction.TAP, result.action)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), touches.map { it.action })
        assertEquals(10f, touches.single { it.action == MotionEvent.ACTION_DOWN }.x, FloatTolerance)
        assertEquals(20f, touches.single { it.action == MotionEvent.ACTION_DOWN }.y, FloatTolerance)
    }

    @Test
    fun tapRejectsCoordinatesOutsideBounds() = runBlocking {
        val activity = measuredActivity(width = 100, height = 200)
        val performer = AndroidNavigationPerformer(activityProvider = { activity })

        val error = runCatching {
            performer.perform(BridgeNavigationRequest(action = BridgeNavigationAction.TAP, x = 200f, y = 20f))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun swipeDefaultsDistanceToSixtyPercentOfShorterWindowSide() = runBlocking {
        val activity = measuredActivity(width = 200, height = 100)
        val touches = mutableListOf<RecordedTouch>()
        val performer = AndroidNavigationPerformer(
            activityProvider = { activity },
            touchEventDispatcher = recordingTouchDispatcher(touches),
        )

        val result = performer.perform(
            BridgeNavigationRequest(action = BridgeNavigationAction.SWIPE, direction = BridgeSwipeDirection.UP),
        )

        assertEquals(true, result.performed)
        assertEquals(BridgeNavigationAction.SWIPE, result.action)
        assertEquals(
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP),
            touches.map { it.action },
        )
        assertEquals(100f, touches[0].x, FloatTolerance)
        assertEquals(50f, touches[0].y, FloatTolerance)
        assertEquals(100f, touches[1].x, FloatTolerance)
        assertEquals(-10f, touches[1].y, FloatTolerance)
    }

    @Test
    fun swipeRejectsInvalidDistance() = runBlocking {
        val activity = measuredActivity(width = 100, height = 200)
        val performer = AndroidNavigationPerformer(activityProvider = { activity })

        val error = runCatching {
            performer.perform(
                BridgeNavigationRequest(
                    action = BridgeNavigationAction.SWIPE,
                    direction = BridgeSwipeDirection.DOWN,
                    distance = 0f,
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun measuredActivity(width: Int, height: Int): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val content = FrameLayout(activity)
        activity.setContentView(content)
        content.addView(FrameLayout(activity), ViewGroup.LayoutParams(width, height))
        val decorView = activity.window.decorView
        decorView.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decorView.layout(0, 0, width, height)
        return activity
    }

    private fun recordingTouchDispatcher(touches: MutableList<RecordedTouch>): (View, MotionEvent) -> Boolean = { _, event ->
        touches += RecordedTouch(event.actionMasked, event.x, event.y)
        true
    }

    private data class RecordedTouch(
        val action: Int,
        val x: Float,
        val y: Float,
    )

    private companion object {
        const val FloatTolerance = 0.001f
    }
}
