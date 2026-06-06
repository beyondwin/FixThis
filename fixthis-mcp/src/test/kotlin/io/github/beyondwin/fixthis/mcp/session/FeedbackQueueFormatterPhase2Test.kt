package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.format.DetailMode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.handoffMessage
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceCandidateDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceKindDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceReasonDto
import io.github.beyondwin.fixthis.mcp.session.dto.EditSurfaceRoleDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackQueueFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeedbackQueueFormatterPhase2Test {
    @Test
    fun bothEmpty_preservesLegacyNoCandidateLiteral() {
        val item = annotationWith(
            sourceCandidates = emptyList(),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val session = sessionOf(item)
        val md = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        assertTrue(
            md.contains("No source candidate from current evidence; search by target labels and request."),
            "expected legacy literal preserved\n$md",
        )
    }

    @Test
    fun paired_renderEditBulletBeneathSourceCandidate() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/Home.kt", line = 5)),
            editSurfaceCandidates = listOf(
                editSurface(
                    file = "app/Home.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.CALL_SITE,
                    confidence = io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.HIGH,
                    reasons = listOf(EditSurfaceReasonDto.STYLE_INTENT),
                ),
            ),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("   - edit: containerColor role=call-site -> `app/Home.kt:42` (conf=high, why=style-intent)"),
            "missing paired edit bullet\n$md",
        )
    }

    @Test
    fun paired_omitRoleTokenWhenNull() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, role = null)),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("   - edit: containerColor -> "), "role= token should be omitted\n$md")
        assertTrue(!md.contains("role="), "role= token must not appear\n$md")
    }

    @Test
    fun paired_renderFileOnlyWhenLineNull() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = null, role = null)),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("-> `app/A.kt` ("), "expected file-only target\n$md")
        assertTrue(!md.contains("app/A.kt:"), "should not contain ':line' suffix\n$md")
    }

    @Test
    fun paired_renderEditNoteWhenPresent() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "app/A.kt", line = 1, note = "container color from M3 theme"),
            ),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("   - edit-note: container color from M3 theme"),
            "expected edit-note bullet\n$md",
        )
    }

    @Test
    fun paired_omitEditNoteWhenBlank() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, note = "   ")),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(!md.contains("edit-note:"), "blank note should be omitted\n$md")
    }

    @Test
    fun rank1Caution_rendersOnceAfterCandidates() {
        val item = annotationWith(
            sourceCandidates = listOf(
                sourceCandidate(file = "app/A.kt", caution = "candidates close; verify before editing"),
                sourceCandidate(file = "app/B.kt", caution = "second-rank caution must not render"),
            ),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("- note: candidates close; verify before editing"),
            "expected rank-1 caution line\n$md",
        )
        assertTrue(
            !md.contains("second-rank caution must not render"),
            "rank-2 caution must not render\n$md",
        )
    }

    @Test
    fun rank1Caution_omittedWhenBlank() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt", caution = "  ")),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(!md.contains("- note:"), "blank caution should produce no note line\n$md")
    }

    @Test
    fun orphanEditSurfaces_appendUnpairedBlock() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "app/A.kt", line = 1), // paired (eaten by Task 3)
                editSurface(
                    file = "theme/Colors.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                    note = "container color from theme",
                ),
                editSurface(
                    file = "theme/Spacing.kt",
                    line = 7,
                    kind = EditSurfaceKindDto.SPACING,
                    role = null,
                    note = null,
                ),
            ),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("Edit Surfaces (unpaired):"), "missing orphan header\n$md")
        assertTrue(
            md.contains("1. containerColor role=layout-or-style -> `theme/Colors.kt:42` (conf=high, why=style-intent)"),
            "missing first orphan entry\n$md",
        )
        assertTrue(
            md.contains("   - edit-note: container color from theme"),
            "missing first orphan edit-note\n$md",
        )
        assertTrue(
            md.contains("2. spacing -> `theme/Spacing.kt:7` (conf=high, why=style-intent)"),
            "missing second orphan entry without role token\n$md",
        )
    }

    @Test
    fun orphanEditSurfaces_capAtTwo() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(
                editSurface(file = "x1.kt", line = 1),
                editSurface(file = "x2.kt", line = 2),
                editSurface(file = "x3.kt", line = 3), // dropped by orphan cap
            ),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("`x1.kt:1`"))
        assertTrue(md.contains("`x2.kt:2`"))
        assertTrue(!md.contains("`x3.kt:3`"), "orphan beyond cap must be dropped\n$md")
    }

    @Test
    fun emptySource_renderEditSurfaceHintsHeader() {
        val item = annotationWith(
            sourceCandidates = emptyList(),
            editSurfaceCandidates = listOf(
                editSurface(
                    file = "theme/Colors.kt",
                    line = 42,
                    kind = EditSurfaceKindDto.CONTAINER_COLOR,
                    role = EditSurfaceRoleDto.LAYOUT_OR_STYLE,
                ),
            ),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("No source candidate; edit-surface hints:"), "missing fallback header\n$md")
        assertTrue(
            md.contains("1. containerColor role=layout-or-style -> `theme/Colors.kt:42` (conf=high, why=style-intent)"),
            "missing fallback entry\n$md",
        )
        assertTrue(!md.contains("Edit Surfaces (unpaired):"), "should not duplicate the unpaired header\n$md")
        assertTrue(
            !md.contains("No source candidate from current evidence; search by target labels and request."),
            "must not emit legacy literal when edit-surface hints exist\n$md",
        )
    }

    @Test
    fun action_emitsForVisualAreaOnly() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
                ),
            ),
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("- Action: use screenshot/bounds first, then check whether Compose source explains the pixels."),
            "expected visual-area Action line\n$md",
        )
    }

    @Test
    fun action_emitsForPossibleViewInterop() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
                ),
            ),
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("- Action: treat source candidates as hints only; AndroidView/WebView may own the pixels."),
            "expected interop Action line\n$md",
        )
    }

    @Test
    fun action_emitsForNoMeaningfulComposeTarget() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.NO_MEANINGFUL_COMPOSE_TARGET,
            ),
        )
        assertTrue(
            md.contains("- Action: no Compose semantics node covers this — search by surrounding labels."),
            "expected no-meaningful-target Action line\n$md",
        )
    }

    @Test
    fun action_emitsForSensitiveTextRedacted() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
            ),
        )
        assertTrue(
            md.contains(
                "- Action: source candidates were ranked without the redacted text — corroborate before editing.",
            ),
            "expected sensitive-text Action line\n$md",
        )
    }

    @Test
    fun action_omittedForWarningsWithoutAction() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SOURCE_INDEX_STALE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.LOW_SOURCE_CANDIDATE_MARGIN,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SCREEN_FINGERPRINT_UNAVAILABLE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SCREEN_FINGERPRINT_MISMATCH_FORCED,
            ),
        )
        assertTrue(!md.contains("- Action:"), "no Action line should be emitted\n$md")
    }

    @Test
    fun action_rendersInEnumOrderForMultipleWarnings() {
        val md = renderWith(
            warnings = listOf(
                // Construction order is reversed on purpose; output must follow enum order.
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
            ),
        )
        val visualIdx = md.indexOf("- Action: use screenshot/bounds first")
        val sensitiveIdx = md.indexOf("- Action: source candidates were ranked without the redacted text")
        assertTrue(visualIdx in 0..<sensitiveIdx, "visual-area Action must precede sensitive-text Action\n$md")
    }

    @Test
    fun action_appearsAfterWarningLines() {
        val md = renderWith(
            warnings = listOf(
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.SOURCE_INDEX_STALE,
                io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
            ),
        )
        val lastWarningIdx = md.lastIndexOf("- Warning:")
        val firstActionIdx = md.indexOf("- Action:")
        assertTrue(lastWarningIdx >= 0 && firstActionIdx > lastWarningIdx, "Action lines must follow Warning lines\n$md")
    }

    @Test
    fun compat_oldSessionWithoutPhase2FieldsHasNoNewLines() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt"), sourceCandidate(file = "app/B.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(!md.contains("- edit:"), "no edit: bullet expected\n$md")
        assertTrue(!md.contains("- edit-note:"), "no edit-note: bullet expected\n$md")
        assertTrue(!md.contains("Edit Surfaces (unpaired):"), "no orphan block expected\n$md")
        assertTrue(!md.contains("- Action:"), "no Action line expected\n$md")
        assertTrue(!md.contains("- Target confidence:"), "no target confidence line when reliability is null\n$md")
    }

    @Test
    fun cap_preciseLimitsSourceCandidatesToThree() {
        val item = annotationWith(
            sourceCandidates = (1..5).map { sourceCandidate(file = "app/F$it.kt") },
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(md.contains("`app/F1.kt`"))
        assertTrue(md.contains("`app/F3.kt`"))
        assertTrue(!md.contains("`app/F4.kt`"), "PRECISE must cap at 3 source candidates\n$md")
    }

    @Test
    fun cap_fullRendersAllSourceCandidates() {
        val item = annotationWith(
            sourceCandidates = (1..5).map { sourceCandidate(file = "app/F$it.kt") },
            editSurfaceCandidates = emptyList(),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.FULL)
        for (i in 1..5) {
            assertTrue(md.contains("`app/F$i.kt`"), "FULL must include F$i.kt\n$md")
        }
    }

    @Test
    fun equivalence_compactAndPreciseShareKeyTokens() {
        val warning =
            io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY
        val edit = editSurface(file = "app/A.kt", line = 1)
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(edit),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(warning),
            ),
        )
        val session = sessionOf(item)
        val precise = FeedbackQueueFormatter.toMarkdown(session, DetailMode.PRECISE)
        val compact = FeedbackQueueFormatter.toMarkdown(session, DetailMode.COMPACT)
        val warningBody = warning.handoffMessage()
        // Token-level equivalence only — format, ordering, line counts intentionally differ.
        assertTrue(precise.contains("low") && compact.contains("low"), "confidence token missing in one renderer")
        assertTrue(precise.contains(edit.file) && compact.contains(edit.file), "edit file missing in one renderer")
        assertTrue(
            precise.contains(warningBody) && compact.contains(warningBody),
            "warning body missing in one renderer\nprecise=$precise\ncompact=$compact",
        )
    }

    @Test
    fun escape_backtickInFilePathIsNeutralized() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = listOf(editSurface(file = "app/A.kt", line = 1, note = "watch `this` token")),
            targetReliability = null,
        )
        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
        assertTrue(
            md.contains("edit-note: watch 'this' token"),
            "expected backticks in note to be replaced with single quotes\n$md",
        )
    }

    @Test
    fun boundaryGuidance_rendersBeforeLikelySourceForInteropRisk() {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "sample/DiagnosticsScreen.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        val boundaryIndex = md.indexOf("- Boundary: possible AndroidView/WebView target")
        val sourceIndex = md.indexOf("1. `sample/DiagnosticsScreen.kt`")
        assertTrue(boundaryIndex >= 0, "missing boundary guidance\n$md")
        assertTrue(sourceIndex > boundaryIndex, "boundary guidance must precede source candidates\n$md")
        assertTrue(md.contains("- Boundary action: inspect-and-corroborate the Compose host first; verify native View/WebView ownership before editing."))
    }

    @Test
    fun boundaryContext_preciseRendersBeforeLikelySourceCandidates() {
        val host = io.github.beyondwin.fixthis.compose.core.model.FixThisNode(
            uid = "host",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = io.github.beyondwin.fixthis.compose.core.model.TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 80f, 320f, 260f),
            text = listOf("Revenue"),
            testTag = "comp:NativeChartHost:chart",
        )
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "sample/NativeChartHost.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP,
                ),
            ),
        ).copy(
            target = AnnotationTargetDto.Area(FixThisRect(40f, 120f, 220f, 220f)),
            nearbyNodes = listOf(host),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        val boundaryIndex = md.indexOf("- Boundary: possible AndroidView/WebView target")
        val contextIndex = md.indexOf("- Boundary host:")
        val sourceIndex = md.indexOf("1. `sample/NativeChartHost.kt`")
        assertTrue(boundaryIndex >= 0, "missing boundary guidance\n$md")
        assertTrue(contextIndex > boundaryIndex, "context must follow boundary guidance\n$md")
        assertTrue(sourceIndex > contextIndex, "source candidates must follow boundary context\n$md")
        assertTrue(md.contains("it does not prove Compose owns the selected pixels"), md)
    }

    @Test
    fun boundaryGuidance_visualAreaNoSourceDoesNotInventSource() {
        val item = AnnotationDto(
            itemId = "area-no-source",
            screenId = "screen-1",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 160f, 80f)),
            sourceCandidates = emptyList(),
            editSurfaceCandidates = emptyList(),
            comment = "Tighten this empty area",
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = listOf(
                    io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning.VISUAL_AREA_ONLY,
                ),
            ),
        )

        val md = FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)

        assertTrue(md.contains("- Boundary: visual area target; do not infer an exact Compose owner from nearby labels."))
        assertTrue(md.contains("No source candidate from current evidence; search by target labels and request."))
        assertTrue(!md.contains("Likely Source:\n1."), "must not invent a source candidate\n$md")
    }

    private fun renderWith(
        warnings: List<io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning>,
    ): String {
        val item = annotationWith(
            sourceCandidates = listOf(sourceCandidate(file = "app/A.kt")),
            editSurfaceCandidates = emptyList(),
            targetReliability = reliability(
                confidence = io.github.beyondwin.fixthis.compose.core.model.TargetConfidence.LOW,
                warnings = warnings,
            ),
        )
        return FeedbackQueueFormatter.toMarkdown(sessionOf(item), DetailMode.PRECISE)
    }

    private fun reliability(
        confidence: io.github.beyondwin.fixthis.compose.core.model.TargetConfidence,
        warnings: List<io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning>,
    ): io.github.beyondwin.fixthis.compose.core.model.TargetReliability = io.github.beyondwin.fixthis.compose.core.model.TargetReliability(
        confidence = confidence,
        reasons = emptyList(),
        warnings = warnings,
    )

    @Test
    fun pairing_groupsByExactFileAndCapsAtTwo() {
        val src1 = sourceCandidate(file = "app/A.kt")
        val src2 = sourceCandidate(file = "app/B.kt")
        val edits = listOf(
            editSurface(file = "app/A.kt", line = 10),
            editSurface(file = "app/A.kt", line = 20),
            editSurface(file = "app/A.kt", line = 30), // dropped by cap
            editSurface(file = "app/B.kt", line = 40),
            editSurface(file = "app/UNMATCHED.kt", line = 50),
            editSurface(file = "", line = 60), // orphan: blank file
        )
        val pairing = FeedbackQueueFormatter.buildEditSurfacePairingForTest(
            sourceCandidates = listOf(src1, src2),
            editSurfaceCandidates = edits,
        )
        assertEquals(listOf(10, 20), pairing.paired[0]?.map { it.line })
        assertEquals(listOf(40), pairing.paired[1]?.map { it.line })
        assertEquals(listOf(50, 60), pairing.orphans.map { it.line })
    }

    private fun sourceCandidate(
        file: String,
        line: Int? = null,
        confidence: io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence =
            io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.MEDIUM,
        caution: String? = null,
    ): io.github.beyondwin.fixthis.compose.core.model.SourceCandidate = io.github.beyondwin.fixthis.compose.core.model.SourceCandidate(
        file = file,
        line = line,
        score = 1.0,
        confidence = confidence,
        caution = caution,
    )

    @Suppress("LongParameterList")
    private fun editSurface(
        file: String,
        line: Int?,
        kind: EditSurfaceKindDto = EditSurfaceKindDto.CONTAINER_COLOR,
        role: EditSurfaceRoleDto? = EditSurfaceRoleDto.CALL_SITE,
        confidence: io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence =
            io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence.HIGH,
        reasons: List<EditSurfaceReasonDto> = listOf(EditSurfaceReasonDto.STYLE_INTENT),
        note: String? = null,
    ): EditSurfaceCandidateDto = EditSurfaceCandidateDto(
        kind = kind,
        file = file,
        line = line,
        confidence = confidence,
        reasons = reasons,
        note = note,
        role = role,
    )

    // -------------------- builders --------------------

    private fun annotationWith(
        sourceCandidates: List<io.github.beyondwin.fixthis.compose.core.model.SourceCandidate>,
        editSurfaceCandidates: List<EditSurfaceCandidateDto>,
        targetReliability: io.github.beyondwin.fixthis.compose.core.model.TargetReliability?,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-1",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 10f, 10f)),
        selectedNode = null,
        sourceCandidates = sourceCandidates,
        editSurfaceCandidates = editSurfaceCandidates,
        comment = "request body",
        targetReliability = targetReliability,
    )

    private fun sessionOf(vararg items: AnnotationDto): SessionDto = SessionDto(
        sessionId = "sess",
        packageName = "io.example.pkg",
        projectRoot = "/repo",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        screens = emptyList(),
        items = items.toList().mapIndexed { idx, item -> item.copy(sequenceNumber = idx + 1) },
    )
}
