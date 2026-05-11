package io.beyondwin.fixthis.compose.sidekick.inspect

import android.graphics.Rect
import android.view.View
import androidx.compose.ui.semantics.getAllSemanticsNodes
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind

data class SemanticsInspectionResult(
    val roots: List<InspectedComposeRoot>,
    val errors: List<FixThisError> = emptyList(),
) {
    val mergedNodes: List<FixThisNode> = roots.flatMap { it.mergedNodes }
    val unmergedNodes: List<FixThisNode> = roots.flatMap { it.unmergedNodes }
}

data class InspectedComposeRoot(
    val rootIndex: Int,
    val boundsInWindow: FixThisRect,
    val mergedNodes: List<FixThisNode>,
    val unmergedNodes: List<FixThisNode>,
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
                errors = listOf(throwable.toFixThisError("ROOT_DISCOVERY_FAILED", "Failed to discover Compose roots")),
            )
        }

        val inspectedRoots = mutableListOf<InspectedComposeRoot>()
        val errors = mutableListOf<FixThisError>()

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
        val errors = mutableListOf<FixThisError>()
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
                boundsInWindow = root.boundsInWindow.toFixThisRect(),
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
        errors: MutableList<FixThisError>,
    ): List<FixThisNode> = try {
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
        errors += throwable.toFixThisError(
            code = "SEMANTICS_${treeKind.name}_INSPECTION_FAILED",
            fallbackMessage = "Failed to inspect ${treeKind.name.lowercase()} semantics tree",
            details = mapOf("rootIndex" to root.index.toString()),
        )
        emptyList()
    }

    private data class RootInspection(
        val root: InspectedComposeRoot,
        val errors: List<FixThisError>,
    )
}

private fun Throwable.toFixThisError(
    code: String,
    fallbackMessage: String,
    details: Map<String, String> = emptyMap(),
): FixThisError = FixThisError(
    code = code,
    message = message?.takeUnless { it.isBlank() } ?: fallbackMessage,
    details = details + mapOf("exception" to this::class.java.name),
)

private fun Rect.toFixThisRect(): FixThisRect = FixThisRect(left = left.toFloat(), top = top.toFloat(), right = right.toFloat(), bottom = bottom.toFloat())
