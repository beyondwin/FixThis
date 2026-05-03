package io.github.pointpatch.compose.sidekick.overlay

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.pointpatch.compose.overlay.PointPatchToolbar
import io.github.pointpatch.compose.sidekick.R

class PointPatchOverlayHostLayout(context: Context) : FrameLayout(context) {
    private val toolbarView = ComposeView(context)
    private var handlingToolbarGesture = false

    init {
        markAsOverlayHost(this)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        toolbarView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        toolbarView.setContent {
            PointPatchToolbar(onSelectUi = {})
        }

        addView(
            toolbarView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                val margin = resources.displayMetrics.density.times(16).toInt()
                topMargin = margin
                marginEnd = margin
            },
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            handlingToolbarGesture = event.isInside(toolbarView)
        }

        if (!handlingToolbarGesture) {
            return false
        }

        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            handlingToolbarGesture = false
        }
        return handled
    }

    companion object {
        const val HOST_TAG = "io.github.pointpatch.compose.overlay.HOST"

        internal fun markAsOverlayHost(view: View) {
            view.tag = HOST_TAG
            view.setTag(R.id.pointpatch_overlay_host, true)
        }

        fun attachTo(activity: Activity) {
            val decorView = activity.window?.decorView as? ViewGroup ?: return
            if (decorView.findPointPatchOverlayHosts().isNotEmpty()) {
                return
            }

            decorView.addView(
                PointPatchOverlayHostLayout(activity),
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }
}

internal fun View.isPointPatchOverlayHost(): Boolean =
    this is PointPatchOverlayHostLayout ||
        getTag(R.id.pointpatch_overlay_host) == true ||
        tag == PointPatchOverlayHostLayout.HOST_TAG

internal fun View.findPointPatchOverlayHosts(): List<View> {
    val hosts = mutableListOf<View>()
    collectPointPatchOverlayHosts(hosts)
    return hosts
}

private fun View.collectPointPatchOverlayHosts(hosts: MutableList<View>) {
    if (isPointPatchOverlayHost()) {
        hosts += this
        return
    }

    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).collectPointPatchOverlayHosts(hosts)
        }
    }
}

private fun MotionEvent.isInside(view: View): Boolean =
    x >= view.left &&
        x < view.right &&
        y >= view.top &&
        y < view.bottom
