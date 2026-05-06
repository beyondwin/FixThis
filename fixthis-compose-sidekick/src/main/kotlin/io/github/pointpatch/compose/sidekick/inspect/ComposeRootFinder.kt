package io.github.pointpatch.compose.sidekick.inspect

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import io.github.pointpatch.compose.sidekick.overlay.isPointPatchOverlayHost

data class ComposeRootInfo(
    val view: View,
    val rootForTest: RootForTest,
    val boundsInWindow: Rect,
    val index: Int,
)

object ComposeRootFinder {
    fun findRoots(decorView: View): List<ComposeRootInfo> {
        val roots = mutableListOf<ComposeRootInfo>()
        visit(decorView, roots)
        return roots
    }

    private fun visit(view: View, roots: MutableList<ComposeRootInfo>) {
        if (view.isPointPatchOverlayHost()) {
            return
        }

        if (view is RootForTest && view.isDiscoverableRoot()) {
            roots += ComposeRootInfo(
                view = view,
                rootForTest = view,
                boundsInWindow = view.boundsInWindow(),
                index = roots.size,
            )
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                visit(view.getChildAt(index), roots)
            }
        }
    }
}

private fun View.isDiscoverableRoot(): Boolean =
    isAttachedToWindow &&
        visibility == View.VISIBLE &&
        isShown &&
        width > 0 &&
        height > 0

private fun View.boundsInWindow(): Rect {
    val location = IntArray(2)
    getLocationInWindow(location)
    return Rect(
        location[0],
        location[1],
        location[0] + width,
        location[1] + height,
    )
}
