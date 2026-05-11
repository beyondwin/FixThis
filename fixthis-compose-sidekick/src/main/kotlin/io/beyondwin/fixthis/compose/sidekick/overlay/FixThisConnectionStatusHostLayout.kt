package io.beyondwin.fixthis.compose.sidekick.overlay

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.beyondwin.fixthis.compose.sidekick.R
import io.beyondwin.fixthis.compose.sidekick.bridge.BridgeConnectionState
import io.beyondwin.fixthis.compose.sidekick.bridge.FixThisBridgeRuntime

internal class FixThisConnectionStatusHostLayout(
    context: Context,
    private val connectionState: BridgeConnectionState = FixThisBridgeRuntime.connectionState,
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val pillView = LinearLayout(context)
    private val statusIcon = StatusIndicatorView(context)
    private val statusView = TextView(context)
    private var lastConnected: Boolean? = null
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

        pillView.orientation = LinearLayout.HORIZONTAL
        pillView.gravity = Gravity.CENTER
        val horizontalPadding = resources.displayMetrics.density.times(10).toInt()
        val verticalPadding = resources.displayMetrics.density.times(6).toInt()
        pillView.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)

        addStatusIcon()

        statusView.gravity = Gravity.CENTER
        statusView.setTextColor(Color.rgb(18, 18, 18))
        statusView.typeface = Typeface.DEFAULT_BOLD
        statusView.textSize = 14f
        pillView.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            pillView,
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
        if (lastConnected == connected) return
        lastConnected = connected

        statusView.text = if (connected) "MCP connected" else "MCP waiting"
        statusView.contentDescription = if (connected) {
            "FixThis MCP browser connected"
        } else {
            "FixThis MCP browser waiting"
        }
        pillView.background = pillBackground(
            if (connected) translucentColor(196, 229, 104) else translucentColor(228, 228, 231),
        )
        statusIcon.setConnected(connected)
    }

    private fun translucentColor(red: Int, green: Int, blue: Int): Int = Color.argb(PillBackgroundAlpha, red, green, blue)

    private fun pillBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = resources.displayMetrics.density.times(999).toFloat()
        setColor(color)
    }

    private fun addStatusIcon() {
        val size = resources.displayMetrics.density.times(16).toInt()
        pillView.addView(
            statusIcon,
            LinearLayout.LayoutParams(size, size).apply {
                marginEnd = resources.displayMetrics.density.times(8).toInt()
            },
        )
    }

    companion object {
        const val HOST_TAG = "io.beyondwin.fixthis.compose.overlay.HOST"
        private const val RefreshIntervalMillis = 1_000L
        private const val HorizontalMarginDp = 16
        private const val PillBackgroundAlpha = 170

        fun attachTo(activity: Activity) {
            val decor = activity.window.decorView as? ViewGroup ?: return
            if (decor.attachedStatusWindowHandle() != null) return
            if (decor.findFixThisOverlayHosts().isNotEmpty()) return

            val windowToken = decor.windowToken
            if (windowToken == null) {
                decor.post { attachTo(activity) }
                return
            }
            val windowManager = activity.getSystemService(WindowManager::class.java)
            val host = FixThisConnectionStatusHostLayout(activity)
            val layoutParams = WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                token = windowToken
                x = activity.dp(HorizontalMarginDp)
                y = statusSafeTopMarginPx(
                    activity,
                    activity.statusBarInsetPx(),
                )
                setTitle("FixThis MCP status")
            }

            windowManager.addView(host, layoutParams)
            decor.setTag(
                R.id.fixthis_connection_status_window_handle,
                StatusWindowHandle(windowManager, host),
            )
        }

        fun detachFrom(activity: Activity) {
            val decor = activity.window.decorView as? ViewGroup ?: return
            val handle = decor.attachedStatusWindowHandle() ?: return
            decor.setTag(R.id.fixthis_connection_status_window_handle, null)
            runCatching {
                handle.windowManager.removeViewImmediate(handle.host)
            }
        }

        fun markAsOverlayHost(view: View) {
            view.setTag(R.id.fixthis_overlay_host, true)
            view.tag = HOST_TAG
        }
    }
}

private class StatusIndicatorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PulseDurationMillis
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { animation ->
            pulseProgress = animation.animatedValue as Float
            invalidate()
        }
    }
    private var connected = false
    private var pulseProgress = 0f

    init {
        setWillNotDraw(false)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        updatePulseAnimator()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updatePulseAnimator()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        pulseProgress = 0f
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = width.coerceAtMost(height) / 2f
        if (radius <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        if (connected) {
            paint.color = Color.argb(
                (PulseMaxAlpha * (1f - pulseProgress)).toInt(),
                82,
                98,
                46,
            )
            canvas.drawCircle(
                centerX,
                centerY,
                radius * (0.72f + 0.28f * pulseProgress),
                paint,
            )
        }
        paint.color = if (connected) Color.rgb(82, 98, 46) else Color.rgb(113, 113, 122)
        canvas.drawCircle(centerX, centerY, radius * 0.62f, paint)
    }

    @Suppress("unused")
    private fun isPulseRunningForTest(): Boolean = pulseAnimator.isRunning

    private fun updatePulseAnimator() {
        if (connected && isAttachedToWindow) {
            if (!pulseAnimator.isStarted) pulseAnimator.start()
        } else {
            pulseAnimator.cancel()
            pulseProgress = 0f
        }
    }

    companion object {
        private const val PulseDurationMillis = 900L
        private const val PulseMaxAlpha = 88
    }
}

private data class StatusWindowHandle(
    val windowManager: WindowManager,
    val host: View,
)

private fun View.attachedStatusWindowHandle(): StatusWindowHandle? = getTag(R.id.fixthis_connection_status_window_handle) as? StatusWindowHandle

private fun Activity.statusBarInsetPx(): Int? {
    val insets = window.decorView.rootWindowInsets ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.statusBars()).top
    } else {
        @Suppress("DEPRECATION")
        insets.systemWindowInsetTop
    }
}

internal fun statusSafeTopMarginPx(context: Context, statusBarInsetPx: Int?): Int {
    val statusBarHeight = statusBarInsetPx?.takeIf { it > 0 } ?: context.statusBarHeightPx()
    return statusBarHeight + context.dp(TopStatusBarGapDp)
}

private fun Context.statusBarHeightPx(): Int {
    val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
    val resourceHeight = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    return resourceHeight.takeIf { it > 0 } ?: dp(DefaultStatusBarHeightDp)
}

private fun Context.dp(value: Int): Int = resources.displayMetrics.density.times(value).toInt()

private const val DefaultStatusBarHeightDp = 24
private const val TopStatusBarGapDp = 8

internal fun View.findFixThisOverlayHosts(): List<View> = if (this is ViewGroup) {
    childrenDepthFirst().filter { it.isFixThisOverlayHost() }.toList()
} else {
    emptyList()
}

internal fun View.isFixThisOverlayHost(): Boolean = getTag(R.id.fixthis_overlay_host) == true || tag == FixThisConnectionStatusHostLayout.HOST_TAG

private fun ViewGroup.childrenDepthFirst(): Sequence<View> = sequence {
    for (index in 0 until childCount) {
        val child = getChildAt(index)
        yield(child)
        if (child is ViewGroup) yieldAll(child.childrenDepthFirst())
    }
}
