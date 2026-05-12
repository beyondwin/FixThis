package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogException
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the event-log integration on [FeedbackSessionStore].
 *
 * Constructor shape assumed:
 *   FeedbackSessionStore(
 *       clock, idGenerator, persistence,
 *       eventLogWriterProvider: ((sessionId: String) -> EventLogWriter)? = null,
 *       eventLogReaderProvider: ((sessionId: String) -> EventLogReader)? = null,
 *   )
 *
 * When both are null the store behaves identically to the pre-A.4 baseline
 * (backward-compatible). When writerProvider is non-null, every spec'd mutation
 * appends a SessionEvent BEFORE updating in-memory state. When readerProvider is
 * non-null, the init block replays events to reconstruct sessions on boot.
 */
class FeedbackSessionStoreEventLogTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeDraftItem(screenId: String, comment: String = "item") = AnnotationDto(
        itemId = "pending",
        screenId = screenId,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
        comment = comment,
    )

    private fun makeScreen() = SnapshotDto(
        screenId = "pending",
        capturedAtEpochMillis = 0L,
        displayName = "TestScreen",
    )

    private fun eventsDir(base: File, sessionId: String): File = File(base, "$sessionId/events")

    private fun writerFor(base: File, onWriteHook: (java.nio.file.Path) -> Unit = {}): (String) -> EventLogWriter = { sessionId -> EventLogWriter(eventsDir(base, sessionId), onWriteHook) }

    private fun readerFor(base: File): (String) -> EventLogReader = { sessionId -> EventLogReader(eventsDir(base, sessionId)) }

    private fun sequentialIdGenerator(): () -> String {
        var counter = 0
        return { "id-${++counter}" }
    }

    // -------------------------------------------------------------------------
    // Test 1: addItem appends an "addItem" event; addScreen appends "addScreen"
    // -------------------------------------------------------------------------

    @Test
    fun addItemAppendsEventToEventLog() {
        val tmp = Files.createTempDirectory("alh-test-1").toFile()
        try {
            val store = FeedbackSessionStore(
                clock = { 1_000L },
                idGenerator = sequentialIdGenerator(),
                eventLogWriterProvider = writerFor(tmp),
            )

            val session = store.openSession("com.test", "/project")
            val sid = session.sessionId

            // addScreen is also wrapped — it emits an "addScreen" event
            val screen = store.addScreen(sid, makeScreen())

            // This is the mutation under test
            store.addItem(sid, makeDraftItem(screen.screenId))

            val evtDir = eventsDir(tmp, sid)
            val allEvents = EventLogReader(evtDir).readAll()

            // Expect exactly 2 events: addScreen + addItem
            assertEquals(2, allEvents.size, "Expected 2 events (addScreen + addItem)")

            val addItemEvents = allEvents.filter { it.type == "addItem" }
            assertEquals(1, addItemEvents.size, "Expected exactly 1 addItem event")

            val addScreenEvents = allEvents.filter { it.type == "addScreen" }
            assertEquals(1, addScreenEvents.size, "Expected exactly 1 addScreen event")
        } finally {
            tmp.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Test 2: EventLogException before memory mutation — state rolls back
    // -------------------------------------------------------------------------

    @Test
    fun addItemWithFailingWriterLeavesMemoryUnchanged() {
        val tmp = Files.createTempDirectory("alh-test-2").toFile()
        try {
            var shouldFail = false

            val store = FeedbackSessionStore(
                clock = { 1_000L },
                idGenerator = sequentialIdGenerator(),
                eventLogWriterProvider = writerFor(tmp) { _ ->
                    if (shouldFail) throw IOException("disk full")
                },
            )

            val session = store.openSession("com.test", "/project")
            val sid = session.sessionId

            // Setup: add a screen normally (writer not yet failing)
            val screen = store.addScreen(sid, makeScreen())
            val itemCountBefore = store.getSession(sid).items.size

            // Engage failure mode — next write will throw
            shouldFail = true

            // addItem must throw EventLogException (wrapping the IOException)
            assertFailsWith<EventLogException> {
                store.addItem(sid, makeDraftItem(screen.screenId))
            }

            // Memory must be unchanged
            val itemCountAfter = store.getSession(sid).items.size
            assertEquals(
                itemCountBefore,
                itemCountAfter,
                "In-memory item count must be unchanged after EventLogException",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Test 3: Boot replay reconstructs items from event log after simulated crash
    // -------------------------------------------------------------------------

    @Test
    fun bootReplayReconstructsSessionFromEventLog() {
        val tmp = Files.createTempDirectory("alh-test-3").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")

            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            // --- Store 1: open session, add screen, add 3 items, then "crash" ---
            val store1 = FeedbackSessionStore(
                clock = { 1_000L },
                idGenerator = sharedIdGen,
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val session1 = store1.openSession("com.test", "/project")
            val sid = session1.sessionId
            val screen = store1.addScreen(sid, makeScreen())
            store1.addItem(sid, makeDraftItem(screen.screenId, "alpha"))
            store1.addItem(sid, makeDraftItem(screen.screenId, "beta"))
            store1.addItem(sid, makeDraftItem(screen.screenId, "gamma"))

            assertEquals(3, store1.getSession(sid).items.size)

            // --- Store 2: boots from same persistence + event log (simulates restart after crash) ---
            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            // Replay should reconstruct all 3 items
            val replayed = store2.getSession(sid)
            assertEquals(3, replayed.items.size, "Boot replay must reconstruct 3 items")

            val replayedComments = replayed.items.map { it.comment }.toSet()
            assertEquals(setOf("alpha", "beta", "gamma"), replayedComments)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
