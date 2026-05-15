package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.eventlog.EventLogWriter
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A.5 (ALH-3) — SIGKILL replay invariant test.
 *
 * Applies 100 random wrapped mutations to store1, then constructs store2 from the same
 * persistence + event-log paths (simulating a SIGKILL/restart), and asserts identical
 * session state.
 *
 * Equality relaxation: session.updatedAtEpochMillis is excluded from comparison.
 * Reason: on the write path, the session's updatedAtEpochMillis is set at tick A (before
 * appendEventThenMutate), but applyEvent on replay restores it to event.epochMillis (tick B,
 * one increment later). Per-item and per-screen timestamps baked into event payloads are
 * identical, so structural content can be compared field-by-field.
 */
class SigkillReplayTest {

    // -------------------------------------------------------------------------
    // Main test
    // -------------------------------------------------------------------------

    @Test
    fun `randomized 100 mutations replay to identical state across two store instances`() {
        val tmp = Files.createTempDirectory("sigkill-replay").toFile()
        try {
            val projectBase = File(tmp, "project")
            val paths = FeedbackSessionPaths(projectBase)
            val persistence = FeedbackSessionPersistence(paths)
            val evtBase = File(tmp, "events")

            var clockTick = 0L
            val clock: () -> Long = { ++clockTick }
            var idSeq = 0
            val sharedIdGen: () -> String = { "id-${++idSeq}" }

            // Build store1 with persistence + event log providers
            val store1 = FeedbackSessionStore(
                clock = clock,
                idGenerator = sharedIdGen,
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            // openSession is setup — does NOT count toward the 100 random ops
            val sid = store1.openSession(
                packageName = "test.pkg",
                projectRoot = tmp.absolutePath,
            ).sessionId

            // Apply 100 random wrapped mutations
            val rng = Random(42)
            val opGen = RandomOpGen(store1, sid, rng)
            repeat(100) { opGen.applyRandom() }

            val snapshot1 = store1.getSession(sid)

            // Discard store1 (simulate SIGKILL — no shutdown call).
            // Build store2 from the same paths; boot replay runs in init.
            val store2 = FeedbackSessionStore(
                clock = clock, // shares same clock progression
                idGenerator = { "replay-${++idSeq}" }, // never called during replay
                persistence = persistence,
                eventLogWriterProvider = writerFor(evtBase),
                eventLogReaderProvider = readerFor(evtBase),
            )

            val snapshot2 = store2.getSession(sid)

            // Compare structural state, excluding session.updatedAtEpochMillis (see class KDoc).
            assertEquals(
                canonicalize(snapshot1),
                canonicalize(snapshot2),
                "Replay must reconstruct identical structural state",
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun writerFor(base: File): (String) -> EventLogWriter = { sessionId ->
        EventLogWriter(eventsDir(base, sessionId))
    }

    private fun readerFor(base: File): (String) -> EventLogReader = { sessionId ->
        EventLogReader(eventsDir(base, sessionId))
    }

    private fun eventsDir(base: File, sessionId: String): File = File(base, "$sessionId/events")

    /**
     * Stable canonical representation of a [SessionDto] for equality checks.
     *
     * Excludes [SessionDto.updatedAtEpochMillis] because the write path and
     * replay path produce different values (see class KDoc).
     */
    private fun canonicalize(session: SessionDto): CanonicalSession {
        val items = session.items
            .sortedBy { it.itemId }
            .map { item ->
                CanonicalItem(
                    itemId = item.itemId,
                    screenId = item.screenId,
                    sequenceNumber = item.sequenceNumber,
                    comment = item.comment,
                    status = item.status,
                    delivery = item.delivery,
                    handoffBatchId = item.handoffBatchId,
                    createdAtEpochMillis = item.createdAtEpochMillis,
                    updatedAtEpochMillis = item.updatedAtEpochMillis,
                    sentAtEpochMillis = item.sentAtEpochMillis,
                    lastHandedOffAtEpochMillis = item.lastHandedOffAtEpochMillis,
                )
            }
        val screens = session.screens
            .sortedBy { it.screenId }
            .map { screen ->
                CanonicalScreen(
                    screenId = screen.screenId,
                    displayName = screen.displayName,
                    activityName = screen.activityName,
                    capturedAtEpochMillis = screen.capturedAtEpochMillis,
                )
            }
        val batches = session.handoffBatches
            .sortedBy { it.batchId }
            .map { batch ->
                CanonicalBatch(
                    batchId = batch.batchId,
                    sequenceNumber = batch.sequenceNumber,
                    itemIds = batch.itemIds.sorted(),
                    createdAtEpochMillis = batch.createdAtEpochMillis,
                )
            }
        return CanonicalSession(
            sessionId = session.sessionId,
            packageName = session.packageName,
            status = session.status,
            items = items,
            screens = screens,
            batches = batches,
        )
    }

    // -------------------------------------------------------------------------
    // Canonical data classes (pure data, no timestamps that can diverge)
    // -------------------------------------------------------------------------

    private data class CanonicalSession(
        val sessionId: String,
        val packageName: String,
        val status: SessionStatusDto,
        val items: List<CanonicalItem>,
        val screens: List<CanonicalScreen>,
        val batches: List<CanonicalBatch>,
    )

    private data class CanonicalItem(
        val itemId: String,
        val screenId: String,
        val sequenceNumber: Int?,
        val comment: String,
        val status: AnnotationStatusDto,
        val delivery: FeedbackDelivery,
        val handoffBatchId: String?,
        val createdAtEpochMillis: Long,
        val updatedAtEpochMillis: Long,
        val sentAtEpochMillis: Long?,
        val lastHandedOffAtEpochMillis: Long?,
    )

    private data class CanonicalScreen(
        val screenId: String,
        val displayName: String,
        val activityName: String?,
        val capturedAtEpochMillis: Long,
    )

    private data class CanonicalBatch(
        val batchId: String,
        val sequenceNumber: Int,
        val itemIds: List<String>,
        val createdAtEpochMillis: Long,
    )

    // -------------------------------------------------------------------------
    // Random op generator — exactly the 7 wrapped mutations from A.4
    // -------------------------------------------------------------------------

    private inner class RandomOpGen(
        private val store: FeedbackSessionStore,
        private val sid: String,
        private val rng: Random,
    ) {
        private var opCount = 0
        private var screenCounter = 0
        private var itemCounter = 0

        fun applyRandom() {
            val session = store.getSession(sid)
            val screens = session.screens
            val draftItems = session.items.filter { it.delivery == FeedbackDelivery.DRAFT }

            val mutated = when (rng.nextInt(7)) {
                0 -> {
                    applyAddScreen(session)
                    true
                }
                1 -> {
                    applyAddScreenWithItems(session)
                    true
                }
                2 -> applyAddItemOp(screens)
                3 -> applyDeleteScreenOp(screens)
                4 -> applyUpdateDraftItemOp(draftItems)
                5 -> applyDeleteDraftItemOp(draftItems)
                6 -> applySendDraftToAgentOp(draftItems)
                else -> false // unreachable
            }
            if (mutated) opCount++
        }

        private fun applyAddItemOp(screens: List<SnapshotDto>): Boolean {
            if (screens.isEmpty()) return false
            val screen = screens[rng.nextInt(screens.size)]
            store.addItem(sid, makeDraftItem(screen.screenId, "item-${++itemCounter}"))
            return true
        }

        private fun applyDeleteScreenOp(screens: List<SnapshotDto>): Boolean {
            if (screens.isEmpty()) return false
            val screen = screens[rng.nextInt(screens.size)]
            store.deleteScreen(sid, screen.screenId)
            return true
        }

        private fun applyUpdateDraftItemOp(draftItems: List<AnnotationDto>): Boolean {
            if (draftItems.isEmpty()) return false
            val item = draftItems[rng.nextInt(draftItems.size)]
            store.updateDraftItem(
                sessionId = sid,
                itemId = item.itemId,
                label = "label-$opCount",
                severity = null,
                comment = "updated-comment-${opCount + 1}",
                status = null,
            )
            return true
        }

        private fun applyDeleteDraftItemOp(draftItems: List<AnnotationDto>): Boolean {
            if (draftItems.isEmpty()) return false
            store.deleteDraftItem(sid, draftItems[rng.nextInt(draftItems.size)].itemId)
            return true
        }

        private fun applySendDraftToAgentOp(draftItems: List<AnnotationDto>): Boolean {
            if (draftItems.isEmpty()) return false
            store.sendDraftToAgent(sid, markdownSnapshot = null, targetItemIds = null)
            return true
        }

        private fun applyAddScreen(@Suppress("UnusedParameter") session: SessionDto) {
            store.addScreen(sid, makeScreen("screen-${++screenCounter}"))
        }

        private fun applyAddScreenWithItems(@Suppress("UnusedParameter") session: SessionDto) {
            val screenName = "screen-${++screenCounter}"
            val itemCount = 1 + rng.nextInt(3) // 1..3 items
            val items = (1..itemCount).map { makeDraftItem("pending", "item-${++itemCounter}") }
            store.addScreenWithItems(sid, makeScreen(screenName), items)
        }
    }

    // -------------------------------------------------------------------------
    // DTO factories
    // -------------------------------------------------------------------------

    private fun makeDraftItem(screenId: String, comment: String) = AnnotationDto(
        itemId = "pending",
        screenId = screenId,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
        comment = comment,
        delivery = FeedbackDelivery.DRAFT,
    )

    private fun makeScreen(displayName: String = "TestScreen") = SnapshotDto(
        screenId = "pending",
        capturedAtEpochMillis = 0L,
        displayName = displayName,
    )
}
