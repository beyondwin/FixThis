package io.github.beyondwin.fixthis.mcp.console

import io.github.beyondwin.fixthis.cli.AdbDevice
import io.github.beyondwin.fixthis.cli.fixThisJson
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.github.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.github.beyondwin.fixthis.mcp.fixtures.DeviceListBridge
import io.github.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.github.beyondwin.fixthis.mcp.fixtures.assertDoesNotClearDraftOrPreview
import io.github.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.github.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.github.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CONNECTION_SAMPLE_PACKAGE = "io.github.beyondwin.fixthis.sample"

class ConsoleConnectionRoutesTest {
    @Test
    fun consoleHtmlShowsReadableDeviceConnectionStates() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("const DeviceUiState = {"))
        assertTrue(html.contains("NONE: 'none'"))
        assertTrue(html.contains("CONNECTING: 'connecting'"))
        assertTrue(html.contains("CONNECTED: 'connected'"))
        assertTrue(html.contains("UNAVAILABLE: 'unavailable'"))
        assertTrue(html.contains("DeviceStateCopy = {"))
        assertTrue(html.contains("No device"))
        assertTrue(html.contains("Connecting"))
        assertTrue(html.contains("Connected"))
        assertTrue(html.contains("Unavailable"))
        assertTrue(html.contains("data-connection-state=\"none\""))
        assertTrue(html.contains("deviceControl.dataset.connectionState = uiState;"))
        assertTrue(html.contains("deviceConnectionState.textContent = decorateConnectionLabel(baseLabel, reason);"))
        // Connection state initial shape lives in connectionFsm.js (createInitialConnectionState).
        assertTrue(html.contains("createInitialConnectionState"))
        assertTrue(html.contains("hasEverConnected: false"))
        assertTrue(html.contains("lastReadyAt: null"))
        assertTrue(html.contains("launchInFlight: false"))
        assertTrue(html.contains("state.devices = devices;"))
        assertTrue(
            html.contains("setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));"),
        )
    }

    @Test
    fun consoleHtmlRefreshesConnectionStatusWhileDeviceIsSelected() {
        val html = ConsoleSourceFixtures.readAll()
        val startHeartbeatPolling = javascriptFunctionBody(html, "startHeartbeatPolling")
        val stopHeartbeatPolling = javascriptFunctionBody(html, "stopHeartbeatPolling")
        val sendBridgeHeartbeat = javascriptFunctionBody(html, "sendBridgeHeartbeat")

        assertTrue(html.contains("let heartbeatTimer = null;"))
        assertTrue(html.contains("let heartbeatPolling = false;"))
        assertTrue(sendBridgeHeartbeat.contains("refreshConnection()"))
        assertTrue(sendBridgeHeartbeat.contains("if (!state.session || !state.selectedDeviceSerial) return;"))
        assertTrue(startHeartbeatPolling.contains("sendBridgeHeartbeat()"))
        assertTrue(startHeartbeatPolling.contains("heartbeatPolling = true"))
        assertTrue(startHeartbeatPolling.contains("scheduleNextHeartbeat"))
        assertTrue(stopHeartbeatPolling.contains("heartbeatPolling = false"))
        assertTrue(stopHeartbeatPolling.contains("clearTimeout(heartbeatTimer)"))
        assertTrue(html.contains("unresponsiveTracker.nextBackoffMs()"))
        assertTrue(html.contains("startHeartbeatPolling();"))
        assertTrue(html.contains("stopHeartbeatPolling();"))
    }

    @Test
    fun consoleHasSimpleConnectionRecoveryCard() {
        val html = ConsoleSourceFixtures.readAll()
        val refreshConnectionBody = javascriptFunctionBody(html, "refreshConnection")
        val renderConnectionBody = javascriptFunctionBody(html, "renderConnection")
        val launchAppBody = javascriptFunctionBody(html, "launchApp")

        assertTrue(html.contains("id=\"connectionCard\""))
        assertTrue(html.contains("id=\"connectionHeadline\""))
        assertTrue(html.contains("id=\"connectionMessage\""))
        assertTrue(html.contains("id=\"connectionPrimaryAction\""))
        assertTrue(html.contains("id=\"connectionDetails\""))
        assertTrue(html.contains("id=\"connectionDetailsBody\""))
        assertTrue(html.contains("id=\"previewStaleBadge\""))
        assertTrue(html.contains("/api/connection"))
        assertTrue(html.contains("/api/app/launch"))
        assertTrue(refreshConnectionBody.contains("requestJson('/api/connection'"))
        assertTrue(renderConnectionBody.contains("connectionCard.dataset.connectionState"))
        assertTrue(renderConnectionBody.contains("const connectionState = connectionUseCases.getState();"))
        assertTrue(renderConnectionBody.contains("connectionPrimaryAction.disabled = connectionState.launchInFlight;"))
        assertFalse(
            renderConnectionBody.contains(
                "connectionPrimaryAction.disabled = state.connection.launchInFlight || viewState === 'starting';",
            ),
        )
        assertTrue(launchAppBody.contains("requestJson('/api/app/launch'"))
    }

    @Test
    fun connectionDropPreservesDraftWorkAndMarksPreviewStale() {
        val html = ConsoleSourceFixtures.readAll()
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

        assertTrue(applyConnectionBody.contains("draftItemList()"))
        assertTrue(applyConnectionBody.contains("markPreviewStale"))
        assertTrue(applyConnectionBody.contains("stopLivePreviewPolling"))
        assertTrue(applyConnectionBody.contains("startLivePreviewPolling"))
        // hasEverConnected is now written by the connection FSM's STATUS_RECEIVED action
        // when the incoming status reports ready. Verify the dispatch path exists.
        assertTrue(applyConnectionBody.contains("connectionUseCases.setStatus("))
        assertDoesNotClearDraftOrPreview("applyConnectionStatus", applyConnectionBody)
    }

    @Test
    fun previewFailureMarksConnectionPausedWithoutClearingDrafts() {
        val html = ConsoleSourceFixtures.readAll()
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")
        val refreshConnectionBody = javascriptFunctionBody(html, "refreshConnection")
        val friendlyErrorMessageBody = javascriptFunctionBody(html, "friendlyErrorMessage")
        val showErrorBody = javascriptFunctionBody(html, "showError")
        val sendBridgeHeartbeatBody = javascriptFunctionBody(html, "sendBridgeHeartbeat")
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

        assertTrue(refreshPreviewBody.contains("markPreviewStale(true)"))
        assertTrue(refreshPreviewBody.contains("refreshConnection({ preservePreviewStale: true }).catch"))
        assertTrue(refreshConnectionBody.contains("applyConnectionStatus(status, options);"))
        assertTrue(applyConnectionBody.contains("const connectionOptions = options || {};"))
        assertTrue(
            applyConnectionBody.contains(
                "if (!connectionOptions.preservePreviewStale " +
                    "&& !state.preview?.obstructedBySystemUi) markPreviewStale(false);",
            ),
        )
        assertTrue(friendlyErrorMessageBody.contains("Connection paused. Your work is saved."))
        val friendlyReturnIndex = friendlyErrorMessageBody.indexOf("return 'Connection paused. Your work is saved.';")
        assertTrue(friendlyReturnIndex >= 0)
        assertTrue(
            friendlyErrorMessageBody.indexOf("Bridge closed before sending a response") in 0 until friendlyReturnIndex,
            "Bridge closed failures should map to the saved-work message",
        )
        assertTrue(
            friendlyErrorMessageBody.indexOf("Could not connect to FixThis bridge") in 0 until friendlyReturnIndex,
            "Bridge connection failures should map to the saved-work message",
        )
        assertTrue(
            friendlyErrorMessageBody.indexOf("lower.includes('bridge')") in 0 until friendlyReturnIndex &&
                friendlyErrorMessageBody.indexOf("lower.includes('timed out')") in 0 until friendlyReturnIndex,
            "Only bridge-specific timeout failures should map to the saved-work message",
        )
        assertFalse(
            friendlyErrorMessageBody.contains("raw.includes('timed out')"),
            "Unrelated timeout failures should keep their original error text",
        )
        assertTrue(friendlyErrorMessageBody.contains("DEVICE_NOT_AVAILABLE"))
        assertTrue(friendlyErrorMessageBody.contains("Check your phone, then try again."))
        assertTrue(showErrorBody.contains("friendlyErrorMessage"))
        assertTrue(html.contains("draftPinsState = [];"))
        assertDoesNotClearDraftOrPreview("refreshPreview", refreshPreviewBody)
        assertDoesNotClearDraftOrPreview("friendlyErrorMessage", friendlyErrorMessageBody)
        assertDoesNotClearDraftOrPreview("showError", showErrorBody)
        assertDoesNotClearDraftOrPreview("sendBridgeHeartbeat", sendBridgeHeartbeatBody)
        assertDoesNotClearDraftOrPreview("applyConnectionStatus", applyConnectionBody)
    }

    @Test
    fun obstructedLivePreviewKeepsStableFrameAndMarksItStale() {
        val html = ConsoleSourceFixtures.readAll()
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")

        assertTrue(refreshPreviewBody.contains("preview?.screen?.systemUiVisible && state.preview"))
        assertTrue(
            refreshPreviewBody.contains(
                "state.preview.obstructedBySystemUi = preview.screen.systemUiKind || 'system_ui';",
            ),
        )
        assertTrue(refreshPreviewBody.contains("return;"))
        assertTrue(applyConnectionBody.contains("if (state.preview?.obstructedBySystemUi)"))
        assertTrue(applyConnectionBody.contains("state.preview.stale = true;"))
        assertTrue(
            applyConnectionBody.contains(
                "if (!connectionOptions.preservePreviewStale " +
                    "&& !state.preview?.obstructedBySystemUi) markPreviewStale(false);",
            ),
        )
        assertDoesNotClearDraftOrPreview("refreshPreview", refreshPreviewBody)
        assertDoesNotClearDraftOrPreview("applyConnectionStatus", applyConnectionBody)
    }

    @Test
    fun readyConnectionSyncsSelectedDeviceBeforePreviewPolling() {
        val html = ConsoleSourceFixtures.readAll()
        val applyConnectionBody = javascriptFunctionBody(html, "applyConnectionStatus")
        val syncSelectedDeviceBody = javascriptFunctionBody(html, "syncSelectedDeviceFromConnection")

        assertTrue(syncSelectedDeviceBody.contains("const selectedDevice = status?.selectedDevice;"))
        assertTrue(syncSelectedDeviceBody.contains("selectedDevice?.serial"))
        assertTrue(syncSelectedDeviceBody.contains("state.selectedDeviceSerial = selectedDevice.serial;"))
        assertTrue(syncSelectedDeviceBody.contains("deviceBySerial(state.devices, selectedDevice.serial)"))
        assertTrue(syncSelectedDeviceBody.contains("setDeviceUiState"))

        val syncIndex = applyConnectionBody.indexOf("syncSelectedDeviceFromConnection(status);")
        val pollingIndex = applyConnectionBody.indexOf("startLivePreviewPolling();")
        assertTrue(syncIndex >= 0, "Connection status should sync server-selected device")
        assertTrue(pollingIndex >= 0, "Ready connection should start live preview polling")
        assertTrue(syncIndex < pollingIndex, "Selected device must be synced before preview polling starts")
    }

    @Test
    fun heartbeatApiPingsBridgeStatusWithoutCapturingPreview() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/heartbeat")

            assertEquals(200, connection.responseCode)
            assertEquals(1, bridge.statusCount)
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionApiReturnsSimpleConnectionStatus() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            assertEquals("Ready", json.getValue("headline").jsonPrimitive.content)
            assertEquals(true, json.getValue("canCapture").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusSurfacesAvailabilitySignalsFromBridgeStatus() {
        val bridge = FakeFixThisBridge(
            statusProvider = {
                buildJsonObject {
                    put("activity", "MainActivity")
                    put("rootsCount", 3)
                    put("screenInteractive", true)
                    put("keyguardLocked", false)
                    put("appForeground", true)
                    put("pictureInPicture", false)
                }
            },
        )
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            val availability = json.getValue("availability").jsonObject
            assertEquals(true, availability.getValue("screenInteractive").jsonPrimitive.boolean)
            assertEquals(false, availability.getValue("keyguardLocked").jsonPrimitive.boolean)
            assertEquals(true, availability.getValue("appForeground").jsonPrimitive.boolean)
            assertEquals(false, availability.getValue("pictureInPicture").jsonPrimitive.boolean)
            assertEquals(3, availability.getValue("rootsCount").jsonPrimitive.int)
            assertEquals("MainActivity", availability.getValue("activity").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusOmitsAvailabilityFieldsWhenLegacyBridgeStatusIsMissingThem() {
        val bridge = FakeFixThisBridge(
            statusProvider = {
                buildJsonObject {
                    put("rootsCount", 2)
                    put("sidekickVersion", "0.0.1")
                    put("bridgeProtocolVersion", 1)
                    put("sourceIndexAvailable", true)
                }
            },
        )
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject

            assertEquals("READY", json.getValue("state").jsonPrimitive.content)
            val availability = json.getValue("availability").jsonObject
            assertEquals(null, availability["screenInteractive"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["keyguardLocked"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["appForeground"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(null, availability["pictureInPicture"]?.jsonPrimitive?.booleanOrNull)
            assertEquals(2, availability.getValue("rootsCount").jsonPrimitive.int)
            assertEquals(null, availability["activity"]?.jsonPrimitive?.contentOrNull)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionStatusDoesNotCreateHiddenSessionAfterHistoryIsCleared() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "session-2").next),
            projectRoot = "/repo",
            defaultPackageName = CONNECTION_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val open = client.connection(
                "/api/session/open",
                method = "POST",
                body = """{"newSession":true}""",
            )
            assertEquals(200, open.responseCode)
            open.inputStream.close()

            val close = client.connection(
                "/api/session/close",
                method = "POST",
                body = """{"sessionId":"session-1"}""",
            )
            assertEquals(200, close.responseCode)
            close.inputStream.close()

            val connection = client.connection("/api/connection")
            assertEquals(200, connection.responseCode)
            connection.inputStream.close()

            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray

            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun heartbeatApiDoesNotCreateHiddenSessionWhenHistoryIsEmpty() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = CONNECTION_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val heartbeat = client.connection("/api/heartbeat")

            assertEquals(200, heartbeat.responseCode)
            heartbeat.inputStream.close()
            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun appLaunchApiDoesNotCreateHiddenSessionWhenHistoryIsEmpty() {
        val bridge = FakeFixThisBridge()
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = CONNECTION_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val client = ConsoleHttpTestClient(server.url)
            val launch = client.connection("/api/app/launch", method = "POST", body = "{}")

            assertEquals(200, launch.responseCode)
            launch.inputStream.close()
            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun connectionApiMapsUnauthorizedDeviceToDeviceBlockedReadiness() {
        val bridge = FakeFixThisBridge(
            devicesOverride = listOf(AdbDevice("unauthorized-device", "unauthorized")),
        )
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/connection")
            val json = fixThisJson.parseToJsonElement(body).jsonObject
            val readiness = json.getValue("readiness").jsonObject

            assertEquals("CHECK_PHONE", json.getValue("state").jsonPrimitive.content)
            assertEquals("DEVICE_BLOCKED", readiness.getValue("state").jsonPrimitive.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun launchAppApiLaunchesSelectedPackageAndReturnsStartingStatus() {
        val bridge = FakeFixThisBridge(heartbeatError = RuntimeException("not ready yet"))
        bridge.selectDevice("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp")
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service).also { it.start() }
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/app/launch")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }

            assertEquals(200, connection.responseCode)
            val json = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertEquals("STARTING", json.getValue("state").jsonPrimitive.content)
            assertEquals(listOf(CONNECTION_SAMPLE_PACKAGE), bridge.launchedPackages)
        } finally {
            server.stop()
        }
    }
}

class ConsoleDeviceSelectionRoutesTest {
    @Test
    fun consoleHtmlDisablesPreviewPollingForUnavailableDeviceSelection() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(
            html.contains("const selectedSerial = selected && selected.state === 'device' ? selected.serial : null;"),
        )
        assertTrue(html.contains("state.selectedDeviceSerial = null;"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(
            html.contains(
                "setDeviceUiState(DeviceUiState.UNAVAILABLE, " +
                    "deviceBySerial(state.devices, option.value) || { serial: option.value });",
            ),
        )
    }

    @Test
    fun consoleHtmlAutoSelectsSingleConnectedDeviceOnRefresh() {
        val html = ConsoleSourceFixtures.readAll()
        val refreshDevices = javascriptFunctionBody(html, "refreshDevices")

        assertTrue(refreshDevices.contains("let payload = await requestJson('/api/devices');"))
        assertTrue(refreshDevices.contains("const devices = payload.devices || [];"))
        assertTrue(
            refreshDevices.contains(
                "const connectedDevices = (payload.devices || []).filter(device => device.state === 'device');",
            ),
        )
        assertTrue(
            refreshDevices.contains(
                "if (!payload.selectedSerial && devices.length === 1 && connectedDevices.length === 1) {",
            ),
        )
        assertTrue(refreshDevices.contains("body: JSON.stringify({ serial: connectedDevices[0].serial })"))
        assertTrue(refreshDevices.contains("renderDeviceList(payload);"))
    }

    @Test
    fun consoleHtmlRerendersPreviewWhenDeviceSelectionInvalidatesPreview() {
        val html = ConsoleSourceFixtures.readAll()
        val renderDeviceList = javascriptFunctionBody(html, "renderDeviceList")
        val invalidatesPreview = "\\s*bumpSessionMutationGeneration\\(\\);" +
            "\\s*clearPreview\\(\\);\\s*renderPreviewOnly\\(\\);\\s*\\}"
        val noDevicesSelectionChange = Regex(
            "if \\(!devices\\.length\\) \\{[\\s\\S]*?" +
                "if \\(previousSelectedDeviceSerial !== selectedSerial\\) \\{$invalidatesPreview",
        )
        val selectedSerialChange = Regex(
            "const selectedSerial = selected && selected\\.state === 'device' \\? " +
                "selected\\.serial : null;" +
                "[\\s\\S]*?if \\(previousSelectedDeviceSerial !== selectedSerial\\) \\{" +
                invalidatesPreview,
        )

        assertTrue(
            noDevicesSelectionChange.containsMatchIn(renderDeviceList),
            "No-devices selection invalidation must rerender the preview region",
        )
        assertTrue(
            selectedSerialChange.containsMatchIn(renderDeviceList),
            "Selected-serial invalidation must rerender the preview region",
        )
    }

    @Test
    fun consoleHtmlClearsDeviceUiOnlyAfterClearSelectionSucceeds() {
        val html = ConsoleSourceFixtures.readAll()
        val clearRequest = "renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));"
        val clearUi = "setDeviceUiState(DeviceUiState.NONE);"
        val clearRequestIndex = html.indexOf(clearRequest)
        val clearUiIndex = if (clearRequestIndex >= 0) html.indexOf(clearUi, clearRequestIndex) else -1

        assertTrue(clearRequestIndex >= 0)
        assertTrue(clearUiIndex > clearRequestIndex)
    }

    @Test
    fun consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("function shortenDeviceSerial(serial)"))
        assertTrue(html.contains("withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];"))
        assertTrue(
            html.contains("if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);"),
        )

        val deviceLabelFallback = Regex(
            """function deviceLabel\(device\) \{\s+if \(!device\) return 'No device';\s+return ([^;]+);""",
        )
            .find(html)
            ?.groupValues
            ?.get(1)

        assertEquals(
            "device.label || device.model || device.deviceName || device.product || " +
                "shortenDeviceSerial(device.serial) || 'Unknown device'",
            deviceLabelFallback,
            "Connection-device label must be the first label fallback while preserving normal device serial shortening",
        )
    }

    @Test
    fun consoleHtmlPlacesAnnotateHintOutsideDeviceFrame() {
        val html = ConsoleSourceFixtures.readAll()
        val renderPreviewRegion = javascriptFunctionBody(html, "renderPreviewRegion")

        assertTrue(html.contains(".snapshot-stage"))
        assertTrue(html.contains("flex-direction: column;"))
        assertTrue(html.contains(".annotate-hint-slot"))
        assertTrue(Regex("\\.snapshot-stage \\{[\\s\\S]*gap: 10px;").containsMatchIn(html))
        assertTrue(html.contains(".annotate-hint"))
        assertTrue(html.contains("position: static;"))
        assertTrue(html.contains("id=\"annotateHintSlot\""))
        assertTrue(
            renderPreviewRegion.contains(
                "snapshot.dataset.toolMode = toolMode.isAnnotateMode() ? 'annotate' : 'select';",
            ),
        )
        assertTrue(renderPreviewRegion.contains("const hintSlot = document.getElementById('annotateHintSlot');"))
        assertTrue(renderPreviewRegion.contains("hintSlot.appendChild(hint);"))
        assertFalse(renderPreviewRegion.contains("snapshot.insertBefore(hint, frame);"))
        assertFalse(renderPreviewRegion.contains("frame.appendChild(hint);"))
    }

    @Test
    fun consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly() {
        val html = ConsoleSourceFixtures.readAll()
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")
        val renderSavedEvidenceOverlay = javascriptFunctionBody(html, "renderSavedEvidenceOverlay")
        val renderOverlayBox = javascriptFunctionBody(html, "renderOverlayBox")

        assertTrue(html.contains("function renderSavedEvidenceOverlay(overlay, image, items)"))
        // focusedSavedItemId is now owned by the toolModeFsm (no longer a module-level let).
        assertTrue(html.contains("focusedSavedItemId: null"))
        assertTrue(html.contains("function focusSavedEvidenceItem(itemId)"))
        assertTrue(html.contains("function selectedSavedAnnotation()"))
        assertTrue(renderOverlayBox.contains("selectHandler"))
        assertTrue(renderOverlayBox.contains("selectHandler(annotationIndex);"))
        assertTrue(renderSavedEvidenceOverlay.contains("focusSavedEvidenceItem(item.itemId)"))
        assertTrue(html.contains("savedEvidenceItems()"))
        assertFalse(renderSavedEvidenceGroups.contains("saved-evidence-preview"))
        assertFalse(renderSavedEvidenceGroups.contains("hydrateSavedEvidencePreviews"))
        assertFalse(html.contains("function hydrateSavedEvidencePreviews()"))
    }

    @Test
    @Suppress("LongMethod")
    fun consoleHtmlInvalidatesPreviewContextOnDeviceAndSessionBoundaries() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("function clearPreview()"))
        // Preview FSM single source of truth — invalidate dispatches
        // CONTEXT_CHANGED, which clears inFlight + current and bumps
        // requestGeneration/contextGeneration atomically.
        assertTrue(html.contains("previewUseCases.contextChanged();"))
        assertTrue(html.contains("setConsolePreview(null);"))
        assertTrue(
            Regex(
                "async function selectDevice\\(\\)[\\s\\S]*clearPreview\\(\\);" +
                    "[\\s\\S]*/api/device/select",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function disconnectDevice\\(\\)[\\s\\S]*clearPreview\\(\\);" +
                    "[\\s\\S]*/api/device/disconnect",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function openSession\\(sessionId\\)[\\s\\S]*clearPreview\\(\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function openSession\\(sessionId\\)[\\s\\S]*await refresh\\(\\);[\\s\\S]*if " +
                    "\\(!latestPersistedScreen\\(\\) && shouldAutoFetchPreview\\(\\)\\) \\{" +
                    "[\\s\\S]*await refreshPreview\\(\\);[\\s\\S]*\\}[\\s\\S]*startLivePreviewPolling\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex("function latestScreen\\(\\) \\{\\s+if \\(draftFlow\\(\\)\\) return draftFlow\\(\\)\\.screen;")
                .containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "if \\(focusedSavedItemId\\) \\{[\\s\\S]*?const focusedItem = savedEvidenceItems\\(\\)" +
                    "\\.find\\(item => item\\.itemId === focusedSavedItemId\\);",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("return savedScreen || state.preview?.screen || latestPersistedScreen();"))
        assertTrue(
            Regex(
                "async function newSession\\(\\)[\\s\\S]*clearPreview\\(\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function closeSession\\(\\)[\\s\\S]*clearPreview\\(\\);" +
                    "[\\s\\S]*/api/session/close",
            ).containsMatchIn(html),
        )
        assertTrue(html.contains("const previousSelectedDeviceSerial = state.selectedDeviceSerial;"))
        assertTrue(html.contains("if (previousSelectedDeviceSerial !== selectedSerial) {"))
        assertFalse(
            html.contains(
                "setConsolePreview(null);\n" +
                    "              setConsoleSession(await requestJson('/api/session/open'",
            ),
        )
        assertFalse(html.contains("setConsolePreview(null);\n              await refreshSessions();"))
    }

    @Test
    fun devicesApiListsAndSelectsActiveDevice() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val devices = ConsoleHttpTestClient(server.url).get("/api/devices")
            assertTrue(devices.contains("SM_G986N"))
            assertTrue(devices.contains("adb-R3CN60LXW3L"))

            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use {
                it.write("""{"serial":"adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"}""".toByteArray())
            }

            assertEquals(200, select.responseCode)
            assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsBadRequestForBlankSerial() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            CONNECTION_SAMPLE_PACKAGE,
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":" "}""".toByteArray()) }

            assertEquals(400, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("Device serial"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForMissingSerialWithoutChangingSelection() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"missing-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsConflictForOfflineDeviceWithoutChangingSelection() {
        val bridge = DeviceListBridge(
            listOf(
                AdbDevice(
                    serial = "offline-device",
                    state = "offline",
                    model = "Pixel_8",
                    product = "shiba",
                    deviceName = "shiba",
                ),
            ),
        )
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", CONNECTION_SAMPLE_PACKAGE)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = ConsoleHttpTestClient(server.url).connection("/api/device/select")
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"offline-device"}""".toByteArray()) }

            assertEquals(409, select.responseCode)
            assertTrue(select.errorStream.bufferedReader().readText().contains("DEVICE_NOT_AVAILABLE"))
            assertEquals(null, bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }
}

class ConsoleConnectionHtmlContractRoutesTest {
    @Test
    fun applyConnectionStatusCallsCheckProtocolCompat() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "applyConnectionStatus")
        assertTrue(
            body.contains("checkProtocolCompat"),
            "applyConnectionStatus must invoke checkProtocolCompat",
        )
        assertTrue(
            body.contains("checkSidekickBuildEpoch"),
            "applyConnectionStatus must invoke checkSidekickBuildEpoch",
        )
    }

    @Test
    fun stateConnectionDeclaresSessionsPollingPaused() {
        val html = ConsoleSourceFixtures.readAll()
        // The flag must be declared on state.connection (or a sibling module-level let).
        assertTrue(
            html.contains("sessionsPollingPaused"),
            "must declare sessionsPollingPaused flag on state.connection",
        )
        assertTrue(
            html.contains("function setSessionsPollingPaused"),
            "must declare setSessionsPollingPaused helper",
        )
    }

    @Test
    fun renderConnectionSurfacesSessionsPollingPaused() {
        val html = ConsoleSourceFixtures.readAll()
        val body = javascriptFunctionBody(html, "renderConnection")
        assertTrue(
            body.contains("sessionsPollingPaused") || body.contains("Reconnecting feedback updates"),
            "renderConnection must consult the paused flag and surface a Reconnecting message",
        )
    }
}
