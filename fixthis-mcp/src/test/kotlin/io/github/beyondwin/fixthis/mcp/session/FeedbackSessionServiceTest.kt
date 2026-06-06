package io.github.beyondwin.fixthis.mcp.session

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.readiness.FirstRunReadinessState
import io.github.beyondwin.fixthis.compose.core.model.EvidenceQuality
import io.github.beyondwin.fixthis.compose.core.model.FixThisNode
import io.github.beyondwin.fixthis.compose.core.model.FixThisRect
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintConfidence
import io.github.beyondwin.fixthis.compose.core.model.IdentityHintSource
import io.github.beyondwin.fixthis.compose.core.model.OccurrenceSignatureType
import io.github.beyondwin.fixthis.compose.core.model.TreeKind
import io.github.beyondwin.fixthis.compose.core.source.SourceIndex
import io.github.beyondwin.fixthis.compose.core.source.SourceIndexEntry
import io.github.beyondwin.fixthis.mcp.console.AnnotationDraftDto
import io.github.beyondwin.fixthis.mcp.console.ConsoleConnectionState
import io.github.beyondwin.fixthis.mcp.console.FeedbackTargetType
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.AnnotationTargetDto
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationAction
import io.github.beyondwin.fixthis.mcp.session.dto.FeedbackNavigationRequest
import io.github.beyondwin.fixthis.mcp.session.dto.SessionDto
import io.github.beyondwin.fixthis.mcp.session.dto.SessionStatusDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotRootDto
import io.github.beyondwin.fixthis.mcp.session.dto.SnapshotScreenshotDto
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackDelivery
import io.github.beyondwin.fixthis.mcp.session.handoff.FeedbackQueueFormatter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogReader
import io.github.beyondwin.fixthis.mcp.session.lifecycle.event.eventlog.EventLogWriter
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionException
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPaths
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionPersistence
import io.github.beyondwin.fixthis.mcp.session.lifecycle.store.FeedbackSessionStore
import io.github.beyondwin.fixthis.mcp.session.preview.PreviewCacheRetentionPolicy
import io.github.beyondwin.fixthis.mcp.session.preview.ScreenFingerprintMismatch
import io.github.beyondwin.fixthis.mcp.tools.FixThisBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PACKAGE_NAME = "io.github.beyondwin.fixthis.sample"

class FeedbackSessionServiceTest {
    @Test
    fun connectionStatusIsReadyWhenDeviceAndHeartbeatSucceed() = runBlocking {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.connectionStatus()

        assertEquals(ConsoleConnectionState.READY, status.state)
        assertEquals("Ready", status.headline)
        assertEquals(true, status.canCapture)
        assertEquals(true, status.canNavigate)
    }

