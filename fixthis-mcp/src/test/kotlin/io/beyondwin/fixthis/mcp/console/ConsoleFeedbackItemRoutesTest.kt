package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.cli.fixThisJson
import io.beyondwin.fixthis.compose.core.model.FixThisNode
import io.beyondwin.fixthis.compose.core.model.FixThisRect
import io.beyondwin.fixthis.compose.core.model.TreeKind
import io.beyondwin.fixthis.mcp.fixtures.ConsoleHttpTestClient
import io.beyondwin.fixthis.mcp.fixtures.FakeIds
import io.beyondwin.fixthis.mcp.fixtures.FakeLongs
import io.beyondwin.fixthis.mcp.fixtures.NullableSequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.SecondCaptureIllegalArgumentBridge
import io.beyondwin.fixthis.mcp.fixtures.SequencedFingerprintBridge
import io.beyondwin.fixthis.mcp.fixtures.addCapturedScreenForTest
import io.beyondwin.fixthis.mcp.fixtures.captureFakeScreenForTest
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import io.beyondwin.fixthis.mcp.fixtures.seedSessionWithOneItem
import io.beyondwin.fixthis.mcp.session.AnnotationDto
import io.beyondwin.fixthis.mcp.session.AnnotationStatusDto
import io.beyondwin.fixthis.mcp.session.AnnotationTargetDto
import io.beyondwin.fixthis.mcp.session.FakeFixThisBridge
import io.beyondwin.fixthis.mcp.session.FeedbackDelivery
import io.beyondwin.fixthis.mcp.session.FeedbackNavigationAction
import io.beyondwin.fixthis.mcp.session.FeedbackSessionException
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPaths
import io.beyondwin.fixthis.mcp.session.FeedbackSessionPersistence
import io.beyondwin.fixthis.mcp.session.FeedbackSessionService
import io.beyondwin.fixthis.mcp.session.FeedbackSessionStore
import io.beyondwin.fixthis.mcp.session.SnapshotDto
import io.beyondwin.fixthis.mcp.session.SnapshotRootDto
import io.beyondwin.fixthis.mcp.session.SnapshotScreenshotDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ConsoleHttpTestClient.getJsonObject(path: String): JsonObject = fixThisJson
    .parseToJsonElement(get(path))
    .jsonObject

private fun HttpURLConnection.inputJsonObject(): JsonObject = fixThisJson
    .parseToJsonElement(inputStream.bufferedReader().readText())
    .jsonObject

