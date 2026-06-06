package io.github.beyondwin.fixthis.mcp.fixture

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeTargetResolverTest {
    @Test
    fun resolvesByTextAndRole() {
        val screen = screenWith(
            node(uid = "compose", text = listOf("Compose"), role = "Button"),
            node(uid = "search", text = listOf("Search"), role = "Button"),
        )

        val resolved = RuntimeTargetResolver.resolve(screen, RuntimeTargetSelector(text = "Compose", role = "Button"))

        assertEquals("compose", resolved.uid)
    }

    @Test
    fun rejectsAmbiguousSelectors() {
        val screen = screenWith(
            node(uid = "compose-1", text = listOf("Compose"), role = "Button"),
            node(uid = "compose-2", text = listOf("Compose"), role = "Button"),
        )

        val error = assertFailsWith<RuntimeTargetResolutionException> {
            RuntimeTargetResolver.resolve(screen, RuntimeTargetSelector(text = "Compose", role = "Button"))
        }

        assertEquals("target_ambiguous", error.code)
    }

    @Test
    fun ignoresZeroSizeNodesWhenResolvingRuntimeTargets() {
        val screen = screenWith(
            node(uid = "hidden-compose", text = listOf("Compose"), role = "Button", bounds = FixThisRect(0f, 0f, 0f, 0f)),
            node(uid = "visible-compose", text = listOf("Compose"), role = "Button"),
        )

        val resolved = RuntimeTargetResolver.resolve(screen, RuntimeTargetSelector(text = "Compose", role = "Button"))

        assertEquals("visible-compose", resolved.uid)
    }

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 1L,
        displayName = "Runtime fixture",
        roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 400f, 800f), mergedNodes = nodes.toList())),
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        role: String? = null,
        bounds: FixThisRect = FixThisRect(10f, 20f, 120f, 80f),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
    )
}
