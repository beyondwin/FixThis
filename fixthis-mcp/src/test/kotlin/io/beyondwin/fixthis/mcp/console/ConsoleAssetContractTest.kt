/**
 * Asset contract tests for the served console payload.
 *
 * These tests load the served HTML/JS via FeedbackConsoleAssets (the same path
 * the browser hits) and assert that:
 *  - DOM element IDs the JS expects exist in the rendered HTML.
 *  - JS function names / literal strings appear in the bundled app.js.
 *  - CSS class names referenced by the JS exist in the bundled CSS.
 *
 * Also includes a JAR resource check (no source maps leak into packaged jar).
 *
 * Running in isolation: a fresh `node scripts/build-console-assets.mjs` must
 * have been run since the last edit to console JS sources under
 * fixthis-mcp/src/main/console/.
 * See CONTRIBUTING.md for the full local-checks recipe.
 */
package io.beyondwin.fixthis.mcp.console

import io.beyondwin.fixthis.mcp.fixtures.ConsoleSourceFixtures
import io.beyondwin.fixthis.mcp.fixtures.javascriptFunctionBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass")
class ConsoleAssetContractTest {
    @Test
    fun `JAR resources do not include source maps`() {
        val cl = javaClass.classLoader
        val mapUrl = cl.getResource("console/app.js.map")
        assertNull(mapUrl, "app.js.map leaked into the packaged resources")
    }

