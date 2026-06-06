package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHint
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.Occurrence
import io.github.beyondwin.fixthis.compose.core.model.OccurrenceSignature
import io.github.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.github.beyondwin.fixthis.compose.core.model.SelectionConfidence
import io.github.beyondwin.fixthis.compose.core.model.SourceCandidateSummary
import io.github.beyondwin.fixthis.compose.core.model.SourceInterpretation
import io.github.beyondwin.fixthis.compose.core.model.TargetEvidence
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.toDomainSession
import io.github.beyondwin.fixthis.mcp.session.dto.toSessionDto
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackSessionStoreTest {
    @Test
    fun deserializesLegacySessionJsonWithoutNewSourceCandidateFields() {
        val raw = javaClass.classLoader.getResource("legacy/source-candidate-v1.json")!!.readText()
        val session = fixThisJson.decodeFromString(SessionDto.serializer(), raw)
        val candidate = session.items.single().sourceCandidates.single()

        assertEquals("Foo.kt", candidate.file)
        assertEquals(SelectionConfidence.MEDIUM, candidate.confidence)
        assertTrue(candidate.riskFlags.isEmpty())
        assertEquals(null, candidate.scoreMargin)
    }

    @Test
    fun feedbackSessionRoundTripsThroughJson() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                SnapshotDto(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    activityName = "MainActivity",
                    displayName = "MainActivity",
                    screenshot = SnapshotScreenshotDto(desktopFullPath = "/repo/.fixthis/artifacts/screen-1/full.png"),
                ),
            ),
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Make this button clearer",
                    status = AnnotationStatusDto.READY,
                ),
            ),
            status = SessionStatusDto.READY_FOR_AGENT,
        )

        val encoded = fixThisJson.encodeToString(SessionDto.serializer(), session)
        val decoded = fixThisJson.decodeFromString(SessionDto.serializer(), encoded)

        assertEquals(session, decoded)
        assertTrue(encoded.contains("ready_for_agent"))
        assertTrue(encoded.contains("visual_area"))
    }

    @Test
    fun feedbackSessionRoundTripsTargetEvidenceThroughJson() {
        val evidence = targetEvidenceForTest()
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Make this button clearer",
                    targetEvidence = evidence,
                ),
            ),
        )

        val encoded = fixThisJson.encodeToString(SessionDto.serializer(), session)
        val decoded = fixThisJson.decodeFromString(SessionDto.serializer(), encoded)

        assertTrue(encoded.contains("\"targetEvidence\""))
        assertEquals(evidence, decoded.items.single().targetEvidence)
    }

    @Test
    fun domainSessionMappersPreserveTargetEvidence() {
        val evidence = targetEvidenceForTest()
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Make this button clearer",
                    targetEvidence = evidence,
                ),
            ),
        )

        val roundTrip = session.toDomainSession().toSessionDto()

        assertEquals(evidence, roundTrip.items.single().targetEvidence)
    }

    @Test
    fun feedbackSessionRoundTripsDeliveryAndHandoffHistory() {
        val session = SessionDto(
            sessionId = "session-1",
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            items = listOf(
                AnnotationDto(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 11L,
                    updatedAtEpochMillis = 12L,
                    target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                    comment = "Fix spacing",
                    sequenceNumber = 1,
                    delivery = FeedbackDelivery.SENT,
                    handoffBatchId = "batch-1",
                    sentAtEpochMillis = 15L,
                ),
            ),
            handoffBatches = listOf(
                FeedbackHandoffBatch(
                    batchId = "batch-1",
                    sequenceNumber = 1,
                    createdAtEpochMillis = 15L,
                    itemIds = listOf("item-1"),
                    markdownSnapshot = "Batch markdown",
                ),
            ),
        )

        val encoded = fixThisJson.encodeToString(SessionDto.serializer(), session)
        val decoded = fixThisJson.decodeFromString(SessionDto.serializer(), encoded)

        assertEquals(session, decoded)
        assertTrue(encoded.contains("\"delivery\": \"sent\""))
        assertTrue(encoded.contains("\"handoffBatches\""))
    }

    @Test
    fun oldFeedbackSessionJsonDefaultsDeliveryAndHandoffHistory() {
        val decoded = fixThisJson.decodeFromString(
            SessionDto.serializer(),
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "session-1",
              "packageName": "io.github.beyondwin.fixthis.sample",
              "projectRoot": "/repo",
              "createdAtEpochMillis": 10,
              "updatedAtEpochMillis": 20,
              "items": [
                {
                  "itemId": "item-1",
                  "screenId": "screen-1",
                  "createdAtEpochMillis": 11,
                  "updatedAtEpochMillis": 12,
                  "target": {
                    "type": "visual_area",
                    "boundsInWindow": {
                      "left": 1.0,
                      "top": 2.0,
                      "right": 3.0,
                      "bottom": 4.0
                    }
                  },
                  "comment": "Old draft"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(emptyList(), decoded.handoffBatches)
        assertEquals(null, decoded.items.single().sequenceNumber)
        assertEquals(FeedbackDelivery.DRAFT, decoded.items.single().delivery)
        assertEquals(null, decoded.items.single().handoffBatchId)
        assertEquals(null, decoded.items.single().sentAtEpochMillis)
    }

    @Test
    fun loadsLegacySessionJsonWithoutLastHandedOffAtField() {
        val decoded = fixThisJson.decodeFromString(
            SessionDto.serializer(),
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "legacy-1",
              "packageName": "io.github.beyondwin.fixthis.sample",
              "projectRoot": "/repo",
              "createdAtEpochMillis": 1,
              "updatedAtEpochMillis": 1,
              "screens": [],
              "items": [
                {
                  "itemId": "item-1",
                  "screenId": "screen-1",
                  "createdAtEpochMillis": 1,
                  "updatedAtEpochMillis": 1,
                  "target": {
                    "type": "visual_area",
                    "boundsInWindow": {
                      "left": 0.0,
                      "top": 0.0,
                      "right": 1.0,
                      "bottom": 1.0
                    }
                  },
                  "comment": "x",
                  "delivery": "sent",
                  "status": "ready",
                  "sentAtEpochMillis": 1
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, decoded.items.size)
        assertNull(decoded.items.single().lastHandedOffAtEpochMillis, "legacy field should default to null")
    }

    @Test
    fun storeAssignsItemSequenceNumbersAndSendsDraftBatch() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "First",
            ),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
                comment = "Second",
            ),
        )

        val sent = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "markdown")

        assertEquals(SessionStatusDto.READY_FOR_AGENT, sent.status)
        assertEquals(listOf(1, 2), sent.items.map { it.sequenceNumber })
        assertEquals(listOf(FeedbackDelivery.SENT, FeedbackDelivery.SENT), sent.items.map { it.delivery })
        assertEquals(listOf("batch-1", "batch-1"), sent.items.map { it.handoffBatchId })
        assertEquals(1, sent.handoffBatches.single().sequenceNumber)
        assertEquals(listOf("item-1", "item-2"), sent.handoffBatches.single().itemIds)
    }

    @Test
    fun addScreenWithItemsPersistsOneScreenAndMultipleDraftItemsAtomically() {
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "screen-1", "item-1", "item-2"),
        )
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = SnapshotDto(
            screenId = "pending",
            capturedAtEpochMillis = 0L,
            activityName = "io.github.beyondwin.fixthis.sample.MainActivity",
            displayName = "MainActivity",
            screenshot = SnapshotScreenshotDto(width = 720, height = 1600),
        )
        val first = AnnotationDto(
            itemId = "pending",
            screenId = "pending",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 110f, 80f)),
            comment = "Change headline copy",
        )
        val second = AnnotationDto(
            itemId = "pending",
            screenId = "pending",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(120f, 200f, 260f, 280f)),
            comment = "Add more left margin",
        )

        val updated = store.addScreenWithItems(session.sessionId, screen, listOf(first, second))

        assertEquals(1, updated.screens.size)
        assertEquals("screen-1", updated.screens.single().screenId)
        assertEquals(2, updated.items.size)
        assertEquals(listOf("screen-1", "screen-1"), updated.items.map { it.screenId })
        assertEquals(listOf(1, 2), updated.items.map { it.sequenceNumber })
        assertEquals(listOf("item-1", "item-2"), updated.items.map { it.itemId })
    }

    @Test
    fun addScreenWithItemsRejectsBlankCommentsBeforeMutatingSession() {
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "screen-1", "item-1"),
        )
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = SnapshotDto(
            screenId = "pending",
            capturedAtEpochMillis = 0L,
            displayName = "MainActivity",
        )
        val blank = AnnotationDto(
            itemId = "pending",
            screenId = "pending",
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(10f, 20f, 110f, 80f)),
            comment = "",
        )

        val error = assertFailsWith<IllegalArgumentException> {
            store.addScreenWithItems(session.sessionId, screen, listOf(blank))
        }

        assertTrue(error.message!!.contains("blank comment"))
        assertTrue(store.getSession(session.sessionId).items.isEmpty())
        assertTrue(store.getSession(session.sessionId).screens.isEmpty())
    }

    @Test
    fun deleteScreenRemovesLinkedFeedbackItemsAndPrunesBatches() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "screen-2", "item-1", "batch-1", "item-2")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val firstScreen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "First"))
        val secondScreen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Second"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = firstScreen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Delete with screen",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "sent")
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = secondScreen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
                comment = "Keep",
            ),
        )

        val updated = store.deleteScreen(session.sessionId, firstScreen.screenId)

        assertEquals(listOf(secondScreen.screenId), updated.screens.map { it.screenId })
        assertEquals(listOf("item-2"), updated.items.map { it.itemId })
        assertTrue(updated.handoffBatches.isEmpty())
    }

    @Test
    fun deleteScreenReturnsNotFoundForUnknownScreen() {
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")

        val error = assertFailsWith<FeedbackSessionException> {
            store.deleteScreen(session.sessionId, "missing-screen")
        }

        assertTrue(error.message.orEmpty().contains("SCREEN_NOT_FOUND"))
    }

    @Test
    fun addItemAssignsNextSequenceAfterHighestExistingSequenceNumber() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "imported-item",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Imported",
                sequenceNumber = 10,
            ),
        )

        val added = store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
                comment = "Next",
            ),
        )

        assertEquals(11, added.sequenceNumber)
        assertEquals(listOf(10, 11), store.getSession(session.sessionId).items.map { it.sequenceNumber })
    }

    @Test
    fun itemSequenceCounterDoesNotReuseNumbersAfterDelete() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2", "item-3", "item-4")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession(packageName = "io.github.beyondwin.fixthis.sample", projectRoot = "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        fun draft(comment: String) = AnnotationDto(
            itemId = "pending",
            screenId = screen.screenId,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
            comment = comment,
        )

        val first = store.addItem(session.sessionId, draft("first"))
        val second = store.addItem(session.sessionId, draft("second"))
        val third = store.addItem(session.sessionId, draft("third"))

        store.deleteDraftItem(session.sessionId, second.itemId)
        val fourth = store.addItem(session.sessionId, draft("fourth"))

        val current = store.getSession(session.sessionId)
        assertEquals(listOf(1, 3, 4), current.items.map { it.sequenceNumber })
        assertEquals(5, current.nextItemSequenceNumber)
        assertEquals(listOf(1, 2, 3, 4), listOf(first, second, third, fourth).map { it.sequenceNumber })
    }

    @Test
    fun clearDraftItemsKeepsSentHistory() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Sent",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "sent")
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
                comment = "Draft",
            ),
        )

        val cleared = store.clearDraftItems(session.sessionId)

        assertEquals(listOf("Sent"), cleared.items.map { it.comment })
        assertEquals(FeedbackDelivery.SENT, cleared.items.single().delivery)
        assertEquals(1, cleared.handoffBatches.size)
    }

    @Test
    fun failedSendDraftSaveKeepsDraftSessionInMemory() {
        val root = tempDir(prefix = "fixthis-v2-send-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 100L })
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = ids::next, persistence = persistence)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Draft",
            ),
        )
        paths.sessionDirectory(session.sessionId).deleteRecursively()
        paths.sessionDirectory(session.sessionId).writeText("blocked")

        assertFailsWith<FeedbackSessionException> {
            store.sendDraftToAgent(session.sessionId, markdownSnapshot = "draft")
        }

        val current = store.getSession(session.sessionId)
        assertEquals(SessionStatusDto.ACTIVE, current.status)
        assertEquals(FeedbackDelivery.DRAFT, current.items.single().delivery)
        assertEquals(AnnotationStatusDto.OPEN, current.items.single().status)
        assertEquals(emptyList(), current.handoffBatches)
    }

    @Test
    fun storeCreatesSessionAndAddsScreenAndItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)

        val session = store.openSession(
            packageName = "io.github.beyondwin.fixthis.sample",
            projectRoot = "/repo",
        )
        val screen = store.addScreen(
            sessionId = session.sessionId,
            screen = SnapshotDto(
                screenId = "pending",
                capturedAtEpochMillis = -1L,
                displayName = "Checkout",
            ),
        )
        val item = store.addItem(
            sessionId = session.sessionId,
            item = AnnotationDto(
                itemId = "ignored",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val current = store.getSession(session.sessionId)
        assertEquals("session-1", session.sessionId)
        assertEquals("screen-1", screen.screenId)
        assertEquals("item-1", item.itemId)
        assertEquals(1, current.screens.size)
        assertEquals(1, current.items.size)
        assertEquals(AnnotationStatusDto.OPEN, current.items.single().status)
    }

    @Test
    fun addScreenPreservesReservedScreenId() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")

        val screen = store.addScreen(
            sessionId = session.sessionId,
            screen = SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = -1L,
                displayName = "Checkout",
            ),
        )

        assertEquals("screen-1", screen.screenId)
        assertEquals("screen-1", store.getSession(session.sessionId).screens.single().screenId)
    }

    @Test
    fun storeResolvesFeedbackItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", -1L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val resolved = store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = AnnotationStatusDto.RESOLVED,
            agentSummary = "Adjusted color token.",
        )

        assertEquals(AnnotationStatusDto.RESOLVED, resolved.status)
        assertEquals("Adjusted color token.", resolved.agentSummary)
        assertEquals(100L, resolved.updatedAtEpochMillis)
    }

    @Test
    fun storePersistsMutationsAndCanResumeLatestSession() {
        val root = tempDir(prefix = "fixthis-v2-store-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = { ids.removeFirst() },
            persistence = persistence,
        )

        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        val screen = store.addScreen(
            session.sessionId,
            SnapshotDto(screenId = "pending", capturedAtEpochMillis = 0L, displayName = "Main"),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "Fix it",
            ),
        )

        val resumed = FeedbackSessionStore(clock = { 200L }, persistence = persistence)

        assertEquals("session-1", resumed.currentSession()?.sessionId)
        assertEquals(1, resumed.currentSession()?.screens?.size)
        assertEquals(1, resumed.currentSession()?.items?.size)
    }

    @Test
    fun storeCanOpenExactPersistedSession() {
        val root = tempDir(prefix = "fixthis-v2-exact-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
        val created = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)

        val fresh = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
        val opened = fresh.openExistingSession(created.sessionId)

        assertEquals(created.sessionId, opened.sessionId)
        assertEquals(created.sessionId, fresh.currentSession()?.sessionId)
    }

    @Test
    fun failedSessionSaveDoesNotOpenUnsavedSession() {
        val root = tempDir(prefix = "fixthis-v2-open-fail-")
        root.resolve(".fixthis").writeText("blocked")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)

        assertFailsWith<FeedbackSessionException> {
            store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        }

        assertNull(store.currentSession())
        assertFailsWith<FeedbackSessionException> {
            store.getSession("session-1")
        }
    }

    @Test
    fun failedCloseSaveKeepsCurrentSessionOpenInMemory() {
        val root = tempDir(prefix = "fixthis-v2-close-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        paths.sessionDirectory(session.sessionId).deleteRecursively()
        paths.sessionDirectory(session.sessionId).writeText("blocked")

        assertFailsWith<FeedbackSessionException> {
            store.closeSession(session.sessionId)
        }

        assertEquals(session.sessionId, store.currentSession()?.sessionId)
        assertEquals(SessionStatusDto.ACTIVE, store.getSession(session.sessionId).status)
    }

    @Test
    fun claimFeedbackPromotesDraftItemToInProgress() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "fix this",
            ),
        )

        val claimed = store.claimFeedback(session.sessionId, "item-1", agentNote = "starting")

        assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
        assertEquals("starting", claimed.agentSummary)
        assertEquals(FeedbackDelivery.DRAFT, claimed.delivery)
    }

    @Test
    fun claimFeedbackOnSentItemKeepsSentDelivery() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "fix this",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")

        val claimed = store.claimFeedback(session.sessionId, "item-1", agentNote = null)

        assertEquals(AnnotationStatusDto.IN_PROGRESS, claimed.status)
        assertEquals(FeedbackDelivery.SENT, claimed.delivery)
    }

    @Test
    fun claimFeedbackIsIdempotentOnInProgress() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "fix this",
            ),
        )

        store.claimFeedback(session.sessionId, "item-1", agentNote = "first")
        val secondClaim = store.claimFeedback(session.sessionId, "item-1", agentNote = "second")

        assertEquals(AnnotationStatusDto.IN_PROGRESS, secondClaim.status)
        assertEquals("second", secondClaim.agentSummary)
    }

    @Test
    fun claimFeedbackRejectsResolvedItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                "pending",
                screen.screenId,
                0L,
                0L,
                AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "fix this",
            ),
        )
        store.updateItemStatus(session.sessionId, "item-1", AnnotationStatusDto.RESOLVED, agentSummary = "done")

        val ex = assertFailsWith<FeedbackSessionException> {
            store.claimFeedback(session.sessionId, "item-1", agentNote = null)
        }
        assertTrue(ex.message!!.startsWith("ITEM_ALREADY_RESOLVED"))
    }

    @Test
    fun claimFeedbackRejectsUnknownItem() {
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")

        val ex = assertFailsWith<FeedbackSessionException> {
            store.claimFeedback(session.sessionId, "missing", agentNote = null)
        }
        assertTrue(ex.message!!.contains("Unknown feedback item"))
    }

    @Test
    fun updateItemStatusStillRejectsInProgress() {
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")

        assertFailsWith<IllegalArgumentException> {
            store.updateItemStatus(session.sessionId, "item-1", AnnotationStatusDto.IN_PROGRESS, agentSummary = null)
        }
    }

    @Test
    fun updateItemStatusRejectsDisallowedAgentStatusWithMessage() {
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")

        // Retention guard for the resolution allow-list (RESOLVED/NEEDS_CLARIFICATION/WONT_FIX)
        // that the removed ResolveAnnotationUseCase used to own; it now lives on the store path.
        val error = assertFailsWith<IllegalArgumentException> {
            store.updateItemStatus(session.sessionId, "item-1", AnnotationStatusDto.OPEN, agentSummary = null)
        }
        assertEquals("Agent resolution status is not allowed: OPEN", error.message)
    }

    @Test
    fun sendDraftToAgentSetsLastHandedOffAtToSentAt() {
        val clock = FakeClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "First",
            ),
        )

        val updated = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        val item = updated.items.single { it.itemId == "item-1" }

        assertEquals(1000L, item.sentAtEpochMillis)
        assertEquals(1000L, item.lastHandedOffAtEpochMillis)
    }

    @Test
    fun sendDraftToAgentReSaveUpdatesLastHandedOffAtAndAddsNewBatch() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1000", "batch-2000")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "First",
            ),
        )

        val first = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap-1")
        assertEquals(1000L, first.items.single { it.itemId == "item-1" }.lastHandedOffAtEpochMillis)
        assertEquals(1, first.handoffBatches.size)

        clock.value = 2000L
        val second = store.sendDraftToAgent(
            sessionId = session.sessionId,
            markdownSnapshot = "snap-2",
            targetItemIds = listOf("item-1"),
        )
        val item = second.items.single { it.itemId == "item-1" }
        assertEquals(2000L, item.lastHandedOffAtEpochMillis, "re-save updates lastHandedOffAt")
        assertEquals(1000L, item.sentAtEpochMillis, "first sentAt is preserved")
        assertEquals(FeedbackDelivery.SENT, item.delivery)
        assertEquals(2, second.handoffBatches.size, "re-save creates a new batch")
        assertEquals("batch-2000", second.handoffBatches[1].batchId)
        assertEquals(listOf("item-1"), second.handoffBatches[1].itemIds)
        assertEquals("batch-2000", item.handoffBatchId, "handoffBatchId points to latest batch")
    }

    @Test
    fun updateDraftItemAllowsSentReadyComment() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")

        clock.value = 2000L
        val updated = store.updateDraftItem(
            sessionId = session.sessionId,
            itemId = "item-1",
            label = null,
            severity = null,
            comment = "edited after send",
            status = null,
        )
        val item = updated.items.single { it.itemId == "item-1" }
        assertEquals("edited after send", item.comment)
        assertEquals(FeedbackDelivery.SENT, item.delivery)
        assertEquals(AnnotationStatusDto.READY, item.status)
        assertTrue(
            item.updatedAtEpochMillis > item.sentAtEpochMillis!!,
            "updatedAt must reflect the post-send edit",
        )
    }

    @Test
    fun staleStoreMutationPreservesNewerAgentResolutionFromDisk() {
        val root = tempDir(prefix = "fixthis-v2-cross-process-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 100L })
        val consoleClock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2", "batch-1")
        val consoleStore = FeedbackSessionStore(
            clock = consoleClock::now,
            idGenerator = ids::next,
            persistence = persistence,
        )
        val session = consoleStore.openSession("io.github.beyondwin.fixthis.sample", root.absolutePath)
        val screen = consoleStore.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Home"))
        consoleStore.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "first",
            ),
        )
        consoleStore.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(10f, 10f, 20f, 20f)),
                comment = "second",
            ),
        )
        consoleStore.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")

        val agentStore = FeedbackSessionStore(clock = { 2000L }, persistence = persistence)
        agentStore.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = AnnotationStatusDto.RESOLVED,
            agentSummary = "fixed",
        )

        consoleClock.value = 3000L
        consoleStore.updateDraftItem(
            sessionId = session.sessionId,
            itemId = "item-2",
            label = null,
            severity = null,
            comment = "second edited from console",
            status = null,
        )

        val persisted = persistence.load(session.sessionId)
        val first = persisted.items.single { it.itemId == "item-1" }
        val second = persisted.items.single { it.itemId == "item-2" }
        assertEquals(AnnotationStatusDto.RESOLVED, first.status)
        assertEquals("fixed", first.agentSummary)
        assertEquals("second edited from console", second.comment)
    }

    @Test
    fun updateDraftItemRejectsSentInProgress() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        store.claimFeedback(session.sessionId, "item-1", agentNote = "starting")

        val ex = assertFailsWith<FeedbackSessionException> {
            store.updateDraftItem(
                sessionId = session.sessionId,
                itemId = "item-1",
                label = null,
                severity = null,
                comment = "should be blocked",
                status = null,
            )
        }
        assertTrue(ex.message!!.startsWith("ITEM_NOT_EDITABLE"))
        assertTrue(
            ex.message!!.contains("Agent has claimed this item"),
            "error message reflects new lock semantics: ${ex.message}",
        )
    }

    @Test
    fun updateDraftItemRejectsSentResolved() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = AnnotationStatusDto.RESOLVED,
            agentSummary = "fixed",
        )

        val ex = assertFailsWith<FeedbackSessionException> {
            store.updateDraftItem(
                sessionId = session.sessionId,
                itemId = "item-1",
                label = null,
                severity = null,
                comment = "should be blocked",
                status = null,
            )
        }
        assertTrue(ex.message!!.startsWith("ITEM_NOT_EDITABLE"))
    }

    @Test
    fun updateDraftItemAllowsSentNeedsClarification() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = AnnotationStatusDto.NEEDS_CLARIFICATION,
            agentSummary = "which variant?",
        )

        val updated = store.updateDraftItem(
            sessionId = session.sessionId,
            itemId = "item-1",
            label = null,
            severity = null,
            comment = "clarified from console",
            status = null,
        )

        assertEquals("clarified from console", updated.items.single().comment)
        assertEquals(AnnotationStatusDto.NEEDS_CLARIFICATION, updated.items.single().status)
    }

    @Test
    fun updateDraftItemRejectsSentWontFix() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = AnnotationStatusDto.WONT_FIX,
            agentSummary = "outside scope",
        )

        val ex = assertFailsWith<FeedbackSessionException> {
            store.updateDraftItem(
                sessionId = session.sessionId,
                itemId = "item-1",
                label = null,
                severity = null,
                comment = "should be blocked",
                status = null,
            )
        }
        assertTrue(ex.message!!.startsWith("ITEM_NOT_EDITABLE"))
    }

    @Test
    fun deleteDraftItemAllowsSentReady() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")

        val updated = store.deleteDraftItem(session.sessionId, "item-1")
        assertTrue(updated.items.none { it.itemId == "item-1" })
    }

    @Test
    fun deleteDraftItemRejectsSentInProgress() {
        val clock = MutableClock(1000L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.beyondwin.fixthis.sample", "/repo")
        val screen = store.addScreen(session.sessionId, SnapshotDto("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 10f, 10f)),
                comment = "original",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "snap")
        store.claimFeedback(session.sessionId, "item-1", agentNote = null)

        val ex = assertFailsWith<FeedbackSessionException> {
            store.deleteDraftItem(session.sessionId, "item-1")
        }
        assertTrue(ex.message!!.startsWith("ITEM_NOT_EDITABLE"))
        assertTrue(ex.message!!.contains("Agent has claimed this item"))
    }

    private class MutableClock(var value: Long) {
        fun now(): Long = value
    }

    private fun sequenceClock(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: values.last() }
    }

    private fun sequenceIds(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: error("No more ids configured") }
    }

    private class FakeClock(private val value: Long) {
        fun now(): Long = value
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        fun next(): String = queue.removeFirst()
    }

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

    private fun targetEvidenceForTest(): TargetEvidence = TargetEvidence(
        identityHint = IdentityHint(
            composableNameHint = "AppPrimaryButton",
            variantHint = "primary",
            stableLabel = "Button Sign In",
            source = IdentityHintSource.TEST_TAG_CONVENTION,
            confidence = IdentityHintConfidence.HIGH,
        ),
        occurrence = Occurrence(
            signature = OccurrenceSignature(
                type = OccurrenceSignatureType.IDENTITY_HINT,
                value = "AppPrimaryButton:primary",
            ),
            count = 2,
            selectedOrdinal = 1,
        ),
        sourceInterpretation = SourceInterpretation(
            topCandidate = SourceCandidateSummary(
                file = "sample/src/main/java/io/github/beyondwin/fixthis/sample/components/AppPrimaryButton.kt",
                line = 42,
                confidence = SelectionConfidence.HIGH,
            ),
            reasonSummary = listOf("selected testTag convention composable"),
        ),
        evidenceQuality = EvidenceQuality.STRUCTURED,
        screenshotKinds = listOf("full", "crop"),
        warnings = listOf("fallback source candidate was ignored"),
    )
}
