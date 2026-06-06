package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.draft.FeedbackDraftService
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackQueueFormatter
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCaptureService
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewSnapshotCache
import io.github.beyondwin.fixthis.mcp.session.preview.ScreenshotArtifactPromoter
import io.github.beyondwin.fixthis.mcp.session.source.SourceIndexRegistry
import io.github.beyondwin.fixthis.mcp.session.target.TargetEvidenceService
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackDraftServiceTest {
    @Test
    fun blankPendingCommentsAreRejectedEvenForLegacyBlankSaveFlag() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-blank-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)
        val blankItem = AnnotationDraftDto(
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(112f, 426f, 351f, 588f),
            comment = "",
        )

        assertFailsWith<IllegalArgumentException> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(blankItem),
                allowBlankComments = false,
            )
        }

        val legacyFlagError = assertFailsWith<IllegalArgumentException> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(blankItem),
                allowBlankComments = true,
            )
        }
        assertTrue(legacyFlagError.message!!.contains("blank comment"))
    }

    @Test
    fun writtenPendingCommentsArePersistedBeforeCopyPromptOrSendAgent() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1", "handoff-1"),
            prefix = "fixthis-draft-written-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Persist this before handoff",
                ),
            ),
            allowBlankComments = false,
        )

        val currentSession = fixture.store.getSession(session.sessionId)
        val copyPrompt = FeedbackQueueFormatter.toMarkdown(currentSession)
        val sent = fixture.draftService.sendDraftToAgent(
            session.sessionId,
            targetItemIds = currentSession.items.map { it.itemId },
        )

        assertTrue(copyPrompt.contains("Persist this before handoff"))
        assertTrue(sent.handoffBatches.single().markdownSnapshot.orEmpty().contains("Persist this before handoff"))
        assertEquals(FeedbackDelivery.SENT, sent.items.single().delivery)
    }

    @Test
    fun previewPromotionHappensOncePerFrozenPreviewSave() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1", "item-2"),
            prefix = "fixthis-draft-promotion-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)
        val previewDirectory = fixture.root
            .resolve(".fixthis/preview-cache/${session.sessionId}/${preview.previewId}")
        assertTrue(previewDirectory.isDirectory)

        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Change area",
                ),
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.NODE,
                    nodeUid = "email-label",
                    bounds = FixThisRect(28f, 77f, 692f, 186f),
                    comment = "Rename label",
                ),
            ),
            allowBlankComments = false,
        )

        assertEquals(1, updated.screens.size)
        assertEquals(listOf("screen-1", "screen-1"), updated.items.map { it.screenId })
        assertFalse(previewDirectory.exists())
        assertTrue(updated.screens.single().screenshot?.desktopFullPath.orEmpty().contains("artifacts/screens/screen-1"))
    }

    @Test
    fun addFeedbackItemRejectsInvalidInputBeforeReadingSourceIndex() = runBlocking {
        suspend fun assertInvalidInputDoesNotReadSourceIndex(
            action: suspend (DraftFixture, SessionDto, SnapshotDto) -> Unit,
            expectedMessage: String,
        ) {
            val fixture = draftFixture(
                ids = arrayOf("session-1", "item-1"),
                prefix = "fixthis-draft-add-invalid-",
            )
            val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
            val screen = fixture.store.addScreen(
                sessionId = session.sessionId,
                screen = previewScreen(sourceIndexAvailable = true),
            )

            val error = assertFailsWith<IllegalArgumentException> {
                action(fixture, session, screen)
            }

            assertTrue(error.message.orEmpty().contains(expectedMessage))
            assertEquals(0, fixture.bridge.readSourceIndexCount)
        }

        assertInvalidInputDoesNotReadSourceIndex(
            action = { fixture, session, screen ->
                fixture.draftService.addFeedbackItem(
                    sessionId = session.sessionId,
                    screenId = screen.screenId,
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(10f, 10f, 40f, 40f),
                    nodeUid = null,
                    comment = "",
                )
            },
            expectedMessage = "Feedback comment must not be blank",
        )
        assertInvalidInputDoesNotReadSourceIndex(
            action = { fixture, session, screen ->
                fixture.draftService.addFeedbackItem(
                    sessionId = session.sessionId,
                    screenId = screen.screenId,
                    targetType = FeedbackTargetType.NODE,
                    bounds = FixThisRect(10f, 10f, 40f, 40f),
                    nodeUid = "missing-node",
                    comment = "Missing node",
                )
            },
            expectedMessage = "Selected node does not exist on screen: missing-node",
        )
        assertInvalidInputDoesNotReadSourceIndex(
            action = { fixture, session, screen ->
                fixture.draftService.addFeedbackItem(
                    sessionId = session.sessionId,
                    screenId = screen.screenId,
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(-1f, 10f, 40f, 40f),
                    nodeUid = null,
                    comment = "Bad bounds",
                )
            },
            expectedMessage = "Selection bounds must be inside the screenshot",
        )
    }

    @Test
    fun previewSaveReportsPreviewSpecificMissingNodeContext() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-preview-missing-node-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        val error = assertFailsWith<IllegalArgumentException> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.NODE,
                        bounds = FixThisRect(10f, 10f, 40f, 40f),
                        nodeUid = "missing-node",
                        comment = "Missing node",
                    ),
                ),
                allowBlankComments = false,
            )
        }

        assertTrue(error.message.orEmpty().contains("Selected node does not exist on preview: missing-node"))
    }

    @Test
    fun invalidPreviewSaveReleasesReservationSoRetryCanSucceed() = runBlocking {
        val fixture = draftFixture(
            ids = arrayOf("session-1", "preview-1", "screen-1", "item-1"),
            prefix = "fixthis-draft-reservation-release-",
        )
        val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
        val preview = fixture.previewCaptureService.capturePreview(session)

        assertFailsWith<IllegalArgumentException> {
            fixture.draftService.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.NODE,
                        nodeUid = "missing-node",
                        bounds = FixThisRect(1f, 1f, 10f, 10f),
                        comment = "Invalid node",
                    ),
                ),
                allowBlankComments = false,
            )
        }

        val updated = fixture.draftService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Retry succeeds",
                ),
            ),
            allowBlankComments = false,
        )

        assertEquals(listOf("Retry succeeds"), updated.items.map { it.comment })
    }

    @Test
    fun fallbackPreviewSaveRejectsInvalidPendingItemsBeforeReadingSourceIndex() = runBlocking {
        fun assertInvalidFallbackItemDoesNotReadSourceIndex(
            item: AnnotationDraftDto,
            allowBlankComments: Boolean,
            expectedMessage: String,
        ) {
            val fixture = draftFixture(
                ids = arrayOf("session-1", "item-1"),
                prefix = "fixthis-draft-fallback-invalid-",
            )
            val session = fixture.store.openSession("io.github.beyondwin.fixthis.sample", fixture.root.absolutePath)
            val fallbackScreen = previewScreen(sourceIndexAvailable = true)

            val error = assertFailsWith<IllegalArgumentException> {
                fixture.draftService.savePreviewFeedbackItems(
                    sessionId = session.sessionId,
                    previewId = "preview-missing-from-cache",
                    items = listOf(item),
                    fallbackScreen = fallbackScreen,
                    allowBlankComments = allowBlankComments,
                )
            }

            assertTrue(error.message.orEmpty().contains(expectedMessage))
            assertEquals(0, fixture.bridge.readSourceIndexCount)
        }

        assertInvalidFallbackItemDoesNotReadSourceIndex(
            item = AnnotationDraftDto(
                targetType = FeedbackTargetType.NODE,
                bounds = FixThisRect(10f, 10f, 40f, 40f),
                nodeUid = "missing-node",
                comment = "Missing node",
            ),
            allowBlankComments = false,
            expectedMessage = "Selected node does not exist on preview: missing-node",
        )
        assertInvalidFallbackItemDoesNotReadSourceIndex(
            item = AnnotationDraftDto(
                targetType = FeedbackTargetType.AREA,
                bounds = FixThisRect(-1f, 10f, 40f, 40f),
                comment = "Bad bounds",
            ),
            allowBlankComments = false,
            expectedMessage = "Selection bounds must be inside the screenshot",
        )
        assertInvalidFallbackItemDoesNotReadSourceIndex(
            item = AnnotationDraftDto(
                targetType = FeedbackTargetType.AREA,
                bounds = FixThisRect(10f, 10f, 40f, 40f),
                comment = "",
            ),
            allowBlankComments = false,
            expectedMessage = "Feedback comment must not be blank",
        )
    }

    private fun draftFixture(ids: Array<String>, prefix: String): DraftFixture {
        val root = tempDir(prefix)
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds(*ids),
        )
        val previewCache = PreviewSnapshotCache(3)
        val targetEvidenceService = TargetEvidenceService(
            bridge = bridge,
            sourceIndexRegistry = SourceIndexRegistry(),
        )
        val previewCaptureService = PreviewCaptureService(
            bridge = bridge,
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
        )
        val draftService = FeedbackDraftService(
            store = store,
            previewCache = previewCache,
            targetEvidenceService = targetEvidenceService,
            screenshotArtifactPromoter = ScreenshotArtifactPromoter(),
        )
        return DraftFixture(root, bridge, store, previewCaptureService, draftService)
    }

    private data class DraftFixture(
        val root: File,
        val bridge: FakeFixThisBridge,
        val store: FeedbackSessionStore,
        val previewCaptureService: PreviewCaptureService,
        val draftService: FeedbackDraftService,
    )

    private fun previewScreen(sourceIndexAvailable: Boolean): SnapshotDto = SnapshotDto(
        screenId = "screen-1",
        capturedAtEpochMillis = 100L,
        displayName = "Checkout",
        roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(
                    FixThisNode(
                        uid = "email-label",
                        composeNodeId = 42,
                        rootIndex = 0,
                        treeKind = TreeKind.MERGED,
                        boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
                        text = listOf("Email address"),
                        testTag = "emailField",
                    ),
                ),
            ),
        ),
        sourceIndexAvailable = sourceIndexAvailable,
        screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
    )

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

    private fun sequenceClock(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: values.last() }
    }

    private fun sequenceIds(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: error("No more ids configured") }
    }
}
