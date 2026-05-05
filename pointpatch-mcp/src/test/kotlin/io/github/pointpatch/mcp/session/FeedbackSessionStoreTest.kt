package io.github.pointpatch.mcp.session

import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedbackSessionStoreTest {
    @Test
    fun feedbackSessionRoundTripsThroughJson() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            screens = listOf(
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 11L,
                    activityName = "MainActivity",
                    displayName = "MainActivity",
                    screenshot = FeedbackScreenshot(desktopFullPath = "/repo/.pointpatch/artifacts/screen-1/full.png"),
                ),
            ),
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 12L,
                    updatedAtEpochMillis = 13L,
                    target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
                    comment = "Make this button clearer",
                    status = FeedbackItemStatus.READY,
                ),
            ),
            status = FeedbackSessionStatus.READY_FOR_AGENT,
        )

        val encoded = pointPatchJson.encodeToString(FeedbackSession.serializer(), session)
        val decoded = pointPatchJson.decodeFromString(FeedbackSession.serializer(), encoded)

        assertEquals(session, decoded)
        assertTrue(encoded.contains("ready_for_agent"))
        assertTrue(encoded.contains("visual_area"))
    }

    @Test
    fun feedbackSessionRoundTripsDeliveryAndHandoffHistory() {
        val session = FeedbackSession(
            sessionId = "session-1",
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 20L,
            items = listOf(
                FeedbackItem(
                    itemId = "item-1",
                    screenId = "screen-1",
                    createdAtEpochMillis = 11L,
                    updatedAtEpochMillis = 12L,
                    target = FeedbackTarget.Area(PointPatchRect(1f, 2f, 3f, 4f)),
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

        val encoded = pointPatchJson.encodeToString(FeedbackSession.serializer(), session)
        val decoded = pointPatchJson.decodeFromString(FeedbackSession.serializer(), encoded)

        assertEquals(session, decoded)
        assertTrue(encoded.contains("\"delivery\": \"sent\""))
        assertTrue(encoded.contains("\"handoffBatches\""))
    }

    @Test
    fun oldFeedbackSessionJsonDefaultsDeliveryAndHandoffHistory() {
        val decoded = pointPatchJson.decodeFromString(
            FeedbackSession.serializer(),
            """
            {
              "schemaVersion": "1.0",
              "sessionId": "session-1",
              "packageName": "io.github.pointpatch.sample",
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
    fun storeAssignsItemSequenceNumbersAndSendsDraftBatch() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2", "batch-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.pointpatch.sample", "/repo")
        val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                comment = "First",
            ),
        )
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
                comment = "Second",
            ),
        )

        val sent = store.sendDraftToAgent(session.sessionId, markdownSnapshot = "markdown")

        assertEquals(FeedbackSessionStatus.READY_FOR_AGENT, sent.status)
        assertEquals(listOf(1, 2), sent.items.map { it.sequenceNumber })
        assertEquals(listOf(FeedbackDelivery.SENT, FeedbackDelivery.SENT), sent.items.map { it.delivery })
        assertEquals(listOf("batch-1", "batch-1"), sent.items.map { it.handoffBatchId })
        assertEquals(1, sent.handoffBatches.single().sequenceNumber)
        assertEquals(listOf("item-1", "item-2"), sent.handoffBatches.single().itemIds)
    }

    @Test
    fun addItemAssignsNextSequenceAfterHighestExistingSequenceNumber() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "item-2")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.pointpatch.sample", "/repo")
        val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "imported-item",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                comment = "Imported",
                sequenceNumber = 10,
            ),
        )

        val added = store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
                comment = "Next",
            ),
        )

        assertEquals(11, added.sequenceNumber)
        assertEquals(listOf(10, 11), store.getSession(session.sessionId).items.map { it.sequenceNumber })
    }

    @Test
    fun clearDraftItemsKeepsSentHistory() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.pointpatch.sample", "/repo")
        val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                "pending",
                screen.screenId,
                0L,
                0L,
                FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                comment = "Sent",
            ),
        )
        store.sendDraftToAgent(session.sessionId, markdownSnapshot = "sent")
        store.addItem(
            session.sessionId,
            FeedbackItem(
                "pending",
                screen.screenId,
                0L,
                0L,
                FeedbackTarget.Area(PointPatchRect(10f, 10f, 20f, 20f)),
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
        val root = createTempDir(prefix = "pointpatch-v2-send-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 100L })
        val ids = FakeIds("session-1", "screen-1", "item-1", "batch-1")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = ids::next, persistence = persistence)
        val session = store.openSession("io.github.pointpatch.sample", root.absolutePath)
        val screen = store.addScreen(session.sessionId, CapturedScreen("pending", 0L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                "pending",
                screen.screenId,
                0L,
                0L,
                FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
                comment = "Draft",
            ),
        )
        paths.sessionDirectory(session.sessionId).deleteRecursively()
        paths.sessionDirectory(session.sessionId).writeText("blocked")

        assertFailsWith<FeedbackSessionException> {
            store.sendDraftToAgent(session.sessionId, markdownSnapshot = "draft")
        }

        val current = store.getSession(session.sessionId)
        assertEquals(FeedbackSessionStatus.ACTIVE, current.status)
        assertEquals(FeedbackDelivery.DRAFT, current.items.single().delivery)
        assertEquals(FeedbackItemStatus.OPEN, current.items.single().status)
        assertEquals(emptyList(), current.handoffBatches)
    }

    @Test
    fun storeCreatesSessionAndAddsScreenAndItem() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1", "screen-1", "item-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)

        val session = store.openSession(
            packageName = "io.github.pointpatch.sample",
            projectRoot = "/repo",
        )
        val screen = store.addScreen(
            sessionId = session.sessionId,
            screen = CapturedScreen(
                screenId = "pending",
                capturedAtEpochMillis = -1L,
                displayName = "Checkout",
            ),
        )
        val item = store.addItem(
            sessionId = session.sessionId,
            item = FeedbackItem(
                itemId = "ignored",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = FeedbackTarget.Area(PointPatchRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val current = store.getSession(session.sessionId)
        assertEquals("session-1", session.sessionId)
        assertEquals("screen-1", screen.screenId)
        assertEquals("item-1", item.itemId)
        assertEquals(1, current.screens.size)
        assertEquals(1, current.items.size)
        assertEquals(FeedbackItemStatus.OPEN, current.items.single().status)
    }

    @Test
    fun addScreenPreservesReservedScreenId() {
        val clock = FakeClock(100L)
        val ids = FakeIds("session-1")
        val store = FeedbackSessionStore(clock = clock::now, idGenerator = ids::next)
        val session = store.openSession("io.github.pointpatch.sample", "/repo")

        val screen = store.addScreen(
            sessionId = session.sessionId,
            screen = CapturedScreen(
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
        val session = store.openSession("io.github.pointpatch.sample", "/repo")
        val screen = store.addScreen(session.sessionId, CapturedScreen("pending", -1L, displayName = "Checkout"))
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = -1L,
                updatedAtEpochMillis = -1L,
                target = FeedbackTarget.Area(PointPatchRect(1f, 1f, 10f, 10f)),
                comment = "Increase contrast",
            ),
        )

        val resolved = store.updateItemStatus(
            sessionId = session.sessionId,
            itemId = "item-1",
            status = FeedbackItemStatus.RESOLVED,
            agentSummary = "Adjusted color token.",
        )

        assertEquals(FeedbackItemStatus.RESOLVED, resolved.status)
        assertEquals("Adjusted color token.", resolved.agentSummary)
        assertEquals(100L, resolved.updatedAtEpochMillis)
    }

    @Test
    fun storePersistsMutationsAndCanResumeLatestSession() {
        val root = createTempDir(prefix = "pointpatch-v2-store-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val ids = ArrayDeque(listOf("session-1", "screen-1", "item-1"))
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = { ids.removeFirst() },
            persistence = persistence,
        )

        val session = store.openSession("io.github.pointpatch.sample", root.absolutePath)
        val screen = store.addScreen(
            session.sessionId,
            CapturedScreen(screenId = "pending", capturedAtEpochMillis = 0L, displayName = "Main"),
        )
        store.addItem(
            session.sessionId,
            FeedbackItem(
                itemId = "pending",
                screenId = screen.screenId,
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = FeedbackTarget.Area(PointPatchRect(0f, 0f, 10f, 10f)),
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
        val root = createTempDir(prefix = "pointpatch-v2-exact-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
        val created = store.openSession("io.github.pointpatch.sample", root.absolutePath)

        val fresh = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
        val opened = fresh.openExistingSession(created.sessionId)

        assertEquals(created.sessionId, opened.sessionId)
        assertEquals(created.sessionId, fresh.currentSession()?.sessionId)
    }

    @Test
    fun failedSessionSaveDoesNotOpenUnsavedSession() {
        val root = createTempDir(prefix = "pointpatch-v2-open-fail-")
        root.resolve(".pointpatch").writeText("blocked")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)

        assertFailsWith<FeedbackSessionException> {
            store.openSession("io.github.pointpatch.sample", root.absolutePath)
        }

        assertNull(store.currentSession())
        assertFailsWith<FeedbackSessionException> {
            store.getSession("session-1")
        }
    }

    @Test
    fun failedCloseSaveKeepsCurrentSessionOpenInMemory() {
        val root = createTempDir(prefix = "pointpatch-v2-close-fail-")
        val paths = FeedbackSessionPaths(root)
        val persistence = FeedbackSessionPersistence(paths, clock = { 100L })
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence)
        val session = store.openSession("io.github.pointpatch.sample", root.absolutePath)
        paths.sessionDirectory(session.sessionId).deleteRecursively()
        paths.sessionDirectory(session.sessionId).writeText("blocked")

        assertFailsWith<FeedbackSessionException> {
            store.closeSession(session.sessionId)
        }

        assertEquals(session.sessionId, store.currentSession()?.sessionId)
        assertEquals(FeedbackSessionStatus.ACTIVE, store.getSession(session.sessionId).status)
    }

    private class FakeClock(private val value: Long) {
        fun now(): Long = value
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        fun next(): String = queue.removeFirst()
    }
}
