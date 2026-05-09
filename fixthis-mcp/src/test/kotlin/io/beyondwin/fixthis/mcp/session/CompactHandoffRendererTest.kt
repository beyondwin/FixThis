package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
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

    // ---- Task 2.1: candidates block tests ----


    // ---- Task 2.2: rank-1 enrichment (margin + matched) ----

    private fun makeSessionWith2Candidates(
        rank1ScoreMargin: Double?,
        rank1MatchReasons: List<String>,
    ): SessionDto = SessionDto(
        sessionId = "session-rank1",
        packageName = "io.beyondwin.fixthis.sample",
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

        val expectedLine = "    ~ src/.../HomeScreen.kt:44  conf=medium  margin=0.30  matched=[tag, compTag, nearbyTag]"
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

        // rank-2 line should be plain: ~ Other.kt:99  conf=low — no margin= or matched=
        val rank2Line = lines.firstOrNull { it.contains("Other.kt:99") }
        assertTrue(
            rank2Line != null,
            "Expected a line containing 'Other.kt:99' but got:\n$markdown",
        )
        assertTrue(
            rank2Line!!.trim() == "~ Other.kt:99  conf=low",
            "Expected rank-2 line to be plain '~ Other.kt:99  conf=low' but got: '$rank2Line'",
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
            packageName = "io.beyondwin.fixthis.sample",
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

        assertTrue(
            lines.any { it == "  candidates:" },
            "Expected exactly '  candidates:' line but got:\n$markdown",
        )
        // After Task 2.4: margin is computed from score difference (0.95 - 0.75 = 0.20) when scoreMargin is null
        assertTrue(
            lines.any { it == "    ~ AppPrimaryButton.kt:42  conf=high  margin=0.20  matched=[compTag]" },
            "Expected '    ~ AppPrimaryButton.kt:42  conf=high  margin=0.20  matched=[compTag]' but got:\n$markdown",
        )
        assertTrue(
            lines.any { it == "    ~ CheckoutScreen.kt:88  conf=medium" },
            "Expected '    ~ CheckoutScreen.kt:88  conf=medium' but got:\n$markdown",
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
            packageName = "io.beyondwin.fixthis.sample",
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
        val candidateLines = markdown.lines().filter { it.trim().startsWith("~ ") }
        assertTrue(
            candidateLines.size == 3,
            "Expected exactly 3 '~ ' candidate lines when 5 candidates provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    @Test
    fun renderEmitsExactlyOneCandidateLineWhenOneProvided() {
        val session = makeSessionWithNCandidates(1)
        val markdown = CompactHandoffRenderer.render(session)
        val candidateLines = markdown.lines().filter { it.trim().startsWith("~ ") }
        assertTrue(
            candidateLines.size == 1,
            "Expected exactly 1 '~ ' candidate line when 1 candidate provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    @Test
    fun renderEmitsExactlyTwoCandidateLinesWhenTwoProvided() {
        val session = makeSessionWithNCandidates(2)
        val markdown = CompactHandoffRenderer.render(session)
        val candidateLines = markdown.lines().filter { it.trim().startsWith("~ ") }
        assertTrue(
            candidateLines.size == 2,
            "Expected exactly 2 '~ ' candidate lines when 2 candidates provided, but got ${candidateLines.size}:\n$markdown",
        )
    }

    // ---- Task 2.4: prefer wire scoreMargin, else compute ----

    @Test
    fun renderEmitsWireMarginWhenNonNull() {
        // Test A: rank-1 with non-null scoreMargin=0.42 should emit margin=0.42 (wire value, not recomputed)
        val session = SessionDto(
            sessionId = "session-wire-margin",
            packageName = "io.beyondwin.fixthis.sample",
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
                            scoreMargin = 0.42,  // wire value present
                        ),
                        SourceCandidate(
                            file = "OtherScreen.kt",
                            line = 20,
                            score = 0.50,  // difference would be 0.49, not 0.42
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
            packageName = "io.beyondwin.fixthis.sample",
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
                            scoreMargin = null,  // wire field is null -> must compute
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
            packageName = "io.beyondwin.fixthis.sample",
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
                            scoreMargin = null,  // no runner-up, no wire margin
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

    @Test
    fun renderEmitsCandidatesUnknownWhenSourceCandidatesEmpty() {
        val session = SessionDto(
            sessionId = "session-nocand",
            packageName = "io.beyondwin.fixthis.sample",
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
            lines.any { it == "  candidates:" },
            "Expected exactly '  candidates:' line but got:\n$markdown",
        )
        assertTrue(
            lines.any { it == "    ~ unknown" },
            "Expected '    ~ unknown' for empty candidates but got:\n$markdown",
        )
        assertTrue(
            !lines.any { it.trim().startsWith("src?") },
            "Expected no 'src?' line in v2 output but got:\n$markdown",
        )
    }
}