class ConsoleFeedbackItemRoutesTest {
    @Test
    fun itemPatchUpdatesDraftAnnotation() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L).next,
            idGenerator = FakeIds("session-1", "item-1").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        store.addScreen(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        store.addItem(
            session.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"comment":"After","status":"in_progress"}""",
            )

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            val item = payload.getValue("items").jsonArray.single().jsonObject
            assertEquals("After", item.getValue("comment").jsonPrimitive.content)
            assertEquals("in_progress", item.getValue("status").jsonPrimitive.content)
            assertEquals("After", service.getSession("session-1").items.single().comment)
            assertEquals(AnnotationStatusDto.IN_PROGRESS, service.getSession("session-1").items.single().status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemPatchUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
            idGenerator = FakeIds("session-1", "item-1", "session-2").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session1 = service.openSession(null, newSession = true)
        store.addScreen(
            session1.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Screen 1",
            ),
        )
        store.addItem(
            session1.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "Before",
            ),
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection(
                "/api/items/item-1",
                method = "PUT",
                body = """{"sessionId":"session-1","comment":"After"}""",
            )

            assertEquals(200, connection.responseCode)
            assertEquals("After", service.getSession("session-1").items.single().comment)
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchSaveUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L).next,
            idGenerator = FakeIds("session-a", "preview-a", "preview-screen-a", "session-b", "item-a").next,
        )
        val service = FeedbackSessionService(
            bridge = SequencedFingerprintBridge("fp-a", "fp-a"),
            store = store,
            projectRoot = Files.createTempDirectory("fixthis-session-scope").toString(),
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        val preview = runBlocking { service.capturePreview(sessionA.sessionId) }
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = """
                {
                  "sessionId": "${sessionA.sessionId}",
                  "previewId": "${preview.previewId}",
                  "frozenFingerprint": "fp-a",
                  "items": [{
                    "targetType": "area",
                    "bounds": {"left":1.0,"top":2.0,"right":30.0,"bottom":40.0},
                    "comment": "save into session A"
                  }]
                }
            """.trimIndent()
            val response = ConsoleHttpTestClient(server.url).postJson("/api/items/batch", body)

            assertEquals(200, response.statusCode)
            assertEquals(1, service.getSession(sessionA.sessionId).items.size)
            assertEquals("save into session A", service.getSession(sessionA.sessionId).items.single().comment)
            assertTrue(service.requireCurrentSession().sessionId != sessionA.sessionId)
            assertTrue(service.requireCurrentSession().items.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffUsesRequestedSessionWhenCurrentSessionChanged() {
        val store = FeedbackSessionStore(
            clock = FakeLongs(100L, 200L, 300L, 400L, 500L, 600L, 700L).next,
            idGenerator = FakeIds("session-a", "screen-a", "item-a", "session-b", "handoff-a").next,
        )
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val sessionA = service.openSession(null, newSession = true)
        store.addScreen(sessionA.sessionId, SnapshotDto("screen-a", 100L, displayName = "A"))
        val item = store.addItem(
            sessionA.sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-a",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(1f, 2f, 3f, 4f)),
                comment = "handoff A",
            ),
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = """{"sessionId":"${sessionA.sessionId}","itemIds":["${item.itemId}"]}"""
            val response = ConsoleHttpTestClient(server.url).postJson("/api/agent-handoffs", body)

            assertEquals(200, response.statusCode)
            assertEquals(FeedbackDelivery.SENT, service.getSession(sessionA.sessionId).items.single().delivery)
            assertTrue(service.requireCurrentSession().items.isEmpty())
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
    fun consoleHtmlUsesBrowserStudioLayout() {
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

        assertTrue(html.contains("@media (max-width: 900px)"))
        assertTrue(Regex("\\.studio-body \\{\\s+grid-template-columns: 1fr;").containsMatchIn(html))
        assertTrue(html.contains("id=\"historyToggleButton\""))
        assertTrue(html.contains("id=\"historyDrawerScrim\""))
        assertTrue(html.contains("aria-controls=\"historyPanel\""))
        assertTrue(Regex("\\.studio-history \\{\\s+position: fixed;").containsMatchIn(html))
        assertTrue(html.contains("body[data-history-drawer-open=\"true\"] .studio-history"))
        assertTrue(Regex("\\.studio-inspector \\{\\s+min-height: 280px;").containsMatchIn(html))
        assertTrue(Regex("\\.snapshot-stage \\{\\s+min-height: 360px;").containsMatchIn(html))
        assertFalse(html.contains("Resize to >= 900px wide"))
        assertFalse(html.contains(".studio-shell::before"))
    }

    @Test
    fun consoleHtmlUsesModeAwareStudioInspector() {
        val html = FeedbackConsoleAssets.indexHtml
        val pendingRenderer = javascriptFunctionBody(html, "renderPendingItems")
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(html.contains("function renderComposerInspector"))
        assertTrue(html.contains("function renderSavedAnnotationsInspector"))
        assertTrue(html.contains("function renderAnnotationDetail"))
        assertTrue(html.contains("function renderSavedAnnotationDetail"))
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
        assertTrue(createAnnotationFromSelection.contains("addDraftItem(draftWorkspace, selection, ports)"))
        assertTrue(createAnnotationFromSelection.contains("setDraftWorkspace(nextWorkspace);"))
        assertFalse(pendingRenderer.contains("data-delete-pending"))
        assertTrue(renderSavedEvidenceGroups.contains("data-focus-saved"))
        assertTrue(html.contains("grid-template-columns: 28px minmax(0, 1fr) auto;"))
        assertTrue(Regex("\\.ann-row-body \\{\\s+min-width: 0;\\s+overflow: hidden;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-title \\{\\s+display: block;\\s+max-width: 100%;").containsMatchIn(html))
        assertTrue(Regex("\\.ann-row-status \\{\\s+justify-self: end;").containsMatchIn(html))
        assertTrue(pendingRenderer.contains("style=\"--annotation-color:"))
        assertTrue(
            html.contains(
                "renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false, " +
                    "index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)))",
            ),
        )
        assertTrue(html.contains("focusSavedEvidenceItem(item.itemId)"))
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
    fun consoleHtmlEditsSelectedAnnotationsAndFocusesComment() {
        val html = FeedbackConsoleAssets.indexHtml
        val toolbarAnnotationCounts = javascriptFunctionBody(html, "toolbarAnnotationCounts")
        val focusCommentInputAtEnd = javascriptFunctionBody(html, "focusCommentInputAtEnd")
        val renderAnnotationDetail = javascriptFunctionBody(html, "renderAnnotationDetail")
        val renderSavedAnnotationDetail = javascriptFunctionBody(html, "renderSavedAnnotationDetail")
        val persistSavedEvidenceItem = javascriptFunctionBody(html, "persistSavedEvidenceItem")

        assertTrue(toolbarAnnotationCounts.contains("const annotations = toolbarAnnotations();"))
        assertFalse(toolbarAnnotationCounts.contains("const summary = selectedHistorySummary();"))
        assertTrue(focusCommentInputAtEnd.contains("commentInput.focus();"))
        assertTrue(renderAnnotationDetail.contains("focusCommentInputAtEnd(commentInput);"))
        assertTrue(renderSavedAnnotationDetail.contains("id=\"annotationCommentInput\""))
        assertFalse(renderSavedAnnotationDetail.contains("readonly"))
        assertTrue(
            renderSavedAnnotationDetail.contains(
                "const editSessionId = focusedSavedSessionId || state.session?.sessionId || null;",
            ),
        )
        assertTrue(renderSavedAnnotationDetail.contains("persistSavedEvidenceItem(item, editSessionId)"))
        assertTrue(persistSavedEvidenceItem.contains("requestJson('/api/items/' + encodeURIComponent(item.itemId)"))
        assertTrue(persistSavedEvidenceItem.contains("method: 'PUT'"))
        assertTrue(persistSavedEvidenceItem.contains("sessionId: sessionId"))
        assertTrue(renderSavedAnnotationDetail.contains("if (editable) focusCommentInputAtEnd(commentInput);"))
    }

    @Test
    fun consoleHtmlResetsAnnotationComposerStateAcrossSessionActions() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("function resetAnnotationComposerState(clearFlow = true, clearMirror = true)"))
        assertTrue(
            Regex("if \\(clearMirror\\) \\{[\\s\\S]*clearPendingMirror\\(state\\.session\\?\\.sessionId\\);")
                .containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function openSession\\(sessionId\\)[\\s\\S]*resetAnnotationComposerState\\(true, false\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function newSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function closeSession\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);" +
                    "[\\s\\S]*/api/session/close",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function deleteHistorySession\\(sessionId\\)[\\s\\S]*const isDisplayedSession = " +
                    "\\(\\) => state\\.session\\?\\.sessionId === sessionId;[\\s\\S]*const wasDisplayedSession = " +
                    "isDisplayedSession\\(\\);[\\s\\S]*if \\(wasDisplayedSession\\) \\{\\s+" +
                    "resetAnnotationComposerState\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function deleteHistorySession\\(sessionId\\)[\\s\\S]*if " +
                    "\\(wasDisplayedSession\\) \\{[\\s\\S]*state\\.session = null;[\\s\\S]*" +
                    "await refreshSessions\\(\\);\\s+render\\(\\);\\s+await refreshDevices\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "function cancelAddItemsFlow\\(\\)[\\s\\S]*resetAnnotationComposerState\\(\\);" +
                    "[\\s\\S]*render\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "function deletePendingFeedbackItem\\(index\\)[\\s\\S]*focusedPendingItemIndex = null;" +
                    "[\\s\\S]*currentSelection = null;[\\s\\S]*comment.value = '';",
            ).containsMatchIn(html),
        )
    }

    @Test
    fun consoleHtmlKeepsFixThisTopLevelActionsInStudioTopbar() {
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
        assertTrue(html.contains("<span>Save to MCP</span>"))
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
        assertTrue(html.contains("aria-label=\"FixThis preview\""))
    }

    @Test
    fun consoleHtmlDoesNotRenderSavedAnnotationPinsOnLivePreviewWithoutFocus() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSelectionOverlay = javascriptFunctionBody(html, "renderSelectionOverlay")

        assertTrue(
            renderSelectionOverlay.contains("if (!addItemsFlow && focusedSavedItemId)"),
            "saved overlays should be gated by focusedSavedItemId",
        )
        assertFalse(
            renderSelectionOverlay.contains("if (nodeUid) return visibleUids.has(nodeUid);"),
            "saved overlays must not infer screen identity from nodeUid on live preview",
        )
        assertTrue(
            renderSelectionOverlay.contains("item.screenId === focusedItem.screenId"),
            "focused saved overlay should include only items from the focused screen",
        )
    }

    @Test
    fun consoleHtmlRefreshesSessionSummariesAfterSavedItemDeleteOrEdit() {
        val html = FeedbackConsoleAssets.indexHtml
        val deleteSavedEvidenceItem = javascriptFunctionBody(html, "deleteSavedEvidenceItem")
        val applySavedSessionUpdate = javascriptFunctionBody(html, "applySavedSessionUpdate")

        // History sidebar pip counts (open / done / pts) read from state.sessionSummaries,
        // not state.session. Deleting a saved annotation or editing it (status change)
        // updates state.session in place but left sessionSummaries stale, so the active
        // card kept showing the old "1 open" badge after the panel had emptied. Both code
        // paths must call refreshSessions() so the summary cache is rehydrated.
        // Both functions previously already had refreshSessions() in the non-matching
        // (else) branch only. Adding it to the matching branch means the call must appear
        // twice in each body — once per branch.
        val deleteCount = Regex("refreshSessions\\(\\)").findAll(deleteSavedEvidenceItem).count()
        val applyCount = Regex("refreshSessions\\(\\)").findAll(applySavedSessionUpdate).count()
        assertEquals(2, deleteCount, "deleteSavedEvidenceItem should call refreshSessions() in both branches")
        assertEquals(2, applyCount, "applySavedSessionUpdate should call refreshSessions() in both branches")
    }

    @Test
    fun consoleHtmlReplacesPlaceholderYouLabelWithScreensCount() {
        val html = FeedbackConsoleAssets.indexHtml
        val formatSessionSummary = javascriptFunctionBody(html, "formatSessionSummary")

        // History cards previously showed "You · May 9 · 19:33" — meaningless on a
        // single-user tool. Replaced with the session's screensCount so users can scan
        // sessions by their actual size: e.g. "3 screens · May 9 · 19:33". Sessions
        // without any captured screen drop the prefix entirely instead of showing "0".
        assertFalse(formatSessionSummary.contains("'You · '"))
        assertTrue(formatSessionSummary.contains("session?.screensCount"))
        assertTrue(formatSessionSummary.contains("countLabel(screens, 'screen', 'screens')"))
        assertTrue(formatSessionSummary.contains("screens > 0"))
    }

    @Test
    fun consoleHtmlGroupsSavedAnnotationsByScreenInPanel() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        // The saved-annotations panel previously rendered a flat list of "1, 2, 3, 4" rows
        // even when items were spread across multiple captured screens, so users with more
        // than one screen could not tell which annotation belonged to which screen. The
        // renderer must now emit a "Screen N · HH:MM" header + separator at every screen
        // boundary, using each screen's capture order to pick the ordinal.
        assertTrue(
            html.contains("ann-screen-header"),
            "Bundle should reference the ann-screen-header marker",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("savedScreenOrdinalLookup()"),
            "renderSavedEvidenceGroups should derive a screen ordinal lookup",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("if (item.screenId !== prevScreenId)"),
            "renderSavedEvidenceGroups should compare adjacent items' screenId",
        )
        assertTrue(
            renderSavedEvidenceGroups.contains("savedScreenHeaderHtml(item, ordinalByScreenId, prevScreenId === null)"),
            "renderSavedEvidenceGroups should emit a header on every screen-id boundary",
        )
        assertTrue(
            html.contains("function savedScreenOrdinalLookup"),
            "savedScreenOrdinalLookup helper should be defined",
        )
        assertTrue(
            html.contains("function savedScreenHeaderHtml"),
            "savedScreenHeaderHtml helper should be defined",
        )
        assertTrue(
            html.contains(".ann-screen-header {"),
            "styles.css should style the new screen header",
        )
        assertTrue(
            html.contains(".ann-screen-header.first {"),
            "styles.css should suppress the divider above the first screen header",
        )
    }

    @Test
    fun consoleHtmlComposerInspectorAlsoShowsSavedAnnotations() {
        val html = FeedbackConsoleAssets.indexHtml
        val renderComposerInspector = javascriptFunctionBody(html, "renderComposerInspector")

        // While `addItemsFlow` is active the composer inspector previously hid every saved
        // annotation, so users adding a new pin to a session that already had four saved
        // items only saw the single pending entry. The composer must surface saved
        // annotations as well so totals stay coherent across pending + saved.
        assertTrue(
            renderComposerInspector.contains("const savedItems = savedEvidenceItems();"),
            "renderComposerInspector should resolve saved items",
        )
        assertTrue(
            renderComposerInspector.contains(
                "inspectorCount.textContent = String(pendingFeedbackItems.length + savedItems.length);",
            ),
            "renderComposerInspector inspector count should include saved items",
        )
        assertTrue(
            renderComposerInspector.contains("draftItems.hidden = savedItems.length === 0;"),
            "renderComposerInspector should show the saved-items section when savedItems exist",
        )
        assertTrue(
            renderComposerInspector.contains("if (savedItems.length) renderSavedEvidenceGroups();"),
            "renderComposerInspector should populate the saved-items list",
        )
    }

    @Test
    fun consoleHtmlNoLongerFiltersSentItemsFromInspector() {
        val html = FeedbackConsoleAssets.indexHtml
        // Narrow scope: latestPersistedScreen() must include SENT items.
        // The send-path filter inside currentPromptAnnotations() is intentional and stays.
        val latestPersistedScreenBody = javascriptFunctionBody(html, "latestPersistedScreen")
        assertFalse(
            latestPersistedScreenBody.contains("delivery !== 'sent'"),
            "latestPersistedScreen must show SENT items too",
        )
    }
}

class ConsoleFeedbackItemWorkspaceRoutesTest {
    @Test
    @Suppress("LongMethod")
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
        assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
        assertTrue(html.contains("title=\"Clear FixThis device selection\""))
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
        assertFalse(html.contains("id=\"sentHistory\""))
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
        assertTrue(html.contains("sendAgentButton.disabled = promptDisabled;"))
        assertTrue(html.contains("formatSessionLabel"))
        assertTrue(html.contains("formatSessionSummary"))
        assertTrue(html.contains("historyOpenCount"))
        assertTrue(html.contains("historyDoneCount"))
        assertTrue(html.contains("renderHistoryStrip"))
        assertTrue(html.contains("formatItemLabel"))
        assertTrue(html.contains("function findScreen"))
        assertTrue(html.contains("function targetLabel"))
        assertTrue(html.contains("function sourceHintLabel"))
        assertTrue(html.contains("function escapeHtmlValue(value)"))
        assertTrue(html.contains("escapeHtmlValue(item.comment)"))
        assertFalse(html.contains("id=\"modeSelect\""))
        assertFalse(html.contains("id=\"modeNavigate\""))
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
    @Suppress("LongMethod")
    fun consoleHtmlRendersStudioSessionHistoryWithoutInternalIds() {
        val html = FeedbackConsoleAssets.indexHtml
        val formatSessionLabel = javascriptFunctionBody(html, "formatSessionLabel")

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
        assertTrue(html.contains("class=\"hi-strip-cell"))
        assertTrue(html.contains("data-delete-session-id"))
        assertTrue(html.contains("async function deleteHistorySession(sessionId)"))
        assertTrue(html.contains("event.stopPropagation();"))
        assertTrue(html.contains("row.addEventListener('keydown'"))
        assertTrue(html.contains("row.classList.toggle('is-active'"))
        assertTrue(Regex("\\.history-list \\{\\s+align-content: start;").containsMatchIn(html))
        assertFalse(html.contains("class=\"sent-history-drawer\""))
        assertTrue(html.contains("formatSessionSummary(session)"))
        assertTrue(html.contains("function sessionOrdinalLookup(sessions)"))
        assertTrue(html.contains("createdAtEpochMillis || 0"))
        assertTrue(html.contains("ordinalBySessionId.set(session.sessionId, index + 1);"))
        assertTrue(html.contains("function stableHistorySessions(sessions)"))
        assertTrue(html.contains("const renderedActiveSummaries = stableHistorySessions(activeSummaries);"))
        assertTrue(formatSessionLabel.contains("const safeOrdinal = Math.max(1, Number(ordinal || 1));"))
        assertTrue(formatSessionLabel.contains("return 'Session ' + safeOrdinal;"))
        assertFalse(formatSessionLabel.contains("state.session"))
        assertFalse(formatSessionLabel.contains("latestScreen"))
        assertFalse(formatSessionLabel.contains("displayName"))
        assertFalse(formatSessionLabel.contains("packageTail"))
        assertFalse(formatSessionLabel.contains("Feedback snapshot"))
        assertTrue(html.contains("const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);"))
        assertTrue(html.contains("const renderedSessions = renderedActiveSummaries.map((session, index) => {"))
        assertTrue(
            html.contains(
                "const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);",
            ),
        )
        assertTrue(html.contains("max-height:"))
        assertTrue(html.contains("overflow: auto"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
        assertTrue(html.contains("class=\"history-item history-add-row\""))
        assertTrue(html.contains("data-start-new-history-annotating"))
        assertTrue(html.contains("function emptySessionsHtml()"))
        assertTrue(
            Regex(
                "sessions\\.innerHTML = renderedSessions\\s+\\? renderedSessions \\+ " +
                    "historyStartAnnotatingItemHtml\\(\\)\\s+: historyStartAnnotatingItemHtml\\(\\) \\+ " +
                    "emptySessionsHtml\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            html.contains("button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));"),
        )
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

        assertTrue(html.contains("async function resolvePendingBeforeBoundary(action, sessionId = null)"))
        assertTrue(
            html.contains("resolveDraftBoundary(workspace, { kind: action, sessionId }, createBrowserDraftPorts())"),
        )
        assertTrue(html.contains("ensureDraftCommandQueue().enqueue"))
        assertTrue(openSession.contains("await resolvePendingBeforeBoundary('open-session', sessionId)"))
        assertTrue(newSession.contains("await resolvePendingBeforeBoundary('new-session')"))
        assertFalse(openSession.contains("flushPendingAnnotationsBeforeSessionChange"))
        assertFalse(newSession.contains("flushPendingAnnotationsBeforeSessionChange"))
        assertTrue(html.contains("const allowBlankComments = Boolean(options.allowBlankComments);"))
        assertTrue(html.contains("!allowBlankComments"))
        assertTrue(html.contains("allowBlankComments,"))
    }

    @Test
    fun consoleUsesOptionASelectAnnotateToolsAndSimpleLabels() {
        val html = FeedbackConsoleAssets.indexHtml

        assertTrue(html.contains("<span>Select</span>"))
        assertTrue(html.contains("<span>Annotate</span>"))
        assertTrue(html.contains("<span>Copy Prompt</span>"))
        assertTrue(html.contains("<span>Save to MCP</span>"))
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
        assertTrue(
            Regex(
                "async function enterAnnotateMode\\(\\) \\{\\s+" +
                    "if \\(!requirePendingRecoveryChoiceBeforeSessionChange\\(\\)\\) return;\\s+" +
                    "await ensureSessionForAnnotating\\(\\);\\s+" +
                    "toolMode = 'annotate';\\s+" +
                    "renderCurrentSessionList\\(\\);\\s+" +
                    "if \\(!addItemsFlow\\) \\{\\s+" +
                    "await startAddItemsFlow\\(\\);",
            ).containsMatchIn(html),
        )
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
        assertTrue(html.contains("const PreviewIntervalStorageKey = 'fixthis.previewIntervalMs.v2'"))
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
        // Composer inspector now keeps the saved-items list visible whenever the session
        // already has saved annotations (so users don't lose them while adding new ones).
        // The list is hidden via a length check rather than a literal `= true` assignment.
        assertTrue(html.contains("draftItems.hidden = savedItems.length === 0;"))
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

        assertTrue(
            Regex("\\.annotation-back \\{[\\s\\S]*min-height: 32px;[\\s\\S]*padding: 0 14px;")
                .containsMatchIn(html),
        )
        assertTrue(
            Regex("\\.annotation-danger,[\\s\\S]*\\.annotation-done \\{[\\s\\S]*padding: 0 14px;")
                .containsMatchIn(html),
        )
    }

    @Test
    fun consoleHtmlCreatesHistorySessionBeforeAnnotatingFromEmptyState() {
        val html = FeedbackConsoleAssets.indexHtml
        val hasActiveHistorySessionForAnnotating = javascriptFunctionBody(html, "hasActiveHistorySessionForAnnotating")
        val ensureSessionForAnnotating = javascriptFunctionBody(html, "ensureSessionForAnnotating")
        val enterAnnotateMode = javascriptFunctionBody(html, "enterAnnotateMode")

        assertTrue(html.contains("function hasActiveHistorySessionForAnnotating()"))
        assertTrue(ensureSessionForAnnotating.contains("if (hasActiveHistorySessionForAnnotating()) return;"))
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
    fun consoleHtmlNoLongerFiltersReadyForAgentSessions() {
        val html = FeedbackConsoleAssets.indexHtml
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("'ready_for_agent'"), "History list must show sent sessions too")
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
}

class ConsoleFeedbackItemCanvasRoutesTest {
    @Test
    fun consoleHtmlRendersOptionACanvasToolbar() {
        val html = FeedbackConsoleAssets.indexHtml
        val toolbarAnnotationCounts = javascriptFunctionBody(html, "toolbarAnnotationCounts")

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
        assertFalse(toolbarAnnotationCounts.contains("const summary = selectedHistorySummary();"))
        assertFalse(toolbarAnnotationCounts.contains("open: historyOpenCount(summary)"))
        assertFalse(toolbarAnnotationCounts.contains("resolved: historyDoneCount(summary)"))
        assertTrue(html.contains("state.sessionSummaries = sessionSummaries;"))
        assertTrue(html.contains("toolbarOpenCount()"))
        assertTrue(html.contains("toolbarResolvedCount()"))
        assertTrue(html.contains("Click a widget — or drag to draw a region"))
        assertTrue(html.contains("class=\"zoom-control\""))
        assertTrue(html.contains("id=\"zoomOutButton\""))
        assertTrue(html.contains("id=\"zoomPercent\""))
        assertTrue(html.contains("id=\"zoomInButton\""))
        // Preview FSM owns the zoom level (zoom: 1 in createInitialPreviewState).
        assertTrue(html.contains("zoom: 1,"))
        assertTrue(html.contains("function applyPreviewZoom()"))
        assertTrue(html.contains("function setPreviewZoom(nextZoom)"))
        assertTrue(html.contains("frame.style.setProperty('--preview-zoom'"))
        assertTrue(html.contains("zoomOutButton.addEventListener('click'"))
        assertTrue(html.contains("zoomInButton.addEventListener('click'"))
        assertFalse(html.contains(".snapshot-frame::before"))
        assertTrue(html.contains("0 12px 24px -8px rgba(0, 0, 0, .4)"))
        assertTrue(html.contains("renderNumberedFeedbackOverlay"))
        assertTrue(html.contains("renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false"))
    }

    @Test
    fun consoleHtmlCountsActivePendingAnnotationsInHistory() {
        val html = FeedbackConsoleAssets.indexHtml
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        val historyDoneCount = javascriptFunctionBody(html, "historyDoneCount")
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")
        val deletePendingFeedbackItem = javascriptFunctionBody(html, "deletePendingFeedbackItem")
        val renderAnnotationDetail = javascriptFunctionBody(html, "renderAnnotationDetail")

        assertTrue(html.contains("function pendingHistoryItemsForSession(session)"))
        assertTrue(historyOpenCount.contains("pendingHistoryItemsForSession(session)"))
        assertTrue(
            historyOpenCount.contains(
                "(session.unresolvedItemsCount || 0) + (session.inProgressItemsCount || 0) + " +
                    "pending.filter(item => annotationStatus(item) !== 'resolved').length",
            ),
        )
        assertTrue(historyDoneCount.contains("pending.filter(item => annotationStatus(item) === 'resolved').length"))
        assertTrue(html.contains("function renderCurrentSessionList()"))
        assertTrue(createAnnotationFromSelection.contains("renderCurrentSessionList();"))
        assertTrue(deletePendingFeedbackItem.contains("renderCurrentSessionList();"))
        assertTrue(
            Regex("item\\.status = button\\.dataset\\.setStatus;[\\s\\S]*renderCurrentSessionList\\(\\);")
                .containsMatchIn(renderAnnotationDetail),
        )
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
    @Suppress("LongMethod")
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
        assertTrue(
            html.contains(
                "const label = firstMeaningful([...(node.text || []), node.editableText, " +
                    "...(node.contentDescription || [])]);",
            ),
        )
        assertTrue(html.contains("if (role && label) return humanize(role) + ' \"' + label + '\"';"))
        assertTrue(
            html.contains(
                "if (bounds) return 'Component ' + Math.round(bounds.right - bounds.left) + 'x' + " +
                    "Math.round(bounds.bottom - bounds.top);",
            ),
        )
        assertFalse(
            html.contains("const textValue = (node.text || [])[0] || (node.contentDescription || [])[0] || node.uid;"),
        )
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
}

class ConsoleFeedbackItemBatchRoutesTest {
    @Test
    fun consoleHtmlAnnotationSaveUsesCurrentSelectionPayload() {
        val html = FeedbackConsoleAssets.indexHtml
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")

        assertTrue(html.contains("function buildDraftWorkspaceSaveRequest"))
        assertTrue(html.contains("const result = await ensureDraftCommandQueue().enqueue"))
        assertTrue(html.contains("persistDraftWorkspace(saveWorkspace, createBrowserDraftPorts()"))
        assertTrue(html.contains("targetType: selection.targetType"))
        assertTrue(html.contains("nodeUid: selection.nodeUid"))
        assertTrue(html.contains("bounds: selection.bounds"))
        assertTrue(html.contains("function draftSelectionToItem"))
        assertTrue(html.contains("function persistPendingFeedbackItems"))
        assertTrue(createAnnotationFromSelection.contains("toolMode = 'annotate';"))
        assertFalse(createAnnotationFromSelection.contains("toolMode = 'select';"))
        assertTrue(createAnnotationFromSelection.contains("addDraftItem(draftWorkspace, selection, ports)"))
        assertTrue(createAnnotationFromSelection.contains("setDraftWorkspace(nextWorkspace);"))
        assertTrue(html.contains("suppressNextClick = true;"))
        assertTrue(html.contains("function updateSelectedAnnotationComment"))
        assertTrue(html.contains("item.comment = comment.value;"))
        assertTrue(html.contains("Add a comment to every annotation before saving."))
        assertTrue(html.contains("Select a component or area first."))
    }

    @Test
    @Suppress("LongMethod")
    fun savingDraftItemsAppendsOneScreenAndTwoItems() {
        val projectRoot = Files.createTempDirectory("fixthis-console-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "item-1",
                        "item-2",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
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
                val session = connection.inputJsonObject()
                assertEquals(1, session.getValue("screens").jsonArray.size)
                val items = session.getValue("items").jsonArray.map { it.jsonObject }
                assertEquals(2, items.size)
                assertEquals(
                    listOf("Change headline", "Add margin"),
                    items.map { it.getValue("comment").jsonPrimitive.content },
                )
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
    fun batchItemsApiReturnsConflictWhenLiveScreenFingerprintDiffersFromFrozenPreview() {
        val projectRoot = Files.createTempDirectory("fixthis-console-mismatch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = SequencedFingerprintBridge("frozen", "current"),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "frozenFingerprint": "frozen",
                          "screen": $frozenScreen,
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

                assertEquals(409, connection.responseCode)
                val payload = fixThisJson
                    .parseToJsonElement(connection.errorStream.bufferedReader().readText())
                    .jsonObject
                assertEquals("screen_fingerprint_mismatch", payload.getValue("error").jsonPrimitive.content)
                assertEquals("frozen", payload.getValue("frozenFingerprint").jsonPrimitive.content)
                assertEquals("current", payload.getValue("currentFingerprint").jsonPrimitive.content)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsFingerprintUnavailableHeaderWhenCurrentFingerprintIsMissing() {
        val projectRoot = Files.createTempDirectory("fixthis-console-null-fingerprint").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = NullableSequencedFingerprintBridge("frozen", null),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content
                val frozenScreen = preview.getValue("screen").jsonObject

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(
                        """
                        {
                          "previewId": "$previewId",
                          "frozenFingerprint": "frozen",
                          "screen": $frozenScreen,
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

                assertEquals(200, connection.responseCode)
                assertEquals(
                    "current_fingerprint_unavailable",
                    connection.getHeaderField("X-FixThis-Fingerprint-Unavailable-Reason"),
                )
                val session = connection.inputJsonObject()
                assertFalse(session.containsKey("fingerprintUnavailableReason"))
                assertEquals(1, session.getValue("screens").jsonArray.size)
                assertEquals(1, session.getValue("items").jsonArray.size)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun batchItemsApiReturnsServerErrorWhenLiveRecaptureThrowsIllegalArgumentException() {
        val projectRoot = Files.createTempDirectory("fixthis-console-recapture-error").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = SecondCaptureIllegalArgumentBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds(
                        "session-1",
                        "preview-1",
                        "preview-screen-1",
                        "recapture-screen-1",
                        "item-1",
                    ).next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
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
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(500, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("recapture failed"))
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun savingDraftItemsAllowsBlankCommentsForUnwrittenAnnotations() {
        val projectRoot = Files.createTempDirectory("fixthis-console-blank-batch").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1", "item-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
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
                val session = connection.inputJsonObject()
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
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"previewId":"preview-1","items":[]}""".toByteArray()) }

            assertEquals(400, connection.responseCode)
            assertTrue(
                connection.errorStream.bufferedReader().readText().contains("At least one feedback item is required"),
            )
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchItemsApiReturnsNotFoundForUnknownPreviewId() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge = bridge,
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
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
            assertEquals(0, bridge.captureCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun batchItemsApiReturnsBadRequestForInvalidPreviewTarget() {
        val bridge = FakeFixThisBridge()
        val projectRoot = Files.createTempDirectory("fixthis-console-invalid-target").toFile()
        try {
            val service = FeedbackSessionService(
                bridge = bridge,
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = FakeIds("session-1", "preview-1", "preview-screen-1").next,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                service.openSession(null, newSession = true)
                val preview = ConsoleHttpTestClient(server.url).getJsonObject("/api/preview")
                val previewId = preview.getValue("previewId").jsonPrimitive.content

                val connection = ConsoleHttpTestClient(server.url).connection("/api/items/batch")
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
                              "bounds": {"left":-1.0,"top":20.0,"right":110.0,"bottom":80.0},
                              "comment": "Change headline"
                            }
                          ]
                        }
                        """.trimIndent().toByteArray(),
                    )
                }

                assertEquals(400, connection.responseCode)
                assertTrue(connection.errorStream.bufferedReader().readText().contains("Selection bounds"))
                assertEquals(1, bridge.captureCount)
            } finally {
                server.stop()
            }
        } finally {
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun previewSaveInProgressMapsToConflict() {
        val method = Class
            .forName("io.beyondwin.fixthis.mcp.console.FeedbackConsoleServerKt")
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
}

class ConsoleFeedbackItemSessionRoutesTest {
    @Test
    fun sessionApiDoesNotCreateSessionWhenHistoryIsEmpty() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)

            assertEquals("null", client.get("/api/session"))

            val sessions = fixThisJson.parseToJsonElement(client.get("/api/sessions")).jsonObject
                .getValue("sessions")
                .jsonArray
            assertEquals(0, sessions.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiSendsDraftAndClearsDraftList() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds("session-1", "screen-1", "item-1", "batch-1").next,
            ),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Fix it")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["item-1"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            val sessionObj = payload["session"]!!.jsonObject
            assertTrue(sessionObj["handoffBatches"]?.jsonArray.orEmpty().isNotEmpty())
            assertEquals(
                "sent",
                sessionObj["items"]?.jsonArray?.single()?.jsonObject?.get("delivery")?.jsonPrimitive?.content,
            )
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: item-1"), "prompt should contain 'id: item-1', got:\n$prompt")
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffApiReturnsConflictWhenNoDraftItemsExist() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["fake-id"]}""",
            )
            assertEquals(409, response.statusCode)
            assertTrue(response.body.contains("NO_DRAFT_FEEDBACK"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun clearDraftApiKeepsSentItems() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(
                clock = { 100L },
                idGenerator = FakeIds(
                    "session-1",
                    "screen-1",
                    "item-1",
                    "batch-1",
                    "item-2",
                ).next,
            ),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.captureFakeScreenForTest(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(0f, 0f, 10f, 10f), "Sent")
        service.sendDraftToAgent(session.sessionId)
        service.addAreaFeedback(session.sessionId, screen.screenId, FixThisRect(10f, 10f, 20f, 20f), "Draft")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val clear = ConsoleHttpTestClient(server.url).connection("/api/items/draft")
            clear.requestMethod = "DELETE"

            assertEquals(200, clear.responseCode)
            val body = clear.inputStream.bufferedReader().readText()
            val comments = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items")
                .jsonArray
                .map { it.jsonObject.getValue("comment").jsonPrimitive.content }
            assertEquals(listOf("Sent"), comments)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiUsesCapturedNodeBoundsInsteadOfRequestBounds() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "item-1").next),
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
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "screenId": "${screen.screenId}",
                      "targetType": "node",
                      "nodeUid": "${node.uid}",
                      "bounds": {"left":200.0,"top":300.0,"right":260.0,"bottom":340.0},
                      "comment": "Button copy is unclear"
                    }
                    """.trimIndent().toByteArray(),
                )
            }

            assertEquals(200, connection.responseCode)
            val item = fixThisJson.decodeFromString(
                AnnotationDto.serializer(),
                connection.inputStream.bufferedReader().readText(),
            )
            assertEquals(AnnotationTargetDto.Node(node.uid, node.boundsInWindow), item.target)
            assertEquals(node, item.selectedNode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun itemsApiReturnsBadRequestForUnknownScreenId() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "screenId": "missing-screen",
                      "targetType": "area",
                      "bounds": {"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},
                      "comment": "Bad screen"
                    }
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
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "screenId": "${screen.screenId}",
                      "targetType": "area",
                      "bounds": {"left":0.0,"top":0.0,"right":10.0,"bottom":10.0},
                      "comment": "Bad field",
                      "screenID": "typo"
                    }
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
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1").next),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val screen = service.addCapturedScreenForTest(
            session.sessionId,
            SnapshotDto(
                screenId = "screen-1",
                capturedAtEpochMillis = 100L,
                displayName = "Checkout",
                screenshot = SnapshotScreenshotDto(width = 720, height = 1600, desktopFullPath = "/repo/screen.png"),
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/items")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(
                    """
                    {
                      "screenId": "${screen.screenId}",
                      "targetType": "area",
                      "bounds": {"left":-1.0,"top":0.0,"right":10.0,"bottom":10.0},
                      "comment": "Bad bounds"
                    }
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
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url).get("/api/sessions")

            assertTrue(sessions.contains("session-1"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionsApiFiltersByPackageNameQuery() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val matching = service.openSession("io.beyondwin.fixthis.sample", newSession = true)
        val other = service.openSession("io.beyondwin.fixthis.other", newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val sessions = ConsoleHttpTestClient(server.url)
                .get("/api/sessions?packageName=io.beyondwin.fixthis.sample")

            assertTrue(sessions.contains(matching.sessionId))
            assertFalse(sessions.contains(other.sessionId))
        } finally {
            server.stop()
        }
    }

    @Test
    fun openSessionApiSwitchesCurrentSession() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1", "session-2").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val first = service.openSession(null, newSession = true)
        service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
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
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/open")
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
        val projectRoot = Files.createTempDirectory("fixthis-console-save-fail").toFile()
        try {
            projectRoot.resolve(".fixthis").writeText("blocked")
            val persistence = FeedbackSessionPersistence(FeedbackSessionPaths(projectRoot), clock = { 100L })
            val service = FeedbackSessionService(
                bridge = FakeFixThisBridge(),
                store = FeedbackSessionStore(
                    clock = { 100L },
                    idGenerator = { "session-1" },
                    persistence = persistence,
                ),
                projectRoot = projectRoot.absolutePath,
                defaultPackageName = "io.beyondwin.fixthis.sample",
            )
            val server = FeedbackConsoleServer(service = service, port = 0)
            server.start()
            try {
                val connection = ConsoleHttpTestClient(server.url).connection(
                    "/api/session/open",
                    method = "POST",
                    body = """{"newSession":true}""",
                )

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
            FakeFixThisBridge(),
            FeedbackSessionStore(idGenerator = FakeIds("session-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null, newSession = true)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
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
            FakeFixThisBridge(),
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/session/close")
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
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(null, newSession = true)
            val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write("""{"action":"back","captureAfter":false}""".toByteArray()) }

            assertEquals(200, connection.responseCode)
            val response = connection.inputStream.bufferedReader().readText()
            assertEquals(true, fixThisJson.parseToJsonElement(response).jsonObject["performed"]?.jsonPrimitive?.boolean)
            assertEquals(FeedbackNavigationAction.BACK, bridge.navigationRequests.single().action)
            assertFalse(bridge.navigationRequests.single().captureAfter)
        } finally {
            server.stop()
        }
    }

    @Test
    fun navigationApiRejectsUnknownAutomationFields() {
        val bridge = FakeFixThisBridge()
        val service = FeedbackSessionService(
            bridge,
            FeedbackSessionStore(),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val payloads = listOf(
                """{"action":"back","sequence":[]}""",
                """{"action":"back","script":"adb shell input keyevent BACK"}""",
            )

            payloads.forEach { payload ->
                val connection = ConsoleHttpTestClient(server.url).connection("/api/navigation")
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
}

class ConsoleFeedbackItemHistoryRoutesTest {
    @Test
    fun deleteScreenApiDeletesScreenAndLinkedItems() {
        val service = FeedbackSessionService(
            FakeFixThisBridge(),
            FeedbackSessionStore(clock = { 100L }, idGenerator = FakeIds("session-1", "screen-1", "item-1").next),
            "/repo",
            "io.beyondwin.fixthis.sample",
        )
        val session = service.openSession(null)
        service.addCapturedScreenForTest(session.sessionId, SnapshotDto("screen-1", 0L, displayName = "Main"))
        service.addAreaFeedback(session.sessionId, "screen-1", FixThisRect(0f, 0f, 10f, 10f), "Remove me")
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val connection = ConsoleHttpTestClient(server.url).connection("/api/screens/screen-1")
            connection.requestMethod = "DELETE"

            assertEquals(200, connection.responseCode)
            val payload = fixThisJson.parseToJsonElement(connection.inputStream.bufferedReader().readText()).jsonObject
            assertTrue(payload.getValue("screens").jsonArray.isEmpty())
            assertTrue(payload.getValue("items").jsonArray.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            assertEquals(200, first.statusCode)
            val etag = first.header("ETag")
            assertNotNull(etag)
            assertTrue(etag.startsWith("\"") && etag.endsWith("\""), etag)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/sessions", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertEquals(etag, second.header("ETag"))
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionsEtagChangesAfterMutation() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/sessions").header("ETag")!!
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val second = client.getResponse("/api/sessions").header("ETag")!!
            assertNotEquals(first, second)
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionResponseIncludesEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertNotNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionReturns304ForMatchingIfNoneMatch() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            service.openSession(packageNameOverride = "com.example.app", newSession = true)
            val client = ConsoleHttpTestClient(server.url)
            val first = client.getResponse("/api/session")
            val etag = first.header("ETag")!!
            val second = client.getResponse("/api/session", headers = mapOf("If-None-Match" to etag))
            assertEquals(304, second.statusCode)
            assertTrue(second.body.isEmpty())
        } finally {
            server.stop()
        }
    }

    @Test
    fun apiSessionWithoutCurrentReturns200NullAndNoEtag() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).getResponse("/api/session")
            assertEquals(200, response.statusCode)
            assertEquals("null", response.body.trim())
            assertNull(response.header("ETag"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun historyPipsCollapseWorkingIntoOpen() {
        val html = FeedbackConsoleAssets.indexHtml
        assertFalse(
            html.contains("class=\"hi-pip working\""),
            "History pips must not render a separate working/WIP pip",
        )
        val historyOpenCount = javascriptFunctionBody(html, "historyOpenCount")
        assertTrue(
            historyOpenCount.contains("(session.inProgressItemsCount || 0)"),
            "historyOpenCount must include in-progress items so WIP collapses into the open count",
        )
    }

    @Test
    fun historyPipDropsPointsLabel() {
        val html = FeedbackConsoleAssets.indexHtml
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("hi-pip points"), "Points pip must be removed")
    }

    @Test
    fun consoleHtmlContainsSessionsPolling() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("function startSessionsPolling"), html.takeLast(2_000))
        assertTrue(html.contains("async function pollSessionsTick"), "Polling tick must exist")
    }

    @Test
    fun consoleHtmlDeclaresPollingGlobals() {
        // Per console-state-machine-expansion Task 4, the polling-owned state
        // (lastSessionsEtag, lastSessionEtag, pendingMutationCount,
        // consecutivePollFailures, promptActionInFlight, sessionMutationGeneration)
        // moved from module-level lets in state.js into pollingFsm /
        // pollingUseCases. The sessionsPollingTimer setInterval handle now
        // lives in sessions-polling.js's closure but is still emitted in the
        // bundled IIFE. The withMutationLock + pollingUseCases identifiers
        // must survive for behavior contracts elsewhere.
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("let sessionsPollingTimer"))
        assertTrue(html.contains("async function withMutationLock"))
        assertTrue(html.contains("pollingUseCases"), "polling FSM must be wired in")
        assertTrue(html.contains("createBrowserPollingUseCases"), "browser adapter must be wired")
        assertTrue(html.contains("lastSessionsEtag"), "polling FSM owns lastSessionsEtag")
        assertTrue(html.contains("lastSessionEtag"), "polling FSM owns lastSessionEtag")
        assertTrue(html.contains("pendingMutationCount"), "polling FSM owns pendingMutationCount")
    }

    @Test
    fun saveToMcpToastMentionsAgentPickup() {
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("Saved to MCP ✓ — agent will pick up"))
        assertFalse(html.contains("Saved to MCP ✓\","), "Old toast text must be gone")
    }

    @Test
    fun promptActionsDoNotSilentlyDropUncommentedPendingAnnotations() {
        val html = FeedbackConsoleAssets.indexHtml
        val persistAndCollect = javascriptFunctionBody(html, "persistAndCollectItemIds")

        assertTrue(
            persistAndCollect.contains("pendingFeedbackItems.some(item => !hasWrittenAnnotationComment(item))"),
            "Prompt actions must detect partially-commented pending batches before persistence",
        )
        assertTrue(
            persistAndCollect.contains("Add a comment to every annotation before saving."),
            "Prompt actions must keep all pending annotations visible instead of saving a partial batch",
        )
        assertFalse(
            persistAndCollect.contains("persistPendingFeedbackItems({ onlyWrittenComments: true })"),
            "Persisting only written comments clears the entire pending flow and silently drops uncommented pins",
        )
    }

    @Test
    fun mutationsAreWrappedInLock() {
        val html = FeedbackConsoleAssets.indexHtml
        val sendAgent = javascriptFunctionBody(html, "sendAgentPrompt")
        val copyPrompt = javascriptFunctionBody(html, "copyPrompt")
        assertTrue(sendAgent.contains("withMutationLock"))
        assertTrue(copyPrompt.contains("withMutationLock"))
    }

    @Test
    fun mergeSessionIntoStatePreservesUserState() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(body.contains("comment.value"), "Must preserve textarea value")
        assertTrue(body.contains("focusedSavedItemId") || body.contains("focusedPendingItemIndex"))
        assertTrue(body.contains("currentSelection"))
        assertTrue(body.contains("data-just-changed"))
    }

    @Test
    fun mergeSessionIntoStateSkipsHighlightOnBulkChange() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "mergeSessionIntoState")
        assertTrue(
            body.contains("BULK_CHANGE_HIGHLIGHT_THRESHOLD") || body.contains(">= 6") || body.contains("> 5"),
            "mergeSessionIntoState must guard against bulk highlight cascade",
        )
    }

    @Test
    fun startSessionsPollingIsCalledOnBoot() {
        val html = FeedbackConsoleAssets.indexHtml
        // Boot chain (16-space indent inside .then()): startSessionsPolling() must follow
        // startLivePreviewPolling() in the .then() block that already starts heartbeat + live-preview polling.
        assertTrue(
            html.contains(
                "                startHeartbeatPolling();\n" +
                    "                startLivePreviewPolling();\n" +
                    "                startSessionsPolling();\n" +
                    "              })",
            ),
            "main.js boot chain must call startSessionsPolling() after startLivePreviewPolling()",
        )
        // Visibility-change handler (14-space indent inside arrow body): must restart sessions polling
        // alongside the live-preview polling restart when the tab becomes visible again.
        assertTrue(
            html.contains(
                "              startLivePreviewPolling();\n" +
                    "              startSessionsPolling();\n" +
                    "            });",
            ),
            "visibilitychange handler must restart startSessionsPolling() when tab becomes visible",
        )
    }

    @Test
    fun sessionsPollingDeclaresFailureBackoffConstants() {
        // Failure counter (consecutiveFailures) and threshold
        // (MaxConsecutivePollFailures = 5) now live in pollingFsm.js. The
        // counter no longer exists as a module-level let in state.js, but
        // both identifiers must survive in the bundle as part of the FSM.
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(html.contains("consecutiveFailures"), "polling FSM must own the failure counter")
        assertTrue(
            html.contains("MaxConsecutivePollFailures = 5") || html.contains("MaxConsecutivePollFailures=5"),
            "must declare threshold constant",
        )
    }

    @Test
    fun pollSessionsTickResetsFailureCounterOnSuccess() {
        // The reset semantics now live in pollingFsm.js TICK_OK. The
        // top-level pollSessionsTick wrapper still exists for the grep
        // contract; the FSM-side behavior is verified by node tests.
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("pollingUseCases.pollSessionsTick"),
            "tick must delegate to pollingUseCases.pollSessionsTick (FSM dispatches TICK_OK on success)",
        )
    }

    @Test
    fun pollSessionsTickIncrementsFailureCounterOnError() {
        // Increment semantics live in pollingFsm.js TICK_FAILED.
        val html = FeedbackConsoleAssets.indexHtml
        assertTrue(
            html.contains("TICK_FAILED"),
            "polling FSM must dispatch TICK_FAILED to increment the failure counter",
        )
    }

    @Test
    fun pollSessionsTickPausesAfterThreshold() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "pollSessionsTick")
        assertTrue(
            body.contains("setSessionsPollingPaused(true)") || body.contains("stopSessionsPolling()"),
            "tick must pause polling once threshold reached",
        )
    }

    @Test
    fun visibilityChangeRecoversFromPolledFailure() {
        val html = FeedbackConsoleAssets.indexHtml
        // The visibilitychange handler must restart polling when paused.
        assertTrue(
            html.contains("sessionsPollingPaused") && html.contains("startSessionsPolling"),
            "visibility handler must consult sessionsPollingPaused and call startSessionsPolling",
        )
    }

    @Test
    fun withMutationLockRecoversFromPolledFailure() {
        val html = FeedbackConsoleAssets.indexHtml
        val body = javascriptFunctionBody(html, "withMutationLock")
        assertTrue(
            body.contains("sessionsPollingPaused") || body.contains("startSessionsPolling"),
            "withMutationLock finally-block must restart polling if paused",
        )
    }
}

class ConsoleFeedbackItemHandoffRoutesTest {
    @Test
    fun handoffPreviewEndpointReturnsMarkdownForRequestedItems() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(response.contentTypeStartsWith("text/markdown"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("id: $itemId"), "expected 'id: $itemId' in:\n${response.body}")
            assertTrue(response.body.contains("session_id: $sessionId"), "expected 'session_id:' in:\n${response.body}")
            assertTrue(response.body.contains("agent_protocol:"), "expected agent_protocol block in:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/handoff-preview",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun handoffPreviewEndpointEmitsJsonErrorBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/handoff-preview",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(response.contentTypeStartsWith("application/json"), "got: ${response.header("Content-Type")}")
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
            assertTrue(
                response.body.contains("itemIds must not be empty"),
                "expected reason in body, got:\n${response.body}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointUpdatesLastHandedOffAtForItems() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        // Promote DRAFT to SENT so the item carries SENT delivery before the call.
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            val item = store.getSession(sessionId).items.first { it.itemId == itemId }
            assertEquals(500L, item.lastHandedOffAtEpochMillis)
            assertEquals(500L, item.updatedAtEpochMillis)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, _) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.contentTypeStartsWith("application/json"),
                "got: ${response.header("Content-Type")}",
            )
            assertTrue(response.body.contains("\"error\""), "expected error JSON body, got:\n${response.body}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointReturns404ForUnknownSession() {
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = FeedbackSessionStore(clock = { 100L }, idGenerator = { "session-1" }),
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/sessions/00000000-0000-0000-0000-000000000000/items/mark-handed-off",
                body = """{"itemIds":["x"]}""",
            )
            assertEquals(404, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun markHandedOffEndpointRequiresConsoleToken() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url, includeConsoleToken = false).postJson(
                path = "/api/sessions/$sessionId/items/mark-handed-off",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(403, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsAcceptsItemIdsAndReturnsRenderedPrompt() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (_, itemId) = seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$itemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val payload = fixThisJson.parseToJsonElement(response.body).jsonObject
            assertTrue(payload.containsKey("session"), "response should have 'session', got: ${response.body}")
            assertTrue(payload.containsKey("prompt"), "response should have 'prompt', got: ${response.body}")
            val prompt = payload["prompt"]!!.jsonPrimitive.content
            assertTrue(prompt.contains("id: $itemId"), "prompt should contain 'id: $itemId', got:\n$prompt")
            val sessionObj = payload["session"]!!.jsonObject
            val itemDelivery = sessionObj["items"]!!.jsonArray
                .map { it.jsonObject }
                .first { it["itemId"]!!.jsonPrimitive.content == itemId }["delivery"]!!.jsonPrimitive.content
            assertEquals("sent", itemDelivery)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsLegacyPromptBody() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"prompt":"# old format"}""",
            )
            assertEquals(400, response.statusCode)
            assertTrue(
                response.body.contains("itemIds"),
                "error message should mention itemIds, got: ${response.body}",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsRejectsEmptyItemIds() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":[]}""",
            )
            assertEquals(400, response.statusCode)
        } finally {
            server.stop()
        }
    }

    @Test
    fun agentHandoffsFlipsOnlySpecifiedItemIdsToSentLeavesOthersAsDraft() {
        val store = FeedbackSessionStore()
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, keepItemId) = seedSessionWithOneItem(store, service)
        // Add a second DRAFT item that should NOT be flipped
        val secondItem = store.addItem(
            sessionId,
            AnnotationDto(
                itemId = "pending",
                screenId = "screen-1",
                createdAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
                target = AnnotationTargetDto.Area(FixThisRect(5f, 6f, 7f, 8f)),
                comment = "second draft",
            ),
        )
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val response = ConsoleHttpTestClient(server.url).postJson(
                path = "/api/agent-handoffs",
                body = """{"itemIds":["$keepItemId"]}""",
            )
            assertEquals(200, response.statusCode)
            val sessionAfter = store.getSession(sessionId)
            val keptItem = sessionAfter.items.first { it.itemId == keepItemId }
            val otherItem = sessionAfter.items.first { it.itemId == secondItem.itemId }
            assertEquals(FeedbackDelivery.SENT, keptItem.delivery, "specified item should flip to SENT")
            assertEquals(FeedbackDelivery.DRAFT, otherItem.delivery, "unspecified item should remain DRAFT")
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseIncludesStaleAfterHandoffFalseInitially() {
        val store = FeedbackSessionStore(clock = { 100L })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        seedSessionWithOneItem(store, service)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val items = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject
            assertTrue(item.containsKey("staleAfterHandoff"), "missing staleAfterHandoff: $item")
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffTrueWhenUpdatedAfterSend() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(true, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(200L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
            assertEquals(500L, item.getValue("updatedAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }

    @Test
    fun sessionResponseStaleAfterHandoffFalseAfterReSave() {
        var nowMillis = 100L
        val store = FeedbackSessionStore(clock = { nowMillis })
        val service = FeedbackSessionService(
            bridge = FakeFixThisBridge(),
            store = store,
            projectRoot = "/repo",
            defaultPackageName = "io.beyondwin.fixthis.sample",
        )
        val (sessionId, itemId) = seedSessionWithOneItem(store, service)
        nowMillis = 200L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        nowMillis = 500L
        service.updateDraftFeedback(sessionId, itemId, label = null, severity = null, comment = "edited", status = null)
        nowMillis = 700L
        service.sendDraftToAgent(sessionId, listOf(itemId))
        val server = FeedbackConsoleServer(service = service, port = 0)
        server.start()
        try {
            val body = ConsoleHttpTestClient(server.url).get("/api/session")
            val item = fixThisJson.parseToJsonElement(body).jsonObject
                .getValue("items").jsonArray
                .single { it.jsonObject.getValue("itemId").jsonPrimitive.content == itemId }
                .jsonObject
            assertEquals(false, item.getValue("staleAfterHandoff").jsonPrimitive.boolean)
            assertEquals(700L, item.getValue("lastHandedOffAtEpochMillis").jsonPrimitive.long)
        } finally {
            server.stop()
        }
    }
}
