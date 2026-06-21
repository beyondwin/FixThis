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
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
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
    fun compactHandoffRendersRuntimeEvidenceSummariesOnly() {
        val session = oneItemSession(
            AnnotationDto(
                itemId = "item-runtime",
                screenId = "screen-1",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 30f, 40f)),
                comment = "The screen janks when this opens",
                sequenceNumber = 1,
                runtimeEvidenceIds = listOf("e-logcat", "e-frame"),
            ),
        ).copy(
            runtimeEvidence = listOf(
                RuntimeEvidenceAttachment(
                    evidenceId = "e-logcat",
                    type = RuntimeEvidenceType.LOGCAT_WINDOW,
                    capturedAtEpochMillis = 10L,
                    packageName = "io.github.beyondwin.fixthis.sample",
                    summary = "2 RuntimeException lines from MainActivity",
                    artifactPath = ".fixthis/runtime-evidence/e-logcat/logcat.txt",
                ),
                RuntimeEvidenceAttachment(
                    evidenceId = "e-frame",
                    type = RuntimeEvidenceType.FRAME_SUMMARY,
                    capturedAtEpochMillis = 11L,
                    packageName = "io.github.beyondwin.fixthis.sample",
                    summary = "6 slow frames, 1 frozen frame candidate",
                    artifactPath = ".fixthis/runtime-evidence/e-frame/gfxinfo.json",
                ),
            ),
        )

        val markdown = CompactHandoffRenderer.render(session)

        assertTrue(markdown.contains("  runtimeEvidence:"), markdown)
        assertTrue(markdown.contains("    - logcat_window -> .fixthis/runtime-evidence/e-logcat/logcat.txt"), markdown)
        assertTrue(markdown.contains("      summary: 2 RuntimeException lines from MainActivity"), markdown)
        assertTrue(markdown.contains("    - frame_summary -> .fixthis/runtime-evidence/e-frame/gfxinfo.json"), markdown)
        assertTrue(!markdown.contains("full raw log line"), markdown)
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
