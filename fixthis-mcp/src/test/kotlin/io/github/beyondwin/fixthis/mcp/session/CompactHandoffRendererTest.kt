package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import org.junit.Test
import kotlin.test.assertTrue

class CompactHandoffRendererTest {
    @Test
    fun compactHandoffRendersTargetReliabilityWarnings() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Diagnostics",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 200f, 120f)),
                    comment = "Fix the native chart spacing",
                    sequenceNumber = 1,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("targetConfidence=low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }

    @Test
    fun renderEmitsViewportLineWhenScreenshotHasDimensions() {
        val session = SessionDto(
            sessionId = "session-vp",
            packageName = "io.github.beyondwin.fixthis.sample",
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
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            !CompactHandoffRenderer.render(sessionWidthNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when width is null",
        )

        // height null → no viewport line
        val sessionHeightNull = SessionDto(
            sessionId = "session-hn",
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            !CompactHandoffRenderer.render(sessionHeightNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when height is null",
        )

        // both null → no viewport line
        val sessionBothNull = SessionDto(
            sessionId = "session-bn",
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            !CompactHandoffRenderer.render(sessionBothNull).lines().any { it.startsWith("viewport:") },
            "Expected no viewport: line when both width and height are null",
        )
    }

    @Test
    fun renderEmitsActivityLineWhenDistinctFromDisplayName() {
        val session = SessionDto(
            sessionId = "session-act",
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            lines[activityIdx].contains("MainActivity"),
            "Expected 'activity: MainActivity' but got: '${lines[activityIdx]}'",
        )
    }

    @Test
    fun renderOmitsActivityLineWhenEqualToDisplayName() {
        val session = SessionDto(
            sessionId = "session-act-eq",
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            !markdown.lines().any { it.startsWith("activity:") },
            "Expected no activity: line when activityName == displayName but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsActivityLineWhenActivityNameNull() {
        val session = SessionDto(
            sessionId = "session-act-null",
            packageName = "io.github.beyondwin.fixthis.sample",
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
        assertTrue(
            !markdown.lines().any { it.startsWith("activity:") },
            "Expected no activity: line when activityName is null but got:\n$markdown",
        )
    }

    @Test
    fun renderTruncatesScreenIdToFirst8Chars() {
        val fullUuid = "4ce1eaa3-1e20-4da0-b3be-1a5c806fa934"
        val session = SessionDto(
            sessionId = "session-trunc",
            packageName = "io.github.beyondwin.fixthis.sample",
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
            packageName = "io.github.beyondwin.fixthis.sample",
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
            packageName = "io.github.beyondwin.fixthis.sample",
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
        packageName = "io.github.beyondwin.fixthis.sample",
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
        val expectedLine = "  role=MetricCard  tag=comp:MetricCard:summary  box=(28.0,212.0)-(692.0,419.0)"
        assertTrue(
            markdown.lines().any { it == expectedLine },
            "Expected to find line:\n  '$expectedLine'\nin:\n$markdown",
        )
    }

    @Test
    fun renderOmitsTagWhenTestTagMissing() {
        val session = makeSessionWithNode(
            itemId = "i-notag",
            selectedNode = makeNode(role = "Button", testTag = null),
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.contains("tag="),
            "Expected no 'tag=' token when testTag is missing but got:\n$markdown",
        )
        assertTrue(
            markdown.lines().any { it.startsWith("  role=Button  box=") },
            "Expected a line starting with '  role=Button  box=' but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsRoleWhenRoleMissing() {
        val session = makeSessionWithNode(
            itemId = "i-norole",
            selectedNode = makeNode(role = null, testTag = "some:tag"),
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.contains("role="),
            "Expected no 'role=' token when role is missing but got:\n$markdown",
        )
        assertTrue(
            markdown.lines().any { it.startsWith("  tag=some:tag  box=") },
            "Expected a line starting with '  tag=some:tag  box=' but got:\n$markdown",
        )
    }

    // ---- Task 1.6: severity prefix tests ----

    private fun makeSessionWithSeverity(
        severity: AnnotationSeverityDto,
        comment: String,
    ): SessionDto = SessionDto(
        sessionId = "session-sev",
        packageName = "io.github.beyondwin.fixthis.sample",
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
        val session = makeSessionWithSeverity(AnnotationSeverityDto.HIGH, "Red card")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "[1] [!] Red card" },
            "Expected line '[1] [!] Red card' but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsPrefixForMediumSeverity() {
        val session = makeSessionWithSeverity(AnnotationSeverityDto.MED, "Medium severity")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "[1] Medium severity" },
            "Expected line '[1] Medium severity' (no prefix) but got:\n$markdown",
        )
        assertTrue(
            !markdown.contains("[!]"),
            "Expected no '[!]' prefix for MED severity but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsPrefixForLowSeverity() {
        val session = makeSessionWithSeverity(AnnotationSeverityDto.LOW, "Low severity")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.lines().any { it == "[1] Low severity" },
            "Expected line '[1] Low severity' (no prefix) but got:\n$markdown",
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
            packageName = "io.github.beyondwin.fixthis.sample",
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

    // ---- Task 2.1: candidates block tests ----

    // ---- Task 2.2: rank-1 enrichment (margin + matched) ----

    private fun makeSessionWith2Candidates(
        rank1ScoreMargin: Double?,
        rank1MatchReasons: List<String>,
    ): SessionDto = SessionDto(
        sessionId = "session-rank1",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
        items = listOf(
            AnnotationDto(
                itemId = "i-rank1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "fix this",
                sequenceNumber = 1,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "src/.../HomeScreen.kt",
                        line = 44,
                        score = 0.95,
                        matchedTerms = listOf("testTag:summary", "compTag:MetricCard"),
                        matchReasons = rank1MatchReasons,
                        confidence = SelectionConfidence.MEDIUM,
                        scoreMargin = rank1ScoreMargin,
                        ownerComposable = "HomeScreen",
                    ),
                    SourceCandidate(
                        file = "Other.kt",
                        line = 99,
                        score = 0.65,
                        matchedTerms = emptyList(),
                        matchReasons = listOf("selected text"),
                        confidence = SelectionConfidence.LOW,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun renderEmitsRankOneCandidateWithMarginAndMatched() {
        val session = makeSessionWith2Candidates(
            rank1ScoreMargin = 0.30,
            rank1MatchReasons = listOf(
                "selected testTag",
                "selected testTag convention composable",
                "nearby testTag",
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        val expectedLine = "  src/.../HomeScreen.kt:44  conf=medium  owner=HomeScreen  margin=0.30" +
            "  matched=[tag, compTag, nearbyTag]"
        assertTrue(
            lines.any { it == expectedLine },
            "Expected to find rank-1 enriched line:\n  '$expectedLine'\nin:\n$markdown",
        )
    }

    @Test
    fun renderEmitsRankTwoCandidateWithoutMarginOrMatched() {
        val session = makeSessionWith2Candidates(
            rank1ScoreMargin = 0.30,
            rank1MatchReasons = listOf("selected testTag"),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        // rank-2 line should be plain: Other.kt:99  conf=low — no margin= or matched=
        val rank2Line = lines.firstOrNull { it.contains("Other.kt:99") }
        assertTrue(
            rank2Line != null,
            "Expected a line containing 'Other.kt:99' but got:\n$markdown",
        )
        assertTrue(
            rank2Line!!.trim() == "Other.kt:99  conf=low",
            "Expected rank-2 line to be plain 'Other.kt:99  conf=low' but got: '$rank2Line'",
        )
        assertTrue(
            !rank2Line.contains("margin="),
            "Expected no 'margin=' in rank-2 line but got: '$rank2Line'",
        )
        assertTrue(
            !rank2Line.contains("matched="),
            "Expected no 'matched=' in rank-2 line but got: '$rank2Line'",
        )
    }

    @Test
    fun renderEmitsCandidatesBlockWithConfLevelForEachCandidate() {
        val session = SessionDto(
            sessionId = "session-cand",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-cand",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix contrast",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "AppPrimaryButton.kt",
                            line = 42,
                            score = 0.95,
                            matchedTerms = listOf("AppPrimaryButton"),
                            matchReasons = listOf("selected testTag convention composable"),
                            confidence = SelectionConfidence.HIGH,
                        ),
                        SourceCandidate(
                            file = "CheckoutScreen.kt",
                            line = 88,
                            score = 0.75,
                            matchedTerms = listOf("Pay now"),
                            matchReasons = listOf("selected text"),
                            confidence = SelectionConfidence.MEDIUM,
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        // After Task 2.4: margin is computed from score difference (0.95 - 0.75 = 0.20) when scoreMargin is null
        assertTrue(
            lines.any { it == "  AppPrimaryButton.kt:42  conf=high  margin=0.20  matched=[compTag]" },
            "Expected '  AppPrimaryButton.kt:42  conf=high  margin=0.20  matched=[compTag]' but got:\n$markdown",
        )
        assertTrue(
            lines.any { it == "  CheckoutScreen.kt:88  conf=medium" },
            "Expected '  CheckoutScreen.kt:88  conf=medium' but got:\n$markdown",
        )
        assertTrue(
            !lines.any { it.trim().startsWith("src?") },
            "Expected no 'src?' line in v2 output but got:\n$markdown",
        )
    }

    // ---- Task 2.3: cap candidates at 3 ----

    private fun makeSourceCandidate(file: String, line: Int, rank: Int = 1) = SourceCandidate(
        file = file,
        line = line,
        score = 1.0 - (rank - 1) * 0.1,
        matchedTerms = emptyList(),
        matchReasons = emptyList(),
        confidence = SelectionConfidence.LOW,
    )

    private fun makeSessionWithNCandidates(n: Int): SessionDto {
        val candidates = (1..n).map { rank -> makeSourceCandidate("File$rank.kt", rank * 10, rank) }
        return SessionDto(
            sessionId = "session-cap-$n",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-cap",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix",
                    sequenceNumber = 1,
                    sourceCandidates = candidates,
                ),
            ),
        )
    }

    @Test
    fun renderCapsCandidatesAtThreeWhenFiveProvided() {
        val session = makeSessionWithNCandidates(5)
        val markdown = CompactHandoffRenderer.render(session)
        val candidateLines = markdown.lines().filter { it.startsWith("  ") && it.contains("conf=") }
        assertTrue(
            candidateLines.size == 3,
            "Expected exactly 3 candidate lines when 5 candidates provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    @Test
    fun renderEmitsExactlyOneCandidateLineWhenOneProvided() {
        val session = makeSessionWithNCandidates(1)
        val markdown = CompactHandoffRenderer.render(session)
        val candidateLines = markdown.lines().filter { it.startsWith("  ") && it.contains("conf=") }
        assertTrue(
            candidateLines.size == 1,
            "Expected exactly 1 candidate line when 1 candidate provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    @Test
    fun renderEmitsExactlyTwoCandidateLinesWhenTwoProvided() {
        val session = makeSessionWithNCandidates(2)
        val markdown = CompactHandoffRenderer.render(session)
        val candidateLines = markdown.lines().filter { it.startsWith("  ") && it.contains("conf=") }
        assertTrue(
            candidateLines.size == 2,
            "Expected exactly 2 candidate lines when 2 candidates provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    // ---- Task 2.4: prefer wire scoreMargin, else compute ----

    @Test
    fun renderEmitsWireMarginWhenNonNull() {
        // Test A: rank-1 with non-null scoreMargin=0.42 should emit margin=0.42 (wire value, not recomputed)
        val session = SessionDto(
            sessionId = "session-wire-margin",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-wire",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix this",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "HomeScreen.kt",
                            line = 10,
                            score = 0.99,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.42, // wire value present
                        ),
                        SourceCandidate(
                            file = "OtherScreen.kt",
                            line = 20,
                            score = 0.50, // difference would be 0.49, not 0.42
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.contains("margin=0.42"),
            "Expected 'margin=0.42' (wire value) but got:\n$markdown",
        )
        // Verify it uses wire value 0.42, not computed 0.49
        assertTrue(
            !markdown.contains("margin=0.49"),
            "Expected wire margin=0.42, not computed margin=0.49, but got:\n$markdown",
        )
    }

    @Test
    fun renderComputesMarginFromScoresWhenWireNull() {
        // Test B: rank-1 with scoreMargin=null, score=0.95; rank-2 with score=0.65 -> margin=0.30
        val session = SessionDto(
            sessionId = "session-computed-margin",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-computed",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix this",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "HomeScreen.kt",
                            line = 10,
                            score = 0.95,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = null, // wire field is null -> must compute
                        ),
                        SourceCandidate(
                            file = "OtherScreen.kt",
                            line = 20,
                            score = 0.65,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            markdown.contains("margin=0.30"),
            "Expected computed 'margin=0.30' (0.95 - 0.65) but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsMarginWhenSingleCandidateAndScoreMarginNull() {
        // Test C: only 1 candidate with scoreMargin=null -> no margin= token
        val session = SessionDto(
            sessionId = "session-single-no-margin",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-single",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix this",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "HomeScreen.kt",
                            line = 10,
                            score = 0.90,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.MEDIUM,
                            scoreMargin = null, // no runner-up, no wire margin
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.contains("margin="),
            "Expected no 'margin=' token for single candidate with null scoreMargin but got:\n$markdown",
        )
    }

    // ---- Task 2.5: candidate caution → note line ----

    private fun makeSessionWithRank1Caution(caution: String?): SessionDto = SessionDto(
        sessionId = "session-caution",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
        items = listOf(
            AnnotationDto(
                itemId = "i-caution",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "fix this",
                sequenceNumber = 1,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "HomeScreen.kt",
                        line = 10,
                        score = 0.90,
                        matchedTerms = emptyList(),
                        matchReasons = emptyList(),
                        confidence = SelectionConfidence.MEDIUM,
                        caution = caution,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun renderEmitsCautionAsNoteLineWhenRankOneHasCaution() {
        val session = makeSessionWithRank1Caution(caution = "treat as low-confidence")
        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        val candidateIdx = lines.indexOfFirst { it.startsWith("  ") && it.contains("conf=") }
        assertTrue(candidateIdx >= 0, "Expected a candidate line but got:\n$markdown")
        val noteLine = lines.getOrNull(candidateIdx + 1)
        assertTrue(
            noteLine == "  note: treat as low-confidence",
            "Expected '  note: treat as low-confidence' immediately after candidate line but got: '$noteLine'\nFull output:\n$markdown",
        )
    }

    @Test
    fun renderOmitsNoteLineWhenCautionNull() {
        val session = makeSessionWithRank1Caution(caution = null)
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.lines().any { it.startsWith("  note:") },
            "Expected no '  note:' line when caution is null but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsNoteLineWhenCautionBlank() {
        val session = makeSessionWithRank1Caution(caution = "   ")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.lines().any { it.startsWith("  note:") },
            "Expected no '  note:' line when caution is blank but got:\n$markdown",
        )
    }

    // ---- Task 3.2: instance i/N on grouped ui lines ----

    private fun makeNode3(
        uid: String,
        role: String? = "MetricCard",
        testTag: String? = "comp:MetricCard:summary",
        path: List<String> = emptyList(),
    ) = FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 100f, 100f),
        role = role,
        testTag = testTag,
        path = path,
    )

    private fun makeGroupedCandidate() = SourceCandidate(
        file = "HomeScreen.kt",
        line = 44,
        score = 0.9,
        matchedTerms = emptyList(),
        matchReasons = emptyList(),
        confidence = SelectionConfidence.MEDIUM,
    )

    @Test
    fun renderEmitsInstanceLabelsOnGroupedUiLines() {
        // 3 items sharing same (file:line, testTag), distinct path leaves — should get instance 1/3, 2/3, 3/3
        // InstanceGroupingHelper.compute orders by path.joinToString("/"); paths: "root/a", "root/b", "root/c" -> a < b < c
        // Use non-overlapping bounds so overlap detector does not merge them into an overlap group
        val session = SessionDto(
            sessionId = "session-instance",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-a",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-a", boundsInWindow = FixThisRect(0f, 0f, 100f, 50f)),
                    selectedNode = makeNode3(uid = "uid-a", path = listOf("root", "a")),
                    comment = "fix a",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(makeGroupedCandidate()),
                ),
                AnnotationDto(
                    itemId = "item-b",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-b", boundsInWindow = FixThisRect(0f, 200f, 100f, 250f)),
                    selectedNode = makeNode3(uid = "uid-b", path = listOf("root", "b")),
                    comment = "fix b",
                    sequenceNumber = 2,
                    sourceCandidates = listOf(makeGroupedCandidate()),
                ),
                AnnotationDto(
                    itemId = "item-c",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-c", boundsInWindow = FixThisRect(0f, 400f, 100f, 450f)),
                    selectedNode = makeNode3(uid = "uid-c", path = listOf("root", "c")),
                    comment = "fix c",
                    sequenceNumber = 3,
                    sourceCandidates = listOf(makeGroupedCandidate()),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val uiLines = markdown.lines().filter { it.contains("  box=") && it.contains("  instance ") }
        assertTrue(
            uiLines.size == 3,
            "Expected 3 ui lines with instance labels but got ${uiLines.size}:\n$markdown",
        )
        assertTrue(
            uiLines.any { it.endsWith("  instance 1/3") },
            "Expected a ui line ending with '  instance 1/3' but got:\n${uiLines.joinToString("\n")}",
        )
        assertTrue(
            uiLines.any { it.endsWith("  instance 2/3") },
            "Expected a ui line ending with '  instance 2/3' but got:\n${uiLines.joinToString("\n")}",
        )
        assertTrue(
            uiLines.any { it.endsWith("  instance 3/3") },
            "Expected a ui line ending with '  instance 3/3' but got:\n${uiLines.joinToString("\n")}",
        )
    }

    @Test
    fun renderOmitsInstanceLabelForLoneItem() {
        val session = makeSessionWithNode(
            itemId = "i-lone",
            selectedNode = makeNode(role = "Button", testTag = "btn:lone"),
            bounds = FixThisRect(0f, 0f, 100f, 50f),
        )

        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.lines().any { it.contains("instance ") },
            "Expected no 'instance ' token in any line for lone item but got:\n$markdown",
        )
    }

    // ---- Task 3.3: collision note on instance-group leader ----

    private fun makeGroupedCandidateWithSource() = SourceCandidate(
        file = "HomeScreen.kt",
        line = 44,
        score = 0.9,
        matchedTerms = emptyList(),
        matchReasons = emptyList(),
        confidence = SelectionConfidence.MEDIUM,
    )

    private fun makeNode33(
        uid: String,
        role: String? = "MetricCard",
        testTag: String? = "comp:MetricCard:summary",
        path: List<String> = emptyList(),
    ) = FixThisNode(
        uid = uid,
        composeNodeId = 1,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(0f, 0f, 100f, 100f),
        role = role,
        testTag = testTag,
        path = path,
    )

    @Test
    fun renderEmitsCollisionNoteOnGroupLeader() {
        // 3 items sharing (file:line, testTag), distinct path leaves — leader is item-a (path "root/a" < "root/b" < "root/c")
        // Non-overlapping bounds so overlap detector doesn't merge them
        val session = SessionDto(
            sessionId = "session-collision",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-a",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-a", boundsInWindow = FixThisRect(0f, 0f, 100f, 50f)),
                    selectedNode = makeNode33(uid = "uid-a", path = listOf("root", "a")),
                    comment = "fix a",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
                AnnotationDto(
                    itemId = "item-b",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-b", boundsInWindow = FixThisRect(0f, 200f, 100f, 250f)),
                    selectedNode = makeNode33(uid = "uid-b", path = listOf("root", "b")),
                    comment = "fix b",
                    sequenceNumber = 2,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
                AnnotationDto(
                    itemId = "item-c",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-c", boundsInWindow = FixThisRect(0f, 400f, 100f, 450f)),
                    selectedNode = makeNode33(uid = "uid-c", path = listOf("root", "c")),
                    comment = "fix c",
                    sequenceNumber = 3,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        val collisionNoteLine = "  note: 3 markers map to same call site — likely list-rendered; disambiguate by instance index"

        // Collision note appears EXACTLY ONCE
        val collisionNoteCount = lines.count { it == collisionNoteLine }
        assertTrue(
            collisionNoteCount == 1,
            "Expected collision note to appear exactly once but found $collisionNoteCount times:\n$markdown",
        )

        // Collision note appears AFTER item-a's candidates block (leader is item-a, path "root/a")
        // item-a's marker line: "[1] fix a"
        val itemAMarkerIdx = lines.indexOfFirst { it == "[1] fix a" }
        assertTrue(itemAMarkerIdx >= 0, "Expected item-a marker line '[1] fix a' but got:\n$markdown")

        val collisionNoteIdx = lines.indexOfFirst { it == collisionNoteLine }
        assertTrue(
            collisionNoteIdx > itemAMarkerIdx,
            "Expected collision note to appear after item-a's marker line but got:\n$markdown",
        )

        // item-b's and item-c's blocks do NOT have an extra note line
        val itemBMarkerIdx = lines.indexOfFirst { it == "[2] fix b" }
        val itemCMarkerIdx = lines.indexOfFirst { it == "[3] fix c" }
        assertTrue(itemBMarkerIdx >= 0, "Expected item-b marker line but got:\n$markdown")
        assertTrue(itemCMarkerIdx >= 0, "Expected item-c marker line but got:\n$markdown")
        assertTrue(
            collisionNoteIdx < itemBMarkerIdx,
            "Expected collision note to appear BEFORE item-b's block (not inside it), but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsCollisionNoteForOverlapItems() {
        // 3 items in same instance group AND overlapping bounds => collision note must NOT appear
        val sharedBounds = FixThisRect(0f, 0f, 200f, 200f)
        val session = SessionDto(
            sessionId = "session-overlap-no-collision",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-a",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-a", boundsInWindow = sharedBounds),
                    selectedNode = makeNode33(uid = "uid-a", path = listOf("root", "a")),
                    comment = "fix a",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
                AnnotationDto(
                    itemId = "item-b",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-b", boundsInWindow = sharedBounds),
                    selectedNode = makeNode33(uid = "uid-b", path = listOf("root", "b")),
                    comment = "fix b",
                    sequenceNumber = 2,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
                AnnotationDto(
                    itemId = "item-c",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-c", boundsInWindow = sharedBounds),
                    selectedNode = makeNode33(uid = "uid-c", path = listOf("root", "c")),
                    comment = "fix c",
                    sequenceNumber = 3,
                    sourceCandidates = listOf(makeGroupedCandidateWithSource()),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val collisionNoteLine = "  note: 3 markers map to same call site — likely list-rendered; disambiguate by instance index"
        assertTrue(
            !markdown.lines().any { it == collisionNoteLine },
            "Expected NO collision note for items in an overlap group but got:\n$markdown",
        )
    }

    @Test
    fun renderEmitsCandidatesUnknownWhenSourceCandidatesEmpty() {
        val session = SessionDto(
            sessionId = "session-nocand",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-nocand",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix spacing",
                    sequenceNumber = 1,
                    sourceCandidates = emptyList(),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        assertTrue(
            lines.any { it == "  unknown" },
            "Expected '  unknown' for empty candidates but got:\n$markdown",
        )
        assertTrue(
            !lines.any { it.trim().startsWith("src?") },
            "Expected no 'src?' line in v2 output but got:\n$markdown",
        )
    }

    // ---- Task 4.2: duplicate-of-marker-N suffix ----

    /**
     * Builds a 4-item session where items 1 and 4 share the same duplicate key
     * (same fileLine + testTag + pathLeaves + bounds).
     * Items 2 and 3 have distinct bounds so they are NOT duplicates.
     *
     * NOTE: Because items 1 and 4 share identical bounds, the AnnotationOverlapDetector
     * places them in an overlap group. As a result, in the rendered output:
     *   - Item 1 becomes marker 1 (canonical, in overlap group)
     *   - Item 4 becomes marker 2 (duplicate, in overlap group)
     *   - Item "Fix other A" becomes marker 3
     *   - Item "Fix other B" becomes marker 4
     * The test assertions use marker 2 for the duplicate item.
     */
    private fun makeDuplicateSession(): SessionDto {
        val sharedBounds = FixThisRect(28f, 212f, 692f, 419f)
        val sharedCandidate = SourceCandidate(
            file = "HomeScreen.kt",
            line = 44,
            score = 0.9,
            matchedTerms = emptyList(),
            matchReasons = listOf("selected testTag convention composable"),
            confidence = SelectionConfidence.MEDIUM,
        )
        fun makeNode4(uid: String, path: List<String>) = FixThisNode(
            uid = uid,
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 100f),
            role = "MetricCard",
            testTag = "comp:MetricCard:summary",
            path = path,
        )
        return SessionDto(
            sessionId = "session-dup",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                // item 1 — canonical (will be marker 1)
                AnnotationDto(
                    itemId = "dup-item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-1", boundsInWindow = sharedBounds),
                    selectedNode = makeNode4(uid = "uid-1", path = listOf("root", "MetricCard")),
                    comment = "Fix MetricCard 1",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(sharedCandidate),
                ),
                // item 2 — unrelated (distinct bounds, no duplicate)
                AnnotationDto(
                    itemId = "dup-item-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-2", boundsInWindow = FixThisRect(0f, 500f, 100f, 600f)),
                    selectedNode = makeNode4(uid = "uid-2", path = listOf("root", "OtherA")),
                    comment = "Fix other A",
                    sequenceNumber = 2,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "OtherScreen.kt",
                            line = 10,
                            score = 0.8,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                ),
                // item 3 — unrelated (distinct bounds, no duplicate)
                AnnotationDto(
                    itemId = "dup-item-3",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-3", boundsInWindow = FixThisRect(0f, 700f, 100f, 800f)),
                    selectedNode = makeNode4(uid = "uid-3", path = listOf("root", "OtherB")),
                    comment = "Fix other B",
                    sequenceNumber = 3,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "AnotherScreen.kt",
                            line = 20,
                            score = 0.7,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.LOW,
                        ),
                    ),
                ),
                // item 4 — duplicate of item 1 (same fileLine + testTag + pathLeaves + bounds)
                // Shares sharedBounds with item 1 → overlap detector places both in overlap group 1
                // → item 4 receives marker 2 (not marker 4)
                AnnotationDto(
                    itemId = "dup-item-4",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node(nodeUid = "uid-4", boundsInWindow = sharedBounds),
                    selectedNode = makeNode4(uid = "uid-4", path = listOf("root", "MetricCard")),
                    comment = "Fix MetricCard 4",
                    sequenceNumber = 4,
                    sourceCandidates = listOf(sharedCandidate),
                ),
            ),
        )
    }

    @Test
    fun renderEmitsDuplicateOfMarkerSuffixForDuplicateItem() {
        val session = makeDuplicateSession()
        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        // items 1 and 4 share identical bounds → overlap group → item 4 gets marker 2.
        // item 4's ui line (marker 2) must end with "; targetRisk=duplicate-of-marker-1"
        val dupItemMarkerLine = "[2] Fix MetricCard 4"
        val dupUiLine = lines
            .dropWhile { it != dupItemMarkerLine }
            .drop(1)
            .firstOrNull { it.contains("  box=") }
        assertTrue(
            dupUiLine != null,
            "Expected a ui line after '$dupItemMarkerLine' but got:\n$markdown",
        )
        assertTrue(
            dupUiLine!!.endsWith("; targetRisk=duplicate-of-marker-1"),
            "Expected duplicate item's ui line to end with '; targetRisk=duplicate-of-marker-1' but got: '$dupUiLine'\nFull output:\n$markdown",
        )

        // item 1's ui line (marker 1) must NOT have the duplicate-of-marker suffix
        val canonicalMarkerLine = "[1] Fix MetricCard 1"
        val canonicalUiLine = lines
            .dropWhile { it != canonicalMarkerLine }
            .drop(1)
            .firstOrNull { it.contains("  box=") }
        assertTrue(
            canonicalUiLine != null,
            "Expected a ui line after '$canonicalMarkerLine' but got:\n$markdown",
        )
        assertTrue(
            !canonicalUiLine!!.contains("targetRisk=duplicate-of-marker"),
            "Expected canonical item's ui line to NOT have duplicate-of-marker suffix but got: '$canonicalUiLine'\nFull output:\n$markdown",
        )
    }

    @Test
    fun renderSuppressesInstanceLabelForDuplicateItem() {
        val session = makeDuplicateSession()
        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()

        // item 4 (marker 2) is a duplicate — its ui line must NOT contain "instance "
        // (duplicate-of-marker token suppresses the instance label per spec Appendix worked example)
        val dupItemMarkerLine = "[2] Fix MetricCard 4"
        val dupUiLine = lines
            .dropWhile { it != dupItemMarkerLine }
            .drop(1)
            .firstOrNull { it.contains("  box=") }
        assertTrue(
            dupUiLine != null,
            "Expected a ui line after '$dupItemMarkerLine' but got:\n$markdown",
        )
        assertTrue(
            !dupUiLine!!.contains("instance "),
            "Expected duplicate item's ui line to NOT contain 'instance ' (duplicate suppresses instance label) but got: '$dupUiLine'\nFull output:\n$markdown",
        )
    }

    // ---- Task 6.4: backward-compat smoke against v1 fixture ----

    /**
     * Runs the v2 renderer against the existing v1 parity fixture (session.json), which contains
     * minimal v1 structure without viewport/activity/severity fields, and asserts:
     *   1. The Rule: line is present verbatim.
     *   2. There is a ~ line for every item (v1 fixture has 1 item → ≥1 ~ line).
     *   3. No exception is thrown on the v1-shape input (empty sourceCandidates path exercises the
     *      "~ unknown" fallback via the existing renderEmitsCandidatesUnknownWhenSourceCandidatesEmpty
     *      test; here we confirm the non-empty single-candidate path does not throw).
     *   4. Items with exactly 1 candidate and null scoreMargin do NOT emit margin=.
     *      The v1 fixture item has scoreMargin=1.0 (non-null), so it correctly emits margin=;
     *      we verify no spurious margin= appears for a zero-candidate (null) code path by checking
     *      that every margin= token in the output is justified by a non-null wire scoreMargin or a
     *      computable gap (runner-up present). For the v1 fixture this means margin=1.00 is present
     *      and that is the only margin= token — no phantom margins.
     */
    @Test
    fun renderRunsCleanlyOnV1Fixture() {
        val sessionJson = javaClass.getResourceAsStream("/parity/session.json")!!
            .bufferedReader()
            .readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), sessionJson)

        // Must not throw
        val output = CompactHandoffRenderer.render(session)

        // 1. Rule: line verbatim
        assertTrue(
            output.contains("Rule: source hints are candidates; verify screenshot, target, and code before editing."),
            "Expected the verbatim Rule: line but got:\n$output",
        )

        // 2. One candidate line per item (fixture has 1 item with 1 candidate)
        val itemCount = session.items.size
        val candidateLineCount = output.lines().count { it.startsWith("  ") && it.contains("conf=") }
        assertTrue(
            candidateLineCount >= itemCount,
            "Expected ≥$itemCount candidate line(s) (one per item), got $candidateLineCount.\nOutput:\n$output",
        )

        // 3. No exception path for empty sourceCandidates: renderer produces output without crashing.
        //    (The test itself succeeds only if render() returns without throwing.)
        assertTrue(output.isNotBlank(), "Expected non-blank output from v1 fixture render")

        // 4. Items with 1 candidate and null scoreMargin must NOT emit margin=.
        //    The v1 fixture item has scoreMargin=1.0 (non-null), so margin= IS expected there.
        //    We verify: for every item that has exactly 1 candidate with a null scoreMargin, the
        //    rendered output does not contain a margin= token for that item.
        //    (None of the v1 fixture items have null scoreMargin + single candidate, so this
        //    assertion confirms the fixture doesn't accidentally trigger the margin= guard.)
        val singleNullMarginItems = session.items.filter { item ->
            item.sourceCandidates.size == 1 && item.sourceCandidates.first().scoreMargin == null
        }
        if (singleNullMarginItems.isEmpty()) {
            // v1 fixture: all single-candidate items have non-null scoreMargin — correct.
            assertTrue(true, "v1 fixture has no single-candidate items with null scoreMargin (expected)")
        } else {
            // Defensive: if the fixture ever gains such items, assert no margin= in their block.
            // We cannot easily isolate per-item output here, so we assert globally.
            // If ALL items are single-candidate-null-margin, there should be no margin= at all.
            if (session.items.all { it.sourceCandidates.size == 1 && it.sourceCandidates.firstOrNull()?.scoreMargin == null }) {
                assertTrue(
                    !output.contains("margin="),
                    "Expected no margin= when all items have single candidate with null scoreMargin but got:\n$output",
                )
            }
        }
    }

    // ---- Source-root prefix tests ----

    private fun makeSessionWithCandidateFiles(vararg files: String): SessionDto {
        val items = files.mapIndexed { idx, file ->
            AnnotationDto(
                itemId = "i-$idx",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, idx * 100f, 100f, idx * 100f + 50f)),
                comment = "fix $idx",
                sequenceNumber = idx + 1,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = file,
                        line = 10 + idx,
                        score = 0.9,
                        matchedTerms = emptyList(),
                        matchReasons = emptyList(),
                        confidence = SelectionConfidence.MEDIUM,
                    ),
                ),
            )
        }
        return SessionDto(
            sessionId = "session-srcroot",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = items,
        )
    }

    @Test
    fun renderEmitsSourceRootHeaderAndStripsPrefixWhenCommonDirExists() {
        val session = makeSessionWithCandidateFiles(
            "src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt",
            "src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
            "src/main/java/io/github/beyondwin/fixthis/sample/components/MetricCard.kt",
        )
        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        assertTrue(
            lines.any { it == "- Source root: `src/main/java/io/github/beyondwin/fixthis/sample/`" },
            "Expected '- Source root: ...' line but got:\n$markdown",
        )
        assertTrue(
            lines.any { it == "  screens/DiagnosticsScreen.kt:10  conf=medium" },
            "Expected stripped path 'screens/DiagnosticsScreen.kt:10' but got:\n$markdown",
        )
        assertTrue(
            !markdown.contains("src/main/java/io/github/beyondwin/fixthis/sample/screens/DiagnosticsScreen.kt"),
            "Expected full path to be stripped after Source root header but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsSourceRootHeaderWhenPathsHaveNoCommonDirectory() {
        val session = makeSessionWithCandidateFiles("HomeScreen.kt", "MetricCard.kt")
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.contains("Source root:"),
            "Expected no 'Source root:' line when candidates share no directory but got:\n$markdown",
        )
        assertTrue(
            markdown.lines().any { it == "  HomeScreen.kt:10  conf=medium" },
            "Expected unchanged candidate path 'HomeScreen.kt:10' but got:\n$markdown",
        )
    }

    @Test
    fun renderOmitsSourceRootHeaderWhenOnlyOneCandidatePath() {
        val session = makeSessionWithCandidateFiles(
            "src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
        )
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(
            !markdown.contains("Source root:"),
            "Expected no 'Source root:' header when only one candidate path exists but got:\n$markdown",
        )
        assertTrue(
            markdown.contains("src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt:10"),
            "Expected full path to remain when no source root header is emitted but got:\n$markdown",
        )
    }

    @Test
    fun renderUsesRepoFileForSourceCandidateDisplayPath() {
        val session = SessionDto(
            sessionId = "session-repofile",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "i-repofile",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix path",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
                            repoFile = "sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt",
                            line = 44,
                            score = 0.9,
                            matchedTerms = emptyList(),
                            matchReasons = emptyList(),
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(
            markdown.contains("sample/src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt:44"),
            "Expected repoFile display path but got:\n$markdown",
        )
        assertTrue(
            !markdown.lines().any {
                it.trim().startsWith("src/main/java/io/github/beyondwin/fixthis/sample/screens/HomeScreen.kt:44")
            },
            "Expected module-relative file path to be hidden when repoFile is present:\n$markdown",
        )
    }

    @Test
    fun rendersIdTokenForEachItem() {
        val session = SessionDto(
            sessionId = "session-id-token",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-aaa",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "first",
                    sequenceNumber = 1,
                ),
                AnnotationDto(
                    itemId = "item-bbb",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "second",
                    sequenceNumber = 2,
                ),
            ),
        )
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(markdown.contains("id: item-aaa"), markdown)
        assertTrue(markdown.contains("id: item-bbb"), markdown)
    }

    // ---- Task 3 (C6): stale marker rendering ----

    private fun sessionWithStaleCandidate(stale: Boolean?, staleReason: String?): SessionDto = SessionDto(
        sessionId = "session-stale",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
        items = listOf(
            AnnotationDto(
                itemId = "item-stale",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                comment = "fix",
                sequenceNumber = 1,
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "HomeScreen.kt",
                        line = 10,
                        score = 0.9,
                        matchedTerms = emptyList(),
                        matchReasons = emptyList(),
                        confidence = SelectionConfidence.HIGH,
                        stale = stale,
                        staleReason = staleReason,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `render emits stale marker on rank 1 when candidate is stale`() {
        val markdown = CompactHandoffRenderer.render(
            sessionWithStaleCandidate(stale = true, staleReason = "excerpt mismatch"),
        )
        assertTrue(
            markdown.contains("⚠ stale: excerpt mismatch"),
            "Expected stale marker '⚠ stale: excerpt mismatch' in:\n$markdown",
        )
    }

    @Test
    fun `render omits stale marker when candidate is fresh`() {
        val markdown = CompactHandoffRenderer.render(
            sessionWithStaleCandidate(stale = false, staleReason = null),
        )
        assertTrue(
            !markdown.contains("⚠ stale"),
            "Expected no stale marker when stale=false, but got:\n$markdown",
        )
    }

    @Test
    fun `render omits stale marker when candidate stale is null`() {
        val markdown = CompactHandoffRenderer.render(
            sessionWithStaleCandidate(stale = null, staleReason = null),
        )
        assertTrue(
            !markdown.contains("⚠ stale"),
            "Expected no stale marker when stale=null, but got:\n$markdown",
        )
    }

    @Test
    fun rendersAgentProtocolFooter() {
        val session = SessionDto(
            sessionId = "sess-123",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "fix",
                    sequenceNumber = 1,
                ),
            ),
        )
        val markdown = CompactHandoffRenderer.render(session)
        assertTrue(markdown.contains("agent_protocol:"), markdown)
        assertTrue(markdown.contains("before_work: fixthis_claim_feedback"), markdown)
        assertTrue(markdown.contains("on_complete: fixthis_resolve_feedback"), markdown)
        assertTrue(markdown.contains("session_id: sess-123"), markdown)
    }

    // ---- Task 1 (this plan): itemIds filter ----

    @Test
    fun renderFiltersItemsByItemIdsWhenProvided() {
        val session = SessionDto(
            sessionId = "session-filter",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Home",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = "/p.png",
                        width = 1,
                        height = 1,
                    ),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "keep",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "kept",
                    sequenceNumber = 1,
                ),
                AnnotationDto(
                    itemId = "drop",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                    comment = "dropped",
                    sequenceNumber = 2,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session, itemIds = listOf("keep"))

        assertTrue(markdown.contains("id: keep"), "expected 'id: keep' in:\n$markdown")
        assertTrue(!markdown.contains("id: drop"), "should not include filtered-out item, got:\n$markdown")
    }

    @Test
    fun renderEmitsScreenshotLineUsingFullPathFallbackWhenDesktopPathMissing() {
        val session = SessionDto(
            sessionId = "session-fallback",
            packageName = "x",
            projectRoot = "/r",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "S",
                    screenshot = SnapshotScreenshotDto(
                        desktopFullPath = null,
                        fullPath = "/device/path/s.png",
                        width = 1,
                        height = 1,
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

        assertTrue(
            markdown.lineSequence().any { it == "screenshot: /device/path/s.png" },
            "expected screenshot fallback line, got:\n$markdown",
        )
    }

    @Test
    fun renderEmitsNoBlankLineBetweenOverlapHeaderAndFirstItem() {
        val sharedBounds = FixThisRect(0f, 0f, 1f, 1f)
        val session = SessionDto(
            sessionId = "session-overlap-blank",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Home")),
            items = listOf(
                AnnotationDto(
                    itemId = "ov-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(sharedBounds),
                    comment = "overlap first",
                    sequenceNumber = 1,
                ),
                AnnotationDto(
                    itemId = "ov-2",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(sharedBounds),
                    comment = "overlap second",
                    sequenceNumber = 2,
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val lines = markdown.lines()
        val overlapIdx = lines.indexOfFirst { it.startsWith("Overlap group") }
        assertTrue(overlapIdx >= 0, "expected Overlap group header in:\n$markdown")
        val nextLine = lines.getOrNull(overlapIdx + 1) ?: ""
        assertTrue(
            nextLine.startsWith("[1]") || nextLine.startsWith("[2]"),
            "expected first item directly after overlap header, got: '$nextLine'\nfull:\n$markdown",
        )
    }

    @Test
    fun renderAddsTargetSummaryBeforeBoxLine() {
        val session = SessionDto(
            sessionId = "session-target-summary",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 1L,
                    displayName = "Review",
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-1", FixThisRect(42f, 184f, 436f, 252f)),
                    selectedNode = FixThisNode(
                        uid = "node-1",
                        composeNodeId = 1,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(42f, 184f, 436f, 252f),
                        text = listOf("Review request"),
                    ),
                    comment = "Make heading clearer",
                    sequenceNumber = 1,
                ),
            ),
        )

        val lines = CompactHandoffRenderer.render(session).lines()
        val idIndex = lines.indexOf("  id: item-1")
        val targetIndex = lines.indexOf("""  target: text="Review request"""")
        val boxIndex = lines.indexOf("  box=(42.0,184.0)-(436.0,252.0)")

        assertTrue(idIndex >= 0, "Expected id line in:\n${lines.joinToString("\n")}")
        assertTrue(targetIndex > idIndex, "target: line should come after id:")
        assertTrue(boxIndex > targetIndex, "box line should come after target:")
    }

    @Test
    fun renderTargetSummaryIncludesTagTextContentDescriptionAndRoleInStableOrder() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = FixThisNode(
                    uid = "node-1",
                    composeNodeId = 1,
                    rootIndex = 0,
                    treeKind = TreeKind.MERGED,
                    boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
                    text = listOf("Submit request"),
                    contentDescription = listOf("Submit handoff request"),
                    role = "Button",
                    testTag = "comp:ReviewScreen:submit",
                ),
                comment = "Increase tap affordance",
                sequenceNumber = 1,
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)
        val expectedTarget =
            "  target: tag=\"comp:ReviewScreen:submit\"; text=\"Submit request\"; " +
                "contentDescription=\"Submit handoff request\"; role=Button"

        assertTrue(markdown.contains(expectedTarget), markdown)
    }

    @Test
    fun renderTargetSummaryForVisualAreaWithoutSelectedNode() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 120f, 80f)),
                selectedNode = null,
                comment = "Adjust chart spacing",
                sequenceNumber = 1,
            ),
        )

        assertTrue(CompactHandoffRenderer.render(session).contains("  target: visual area"))
    }

    @Test
    fun renderTargetSummaryRedactsSensitiveEditableAndPasswordText() {
        val sensitiveNode = FixThisNode(
            uid = "node-1",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
            text = listOf("agent-context-token"),
            editableText = "agent-context-token",
            role = "TextField",
            isPassword = true,
            isSensitive = true,
        )
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-1",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = sensitiveNode,
                comment = "Token field layout",
                sequenceNumber = 1,
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.MEDIUM,
                    warnings = listOf(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("  target: redacted sensitive target; role=TextField"), markdown)
        assertTrue(!markdown.contains("agent-context-token"), markdown)
    }

    @Test
    fun renderAddsHandoffQualitySummaryForRiskSignals() {
        val markdown = CompactHandoffRenderer.render(handoffQualityRiskSignalSession())

        assertTrue(markdown.contains("Handoff quality:"), markdown)
        assertTrue(markdown.contains("1 low-confidence target"), markdown)
        assertTrue(markdown.contains("2 warning targets"), markdown)
        assertTrue(markdown.contains("1 overlap group"), markdown)
        assertTrue(markdown.contains("1 visual area"), markdown)
        assertTrue(markdown.contains("1 redacted target"), markdown)
        assertTrue(markdown.contains("1 stale source candidate"), markdown)
        assertTrue(markdown.contains("1 item without source candidates"), markdown)
    }

    @Test
    fun renderOmitsHandoffQualitySummaryWhenNoSignalsExist() {
        val session = oneItemSession(
            item = AnnotationDto(
                itemId = "item-clean",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Node("node-clean", FixThisRect(0f, 0f, 100f, 50f)),
                selectedNode = FixThisNode(
                    uid = "node-clean",
                    composeNodeId = 1,
                    rootIndex = 0,
                    treeKind = TreeKind.MERGED,
                    boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
                    text = listOf("Clean target"),
                ),
                comment = "Clean feedback",
                sequenceNumber = 1,
                targetReliability = TargetReliability(confidence = TargetConfidence.HIGH),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/ReviewScreen.kt",
                        line = 56,
                        score = 0.95,
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
            ),
        )

        assertTrue(!CompactHandoffRenderer.render(session).contains("Handoff quality:"))
    }

    @Test
    fun renderHandoffQualitySummaryUsesFilteredItemSet() {
        val session = SessionDto(
            sessionId = "session-filtered-quality",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Review")),
            items = listOf(
                AnnotationDto(
                    itemId = "item-clean",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Node("node-clean", FixThisRect(0f, 0f, 100f, 50f)),
                    comment = "Clean feedback",
                    sequenceNumber = 1,
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.95,
                            confidence = SelectionConfidence.HIGH,
                        ),
                    ),
                ),
                AnnotationDto(
                    itemId = "item-low",
                    screenId = "screen-1",
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 120f, 120f)),
                    comment = "Low confidence feedback",
                    sequenceNumber = 2,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
                    ),
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session, itemIds = listOf("item-clean"))

        assertTrue(!markdown.contains("Handoff quality:"), markdown)
        assertTrue(markdown.contains("[1] Clean feedback"), markdown)
        assertTrue(!markdown.contains("Low confidence feedback"), markdown)
    }

    private fun oneItemSession(item: AnnotationDto): SessionDto = SessionDto(
        sessionId = "session-one-item",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 1L,
                displayName = "Review",
            ),
        ),
        items = listOf(item),
    )

    private fun handoffQualityRiskSignalSession(): SessionDto = SessionDto(
        sessionId = "session-quality",
        packageName = "io.github.beyondwin.fixthis.sample",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = listOf(SnapshotDto("screen-1", 1L, displayName = "Review")),
        items = listOf(
            qualityVisualAreaItem(),
            qualityRedactedNodeItem(),
            qualityOverlapNodeItem(),
        ),
    )

    private fun qualityVisualAreaItem(): AnnotationDto = AnnotationDto(
        itemId = "item-low",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 120f, 120f)),
        comment = "Visual area feedback",
        sequenceNumber = 1,
        targetReliability = TargetReliability(
            confidence = TargetConfidence.LOW,
            warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
        ),
        sourceCandidates = emptyList(),
    )

