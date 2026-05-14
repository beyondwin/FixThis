package io.beyondwin.fixthis.compose.core.format

import io.beyondwin.fixthis.compose.core.model.ActivityInfo
import io.beyondwin.fixthis.compose.core.model.AppInfo
import io.beyondwin.fixthis.compose.core.model.FixThisAnnotation
import io.beyondwin.fixthis.compose.core.model.FixThisError
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHint
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.Occurrence
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.beyondwin.fixthis.compose.core.model.ScreenshotInfo
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SelectionInfo
import io.beyondwin.fixthis.compose.core.model.SelectionKind
import io.beyondwin.fixthis.compose.core.model.SelectionSource
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.beyondwin.fixthis.compose.core.model.SourceInterpretation
import io.beyondwin.fixthis.compose.core.model.TapPoint
import io.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.beyondwin.fixthis.compose.core.model.TargetReliability
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityReason
import io.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.beyondwin.fixthis.compose.core.model.TreeKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixThisMarkdownFormatterTest {
    @Test
    fun includesUserCommentAndSelectedUiDetails() {
        val markdown = FixThisMarkdownFormatter.format(
            annotation(
                userComment = "Make the primary CTA clearer",
                selectedNode = node(
                    uid = "pay-button",
                    text = listOf("Pay now"),
                    role = "Button",
                    actions = listOf("OnClick"),
                ),
            ),
        )

        assertTrue(markdown.contains("FixThis Compose Feedback"))
        assertTrue(markdown.contains("Make the primary CTA clearer"))
        assertTrue(markdown.contains("Pay now"))
        assertTrue(markdown.contains("- UID: pay-button"))
        assertTrue(markdown.contains("- Tree: MERGED"))
        assertTrue(markdown.contains("- Actions: OnClick"))
    }

    @Test
    fun rendersBlankAndEmptyValuesAsNone() {
        val markdown = FixThisMarkdownFormatter.format(annotation(userComment = ""))

        assertTrue(markdown.contains("(No comment)"))
        assertTrue(markdown.contains("## Selected UI\n- none"))
        assertTrue(markdown.contains("## Nearby context\n- none"))
        assertTrue(markdown.contains("## Source candidates\n- none"))
        assertTrue(markdown.contains("## Search hints\n- none"))
        assertTrue(markdown.contains("## Screenshot\n- none"))
    }

    @Test
    fun prefersDesktopScreenshotPathsAndFormatsSourceCandidates() {
        val markdown = FixThisMarkdownFormatter.format(
            annotation(
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "sample/src/main/java/CheckoutScreen.kt",
                        line = 42,
                        score = 0.91,
                        matchedTerms = listOf("Pay"),
                        matchReasons = listOf("text match", "role match"),
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
                searchHints = listOf("Pay now Button"),
                screenshot = ScreenshotInfo(
                    fullPath = "/sdcard/fixthis/full.png",
                    cropPath = "/sdcard/fixthis/crop.png",
                    desktopFullPath = "/tmp/fixthis/full.png",
                    desktopCropPath = "/tmp/fixthis/crop.png",
                ),
            ),
        )

        assertTrue(markdown.contains("`sample/src/main/java/CheckoutScreen.kt:42`"))
        assertTrue(markdown.contains("score: 0.91"))
        assertTrue(markdown.contains("text match, role match"))
        assertTrue(markdown.contains("/tmp/fixthis/full.png"))
        assertTrue(markdown.contains("/tmp/fixthis/crop.png"))
        assertFalse(markdown.contains("/sdcard/fixthis/full.png"))
    }

    @Test
    fun rendersFreeFormMarkdownAsInertText() {
        val injected = "# Fake heading\n- injected"
        val markdown = FixThisMarkdownFormatter.format(
            annotation(
                userComment = injected,
                selectedNode = node(
                    uid = "uid`with`ticks\n- injected",
                    text = listOf(injected),
                    contentDescription = listOf(injected),
                    role = "Button\n- injected",
                    testTag = "cta`tag",
                    actions = listOf("OnClick\n- injected"),
                ),
                sourceCandidates = listOf(
                    SourceCandidate(
                        file = "Checkout`Screen.kt\n- injected",
                        line = 42,
                        score = 0.91,
                        matchedTerms = listOf(injected),
                        matchReasons = listOf(injected),
                        confidence = SelectionConfidence.HIGH,
                    ),
                ),
                searchHints = listOf(injected),
                screenshot = ScreenshotInfo(captureFailedReason = injected),
                errors = listOf(
                    FixThisError(
                        code = "capture\n- injected",
                        message = injected,
                        details = mapOf("path`key" to injected),
                    ),
                ),
            ),
        )

        val outsideFences = linesOutsideCodeFences(markdown)
        assertFalse(outsideFences.contains("# Fake heading"))
        assertFalse(outsideFences.contains("- injected"))
        assertTrue(markdown.contains("```text\n# Fake heading\n- injected\n```"))
        assertTrue(markdown.contains("\\# Fake heading\\n\\- injected"))
    }

    @Test
    fun jsonFormatterOmitsNullsAndComputedRectPropertiesButKeepsDefaults() {
        val json = FixThisJsonFormatter.format(annotation(userComment = "Check spacing"))
        val root = Json.parseToJsonElement(json).jsonObject
        val app = root.getValue("app").jsonObject

        assertTrue(json.contains("\"schemaVersion\": \"1.0\""))
        assertTrue(json.contains("\"platform\": \"android-compose\""))
        assertFalse(root.containsKey("selectedNode"))
        assertFalse(root.containsKey("screenshot"))
        assertFalse(app.containsKey("versionName"))
        assertTrue(root.containsKey("candidatesAtPoint"))
        assertTrue(root.containsKey("scopeCandidates"))
        assertTrue(root.containsKey("nearbyNodes"))
        assertTrue(root.containsKey("errors"))

        val rootWithRect = Json.parseToJsonElement(
            FixThisJsonFormatter.format(annotation(selectedNode = node(uid = "node-1"))),
        ).jsonObject
        val bounds = rootWithRect.getValue("selectedNode")
            .jsonObject
            .getValue("boundsInWindow")
            .jsonObject
        assertFalse(bounds.containsKey("width"))
        assertFalse(bounds.containsKey("height"))
        assertFalse(bounds.containsKey("area"))
    }

    @Test
    fun compactModeIncludesRequestTargetTopSourceAndOmitsNearbyDetails() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.COMPACT)

        assertTrue(markdown.contains("# FixThis Feedback"))
        assertTrue(markdown.contains("Request:"))
        assertTrue(markdown.contains("Target:"))
        assertTrue(markdown.contains("AppPrimaryButton"))
        assertTrue(markdown.contains("Occurrence: 1/2"))
        assertTrue(markdown.contains("AppPrimaryButton.kt:42"))
        assertFalse(markdown.contains("Top Source:"))
        assertFalse(markdown.contains("Nearby context"))
        assertFalse(markdown.contains("SecondaryButton.kt"))
    }

    @Test
    fun preciseModeIncludesTopThreeSourcesAndEvidenceWarnings() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.PRECISE)

        assertTrue(markdown.contains("## Target Evidence"))
        assertTrue(markdown.contains("Identity:"))
        assertTrue(markdown.contains("Occurrence: 1/2"))
        assertTrue(markdown.contains("Caution:"))
        assertTrue(markdown.contains("Warnings:"))
        assertTrue(markdown.contains("## Source Candidates"))
        assertTrue(markdown.contains("AppPrimaryButton.kt:42"))
        assertTrue(markdown.contains("SecondaryButton.kt:7"))
        assertTrue(markdown.contains("LoginScreen.kt:88"))
        assertFalse(markdown.contains("UnusedButton.kt:12"))
    }

    @Test
    fun fullModeKeepsExistingVerboseSections() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.FULL)

        assertTrue(markdown.contains("## Selection"))
        assertTrue(markdown.contains("## Selected UI"))
        assertTrue(markdown.contains("## Nearby context"))
        assertTrue(markdown.contains("## Source candidates"))
    }

    @Test
    fun formatWithoutDetailModeMatchesFullModeForLegacyCallers() {
        val annotation = annotationWithTargetEvidence()

        assertEquals(
            FixThisMarkdownFormatter.format(annotation, DetailMode.FULL),
            FixThisMarkdownFormatter.format(annotation),
        )
    }

    @Test
    fun fullModeIncludesAllSourceCandidates() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithTargetEvidence(), DetailMode.FULL)

        assertTrue(markdown.contains("AppPrimaryButton.kt:42"))
        assertTrue(markdown.contains("SecondaryButton.kt:7"))
        assertTrue(markdown.contains("LoginScreen.kt:88"))
        assertTrue(markdown.contains("UnusedButton.kt:12"))
    }

    @Test
    fun compactModeFallsBackToNodeEvidenceWhenTargetEvidenceIsNull() {
        val markdown = FixThisMarkdownFormatter.format(
            annotation(
                selectedNode = node(
                    uid = "pay-button",
                    text = listOf("Pay now"),
                    role = "Button",
                    testTag = "checkout:pay",
                ),
            ),
            DetailMode.COMPACT,
        )

        assertTrue(markdown.contains("Target:"))
        assertTrue(markdown.contains("- UID: pay-button"))
        assertTrue(markdown.contains("- Role: Button"))
        assertTrue(markdown.contains("- Text: Pay now"))
        assertTrue(markdown.contains("- Test tag: checkout:pay"))
        assertFalse(markdown.contains("- Identity:"))
        assertFalse(markdown.contains("- Occurrence:"))
    }

    @Test
    fun compactModeIncludesTargetReliabilityWarning() {
        val markdown = FixThisMarkdownFormatter.format(
            annotation().copy(
                targetReliability = TargetReliability(
                    confidence = TargetConfidence.LOW,
                    reasons = listOf(TargetReliabilityReason.VISUAL_AREA_SELECTION),
                    warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                ),
            ),
            DetailMode.COMPACT,
        )

        assertTrue(markdown.contains("Target confidence: low"))
        assertTrue(markdown.contains("possible AndroidView/WebView area"))
    }

    @Test
    fun preciseModeStatesBasicSemanticsWhenTargetEvidenceIsNull() {
        val markdown = FixThisMarkdownFormatter.format(annotation(selectedNode = node(uid = "pay-button")), DetailMode.PRECISE)

        assertTrue(markdown.contains("## Target Evidence"))
        assertTrue(markdown.contains("- Evidence: basic semantics only"))
        assertTrue(markdown.contains("## Selected UI"))
    }

    @Test
    fun compactModeEscapesRenderedTargetEvidenceFields() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithEscapedTargetEvidence(), DetailMode.COMPACT)
        val outsideFences = linesOutsideCodeFences(markdown)

        assertTrue(markdown.contains("- Label: \\# Stable label\\n\\- injected"))
        assertTrue(markdown.contains("- Caution: \\\\> caution\\n1\\. ordered"))
        assertTrue(markdown.contains("- Warnings: \\\\+ warning, \\# second warning"))
        assertFalse(outsideFences.contains("- injected"))
        assertFalse(outsideFences.contains("> caution"))
    }

    @Test
    fun preciseModeEscapesRenderedTargetEvidenceFields() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithEscapedTargetEvidence(), DetailMode.PRECISE)
        val outsideFences = linesOutsideCodeFences(markdown)

        assertTrue(markdown.contains("- Label: \\# Stable label\\n\\- injected"))
        assertTrue(markdown.contains("``sample/src/main/`Danger.kt\\n- injected:27``"))
        assertTrue(markdown.contains("- Source reasons: \\# reason, \\- second reason"))
        assertTrue(markdown.contains("- Caution: \\\\> caution\\n1\\. ordered"))
        assertTrue(markdown.contains("- Warnings: \\\\+ warning, \\# second warning"))
        assertTrue(markdown.contains("- Screenshot evidence: full\\`shot, \\\\> crop"))
        assertFalse(outsideFences.contains("- injected"))
        assertFalse(outsideFences.contains("# reason"))
        assertFalse(outsideFences.contains("> crop"))
    }

    @Test
    fun fullModeEscapesRenderedTargetEvidenceFields() {
        val markdown = FixThisMarkdownFormatter.format(annotationWithEscapedTargetEvidence(), DetailMode.FULL)
        val outsideFences = linesOutsideCodeFences(markdown)

        assertTrue(markdown.contains("- Label: \\# Stable label\\n\\- injected"))
        assertTrue(markdown.contains("``sample/src/main/`Danger.kt\\n- injected:27``"))
        assertTrue(markdown.contains("- Source reasons: \\# reason, \\- second reason"))
        assertTrue(markdown.contains("- Caution: \\\\> caution\\n1\\. ordered"))
        assertTrue(markdown.contains("- Warnings: \\\\+ warning, \\# second warning"))
        assertTrue(markdown.contains("- Screenshot evidence: full\\`shot, \\\\> crop"))
        assertFalse(outsideFences.contains("- injected"))
        assertFalse(outsideFences.contains("# reason"))
        assertFalse(outsideFences.contains("> crop"))
    }

    private fun annotationWithTargetEvidence(): FixThisAnnotation {
        val selectedNode = node(
            uid = "pay-button",
            text = listOf("Sign In"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
            actions = listOf("OnClick"),
        )
        return annotation(
            userComment = "Button color is too muted",
            selectedNode = selectedNode,
            sourceCandidates = listOf(
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                    line = 42,
                    score = 1.0,
                    matchedTerms = listOf("AppPrimaryButton"),
                    matchReasons = listOf("selected testTag convention composable"),
                    confidence = SelectionConfidence.HIGH,
                ),
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/SecondaryButton.kt",
                    line = 7,
                    score = 0.72,
                    matchedTerms = listOf("Button"),
                    matchReasons = listOf("role match"),
                    confidence = SelectionConfidence.MEDIUM,
                ),
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/LoginScreen.kt",
                    line = 88,
                    score = 0.61,
                    matchedTerms = listOf("Sign In"),
                    matchReasons = listOf("text match"),
                    confidence = SelectionConfidence.MEDIUM,
                ),
                SourceCandidate(
                    file = "sample/src/main/java/io/beyondwin/fixthis/sample/UnusedButton.kt",
                    line = 12,
                    score = 0.1,
                    matchedTerms = listOf("Button"),
                    matchReasons = listOf("low confidence fallback"),
                    confidence = SelectionConfidence.LOW,
                ),
            ),
        ).copy(
            targetEvidence = TargetEvidence(
                identityHint = IdentityHint(
                    composableNameHint = "AppPrimaryButton",
                    variantHint = "primary",
                    stableLabel = "Button Sign In",
                    source = IdentityHintSource.TEST_TAG_CONVENTION,
                    confidence = IdentityHintConfidence.HIGH,
                ),
                occurrence = Occurrence(
                    signature = OccurrenceSignature(
                        type = OccurrenceSignatureType.IDENTITY_HINT,
                        value = "AppPrimaryButton:primary",
                    ),
                    count = 2,
                    selectedOrdinal = 1,
                ),
                sourceInterpretation = SourceInterpretation(
                    topCandidate = SourceCandidateSummary(
                        file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                        line = 42,
                        confidence = SelectionConfidence.HIGH,
                    ),
                    reasonSummary = listOf("selected testTag convention composable"),
                    caution = "Multiple matching primary buttons were captured",
                ),
                warnings = listOf("verify repeated target ordinal before editing"),
            ),
        )
    }

    private fun annotationWithEscapedTargetEvidence(): FixThisAnnotation = annotation(
        userComment = "Escape target evidence",
        selectedNode = node(
            uid = "target-node",
            text = listOf("Submit"),
            role = "Button",
        ),
        sourceCandidates = listOf(
            SourceCandidate(
                file = "sample/src/main/`Danger.kt\n- injected",
                line = 27,
                score = 0.82,
                matchedTerms = listOf("Submit"),
                matchReasons = listOf("target evidence source"),
                confidence = SelectionConfidence.MEDIUM,
            ),
        ),
    ).copy(
        targetEvidence = TargetEvidence(
            identityHint = IdentityHint(
                composableNameHint = "DangerButton",
                stableLabel = "# Stable label\n- injected",
                source = IdentityHintSource.SEMANTICS,
                confidence = IdentityHintConfidence.MEDIUM,
            ),
            occurrence = Occurrence(
                signature = OccurrenceSignature(
                    type = OccurrenceSignatureType.ROLE_PLUS_TEXT,
                    value = "Button:Submit",
                ),
                count = 1,
                selectedOrdinal = 1,
            ),
            sourceInterpretation = SourceInterpretation(
                topCandidate = SourceCandidateSummary(
                    file = "sample/src/main/`Danger.kt\n- injected",
                    line = 27,
                    confidence = SelectionConfidence.MEDIUM,
                ),
                reasonSummary = listOf("# reason", "- second reason"),
                caution = "> caution\n1. ordered",
            ),
            screenshotKinds = listOf("full`shot", "> crop"),
            warnings = listOf("+ warning", "# second warning"),
        ),
    )

    private fun annotation(
        userComment: String = "Please inspect this",
        selectedNode: FixThisNode? = null,
        sourceCandidates: List<SourceCandidate> = emptyList(),
        searchHints: List<String> = emptyList(),
        screenshot: ScreenshotInfo? = null,
        errors: List<FixThisError> = emptyList(),
    ): FixThisAnnotation = FixThisAnnotation(
        id = "annotation-1",
        createdAtEpochMillis = 1_714_000_000_000,
        app = AppInfo(packageName = "io.beyondwin.fixthis.sample", debuggable = true),
        activity = ActivityInfo(className = "MainActivity"),
        tap = TapPoint(xInWindow = 12f, yInWindow = 34f),
        selection = SelectionInfo(
            kind = SelectionKind.SEMANTICS_NODE,
            confidence = SelectionConfidence.HIGH,
            selectedUid = selectedNode?.uid,
            source = SelectionSource.TAP_SELECT,
        ),
        selectedNode = selectedNode,
        sourceCandidates = sourceCandidates,
        searchHints = searchHints,
        screenshot = screenshot,
        userComment = userComment,
        errors = errors,
    )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        contentDescription: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
        actions: List<String> = emptyList(),
    ): FixThisNode = FixThisNode(
        uid = uid,
        composeNodeId = 7,
        rootIndex = 0,
        treeKind = TreeKind.MERGED,
        boundsInWindow = FixThisRect(left = 1f, top = 2f, right = 101f, bottom = 52f),
        text = text,
        contentDescription = contentDescription,
        role = role,
        testTag = testTag,
        actions = actions,
    )

    @Test
    fun compactFormatEmitsSrcTokenLineWithLowercaseConfidence() {
        val annotation = annotationWithSingleSource(
            confidence = SelectionConfidence.MEDIUM,
            reasons = listOf("selected text"),
            risk = SourceCandidateRisk.TEXT_ONLY,
        )

        val markdown = FixThisMarkdownFormatter.format(annotation, DetailMode.COMPACT)

        val sourceLine = markdown.lines().firstOrNull { it.trim().startsWith("src?") }
        assertNotNull(sourceLine)
        assertTrue(sourceLine!!.contains(" medium"))
        assertTrue(sourceLine.contains("why=text"))
        assertTrue(sourceLine.contains("risk=text-only"))
    }

    private fun annotationWithSingleSource(
        confidence: SelectionConfidence,
        reasons: List<String>,
        risk: SourceCandidateRisk,
    ): FixThisAnnotation = annotation(
        userComment = "Fix this button",
        selectedNode = node(uid = "pay-button", text = listOf("Pay now"), role = "Button"),
        sourceCandidates = listOf(
            SourceCandidate(
                file = "sample/src/main/java/io/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                line = 42,
                score = 0.8,
                matchedTerms = listOf("Pay now"),
                matchReasons = reasons,
                confidence = confidence,
                riskFlags = listOf(risk),
            ),
        ),
    )

    private fun linesOutsideCodeFences(markdown: String): Set<String> {
        var inFence = false
        return markdown.lineSequence()
            .filter { line ->
                if (line.startsWith("```")) {
                    inFence = !inFence
                    false
                } else {
                    !inFence
                }
            }
            .toSet()
    }
}
