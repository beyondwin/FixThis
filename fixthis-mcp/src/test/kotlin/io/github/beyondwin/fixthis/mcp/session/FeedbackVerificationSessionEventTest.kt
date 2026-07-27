package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.console.FeedbackConsoleServer
import io.github.beyondwin.fixthis.mcp.console.events.ConsoleEventBus
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogException
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationAssertionKind
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationCheckOutcome
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationReceiptDto
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationStartContext
import io.github.beyondwin.fixthis.mcp.session.verification.FeedbackVerificationVerdict
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeedbackVerificationSessionEventTest {
    @Test
    fun feedbackVerifiedEventReplaysReceiptExactlyOnce() = withFixture { fixture ->
        val context = fixture.captureContext()
        val receipt = receiptFixture(context, receiptId = "receipt-1")

        fixture.store.attachVerificationReceipt(context, receipt)

        val event = fixture.events().single { it.type == "feedbackVerified" }
        assertEquals(setOf("sessionId", "receipt"), event.payload.keys)
        assertEquals(fixture.session.sessionId, event.payload.getValue("sessionId").jsonPrimitive.content)
        assertEquals("receipt-1", event.payload.getValue("receipt").jsonObject.getValue("receiptId").jsonPrimitive.content)
        assertEquals(listOf(receipt), fixture.reopen().getSession(fixture.session.sessionId).verificationReceipts)
    }

    @Test
    fun feedbackVerifiedCommitEmitsSessionAndSummaryEvents() = withFixture { fixture ->
        withConsoleNotifications(fixture) { eventBus ->
            val context = fixture.captureContext()
            val receipt = receiptFixture(context)

            fixture.store.attachVerificationReceipt(context, receipt)

            val events = eventBus.eventsAfter(0L).events
            assertEquals(listOf("session-updated", "sessions-updated"), events.map { it.name })
            val sessionEvent = events[0].data
            assertEquals(fixture.session.sessionId, sessionEvent.getValue("sessionId").jsonPrimitive.content)
            assertEquals(
                "receipt-1",
                sessionEvent.getValue("session").jsonObject
                    .getValue("verificationReceipts").jsonArray.single().jsonObject
                    .getValue("receiptId").jsonPrimitive.content,
            )
            val summaryEvent = events[1].data
            assertEquals(fixture.session.sessionId, summaryEvent.getValue("sessionId").jsonPrimitive.content)
            assertEquals(
                fixture.store.getSession(fixture.session.sessionId).updatedAtEpochMillis,
                summaryEvent.getValue("summary").jsonObject
                    .getValue("updatedAtEpochMillis").jsonPrimitive.long,
            )
        }
    }

    @Test
    fun bindFailureDoesNotLeakReceiptNotificationsAcrossRetryOrStop() = withFixture { fixture ->
        val eventBus = ConsoleEventBus(clock = { 3_000L })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = fixture.store,
            projectRoot = fixture.session.projectRoot,
            defaultPackageName = fixture.session.packageName,
        )
        val occupiedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val server = FeedbackConsoleServer(service, port = occupiedPort.localPort, eventBus = eventBus)
        occupiedPort.use {
            assertFailsWith<IOException> { server.start() }
        }

        try {
            server.start()
            val context = fixture.captureContext()
            fixture.store.attachVerificationReceipt(context, receiptFixture(context))

            assertEquals(
                listOf("session-updated", "sessions-updated"),
                eventBus.eventsAfter(0L).events.map { it.name },
            )
        } finally {
            server.stop()
        }

        val contextAfterStop = fixture.captureContext()
        fixture.store.attachVerificationReceipt(
            contextAfterStop,
            receiptFixture(contextAfterStop, receiptId = "receipt-after-stop"),
        )

        assertEquals(
            listOf("session-updated", "sessions-updated"),
            eventBus.eventsAfter(0L).events.map { it.name },
        )
    }

    @Test
    fun competingItemMutationRejectsReceiptWithoutJournalAppend() = withFixture { fixture ->
        val context = fixture.captureContext()
        val eventCount = fixture.events().size
        fixture.store.claimFeedback(fixture.session.sessionId, fixture.itemId, "changed note")

        val validationError = assertFailsWith<FeedbackSessionException> {
            fixture.store.validateVerificationContext(context)
        }
        val attachError = assertFailsWith<FeedbackSessionException> {
            fixture.store.attachVerificationReceipt(context, receiptFixture(context))
        }

        assertTrue(validationError.message.orEmpty().startsWith("VERIFICATION_CONTEXT_CHANGED:"))
        assertTrue(attachError.message.orEmpty().startsWith("VERIFICATION_CONTEXT_CHANGED:"))
        assertEquals(eventCount + 1, fixture.events().size)
        assertTrue(fixture.events().none { it.type == "feedbackVerified" })
        assertTrue(fixture.store.getSession(fixture.session.sessionId).verificationReceipts.isEmpty())
    }

    @Test
    fun receiptWriterFailureLeavesSessionAndJournalUnchanged() {
        var failWrites = false
        withFixture(onWriteHook = {
            if (failWrites) throw IOException("event disk full")
        }) { fixture ->
            withConsoleNotifications(fixture) { eventBus ->
                val context = fixture.captureContext()
                val before = fixture.store.getSession(fixture.session.sessionId)
                val eventCount = fixture.events().size
                failWrites = true

                assertFailsWith<EventLogException> {
                    fixture.store.attachVerificationReceipt(context, receiptFixture(context))
                }

                assertEquals(before, fixture.store.getSession(fixture.session.sessionId))
                assertEquals(eventCount, fixture.events().size)
                assertTrue(eventBus.eventsAfter(0L).events.isEmpty())
            }
        }
    }

    @Test
    fun receiptStateCommitFailureEmitsNoSessionNotifications() = withFixture { fixture ->
        withConsoleNotifications(fixture) { eventBus ->
            val context = fixture.captureContext()
            val sessionFile = fixture.paths.sessionFile(fixture.session.sessionId)
            assertTrue(sessionFile.delete())
            assertTrue(sessionFile.mkdir())

            assertFailsWith<FeedbackSessionException> {
                fixture.store.attachVerificationReceipt(context, receiptFixture(context))
            }

            assertEquals(1, fixture.events().count { it.type == "feedbackVerified" })
            assertTrue(eventBus.eventsAfter(0L).events.isEmpty())
        }
    }

    @Test
    fun legacyFullLogReplayDiscardsSnapshotOnlyReceipt() = withFixture { fixture ->
        val context = fixture.captureContext()
        val snapshotOnly = fixture.store.getSession(fixture.session.sessionId).copy(
            verificationReceipts = listOf(receiptFixture(context, receiptId = "snapshot-only")),
        )
        fixture.store.replaceSessionForDomain(snapshotOnly)

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertTrue(replayed.verificationReceipts.isEmpty())
    }

    private fun receiptFixture(
        context: FeedbackVerificationStartContext,
        receiptId: String = "receipt-1",
    ): FeedbackVerificationReceiptDto = FeedbackVerificationReceiptDto(
        receiptId = receiptId,
        itemId = context.item.itemId,
        baselineScreenId = context.baselineScreen.screenId,
        createdAtEpochMillis = 2_000L,
        verdict = FeedbackVerificationVerdict.PASS,
        checks = listOf(
            FeedbackVerificationCheckDto(
                kind = "TARGET_PRESENT",
                outcome = FeedbackVerificationCheckOutcome.PASSED,
                message = "The corresponding target is present",
            ),
        ),
        assertions = context.assertions,
    )

    private fun withFixture(
        onWriteHook: (Path) -> Unit = {},
        block: (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("feedback-verification-event").toFile()
        try {
            block(Fixture.create(root, onWriteHook))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun withConsoleNotifications(
        fixture: Fixture,
        block: (ConsoleEventBus) -> Unit,
    ) {
        val eventBus = ConsoleEventBus(clock = { 3_000L })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = fixture.store,
            projectRoot = fixture.session.projectRoot,
            defaultPackageName = fixture.session.packageName,
        )
        val server = FeedbackConsoleServer(service, eventBus = eventBus)
        try {
            server.start()
            block(eventBus)
        } finally {
            server.stop()
        }
    }

    private data class Fixture(
        val paths: FeedbackSessionPaths,
        val persistence: FeedbackSessionPersistence,
        val store: FeedbackSessionStore,
        val session: SessionDto,
        val itemId: String,
        val nextId: () -> String,
        val clock: () -> Long,
    ) {
        fun captureContext(): FeedbackVerificationStartContext = store.captureVerificationContext(
            session.sessionId,
            itemId,
            listOf(FeedbackVerificationAssertionDto(FeedbackVerificationAssertionKind.TARGET_PRESENT)),
        )

        fun events() = EventLogReader(paths.eventLogDirectory(session.sessionId)).readAll()

        fun reopen(): FeedbackSessionStore = FeedbackSessionStore(
            clock = clock,
            idGenerator = nextId,
            persistence = persistence,
            eventLogWriterProvider = { EventLogWriter(paths.eventLogDirectory(it)) },
            eventLogReaderProvider = { EventLogReader(paths.eventLogDirectory(it)) },
        )

        companion object {
            fun create(
                root: File,
                onWriteHook: (Path) -> Unit,
            ): Fixture {
                val paths = FeedbackSessionPaths(root)
                val persistence = FeedbackSessionPersistence(paths)
                var now = 1_000L
                var id = 0
                val clock: () -> Long = { ++now }
                val ids: () -> String = { "id-${++id}" }
                val store = FeedbackSessionStore(
                    clock = clock,
                    idGenerator = ids,
                    persistence = persistence,
                    eventLogWriterProvider = {
                        EventLogWriter(paths.eventLogDirectory(it), onWriteHook)
                    },
                    eventLogReaderProvider = { EventLogReader(paths.eventLogDirectory(it)) },
                )
                val session = store.openSession("com.test", root.absolutePath)
                val screen = store.addScreen(
                    session.sessionId,
                    SnapshotDto("pending", 0L, displayName = "Screen"),
                )
                val item = store.addItem(
                    session.sessionId,
                    AnnotationDto(
                        itemId = "pending",
                        screenId = screen.screenId,
                        createdAtEpochMillis = 0L,
                        updatedAtEpochMillis = 0L,
                        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                        comment = "verify this",
                    ),
                )
                store.sendDraftToAgent(session.sessionId, markdownSnapshot = "handoff")
                store.claimFeedback(session.sessionId, item.itemId, "working")
                return Fixture(paths, persistence, store, session, item.itemId, ids, clock)
            }
        }
    }
}