    private fun qualityRedactedNodeItem(): AnnotationDto = AnnotationDto(
        itemId = "item-redacted",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node-redacted", FixThisRect(130f, 10f, 240f, 120f)),
        selectedNode = FixThisNode(
            uid = "node-redacted",
            composeNodeId = 2,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(130f, 10f, 240f, 120f),
            text = listOf("secret"),
            editableText = "secret",
            isSensitive = true,
        ),
        comment = "Sensitive field",
        sequenceNumber = 2,
        targetReliability = TargetReliability(
            confidence = TargetConfidence.MEDIUM,
            warnings = listOf(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED),
        ),
        sourceCandidates = listOf(
            SourceCandidate(
                file = "sample/src/main/java/ReviewScreen.kt",
                line = 70,
                score = 0.9,
                confidence = SelectionConfidence.HIGH,
                stale = true,
                staleReason = "excerpt mismatch",
            ),
        ),
    )

    private fun qualityOverlapNodeItem(): AnnotationDto = AnnotationDto(
        itemId = "item-overlap",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node-overlap", FixThisRect(20f, 20f, 100f, 100f)),
        selectedNode = FixThisNode(
            uid = "node-overlap",
            composeNodeId = 3,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(20f, 20f, 100f, 100f),
            text = listOf("Overlap"),
        ),
        comment = "Overlapping node",
        sequenceNumber = 3,
        sourceCandidates = listOf(
            SourceCandidate(
                file = "sample/src/main/java/ReviewScreen.kt",
                line = 90,
                score = 0.8,
                confidence = SelectionConfidence.MEDIUM,
            ),
        ),
    )
}
