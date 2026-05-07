package io.beyondwin.fixthis.compose.sidekick.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.beyondwin.fixthis.compose.sidekick.R
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeConnectionState
import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime

internal class FixThisConnectionStatusHostLayout(
    context: Context,
    private val connectionState: BridgeConnectionState = FixThisBridgeRuntime.connectionState,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val statusView = TextView(context)
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, RefreshIntervalMillis)
        }
    }

    init {
        markAsOverlayHost(this)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        statusView.gravity = Gravity.CENTER
        statusView.setTextColor(Color.WHITE)
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.textSize = 12f
        val horizontalPadding = resources.displayMetrics.density.times(10).toInt()
        val verticalPadding = resources.displayMetrics.density.times(6).toInt()
        statusView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        addView(
            statusView,
            LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        updateStatus()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updateRunnable)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(updateRunnable)
        super.onDetachedFromWindow()
    }

    private fun updateStatus() {
        val connected = connectionState.isConnected()
        statusView.text = if (connected) "MCP connected" else "MCP waiting"
        statusView.contentDescription = if (connected) {
            "FixThis MCP browser connected"
        } else {
            "FixThis MCP browser waiting"
        }
        statusView.background = pillBackground(
            if (connected) Color.rgb(28, 114, 74) else Color.rgb(82, 82, 91),
        )
    }

    private fun pillBackground(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density.times(999).toFloat()
            setColor(color)
        }

    companion object {
        const val HOST_TAG = "io.beyondwin.fixthis.compose.overlay.HOST"
        private const val RefreshIntervalMillis = 1_000L

        fun attachTo(activity: Activity) {
            val decor = activity.window.decorView as? ViewGroup ?: return
            if (decor.findFixThisOverlayHosts().isNotEmpty()) return
            decor.addView(
                FixThisConnectionStatusHostLayout(activity),
                LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    val margin = activity.resources.displayMetrics.density.times(16).toInt()
                    topMargin = margin
                    marginEnd = margin
                },
            )
        }

        fun markAsOverlayHost(view: View) {
            view.setTag(R.id.fixthis_overlay_host, true)
            view.tag = HOST_TAG
        }
    }
}

internal fun View.findFixThisOverlayHosts(): List<View> =
    if (this is ViewGroup) {
        childrenDepthFirst().filter { it.isFixThisOverlayHost() }.toList()
    } else {
        emptyList()
    }

internal fun View.isFixThisOverlayHost(): Boolean =
    getTag(R.id.fixthis_overlay_host) == true || tag == FixThisConnectionStatusHostLayout.HOST_TAG

private fun ViewGroup.childrenDepthFirst(): Sequence<View> = sequence {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        yield(child)
        if (child is ViewGroup) yieldAll(child.childrenDepthFirst())
    }
}