    @Test
    fun connectionStatusAsksToChooseDeviceWhenMultipleReadyDevicesExist() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(
                AdbDevice("device-1", "device", model = "Pixel_8"),
                AdbDevice("device-2", "device", model = "SM_G986N"),
            ),
        )
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.connectionStatus()

        assertEquals(ConsoleConnectionState.CHOOSE_DEVICE, status.state)
        assertEquals("Choose a device", status.headline)
        assertEquals(false, status.canCapture)
    }

    @Test
    fun connectionStatusAsksToOpenAppWhenDeviceIsReadyButHeartbeatFails() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("Bridge closed before sending a response"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.connectionStatus()

        assertEquals(ConsoleConnectionState.OPEN_APP, status.state)
        assertEquals("Open the app", status.headline)
        assertTrue(status.details.rawError.orEmpty().contains("Bridge closed before sending a response"))
    }

    @Test
    fun connectionStatusPropagatesCancellationFromHeartbeat() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = CancellationException("connection check cancelled"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val error = assertFailsWith<CancellationException> {
            service.connectionStatus()
        }
        assertEquals("connection check cancelled", error.message)
    }

    @Test
    fun connectionStatusReportsUnsupportedBuildWhenHeartbeatFailsWithRunAsPermissionError() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("run-as: package not debuggable: permission denied"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.connectionStatus()

        assertEquals(ConsoleConnectionState.UNSUPPORTED_BUILD, status.state)
        assertEquals("This build cannot connect", status.headline)
    }

    @Test
    fun connectionStatusMapsDeviceEnumerationFailureToDesktopEnvironmentBlocker() = runBlocking {
        val bridge = FakeFixThisBridge(devicesError = RuntimeException("adb server is unavailable"))
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.connectionStatus()

        assertEquals(ConsoleConnectionState.CHECK_PHONE, status.state)
        assertEquals("ADB is not available", status.headline)
        assertEquals(FirstRunReadinessState.ENV_BLOCKER, status.readiness?.state)
        assertTrue(status.details.rawError.orEmpty().contains("adb server is unavailable"))
        assertEquals("unknown", status.details.deviceState)
        assertEquals("not checked", status.details.bridgeState)
    }

    @Test
    fun launchAppForCurrentSessionDelegatesToBridgeAndReturnsStartingState() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(listOf("io.github.beyondwin.fixthis.sample"), bridge.launchedPackages)
        assertEquals(ConsoleConnectionState.STARTING, status.state)
        assertEquals("starting", status.details.bridgeState)
        assertEquals(null, status.details.rawError)
    }

    @Test
    fun launchAppForCurrentSessionTurnsWelcomeIntoStartingState() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(listOf("io.github.beyondwin.fixthis.sample"), bridge.launchedPackages)
        assertEquals(ConsoleConnectionState.STARTING, status.state)
        assertEquals("Opening app", status.headline)
        assertEquals("We're opening the app and connecting.", status.message)
        assertEquals(null, status.primaryAction)
    }

    @Test
    fun launchAppForCurrentSessionScopesWelcomeLaunchToSingleReadyDevice() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(
                AdbDevice("device-1", "device", model = "Pixel_8"),
                AdbDevice("device-2", "offline", model = "Offline"),
            ),
            heartbeatError = RuntimeException("not ready yet"),
        )
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(ConsoleConnectionState.STARTING, status.state)
        assertEquals(listOf("io.github.beyondwin.fixthis.sample"), bridge.launchedPackages)
        assertEquals("device-1", bridge.selectedDeviceSerial())
    }

    @Test
    fun launchAppForCurrentSessionPreservesUnsupportedBuildStatus() = runBlocking {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("run-as: package not debuggable"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(emptyList(), bridge.launchedPackages)
        assertEquals(ConsoleConnectionState.UNSUPPORTED_BUILD, status.state)
        assertEquals("This build cannot connect", status.headline)
    }

    @Test
    fun launchAppForCurrentSessionPreservesCheckPhoneStatus() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(AdbDevice("device-1", "offline", model = "Pixel_8")),
        )
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(emptyList(), bridge.launchedPackages)
        assertEquals(ConsoleConnectionState.CHECK_PHONE, status.state)
        assertEquals("Check your phone", status.headline)
    }

    @Test
    fun launchAppForCurrentSessionPreservesChooseDeviceStatus() = runBlocking {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(
                AdbDevice("device-1", "device", model = "Pixel_8"),
                AdbDevice("device-2", "device", model = "SM_G986N"),
            ),
        )
        val service = serviceWithBridge(bridge)
        service.currentSession()

        val status = service.launchAppForCurrentSession()

        assertEquals(emptyList(), bridge.launchedPackages)
        assertEquals(ConsoleConnectionState.CHOOSE_DEVICE, status.state)
        assertEquals("Choose a device", status.headline)
    }

    @Test
    fun openSessionReusesCurrentSessionForSamePackageAndProject() {
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )

        val session = service.openSession("  ")

        assertEquals("io.github.beyondwin.fixthis.sample", session.packageName)
        assertEquals(listOf<String?>("io.github.beyondwin.fixthis.sample"), bridge.resolvedOverrides)
    }

    @Test
    fun serviceOpensExactPersistedSession() {
        val root = tempDir(prefix = "fixthis-v2-service-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 100L })
        val store = FeedbackSessionStore(
            clock = { 100L },
            idGenerator = FakeIds("session-1").next,
            persistence = persistence,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.github.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val created = service.openSession(packageNameOverride = null, newSession = true)
        val freshStore = FeedbackSessionStore(clock = { 200L }, persistence = persistence)
        val freshService = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.github.beyondwin.fixthis.other"),
            store = freshStore,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.other",
        )

        val reopened = freshService.openSession(packageNameOverride = null, sessionId = created.sessionId)

        assertEquals(created.sessionId, reopened.sessionId)
        assertEquals(created.sessionId, freshStore.currentSession()?.sessionId)
    }

    @Test
    fun serviceListsSessionsForPackage() {
        val root = tempDir(prefix = "fixthis-v2-list-")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(packageName = "io.github.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        service.openSession(packageNameOverride = null, newSession = true)

        val sessions = service.listSessions(packageNameOverride = "io.github.beyondwin.fixthis.sample")

        assertEquals(listOf("session-1"), sessions.sessions.map { it.sessionId })
    }

    @Test
    fun serviceAutoResumesLatestNonClosedPersistedSessionForPackageAndProject() {
        val root = tempDir(prefix = "fixthis-v2-auto-resume-")
        val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(root), clock = { 500L })
        persistence.save(
            SessionDto(
                sessionId = "sample-old",
                packageName = "io.github.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 100L,
                updatedAtEpochMillis = 100L,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "sample-closed",
                packageName = "io.github.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 200L,
                updatedAtEpochMillis = 400L,
                status = SessionStatusDto.CLOSED,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "sample-latest",
                packageName = "io.github.beyondwin.fixthis.sample",
                projectRoot = root.absolutePath,
                createdAtEpochMillis = 300L,
                updatedAtEpochMillis = 300L,
            ),
        )
        persistence.save(
            SessionDto(
                sessionId = "other-current",
                packageName = "io.github.beyondwin.fixthis.other",
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
            bridge = FakeFixThisBridge(packageName = "io.github.beyondwin.fixthis.sample"),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )

        val session = service.openSession(null)
        val screen = service.captureScreen(session.sessionId)

        assertEquals("screen-1", screen.screenId)
        assertEquals("MainActivity", screen.displayName)
        assertEquals(1, store.getSession(session.sessionId).screens.size)
    }

    @Test
    fun captureUsesSessionOwnedArtifactPath() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-artifacts-")
        val bridge = FakeFixThisBridge(packageName = "io.github.beyondwin.fixthis.sample")
        val store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next)
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-preview-save-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1", "item-2"),
        )
        val service = FeedbackSessionService(bridge = bridge, store = store, projectRoot = root.absolutePath)
        val session = service.openSession("io.github.beyondwin.fixthis.sample", newSession = true)

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
            frozenFingerprint = "preview-fingerprint",
            currentFingerprint = "preview-fingerprint",
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
    fun liveSaveUsesCachedPreviewFingerprintWhenClientOmitsFrozenFingerprint() = runBlocking {
        val root = tempDir(prefix = "fixthis-server-fingerprint-")
        val bridge = FakeFixThisBridge(
            snapshotMutator = { callIndex, json ->
                if (callIndex == 1) json.withFingerprint("frozen-server") else json.withFingerprint("current-live")
            },
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val error = assertFailsWith<ScreenFingerprintMismatch> {
            service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                PreviewFeedbackLiveSaveRequest(
                    sessionId = session.sessionId,
                    previewId = preview.previewId,
                    items = listOf(validAreaDraft()),
                    fallbackScreen = preview.screen,
                    fingerprintCheck = PreviewFeedbackFingerprintCheck(
                        frozenFingerprint = null,
                        forceMismatchOverride = false,
                    ),
                ),
            )
        }

        assertEquals("frozen-server", error.frozenFingerprint)
        assertEquals("current-live", error.currentFingerprint)
    }

    @Test
    fun liveSaveIgnoresClientFrozenFingerprintThatDiffersFromCachedPreview() = runBlocking {
        val root = tempDir(prefix = "fixthis-server-fingerprint-tamper-")
        val bridge = FakeFixThisBridge(
            snapshotMutator = { callIndex, json ->
                if (callIndex == 1) json.withFingerprint("frozen-server") else json.withFingerprint("current-live")
            },
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val error = assertFailsWith<ScreenFingerprintMismatch> {
            service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                PreviewFeedbackLiveSaveRequest(
                    sessionId = session.sessionId,
                    previewId = preview.previewId,
                    items = listOf(validAreaDraft()),
                    fallbackScreen = preview.screen,
                    fingerprintCheck = PreviewFeedbackFingerprintCheck(
                        frozenFingerprint = "current-live",
                        forceMismatchOverride = false,
                    ),
                ),
            )
        }

        assertEquals("frozen-server", error.frozenFingerprint)
        assertEquals("current-live", error.currentFingerprint)
    }

    @Test
    fun liveForceSavePersistsServerFingerprintSourceAndClientMismatchMetadata() = runBlocking {
        val root = tempDir(prefix = "fixthis-server-fingerprint-metadata-")
        val eventRoot = File(root, "events")
        val bridge = FakeFixThisBridge(
            snapshotMutator = { callIndex, json ->
                if (callIndex == 1) json.withFingerprint("frozen-server") else json.withFingerprint("current-live")
            },
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1", "event-1"),
                eventLogWriterProvider = { sessionId -> EventLogWriter(File(eventRoot, "$sessionId/events")) },
            ),
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
            PreviewFeedbackLiveSaveRequest(
                sessionId = session.sessionId,
                previewId = preview.previewId,
                items = listOf(validAreaDraft()),
                fallbackScreen = preview.screen,
                fingerprintCheck = PreviewFeedbackFingerprintCheck(
                    frozenFingerprint = "current-live",
                    forceMismatchOverride = true,
                ),
            ),
        )

        val event = EventLogReader(File(eventRoot, "${session.sessionId}/events"))
            .readAll()
            .single { it.type == "addScreenWithItems" }
        assertEquals(true, event.payload["forceMismatchOverride"]?.jsonPrimitive?.boolean)
        assertEquals("previewCache", event.payload["frozenFingerprintSource"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, event.payload["clientFrozenFingerprintMismatched"]?.jsonPrimitive?.boolean)
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
        val root = tempDir(prefix = "fixthis-v2-node-source-")
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
    fun savingNodePreviewFeedbackBuildsStableTargetEvidenceFromCapturedMergedNodes() = runBlocking {
        val selected = FixThisNode(
            uid = "pay-button-2",
            composeNodeId = 42,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(36f, 220f, 684f, 292f),
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        )
        val earlierOccurrence = FixThisNode(
            uid = "pay-button-1",
            composeNodeId = 41,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(36f, 120f, 684f, 192f),
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        )
        val sourceFile = "sample/src/main/java/io/github/fixthis/sample/components/AppPrimaryButton.kt"
        val roots = listOf(
            SnapshotRootDto(
                rootIndex = 0,
                boundsInWindow = FixThisRect(0f, 0f, 720f, 1600f),
                mergedNodes = listOf(selected, earlierOccurrence),
            ),
        )
        val root = tempDir(prefix = "fixthis-v2-target-evidence-")
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
                            file = sourceFile,
                            line = 42,
                            text = listOf("Pay now"),
                            testTags = listOf("comp:AppPrimaryButton:primary"),
                            roles = listOf("Button"),
                            activityNames = listOf("MainActivity"),
                        ),
                    ),
                ),
            ),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
                    comment = "Make the primary button clearer",
                ),
            ),
        )

        val evidence = updated.items.single().targetEvidence
        assertEquals("AppPrimaryButton", evidence?.identityHint?.composableNameHint)
        assertEquals("primary", evidence?.identityHint?.variantHint)
        assertEquals(IdentityHintSource.TEST_TAG_CONVENTION, evidence?.identityHint?.source)
        assertEquals(IdentityHintConfidence.HIGH, evidence?.identityHint?.confidence)
        assertEquals(OccurrenceSignatureType.IDENTITY_HINT, evidence?.occurrence?.signature?.type)
        assertEquals("AppPrimaryButton:primary", evidence?.occurrence?.signature?.value)
        assertEquals(2, evidence?.occurrence?.count)
        assertEquals(2, evidence?.occurrence?.selectedOrdinal)
        assertEquals(sourceFile, evidence?.sourceInterpretation?.topCandidate?.file)
        assertEquals(EvidenceQuality.STRUCTURED, evidence?.evidenceQuality)
        assertEquals(listOf("full"), evidence?.screenshotKinds)
    }

    @Test
    fun capturePreviewCachesDecodedSourceIndexForSessionProcess() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-source-index-cache-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "preview-2", "screen-2"),
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-area-source-")
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-area-overlap-source-")
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
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
            val root = tempDir(prefix = "fixthis-v2-no-source-index-${sourceIndexCase.label}-")
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds(
                    "session-${index + 1}",
                    "preview-${index + 1}",
                    "screen-${index + 1}",
                    "item-${index + 1}",
                ),
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
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
            assertTrue(
                markdown.contains("No source candidate from current evidence") ||
                    markdown.contains("No source candidate; edit-surface hints:"),
                "expected empty-source hint header, got:\n$markdown",
            )
            if (item.editSurfaceCandidates.isEmpty()) {
                assertFalse(markdown.contains(".kt:"))
            }
        }
    }

    @Test
    fun capturePreviewDeletesOldUnsavedPreviewDirectoriesAfterRetentionLimit() = runBlocking {
        val root = tempDir(prefix = "fixthis-preview-retention-")
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
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
                previewCacheRetentionPolicy = PreviewCacheRetentionPolicy(
                    maxDirectoriesPerSession = 2,
                    minAgeMillis = 0L,
                    clock = { 2_000L },
                ),
            )
            val session = service.openSession(null, newSession = true)

            repeat(4) { service.capturePreview(session.sessionId) }

            val previewRoot = root.resolve(".fixthis/preview-cache/${session.sessionId}")
            val names = previewRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
            assertEquals(listOf("preview-3", "preview-4"), names)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun capturePreviewRetainsEvictedPreviewCacheDirectoriesForLateScreenshotRequests() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-preview-cache-")
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
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
            )
            val session = service.openSession(null, newSession = true)

            repeat(4) {
                service.capturePreview(session.sessionId)
            }

            val previewRoot = root.resolve(".fixthis/preview-cache/${session.sessionId}")
            assertTrue(previewRoot.resolve("preview-1").exists())
            assertEquals(
                listOf("preview-1", "preview-2", "preview-3", "preview-4"),
                previewRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun savingPreviewDeletesPreviewCacheDirectoryAfterPromotingScreenshot() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-preview-save-cleanup-")
        try {
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-preview-screenshot-")
        try {
            val store = FeedbackSessionStore(
                clock = sequenceClock(1_000L, 2_000L),
                idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
            )
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = store,
                projectRoot = root.absolutePath,
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val root = tempDir(prefix = "fixthis-v2-preview-duplicate-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
    fun invalidCachedPreviewFeedbackTargetFailsBeforeLiveFingerprintRecapture() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-preview-invalid-preflight-")
        val bridge = FakeFixThisBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1"),
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)

        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                    PreviewFeedbackLiveSaveRequest(
                        sessionId = session.sessionId,
                        previewId = preview.previewId,
                        items = listOf(
                            AnnotationDraftDto(
                                targetType = FeedbackTargetType.NODE,
                                bounds = FixThisRect(10f, 10f, 40f, 40f),
                                nodeUid = "missing-node",
                                comment = "Missing node",
                            ),
                        ),
                    ),
                )
            }
        }

        assertTrue(error.message.orEmpty().contains("Selected node does not exist on preview: missing-node"))
        assertEquals(1, bridge.captureCount)
    }

    @Test
    fun concurrentPreviewSaveReservesBeforeLiveFingerprintRecapture() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-preview-concurrent-reserve-")
        val bridge = SecondCaptureBlockingBridge()
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val service = FeedbackSessionService(
            bridge = bridge,
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val preview = service.capturePreview(session.sessionId)
        val item = AnnotationDraftDto(
            targetType = FeedbackTargetType.AREA,
            bounds = FixThisRect(112f, 426f, 351f, 588f),
            comment = "Change this visual area",
        )
        val firstSaveError = AtomicReference<Throwable?>()

        val firstSave = thread {
            try {
                runBlocking {
                    service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                        PreviewFeedbackLiveSaveRequest(
                            sessionId = session.sessionId,
                            previewId = preview.previewId,
                            items = listOf(item),
                        ),
                    )
                }
            } catch (error: Throwable) {
                firstSaveError.set(error)
            }
        }
        assertTrue(bridge.recaptureStarted.await(5, TimeUnit.SECONDS))

        val secondSaveError = assertFailsWith<FeedbackSessionException> {
            runBlocking {
                service.savePreviewFeedbackItemsWithLiveFingerprintMetadata(
                    PreviewFeedbackLiveSaveRequest(
                        sessionId = session.sessionId,
                        previewId = preview.previewId,
                        items = listOf(item),
                    ),
                )
            }
        }

        assertTrue(secondSaveError.message.orEmpty().contains("PREVIEW_SAVE_IN_PROGRESS"))
        assertEquals(2, bridge.captureCount)
        bridge.releaseRecapture.countDown()
        firstSave.join(5_000L)
        firstSaveError.get()?.let { throw it }
        assertEquals(1, store.getSession(session.sessionId).items.size)
    }

    @Test
    fun savingPreviewCanRecoverFromMissingMemoryCacheUsingFrozenScreenSnapshot() = runBlocking {
        val root = tempDir(prefix = "fixthis-v2-preview-recover-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val firstService = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = firstService.openSession(null, newSession = true)
        val preview = firstService.capturePreview(session.sessionId)
        val restartedService = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = root.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )

        val updated = restartedService.savePreviewFeedbackItems(
            sessionId = session.sessionId,
            previewId = preview.previewId,
            items = listOf(
                AnnotationDraftDto(
                    targetType = FeedbackTargetType.NODE,
                    nodeUid = "email-label",
                    bounds = FixThisRect(28f, 77f, 692f, 186f),
                    comment = "Rename this label",
                ),
            ),
            fallbackScreen = preview.screen,
        )

        assertEquals(1, updated.screens.size)
        assertEquals(1, updated.items.size)
        assertEquals("email-label", updated.items.single().selectedNode?.uid)
        assertTrue(updated.items.single().sourceCandidates.isNotEmpty())
    }

    @Test
    fun savingPreviewPromotesArtifactsUnderSessionProjectRoot() = runBlocking {
        val sessionRoot = tempDir(prefix = "fixthis-v2-session-root-")
        val serviceRoot = tempDir(prefix = "fixthis-v2-service-root-")
        val store = FeedbackSessionStore(
            clock = sequenceClock(1_000L, 2_000L),
            idGenerator = sequenceIds("session-1", "preview-1", "screen-1", "item-1"),
        )
        val session = store.openSession("io.github.beyondwin.fixthis.sample", sessionRoot.absolutePath)
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = serviceRoot.absolutePath,
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
                defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
        val service = FeedbackSessionService(FakeFixThisBridge(), store, "/repo", "io.github.beyondwin.fixthis.sample")
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
        val service = FeedbackSessionService(FakeFixThisBridge(), store, "/repo", "io.github.beyondwin.fixthis.sample")
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
    fun addSelectedNodeFeedbackStoresSelectedNode() = runBlocking {
        val sourceFile = "sample/src/main/java/io/github/fixthis/sample/components/AppPrimaryButton.kt"
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(
                sourceIndex = SourceIndex(
                    entries = listOf(
                        SourceIndexEntry(
                            file = sourceFile,
                            line = 42,
                            text = listOf("Pay now"),
                            testTags = listOf("comp:AppPrimaryButton:primary"),
                            roles = listOf("Button"),
                            activityNames = listOf("Checkout"),
                        ),
                    ),
                ),
            ),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = FixThisNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = FixThisRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
            role = "Button",
            testTag = "comp:AppPrimaryButton:primary",
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                activityName = "Checkout",
                displayName = "Checkout",
                roots = listOf(SnapshotRootDto(0, FixThisRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                sourceIndexAvailable = true,
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
        assertEquals(sourceFile, item.sourceCandidates.first().file)
        assertEquals("AppPrimaryButton", item.targetEvidence?.identityHint?.composableNameHint)
        assertEquals(sourceFile, item.targetEvidence?.sourceInterpretation?.topCandidate?.file)
    }

    @Test
    fun addSelectedNodeFeedbackRejectsNodeBoundsOutsideScreenshot() = runBlocking {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
    fun addSelectedNodeFeedbackStoresNodeBoundsWhenRequestBoundsDiffer() = runBlocking {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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
    fun addCustomAreaFeedbackRejectsBoundsOutsideScreenshot() = runBlocking {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.beyondwin.fixthis.sample",
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

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: SnapshotDto): SnapshotDto = javaClass.getDeclaredField("store").let { field ->
        field.isAccessible = true
        (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
    }

    private fun serviceWithBridge(bridge: FakeFixThisBridge): FeedbackSessionService = FeedbackSessionService(
        bridge = bridge,
        store = FeedbackSessionStore(),
        projectRoot = tempDir(prefix = "fixthis-connection-service-").absolutePath,
        defaultPackageName = "io.github.beyondwin.fixthis.sample",
    )

    private fun JsonObject.withFingerprint(value: String): JsonObject = buildJsonObject {
        this@withFingerprint.forEach { (key, element) -> put(key, element) }
        put("fingerprint", value)
    }

    private fun validAreaDraft(): AnnotationDraftDto = AnnotationDraftDto(
        targetType = FeedbackTargetType.AREA,
        bounds = FixThisRect(112f, 426f, 351f, 588f),
        comment = "Change this visual area",
    )

    private class SecondCaptureBlockingBridge : FixThisBridge {
        val recaptureStarted = CountDownLatch(1)
        val releaseRecapture = CountDownLatch(1)
        var captureCount: Int = 0
            private set

        override fun resolvePackageName(packageOverride: String?): String = packageOverride ?: PACKAGE_NAME

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(
            packageName: String,
            expectedText: String,
            role: String?,
        ): JsonObject = JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            captureCount += 1
            if (captureCount > 1) {
                recaptureStarted.countDown()
                releaseRecapture.await(5, TimeUnit.SECONDS)
            }
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put(
                "inspection",
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("roots", JsonArray(emptyList()))
                    put("errors", JsonArray(emptyList()))
                },
            )
            put(
                "screenshot",
                buildJsonObject {
                    put("desktopFullPath", artifact.absolutePath)
                },
            )
        }
    }

    private fun tempDir(prefix: String): File = kotlin.io.path.createTempDirectory(prefix = prefix).toFile().also { it.deleteOnExit() }

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
