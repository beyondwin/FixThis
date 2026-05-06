package io.github.pointpatch.mcp.console

import io.github.pointpatch.cli.AdbDevice
import io.github.pointpatch.cli.pointPatchJson
import io.github.pointpatch.compose.core.model.PointPatchNode
import io.github.pointpatch.compose.core.model.PointPatchRect
import io.github.pointpatch.compose.core.model.TreeKind
import io.github.pointpatch.mcp.session.CapturedScreen
import io.github.pointpatch.mcp.session.FakePointPatchBridge
import io.github.pointpatch.mcp.session.FeedbackItem
import io.github.pointpatch.mcp.session.FeedbackNavigationAction
import io.github.pointpatch.mcp.session.FeedbackScreenRoot
import io.github.pointpatch.mcp.session.FeedbackSessionException
import io.github.pointpatch.mcp.session.FeedbackSessionPaths
import io.github.pointpatch.mcp.session.FeedbackSessionPersistence
import io.github.pointpatch.mcp.session.FeedbackSessionService
import io.github.pointpatch.mcp.session.FeedbackSessionStore
import io.github.pointpatch.mcp.session.FeedbackScreenshot
import io.github.pointpatch.mcp.session.FeedbackTarget
import io.github.pointpatch.mcp.tools.PointPatchBridge
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackConsoleServerTest {
    @Test
    fun servesIndexAndSessionJson() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val index = URL(server.url).readText()
            assertTrue(index.contains("PointPatch Feedback Console"))

            val session = URL("${server.url}/api/session").readText()
            assertTrue(session.contains("io.github.pointpatch.sample"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesFaviconWithoutBrowserVisible404() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/favicon.ico").openConnection() as HttpURLConnection

            assertEquals(204, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun consoleHtmlIncludesSessionPickerControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"sessions\""))
        assertTrue(html.contains("/api/sessions"))
        assertTrue(html.contains("/api/session/open"))
        assertTrue(html.contains("state.session = null;"))
    }

    @Test
    fun consoleHtmlOmitsToolbarNavigationControls() {
        val html = FeedbackConsoleAssets.indexHtml

        assertFalse(html.contains("id=\"backButton\""))
        assertFalse(html.contains("id=\"captureAfterNavigation\""))
        assertFalse(html.contains("aria-label=\"Swipe up\""))
        assertTrue(html.contains("/api/navigation"))
        assertTrue(html.contains("captureAfter: false"))
    }

    @Test
    fun consoleHtmlUsesOptionAStudioShell() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("class=\"studio-shell\""))
        assertTrue(html.contains("class=\"studio-topbar\""))
        assertTrue(html.contains("class=\"studio-history\""))
        assertTrue(html.contains("class=\"studio-canvas\""))
        assertTrue(html.contains("class=\"studio-inspector\""))
        assertFalse(html.contains("id=\"previewModeBadge\""))
        assertTrue(html.contains("id=\"canvasToolbar\""))
        assertTrue(html.contains("id=\"inspectorTitle\""))
        assertTrue(html.contains("id=\"inspectorBody\""))
        assertTrue(html.contains("id=\"inspectorFooter\""))
        assertTrue(html.contains("<div class=\"panel-title\">History</div>"))
        assertTrue(html.contains("--bg-0: #0d0e10"))
        assertTrue(html.contains("--accent: #b8d36a"))
        assertFalse(html.contains("class=\"queue-pane\""))
    }

    @Test
    fun consoleHtmlKeepsStudioUsableInNarrowBrowser() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("@media (max-width: 899px)"))
        assertTrue(Regex("\\.studio-body \\{\\s+grid-template-columns: 1fr;").containsMatchIn(html))
        assertTrue(Regex("\\.studio-history \\{\\s+max-height: 180px;").containsMatchIn(html))
        assertTrue(Regex("\\.studio-inspector \\{\\s+min-height: 280px;").containsMatchIn(html))
        assertTrue(Regex("\\.snapshot-stage \\{\\s+min-height: 360px;").containsMatchIn(html))
        assertFalse(html.contains("Resize to >= 900px wide"))
        assertFalse(html.contains(".studio-shell::before"))
    }

    @Test
    fun consoleHtmlUsesModeAwareStudioInspector() {
        val html = FeedbackConsoleAssets.indexHtml
        val pendingRenderer = javascriptFunctionBody(html, "renderPendingItems")

        assertTrue(html.contains("function renderComposerInspector"))
        assertTrue(html.contains("function renderSavedAnnotationsInspector"))
        assertTrue(html.contains("function renderAnnotationDetail"))
        assertTrue(html.contains("function colorWithAlpha(color, alpha)"))
        assertTrue(html.contains("box.style.setProperty('--selection-color', color);"))
        assertTrue(html.contains("label.style.setProperty('--selection-color', color);"))
        assertTrue(html.contains("inspectorTitle.textContent = item ? 'Annotation' : 'Annotations'"))
        assertTrue(html.contains("inspectorTitle.textContent = 'Annotations'"))
        assertFalse(html.contains("inspectorTitle.textContent = 'Draft'"))
        assertFalse(html.contains(".saved-evidence-frame .selection-overlay"))
        assertFalse(html.contains("function hydrateSavedEvidencePreviews()"))
        assertTrue(html.contains("background: var(--selection-fill, rgba(184, 211, 106, .12));"))
        assertTrue(html.contains("background: var(--selection-color, var(--accent));"))
        assertTrue(html.contains("box-shadow: inset 3px 0 0 var(--annotation-color, var(--warning));"))
        assertTrue(html.contains("No annotations yet."))
        assertTrue(html.contains("No saved annotations yet."))
        assertTrue(html.contains("Use <b>Annotate</b>"))
        assertTrue(pendingRenderer.contains("ann-row"))
        assertTrue(pendingRenderer.contains("ann-row-num"))
        assertTrue(pendingRenderer.contains("ann-row-status"))
        assertTrue(pendingRenderer.contains("startAnnotatingButtonHtml()"))
        assertTrue(pendingRenderer.contains("data-focus-pending"))
        assertFalse(pendingRenderer.contains("data-delete-pending"))
        assertTrue(html.contains("grid-template-columns: 28px minmax(0, 1fr) auto;"))
        assertTrue(Regex("\\.ann-row-body \\{\\s+min-width: 0;\\s+overflow: hidden;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-title \\{\\s+display: block;\\s+max-width: 100%;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-status \\{\\s+justify-self: end;").containsMatchIn(html))
        assertTrue(pendingRenderer.contains("style=\"--annotation-color:"))
        assertTrue(html.contains("renderOverlayBox(overlay, image, item.bounds, String(index + 1), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)))"))
        assertTrue(html.contains("renderOverlayBox(overlay, image, boundsForTarget(item.target), String(index + 1), false, false, null, '', severityColor(annotationSeverity(item)))"))
        assertFalse(html.contains("item.bounds, '#' + (index + 1)"))
        assertFalse(html.contains("boundsForTarget(item.target), '#' + (index + 1)"))
        assertTrue(html.contains("<label for=\"annotationLabelInput\">Label</label>"))
        assertTrue(html.contains("<label>Severity</label>"))
        assertTrue(html.contains("<label>Status</label>"))
        assertTrue(html.contains("data-set-severity"))
        assertTrue(html.contains("data-set-status"))
        assertTrue(html.contains("data-delete-current"))
        val annotationActionCss = html.substringAfter(".annotation-actions {").substringBefore("img {")
        assertTrue(annotationActionCss.contains(".annotation-danger:focus-visible"))
        assertTrue(annotationActionCss.contains(".annotation-done:focus-visible"))
        assertTrue(annotationActionCss.contains("border: 1px solid transparent;"))
        assertTrue(annotationActionCss.contains("background: rgba(255, 111, 111, .08);"))
        assertTrue(annotationActionCss.contains("border-color: var(--line);"))
    }

    @Test
    fun consoleHtmlResetsAnnotationComposerStateAcrossSessionActions() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function resetAnnotationComposerState(clearFlow = true)"))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function newSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function closeSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*/api/session/close").containsMatchIn(html))
        assertTrue(Regex("async function deleteHistorySession\\(sessionId\\)[\\s\\S]*const isDisplayedSession = \\(\\) => state\\.session\\?\\.sessionId === sessionId;[\\s\\S]*if \\(isDisplayedSession\\(\\)\\) \\{\\s+resetAnnotationComposerState\\(\\);").containsMatchIn(html))
        assertTrue(Regex("async function deleteHistorySession\\(sessionId\\)[\\s\\S]*if \\(isDisplayedSession\\(\\)\\) \\{[\\s\\S]*state\\.session = null;[\\s\\S]*await refreshSessions\\(\\);\\s+render\\(\\);\\s+await refreshDevices\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function cancelAddItemsFlow\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);[\\s\\S]*render\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function deletePendingFeedbackItem\\(index\\)[\\s\\S]*focusedPendingItemIndex = null;[\\s\\S]*currentSelection = null;[\\s\\S]*comment.value = '';").containsMatchIn(html))
    }

    @Test
    fun consoleHtmlKeepsPointPatchTopLevelActionsInStudioTopbar() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"copyPromptButton\""))
        assertTrue(html.contains("id=\"sendAgentButton\""))
        assertTrue(html.contains("id=\"selectToolButton\""))
        assertTrue(html.contains("id=\"annotateToolButton\""))
        assertFalse(html.contains("id=\"refreshButton\""))
        assertFalse(html.contains("id=\"saveButton\""))
        assertFalse(html.contains("id=\"copyMarkdownButton\""))
        assertFalse(html.contains("id=\"sendDraftButton\""))
        assertFalse(html.contains("id=\"newSessionButton\""))
        assertFalse(html.contains("id=\"closeSessionButton\""))
        assertTrue(html.contains("<span>Copy Prompt</span>"))
        assertTrue(html.contains("<span>Send Agent</span>"))
        assertTrue(html.contains("<span>Select</span>"))
        assertTrue(html.contains("<span>Annotate</span>"))
        assertTrue(html.contains("class=\"button-icon\" aria-hidden=\"true\""))
        assertTrue(html.contains("stroke-dasharray=\"3 3\""))
        assertFalse(html.contains("<span>Refresh</span>"))
        assertFalse(html.contains("<span>Save snapshot</span>"))
        assertFalse(html.contains(">Copy<"))
        assertFalse(html.contains(">Send<"))
        assertFalse(html.contains(">New<"))
        assertFalse(html.contains(">Close<"))
        assertFalse(html.contains("id=\"modeSelect\""))
        assertFalse(html.contains("id=\"modeNavigate\""))
    }

    @Test
    fun consoleHtmlBuildsCopiesAndSendsSelectedHistoryPrompt() {
        val html = FeedbackConsoleAssets.indexHtml
        val updateComposerStateBody = javascriptFunctionBody(html, "updateComposerState")
        val copyPromptBody = javascriptFunctionBody(html, "copyPrompt")
        val sendAgentPromptBody = javascriptFunctionBody(html, "sendAgentPrompt")
        val currentPromptAnnotationsBody = javascriptFunctionBody(html, "currentPromptAnnotations")
        val promptGuardBody = javascriptFunctionBody(html, "ensurePromptAnnotationsAvailable")
        val renderSessionsListBody = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        val clearSentHistoryBody = javascriptFunctionBody(html, "clearSentHistory")

        assertTrue(html.contains("function currentAnnotationsPrompt"))
        assertTrue(html.contains("function currentPromptAnnotations"))
        assertTrue(html.contains("function hasWrittenAnnotationComment(item)"))
        assertTrue(currentPromptAnnotationsBody.contains(".filter(hasWrittenAnnotationComment)"))
        assertTrue(html.contains("function ensurePromptAnnotationsAvailable()"))
        assertTrue(promptGuardBody.contains("window.alert(message)"))
        assertTrue(html.contains("async function copyTextToClipboard(text)"))
        assertTrue(html.contains("navigator.clipboard.writeText(text)"))
        assertTrue(html.contains("document.execCommand('copy')"))
        assertTrue(html.contains("fallback.remove()"))
        assertTrue(copyPromptBody.contains("ensurePromptAnnotationsAvailable();"))
        assertTrue(copyPromptBody.contains("await copyTextToClipboard(currentAnnotationsPrompt(annotations))"))
        assertTrue(sendAgentPromptBody.contains("ensurePromptAnnotationsAvailable();"))
        assertTrue(sendAgentPromptBody.contains("const prompt = currentAnnotationsPrompt();"))
        assertTrue(sendAgentPromptBody.contains("persistPendingFeedbackItems({ onlyWrittenComments: true })"))
        assertTrue(sendAgentPromptBody.contains("body: JSON.stringify({ prompt: prompt })"))
        assertTrue(updateComposerStateBody.contains("const hasPromptAnnotations = currentPromptAnnotations().length > 0;"))
        assertTrue(updateComposerStateBody.contains("copyPromptButton.dataset.unavailable = String(!hasPromptAnnotations);"))
        assertTrue(updateComposerStateBody.contains("sendAgentButton.dataset.unavailable = String(!hasPromptAnnotations);"))
        assertTrue(updateComposerStateBody.contains("copyPromptButton.classList.toggle('is-disabled', !hasPromptAnnotations);"))
        assertTrue(updateComposerStateBody.contains("sendAgentButton.classList.toggle('is-disabled', !hasPromptAnnotations);"))
        assertTrue(renderSessionsListBody.contains("session.status !== 'ready_for_agent'"))
        assertTrue(html.contains("id=\"clearSentHistoryButton\""))
        assertTrue(clearSentHistoryBody.contains("window.confirm"))
        assertTrue(clearSentHistoryBody.contains("/api/session/close"))
        assertTrue(html.contains("clearSentHistoryButton.addEventListener('click'"))
    }

    @Test
    fun consoleHtmlRefreshPreviewOnlyRendersPreviewRegion() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshPreviewBody = javascriptFunctionBody(html, "refreshPreview")

        assertTrue(html.contains("function renderPreviewRegion"))
        assertTrue(html.contains("function renderSessionRegions"))
        assertTrue(html.contains("function renderInspectorRegion"))
        assertTrue(html.contains("function renderPreviewOnly"))
        assertTrue(refreshPreviewBody.contains("renderPreviewOnly();"))
        assertFalse(refreshPreviewBody.contains("render();"))
    }

    @Test
    fun consoleHtmlAddsStudioKeyboardAndAccessibilityGuards() {
        val html = FeedbackConsoleAssets.indexHtml
        val inputGuardBody = javascriptFunctionBody(html, "isTextInputFocused")
        val shortcutBody = javascriptFunctionBody(html, "handleGlobalShortcut")

        assertTrue(html.contains("function isTextInputFocused"))
        assertTrue(html.contains("function handleGlobalShortcut"))
        assertTrue(html.contains("function isTextInputFocused(target = document.activeElement)"))
        assertTrue(inputGuardBody.contains("tag === 'SELECT'"))
        assertTrue(shortcutBody.contains("if (event.repeat) return;"))
        assertTrue(shortcutBody.contains("isTextInputFocused(event.target)"))
        assertTrue(html.contains("event.key === 'Escape'"))
        assertTrue(html.contains("event.key.toLowerCase() === 'a'"))
        assertFalse(html.contains("event.key.toLowerCase() === 's'"))
        assertFalse(html.contains("event.key.toLowerCase() === 'n'"))
        assertTrue(html.contains("!event.shiftKey"))
        assertTrue(html.contains("document.addEventListener('keydown', handleGlobalShortcut)"))
        assertTrue(html.contains("role=\"status\" aria-live=\"polite\""))
        assertTrue(html.contains("aria-label=\"PointPatch preview\""))
    }

    private fun javascriptFunctionBody(html: String, functionName: String): String {
        val declarationStart = html.indexOf("function $functionName(")
        assertTrue(declarationStart >= 0, "Missing JavaScript function: $functionName")

        val bodyStart = html.indexOf('{', declarationStart)
        assertTrue(bodyStart >= 0, "Missing JavaScript function body: $functionName")

        var depth = 1
        for (index in bodyStart + 1 until html.length) {
            when (html[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(bodyStart + 1, index)
                }
            }
        }

        throw AssertionError("Unclosed JavaScript function body: $functionName")
    }

    @Test
    fun consoleHtmlIncludesSelectionHandoffWorkspace() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("id=\"deviceControl\""))
        assertTrue(html.contains("id=\"devicePicker\""))
        assertTrue(html.contains("id=\"deviceName\""))
        assertTrue(html.contains("id=\"deviceConnectionState\""))
        assertTrue(html.contains("id=\"refreshDevicesButton\""))
        assertTrue(html.contains("id=\"disconnectDeviceButton\""))
        assertTrue(html.contains("aria-label=\"Android device\""))
        assertTrue(html.contains("aria-label=\"Refresh devices\""))
        assertTrue(html.contains("title=\"Refresh devices\""))
        assertTrue(html.contains("Clear selection"))
        assertTrue(html.contains("aria-label=\"Clear PointPatch device selection\""))
        assertTrue(html.contains("title=\"Clear PointPatch device selection\""))
        assertTrue(html.contains("/api/devices"))
        assertTrue(html.contains("/api/device/select"))
        assertTrue(html.contains("/api/device/disconnect"))
        assertTrue(html.contains("function refreshDevices"))
        assertTrue(html.contains("function selectDevice"))
        assertTrue(html.contains("function disconnectDevice"))
        assertTrue(html.contains("function deviceLabel"))
        assertTrue(html.contains("function shortenDeviceSerial"))
        assertTrue(html.contains("function setDeviceUiState"))
        assertTrue(html.contains("function deviceOptionLabel"))
        assertTrue(html.contains("option.textContent = deviceOptionLabel(device);"))
        assertFalse(html.contains("deviceStatus.textContent = selected ? 'Selected ' + selected.serial"))
        assertFalse(html.contains("Selected ' + selected.serial"))
        assertTrue(html.contains("id=\"previewIntervalSelect\""))
        assertTrue(html.contains("id=\"selectionOverlay\""))
        assertTrue(html.contains("id=\"selectionSummary\""))
        assertTrue(html.contains("id=\"pendingItems\""))
        assertTrue(html.contains("id=\"draftItems\""))
        assertTrue(html.contains("id=\"sentHistory\""))
        assertTrue(html.contains("id=\"sendAgentButton\""))
        assertTrue(html.contains("id=\"copyPromptButton\""))
        assertTrue(html.contains("id=\"clearSelectionButton\""))
        assertTrue(html.contains("id=\"clearDraftButton\""))
        assertTrue(html.contains("/api/preview"))
        assertTrue(html.contains("/api/items/batch"))
        assertTrue(html.contains("/api/items/draft"))
        assertTrue(html.contains("/api/agent-handoffs"))
        assertTrue(html.contains("function clearSelection"))
        assertTrue(html.contains("function clearDraft"))
        assertTrue(html.contains("function sendAgentPrompt"))
        assertTrue(html.contains("selectionSummary.textContent = currentSelection"))
        assertTrue(html.contains("sendAgentButton.classList.toggle('is-disabled', !hasPromptAnnotations);"))
        assertTrue(html.contains("formatSessionLabel"))
        assertTrue(html.contains("formatSessionSummary"))
        assertTrue(html.contains("historyOpenCount"))
        assertTrue(html.contains("historyDoneCount"))
        assertTrue(html.contains("historyPointsCount"))
        assertTrue(html.contains("renderHistoryStrip"))
        assertTrue(html.contains("formatItemLabel"))
        assertTrue(html.contains("function findScreen"))
        assertTrue(html.contains("function targetLabel"))
        assertTrue(html.contains("function sourceHintLabel"))
        assertTrue(html.contains("function escapeHtmlValue(value)"))
        assertTrue(html.contains("escapeHtmlValue(item.comment)"))
        assertTrue(html.contains("function formatBatchLabel"))
        assertTrue(html.contains("function formatBatchDetails"))
        assertTrue(html.contains("function batchItems"))
        assertTrue(html.contains("function formatBatchItemSummary"))
        assertTrue(html.contains("session.handoffBatches"))
        assertTrue(html.contains("Batch #"))
        assertTrue(html.contains("No batch metadata"))
        assertTrue(html.contains("Sent outside a batch"))
        assertTrue(html.contains("Unbatched sent item"))
        assertTrue(html.contains("Missing batch metadata"))
        assertFalse(html.contains("id=\"modeSelect\""))
        assertFalse(html.contains("id=\"modeNavigate\""))
    }

    @Test
    fun consoleHtmlShowsReadableDeviceConnectionStates() {
        val html = FeedbackConsoleAssets.indexHtml

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
        assertTrue(html.contains("deviceConnectionState.textContent = DeviceStateCopy[uiState];"))
        assertTrue(html.contains("const state = { session: null, preview: null, sessionSummaries: [], selectedDeviceSerial: null, devices: [] };"))
        assertTrue(html.contains("state.devices = devices;"))
        assertTrue(html.contains("setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));"))
    }

    @Test
    fun consoleHtmlDisablesPreviewPollingForUnavailableDeviceSelection() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("const selectedSerial = selected && selected.state === 'device' ? selected.serial : null;"))
        assertTrue(html.contains("state.selectedDeviceSerial = null;"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(html.contains("setDeviceUiState(DeviceUiState.UNAVAILABLE, deviceBySerial(state.devices, option.value) || { serial: option.value });"))
    }

    @Test
    fun consoleHtmlAutoSelectsSingleConnectedDeviceOnRefresh() {
        val html = FeedbackConsoleAssets.indexHtml
        val refreshDevices = javascriptFunctionBody(html, "refreshDevices")

        assertTrue(refreshDevices.contains("let payload = await requestJson('/api/devices');"))
        assertTrue(refreshDevices.contains("const devices = payload.devices || [];"))
        assertTrue(refreshDevices.contains("const connectedDevices = (payload.devices || []).filter(device => device.state === 'device');"))
        assertTrue(refreshDevices.contains("if (!payload.selectedSerial && devices.length === 1 && connectedDevices.length === 1) {"))
        assertTrue(refreshDevices.contains("body: JSON.stringify({ serial: connectedDevices[0].serial })"))
        assertTrue(refreshDevices.contains("renderDeviceList(payload);"))
    }

    @Test
    fun consoleHtmlRerendersPreviewWhenDeviceSelectionInvalidatesPreview() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderDeviceList = javascriptFunctionBody(html, "renderDeviceList")
        val noDevicesSelectionChange = Regex(
            """
            if \(!devices\.length\) \{[\s\S]*?if \(previousSelectedDeviceSerial !== selectedSerial\) \{\s*invalidatePreviewContext\(\);\s*renderPreviewOnly\(\);\s*\}
            """.trimIndent(),
        )
        val selectedSerialChange = Regex(
            """
            const selectedSerial = selected && selected\.state === 'device' \? selected\.serial : null;[\s\S]*?if \(previousSelectedDeviceSerial !== selectedSerial\) \{\s*invalidatePreviewContext\(\);\s*renderPreviewOnly\(\);\s*\}
            """.trimIndent(),
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
        val html = FeedbackConsoleAssets.indexHtml
        val clearRequest = "renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));"
        val clearUi = "setDeviceUiState(DeviceUiState.NONE);"
        val clearRequestIndex = html.indexOf(clearRequest)
        val clearUiIndex = if (clearRequestIndex >= 0) html.indexOf(clearUi, clearRequestIndex) else -1

        assertTrue(clearRequestIndex >= 0)
        assertTrue(clearUiIndex > clearRequestIndex)
    }

    @Test
    fun consoleHtmlShortensWifiAdbSerialsForNormalDeviceLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function shortenDeviceSerial(serial)"))
        assertTrue(html.contains("withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];"))
        assertTrue(html.contains("if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);"))
        assertTrue(html.contains("return device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device';"))
    }

    @Test
    fun consoleHtmlDoesNotRenderInternalIdsInHumanLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertFalse(html.contains("id \${shortId(session.sessionId)}"))
        assertFalse(html.contains("\${session.status} | \${shortId(session.sessionId)}"))
        assertFalse(html.contains(" | \${escapeHtml(shortId(screen.screenId))} | "))
        assertFalse(html.contains("item \${escapeHtml(shortId(item.itemId))}"))
        assertFalse(html.contains("screen \${escapeHtml(shortId(item.screenId))}"))
        assertFalse(html.contains("batch \${escapeHtml(shortId(batch.batchId))}"))
        assertFalse(html.contains("items \${escapeHtml((batch.itemIds || []).map(shortId).join(', ') || '-')}"))
        assertFalse(html.contains("Missing item \${shortId(item.itemId)}"))

        assertFalse(html.contains("function formatSessionHeader"))
        assertFalse(html.contains("feedback item', 'feedback items'"))
        assertTrue(html.contains("function renderSavedEvidenceGroups"))
    }

    @Test
    fun consoleHtmlRendersStudioSessionHistoryWithoutInternalIds() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function renderSessionsList"))
        assertTrue(html.contains("sessionCount.textContent"))
        assertTrue(html.contains("class=\"history-item session-row"))
        assertTrue(html.contains("class=\"hi-head\""))
        assertTrue(html.contains("class=\"hi-title\""))
        assertTrue(html.contains("class=\"hi-meta\""))
        assertTrue(html.contains("class=\"hi-stats\""))
        assertTrue(html.contains("class=\"hi-strip\""))
        assertTrue(html.contains("class=\"hi-pip open\""))
        assertTrue(html.contains("class=\"hi-pip done\""))
        assertTrue(html.contains("class=\"hi-pip points\""))
        assertTrue(html.contains("class=\"hi-strip-cell"))
        assertTrue(html.contains("data-delete-session-id"))
        assertTrue(html.contains("async function deleteHistorySession(sessionId)"))
        assertTrue(html.contains("event.stopPropagation();"))
        assertTrue(html.contains("row.addEventListener('keydown'"))
        assertTrue(html.contains("row.classList.toggle('is-active'"))
        assertTrue(html.contains(".history-list { align-content: start; }"))
        assertTrue(html.contains("class=\"sent-history-drawer\""))
        assertTrue(html.contains("formatSessionSummary(session)"))
        assertTrue(html.contains(".sent-history-drawer .history-list"))
        assertTrue(html.contains("max-height:"))
        assertTrue(html.contains("overflow: auto"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
        assertTrue(html.contains("class=\"history-item history-add-row\""))
        assertTrue(html.contains("data-start-new-history-annotating"))
        assertTrue(html.contains("function emptySessionsHtml()"))
        assertTrue(Regex("sessions\\.innerHTML = renderedSessions\\s+\\? renderedSessions \\+ historyStartAnnotatingItemHtml\\(\\)\\s+: historyStartAnnotatingItemHtml\\(\\) \\+ emptySessionsHtml\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));"))
        assertTrue(html.contains("async function enterNewHistoryAnnotateMode()"))
        assertTrue(html.contains("let newHistoryAnnotateModeStarting = false;"))
        assertTrue(html.contains("if (newHistoryAnnotateModeStarting) return;"))
        assertTrue(html.contains("newHistoryAnnotateModeStarting = true;"))
        assertTrue(html.contains("newHistoryAnnotateModeStarting = false;"))
        assertTrue(html.contains("await newSession();"))
        assertTrue(html.contains("scrollActiveHistoryItemIntoView();"))
        assertTrue(html.contains("await enterAnnotateMode();"))
        assertTrue(html.contains("function scrollActiveHistoryItemIntoView()"))
        assertTrue(html.contains("sessions.querySelector('.session-row.is-active')"))
        assertTrue(html.contains("renderCurrentSessionList();"))
        assertTrue(html.contains("if (newHistoryAnnotateModeStarting) return '';"))
        assertFalse(html.contains("if (toolMode === 'annotate' || newHistoryAnnotateModeStarting) return '';"))
        assertFalse(html.contains("id=\"historyStartAnnotatingButton\""))
        assertFalse(html.contains(".panel-head-actions"))
        assertTrue(html.contains(".history-add-row"))
        assertFalse(html.contains("· Not sent"))
        assertFalse(html.contains("shortId(session.sessionId)"))
        assertFalse(html.contains("shortId(screen.screenId)"))
        assertFalse(html.contains("shortId(batch.batchId)"))
    }

    @Test
    fun consoleHtmlFlushesPendingAnnotationsBeforeSessionSwitch() {
        val html = FeedbackConsoleAssets.indexHtml
        val openSession = javascriptFunctionBody(html, "openSession")
        val newSession = javascriptFunctionBody(html, "newSession")
        val flushPending = javascriptFunctionBody(html, "flushPendingAnnotationsBeforeSessionChange")
        val persistPending = javascriptFunctionBody(html, "persistPendingFeedbackItems")
        val pendingPayload = javascriptFunctionBody(html, "pendingPayloadItems")

        assertTrue(html.contains("async function flushPendingAnnotationsBeforeSessionChange()"))
        assertTrue(flushPending.contains("if (!addItemsFlow || !pendingFeedbackItems.length) return;"))
        assertTrue(flushPending.contains("await persistPendingFeedbackItems({ allowBlankComments: true });"))
        assertTrue(openSession.contains("await flushPendingAnnotationsBeforeSessionChange();"))
        assertTrue(newSession.contains("await flushPendingAnnotationsBeforeSessionChange();"))
        assertTrue(html.contains("const allowBlankComments = Boolean(options.allowBlankComments);"))
        assertTrue(html.contains("!allowBlankComments"))
        assertTrue(html.contains("allowBlankComments: allowBlankComments"))
    }

    @Test
    fun consoleUsesOptionASelectAnnotateToolsAndSimpleLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("<span>Select</span>"))
        assertTrue(html.contains("<span>Annotate</span>"))
        assertTrue(html.contains("<span>Copy Prompt</span>"))
        assertTrue(html.contains("<span>Send Agent</span>"))
        assertFalse(html.contains("<span>Save snapshot</span>"))
        assertFalse(html.contains("<span>Refresh</span>"))
        assertFalse(html.contains(">Copy<"))
        assertFalse(html.contains(">Send<"))
        assertTrue(html.contains("setInterval"))
        assertTrue(html.contains("document.hidden"))
        assertTrue(html.contains("previewIntervalSelect"))
        assertTrue(html.contains("PreviewIntervalStorageKey"))
        assertTrue(html.contains("Math.max(1000"))
        assertTrue(html.contains("let toolMode = 'select'"))
        assertTrue(html.contains("function enterAnnotateMode"))
        assertTrue(html.contains("function enterSelectMode"))
        assertTrue(Regex("async function enterAnnotateMode\\(\\) \\{\\s+await ensureSessionForAnnotating\\(\\);\\s+toolMode = 'annotate';\\s+renderCurrentSessionList\\(\\);\\s+if \\(!addItemsFlow\\) \\{\\s+await startAddItemsFlow\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("inspectorTitle.textContent = item ? 'Annotation' : 'Annotations'"))
        assertTrue(html.contains("pendingItems.hidden = false"))
        assertTrue(html.contains("renderPendingItems"))
        assertTrue(html.contains("renderNumberedFeedbackOverlay"))
        assertTrue(html.contains("focusPendingFeedbackItem"))
        assertTrue(html.contains("deletePendingFeedbackItem"))
        assertTrue(html.contains("renderSavedEvidenceGroups"))
        assertTrue(html.contains("const DefaultLivePreviewIntervalMs = 1000"))
        assertTrue(html.contains("const MinLivePreviewIntervalMs = 1000"))
        assertTrue(html.contains("<option value=\"1000\" selected>1s</option>"))
        assertTrue(html.contains("const PreviewIntervalStorageKey = 'pointpatch.previewIntervalMs.v2'"))
        assertFalse(html.contains("id=\"sessionMeta\""))
        assertFalse(html.contains("function formatSessionHeader"))
        assertTrue(html.contains("startAddItemsFlow"))
        assertTrue(html.contains("createAnnotationFromSelection"))
        assertTrue(html.contains("savePendingFeedbackItems"))
        assertFalse(html.contains("modeSelect"))
        assertFalse(html.contains("modeNavigate"))
        assertFalse(html.contains("id=\"addFlowButton\""))
        assertFalse(html.contains("Add to Pending"))
        assertFalse(html.contains("Clear Comment"))
    }

    @Test
    fun consoleHtmlKeepsHiddenInspectorListsOutOfLayout() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("[hidden] { display: none !important; }"))
        assertTrue(html.contains("pendingItems.hidden = true"))
        assertTrue(html.contains("draftItems.hidden = true"))
    }

    @Test
    fun consoleHtmlShowsStartAnnotatingWhenSavedAnnotationsAreEmpty() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(renderSavedEvidenceGroups.contains("startAnnotatingButtonHtml()"))
        assertTrue(html.contains("data-start-annotating"))
        assertTrue(html.contains("Start annotating"))
        assertTrue(html.contains("function bindStartAnnotatingButtons(container)"))
        assertTrue(renderSavedEvidenceGroups.contains("bindStartAnnotatingButtons(draftItems);"))
        assertTrue(html.contains("function startAnnotatingButtonHtml()"))
        assertTrue(html.contains("if (toolMode === 'annotate') return '';"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
    }

    @Test
    fun consoleHtmlGivesBackToAnnotationsButtonButtonPadding() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(Regex("\\.annotation-back \\{[\\s\\S]*min-height: 32px;[\\s\\S]*padding: 0 14px;").containsMatchIn(html))
        assertTrue(Regex("\\.annotation-danger,[\\s\\S]*\\.annotation-done \\{[\\s\\S]*padding: 0 14px;").containsMatchIn(html))
    }

    @Test
    fun consoleHtmlPlacesAnnotateHintOutsideDeviceFrame() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderPreviewRegion = javascriptFunctionBody(html, "renderPreviewRegion")

        assertTrue(html.contains(".snapshot-stage"))
        assertTrue(html.contains("flex-direction: column;"))
        assertTrue(html.contains(".annotate-hint-slot"))
        assertTrue(html.contains("min-height: 6px;"))
        assertTrue(Regex("\\.snapshot-stage \\{[\\s\\S]*gap: 10px;").containsMatchIn(html))
        assertTrue(html.contains(".annotate-hint"))
        assertTrue(html.contains("position: static;"))
        assertTrue(html.contains("id=\"annotateHintSlot\""))
        assertTrue(renderPreviewRegion.contains("const hintSlot = document.getElementById('annotateHintSlot');"))
        assertTrue(renderPreviewRegion.contains("hintSlot.appendChild(hint);"))
        assertFalse(renderPreviewRegion.contains("snapshot.insertBefore(hint, frame);"))
        assertFalse(renderPreviewRegion.contains("frame.appendChild(hint);"))
    }

    @Test
    fun consoleHtmlCreatesHistorySessionBeforeAnnotatingFromEmptyState() {
        val html = FeedbackConsoleAssets.indexHtml
        val hasActiveHistorySessionForAnnotating = javascriptFunctionBody(html, "hasActiveHistorySessionForAnnotating")
        val ensureSessionForAnnotating = javascriptFunctionBody(html, "ensureSessionForAnnotating")
        val enterAnnotateMode = javascriptFunctionBody(html, "enterAnnotateMode")

        assertTrue(html.contains("function hasActiveHistorySessionForAnnotating()"))
        assertTrue(ensureSessionForAnnotating.contains("if (hasActiveHistorySessionForAnnotating()) return;"))
        assertTrue(hasActiveHistorySessionForAnnotating.contains("state.session.status !== 'ready_for_agent'"))
        assertTrue(hasActiveHistorySessionForAnnotating.contains("state.session.status !== 'closed'"))
        assertTrue(hasActiveHistorySessionForAnnotating.contains("(state.sessionSummaries || []).some"))
        assertTrue(ensureSessionForAnnotating.contains("/api/session/open"))
        assertTrue(ensureSessionForAnnotating.contains("body: JSON.stringify({ newSession: true })"))
        assertTrue(ensureSessionForAnnotating.contains("await refreshSessions();"))
        assertTrue(enterAnnotateMode.contains("await ensureSessionForAnnotating();"))
        assertTrue(enterAnnotateMode.contains("toolMode = 'annotate';"))
        assertTrue(enterAnnotateMode.contains("renderCurrentSessionList();"))
        assertTrue(enterAnnotateMode.contains("if (!addItemsFlow) {"))
        assertTrue(enterAnnotateMode.contains("await startAddItemsFlow();"))
    }

    @Test
    fun consoleHtmlKeepsFrozenPreviewStableAndShowsPersistedScreenHistory() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let previewRequestGeneration = 0"))
        assertTrue(html.contains("let previewRequestInFlight = null"))
        assertTrue(html.contains("const preview = await requestLivePreview();"))
        assertTrue(html.contains("const requestGeneration = ++previewRequestGeneration"))
        assertTrue(html.contains("if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;"))
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId)"))
        assertTrue(html.contains("function latestPersistedScreen()"))
        assertTrue(html.contains("const persistedScreenIds = new Set("))
        assertTrue(html.contains(".filter(screen => persistedScreenIds.has(screen.screenId))"))
        assertTrue(html.contains("function screenImageUrl(screen)"))
        assertTrue(html.contains("return addItemsFlow?.screen || latestPersistedScreen() || state.preview?.screen;"))
        assertTrue(html.contains("'/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full'"))
        assertTrue(html.contains("const persistedItems = persistedItemsForScreen(screen?.screenId);"))
        assertTrue(html.contains("renderSavedEvidenceOverlay(overlay, image, persistedItems);"))
        assertFalse(html.contains("if (!addItemsFlow && !state.preview && persistedItems.length)"))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*stopLivePreviewPolling\\(\\);[\\s\\S]*await refresh\\(\\);").containsMatchIn(html))
        assertTrue(html.contains("function savedEvidenceItems()"))
        assertTrue(html.contains("return savedEvidenceItems().filter(item => item.screenId === screenId);"))
        assertFalse(html.contains("escapeHtml(formatSavedEvidenceItemLabel(item, index))"))
    }

    @Test
    fun consoleHtmlKeepsSavedAnnotationPreviewsInCenterDeviceOnly() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(html.contains("function renderSavedEvidenceOverlay(overlay, image, items)"))
        assertTrue(html.contains("persistedItemsForScreen(screen?.screenId)"))
        assertFalse(renderSavedEvidenceGroups.contains("saved-evidence-preview"))
        assertFalse(renderSavedEvidenceGroups.contains("hydrateSavedEvidencePreviews"))
        assertFalse(html.contains("function hydrateSavedEvidencePreviews()"))
    }

    @Test
    fun consoleHtmlRendersSavedAnnotationsWithSameListUiAfterSessionSwitch() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(renderSavedEvidenceGroups.contains("const items = savedEvidenceItems();"))
        assertTrue(renderSavedEvidenceGroups.contains("'<div class=\"ann-list\">'"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row saved-item-row"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-num\""))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-title\""))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-comment"))
        assertTrue(renderSavedEvidenceGroups.contains("class=\"ann-row-status"))
        assertTrue(renderSavedEvidenceGroups.contains("targetLabel(item)"))
        assertTrue(renderSavedEvidenceGroups.contains("firstLine(item.comment || 'No comment')"))
        assertFalse(renderSavedEvidenceGroups.contains("evidence-card"))
        assertFalse(renderSavedEvidenceGroups.contains("screenshot attached"))
        assertFalse(renderSavedEvidenceGroups.contains("sourceHintLabel(item)"))
    }

    @Test
    fun consoleHtmlRendersOptionACanvasToolbar() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("frame.dataset.mode = mode"))
        assertFalse(html.contains("navigationControls.hidden"))
        assertFalse(html.contains("aria-label=\"Back\""))
        assertFalse(html.contains("aria-label=\"Swipe up\""))
        assertTrue(html.contains("class=\"tool-group\""))
        assertTrue(html.contains("id=\"toolStatus\""))
        assertTrue(html.contains("class=\"ts-meta\""))
        assertTrue(html.contains("class=\"ts-hint\""))
        assertTrue(html.contains("sessionSummaries: []"))
        assertTrue(html.contains("function selectedHistorySummary()"))
        assertTrue(html.contains("function toolbarAnnotationCounts()"))
        assertTrue(html.contains("const summary = selectedHistorySummary();"))
        assertTrue(html.contains("open: historyOpenCount(summary)"))
        assertTrue(html.contains("resolved: historyDoneCount(summary)"))
        assertTrue(html.contains("state.sessionSummaries = sessionSummaries;"))
        assertTrue(html.contains("toolbarOpenCount()"))
        assertTrue(html.contains("toolbarResolvedCount()"))
        assertTrue(html.contains("Click a widget — or drag to draw a region"))
        assertTrue(html.contains("class=\"zoom-control\""))
        assertTrue(html.contains("id=\"zoomOutButton\""))
        assertTrue(html.contains("id=\"zoomPercent\""))
        assertTrue(html.contains("id=\"zoomInButton\""))
        assertTrue(html.contains("let previewZoom = 1"))
        assertTrue(html.contains("function applyPreviewZoom()"))
        assertTrue(html.contains("function setPreviewZoom(nextZoom)"))
        assertTrue(html.contains("frame.style.setProperty('--preview-zoom'"))
        assertTrue(html.contains("zoomOutButton.addEventListener('click'"))
        assertTrue(html.contains("zoomInButton.addEventListener('click'"))
        assertTrue(html.contains(".snapshot-frame::before"))
        assertTrue(html.contains("0 12px 24px -8px rgba(0, 0, 0, .4)"))
        assertTrue(html.contains("renderNumberedFeedbackOverlay"))
        assertTrue(html.contains("'#' + (index + 1)"))
    }

    @Test
    fun consoleHtmlCountsActivePendingAnnotationsInHistory() {
        val html = FeedbackConsoleAssets.indexHtml
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        val historyDoneCount = javascriptFunctionBody(html, "historyDoneCount")
        val historyPointsCount = javascriptFunctionBody(html, "historyPointsCount")
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")
        val deletePendingFeedbackItem = javascriptFunctionBody(html, "deletePendingFeedbackItem")
        val renderAnnotationDetail = javascriptFunctionBody(html, "renderAnnotationDetail")

        assertTrue(html.contains("function pendingHistoryItemsForSession(session)"))
        assertTrue(historyOpenCount.contains("pendingHistoryItemsForSession(session)"))
        assertTrue(historyOpenCount.contains("(session.unresolvedItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length"))
        assertTrue(historyDoneCount.contains("pending.filter(item => annotationStatus(item) === 'resolved').length"))
        assertTrue(historyPointsCount.contains("(session.itemsCount || 0) + pendingHistoryItemsForSession(session).length"))
        assertTrue(html.contains("function renderCurrentSessionList()"))
        assertTrue(createAnnotationFromSelection.contains("renderCurrentSessionList();"))
        assertTrue(deletePendingFeedbackItem.contains("renderCurrentSessionList();"))
        assertTrue(Regex("item\\.status = button\\.dataset\\.setStatus;[\\s\\S]*renderCurrentSessionList\\(\\);").containsMatchIn(renderAnnotationDetail))
    }

    @Test
    fun consoleHtmlLivePreviewImageUsesPreviewIdScopedScreenshotRoute() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function previewScreenshotUrl(previewId)"))
        assertTrue(html.contains("return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full';"))
        assertTrue(html.contains("const src = screenImageUrl(screen);"))
        assertFalse(html.contains("const src = addItemsFlow?.screenshotUrl || '/api/preview/screenshot/full'"))
    }

    @Test
    fun consoleHtmlRefreshPreviewReusesInFlightPreviewRequest() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let previewRequestInFlight = null"))
        assertTrue(html.contains("let previewRequestContextGeneration = 0"))
        assertTrue(html.contains("let previewRequestInFlightContextGeneration = null"))
        assertTrue(html.contains("function requestLivePreview()"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration === previewRequestContextGeneration"))
        assertTrue(html.contains("const requestContextGeneration = previewRequestContextGeneration;"))
        assertTrue(html.contains("const request = requestJson('/api/preview')"))
        assertTrue(html.contains("if (previewRequestInFlight === request) {"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration = null;"))
        assertTrue(html.contains("return previewRequestInFlight;"))
        assertTrue(html.contains("const preview = await requestLivePreview();"))
        assertFalse(html.contains("const preview = await requestJson('/api/preview');"))
    }

    @Test
    fun consoleHtmlInvalidatesPreviewContextOnDeviceAndSessionBoundaries() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function invalidatePreviewContext()"))
        assertTrue(html.contains("previewRequestGeneration++;"))
        assertTrue(html.contains("previewRequestContextGeneration++;"))
        assertTrue(html.contains("state.preview = null;"))
        assertTrue(html.contains("previewRequestInFlight = null;"))
        assertTrue(html.contains("previewRequestInFlightContextGeneration = null;"))
        assertTrue(Regex("async function selectDevice\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/device/select").containsMatchIn(html))
        assertTrue(Regex("async function disconnectDevice\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/device/disconnect").containsMatchIn(html))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function openSession\\(sessionId\\)[\\s\\S]*await refresh\\(\\);[\\s\\S]*if \\(!latestPersistedScreen\\(\\) && shouldAutoFetchPreview\\(\\)\\) \\{[\\s\\S]*await refreshPreview\\(\\);[\\s\\S]*\\}[\\s\\S]*startLivePreviewPolling\\(\\);").containsMatchIn(html))
        assertTrue(Regex("function latestScreen\\(\\) \\{\\s+return addItemsFlow\\?\\.screen \\|\\| latestPersistedScreen\\(\\) \\|\\| state\\.preview\\?\\.screen;\\s+\\}").containsMatchIn(html))
        assertTrue(Regex("async function newSession\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/open").containsMatchIn(html))
        assertTrue(Regex("async function closeSession\\(\\)[\\s\\S]*invalidatePreviewContext\\(\\);[\\s\\S]*/api/session/close").containsMatchIn(html))
        assertTrue(html.contains("const previousSelectedDeviceSerial = state.selectedDeviceSerial;"))
        assertTrue(html.contains("if (previousSelectedDeviceSerial !== selectedSerial) {"))
        assertFalse(html.contains("state.preview = null;\n              state.session = await requestJson('/api/session/open'"))
        assertFalse(html.contains("state.preview = null;\n              await refreshSessions();"))
    }

    @Test
    fun consoleHtmlAddFlowStopsPollingAndFreezesPreviewIdScopedScreenshot() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("async function startAddItemsFlow()"))
        assertTrue(html.contains("let addItemsFlowStarting = false;"))
        assertTrue(html.contains("if (addItemsFlowStarting) return;"))
        assertTrue(html.contains("addItemsFlowStarting = true;"))
        assertTrue(html.contains("addItemsFlowStarting = false;"))
        assertTrue(html.contains("stopLivePreviewPolling();"))
        assertTrue(html.contains("try {"))
        assertTrue(html.contains("const addFlowContextGeneration = previewRequestContextGeneration;"))
        assertTrue(html.contains("previewRequestGeneration++;"))
        assertTrue(html.contains("let preview = state.preview;"))
        assertTrue(html.contains("if (previewRequestInFlight || !preview) {"))
        assertTrue(html.contains("preview = await requestLivePreview();"))
        assertTrue(html.contains("if (addFlowContextGeneration !== previewRequestContextGeneration) return;"))
        assertTrue(html.contains("state.preview = preview;"))
        assertTrue(html.contains("if (!state.preview) {"))
        assertTrue(html.contains("previewId: state.preview.previewId"))
        assertTrue(html.contains("screenshotUrl: previewScreenshotUrl(state.preview.previewId)"))
        assertTrue(Regex("finally \\{\\s+addItemsFlowStarting = false;\\s+updateComposerState\\(\\);\\s+if \\(!addItemsFlow\\) startLivePreviewPolling\\(\\);\\s+\\}").containsMatchIn(html))
        assertTrue(html.contains("if (addItemsFlowStarting) {"))
        assertTrue(html.contains("event.preventDefault();"))
        assertTrue(html.contains("annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));"))
    }

    @Test
    fun consoleHtmlClearsSavedPreviewAndDoesNotAutoFetchWhenManual() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("state.preview = null;"))
        assertTrue(html.contains("function shouldAutoFetchPreview()"))
        assertTrue(html.contains("return configuredPreviewIntervalMs() != null && shouldPollPreview();"))
        assertTrue(html.contains("if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);"))
        assertTrue(html.contains("if (shouldAutoFetchPreview()) return refreshPreview();"))
        assertFalse(html.contains("if (!document.hidden && shouldPollPreview()) refreshPreview().catch(showError);"))
        assertFalse(html.contains("if (shouldPollPreview()) return refreshPreview();"))
    }

    @Test
    fun consoleHtmlFocusesPendingItemWithoutDrawingUnnumberedSelectionOverlay() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function focusedPendingSelectionSummary()"))
        assertTrue(html.contains("focusedPendingItemIndex != null"))
        assertTrue(html.contains("const item = focusedPendingSelectionSummary();"))
        assertTrue(html.contains("focusedPendingItemIndex = index;"))
        assertTrue(html.contains("currentSelection = null;"))
        assertFalse(html.contains("const item = pendingFeedbackItems[index];\n              currentSelection = item ?"))
        assertFalse(html.contains("label: item.targetType === 'node' ? 'Selected component' : 'Custom area'"))
    }

    @Test
    fun consoleHtmlImplementsSnapshotSelectionModes() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("let addItemsFlow"))
        assertTrue(html.contains("let pendingFeedbackItems"))
        assertTrue(html.contains("let currentSelection"))
        assertTrue(html.contains("finishAreaSelection"))
        assertTrue(html.contains("selectNodeAtPoint"))
        assertTrue(html.contains("nodesForHitTest"))
        assertTrue(html.contains("function nodesForHitTest(screen, nodesSelector)"))
        assertTrue(html.contains("function smallestContainingNode(nodes, point)"))
        assertTrue(html.contains("function hitTestNodes(screen)"))
        assertTrue(html.contains("let hoveredAnnotationTarget = null"))
        assertTrue(html.contains("function previewNodeAtPoint(event, image)"))
        assertTrue(html.contains("function confirmHoveredAnnotationTarget(event, image)"))
        assertTrue(html.contains("...(root?.mergedNodes || [])"))
        assertTrue(html.contains("...(root?.unmergedNodes || [])"))
        assertTrue(html.contains("const node = smallestContainingNode(hitTestNodes(screen), point);"))
        assertFalse(html.contains("const node = mergedNode || unmergedNode;"))
        assertTrue(html.contains("const seenNodeIds = new Set();"))
        assertTrue(html.contains("if (!node || !node.boundsInWindow) return;"))
        assertTrue(html.contains("unmergedNodes"))
        assertTrue(html.contains("if (!raw || raw.startsWith('compose:')) return '';"))
        assertTrue(html.contains("const label = firstMeaningful([...(node.text || []), node.editableText, ...(node.contentDescription || [])]);"))
        assertTrue(html.contains("if (role && label) return humanize(role) + ' \"' + label + '\"';"))
        assertTrue(html.contains("if (bounds) return 'Component ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top);"))
        assertFalse(html.contains("const textValue = (node.text || [])[0] || (node.contentDescription || [])[0] || node.uid;"))
        assertTrue(html.contains("renderSelectionOverlay"))
        assertTrue(html.contains("dragPreview"))
        assertTrue(html.contains("hover-preview"))
        assertTrue(html.contains("pointermove"))
        assertTrue(html.contains("image.addEventListener('pointerleave', clearHoverPreview)"))
        assertTrue(html.contains("function clearDragState()"))
        assertTrue(html.contains("function clearHoverPreview()"))
        assertTrue(html.contains("if (!dragStart) {"))
        assertTrue(html.contains("previewNodeAtPoint(event, image);"))
        assertTrue(html.contains("confirmHoveredAnnotationTarget(event, image);"))
        assertTrue(html.contains("image.draggable = false"))
        assertTrue(html.contains("image.addEventListener('dragstart', event => event.preventDefault())"))
        assertTrue(html.contains("image.setPointerCapture?.(event.pointerId)"))
        assertTrue(html.contains("image.releasePointerCapture?.(event.pointerId)"))
        assertTrue(html.contains("image.addEventListener('pointercancel', clearDragState)"))
        assertTrue(html.contains("image.addEventListener('lostpointercapture', clearDragState)"))
        assertTrue(html.contains("function clamp(value, min, max)"))
        assertTrue(html.contains("naturalPointFromEvent"))
        assertTrue(html.contains("targetType"))
        assertTrue(html.contains("nodeUid"))
        assertTrue(html.contains("id=\"snapshotImage\""))
        assertTrue(html.contains("function updateComposerState"))
        assertTrue(html.contains("navigate('tap'"))
    }

    @Test
    fun consoleHtmlReportsNavigationCaptureErrors() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("navigation.captureError"))
        assertTrue(html.contains("Navigation performed, but capture failed:"))
    }

    @Test
    fun consoleHtmlAnnotationSaveUsesCurrentSelectionPayload() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("previewId: addItemsFlow.previewId"))
        assertTrue(html.contains("items: pendingPayloadItems({ allowFallbackComments: allowFallbackComments, onlyWrittenComments: onlyWrittenComments, allowBlankComments: allowBlankComments })"))
        assertTrue(html.contains("targetType: selection.targetType"))
        assertTrue(html.contains("nodeUid: selection.nodeUid"))
        assertTrue(html.contains("bounds: selection.bounds"))
        assertTrue(html.contains("function pendingPayloadItems"))
        assertTrue(html.contains("function persistPendingFeedbackItems"))
        assertTrue(html.contains("toolMode = 'select';"))
        assertTrue(html.contains("suppressNextClick = true;"))
        assertTrue(html.contains("function updateSelectedAnnotationComment"))
        assertTrue(html.contains("item.comment = comment.value;"))
        assertTrue(html.contains("Add a comment to every annotation before saving."))
        assertTrue(html.contains("Select a component or area first."))
    }

    @Test
    fun rejectsUnsupportedMethods() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            assertEquals(405, connection.responseCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewRouteDoesNotAppendSessionScreens() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val before = pointPatchJson.parseToJsonElement(URL("${server.url}/api/session").readText()).jsonObject

            val preview = pointPatchJson.parseToJsonElement(URL("${server.url}/api/preview").readText()).jsonObject
            val after = pointPatchJson.parseToJsonElement(URL("${server.url}/api/session").readText()).jsonObject

            assertTrue(preview.containsKey("screen"))
            assertTrue(before.getValue("screens").jsonArray.isEmpty())
            assertTrue(after.getValue("screens").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun savingDraftItemsAppendsOneScreenAndTwoItems() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1", "item-2").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val preview = pointPatchJson.parseToJsonElement(URL("${server.url}/api/preview").readText()).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = URL("${server.url}/api/items/batch").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            },
                            {
                              "targetType": "area",
                              "bounds": {"left":120.0,"top":200.0,"right":260.0,"bottom":280.0},
                              "comment": "Add margin"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(200, connection.responseCode)
                val session = pointPatchJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
                assertEquals(1, session.getValue("screens").jsonArray.size)
                val items = session.getValue("items").jsonArray.map { it.jsonObject }
                assertEquals(2, items.size)
                assertEquals(listOf("Change headline", "Add margin"), items.map { it.getValue("comment").jsonPrimitive.content })
                assertEquals(
                    listOf("preview-screen-1", "preview-screen-1"),
                    items.map { it.getValue("screenId").jsonPrimitive.content },
                )
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-blank-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val preview = pointPatchJson.parseToJsonElement(URL("${server.url}/api/preview").readText()).jsonObject
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = URL("${server.url}/api/items/batch").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "items": [
                            {
                              "targetType": "area",
                              "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": ""
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(200, connection.responseCode)
                val session = pointPatchJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
                val item = session.getValue("items").jsonArray.single().jsonObject
                assertEquals("", item.getValue("comment").jsonPrimitive.content)
                assertEquals("open", item.getValue("status").jsonPrimitive.content)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForEmptyItemList() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items/batch").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"previewId":"preview-1","items":[]}""".toByteArray()) }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("At least one feedback item is required"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchItemsApiReturnsNotFoundForUnknownPreviewId() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items/batch").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "previewId": "missing-preview",
                      "items": [
                        {
                          "targetType": "area",
                          "bounds": {"left":10.0,"top":20.0,"right":110.0,"bottom":80.0},
                          "comment": "Change headline"
                        }
                      ]
                    }
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("PREVIEW_NOT_FOUND"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun previewSaveInProgressMapsToConflict() {
        val method = Class
            .forName("io.github.pointpatch.mcp.console.FeedbackConsoleServerKt")
            .getDeclaredMethod("toConsoleHttpException", FeedbackSessionException::class.java)
        method.isAccessible = true

        val httpError = method.invoke(
            null,
            FeedbackSessionException("PREVIEW_SAVE_IN_PROGRESS: Preview is already being saved: preview-1"),
        )

        val statusCode = httpError.javaClass.getDeclaredField("statusCode")
        statusCode.isAccessible = true
        assertEquals(409, statusCode.get(httpError))
    }

    @Test
    fun previewScreenshotRouteServesLatestPreviewPng() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console-preview").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                URL("${server.url}/api/preview").readText()

                val connection = URL("${server.url}/api/preview/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewIdScreenshotRouteServesExactPreviewPngInsteadOfLatest() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console-preview-exact").toFile()
        try {
            val firstPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x01)
            val secondPng = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x02)
            val service = FeedbackSessionService(
                bridge = SequencedSessionScreenshotBridge(firstPng, secondPng),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "preview-2",
                        "preview-screen-2",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val firstPreview = pointPatchJson.parseToJsonElement(URL("${server.url}/api/preview").readText()).jsonObject
                val secondPreview = pointPatchJson.parseToJsonElement(URL("${server.url}/api/preview").readText()).jsonObject
                val firstPreviewId = firstPreview.getValue("previewId").jsonPrimitive.content
                val secondPreviewId = secondPreview.getValue("previewId").jsonPrimitive.content

                val firstConnection = URL("${server.url}/api/preview/$firstPreviewId/screenshot/full").openConnection() as HttpURLConnection
                val secondConnection = URL("${server.url}/api/preview/$secondPreviewId/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, firstConnection.responseCode)
                assertEquals("image/png", firstConnection.contentType)
                assertTrue(firstConnection.inputStream.use { it.readBytes() }.contentEquals(firstPng))
                assertEquals(200, secondConnection.responseCode)
                assertEquals("image/png", secondConnection.contentType)
                assertTrue(secondConnection.inputStream.use { it.readBytes() }.contentEquals(secondPng))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewScreenshotRouteRejectsPersistedScreenshotsOutsidePointPatchRoots() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-preview-safe").toFile()
        val outsideArtifact = Files.createTempFile("pointpatch-outside", ".png").toFile()
        try {
            outsideArtifact.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47))
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.addCapturedScreenForTest(
                session.sessionId,
                CapturedScreen(
                    screenId = "screen-1",
                    capturedAtEpochMillis = 100L,
                    displayName = "Unsafe",
                    screenshot = FeedbackScreenshot(desktopFullPath = outsideArtifact.absolutePath),
                ),
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/preview/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(404, connection.responseCode)
            } finally {
                server.stop()
            }
        } finally {
            outsideArtifact.delete()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun devicesApiListsAndSelectsActiveDevice() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val devices = URL("${server.url}/api/devices").readText()
            assertTrue(devices.contains("SM_G986N"))
            assertTrue(devices.contains("adb-R3CN60LXW3L"))

            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
            select.requestMethod = "POST"
            select.doOutput = true
            select.setRequestProperty("Content-Type", "application/json")
            select.outputStream.use { it.write("""{"serial":"adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp"}""".toByteArray()) }

            assertEquals(200, select.responseCode)
            assertEquals("adb-R3CN60LXW3L-cuwm3G._adb-tls-connect._tcp", bridge.selectedDeviceSerial)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiSendsDraftAndClearsDraftList() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(0f, 0f, 10f, 10f), "Fix it")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val handoff = URL("${server.url}/api/agent-handoffs").openConnection() as HttpURLConnection
            handoff.requestMethod = "POST"
            handoff.doOutput = true
            handoff.setRequestProperty("Content-Type", "application/json")
            handoff.outputStream.use { it.write("""{"prompt":"custom agent prompt"}""".toByteArray()) }

            assertEquals(200, handoff.responseCode)
            val body = handoff.inputStream.bufferedReader().readText()
            val response = pointPatchJson.parseToJsonElement(body).jsonObject
            assertTrue(response["handoffBatches"]?.jsonArray.orEmpty().isNotEmpty())
            assertEquals("sent", response["items"]?.jsonArray?.single()?.jsonObject?.get("delivery")?.jsonPrimitive?.content)
            assertEquals("custom agent prompt", response["handoffBatches"]?.jsonArray?.single()?.jsonObject?.get("markdownSnapshot")?.jsonPrimitive?.content)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiReturnsConflictWhenNoDraftItemsExist() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val handoff = URL("${server.url}/api/agent-handoffs").openConnection() as HttpURLConnection
            handoff.requestMethod = "POST"
            handoff.doOutput = true
            handoff.setRequestProperty("Content-Type", "application/json")
            handoff.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(409, handoff.responseCode)
            assertTrue(handoff.errorStream.bufferedReader().readText().contains("NO_DRAFT_FEEDBACK"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun clearDraftApiKeepsSentItems() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1", "item-2").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(0f, 0f, 10f, 10f), "Sent")
        service.sendDraftToAgent(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, PointPatchRect(10f, 10f, 20f, 20f), "Draft")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val clear = URL("${server.url}/api/items/draft").openConnection() as HttpURLConnection
            clear.requestMethod = "DELETE"

            assertEquals(200, clear.responseCode)
            val body = clear.inputStream.bufferedReader().readText()
            val comments = pointPatchJson.parseToJsonElement(body).jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject.getValue("comment").jsonPrimitive.content }
            assertEquals(listOf("Sent"), comments)
        } finally {
            server.stop()
        }
    }

    @Test
    fun deviceSelectApiReturnsBadRequestForBlankSerial() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
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
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
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
        val service = FeedbackSessionService(bridge, FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val select = URL("${server.url}/api/device/select").openConnection() as HttpURLConnection
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

    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val node = PointPatchNode(
            uid = "compose:0:merged:10",
            composeNodeId = 10,
            rootIndex = 0,
            treeKind = TreeKind.MERGED,
            boundsInWindow = PointPatchRect(10f, 20f, 110f, 70f),
            text = listOf("Pay now"),
        )
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                roots = listOf(FeedbackScreenRoot(0, PointPatchRect(0f, 0f, 720f, 1600f), mergedNodes = listOf(node))),
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"node","nodeUid":"${node.uid}","bounds":{"left":200.0,"top":300.0,"right":260.0,"bottom":340.0},"comment":"Button copy is unclear"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(200, connection.responseCode)
            val item = pointPatchJson.decodeFromString(FeedbackItem.serializer(), connection.inputStream.bufferedReader().readText())
            assertEquals(FeedbackTarget.Node(node.uid, node.boundsInWindow), item.target)
            assertEquals(node, item.selectedNode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnknownScreenId() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"missing-screen","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad screen"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("SCREEN_NOT_FOUND"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnsupportedFields() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad field","screenID":"typo"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported feedback item field"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForInvalidAreaBounds() {
        val service = FeedbackSessionService(
            bridge = FakePointPatchBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            CapturedScreen(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = FeedbackScreenshot(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/items").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {"screenId":"${screen.screenId}","targetType":"area","bounds":{"left":-1.0,"top":0.0,"right":10.0,"bottom":10.0},"comment":"Bad bounds"}
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(400, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiListsWorkspaces() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions").readText()

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val matching = service.openSession("io.github.pointpatch.sample", newSession = true)
        val other = service.openSession("io.github.pointpatch.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = URL("${server.url}/api/sessions?packageName=io.github.pointpatch.sample").readText()

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"${first.sessionId}"}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            assertTrue(connection.inputStream.bufferedReader().readText().contains(first.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/open").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionApiReturnsServerErrorForSessionSaveFailure() {
        val projectRoot = Files.createTempDirectory("pointpatch-console-save-fail").toFile()
        try {
            projectRoot.resolve(".pointpatch").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakePointPatchBridge(),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }, persistence = persistence),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/session").openConnection() as HttpURLConnection

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("SESSION_SAVE_FAILED:"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun closeSessionApiClosesCurrentSession() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("{}".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertTrue(response.contains(session.sessionId))
            assertTrue(response.contains("closed"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun closeSessionApiReturnsNotFoundForUnknownSessionId() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/session/close").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"sessionId":"missing-session"}""".toByteArray()) }

            assertEquals(404, connection.responseCode)
            assertTrue(connection.errorStream.bufferedReader().readText().contains("Unknown feedback session"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiPerformsAction() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, pointPatchJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        val bridge = FakePointPatchBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val payloads = listOf(
                """{"action":"back","sequence":[]}""",
                """{"action":"back","script":"adb shell input keyevent BACK"}""",
            )

            payloads.forEach { payload ->
                val connection = URL("${server.url}/api/navigation").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toByteArray()) }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Unsupported navigation field"))
            }
            assertTrue(bridge.navigationRequests.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun startUrlUsesConfiguredHostAndBoundPort() {
        val service = FeedbackSessionService(FakePointPatchBridge(), FeedbackSessionStore(), "/repo", "io.github.pointpatch.sample")
        val server = FeedbackConsoleServer(service = service, host = "127.0.0.1", port = 0)
        val url = server.start()
        try {
            assertTrue(url.startsWith("http://127.0.0.1:"))
            assertEquals(url, server.url)
            assertTrue(URL(url).readText().contains("PointPatch Feedback Console"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesSessionOwnedScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            val service = FeedbackSessionService(
                bridge = SessionScreenshotBridge(pngBytes),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/screens/screen-1/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun servesLegacyScreenshotArtifactFromCurrentSession() = runBlocking {
        val projectRoot = Files.createTempDirectory("pointpatch-console").toFile()
        try {
            val artifact = projectRoot.resolve(".pointpatch/artifacts/screen-1/full.png")
            artifact.parentFile.mkdirs()
            val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
            artifact.writeBytes(pngBytes)
            val service = FeedbackSessionService(
                bridge = LegacyScreenshotBridge(artifact),
                store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1").next),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.github.pointpatch.sample",
            )
            val session = service.openSession(null)
            service.captureScreen(session.sessionId)
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = URL("${server.url}/api/screens/screen-1/screenshot/full").openConnection() as HttpURLConnection

                assertEquals(200, connection.responseCode)
                assertEquals("image/png", connection.contentType)
                assertTrue(connection.inputStream.use { it.readBytes() }.contentEquals(pngBytes))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun deleteScreenApiDeletesScreenAndLinkedItems() {
        val service = FeedbackSessionService(
            FakePointPatchBridge(),
            FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            "/repo",
            "io.github.pointpatch.sample",
        )
        val session = service.openSession(null)
        service.addCapturedScreenForTest(session.sessionId, CapturedScreen("screen-1", 0L, displayName = "Main"))
        service.addAreaFeedback(session.sessionId, "screen-1", PointPatchRect(0f, 0f, 10f, 10f), "Remove me")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = URL("${server.url}/api/screens/screen-1").openConnection() as HttpURLConnection
            connection.requestMethod = "DELETE"

            assertEquals(200, connection.responseCode)
            val payload = pointPatchJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertTrue(payload.getValue("screens").jsonArray.isEmpty())
            assertTrue(payload.getValue("items").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    private class FakeIds(vararg values: String) {
        private val queue = ArrayDeque(values.toList())
        val next: () -> String = { queue.removeFirst() }
    }

    private fun FeedbackSessionService.captureFakeScreenForTest(sessionId: String): CapturedScreen =
        runBlocking { captureScreen(sessionId) }

    private fun FeedbackSessionService.addCapturedScreenForTest(sessionId: String, screen: CapturedScreen): CapturedScreen =
        javaClass.getDeclaredField("store").let { field ->
            field.isAccessible = true
            (field.get(this) as FeedbackSessionStore).addScreen(sessionId, screen)
        }

    private class DeviceListBridge(private val devices: List<AdbDevice>) : PointPatchBridge {
        var selectedDeviceSerial: String? = null
            private set

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override fun devices(): List<AdbDevice> = devices

        override fun selectedDeviceSerial(): String? = selectedDeviceSerial

        override fun selectDevice(serial: String) {
            selectedDeviceSerial = serial
        }

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = JsonObject(emptyMap())
    }

    private class SessionScreenshotBridge(private val pngBytes: ByteArray) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(pngBytes)
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }

    private class SequencedSessionScreenshotBridge(vararg pngBytes: ByteArray) : PointPatchBridge {
        private val queue = ArrayDeque(pngBytes.toList())

        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            val artifact = requireNotNull(destinationDirectory)
                .resolve("${requireNotNull(screenId)}-full.png")
            artifact.parentFile.mkdirs()
            artifact.writeBytes(queue.removeFirst())
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }

    private class LegacyScreenshotBridge(private val artifact: File) : PointPatchBridge {
        override fun resolvePackageName(packageOverride: String?): String =
            packageOverride ?: "io.github.pointpatch.sample"

        override suspend fun status(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun inspectCurrentScreen(packageName: String): JsonObject = JsonObject(emptyMap())

        override suspend fun startFeedbackCapture(packageName: String, timeoutMillis: Long): JsonObject = JsonObject(emptyMap())

        override suspend fun verifyUiChange(packageName: String, expectedText: String, role: String?): JsonObject =
            JsonObject(emptyMap())

        override suspend fun captureScreenSnapshot(
            packageName: String,
            sessionId: String?,
            screenId: String?,
            destinationDirectory: File?,
        ): JsonObject = buildJsonObject {
            put("activity", "MainActivity")
            put("sourceIndexAvailable", true)
            put("inspection", buildJsonObject {
                put("activity", "MainActivity")
                put("roots", JsonArray(emptyList()))
                put("errors", JsonArray(emptyList()))
            })
            put("screenshot", buildJsonObject {
                put("desktopFullPath", artifact.absolutePath)
            })
        }
    }
}
