package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationStartContext
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackVerificationResolutionTest {
    @Test
    fun latestPassLinksInTheSameDurableItemUpdate() = withFixture { fixture ->
        fixture.attachReceipt("pass-1", FeedbackVerificationVerdict.PASS, createdAt = 10L)
        fixture.attachReceipt("pass-2", FeedbackVerificationVerdict.PASS, createdAt = 20L)

        val resolved = fixture.service.resolveFeedback(
            fixture.sessionId,
            fixture.itemId,
            AnnotationStatusDto.RESOLVED,
            "verified",
            "pass-2",
        )

        assertEquals(AnnotationStatusDto.RESOLVED, resolved.status)
        assertEquals("verified", resolved.agentSummary)
        assertEquals("pass-2", resolved.resolutionVerificationReceiptId)
        assertEquals(1, fixture.events().count { it.type == "updateItemStatus" })
        val eventItem = fixture.events().single { it.type == "updateItemStatus" }
            .payload.getValue("items")
            .toString()
        assertTrue(eventItem.contains("\"status\":\"resolved\""), eventItem)
        assertTrue(eventItem.contains("\"agentSummary\":\"verified\""), eventItem)
        assertTrue(eventItem.contains("\"resolutionVerificationReceiptId\":\"pass-2\""), eventItem)
        assertEquals("pass-2", fixture.reopen().getSession(fixture.sessionId).items.single().resolutionVerificationReceiptId)
    }

    @Test
    fun latestWarnCanLinkToResolved() = withFixture { fixture ->
        fixture.attachReceipt("warn-1", FeedbackVerificationVerdict.WARN, createdAt = 20L)

        val resolved = fixture.service.resolveFeedback(
            fixture.sessionId,
            fixture.itemId,
            AnnotationStatusDto.RESOLVED,
            "human reviewed",
            "warn-1",
        )

        assertEquals("warn-1", resolved.resolutionVerificationReceiptId)
    }

    @Test
    fun sameTimestampUsesReceiptIdAsLatestTieBreak() = withFixture { fixture ->
        fixture.attachReceipt("receipt-a", FeedbackVerificationVerdict.PASS, createdAt = 20L)
        fixture.attachReceipt("receipt-b", FeedbackVerificationVerdict.PASS, createdAt = 20L)

        fixture.assertResolveError("VERIFICATION_RECEIPT_NOT_LATEST:", "receipt-a")
        val resolved = fixture.service.resolveFeedback(
            fixture.sessionId,
            fixture.itemId,
            AnnotationStatusDto.RESOLVED,
            "done",
            "receipt-b",
        )

        assertEquals("receipt-b", resolved.resolutionVerificationReceiptId)
    }

    @Test
    fun rejectsMissingFailedCrossItemOldAndNonResolvedReceiptLinks() = withFixture { fixture ->
        fixture.attachReceipt("old", FeedbackVerificationVerdict.PASS, createdAt = 10L)
        fixture.attachReceipt("failed", FeedbackVerificationVerdict.FAIL, createdAt = 20L)
        fixture.attachReceipt("other-item", FeedbackVerificationVerdict.PASS, createdAt = 30L, itemId = "item-2")
        fixture.attachReceipt("latest", FeedbackVerificationVerdict.PASS, createdAt = 40L)

        fixture.assertResolveError("VERIFICATION_RECEIPT_NOT_FOUND:", "missing")
        fixture.assertResolveError("VERIFICATION_RECEIPT_FAILED:", "failed")
        fixture.assertResolveError("VERIFICATION_RECEIPT_MISMATCH:", "other-item")
        fixture.assertResolveError("VERIFICATION_RECEIPT_NOT_LATEST:", "old")
        fixture.assertResolveError(
            prefix = "VERIFICATION_RECEIPT_MISMATCH:",
            receiptId = "latest",
            status = AnnotationStatusDto.WONT_FIX,
        )
        assertEquals(0, fixture.events().count { it.type == "updateItemStatus" })
        assertEquals(AnnotationStatusDto.IN_PROGRESS, fixture.store.getSession(fixture.sessionId).items.single().status)
    }

    @Test
    fun receiptFreeResolutionRemainsCompatibleAndUnlinked() = withFixture { fixture ->
        fixture.attachReceipt("pass-1", FeedbackVerificationVerdict.PASS, createdAt = 10L)

        val resolved = fixture.service.resolveFeedback(
            fixture.sessionId,
            fixture.itemId,
            AnnotationStatusDto.RESOLVED,
            "done",
            null,
        )

        assertNull(resolved.resolutionVerificationReceiptId)
        assertNull(fixture.reopen().getSession(fixture.sessionId).items.single().resolutionVerificationReceiptId)
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("feedback-verification-resolution-").toFile()
        try {
            block(Fixture.create(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val store: FeedbackSessionStore,
        val service: FeedbackSessionService,
        val paths: FeedbackSessionPaths,
        val persistence: FeedbackSessionPersistence,
        val sessionId: String,
        val itemId: String,
        val clock: () -> Long,
        val nextId: () -> String,
    ) {
        fun attachReceipt(
            receiptId: String,
            verdict: FeedbackVerificationVerdict,
            createdAt: Long,
            itemId: String = this.itemId,
        ) {
            val context = captureContext()
            store.attachVerificationReceipt(
                context,
                FeedbackVerificationReceiptDto(
                    receiptId = receiptId,
                    itemId = itemId,
                    baselineScreenId = context.baselineScreen.screenId,
                    createdAtEpochMillis = createdAt,
                    verdict = verdict,
                    checks = emptyList(),
                    assertions = emptyList(),
                ),
            )
        }

        fun assertResolveError(
            prefix: String,
            receiptId: String,
            status: AnnotationStatusDto = AnnotationStatusDto.RESOLVED,
        ) {
            val failure = assertFailsWith<FeedbackSessionException> {
                service.resolveFeedback(sessionId, itemId, status, "done", receiptId)
            }
            assertTrue(failure.message.orEmpty().startsWith(prefix), failure.message)
        }

        fun events() = EventLogReader(paths.eventLogDirectory(sessionId)).readAll()

        fun reopen(): FeedbackSessionStore = FeedbackSessionStore(
            clock = clock,
            idGenerator = nextId,
            persistence = persistence,
            eventLogWriterProvider = { EventLogWriter(paths.eventLogDirectory(it)) },
            eventLogReaderProvider = { EventLogReader(paths.eventLogDirectory(it)) },
        )

        private fun captureContext(): FeedbackVerificationStartContext = store.captureVerificationContext(
            sessionId,
            itemId,
            emptyList(),
        )

        companion object {
            fun create(root: File): Fixture {
                val paths = FeedbackSessionPaths(root)
                val persistence = FeedbackSessionPersistence(paths)
                var now = 1_000L
                var id = 0
                val clock = { ++now }
                val nextId = { "id-${++id}" }
                val store = FeedbackSessionStore(
                    clock = clock,
                    idGenerator = nextId,
                    persistence = persistence,
                    eventLogWriterProvider = { EventLogWriter(paths.eventLogDirectory(it)) },
                    eventLogReaderProvider = { EventLogReader(paths.eventLogDirectory(it)) },
                )
                val session = store.openSession("com.test", root.absolutePath)
                val screen = store.addScreen(
                    session.sessionId,
                    SnapshotDto("pending", 0L, displayName = "Checkout"),
                )
                val item = store.addItem(
                    session.sessionId,
                    AnnotationDto(
                        itemId = "pending",
                        screenId = screen.screenId,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                        comment = "verify checkout",
                        delivery = FeedbackDelivery.DRAFT,
                    ),
                )
                store.sendDraftToAgent(session.sessionId, markdownSnapshot = "handoff")
                store.claimFeedback(session.sessionId, item.itemId, "working")
                val service = FeedbackSessionService(
                    bridge = FakeFixThisBridge(),
                    store = store,
                    projectRoot = root.absolutePath,
                    defaultPackageName = "com.test",
                )
                return Fixture(store, service, paths, persistence, session.sessionId, item.itemId, clock, nextId)
            }
        }
    }
}
