package io.github.pointpatch.compose.sidekick.overlay

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
import io.github.pointpatch.compose.overlay.OverlayMode
import io.github.pointpatch.compose.overlay.PointPatchCommentSheet
import io.github.pointpatch.compose.overlay.PointPatchHighlightLayer
import io.github.pointpatch.compose.overlay.PointPatchSelectionLayer
import io.github.pointpatch.compose.overlay.PointPatchToolbar
import io.github.pointpatch.compose.sidekick.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PointPatchOverlayHostLayout(context: Context) : FrameLayout(context) {
    private val overlayView = ComposeView(context)
    private val toolbarView = ComposeView(context)
    private val coroutineScope = MainScope()
    private val controller = (context as? Activity)?.let { activity ->
        PointPatchOverlayController(activity)
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
                    is OverlayMode.Exported -> Unit

                    is OverlayMode.Selecting -> PointPatchSelectionLayer(
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

                    is OverlayMode.ReviewingSelection -> PointPatchHighlightLayer(
                        draft = mode.draft,
                        modifier = Modifier.fillMaxSize(),
                        onScopeSelected = { candidate ->
                            coroutineScope.launch {
                                overlayController.selectScope(candidate)
                            }
                        },
                    )

                    is OverlayMode.Commenting -> {
                        PointPatchHighlightLayer(
                            draft = mode.draft,
                            modifier = Modifier.fillMaxSize(),
                            onScopeSelected = { candidate ->
                                coroutineScope.launch {
                                    overlayController.selectScope(candidate)
                                }
                            },
                        )
                        PointPatchCommentSheet(
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
            PointPatchToolbar(onSelectUi = { controller?.startSelection() })
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
