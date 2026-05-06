package io.beyondwin.fixthis.mcp.session

import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.compose.core.source.SourceIndex
import io.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackSessionServiceTest {
    @Test
    fun openSessionReusesCurrentSessionForSamePackageAndProject() {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )

        val first = service.openSession(null)
        val second = service.openSession(null)

        assertEquals("session-1", second.sessionId)
        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun openSessionTreatsBlankPackageOverrideAsDefaultPackage() {
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )

        val session = service.openSession("  ")

        assertEquals("io.beyondwin.fixthis.sample", session.packageName)
        assertEquals(listOf<String?>("io.beyondwin.fixthis.sample"), bridge.resolvedOverrides)
    }

    @Test
    fun serviceOpensExactPersistedSession() {
        val root = createTempDir(prefix = "fixthis-v2-service-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = FakeIds("session-1").next,
            persistence = persistence,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val created = service.openSession(packageNameOverride = null, newSession = true)
        val freshStore = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
        val freshService = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.other"),
            store = freshStore,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.other",
        )

        val reopened = freshService.openSession(packageNameOverride = null, sessionId = created.sessionId)

        assertEquals(created.sessionId, reopened.sessionId)
        assertEquals(created.sessionId, freshStore.currentSession()?.sessionId)
    }

    @Test
    fun serviceListsSessionsForPackage() {
        val root = createTempDir(prefix = "fixthis-v2-list-")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(packageNameOverride = null, newSession = true)

        val sessions = service.listSessions(packageNameOverride = "io.beyondwin.fixthis.sample")

        assertEquals(listOf("session-1"), sessions.sessions.map { it.sessionId })
    }

    @Test
    fun serviceAutoResumesLatestNonClosedPersistedSessionForPackageAndProject() {
        val root = createTempDir(prefix = "fixthis-v2-auto-resume-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 500L })
        persistence.save(
            SessionDto(
                sessionId = "sample-old",
                packageName = "io.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "sample-closed",
                packageName = "io.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 200L,
                updatedAtEpochMillis = 400L,
                status = SessionStatusDto.CLOSED,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "sample-latest",
                packageName = "io.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 300L,
                updatedAtEpochMillis = 300L,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "other-current",
                packageName = "io.beyondwin.fixthis.other",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 400L,
                updatedAtEpochMillis = 450L,
            ),
        )
        val store = FeedbackSessionStore(
            clock = { 600L },
            idGenerator = FakeIds("new-session").next,
            persistence = persistence,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )

        val session = service.openSession(packageNameOverride = null)

        assertEquals("sample-latest", session.sessionId)
        assertEquals("sample-latest", store.currentSession()?.sessionId)
    }

    @Test
    fun captureScreenAddsScreenToCurrentSession() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )

        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        assertEquals("screen-1", screen.screenId)
        assertEquals("MainActivity", screen.displayName)
        assertEquals(1, store.getSession(session.sessionId).screens.size)
    }

    @Test
    fun captureUsesSessionOwnedArtifactPath() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-artifacts-")
        val bridge = FakeFixThisBridge(packageName = "io.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)

        service.captureScreen(session.sessionId)

        assertEquals("session-1", bridge.lastCaptureSessionId)
        assertEquals("screen-1", bridge.lastCaptureScreenId)
        assertTrue(
            bridge.lastCaptureDestination!!
                .contains(".fixthis/feedback-sessions/session-1/artifacts/screens/screen-1"),
        )
    }

    @Test
    fun savingFrozenPreviewPersistsOneScreenForMultipleItems() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-preview-save-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1", "item-2"),
        )
        val service = FeedbackSessionService(bridge = bridge, store = store, projectRoot = root.absolutePath)
        val session = service.openSession("io.beyondwin.fixthis.sample", newSession = true)

        val preview = service.capturePreview(session.sessionId)
        assertEquals(1, bridge.captureCount)
        assertTrue(store.getSession(session.sessionId).screens.isEmpty())

        val updated = service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.NODE,
                    nodeUid = "email-label",
                    bounds = FixThisRect(28f, 77f, 692f, 186f),
                    comment = "Rename this label",
                ),
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Change this visual area",
                ),
            ),
        )

        assertEquals(1, updated.screens.size)
        assertEquals(1, bridge.captureCount)
        assertEquals(2, updated.items.size)
        assertEquals(listOf("screen-1", "screen-1"), updated.items.map { it.screenId })
        assertTrue(updated.items.first().selectedNode?.text.orEmpty().contains("Email address"))
        assertEquals(listOf("promo-card"), updated.items.first().nearbyNodes.map { it.uid })
        assertTrue(updated.items[1].nearbyNodes.isNotEmpty())
        assertTrue(updated.items.first().sourceCandidates.isNotEmpty())
        assertTrue(updated.items[1].sourceCandidates.isNotEmpty())
    }

    @Test
    fun savingNodePreviewFeedbackUsesSelectedNodeAndSameRootNearbyEvidence() = runBlocking {
        val selected = FixThisNode(
            uid = "email-label",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
            text = listOf("Email address"),
            testTag = "emailField",
        )
        val sameRootNearby = FixThisNode(
            uid = "submit-button",
            composeNodeId = 43,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(36f, 220f, 684f, 292f),
            text = listOf("Submit"),
        )
        val otherRootNearby = FixThisNode(
            uid = "toolbar-title",
            composeNodeId = 44,
            rootIndex = 1,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(36f, 210f, 400f, 260f),
            text = listOf("Profile"),
        )
        val roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(selected, sameRootNearby),
            ),
            SnapshotRootDto(
                rootIndex = 1,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(otherRootNearby),
            ),
        )
        val root = createTempDir(prefix = "fixthis-v2-node-source-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(
                captureRoots = roots,
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt",
                            line = 37,
                            text = listOf("Email address"),
                            testTags = listOf("emailField"),
                            activityNames = listOf("MainActivity"),
                        ),
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/Toolbar.kt",
                            line = 12,
                            text = listOf("Profile"),
                            activityNames = listOf("MainActivity"),
                        ),
                    ),
                ),
            ),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val updated = service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.NODE,
                    nodeUid = selected.uid,
                    bounds = selected.boundsInWindow,
                    comment = "Move the email field",
                ),
            ),
        )

        val item = updated.items.single()
        assertEquals(selected.uid, item.selectedNode?.uid)
        assertEquals(listOf("submit-button"), item.nearbyNodes.map { it.uid })
        assertEquals("sample/src/main/java/io/github/fixthis/sample/screens/FormScreen.kt", item.sourceCandidates.first().file)
        assertEquals(37, item.sourceCandidates.first().line)
    }

    @Test
    fun capturePreviewCachesDecodedSourceIndexForSessionProcess() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-source-index-cache-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "preview-2", "screen-2"),
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)

        service.capturePreview(session.sessionId)
        service.capturePreview(session.sessionId)

        assertEquals(1, bridge.readSourceIndexCount)
    }

    @Test
    fun savingAreaPreviewFeedbackUsesNearestMeaningfulNodesWhenNothingOverlaps() = runBlocking {
        val nearest = FixThisNode(
            uid = "distant-label",
            composeNodeId = 51,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(500f, 900f, 640f, 960f),
            text = listOf("Distant label"),
        )
        val secondNearest = FixThisNode(
            uid = "farther-button",
            composeNodeId = 52,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(540f, 1100f, 680f, 1160f),
            text = listOf("Continue"),
        )
        val roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(secondNearest, nearest),
            ),
        )
        val root = createTempDir(prefix = "fixthis-v2-area-source-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(
                captureRoots = roots,
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/NearCard.kt",
                            line = 18,
                            text = listOf("Distant label"),
                            activityNames = listOf("MainActivity"),
                        ),
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/NearCard.kt",
                            line = 32,
                            text = listOf("Continue"),
                            activityNames = listOf("MainActivity"),
                        ),
                    ),
                ),
            ),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val updated = service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(10f, 10f, 40f, 40f),
                    comment = "Fix this empty corner",
                ),
            ),
        )

        val item = updated.items.single()
        assertEquals(listOf("distant-label", "farther-button"), item.nearbyNodes.map { it.uid })
        assertEquals("sample/src/main/java/io/github/fixthis/sample/screens/NearCard.kt", item.sourceCandidates.first().file)
        assertEquals(18, item.sourceCandidates.first().line)
    }

    @Test
    fun savingAreaPreviewFeedbackUsesOnlyOverlappingEvidenceWhenAnyNodeOverlaps() = runBlocking {
        val overlapping = FixThisNode(
            uid = "overlapping-card",
            composeNodeId = 61,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(120f, 120f, 220f, 220f),
            text = listOf("Overlapping card"),
        )
        val nearbyNonOverlapping = FixThisNode(
            uid = "nearby-label",
            composeNodeId = 62,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(230f, 120f, 360f, 180f),
            text = listOf("Nearby label"),
        )
        val roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(nearbyNonOverlapping, overlapping),
            ),
        )
        val root = createTempDir(prefix = "fixthis-v2-area-overlap-source-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(
                captureRoots = roots,
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/OverlapCard.kt",
                            line = 18,
                            text = listOf("Overlapping card"),
                            activityNames = listOf("MainActivity"),
                        ),
                        SourceIndexEntry(
                            file = "sample/src/main/java/io/github/fixthis/sample/screens/NearbyLabel.kt",
                            line = 42,
                            text = listOf("Nearby label"),
                        ),
                    ),
                ),
            ),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val updated = service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(100f, 100f, 200f, 200f),
                    comment = "Fix this selected area",
                ),
            ),
        )

        val item = updated.items.single()
        assertEquals(listOf("overlapping-card"), item.nearbyNodes.map { it.uid })
        assertEquals(listOf("sample/src/main/java/io/github/fixthis/sample/screens/OverlapCard.kt"), item.sourceCandidates.map { it.file })
    }

    @Test
    fun savingPreviewDoesNotInventSourceCandidatesWhenSourceIndexIsUnavailableMissingEmptyOrUnreadable() = runBlocking {
        val selected = FixThisNode(
            uid = "email-label",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(28f, 77f, 692f, 186f),
            text = listOf("Email address"),
            testTag = "emailField",
        )
        val roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(selected),
            ),
        )

        listOf(
            NoSourceIndexCase("unavailable", false, null, null),
            NoSourceIndexCase("missing", true, null, null),
            NoSourceIndexCase("empty", true, SourceIndex(entries = emptyList()), null),
            NoSourceIndexCase("read-error", true, null, "Source index asset is malformed"),
        ).forEachIndexed { index, sourceIndexCase ->
            val root = createTempDir(prefix = "fixthis-v2-no-source-index-${sourceIndexCase.label}-")
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds("session-${index + 1}", "preview-${index + 1}", "screen-${index + 1}", "item-${index + 1}"),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(
                    captureRoots = roots,
                    sourceIndexAvailable = sourceIndexCase.available,
                    sourceIndex = sourceIndexCase.sourceIndex,
                    sourceIndexReadError = sourceIndexCase.error,
                ),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)
            val preview = service.capturePreview(session.sessionId)

            val updated = service.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.NODE,
                        nodeUid = selected.uid,
                        bounds = selected.boundsInWindow,
                        comment = "Move the email field",
                    ),
                ),
            )

            val item = updated.items.single()
            assertTrue(item.sourceCandidates.isEmpty())
            val markdown = FeedbackQueueFormatter.toMarkdown(updated)
            assertTrue(markdown.contains("No source candidate from current evidence"))
            assertFalse(markdown.contains(".kt:"))
        }
    }

    @Test
    fun capturePreviewDeletesEvictedPreviewCacheDirectories() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-preview-cache-")
        try {
            val store = FeedbackSessionStore(
                clock = { 1_000L },
                idGenerator = sequenceIds(
                    "session-1",
                    "preview-1",
                    "screen-1",
                    "preview-2",
                    "screen-2",
                    "preview-3",
                    "screen-3",
                    "preview-4",
                    "screen-4",
                ),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)

            repeat(4) {
                service.capturePreview(session.sessionId)
            }

            val previewRoot = root.resolve(".fixthis/preview-cache/${session.sessionId}")
            assertFalse(previewRoot.resolve("preview-1").exists())
            assertEquals(
                listOf("preview-2", "preview-3", "preview-4"),
                previewRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingPreviewDeletesPreviewCacheDirectoryAfterPromotingScreenshot() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-preview-save-cleanup-")
        try {
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)
            val preview = service.capturePreview(session.sessionId)
            val previewDirectory = root.resolve(".fixthis/preview-cache/${session.sessionId}/${preview.previewId}")
            assertTrue(previewDirectory.exists())

            val updated = service.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.AREA,
                        bounds = FixThisRect(112f, 426f, 351f, 588f),
                        comment = "Change this visual area",
                    ),
                ),
            )

            assertFalse(previewDirectory.exists())
            val savedPath = updated.screens.single().screenshot?.desktopFullPath.orEmpty()
            assertTrue(savedPath.contains(".fixthis/feedback-sessions/${session.sessionId}/artifacts/screens/screen-1"))
            assertTrue(java.io.File(savedPath).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun previewScreenshotFileRequiresLivePreviewRecordAndDoesNotUseDeletedCache() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-preview-screenshot-")
        try {
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)
            val preview = service.capturePreview(session.sessionId)

            val screenshotFile = service.previewScreenshotFile(session.sessionId, preview.previewId)
            assertTrue(screenshotFile.isFile)
            assertTrue(screenshotFile.absolutePath.contains(".fixthis/preview-cache/${session.sessionId}/${preview.previewId}"))

            service.savePreviewFeedbackItems(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(
                    AnnotationDraftDto(
                        targetType = FeedbackTargetType.AREA,
                        bounds = FixThisRect(112f, 426f, 351f, 588f),
                        comment = "Change this visual area",
                    ),
                ),
            )

            val error = assertFailsWith<FeedbackSessionException> {
                service.previewScreenshotFile(session.sessionId, preview.previewId)
            }
            assertTrue(error.message.orEmpty().contains("PREVIEW_NOT_FOUND"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingSamePreviewTwiceDoesNotPersistDuplicateScreensOrItems() = runBlocking {
        val root = createTempDir(prefix = "fixthis-v2-preview-duplicate-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)
        val item = AnnotationDraftDto(
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(112f, 426f, 351f, 588f),
            comment = "Change this visual area",
        )

        service.savePreviewFeedbackItems(session.sessionId, preview.previewId, listOf(item))
        assertFailsWith<FeedbackSessionException> {
            service.savePreviewFeedbackItems(session.sessionId, preview.previewId, listOf(item))
        }

        val stored = store.getSession(session.sessionId)
        assertEquals(1, stored.screens.size)
        assertEquals(1, stored.items.size)
    }

    @Test
    fun savingPreviewPromotesArtifactsUnderSessionProjectRoot() = runBlocking {
        val sessionRoot = createTempDir(prefix = "fixthis-v2-session-root-")
        val serviceRoot = createTempDir(prefix = "fixthis-v2-service-root-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val session = store.openSession("io.beyondwin.fixthis.sample", sessionRoot.absolutePath)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = serviceRoot.absolutePath,
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val preview = service.capturePreview(session.sessionId)

        val updated = service.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.AREA,
                    bounds = FixThisRect(112f, 426f, 351f, 588f),
                    comment = "Change this visual area",
                ),
            ),
        )

        val expectedPath = FeedbackSessionPaths(sessionRoot)
            .screenArtifactDirectory(session.sessionId, "screen-1")
            .resolve("screen-1-full.png")
            .absolutePath
        val savedPath = updated.screens.single().screenshot?.desktopFullPath.orEmpty()
        assertEquals(expectedPath, savedPath)
    }

    @Test
    fun navigatePropagatesCancellationFromFollowUpCapture() {
        runBlocking {
            val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
            val bridge = FakeFixThisBridge(captureError = CancellationException("capture cancelled"))
            val service = FeedbackSessionService(
                bridge = bridge,
                store = store,
                projectRoot = "/repo",
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null)

            assertFailsWith<CancellationException> {
                service.navigate(
                    sessionId = session.sessionId,
                    request = FeedbackNavigationRequest(action = FeedbackNavigationAction.BACK),
                )
            }
        }
    }

    @Test
    fun addAreaFeedbackStoresItemForScreen() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next)
        val service = FeedbackSessionService(FakeFixThisBridge(), store, "/repo", "io.beyondwin.fixthis.sample")
        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        val item = service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = "Fix spacing",
        )

        assertEquals("item-1", item.itemId)
        assertEquals("Fix spacing", item.comment)
    }

    @Test
    fun addAreaFeedbackWithBlankCommentStaysOpen() = runBlocking {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next)
        val service = FeedbackSessionService(FakeFixThisBridge(), store, "/repo", "io.beyondwin.fixthis.sample")
        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        val item = service.addAreaFeedback(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            bounds = FixThisRect(1f, 2f, 3f, 4f),
            comment = " ",
        )

        assertEquals(AnnotationStatusDto.OPEN, item.status)
    }

    @Test
    fun addSelectedNodeFeedbackStoresSelectedNode() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val item = service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            targetType = FeedbackTargetType.NODE,
            bounds = node.boundsInWindow,
            nodeUid = node.uid,
            comment = "Button copy is unclear",
        )

        assertEquals(AnnotationTargetDto.Node(node.uid, node.boundsInWindow), item.target)
        assertEquals(node, item.selectedNode)
        assertEquals(FeedbackDelivery.DRAFT, item.delivery)
        assertEquals(1, item.sequenceNumber)
    }

    @Test
    fun addSelectedNodeFeedbackRejectsNodeBoundsOutsideScreenshot() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(-1f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = "screen-1",
                targetType = FeedbackTargetType.NODE,
                bounds = FixThisRect(10f, 20f, 110f, 70f),
                nodeUid = node.uid,
                comment = "Button copy is unclear",
            )
        }

        assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
    }

    @Test
    fun addSelectedNodeFeedbackStoresNodeBoundsWhenRequestBoundsDiffer() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:unmerged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.UNMERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), unmergedNodes = listOf(node))),
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val item = service.addFeedbackItem(
            sessionId = session.sessionId,
            screenId = screen.screenId,
            targetType = FeedbackTargetType.NODE,
            bounds = FixThisRect(200f, 300f, 260f, 340f),
            nodeUid = node.uid,
            comment = "Button copy is unclear",
        )

        assertEquals(AnnotationTargetDto.Node(node.uid, node.boundsInWindow), item.target)
        assertEquals(node, item.selectedNode)
    }

    @Test
    fun addCustomAreaFeedbackRejectsBoundsOutsideScreenshot() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.addFeedbackItem(
                sessionId = session.sessionId,
                screenId = "screen-1",
                targetType = FeedbackTargetType.AREA,
                bounds = FixThisRect(-1f, 0f, 10f, 10f),
                nodeUid = null,
                comment = "Bad bounds",
            )
        }

        assertTrue(error.message.orEmpty().contains("Selection bounds must be inside the screenshot"))
    }

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: SnapshotDto): SnapshotDto =
        javaClass.getDeclaredField("store").let { field ->
            field.isAccessible = true
            (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
        }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private data class NoSourceIndexCase(
        val label: String,
        val available: Boolean,
        val sourceIndex: SourceIndex?,
        val error: String?,
    )

    private fun sequenceClock(vararg values: Long): () -> Long {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: values.last() }
    }

    private fun sequenceIds(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirstOrNull() ?: error("No more ids configured") }
    }
}
