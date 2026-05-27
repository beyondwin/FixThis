// @requires state.js, draftWorkspace.js, annotations.js, historyPendingDedupe.js, domain/consoleEvents.js, boundaryTriggers.js, draftUseCases.js, editorState.js, boundaryDialogVariants.js, historySessionRow.js
            function sessionOrdinalLookup(sessions) {
              const ordinalBySessionId = new Map();
              stableHistorySessions(sessions)
                .forEach((session, index) => {
                  ordinalBySessionId.set(session.sessionId, index + 1);
                });
              return ordinalBySessionId;
            }

            function stableHistorySessions(sessions) {
              return [...(sessions || [])].sort((left, right) =>
                (left.createdAtEpochMillis || 0) - (right.createdAtEpochMillis || 0) ||
                String(left.sessionId || '').localeCompare(String(right.sessionId || ''))
              );
            }

            function formatSessionLabel(session, ordinal) {
              const safeOrdinal = Math.max(1, Number(ordinal || 1));
              return 'Session ' + safeOrdinal;
            }

            function formatSessionSummary(session) {
              const screens = Number(session?.screensCount || 0);
              const screensLabel = screens > 0 ? countLabel(screens, 'screen', 'screens') + ' · ' : '';
              return screensLabel + formatHistoryDate(session.updatedAtEpochMillis);
            }

            function pendingHistoryItemsForSession(session) {
              if (draftFlow() && state.session?.sessionId === session?.sessionId) {
                return dedupePendingHistoryItemsForSession(session, draftItemList(), draftWorkspace?.workspaceId);
              }
              return historyRecoveryItemsForSession(session);
            }

            function historyRecoveryItemsForSession(session) {
              const sessionId = session?.sessionId;
              if (!sessionId) return [];
              try {
                const storage = createBrowserDraftPorts().storage;
                return newestDedupedHistoryRecoveryItems(session, storage.loadWorkspacesForSession(sessionId));
              } catch (_) {
                return [];
              }
            }

            function pendingHistorySummaryForSession(session) {
              const items = pendingHistoryItemsForSession(session);
              const summary = draftRecoverySummary(items);
              if (!summary.total) return '';
              const parts = [];
              if (summary.commented) parts.push(countLabel(summary.commented, 'draft comment', 'draft comments'));
              if (summary.pinOnly) parts.push(countLabel(summary.pinOnly, 'pin without comment', 'pins without comments'));
              return parts.join(' · ');
            }

            function historyOpenCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              return (session.unresolvedItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length;
            }

            function historyDoneCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              const persistedDone = Math.max(0, (session.itemsCount || 0) - (session.unresolvedItemsCount || 0));
              return persistedDone + pending.filter(item => annotationStatus(item) === 'resolved').length;
            }

            function renderHistoryStrip(session) {
              const open = historyOpenCount(session);
              const done = historyDoneCount(session);
              const total = open + done;
              if (!total) return '<span class="hi-strip-cell empty"></span>';
              return [
                ...Array.from({ length: open }, () => '<span class="hi-strip-cell"></span>'),
                ...Array.from({ length: done }, () => '<span class="hi-strip-cell done"></span>')
              ].join('');
            }

            function firstLine(value) {
              return text(value).split(/\r?\n/)[0] || '(No comment)';
            }

            function formatItemLabel(item, index) {
              const number = item.sequenceNumber ?? (index + 1);
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function formatSavedEvidenceItemLabel(item, index) {
              const number = item.sequenceNumber ?? (index + 1);
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function findScreen(screenId) {
              return (state.session?.screens || []).find(screen => screen.screenId === screenId) || null;
            }


            function selectedHistorySummary() {
              const sessionId = state.session?.sessionId;
              if (!sessionId) return null;
              return (state.sessionSummaries || []).find(session => session.sessionId === sessionId) || null;
            }

            function displayedSessionId() {
              return state.session?.sessionId || draftWorkspace?.context?.sessionId || null;
            }

            function isClosedSession(session) {
              return String(session?.status || '').toLowerCase() === 'closed';
            }

            function clearDisplayedSessionState() {
              pendingRecovery = null;
              resetComposer();
              clearPreview();
              setConsoleSession(null);
            }

            let sessionNavigationInFlight = false;
            let pendingSessionNavigationId = null;

            function isSessionNavigationInFlight() {
              return sessionNavigationInFlight;
            }

            function toolbarAnnotationCounts() {
              if (draftFlow()) {
                const pending = draftItemList();
                return {
                  open: pending.filter(item => annotationStatus(item) !== 'resolved').length,
                  resolved: pending.filter(item => annotationStatus(item) === 'resolved').length
                };
              }
              const annotations = toolbarAnnotations();
              return {
                open: annotations.filter(item => annotationStatus(item) !== 'resolved').length,
                resolved: annotations.filter(item => annotationStatus(item) === 'resolved').length
              };
            }

            function toolbarOpenCount() {
              return toolbarAnnotationCounts().open;
            }

            function toolbarResolvedCount() {
              return toolbarAnnotationCounts().resolved;
            }

            function selectedAnnotation() {
              if (draftFocusIndex() == null) return null;
              return draftItemList()[draftFocusIndex()] || null;
            }

            function sourceHintLabel(item) {
              return (item.sourceCandidates || []).length ? 'Source hint available' : 'No source hint';
            }

            function hasActiveHistorySessionForAnnotating() {
              return Boolean(
                state.session &&
                state.session.status !== 'closed' &&
                (state.sessionSummaries || []).some(session =>
                  session.sessionId === state.session.sessionId &&
                  session.status !== 'closed'
                )
              );
            }

            async function ensureSessionForAnnotating() {
              if (hasActiveHistorySessionForAnnotating()) return;
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) {
                throw new Error('Recover, recapture, or discard unsaved annotations before changing sessions.');
              }
              resetComposer();
              clearPreview();
	              setConsoleSession(await withMutationLock(() => requestJson('/api/session/open', {
	                method: 'POST',
	                headers: { 'Content-Type': 'application/json' },
	                body: JSON.stringify({ newSession: true })
	              })));
              await refreshSessions();
            }

            async function enterAnnotateMode() {
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) {
                showStatus('Resolve the recovered draft first, then try Start annotating again.', { variant: 'warn', durationMs: 3500 });
                return;
              }
              await ensureSessionForAnnotating();
              if (!draftFlow()) {
                await startDraftAnnotationFlow();
              } else {
                toolMode.enterAnnotate();
                renderCurrentSessionList();
                renderPreviewOnly();
                renderInspectorRegion();
              }
            }

            async function enterNewHistoryAnnotateMode() {
              if (toolMode.getState().newHistoryAnnotateModeStarting) return;
              const wasAnnotating = toolMode.isAnnotateMode();
              toolMode.setNewHistoryAnnotateModeStarting(true);
              renderCurrentSessionList();
              try {
                const openedNewSession = await newSession();
                if (!openedNewSession) {
                  if (!wasAnnotating) toolMode.enterSelect();
                  render();
                  return;
                }
                scrollActiveHistoryItemIntoView();
                await enterAnnotateMode();
                scrollActiveHistoryItemIntoView();
              } finally {
                toolMode.setNewHistoryAnnotateModeStarting(false);
                renderCurrentSessionList();
              }
            }

            function scrollActiveHistoryItemIntoView() {
              const activeRow = sessions.querySelector('.session-row.is-active');
              activeRow?.scrollIntoView({ block: 'nearest' });
            }

            function syncHistoryDrawerState() {
              if (!historyToggleButton || !historyDrawerScrim) return;
              const open = toolMode.getState().historyDrawerOpen;
              if (open) {
                document.body.dataset.historyDrawerOpen = 'true';
              } else {
                delete document.body.dataset.historyDrawerOpen;
              }
              historyToggleButton.setAttribute('aria-expanded', String(open));
            }

            function openHistoryDrawer() {
              toolMode.setHistoryDrawer(true);
              syncHistoryDrawerState();
              document.getElementById('historyPanel')?.focus({ preventScroll: true });
            }

            function closeHistoryDrawer(options = {}) {
              const wasOpen = toolMode.getState().historyDrawerOpen;
              toolMode.setHistoryDrawer(false);
              syncHistoryDrawerState();
              if (wasOpen && options.returnFocus !== false) historyToggleButton?.focus({ preventScroll: true });
            }

            // Wraps the history module's global listeners so the boot
            // controller (main.js) can dispose them via __fixthisDisposers.
            // Per-row listeners attached inside renderSessionsListFromPayload
            // re-attach on every render and are out of scope for dispose().
            function initHistory({
              document,
              historyToggleButton,
              historyDrawerScrim,
              openHistoryDrawer,
              closeHistoryDrawer,
              syncHistoryDrawerState,
              toolMode,
            }) {
              const handlers = [];
              function on(target, type, fn) {
                if (!target) return;
                target.addEventListener(type, fn);
                handlers.push([target, type, fn]);
              }
              on(historyToggleButton, 'click', openHistoryDrawer);
              const scrimHandler = () => closeHistoryDrawer();
              on(historyDrawerScrim, 'click', scrimHandler);
              const keydownHandler = (event) => {
                if (event.key === 'Escape' && toolMode.getState().historyDrawerOpen) {
                  event.preventDefault();
                  closeHistoryDrawer();
                }
              };
              on(document, 'keydown', keydownHandler);
              syncHistoryDrawerState();
              return {
                dispose() {
                  for (const [target, type, fn] of handlers) {
                    target.removeEventListener(type, fn);
                  }
                  handlers.length = 0;
                },
              };
            }
            __fixthisDisposers.push(initHistory({
              document,
              historyToggleButton,
              historyDrawerScrim,
              openHistoryDrawer,
              closeHistoryDrawer,
              syncHistoryDrawerState,
              toolMode,
            }));

            function historyStartAnnotatingItemHtml() {
              if (toolMode.getState().newHistoryAnnotateModeStarting || isSessionNavigationInFlight()) return '';
              return '<button type="button" class="history-item history-add-row" data-start-new-history-annotating aria-label="Start annotating">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>' +
              '</button>';
            }


            function renderSessionsListFromPayload(sessionSummaries) {
              state.sessionSummaries = sessionSummaries;
              const activeId = state.session?.sessionId;
              const navigationBusy = isSessionNavigationInFlight();
              const activeSummaries = sessionSummaries.filter(session => session.status !== 'closed');
              const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);
              const renderedActiveSummaries = stableHistorySessions(activeSummaries);
              sessionCount.textContent = String(activeSummaries.length);
              const renderedSessions = renderedActiveSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
                const pendingSummary = pendingHistorySummaryForSession(session);
                const pips = [
                  open > 0 ? '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' : '',
                  done > 0 ? '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'resolved', 'resolved')) + '</span>' : '',
                ].join('');
                const busy = navigationBusy && (session.sessionId === activeId || session.sessionId === pendingSessionNavigationId);
                return historySessionRowHtml({
                  session,
                  label,
                  pendingSummary,
                  pips,
                  activeId,
                  busy,
                  navigationBusy,
                });
              }).join('');
              sessions.innerHTML = renderedSessions
                ? renderedSessions + historyStartAnnotatingItemHtml()
                : historyStartAnnotatingItemHtml() + emptySessionsHtml();
              bindHistorySessionRowEvents(document);
              document.querySelectorAll('[data-start-new-history-annotating]').forEach(button => {
                button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));
              });
            }

            function applySessionSummaryFromPayload(summary) {
              if (!summary || !summary.sessionId) return state.sessionSummaries || [];
              const existing = state.sessionSummaries || [];
              const found = existing.some(session => session.sessionId === summary.sessionId);
              const next = found
                ? existing.map(session => session.sessionId === summary.sessionId ? summary : session)
                : [summary, ...existing];
              renderSessionsListFromPayload(next);
              return next;
            }

            function renderSessionsList() {
              const activeId = state.session?.sessionId;
              const navigationInFlight = isSessionNavigationInFlight();
              document.querySelectorAll('.session-row').forEach(row => {
                const busy = navigationInFlight && (row.dataset.sessionId === activeId || row.dataset.sessionId === pendingSessionNavigationId);
                row.classList.toggle('is-active', row.dataset.sessionId === activeId);
                row.classList.toggle('is-busy', busy);
                row.setAttribute('aria-busy', busy ? 'true' : 'false');
                row.setAttribute('aria-disabled', navigationInFlight ? 'true' : 'false');
                row.querySelectorAll('[data-delete-session-id]').forEach(button => {
                  button.disabled = navigationInFlight;
                });
              });
            }

            function renderCurrentSessionList() {
              renderSessionsListFromPayload(state.sessionSummaries || []);
            }

            // Sync sidebar summaries and the active session in lockstep so the panel/toolbar
            // (driven by state.session) cannot drift behind the sidebar (driven by summaries).
            // Both endpoints fetched in parallel; if one fails the call rejects as before.
            async function refreshSessions() {
              const previousSessionId = state.session?.sessionId || null;
              const [response, currentSession] = await Promise.all([
                requestJson('/api/sessions'),
                requestJson('/api/session'),
              ]);
              const nextSessionId = currentSession?.sessionId || null;
              if (previousSessionId !== nextSessionId) clearPreview();
              setConsoleSession(currentSession || null);
              renderSessionsListFromPayload(response.sessions || []);
              return response.sessions || [];
            }

            async function refreshSessionsWhenEventsDisconnected() {
              if (isConsoleEventsConnected() || wasConsoleEventsRecentlyConnected()) return state.sessionSummaries || [];
              return refreshSessions();
            }

            async function refresh() {
              error.textContent = '';
              await refreshSessions();
              await refreshDevices();
              await refreshConnection();
              loadPendingRecoveryForCurrentSession();
              render();
            }

            async function openSession(sessionId) {
              if (!sessionId) return;
              error.textContent = '';
              if (sessionId === state.session?.sessionId) {
                renderSessionsList();
                return;
              }
              if (sessionNavigationInFlight) {
                pendingSessionNavigationId = sessionId;
                renderSessionsList();
                return;
              }
              sessionNavigationInFlight = true;
              pendingSessionNavigationId = null;
              renderSessionsList();
              try {
                resolveTrigger(Trigger.SESSION_SWITCH, {
                  state: deriveEditorState(currentDraftWorkspace(), draftSelection(), null),
                  targetSessionId: sessionId,
                }, {
                  silentDiscard: clearSelection,
                  showToast: (text) => showStatus(text, { variant: 'info' }),
                  openBoundaryDialog: () => {},
                  preserve: () => {},
                });
                if (await resolvePendingBeforeBoundary('open-session', sessionId) !== 'continue') return;
                bumpSessionMutationGeneration();
                stopLivePreviewPolling();
                resetComposer(true, false);
                clearPreview();
	                setConsoleSession(await withMutationLock(() => requestJson('/api/session/open', {
	                  method: 'POST',
	                  headers: { 'Content-Type': 'application/json' },
	                  body: JSON.stringify({ sessionId: sessionId })
	                })));
                renderCurrentSessionList();
                renderInspectorRegion();
                await refresh();
                if (!latestPersistedScreen() && shouldAutoFetchPreview()) {
                  await refreshPreview();
                }
                startLivePreviewPolling();
              } finally {
                sessionNavigationInFlight = false;
                const queuedSessionId = pendingSessionNavigationId;
                pendingSessionNavigationId = null;
                renderCurrentSessionList();
                if (queuedSessionId && queuedSessionId !== state.session?.sessionId) await openSession(queuedSessionId);
              }
            }

            async function newSession() {
              error.textContent = '';
              if (sessionNavigationInFlight) return false;
              if (await resolvePendingBeforeBoundary('new-session') !== 'continue') return false;
              bumpSessionMutationGeneration();
              resetComposer();
              clearPreview();
	              setConsoleSession(await withMutationLock(() => requestJson('/api/session/open', {
	                method: 'POST',
	                headers: { 'Content-Type': 'application/json' },
	                body: JSON.stringify({ newSession: true })
	              })));
              await refresh();
              startLivePreviewPolling();
              return true;
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              if (await resolvePendingBeforeBoundary('close-session', state.session.sessionId) !== 'continue') return;
              const sessionId = state.session.sessionId;
              bumpSessionMutationGeneration();
              await withMutationLock(() => requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              resetComposer();
              clearPreview();
              setConsoleSession(null);
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function deleteHistorySession(sessionId, options = {}) {
              error.textContent = '';
              if (!sessionId) return;
              if (sessionNavigationInFlight) return;
              const deleteContext = {
                currentSessionName: options.sessionLabel || 'this session',
                annotationCount: options.annotationCount ?? 0,
                screenCount: options.screenCount ?? 0,
              };
              const hasDraftForThisSession = draftWorkspace?.context?.sessionId === sessionId
                && draftItemList().length > 0;
              if (hasDraftForThisSession) {
                if (await resolvePendingBeforeBoundary('delete-session', sessionId, {
                  ...deleteContext,
                  annotationCount: deleteContext.annotationCount + draftItemList().length,
                }) !== 'continue') return;
              } else {
                const choice = await promptBoundaryDialogChoice('sessionDelete', deleteContext);
                if (choice !== 'confirm') return;
              }
              const isDisplayedSession = () => state.session?.sessionId === sessionId;
              const hasDisplayedDraftForDeletedSession = () => draftWorkspace?.context?.sessionId === sessionId;
              const hasPendingRecoveryForDeletedSession = () =>
                (pendingRecovery?.sessionId || pendingRecovery?.context?.sessionId) === sessionId;
              const wasDisplayedSession = isDisplayedSession();
              const wasDisplayedDraft = hasDisplayedDraftForDeletedSession();
              const hadPendingRecovery = hasPendingRecoveryForDeletedSession();
              bumpSessionMutationGeneration();
              await withMutationLock(() => requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              createBrowserDraftPorts().storage.deleteWorkspacesForSession(sessionId);
              if (hadPendingRecovery) pendingRecovery = null;
              if (wasDisplayedSession || wasDisplayedDraft) {
                clearDisplayedSessionState();
              }
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function clearLocalDraft() {
              error.textContent = '';
              if (!draftFlow() && !pendingRecoveryItems(pendingRecovery).length) return;
              const accepted = await promptBoundaryDialogChoice('clearLocalDraft', {
                itemCount: draftWorkspaceItems(draftWorkspace).length || pendingRecoveryItems(pendingRecovery).length,
              });
              if (accepted !== 'confirm') return;
              deleteCurrentDraftWorkspaceStorage();
              if (pendingRecovery?.schemaVersion === 2) {
                createBrowserDraftPorts().storage.deleteWorkspace(
                  pendingRecovery.sessionId || pendingRecovery.context?.sessionId,
                  pendingRecovery.workspaceId,
                );
              }
              pendingRecovery = null;
              replaceDraftWorkspace(createEmptyDraftWorkspace());
              clearSelection();
              renderPendingRecoveryBanner();
              render();
            }

            async function clearServerDrafts() {
              error.textContent = '';
              const sessionId = state.session?.sessionId;
              if (!sessionId) return;
              if (await resolvePendingBeforeBoundary('clear-server-drafts', sessionId) !== 'continue') return;
              const accepted = await promptBoundaryDialogChoice('clearServerDrafts', {
                itemCount: savedEvidenceItems().filter((item) => item.delivery !== 'sent').length,
              });
              if (accepted !== 'confirm') return;
              await withMutationLock(() => requestJson('/api/items/draft?sessionId=' + encodeURIComponent(sessionId), { method: 'DELETE' }));
              clearSelection();
              await refresh();
            }
