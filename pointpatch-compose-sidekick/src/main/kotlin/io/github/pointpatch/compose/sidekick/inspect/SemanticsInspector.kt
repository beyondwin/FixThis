package io.github.pointpatch.compose.sidekick.inspect

import android.graphics.Rect
import android.view.View
import androidx.compose.ui.semantics.getAllSemanticsNodes
import io.github.pointpatch.compose.core.model.PointPatchError
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind

data class SemanticsInspectionResult(
    val roots: List<InspectedComposeRoot>,
    val errors: List<PointPatchError> = emptyList(),
) {
    val mergedNodes: List<PointPatchNode> = roots.flatMap { it.mergedNodes }
    val unmergedNodes: List<PointPatchNode> = roots.flatMap { it.unmergedNodes }
}

data class InspectedComposeRoot(
    val rootIndex: Int,
    val boundsInWindow: PointPatchRect,
    val mergedNodes: List<PointPatchNode>,
    val unmergedNodes: List<PointPatchNode>,
)

data class SemanticsInspectionOptions(
    val includeMergedTree: Boolean = true,
    val includeUnmergedTree: Boolean = true,
    val redactEditableText: Boolean = true,
    val includeRawProperties: Boolean = false,
)

class SemanticsInspector(
    private val options: SemanticsInspectionOptions = SemanticsInspectionOptions(),
) {
    fun inspect(decorView: View): SemanticsInspectionResult {
        val roots = try {
            ComposeRootFinder.findRoots(decorView)
        } catch (throwable: Throwable) {
            return SemanticsInspectionResult(
                roots = emptyList(),
                errors = listOf(throwable.toPointPatchError("ROOT_DISCOVERY_FAILED", "Failed to discover Compose roots")),
            )
        }

        val inspectedRoots = mutableListOf<InspectedComposeRoot>()
        val errors = mutableListOf<PointPatchError>()

        roots.forEach { root ->
            val rootResult = inspectRoot(root)
            inspectedRoots += rootResult.root
            errors += rootResult.errors
        }

        return SemanticsInspectionResult(
            roots = inspectedRoots,
            errors = errors,
        )
    }

    private fun inspectRoot(root: ComposeRootInfo): RootInspection {
        val errors = mutableListOf<PointPatchError>()
        val mergedNodes = if (options.includeMergedTree) {
            inspectTree(root, TreeKind.MERGED, mergingEnabled = true, errors = errors)
        } else {
            emptyList()
        }
        val unmergedNodes = if (options.includeUnmergedTree) {
            inspectTree(root, TreeKind.UNMERGED, mergingEnabled = false, errors = errors)
        } else {
            emptyList()
        }

        return RootInspection(
            root = InspectedComposeRoot(
                rootIndex = root.index,
                boundsInWindow = root.boundsInWindow.toPointPatchRect(),
                mergedNodes = mergedNodes,
                unmergedNodes = unmergedNodes,
            ),
            errors = errors,
        )
    }

    private fun inspectTree(
        root: ComposeRootInfo,
        treeKind: TreeKind,
        mergingEnabled: Boolean,
        errors: MutableList<PointPatchError>,
    ): List<PointPatchNode> =
        try {
            root.rootForTest.semanticsOwner
                .getAllSemanticsNodes(
                    mergingEnabled = mergingEnabled,
                    skipDeactivatedNodes = true,
                )
                .map { node ->
                    SemanticsNodeMapper(
                        redactEditableText = options.redactEditableText,
                    ).map(
                        root = root,
                        treeKind = treeKind,
                        node = node,
                    ).let { mapped ->
                        if (options.includeRawProperties) mapped else mapped.copy(rawProperties = emptyMap())
                    }
                }
        } catch (throwable: Throwable) {
            errors += throwable.toPointPatchError(
                code = "SEMANTICS_${treeKind.name}_INSPECTION_FAILED",
                fallbackMessage = "Failed to inspect ${treeKind.name.lowercase()} semantics tree",
                details = mapOf("rootIndex" to root.index.toString()),
            )
            emptyList()
        }

    private data class RootInspection(
        val root: InspectedComposeRoot,
        val errors: List<PointPatchError>,
    )
}

private fun Throwable.toPointPatchError(
    code: String,
    fallbackMessage: String,
    details: Map<String, String> = emptyMap(),
): PointPatchError =
    PointPatchError(
        code = code,
        message = message?.takeUnless { it.isBlank() } ?: fallbackMessage,
        details = details + mapOf("exception" to this::class.java.name),
    )

private fun Rect.toPointPatchRect(): PointPatchRect =
    PointPatchRect(left = left.toFloat(), top = top.toFloat(), right = right.toFloat(), bottom = bottom.toFloat())
