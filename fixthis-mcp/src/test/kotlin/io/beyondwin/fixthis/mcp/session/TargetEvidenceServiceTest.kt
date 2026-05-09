package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.beyondwin.fixthis.compose.core.model.SourceCandidate
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TargetEvidenceServiceTest {
    @Test
    fun nodeTargetEvidenceIncludesIdentityHintForStrictCompTag() {
        val service = targetEvidenceService()
        val selected = node(
            uid = "pay-button",
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        )
        val screen = screenWith(selected)

        val evidence = service.targetEvidenceFor(
            targetType = FeedbackTargetType.NODE,
            selectedNode = selected,
            screen = screen,
            sourceCandidates = emptyList(),
        )

        assertEquals("AppPrimaryButton", evidence.identityHint?.composableNameHint)
        assertEquals("primary", evidence.identityHint?.variantHint)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, evidence.identityHint?.source)
        assertEquals(IdentityHintConfidence.HIGH, evidence.identityHint?.confidence)
        assertEquals(EvidenceQuality.STRUCTURED, evidence.evidenceQuality)
    }

    @Test
    fun visualAreaTargetEvidenceRemainsBasicWithLowConfidenceWarning() {
        val service = targetEvidenceService()
        val evidence = service.targetEvidenceFor(
            targetType = FeedbackTargetType.AREA,
            selectedNode = null,
            screen = screenWith(),
            sourceCandidates = emptyList(),
        )

        assertEquals(EvidenceQuality.BASIC, evidence.evidenceQuality)
        assertTrue(evidence.warnings.any { it.contains("visual area selections") })
        assertEquals("No source candidate was available from current evidence.", evidence.sourceInterpretation?.caution)
    }

    @Test
    fun sourceCandidateWarningsAndReasonsArePreserved() {
        val service = targetEvidenceService()
        val selected = node(uid = "title", text = listOf("Title"))
        val candidate = SourceCandidate(
            file = "sample/src/main/java/io/github/fixthis/sample/Title.kt",
            line = 12,
            score = 0.2,
            matchedTerms = listOf("Title"),
            matchReasons = listOf("selected text", "activity"),
            confidence = SelectionConfidence.LOW,
        )

        val evidence = service.targetEvidenceFor(
            targetType = FeedbackTargetType.NODE,
            selectedNode = selected,
            screen = screenWith(selected),
            sourceCandidates = listOf(candidate),
        )

        assertEquals(listOf("selected text", "activity"), evidence.sourceInterpretation?.reasonSummary)
        assertEquals(
            "Top source candidate has low confidence; verify before editing.",
            evidence.sourceInterpretation?.caution,
        )
        assertEquals(SelectionConfidence.LOW, evidence.sourceInterpretation?.topCandidate?.confidence)
    }

    @Test
    fun sourceCandidateIsMarkedStaleWhenLiveContentDiffersFromIndexExcerpt() {
        val tmpDir = kotlin.io.path.createTempDirectory(prefix = "fixthis-staleness-test-").toFile()
            .also { it.deleteOnExit() }
        val fooKt = File(tmpDir, "Foo.kt")
        // Live file has different content than what the index excerpt says
        fooKt.writeText("package sample\n\nfun Foo() = \"live content\"\n")

        val selected = node(uid = "foo-node", text = listOf("UniqueLabelStalenessXYZ"))
        val screen = screenWith(selected)
        val sourceIndex = SourceIndex(
            entries = listOf(
                SourceIndexEntry(
                    file = "Foo.kt",
                    line = 3,
                    text = listOf("UniqueLabelStalenessXYZ"),
                    excerpt = "fun Foo() = \"original content\"",
                ),
            ),
        )
        val service = targetEvidenceService(projectRoot = tmpDir)

        val item = service.buildFeedbackItem(
            screen = screen,
            sourceIndex = sourceIndex,
            targetType = FeedbackTargetType.NODE,
            bounds = selected.boundsInWindow,
            nodeUid = selected.uid,
            comment = "needs rename",
            allowBlankComment = false,
            writtenStatus = AnnotationStatusDto.OPEN,
        )

        val candidate = item.sourceCandidates.single { it.file.endsWith("Foo.kt") }
        assertEquals(true, candidate.stale)
        assertEquals("excerpt mismatch", candidate.staleReason)
    }

    private fun targetEvidenceService(projectRoot: File = File(".").canonicalFile): TargetEvidenceService =
        TargetEvidenceService(
            bridge = FakeFixThisBridge(),
            sourceIndexRegistry = SourceIndexRegistry(),
            projectRoot = projectRoot,
        )

    private fun screenWith(vararg nodes: FixThisNode): SnapshotDto =
        SnapshotDto(
            screenId = "screen-1",
            capturedAtEpochMillis = 100L,
            displayName = "Checkout",
            screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/tmp/screen.png"),
            roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = nodes.toList())),
        )

    private fun node(
        uid: String,
        text: List<String> = emptyList(),
        role: String? = null,
        testTag: String? = null,
    ): FixThisNode =
        FixThisNode(
            uid = uid,
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = text,
            role = role,
            testTag = testTag,
        )
}