    @Test
    fun consoleHtmlIncludesSessionPickerControls() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("id=\"sessions\""))
        assertTrue(html.contains("/api/sessions"))
        assertTrue(html.contains("/api/session/open"))
        assertTrue(html.contains("setConsoleSession(null);"))
    }

    @Test
    fun harnessSelectorsAreStable() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("""data-testid="connection-card""""))
        assertTrue(html.contains("""data-testid="save-to-mcp-button""""))
        assertTrue(html.contains("""data-testid="copy-prompt-button""""))
        assertTrue(html.contains("""data-testid="prompt-readiness""""))
        assertTrue(html.contains("""data-testid="global-status""""))
        assertTrue(html.contains("""data-reconnect-visible"""))
        assertTrue(
            html.contains("""data-testid="pending-recovery-banner"""") ||
                html.contains("""setAttribute('data-testid', 'pending-recovery-banner')""") ||
                html.contains("""setAttribute("data-testid","pending-recovery-banner")"""),
        )
    }

    @Test
    fun consoleHtmlOmitsToolbarNavigationControls() {
        val html = ConsoleSourceFixtures.readAll()

        assertFalse(html.contains("id=\"backButton\""))
        assertFalse(html.contains("id=\"captureAfterNavigation\""))
        assertFalse(html.contains("aria-label=\"Swipe up\""))
        assertTrue(html.contains("/api/navigation"))
        assertTrue(html.contains("captureAfter: false"))
    }

    @Test
    fun consoleHtmlUsesBrowserStudioLayout() {
        val html = ConsoleSourceFixtures.readAll()

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
        val html = ConsoleSourceFixtures.readAll()

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
    fun consoleHtmlSpacesEmptyPreviewBadgeFromMessage() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(
            Regex("\\.empty-stage \\{[\\s\\S]*display: flex;[\\s\\S]*gap: 14px;").containsMatchIn(html),
            "Empty preview state should leave generous space between the status badge and guidance copy",
        )
        assertTrue(
            Regex("\\.empty-stage \\{[\\s\\S]*flex-wrap: wrap;").containsMatchIn(html),
            "Empty preview state should wrap cleanly on narrow widths",
        )
        assertTrue(
            Regex("\\.preview-frame-status\\.empty \\{[\\s\\S]*margin-bottom: 0;").containsMatchIn(html),
            "Empty preview badge should not depend on vertical margin for horizontal spacing",
        )
    }

    @Test
    fun consoleHtmlUsesModeAwareStudioInspector() {
        val html = ConsoleSourceFixtures.readAll()
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
        assertTrue(createAnnotationFromSelection.contains("replaceDraftWorkspace(nextWorkspace);"))
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
                    "index === draftFocusIndex(), index, '', severityColor(annotationSeverity(item)))",
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
        val html = ConsoleSourceFixtures.readAll()
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
                "const editSessionId = toolMode.getState().focusedSavedSessionId " +
                    "|| state.session?.sessionId || null;",
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
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(
            html.contains(
                "function resetComposer(clearFlow = true, clearMirror = true)",
            ),
        )
        assertTrue(
            Regex("if \\(clearMirror\\) \\{[\\s\\S]*clearPendingMirror\\(state\\.session\\?\\.sessionId\\);")
                .containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function openSession\\(sessionId\\)[\\s\\S]*" +
                    "resetComposer\\(true, false\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function newSession\\(\\)[\\s\\S]*resetComposer\\(\\);" +
                    "[\\s\\S]*/api/session/open",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function closeSession\\(\\)[\\s\\S]*resetComposer\\(\\);" +
                    "[\\s\\S]*/api/session/close",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function deleteHistorySession\\(sessionId\\)[\\s\\S]*const isDisplayedSession = " +
                    "\\(\\) => state\\.session\\?\\.sessionId === sessionId;[\\s\\S]*const wasDisplayedSession = " +
                    "isDisplayedSession\\(\\);[\\s\\S]*if \\(wasDisplayedSession\\) \\{\\s+" +
                    "resetComposer\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "async function deleteHistorySession\\(sessionId\\)[\\s\\S]*if " +
                    "\\(wasDisplayedSession\\) \\{[\\s\\S]*setConsoleSession\\(null\\);[\\s\\S]*" +
                    "await refreshSessions\\(\\);\\s+render\\(\\);\\s+await refreshDevices\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "function cancelAddItemsFlow\\(\\)[\\s\\S]*resetComposer\\(\\);" +
                    "[\\s\\S]*render\\(\\);",
            ).containsMatchIn(html),
        )
        assertTrue(
            Regex(
                "function deletePendingFeedbackItem\\(index\\)[\\s\\S]*setDraftFocusIndex\\(null\\);" +
                    "[\\s\\S]*setDraftSelection\\(null\\);[\\s\\S]*comment.value = '';",
            ).containsMatchIn(html),
        )
    }

    @Test
    fun consoleHtmlKeepsFixThisTopLevelActionsInStudioTopbar() {
        val html = ConsoleSourceFixtures.readAll()

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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
        val renderSelectionOverlay = javascriptFunctionBody(html, "renderSelectionOverlay")

        assertTrue(
            renderSelectionOverlay.contains("if (!draftFlow())"),
            "saved overlays should be gated outside the active add-items flow",
        )
        assertTrue(
            renderSelectionOverlay.contains(
                "const focusedItem = savedEvidenceItems().find(item => " +
                    "item.itemId === toolModeState.focusedSavedItemId);",
            ),
            "saved overlays should prefer the focused saved item",
        )
        assertTrue(
            renderSelectionOverlay.contains(
                "const savedScreenId = focusedItem?.screenId || " +
                    "toolModeState.focusedSavedScreenId;",
            ),
            "saved overlays should be scoped to the focused saved screen",
        )
        assertTrue(
            renderSelectionOverlay.contains(
                "const sameScreenItems = savedEvidenceItems().filter(" +
                    "item => item.screenId === savedScreenId);",
            ),
            "saved overlays should include only items from the focused screen",
        )
        assertFalse(
            renderSelectionOverlay.contains("if (nodeUid) return visibleUids.has(nodeUid);"),
            "saved overlays must not infer screen identity from nodeUid on live preview",
        )
    }

    @Test
    fun consoleHtmlRefreshesSessionSummariesAfterSavedItemDeleteOrEdit() {
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
        val renderComposerInspector = javascriptFunctionBody(html, "renderComposerInspector")

        // While `draftFlow()` is active the composer inspector previously hid every saved
        // annotation, so users adding a new pin to a session that already had four saved
        // items only saw the single pending entry. The composer must surface saved
        // annotations as well so totals stay coherent across pending + saved.
        assertTrue(
            renderComposerInspector.contains("const savedItems = savedEvidenceItems();"),
            "renderComposerInspector should resolve saved items",
        )
        assertTrue(
            renderComposerInspector.contains(
                "inspectorCount.textContent = String(draftItemList().length + savedItems.length);",
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
        val html = ConsoleSourceFixtures.readAll()
        // Narrow scope: latestPersistedScreen() must include SENT items.
        // The send-path filter inside currentPromptAnnotations() is intentional and stays.
        val latestPersistedScreenBody = javascriptFunctionBody(html, "latestPersistedScreen")
        assertFalse(
            latestPersistedScreenBody.contains("delivery !== 'sent'"),
            "latestPersistedScreen must show SENT items too",
        )
    }

    @Test
    @Suppress("LongMethod")
    fun consoleHtmlIncludesSelectionHandoffWorkspace() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("id=\"deviceControl\""))
        assertTrue(html.contains("id=\"devicePicker\""))
        assertTrue(html.contains("id=\"deviceName\""))
        assertTrue(html.contains("id=\"deviceConnectionState\""))
        assertTrue(html.contains("id=\"refreshDevicesButton\""))
        assertTrue(html.contains("id=\"disconnectDeviceButton\""))
        assertTrue(html.contains("class=\"device-clear-button\""))
        assertTrue(html.contains("aria-label=\"Android device\""))
        assertTrue(html.contains("aria-label=\"Refresh devices\""))
        assertTrue(html.contains("title=\"Refresh devices\""))
        assertTrue(html.contains("aria-label=\"Clear FixThis device selection\""))
        assertTrue(html.contains("title=\"Clear FixThis device selection\""))
        assertTrue(html.contains("aria-hidden=\"true\">&times;</span>"))
        assertFalse(html.contains(">Clear selection</button>"))
        assertFalse(html.contains("class=\"clear-device-button\""))
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
        assertTrue(html.contains("selectionSummary.textContent = draftSelection()"))
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
        val html = ConsoleSourceFixtures.readAll()

        assertFalse(html.contains("id ${'$'}{shortId(session.sessionId)}"))
        assertFalse(html.contains("${'$'}{session.status} | ${'$'}{shortId(session.sessionId)}"))
        assertFalse(html.contains(" | ${'$'}{escapeHtml(shortId(screen.screenId))} | "))
        assertFalse(html.contains("item ${'$'}{escapeHtml(shortId(item.itemId))}"))
        assertFalse(html.contains("screen ${'$'}{escapeHtml(shortId(item.screenId))}"))
        assertFalse(html.contains("batch ${'$'}{escapeHtml(shortId(batch.batchId))}"))
        assertFalse(html.contains("items ${'$'}{escapeHtml((batch.itemIds || []).map(shortId).join(', ') || '-')}"))
        assertFalse(html.contains("Missing item ${'$'}{shortId(item.itemId)}"))

        assertFalse(html.contains("function formatSessionHeader"))
        assertFalse(html.contains("feedback item', 'feedback items'"))
        assertTrue(html.contains("function renderSavedEvidenceGroups"))
    }

    @Test
    @Suppress("LongMethod")
    fun consoleHtmlRendersStudioSessionHistoryWithoutInternalIds() {
        val html = ConsoleSourceFixtures.readAll()
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
        assertTrue(html.contains("newHistoryAnnotateModeStarting: false,"))
        assertTrue(html.contains("if (toolMode.getState().newHistoryAnnotateModeStarting) return;"))
        assertTrue(html.contains("toolMode.setNewHistoryAnnotateModeStarting(true);"))
        assertTrue(html.contains("toolMode.setNewHistoryAnnotateModeStarting(false);"))
        assertTrue(html.contains("await newSession();"))
        assertTrue(html.contains("scrollActiveHistoryItemIntoView();"))
        assertTrue(html.contains("await enterAnnotateMode();"))
        assertTrue(html.contains("function scrollActiveHistoryItemIntoView()"))
        assertTrue(html.contains("sessions.querySelector('.session-row.is-active')"))
        assertTrue(html.contains("renderCurrentSessionList();"))
        assertTrue(
            html.contains(
                "if (toolMode.getState().newHistoryAnnotateModeStarting || " +
                    "isSessionNavigationInFlight()) return '';",
            ),
        )
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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()

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
        assertTrue(html.contains("mode: ToolMode.SELECT,"))
        assertTrue(html.contains("function enterAnnotateMode"))
        assertTrue(html.contains("function enterSelectMode"))
        assertTrue(
            Regex(
                "async function enterAnnotateMode\\(\\) \\{\\s+" +
                    "if \\(!requirePendingRecoveryChoiceBeforeSessionChange\\(\\)\\) return;\\s+" +
                    "await ensureSessionForAnnotating\\(\\);\\s+" +
                    "toolMode\\.enterAnnotate\\(\\);\\s+" +
                    "renderCurrentSessionList\\(\\);\\s+" +
                    "if \\(!draftFlow\\(\\)\\) \\{\\s+" +
                    "requestCanonicalPreviewCapture\\(\\);\\s+" +
                    "await startDraftAnnotationFlow\\(\\);",
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
        assertTrue(html.contains("startDraftAnnotationFlow"))
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
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("[hidden] { display: none !important; }"))
        assertTrue(html.contains("pendingItems.hidden = true"))
        // Composer inspector now keeps the saved-items list visible whenever the session
        // already has saved annotations (so users don't lose them while adding new ones).
        // The list is hidden via a length check rather than a literal `= true` assignment.
        assertTrue(html.contains("draftItems.hidden = savedItems.length === 0;"))
    }

    @Test
    fun consoleHtmlShowsStartAnnotatingWhenSavedAnnotationsAreEmpty() {
        val html = ConsoleSourceFixtures.readAll()
        val renderSavedEvidenceGroups = javascriptFunctionBody(html, "renderSavedEvidenceGroups")

        assertTrue(renderSavedEvidenceGroups.contains("startAnnotatingButtonHtml()"))
        assertTrue(html.contains("data-start-annotating"))
        assertTrue(html.contains("Start annotating"))
        assertTrue(html.contains("function bindStartAnnotatingButtons(container)"))
        assertTrue(renderSavedEvidenceGroups.contains("bindStartAnnotatingButtons(draftItems);"))
        assertTrue(html.contains("function startAnnotatingButtonHtml()"))
        assertTrue(html.contains("if (toolMode.isAnnotateMode()) return '';"))
        assertTrue(html.contains("function historyStartAnnotatingItemHtml()"))
    }

    @Test
    fun consoleHtmlGivesBackToAnnotationsButtonButtonPadding() {
        val html = ConsoleSourceFixtures.readAll()

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
        val html = ConsoleSourceFixtures.readAll()
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
        assertTrue(enterAnnotateMode.contains("toolMode.enterAnnotate();"))
        assertTrue(enterAnnotateMode.contains("renderCurrentSessionList();"))
        assertTrue(enterAnnotateMode.contains("if (!draftFlow()) {"))
        assertTrue(enterAnnotateMode.contains("await startDraftAnnotationFlow();"))
    }

    @Test
    fun consoleHtmlNoLongerFiltersReadyForAgentSessions() {
        val html = ConsoleSourceFixtures.readAll()
        val rendered = javascriptFunctionBody(html, "renderSessionsListFromPayload")
        assertFalse(rendered.contains("'ready_for_agent'"), "History list must show sent sessions too")
    }

    @Test
    fun consoleHtmlRendersSavedAnnotationsWithSameListUiAfterSessionSwitch() {
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("function focusedPendingSelectionSummary()"))
        assertTrue(html.contains("draftFocusIndex() != null"))
        assertTrue(html.contains("const item = focusedPendingSelectionSummary();"))
        assertTrue(html.contains("setDraftFocusIndex(index);"))
        assertTrue(html.contains("setDraftSelection(null);"))
        assertFalse(html.contains("const item = draftItemList()[index];\n              setDraftSelection(item ?"))
        assertFalse(html.contains("label: item.targetType === 'node' ? 'Selected component' : 'Custom area'"))
    }

    @Test
    @Suppress("LongMethod")
    fun consoleHtmlImplementsSnapshotSelectionModes() {
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("let draftFlowState"))
        assertTrue(html.contains("let draftPinsState"))
        assertTrue(html.contains("let draftSelectionState"))
        assertTrue(html.contains("finishAreaSelection"))
        assertTrue(html.contains("selectNodeAtPoint"))
        assertTrue(html.contains("nodesForHitTest"))
        assertTrue(html.contains("function nodesForHitTest(screen, nodesSelector)"))
        assertTrue(html.contains("function smallestContainingNode(nodes, point)"))
        assertTrue(html.contains("function hitTestNodes(screen)"))
        assertTrue(html.contains("hoveredTarget: null"))
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
        assertTrue(html.contains("toolModeState.drag?.preview"))
        assertTrue(html.contains("hover-preview"))
        assertTrue(html.contains("pointermove"))
        assertTrue(html.contains("image.addEventListener('pointerleave', clearHoverPreview)"))
        assertTrue(html.contains("function clearDragState()"))
        assertTrue(html.contains("function clearHoverPreview()"))
        assertTrue(html.contains("if (!dragState) {"))
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
        val html = ConsoleSourceFixtures.readAll()

        assertTrue(html.contains("navigation.captureError"))
        assertTrue(html.contains("Navigation performed, but capture failed:"))
    }

    @Test
    fun consoleHtmlAnnotationSaveUsesCurrentSelectionPayload() {
        val html = ConsoleSourceFixtures.readAll()
        val createAnnotationFromSelection = javascriptFunctionBody(html, "createAnnotationFromSelection")

        assertTrue(html.contains("function buildDraftWorkspaceSaveRequest"))
        assertTrue(html.contains("const result = await ensureDraftCommandQueue().enqueue"))
        assertTrue(html.contains("persistDraftWorkspace(saveWorkspace, createBrowserDraftPorts()"))
        assertTrue(html.contains("targetType: selection.targetType"))
        assertTrue(html.contains("nodeUid: selection.nodeUid"))
        assertTrue(html.contains("bounds: selection.bounds"))
        assertTrue(html.contains("function draftSelectionToItem"))
        assertTrue(html.contains("function persistPendingFeedbackItems"))
        assertTrue(createAnnotationFromSelection.contains("toolMode.enterAnnotate();"))
        assertFalse(createAnnotationFromSelection.contains("toolMode.enterSelect();"))
        assertTrue(createAnnotationFromSelection.contains("addDraftItem(draftWorkspace, selection, ports)"))
        assertTrue(createAnnotationFromSelection.contains("replaceDraftWorkspace(nextWorkspace);"))
        assertTrue(html.contains("toolMode.setSuppressNextClick(true);"))
        assertTrue(html.contains("function updateSelectedAnnotationComment"))
        assertTrue(html.contains("item.comment = comment.value;"))
        assertTrue(html.contains("Add a comment to every annotation before saving."))
        assertTrue(html.contains("Select a component or area first."))
    }

    @Test
    fun consoleHtmlContainsSessionsPolling() {
        val html = ConsoleSourceFixtures.readAll()
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
        val html = ConsoleSourceFixtures.readAll()
        assertTrue(html.contains("let sessionsPollingTimer"))
        assertTrue(html.contains("async function withMutationLock"))
        assertTrue(html.contains("pollingUseCases"), "polling FSM must be wired in")
        assertTrue(html.contains("createBrowserPollingUseCases"), "browser adapter must be wired")
        assertTrue(html.contains("lastSessionsEtag"), "polling FSM owns lastSessionsEtag")
        assertTrue(html.contains("lastSessionEtag"), "polling FSM owns lastSessionEtag")
        assertTrue(html.contains("pendingMutationCount"), "polling FSM owns pendingMutationCount")
    }
}
