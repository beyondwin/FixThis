package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetBoundaryContextFormatterTest {
    @Test
    fun compactLineUsesNearestNonSensitiveComposeContextForInteropRisk() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "native-chart-host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(0f, 80f, 320f, 260f),
                ),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertEquals(
            "boundaryContext: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(0.0,80.0)-(320.0,260.0)",
            line,
        )
    }

    @Test
    fun preciseLinesTellAgentThatContextIsNotExactOwnership() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "native-chart-host",
                    text = listOf("Revenue"),
                    testTag = "comp:NativeChartHost:chart",
                    bounds = FixThisRect(0f, 80f, 320f, 260f),
                ),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        assertTrue(
            lines.contains(
                "- Boundary context: nearest Compose context tag=\"comp:NativeChartHost:chart\"; text=\"Revenue\"; box=`0.0,80.0,320.0,260.0`.",
            ),
        )
        assertTrue(
            lines.contains(
                "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
            ),
        )
    }

    @Test
    fun sensitiveNearbyNodesDoNotLeakTextOrEditableContent() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                sensitiveNode(
                    uid = "password",
                    text = listOf("secret-token"),
                    editableText = "secret-token",
                    testTag = "comp:LoginField:password",
                ),
            ),
        )

        val precise = TargetBoundaryContextFormatter.preciseLines(item).joinToString("\n")

        assertTrue(precise.contains("tag=\"comp:LoginField:password\""))
        assertTrue(!precise.contains("secret-token"), precise)
    }

    @Test
    fun nonInteropItemDoesNotRenderContext() {
        val item = AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 80f)),
            nearbyNodes = listOf(node(uid = "host", testTag = "comp:Host:root")),
            comment = "Tighten this area",
        )

        assertNull(TargetBoundaryContextFormatter.compactLine(item))
        assertEquals(emptyList(), TargetBoundaryContextFormatter.preciseLines(item))
    }

    private fun interopAreaItem(nearbyNodes: List<FixThisNode>): AnnotationDto = AnnotationDto(
        itemId = "item",
        screenId = "screen",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
        nearbyNodes = nearbyNodes,
        comment = "Fix the native chart spacing",
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
        ),
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        testTag: String? = null,
        role: String? = null,
        bounds: FixThisRect = FixThisRect(0f, 0f, 120f, 80f),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = bounds,
        text = text,
        role = role,
        testTag = testTag,
    )

    private fun sensitiveNode(
        uid: String,
        text: List<String>,
        editableText: String?,
        testTag: String?,
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = uid.hashCode(),
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 120f, 80f),
        text = text,
        editableText = editableText,
        testTag = testTag,
        isSensitive = true,
    )
}
