package io.github.beyondwin.fixthis.mcp.session.lifecycle.event

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.SessionEvent
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.SessionStateStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Characterization tests for [SessionBootReplayer], the boot-replay orchestration
 * extracted from FeedbackSessionStoreDelegate (target 1-E).
 *
 * These exercise the replayer directly with a real [SessionEventJournal] +
 * [SessionReplayEngine] backed by an on-disk event log (no persistence), plus a
 * [SessionStateStore] holding the shells. Behavior under test:
 *   - replay of a session WITH events
 *   - a session with NO events (shell preserved)
 *   - skipped-session recording (replay reports a skip via an invalid checkpoint)
 *   - skippedList filtering by packageName and includeClosed
 *   - the post-replay currentSessionId re-derivation rule.
 */
class SessionBootReplayerTest {

    private val tmp: File = Files.createTempDirectory("boot-replayer").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun eventsDir(sessionId: String): File = File(tmp, "$sessionId/events")

    private fun journalFor(): SessionEventJournal = SessionEventJournal(
        clock = { 0L },
        idGenerator = { "evt" },
        writerProvider = { sessionId -> EventLogWriter(eventsDir(sessionId)) },
        readerProvider = { sessionId -> EventLogReader(eventsDir(sessionId)) },
    )

    private fun newReplayer(
        journal: SessionEventJournal,
    ): SessionBootReplayer = SessionBootReplayer(
        replayEngine = SessionReplayEngine(journal, persistence = null),
        journal = journal,
        persistence = null,
    )

    private fun session(
        id: String,
        pkg: String = "test.pkg",
        status: SessionStatusDto = SessionStatusDto.ACTIVE,
        updatedAt: Long = 0L,
    ) = SessionDto(
        sessionId = id,
        packageName = pkg,
        projectRoot = "/root",
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = updatedAt,
        status = status,
    )

    private fun writeAddScreenEvent(sessionId: String, screenId: String, seq: Long, epoch: Long) {
        val writer = EventLogWriter(eventsDir(sessionId))
        val screenJson = buildJsonObject {
            put("screenId", screenId)
            put("capturedAtEpochMillis", epoch)
            put("displayName", "Screen-$screenId")
        }
        val payload = JsonObject(mapOf("screen" to screenJson))
        writer.append(
            SessionEvent(
                eventId = "e-$seq",
                sequenceNumber = seq,
                epochMillis = epoch,
                actor = "mcp",
                type = "addScreen",
                payload = payload,
            ),
        )
    }

    @Test
    fun `replays a session that has events`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        val s = session("s1", updatedAt = 10L)
        store.put(s)
        writeAddScreenEvent("s1", "scr1", seq = 0L, epoch = 50L)

        replayer.replayAll(store, journal)

        val replayed = store.find("s1")!!
        assertEquals(1, replayed.screens.size, "Event must be replayed into store")
        assertEquals("scr1", replayed.screens.single().screenId)
    }

    @Test
    fun `session with no events keeps its shell unchanged`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        val s = session("s1", updatedAt = 10L).copy(
            screens = listOf(
                SnapshotDto(screenId = "existing", capturedAtEpochMillis = 1L, displayName = "Existing"),
            ),
        )
        store.put(s)

        replayer.replayAll(store, journal)

        val after = store.find("s1")!!
        assertEquals(listOf("existing"), after.screens.map { it.screenId }, "Shell must be preserved")
    }

    @Test
    fun `records a skipped session when the checkpoint is invalid`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        store.put(session("s1"))
        // Write a corrupt checkpoint file so readCheckpointOrNull throws -> recorded as skipped.
        val dir = eventsDir("s1").apply { mkdirs() }
        File(dir, "checkpoint.json").writeText("{ not valid json")

        replayer.replayAll(store, journal)

        val skipped = replayer.skippedList(packageName = null, includeClosed = true)
        assertEquals(1, skipped.size, "Invalid checkpoint must record a skipped session")
        assertTrue(skipped.single().message.contains("checkpoint"), "Skip message should mention checkpoint")
    }

    @Test
    fun `skippedList filters by packageName and includeClosed`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        store.put(session("open-a", pkg = "com.a", status = SessionStatusDto.ACTIVE))
        store.put(session("closed-a", pkg = "com.a", status = SessionStatusDto.CLOSED))
        store.put(session("open-b", pkg = "com.b", status = SessionStatusDto.ACTIVE))
        // Corrupt all three checkpoints to register all as skipped.
        for (id in listOf("open-a", "closed-a", "open-b")) {
            val dir = eventsDir(id).apply { mkdirs() }
            File(dir, "checkpoint.json").writeText("{ not valid json")
        }

        replayer.replayAll(store, journal)

        // Default: only non-closed across all packages.
        val nonClosedAll = replayer.skippedList(packageName = null, includeClosed = false)
        assertEquals(setOf("open-a", "open-b"), nonClosedAll.map { skippedSessionIdFor(it.path) }.toSet())

        // Filter by package com.a, non-closed.
        val pkgANonClosed = replayer.skippedList(packageName = "com.a", includeClosed = false)
        assertEquals(setOf("open-a"), pkgANonClosed.map { skippedSessionIdFor(it.path) }.toSet())

        // Include closed for com.a.
        val pkgAAll = replayer.skippedList(packageName = "com.a", includeClosed = true)
        assertEquals(setOf("open-a", "closed-a"), pkgAAll.map { skippedSessionIdFor(it.path) }.toSet())
    }

    /** The checkpoint path embeds the session id directory, so recover the id from it. */
    private fun skippedSessionIdFor(checkpointPath: String): String =
        File(checkpointPath).parentFile.parentFile.name

    @Test
    fun `currentSessionId is highest updatedAt non-closed after replay`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        store.put(session("old", updatedAt = 5L, status = SessionStatusDto.ACTIVE))
        store.put(session("newest-closed", updatedAt = 100L, status = SessionStatusDto.CLOSED))
        store.put(session("newest-open", updatedAt = 50L, status = SessionStatusDto.ACTIVE))

        val result = replayer.replayAll(store, journal)

        assertEquals("newest-open", result.currentSessionId)
    }

    @Test
    fun `currentSessionId is null when no non-closed sessions exist`() {
        val journal = journalFor()
        val replayer = newReplayer(journal)
        val store = SessionStateStore(persistence = null)
        store.put(session("c1", updatedAt = 5L, status = SessionStatusDto.CLOSED))

        val result = replayer.replayAll(store, journal)

        assertNull(result.currentSessionId)
    }

    @Suppress("unused")
    private fun draftItem(screenId: String, itemId: String) = AnnotationDto(
        itemId = itemId,
        screenId = screenId,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = 0L,
        target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
        comment = "c",
        delivery = FeedbackDelivery.DRAFT,
    )

    @Suppress("unused")
    private val ignored = JsonPrimitive("placeholder")
}
