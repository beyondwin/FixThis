package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogException
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogCheckpoint
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import io.beyondwin.fixthis.mcp.session.eventlog.SessionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the event-log integration on [FeedbackSessionStore].
 *
 * Constructor shape:
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

    private val testJson = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

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

    private fun writerFor(
        base: File,
        onWriteHook: (java.nio.file.Path) -> Unit = {},
    ): (String) -> EventLogWriter = { sessionId ->
        EventLogWriter(eventsDir(base, sessionId), onWriteHook)
    }

    private fun readerFor(base: File): (String) -> EventLogReader = { sessionId ->
        EventLogReader(eventsDir(base, sessionId))
    }

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
    //
    // This test is DISCRIMINATING: it injects an "orphan" addItem event directly
    // into the event log (bypassing the store) — simulating a crash that happened
    // AFTER the event was written but BEFORE persistence.save() completed.
    // Only replay can recover this item; the snapshot alone cannot.
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

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)

            // Inject a 4th item DIRECTLY into the event log (bypassing the store).
            // This simulates: event appended, then crash before persistence.save().
            // The snapshot on disk still has 3 items; only replay can see this 4th item.
            injectOrphanAddItemEvent(evtBase, sid, screenId)

            // Verify snapshot alone only has 3 items (confirming orphan is NOT in snapshot)
            val snapshotOnly = persistence.load(sid)
            assertEquals(3, snapshotOnly.items.size, "Snapshot must have only 3 items (orphan not persisted)")

            // --- Store 2: boots from same paths, triggers boot replay ---
            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            // Replay must reconstruct all 4 items (3 snapshot-synced + 1 orphan)
            val replayed = store2.getSession(sid)
            assertEquals(4, replayed.items.size, "Boot replay must reconstruct all 4 items including orphan")
            assertEquals(setOf("alpha", "beta", "gamma", "delta"), replayed.items.map { it.comment }.toSet())
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun bootReplayStartsFromCheckpointSnapshotAndAppliesOnlyNewerEvents() {
        val tmp = Files.createTempDirectory("alh-test-checkpoint").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)
            val checkpointSnapshot = persistence.load(sid)
            assertEquals(3, checkpointSnapshot.items.size)
            EventLogWriter(eventsDir(evtBase, sid)).writeCheckpoint(
                EventLogCheckpoint(
                    sessionId = sid,
                    compactedThroughSequenceNumber = 4L,
                    snapshotUpdatedAtEpochMillis = checkpointSnapshot.updatedAtEpochMillis,
                    createdAtEpochMillis = 1_600L,
                ),
            )
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 4L, comment = "too-old")
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 5L, comment = "epsilon")

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val replayed = store2.getSession(sid)
            assertEquals(4, replayed.items.size)
            assertEquals(setOf("alpha", "beta", "gamma", "epsilon"), replayed.items.map { it.comment }.toSet())
            assertEquals(checkpointSnapshot.screens, replayed.screens)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun bootReplaySkipsReplayAndReportsSkippedSessionWhenCheckpointIsCorrupt() {
        val tmp = Files.createTempDirectory("alh-test-corrupt-checkpoint").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)
            File(eventsDir(evtBase, sid), "checkpoint.json").writeText("{not-json")
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 5L, comment = "epsilon")

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val replayed = store2.getSession(sid)
            assertEquals(3, replayed.items.size)
            assertEquals(setOf("alpha", "beta", "gamma"), replayed.items.map { it.comment }.toSet())
            val skipped = store2.listSessions(includeClosed = true).skippedSessions.single()
            assertEquals(File(eventsDir(evtBase, sid), "checkpoint.json").absolutePath, skipped.path)
            assertTrue(skipped.message.contains("Invalid event log checkpoint"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun degradedBootWithCorruptCheckpointSeedsNextEventSequenceFromActiveLog() {
        val tmp = Files.createTempDirectory("alh-test-corrupt-checkpoint-sequence").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)
            File(eventsDir(evtBase, sid), "checkpoint.json").writeText("{not-json")
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 5L, comment = "epsilon")
            val maxSequenceBeforeBoot = EventLogReader(eventsDir(evtBase, sid)).readAll().maxOf { it.sequenceNumber }

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            store2.addItem(sid, makeDraftItem(screenId, "zeta"))

            val newEvent = EventLogReader(eventsDir(evtBase, sid))
                .readAll()
                .single { event ->
                    event.type == "addItem" &&
                        event.payload["item"]
                            ?.let {
                                testJson.decodeFromJsonElement(AnnotationDto.serializer(), it).comment == "zeta"
                            } == true
                }
            assertTrue(newEvent.sequenceNumber > maxSequenceBeforeBoot)
            assertEquals(1, store2.listSessions(includeClosed = true).skippedSessions.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun bootReplaySkipsReplayAndReportsSkippedSessionWhenCheckpointSessionIdIsWrong() {
        val tmp = Files.createTempDirectory("alh-test-wrong-checkpoint-session").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)
            EventLogWriter(eventsDir(evtBase, sid)).writeCheckpoint(
                EventLogCheckpoint(
                    sessionId = "wrong-session",
                    compactedThroughSequenceNumber = 10L,
                    snapshotUpdatedAtEpochMillis = 1_500L,
                    createdAtEpochMillis = 1_600L,
                ),
            )
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 11L, comment = "epsilon")

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val replayed = store2.getSession(sid)
            assertEquals(3, replayed.items.size)
            val skipped = store2.listSessions(includeClosed = true).skippedSessions.single()
            assertEquals(File(eventsDir(evtBase, sid), "checkpoint.json").absolutePath, skipped.path)
            assertTrue(skipped.message.contains("sessionId"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun bootReplaySkipsReplayAndReportsSkippedSessionWhenCheckpointSchemaIsUnsupported() {
        val tmp = Files.createTempDirectory("alh-test-unsupported-checkpoint-schema").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            val (sid, screenId) = setupStore1WithThreeItems(evtBase, persistence, sharedIdGen)
            EventLogWriter(eventsDir(evtBase, sid)).writeCheckpoint(
                EventLogCheckpoint(
                    schemaVersion = 2,
                    sessionId = sid,
                    compactedThroughSequenceNumber = 10L,
                    snapshotUpdatedAtEpochMillis = 1_500L,
                    createdAtEpochMillis = 1_600L,
                ),
            )
            injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = 11L, comment = "epsilon")

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val replayed = store2.getSession(sid)
            assertEquals(3, replayed.items.size)
            val skipped = store2.listSessions(includeClosed = true).skippedSessions.single()
            assertEquals(File(eventsDir(evtBase, sid), "checkpoint.json").absolutePath, skipped.path)
            assertTrue(skipped.message.contains("schemaVersion"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun bootReplayPreservesPostHandoffItemStateMutations() {
        val tmp = Files.createTempDirectory("alh-test-state-mutations").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")
            var idSeq = 0
            var now = 1_000L

            val store1 = FeedbackSessionStore(
                clock = { now },
                idGenerator = { "id-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )
            val session = store1.openSession("com.test", "/project")
            val screen = store1.addScreen(session.sessionId, makeScreen())
            val alpha = store1.addItem(session.sessionId, makeDraftItem(screen.screenId, "alpha"))
            val beta = store1.addItem(session.sessionId, makeDraftItem(screen.screenId, "beta"))
            store1.addItem(session.sessionId, makeDraftItem(screen.screenId, "gamma"))

            now = 1_100L
            store1.sendDraftToAgent(session.sessionId, "handoff", targetItemIds = listOf(alpha.itemId, beta.itemId))
            now = 1_200L
            store1.markItemsHandedOff(session.sessionId, listOf(alpha.itemId))
            now = 1_300L
            store1.claimFeedback(session.sessionId, alpha.itemId, agentNote = "working alpha")
            now = 1_400L
            store1.updateItemStatus(
                sessionId = session.sessionId,
                itemId = beta.itemId,
                status = AnnotationStatusDto.NEEDS_CLARIFICATION,
                agentSummary = "needs beta details",
            )
            now = 1_500L
            store1.clearDraftItems(session.sessionId)

            val store2 = FeedbackSessionStore(
                clock = { 2_000L },
                idGenerator = { "new-${++idSeq}" },
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val replayed = store2.getSession(session.sessionId)
            assertEquals(listOf("alpha", "beta"), replayed.items.map { it.comment })
            val replayedAlpha = replayed.items.single { it.itemId == alpha.itemId }
            val replayedBeta = replayed.items.single { it.itemId == beta.itemId }
            assertEquals(AnnotationStatusDto.IN_PROGRESS, replayedAlpha.status)
            assertEquals("working alpha", replayedAlpha.agentSummary)
            assertEquals(1_200L, replayedAlpha.lastHandedOffAtEpochMillis)
            assertEquals(AnnotationStatusDto.NEEDS_CLARIFICATION, replayedBeta.status)
            assertEquals("needs beta details", replayedBeta.agentSummary)
            assertEquals(1, replayed.handoffBatches.size)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Sets up store1, opens a session, adds a screen, adds 3 items. Returns sessionId + screenId. */
    private fun setupStore1WithThreeItems(
        evtBase: File,
        persistence: FeedbackSessionPersistence,
        idGen: () -> String,
    ): Pair<String, String> {
        val store1 = FeedbackSessionStore(
            clock = { 1_000L },
            idGenerator = idGen,
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
        return Pair(sid, screen.screenId)
    }

    /**
     * Injects an orphan "addItem" event into the event log for [sid]/[screenId].
     * Simulates a crash that happened after the event was written but before
     * persistence.save() completed — only boot replay can recover this item.
     */
    private fun injectOrphanAddItemEvent(evtBase: File, sid: String, screenId: String) {
        val maxSeq = EventLogReader(eventsDir(evtBase, sid)).readAll().maxOf { it.sequenceNumber }
        injectAddItemEvent(evtBase, sid, screenId, sequenceNumber = maxSeq + 1L, comment = "delta")
    }

    private fun injectAddItemEvent(
        evtBase: File,
        sid: String,
        screenId: String,
        sequenceNumber: Long,
        comment: String,
    ) {
        val orphanItem = AnnotationDto(
            itemId = "orphan-item-id-$sequenceNumber",
            screenId = screenId,
            createdAtEpochMillis = 1_500L,
            updatedAtEpochMillis = 1_500L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
            comment = comment,
            delivery = FeedbackDelivery.DRAFT,
        )
        val orphanEvent = SessionEvent(
            eventId = "orphan-evt-id-$sequenceNumber",
            sequenceNumber = sequenceNumber,
            epochMillis = 1_500L,
            actor = "mcp",
            type = "addItem",
            payload = buildJsonObject {
                put("sessionId", sid)
                put("item", testJson.encodeToJsonElement(AnnotationDto.serializer(), orphanItem))
            },
        )
        EventLogWriter(eventsDir(evtBase, sid)).append(orphanEvent)
    }
}
