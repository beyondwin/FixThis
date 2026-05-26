package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.mcp.session.SnapshotDto

class RuntimeTargetResolutionException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

object RuntimeTargetResolver {
    fun resolve(screen: SnapshotDto, selector: RuntimeTargetSelector): FixThisNode {
        val matches = screen.roots
            .flatMap { it.mergedNodes }
            .filter { node -> node.boundsInWindow.width > 0f && node.boundsInWindow.height > 0f }
            .filter { node -> selector.matches(node) }
            .sortedWith(
                compareBy<FixThisNode> { it.rootIndex }
                    .thenBy { it.boundsInWindow.top }
                    .thenBy { it.boundsInWindow.left }
                    .thenBy { it.uid },
            )

        return when (matches.size) {
            0 -> throw RuntimeTargetResolutionException("target_not_found", "No runtime target matched selector $selector")
            1 -> matches.single()
            else -> throw RuntimeTargetResolutionException(
                "target_ambiguous",
                "Runtime target selector matched ${matches.size} nodes: $selector",
            )
        }
    }

    private fun RuntimeTargetSelector.matches(node: FixThisNode): Boolean = text.matchesValue(node.text) &&
        testTag.matchesNullable(node.testTag) &&
        contentDescription.matchesValue(node.contentDescription) &&
        role.matchesNullable(node.role)

    private fun String?.matchesValue(values: List<String>): Boolean = this == null || values.any { value -> value == this }

    private fun String?.matchesNullable(value: String?): Boolean = this == null || value == this
}
