package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
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
import io.github.beyondwin.fixthis.mcp.session.runtime.FileRuntimeEvidenceArtifactStore
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceArtifactInput
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceAttachment
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidencePolicy
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceRedactor
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceService
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceStatus
import io.github.beyondwin.fixthis.mcp.session.runtime.RuntimeEvidenceType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEvidenceSessionEventTest {
    @Test
    fun runtimeEvidenceReplaysAttachmentAndTwoItemLinksTogether() = withFixture { fixture ->
        fixture.store.attachRuntimeEvidence(
            sessionId = fixture.session.sessionId,
            expectedScreenId = fixture.screen.screenId,
            itemIds = fixture.items.map { it.itemId },
            attachments = listOf(attachment("evidence-1", "capture-1")),
            aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
        )

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertEquals(listOf("evidence-1"), replayed.runtimeEvidence.map { it.evidenceId })
        assertTrue(replayed.items.all { it.runtimeEvidenceIds == listOf("evidence-1") })
        assertEquals(
            listOf("runtimeEvidenceCaptured"),
            fixture.events().filter { it.type == "runtimeEvidenceCaptured" }.map { it.type },
        )
    }

    @Test
    fun runtimeEvidenceDuplicateEventsRemainIdempotentOnReduceAndReplay() = withFixture { fixture ->
        repeat(2) {
            fixture.store.attachRuntimeEvidence(
                sessionId = fixture.session.sessionId,
                expectedScreenId = fixture.screen.screenId,
                itemIds = fixture.items.map { item -> item.itemId },
                attachments = listOf(attachment("evidence-1", "capture-1")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertEquals(listOf("evidence-1"), replayed.runtimeEvidence.map { it.evidenceId })
        assertTrue(replayed.items.all { it.runtimeEvidenceIds == listOf("evidence-1") })
    }

    @Test
    fun runtimeEvidencePolicyOffAndManualReplayFromExactEvents() = withFixture { fixture ->
        fixture.store.updateRuntimeEvidencePolicy(fixture.session.sessionId, RuntimeEvidencePolicy.OFF)
        val off = fixture.reopen()
        assertEquals(RuntimeEvidencePolicy.OFF, off.getSession(fixture.session.sessionId).runtimeEvidencePolicy)

        off.updateRuntimeEvidencePolicy(fixture.session.sessionId, RuntimeEvidencePolicy.MANUAL)
        val manual = fixture.reopen()
        assertEquals(RuntimeEvidencePolicy.MANUAL, manual.getSession(fixture.session.sessionId).runtimeEvidencePolicy)
        assertEquals(
            listOf("runtimeEvidencePolicyUpdated", "runtimeEvidencePolicyUpdated"),
            fixture.events().filter { it.type == "runtimeEvidencePolicyUpdated" }.map { it.type },
        )
    }

    @Test
    fun runtimeEvidenceEventWithoutSnapshotSurvivesReplay() = withFixture { fixture ->
        val sessionFile = fixture.paths.sessionFile(fixture.session.sessionId)
        val snapshotBeforeCapture = sessionFile.readText()
        assertTrue(sessionFile.delete())
        assertTrue(sessionFile.mkdir())

        assertFailsWith<FeedbackSessionException> {
            fixture.store.attachRuntimeEvidence(
                sessionId = fixture.session.sessionId,
                expectedScreenId = fixture.screen.screenId,
                itemIds = fixture.items.map { it.itemId },
                attachments = listOf(attachment("evidence-crash", "capture-crash")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }
        assertEquals(1, fixture.events().count { it.type == "runtimeEvidenceCaptured" })

        assertTrue(sessionFile.deleteRecursively())
        sessionFile.writeText(snapshotBeforeCapture)
        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertEquals(listOf("evidence-crash"), replayed.runtimeEvidence.map { it.evidenceId })
        assertTrue(replayed.items.all { it.runtimeEvidenceIds == listOf("evidence-crash") })
    }

    @Test
    fun runtimeEvidenceChangedContextsAppendNothingAndUseStableError() = withFixture { fixture ->
        val sessionId = fixture.session.sessionId
        val target = fixture.items.first()

        fixture.assertContextChangedWithoutEvent {
            fixture.store.attachRuntimeEvidence(
                "missing-session",
                expectedScreenId = fixture.screen.screenId,
                itemIds = listOf(target.itemId),
                attachments = listOf(attachment("missing-session")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }

        fixture.assertContextChangedWithoutEvent {
            fixture.store.attachRuntimeEvidence(
                sessionId,
                expectedScreenId = "replaced-screen",
                itemIds = listOf(target.itemId),
                attachments = listOf(attachment("wrong-screen")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }

        fixture.store.deleteDraftItem(sessionId, target.itemId)
        fixture.assertContextChangedWithoutEvent {
            fixture.store.attachRuntimeEvidence(
                sessionId,
                expectedScreenId = fixture.screen.screenId,
                itemIds = listOf(target.itemId),
                attachments = listOf(attachment("deleted-item")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }

        fixture.store.closeSession(sessionId)
        fixture.assertContextChangedWithoutEvent {
            fixture.store.attachRuntimeEvidence(
                sessionId,
                expectedScreenId = fixture.screen.screenId,
                itemIds = listOf(fixture.items.last().itemId),
                attachments = listOf(attachment("closed-session")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }
    }

    @Test
    fun runtimeEvidenceWriterFailureLeavesSessionStateUnchanged() {
        var failWrites = false
        withFixture(onWriteHook = {
            if (failWrites) throw IOException("event disk full")
        }) { fixture ->
            val before = fixture.store.getSession(fixture.session.sessionId)
            val eventCount = fixture.events().size
            failWrites = true

            assertFailsWith<EventLogException> {
                fixture.store.attachRuntimeEvidence(
                    sessionId = fixture.session.sessionId,
                    expectedScreenId = fixture.screen.screenId,
                    itemIds = listOf(fixture.items.first().itemId),
                    attachments = listOf(attachment("not-linked")),
                    aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
                )
            }

            assertEquals(before, fixture.store.getSession(fixture.session.sessionId))
            assertEquals(eventCount, fixture.events().size)
        }
    }

    @Test
    fun runtimeEvidenceLegacySnapshotOnlyManualLinksSurviveFullLogReplay() = withFixture { fixture ->
        val legacyAttachment = attachment("legacy-manual")
        val linkedItemId = fixture.items.first().itemId
        val snapshotOnly = fixture.store.getSession(fixture.session.sessionId).copy(
            runtimeEvidence = listOf(legacyAttachment),
            items = fixture.store.getSession(fixture.session.sessionId).items.map { item ->
                if (item.itemId == linkedItemId) item.copy(runtimeEvidenceIds = listOf("legacy-manual")) else item
            },
        )
        fixture.store.replaceSessionForDomain(snapshotOnly)

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertEquals(listOf("legacy-manual"), replayed.runtimeEvidence.map { it.evidenceId })
        assertEquals(listOf("legacy-manual"), replayed.items.single { it.itemId == linkedItemId }.runtimeEvidenceIds)
    }

    @Test
    fun runtimeEvidenceManualAttachmentUsesEventBackedStoreAndReplays() = withFixture { fixture ->
        val service = RuntimeEvidenceService(
            store = fixture.store,
            idGenerator = { "manual-evidence" },
            clock = { 2_000L },
        )

        service.attachManualSummary(
            sessionId = fixture.session.sessionId,
            itemId = fixture.items.first().itemId,
            type = RuntimeEvidenceType.LOGCAT_WINDOW,
            summary = "bounded manual summary",
            artifactPath = null,
        )

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)
        assertEquals(listOf("manual-evidence"), replayed.runtimeEvidence.map { it.evidenceId })
        assertEquals(listOf("manual-evidence"), replayed.items.first().runtimeEvidenceIds)
        assertTrue(replayed.items.last().runtimeEvidenceIds.isEmpty())
    }

    @Test
    fun runtimeEvidenceBootCleanupRunsAfterReplayAndPreservesOnlyReferencedBundle() = withFixture { fixture ->
        val artifacts = FileRuntimeEvidenceArtifactStore(fixture.root, RuntimeEvidenceRedactor())
        artifacts.commit(
            fixture.session.sessionId,
            "capture-kept",
            listOf(RuntimeEvidenceArtifactInput(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "safe")),
        )
        artifacts.commit(
            fixture.session.sessionId,
            "capture-orphan",
            listOf(RuntimeEvidenceArtifactInput(RuntimeEvidenceType.LOGCAT_WINDOW, "logcat.txt", "safe")),
        )

        val sessionFile = fixture.paths.sessionFile(fixture.session.sessionId)
        val snapshotBeforeCapture = sessionFile.readText()
        assertTrue(sessionFile.delete())
        assertTrue(sessionFile.mkdir())
        assertFailsWith<FeedbackSessionException> {
            fixture.store.attachRuntimeEvidence(
                sessionId = fixture.session.sessionId,
                expectedScreenId = fixture.screen.screenId,
                itemIds = listOf(fixture.items.first().itemId),
                attachments = listOf(attachment("event-evidence", "capture-kept")),
                aggregateStatus = RuntimeEvidenceStatus.COMPLETE,
            )
        }
        assertTrue(sessionFile.deleteRecursively())
        sessionFile.writeText(snapshotBeforeCapture)

        val replayed = fixture.reopen().getSession(fixture.session.sessionId)

        assertEquals(listOf("capture-kept"), replayed.runtimeEvidence.mapNotNull { it.captureId })
        assertTrue(File(fixture.root, ".fixthis/runtime-evidence/${fixture.session.sessionId}/capture-kept").isDirectory)
        assertFalse(File(fixture.root, ".fixthis/runtime-evidence/${fixture.session.sessionId}/capture-orphan").exists())
    }

    private fun attachment(
        evidenceId: String,
        captureId: String? = null,
    ): RuntimeEvidenceAttachment = RuntimeEvidenceAttachment(
        evidenceId = evidenceId,
        type = RuntimeEvidenceType.LOGCAT_WINDOW,
        capturedAtEpochMillis = 1_500L,
        packageName = "com.test",
        summary = "redacted summary",
        captureId = captureId,
    )

    private fun withFixture(
        onWriteHook: (Path) -> Unit = {},
        block: (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("runtime-evidence-event").toFile()
        try {
            block(Fixture.create(root, onWriteHook))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(
        val root: File,
        val paths: FeedbackSessionPaths,
        val persistence: FeedbackSessionPersistence,
        val eventsRoot: File,
        val store: FeedbackSessionStore,
        val session: SessionDto,
        val screen: SnapshotDto,
        val items: List<AnnotationDto>,
        val nextId: () -> String,
    ) {
        fun events() = EventLogReader(eventDirectory()).readAll()

        fun reopen(): FeedbackSessionStore = FeedbackSessionStore(
            clock = { 3_000L },
            idGenerator = nextId,
            persistence = persistence,
            eventLogWriterProvider = { EventLogWriter(File(eventsRoot, "$it/events")) },
            eventLogReaderProvider = { EventLogReader(File(eventsRoot, "$it/events")) },
        )

        fun assertContextChangedWithoutEvent(block: () -> Unit) {
            val eventCount = events().size
            val stateBefore = store.getSession(session.sessionId)
            val failure = assertFailsWith<FeedbackSessionException>(block = block)
            assertTrue(failure.message.orEmpty().startsWith("RUNTIME_EVIDENCE_CONTEXT_CHANGED:"))
            assertEquals(eventCount, events().size)
            assertEquals(stateBefore, store.getSession(session.sessionId))
        }

        private fun eventDirectory() = File(eventsRoot, "${session.sessionId}/events")

        companion object {
            fun create(
                root: File,
                onWriteHook: (Path) -> Unit,
            ): Fixture {
                val paths = FeedbackSessionPaths(root)
                val persistence = FeedbackSessionPersistence(paths)
                val eventsRoot = File(root, "event-journal")
                var id = 0
                val ids: () -> String = { "id-${++id}" }
                val store = FeedbackSessionStore(
                    clock = { 1_000L },
                    idGenerator = ids,
                    persistence = persistence,
                    eventLogWriterProvider = { EventLogWriter(File(eventsRoot, "$it/events"), onWriteHook) },
                    eventLogReaderProvider = { EventLogReader(File(eventsRoot, "$it/events")) },
                )
                val session = store.openSession("com.test", root.absolutePath)
                val screen = store.addScreen(
                    session.sessionId,
                    SnapshotDto("pending", 0L, displayName = "Screen"),
                )
                val items = listOf("first", "second").map { comment ->
                    store.addItem(
                        session.sessionId,
                        AnnotationDto(
                            itemId = "pending",
                            screenId = screen.screenId,
                            createdAtEpochMillis = 0L,
                            updatedAtEpochMillis = 0L,
                            target = AnnotationTargetDto.Area(FixThisRect(0f, 0f, 1f, 1f)),
                            comment = comment,
                        ),
                    )
                }
                return Fixture(root, paths, persistence, eventsRoot, store, session, screen, items, ids)
            }
        }
    }
}
