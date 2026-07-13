package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateRisk
import io.github.beyondwin.fixthis.compose.core.model.TargetConfidence
import io.github.beyondwin.fixthis.compose.core.model.TargetReliability
import io.github.beyondwin.fixthis.compose.core.model.TargetReliabilityWarning
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.CompactHandoffRenderer
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceFailureReason
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceProximity
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceTrigger
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceWarning
import org.junit.Test
import kotlin.test.assertTrue

class CompactHandoffEvidenceExtensionsTest {
    @Test
    fun compactHandoffRendersSourceFirstVerificationGuidanceForStrongTargetAndSource() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    targetReliability = TargetReliability(confidence = TargetConfidence.HIGH),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.96,
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.55,
                            matchReasons = listOf("selected testTag"),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(
            markdown.contains("  verify: source-first  because=strong-target,strong-source,clear-margin"),
            markdown,
        )
        assertTrue(!markdown.contains("verifyBeforeEdit"), markdown)
    }

    @Test
    fun compactHandoffDowngradesStaleSourceCandidateToHintOnlyVerification() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.HIGH,
                        warnings = listOf(TargetReliabilityWarning.SOURCE_INDEX_STALE),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.96,
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.31,
                            stale = true,
                            staleReason = "excerpt mismatch",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  verify: hint-only"), markdown)
        assertTrue(markdown.contains("stale-source"), markdown)
        assertTrue(!markdown.contains("verifyBeforeEdit"), markdown)
        assertTrue(!markdown.contains("  verify: source-first"), markdown)
    }

    @Test
    fun compactHandoffDowngradesSharedComponentSourceToHintOnlyVerification() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    targetReliability = TargetReliability(confidence = TargetConfidence.HIGH),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/SharedCard.kt",
                            line = 24,
                            score = 0.96,
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.55,
                            riskFlags = listOf(SourceCandidateRisk.SHARED_COMPONENT),
                        ),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  verify: hint-only"), markdown)
        assertTrue(markdown.contains("source-risk"), markdown)
        assertTrue(!markdown.contains("  verify: source-first"), markdown)
    }

    @Test
    fun compactHandoffDowngradesVisualAreaTargetToManualVerification() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 120f, 80f)),
                    selectedNode = null,
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.VISUAL_AREA_ONLY),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.96,
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.31,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  verify: manual"), markdown)
        assertTrue(markdown.contains("visual-area"), markdown)
        assertTrue(!markdown.contains("verifyBeforeEdit"), markdown)
        assertTrue(!markdown.contains("  verify: source-first"), markdown)
    }

    @Test
    fun compactHandoffDowngradesInteropToHintOnlyVerification() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.LOW,
                        warnings = listOf(TargetReliabilityWarning.POSSIBLE_VIEW_INTEROP),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.68,
                            confidence = SelectionConfidence.MEDIUM,
                            scoreMargin = 0.21,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  verify: hint-only  because=interop-risk,low-target-confidence"), markdown)
        assertTrue(!markdown.contains("verifyBeforeEdit"), markdown)
        assertTrue(!markdown.contains("  verify: source-first"), markdown)
    }

    @Test
    fun compactHandoffDowngradesSensitiveWarningToManualVerification() {
        val markdown = CompactHandoffRenderer.render(
            oneItemSession(
                verificationGuidanceItem(
                    targetReliability = TargetReliability(
                        confidence = TargetConfidence.HIGH,
                        warnings = listOf(TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED),
                    ),
                    sourceCandidates = listOf(
                        SourceCandidate(
                            file = "sample/src/main/java/ReviewScreen.kt",
                            line = 56,
                            score = 0.96,
                            confidence = SelectionConfidence.HIGH,
                            scoreMargin = 0.55,
                        ),
                    ),
                ),
            ),
        )

        assertTrue(markdown.contains("  verify: manual"), markdown)
        assertTrue(markdown.contains("sensitive-redaction"), markdown)
        assertTrue(!markdown.contains("verifyBeforeEdit"), markdown)
        assertTrue(!markdown.contains("  verify: source-first"), markdown)
    }

    @Test
    fun compactHandoffRendersNewestRuntimeEvidenceMetadataAndBoundedSummariesOnly() {
        val boundedSummary = "x".repeat(180)
        val rawSecret = "RAW_SECRET_MUST_NOT_RENDER"
        val markdown = CompactHandoffRenderer.render(runtimeEvidenceSession(boundedSummary, rawSecret))

        assertRuntimeEvidenceMarkdown(markdown, boundedSummary, rawSecret)
    }

    private fun runtimeEvidenceSession(boundedSummary: String, rawSecret: String): SessionDto {
        val session = oneItemSession(
            AnnotationDto(
                itemId = "item-runtime",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
                comment = "The screen janks when this opens",
                sequenceNumber = 1,
                runtimeEvidenceIds = listOf("e-old", "e-logcat", "e-frame", "e-memory"),
            ),
        ).copy(
            runtimeEvidence = listOf(
                oldRuntimeEvidenceAttachment(),
                logcatRuntimeEvidenceAttachment(boundedSummary, rawSecret),
                frameRuntimeEvidenceAttachment(),
                memoryRuntimeEvidenceAttachment(),
            ),
        )
        return session
    }

    private fun oldRuntimeEvidenceAttachment(): RuntimeEvidenceAttachment = RuntimeEvidenceAttachment(
        evidenceId = "e-old",
        type = RuntimeEvidenceType.TRACE_ARTIFACT,
        capturedAtEpochMillis = 9L,
        packageName = "io.github.beyondwin.fixthis.sample",
        summary = "old evidence outside cap",
        artifactPath = ".fixthis/runtime-evidence/e-old/trace.perfetto",
    )

    private fun logcatRuntimeEvidenceAttachment(
        boundedSummary: String,
        rawSecret: String,
    ): RuntimeEvidenceAttachment = RuntimeEvidenceAttachment(
        evidenceId = "e-logcat",
        type = RuntimeEvidenceType.LOGCAT_WINDOW,
        capturedAtEpochMillis = 10L,
        packageName = "io.github.beyondwin.fixthis.sample",
        summary = "$boundedSummary $rawSecret",
        artifactPath = ".fixthis/runtime-evidence/e-logcat/logcat.txt",
        status = RuntimeEvidenceStatus.COMPLETE,
        trigger = RuntimeEvidenceTrigger.HANDOFF_AUTO,
        proximity = RuntimeEvidenceProximity.NEAR,
        warnings = listOf(RuntimeEvidenceWarning.REDACTION_APPLIED),
    )

    private fun frameRuntimeEvidenceAttachment(): RuntimeEvidenceAttachment = RuntimeEvidenceAttachment(
        evidenceId = "e-frame",
        type = RuntimeEvidenceType.FRAME_SUMMARY,
        capturedAtEpochMillis = 11L,
        packageName = "io.github.beyondwin.fixthis.sample",
        summary = "6 slow frames, 1 frozen frame candidate",
        artifactPath = ".fixthis/runtime-evidence/e-frame/gfxinfo.json",
        status = RuntimeEvidenceStatus.PARTIAL,
        trigger = RuntimeEvidenceTrigger.CONSOLE_MANUAL,
        proximity = RuntimeEvidenceProximity.DELAYED,
    )

    private fun memoryRuntimeEvidenceAttachment(): RuntimeEvidenceAttachment = RuntimeEvidenceAttachment(
        evidenceId = "e-memory",
        type = RuntimeEvidenceType.MEMORY_SUMMARY,
        capturedAtEpochMillis = 12L,
        packageName = "io.github.beyondwin.fixthis.sample",
        summary = "capture failed before memory summary",
        status = RuntimeEvidenceStatus.FAILED,
        trigger = RuntimeEvidenceTrigger.MCP_MANUAL,
        proximity = RuntimeEvidenceProximity.STALE,
        failureReason = RuntimeEvidenceFailureReason.PROCESS_NOT_RUNNING,
    )

    private fun assertRuntimeEvidenceMarkdown(markdown: String, boundedSummary: String, rawSecret: String) {
        assertTrue(markdown.contains("  runtimeEvidence:"), markdown)
        assertTrue(markdown.contains("    - logcat_window status=complete proximity=near"), markdown)
        assertTrue(markdown.contains("      summary: $boundedSummary"), markdown)
        assertTrue(markdown.contains("      artifact: .fixthis/runtime-evidence/e-logcat/logcat.txt"), markdown)
        assertTrue(markdown.contains("      warning: redaction_applied"), markdown)
        assertTrue(markdown.indexOf("memory_summary") < markdown.indexOf("frame_summary"), markdown)
        assertTrue(markdown.indexOf("frame_summary") < markdown.indexOf("logcat_window"), markdown)
        assertTrue(!markdown.contains("trace_artifact"), markdown)
        assertTrue(!markdown.contains(rawSecret), markdown)
    }

    private fun verificationGuidanceItem(
        target: AnnotationTargetDto = AnnotationTargetDto.Node("node-1", FixThisRect(0f, 0f, 100f, 50f)),
        selectedNode: FixThisNode? = FixThisNode(
            uid = "node-1",
            composeNodeId = 1,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(0f, 0f, 100f, 50f),
            testTag = "comp:ReviewScreen:submit",
            role = "Button",
        ),
        targetReliability: TargetReliability,
        sourceCandidates: List<SourceCandidate>,
    ): AnnotationDto = AnnotationDto(
        itemId = "item-guidance",
        screenId = "screen-1",
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        target = target,
        selectedNode = selectedNode,
        comment = "Tune this target",
        sequenceNumber = 1,
        targetReliability = targetReliability,
        sourceCandidates = sourceCandidates,
    )

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
}
