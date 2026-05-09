package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind
import kotlin.test.assertTrue
import org.junit.Test

class CompactHandoffRendererTest {
    @Test
    fun renderEmitsViewportLineWhenScreenshotHasDimensions() {
        val session = SessionDto(
            sessionId = "session-vp",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/some/path/screen.png",
                        width = 720,
                        height = 1480,
                    ),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        val screenshotIdx = lines.indexOfFirst { it.startsWith("screenshot:") }
        assertTrue(screenshotIdx >= 0, "Expected a screenshot: line")
        val viewportIdx = lines.indexOfFirst { it.startsWith("viewport:") }
        assertTrue(viewportIdx >= 0, "Expected a viewport: line")
        assertTrue(viewportIdx > screenshotIdx, "viewport: line should come after screenshot: line")
        assertTrue(lines[viewportIdx].contains("720×1480"), "Expected 'viewport: 720×1480' but got: '${lines[viewportIdx]}'")
    }

    @Test
    fun renderOmitsViewportLineWhenDimensionsMissing() {
        val baseAnnotation = AnnotationDto(
            itemId = "i",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
            comment = "x",
            sequenceNumber = 1,
        )

        // width null → no viewport line
        val sessionWidthNull = SessionDto(
            sessionId = "session-wn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = null, height = 1480),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionWidthNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when width is null")

        // height null → no viewport line
        val sessionHeightNull = SessionDto(
            sessionId = "session-hn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = 720, height = null),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionHeightNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when height is null")

        // both null → no viewport line
        val sessionBothNull = SessionDto(
            sessionId = "session-bn",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/some/path/screen.png", width = null, height = null),
                ),
            ),
            items = listOf(baseAnnotation),
        )
        assertTrue(!CompactHandoffRenderer.render(sessionBothNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when both width and height are null")
    }

    @Test
    fun renderEmitsActivityLineWhenDistinctFromDisplayName() {
        val session = SessionDto(
            sessionId = "session-act",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    activityName = "MainActivity",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        val activityIdx = lines.indexOfFirst { it.startsWith("activity:") }
        assertTrue(activityIdx >= 0, "Expected an activity: line but got:\n$markdown")
        assertTrue(lines[activityIdx].contains("MainActivity"),
            "Expected 'activity: MainActivity' but got: '${lines[activityIdx]}'")
    }

    @Test
    fun renderOmitsActivityLineWhenEqualToDisplayName() {
        val session = SessionDto(
            sessionId = "session-act-eq",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "MainActivity",
                    activityName = "MainActivity",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(!markdown.lines().any { it.startsWith("activity:") },
            "Expected no activity: line when activityName == displayName but got:\n$markdown")
    }

    @Test
    fun renderOmitsActivityLineWhenActivityNameNull() {
        val session = SessionDto(
            sessionId = "session-act-null",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    activityName = null,
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(!markdown.lines().any { it.startsWith("activity:") },
            "Expected no activity: line when activityName is null but got:\n$markdown")
    }

    @Test
    fun renderTruncatesScreenIdToFirst8Chars() {
        val fullUuid = "4ce1eaa3-1e20-4da0-b3be-1a5c806fa934"
        val session = SessionDto(
            sessionId = "session-trunc",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = fullUuid,
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = fullUuid,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val screenLines = markdown.lines().filter { it.startsWith("Screen ") }
        assertTrue(screenLines.isNotEmpty(), "Expected at least one Screen header line")
        val headerLine = screenLines.first()
        assertTrue(
            headerLine.contains("Screen 4ce1eaa3:"),
            "Expected header to contain 'Screen 4ce1eaa3:' but got: '$headerLine'",
        )
        assertTrue(
            !headerLine.substringBefore(":").contains(fullUuid),
            "Expected full UUID '$fullUuid' NOT to appear before ':' but got: '$headerLine'",
        )
    }

    @Test
    fun renderHandlesScreenIdShorterThan8Chars() {
        val shortId = "abc"
        val session = SessionDto(
            sessionId = "session-short",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = shortId,
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = shortId,
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val screenLines = markdown.lines().filter { it.startsWith("Screen ") }
        assertTrue(screenLines.isNotEmpty(), "Expected at least one Screen header line")
        val headerLine = screenLines.first()
        assertTrue(
            headerLine.contains("Screen abc:"),
            "Expected header to contain 'Screen abc:' but got: '$headerLine'",
        )
    }

    @Test
    fun renderEmitsTopLevelRuleAndScreenHeader() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "x",
                    sequenceNumber = 1,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(markdown.contains("Rule: source hints are candidates"))
        assertTrue(markdown.contains("Screen "))
    }

    // ---- Task 1.5: ui line tests ----

    private fun makeNode(
        uid: String = "uid-1",
        role: String? = null,
        testTag: String? = null,
    ) = FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 100f, 100f),
        role = role,
        testTag = testTag,
    )

    private fun makeSessionWithNode(
        itemId: String,
        selectedNode: FixThisNode?,
        bounds: FixThisRect,
    ): SessionDto = SessionDto(
        sessionId = "session-ui",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
        items = listOf(
            AnnotationDto(
                itemId = itemId,
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node(nodeUid = "uid-1", boundsInWindow = bounds),
                selectedNode = selectedNode,
                comment = "fix this",
                sequenceNumber = 1,
            ),
        ),
    )

    @Test
    fun renderEmitsUiLineWithFormatBoxForTaggedNode() {
        val session = makeSessionWithNode(
            itemId = "i-ui",
            selectedNode = makeNode(role = "MetricCard", testTag = "comp:MetricCard:summary"),
            bounds = FixThisRect(28f, 212f, 692f, 419f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val expectedLine = "  ui: MetricCard tag=comp:MetricCard:summary  box=(28.0,212.0)-(692.0,419.0) [664×207]"
        assertTrue(
            markdown.lines().any { it == expectedLine },
            "Expected to find line:\n  '$expectedLine'\nin:\n$markdown",
        )
    }

    @Test
    fun renderEmitsTagNoneWhenTestTagMissing() {
        val session = makeSessionWithNode(
            itemId = "i-notag",
            selectedNode = makeNode(role = "Button", testTag = null),
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it.contains("tag=(none)") },
            "Expected 'tag=(none)' in a ui line but got:\n$markdown",
        )
    }

    @Test
    fun renderFallsBackToNodeForMissingRole() {
        val session = makeSessionWithNode(
            itemId = "i-norole",
            selectedNode = makeNode(role = null, testTag = "some:tag"),
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it.startsWith("  ui: Node") },
            "Expected a line starting with '  ui: Node' but got:\n$markdown",
        )
    }

    // ---- Task 1.6: severity prefix tests ----

    private fun makeSessionWithSeverity(
        severity: AnnotationSeverityDto,
        comment: String,
    ): SessionDto = SessionDto(
        sessionId = "session-sev",
        packageName = "io.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
        items = listOf(
            AnnotationDto(
                itemId = "i-sev",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                severity = severity,
                comment = comment,
                sequenceNumber = 1,
            ),
        ),
    )

    @Test
    fun renderPrependsHighSeverityPrefix() {
        val session = makeSessionWithSeverity(AnnotationSeverityDto.HIGH, "레드 카드")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "1. [marker 1] [!] 레드 카드" },
            "Expected line '1. [marker 1] [!] 레드 카드' but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsPrefixForMediumSeverity() {
        val session = makeSessionWithSeverity(AnnotationSeverityDto.MED, "중간 심각도")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "1. [marker 1] 중간 심각도" },
            "Expected line '1. [marker 1] 중간 심각도' (no prefix) but got:\n$markdown",
        )
        assertTrue(
            !markdown.contains("[!]"),
            "Expected no '[!]' prefix for MED severity but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsPrefixForLowSeverity() {
        val session = makeSessionWithSeverity(AnnotationSeverityDto.LOW, "낮은 심각도")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "1. [marker 1] 낮은 심각도" },
            "Expected line '1. [marker 1] 낮은 심각도' (no prefix) but got:\n$markdown",
        )
        assertTrue(
            !markdown.contains("[!]"),
            "Expected no '[!]' prefix for LOW severity but got:\n$markdown",
        )
    }

    @Test
    fun renderPreservesOverlapRiskSuffixOnUiLine() {
        // Two overlapping Node items — overlap group is detected when they share bounds
        val sharedBounds = FixThisRect(0f, 0f, 200f, 200f)
        val session = SessionDto(
            sessionId = "session-overlap",
            packageName = "io.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-1", boundsInWindow = sharedBounds),
                    selectedNode = makeNode(uid = "uid-1", role = "Button", testTag = "btn:1"),
                    comment = "fix 1",
                    sequenceNumber = 1,
                ),
                AnnotationDto(
                    itemId = "i-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-2", boundsInWindow = sharedBounds),
                    selectedNode = makeNode(uid = "uid-2", role = "Text", testTag = "txt:1"),
                    comment = "fix 2",
                    sequenceNumber = 2,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it.endsWith("; targetRisk=overlap") },
            "Expected at least one ui line ending with '; targetRisk=overlap' but got:\n$markdown",
        )
    }
}
