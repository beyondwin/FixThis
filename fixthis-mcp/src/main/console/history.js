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
              if (!addItemsFlow || state.session?.sessionId !== session?.sessionId) return [];
              return pendingFeedbackItems;
            }

            function historyOpenCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              return (session.unresolvedItemsCount || 0) + (session.inProgressItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length;
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

            function toolbarAnnotationCounts() {
              if (addItemsFlow) {
                const pending = pendingFeedbackItems;
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
              if (focusedPendingItemIndex == null) return null;
              return pendingFeedbackItems[focusedPendingItemIndex] || null;
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
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              }));
              await refreshSessions();
            }

            async function enterAnnotateMode() {
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) return;
              await ensureSessionForAnnotating();
              toolModeUseCases.enterAnnotate();
              renderCurrentSessionList();
              if (!addItemsFlow) {
                await startAddItemsFlow();
              } else {
                renderPreviewOnly();
                renderInspectorRegion();
              }
            }

            async function enterNewHistoryAnnotateMode() {
              if (toolModeUseCases.getState().newHistoryAnnotateModeStarting) return;
              toolModeUseCases.setNewHistoryAnnotateModeStarting(true);
              toolModeUseCases.enterAnnotate();
              renderCurrentSessionList();
              try {
                await newSession();
                scrollActiveHistoryItemIntoView();
                await enterAnnotateMode();
                scrollActiveHistoryItemIntoView();
              } finally {
                toolModeUseCases.setNewHistoryAnnotateModeStarting(false);
                renderCurrentSessionList();
              }
            }

            function scrollActiveHistoryItemIntoView() {
              const activeRow = sessions.querySelector('.session-row.is-active');
              activeRow?.scrollIntoView({ block: 'nearest' });
            }

            function syncHistoryDrawerState() {
              if (!historyToggleButton || !historyDrawerScrim) return;
              const open = toolModeUseCases.getState().historyDrawerOpen;
              if (open) {
                document.body.dataset.historyDrawerOpen = 'true';
              } else {
                delete document.body.dataset.historyDrawerOpen;
              }
              historyToggleButton.setAttribute('aria-expanded', String(open));
            }

            function openHistoryDrawer() {
              toolModeUseCases.setHistoryDrawer(true);
              syncHistoryDrawerState();
              document.getElementById('historyPanel')?.focus({ preventScroll: true });
            }

            function closeHistoryDrawer(options = {}) {
              const wasOpen = toolModeUseCases.getState().historyDrawerOpen;
              toolModeUseCases.setHistoryDrawer(false);
              syncHistoryDrawerState();
              if (wasOpen && options.returnFocus !== false) historyToggleButton?.focus({ preventScroll: true });
            }

            historyToggleButton?.addEventListener('click', openHistoryDrawer);
            historyDrawerScrim?.addEventListener('click', () => closeHistoryDrawer());
            document.addEventListener('keydown', event => {
              if (event.key === 'Escape' && toolModeUseCases.getState().historyDrawerOpen) {
                event.preventDefault();
                closeHistoryDrawer();
              }
            });
            syncHistoryDrawerState();

            function historyStartAnnotatingItemHtml() {
              if (toolModeUseCases.getState().newHistoryAnnotateModeStarting) return '';
              return '<button type="button" class="history-item history-add-row" data-start-new-history-annotating aria-label="Start annotating">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>' +
              '</button>';
            }


            function renderSessionsListFromPayload(sessionSummaries) {
              state.sessionSummaries = sessionSummaries;
              const activeId = state.session?.sessionId;
              const activeSummaries = sessionSummaries.filter(session => session.status !== 'closed');
              const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);
              const renderedActiveSummaries = stableHistorySessions(activeSummaries);
              sessionCount.textContent = String(activeSummaries.length);
              const renderedSessions = renderedActiveSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
                const pips = [
                  open > 0 ? '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' : '',
                  done > 0 ? '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'resolved', 'resolved')) + '</span>' : '',
                ].join('');
                return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<span class="hi-head">' +
                    '<span class="hi-title">' + escapeHtml(label) + '</span>' +
                    '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
                  '</span>' +
                  '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                  '<span class="hi-stats">' + pips + '</span>' +
                  '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
                '</div>';
              }).join('');
              sessions.innerHTML = renderedSessions
                ? renderedSessions + historyStartAnnotatingItemHtml()
                : historyStartAnnotatingItemHtml() + emptySessionsHtml();
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  openSession(row.dataset.sessionId)
                    .then(() => closeHistoryDrawer({ returnFocus: false }))
                    .catch(showError);
                });
                row.addEventListener('keydown', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openSession(row.dataset.sessionId)
                      .then(() => closeHistoryDrawer({ returnFocus: false }))
                      .catch(showError);
                  }
                });
              });
              document.querySelectorAll('[data-delete-session-id]').forEach(button => {
                button.addEventListener('click', event => {
                  event.stopPropagation();
                  deleteHistorySession(button.dataset.deleteSessionId).catch(showError);
                });
              });
              document.querySelectorAll('[data-start-new-history-annotating]').forEach(button => {
                button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));
              });
            }

            function renderSessionsList() {
              const activeId = state.session?.sessionId;
              document.querySelectorAll('.session-row').forEach(row => {
                row.classList.toggle('is-active', row.dataset.sessionId === activeId);
              });
            }

            function renderCurrentSessionList() {
              renderSessionsListFromPayload(state.sessionSummaries || []);
            }

            // Sync sidebar summaries and the active session in lockstep so the panel/toolbar
            // (driven by state.session) cannot drift behind the sidebar (driven by summaries).
            // Both endpoints fetched in parallel; if one fails the call rejects as before.
            async function refreshSessions() {
              const [response, currentSession] = await Promise.all([
                requestJson('/api/sessions'),
                requestJson('/api/session'),
              ]);
              state.session = currentSession || null;
              renderSessionsListFromPayload(response.sessions || []);
              return response.sessions || [];
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
              error.textContent = '';
              if (await resolvePendingBeforeBoundary('open-session', sessionId) !== 'continue') return;
              bumpSessionMutationGeneration();
              stopLivePreviewPolling();
              resetAnnotationComposerState(true, false);
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              await refresh();
              if (!latestPersistedScreen() && shouldAutoFetchPreview()) {
                await refreshPreview();
              }
              startLivePreviewPolling();
            }

            async function newSession() {
              error.textContent = '';
              if (await resolvePendingBeforeBoundary('new-session') !== 'continue') return;
              bumpSessionMutationGeneration();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              }));
              await refresh();
              startLivePreviewPolling();
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
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = null;
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function deleteHistorySession(sessionId) {
              error.textContent = '';
              if (!sessionId) return;
              if (await resolvePendingBeforeBoundary('delete-session', sessionId) !== 'continue') return;
              const isDisplayedSession = () => state.session?.sessionId === sessionId;
              const wasDisplayedSession = isDisplayedSession();
              bumpSessionMutationGeneration();
              await withMutationLock(() => requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              if (wasDisplayedSession) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
                state.session = null;
              }
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function clearDraft() {
              error.textContent = '';
              if (!window.confirm('Discard all unsent draft feedback items?')) return;
              await withMutationLock(() => requestJson('/api/items/draft', { method: 'DELETE' }));
              clearSelection();
              await refresh();
            }
