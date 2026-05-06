package io.beyondwin.fixthis.compose.sidekick.overlay

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.beyondwin.fixthis.compose.overlay.OverlayMode
import io.beyondwin.fixthis.compose.overlay.FixThisCommentSheet
import io.beyondwin.fixthis.compose.overlay.FixThisHighlightLayer
import io.beyondwin.fixthis.compose.overlay.FixThisSelectionLayer
import io.beyondwin.fixthis.compose.overlay.FixThisToolbar
import io.beyondwin.fixthis.compose.sidekick.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class FixThisOverlayHostLayout(context: Context) : FrameLayout(context) {
    private val overlayView = ComposeView(context)
    private val toolbarView = ComposeView(context)
    private val coroutineScope = MainScope()
    internal val controller = (context as? Activity)?.let { activity ->
        FixThisOverlayController(activity)
    }
    private var handlingToolbarGesture = false

    init {
        markAsOverlayHost(this)
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false

        overlayView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        overlayView.setContent {
            val overlayController = controller ?: return@setContent
            val mode = overlayController.mode
            Box(modifier = Modifier.fillMaxSize()) {
                when (mode) {
                    OverlayMode.Idle,
                    OverlayMode.MenuOpen,
                    is OverlayMode.Loading,
                    is OverlayMode.Error,
                    is OverlayMode.Exported -> Unit

                    is OverlayMode.Select -> FixThisSelectionLayer(
                        mode = mode,
                        modifier = Modifier.fillMaxSize(),
                        onTap = { xInWindow, yInWindow ->
                            coroutineScope.launch {
                                overlayController.captureTap(xInWindow, yInWindow)
                            }
                        },
                        onAreaSelected = { left, top, right, bottom ->
                            coroutineScope.launch {
                                overlayController.captureArea(left, top, right, bottom)
                            }
                        },
                        onCancel = overlayController::cancel,
                    )

                    is OverlayMode.ReviewingSelection -> FixThisHighlightLayer(
                        draft = mode.draft,
                        modifier = Modifier.fillMaxSize(),
                        onScopeSelected = { candidate ->
                            coroutineScope.launch {
                                overlayController.selectScope(candidate)
                            }
                        },
                    )

                    is OverlayMode.Commenting -> {
                        FixThisHighlightLayer(
                            draft = mode.draft,
                            modifier = Modifier.fillMaxSize(),
                            onScopeSelected = { candidate ->
                                coroutineScope.launch {
                                    overlayController.selectScope(candidate)
                                }
                            },
                        )
                        FixThisCommentSheet(
                            draft = mode.draft,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onCommentChanged = overlayController::updateComment,
                            onScopeSelected = { candidate ->
                                coroutineScope.launch {
                                    overlayController.selectScope(candidate)
                                }
                            },
                            onCopyForAi = { overlayController.copyMarkdown() },
                            onCopyJson = { overlayController.copyJson() },
                            onShare = {
                                coroutineScope.launch {
                                    overlayController.share()
                                }
                            },
                            onSendToAiAgent = { overlayController.copyMarkdown() },
                        )
                    }
                }
            }
        }

        addView(
            overlayView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        toolbarView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        toolbarView.setContent {
            FixThisToolbar(onSelectUi = { controller?.startSelection() })
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

    override fun onDetachedFromWindow() {
        coroutineScope.cancel()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (controller?.shouldHandleOverlayTouch == true) {
            return super.dispatchTouchEvent(event)
        }

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
        const val HOST_TAG = "io.beyondwin.fixthis.compose.overlay.HOST"

        internal fun markAsOverlayHost(view: View) {
            view.tag = HOST_TAG
            view.setTag(R.id.fixthis_overlay_host, true)
        }

        fun attachTo(activity: Activity) {
            val decorView = activity.window?.decorView as? ViewGroup ?: return
            if (decorView.findFixThisOverlayHosts().isNotEmpty()) {
                return
            }

            decorView.addView(
                FixThisOverlayHostLayout(activity),
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        internal fun controllerFor(activity: Activity): FixThisOverlayController? {
            val decorView = activity.window?.decorView ?: return null
            return decorView.findFixThisOverlayHosts()
                .filterIsInstance<FixThisOverlayHostLayout>()
                .firstNotNullOfOrNull { host -> host.controller }
        }
    }
}

internal fun View.isFixThisOverlayHost(): Boolean =
    this is FixThisOverlayHostLayout ||
        getTag(R.id.fixthis_overlay_host) == true ||
        tag == FixThisOverlayHostLayout.HOST_TAG

internal fun View.findFixThisOverlayHosts(): List<View> {
    val hosts = mutableListOf<View>()
    collectFixThisOverlayHosts(hosts)
    return hosts
}

private fun View.collectFixThisOverlayHosts(hosts: MutableList<View>) {
    if (isFixThisOverlayHost()) {
        hosts += this
        return
    }

    if (this is ViewGroup) {
        for (index in 0 until childCount) {
            getChildAt(index).collectFixThisOverlayHosts(hosts)
        }
    }
}

private fun MotionEvent.isInside(view: View): Boolean =
    x >= view.left &&
        x < view.right &&
        y >= view.top &&
        y < view.bottom
