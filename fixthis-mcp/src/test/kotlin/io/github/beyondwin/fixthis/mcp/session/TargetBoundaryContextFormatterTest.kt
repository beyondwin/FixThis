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
            "boundaryHost: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(0.0,80.0)-(320.0,260.0)",
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
                "- Boundary host: tag=\"comp:NativeChartHost:chart\"; text=\"Revenue\"; box=`0.0,80.0,320.0,260.0`.",
            ),
        )
        assertTrue(
            lines.contains(
                "- Boundary context note: this context helps locate the host; it does not prove Compose owns the selected pixels.",
            ),
        )
    }

    @Test
    fun preciseLinesIncludeUpToThreeRankedContextNodes() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(uid = "header", testTag = "comp:Header:bar", bounds = FixThisRect(0f, 0f, 100f, 40f)),
                node(uid = "card", testTag = "comp:Card:body", bounds = FixThisRect(0f, 40f, 100f, 120f)),
                node(uid = "footer", testTag = "comp:Footer:cta", bounds = FixThisRect(0f, 120f, 100f, 200f)),
                node(uid = "extra", testTag = "comp:Extra:zzz", bounds = FixThisRect(0f, 200f, 100f, 320f)),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        val boundaryRows = lines.filter {
            it.startsWith("- Boundary ") && !it.startsWith("- Boundary context note:")
        }
        assertEquals(3, boundaryRows.size)
    }

    @Test
    fun preciseLinesRankSmallerCompContextNodesFirst() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(uid = "footer", testTag = "comp:Footer:cta", bounds = FixThisRect(0f, 0f, 30f, 200f)),
                node(uid = "header", testTag = "comp:Header:bar", bounds = FixThisRect(0f, 0f, 30f, 40f)),
                node(uid = "card", testTag = "comp:Card:body", bounds = FixThisRect(0f, 0f, 30f, 80f)),
            ),
        )

        val contextLines = TargetBoundaryContextFormatter.preciseLines(item)
            .filter { it.contains("Boundary context:") }

        assertTrue(contextLines[0].contains("comp:Header:bar"), contextLines.toString())
        assertTrue(contextLines[1].contains("comp:Card:body"), contextLines.toString())
        assertTrue(contextLines[2].contains("comp:Footer:cta"), contextLines.toString())
    }

    @Test
    fun nonInteropSelectionStillProducesNoContext() {
        val item = AnnotationDto(
            itemId = "item",
            screenId = "screen",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 100f, 80f)),
            nearbyNodes = listOf(node(uid = "header", testTag = "comp:Header:bar")),
            comment = "Tighten this area",
        )

        assertTrue(TargetBoundaryContextFormatter.preciseLines(item).isEmpty())
    }

    @Test
    fun compactLineIncludesRankedContextNodes() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(uid = "footer", testTag = "comp:Footer:cta", bounds = FixThisRect(0f, 0f, 100f, 200f)),
                node(uid = "header", testTag = "comp:Header:bar", bounds = FixThisRect(0f, 0f, 100f, 40f)),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertTrue(line!!.contains("comp:Footer:cta"), line)
        assertTrue(line.contains("comp:Header:bar"), line)
        assertTrue(line.indexOf("comp:Footer:cta") < line.indexOf("comp:Header:bar"), line)
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

    @Test
    fun compactLineLabelsOverlappingCompNodeAsBoundaryHost() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(20f, 100f, 260f, 240f),
                ),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertEquals(
            "boundaryHost: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(20.0,100.0)-(260.0,240.0)",
            line,
        )
    }

    @Test
    fun preciseLinesClassifyBoundaryHostAncestorAndContext() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "root",
                    testTag = "comp:DiagnosticsScreen:root",
                    bounds = FixThisRect(0f, 0f, 400f, 800f),
                ),
                node(
                    uid = "host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(24f, 112f, 360f, 260f),
                ),
                node(
                    uid = "label",
                    text = listOf("Native chart"),
                    bounds = FixThisRect(24f, 280f, 220f, 312f),
                ),
            ),
        )

        val lines = TargetBoundaryContextFormatter.preciseLines(item)

        assertTrue(
            lines.any { it.contains("Boundary host: tag=\"comp:NativeChartHost:chart\"; role=Image") },
            lines.joinToString("\n"),
        )
        assertTrue(
            lines.any { it.contains("Boundary ancestor: tag=\"comp:DiagnosticsScreen:root\"") },
            lines.joinToString("\n"),
        )
        assertTrue(
            lines.any { it.contains("Boundary context: text=\"Native chart\"") },
            lines.joinToString("\n"),
        )
    }

    @Test
    fun compactLineIncludesHostAncestorAndContextForInteropRisk() {
        val item = interopAreaItem(
            nearbyNodes = listOf(
                node(
                    uid = "root",
                    testTag = "comp:DiagnosticsScreen:root",
                    bounds = FixThisRect(0f, 0f, 400f, 800f),
                ),
                node(
                    uid = "host",
                    testTag = "comp:NativeChartHost:chart",
                    role = "Image",
                    bounds = FixThisRect(24f, 112f, 360f, 260f),
                ),
                node(
                    uid = "label",
                    text = listOf("Native chart"),
                    bounds = FixThisRect(24f, 280f, 220f, 312f),
                ),
                node(
                    uid = "extra",
                    text = listOf("Extra context"),
                    bounds = FixThisRect(24f, 320f, 220f, 352f),
                ),
            ),
        )

        val line = TargetBoundaryContextFormatter.compactLine(item)

        assertEquals(
            "boundaryHost: tag=\"comp:NativeChartHost:chart\"; role=Image; box=(24.0,112.0)-(360.0,260.0) | " +
                "boundaryAncestor: tag=\"comp:DiagnosticsScreen:root\"; box=(0.0,0.0)-(400.0,800.0) | " +
                "boundaryContext: text=\"Native chart\"; box=(24.0,280.0)-(220.0,312.0)",
            line,
        )
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
