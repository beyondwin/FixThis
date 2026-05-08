            function sessionOrdinalLookup(sessions) {
              const ordinalBySessionId = new Map();
              [...(sessions || [])]
                .sort((left, right) =>
                  (left.createdAtEpochMillis || 0) - (right.createdAtEpochMillis || 0) ||
                  String(left.sessionId || '').localeCompare(String(right.sessionId || ''))
                )
                .forEach((session, index) => {
                  ordinalBySessionId.set(session.sessionId, index + 1);
                });
              return ordinalBySessionId;
            }

            function formatSessionLabel(session, ordinal) {
              const safeOrdinal = Math.max(1, Number(ordinal || 1));
              return 'Session ' + safeOrdinal;
            }

            function formatSessionSummary(session) {
              return 'You · ' + formatHistoryDate(session.updatedAtEpochMillis);
            }

            function pendingHistoryItemsForSession(session) {
              if (!addItemsFlow || state.session?.sessionId !== session?.sessionId) return [];
              return pendingFeedbackItems;
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

            function historyPointsCount(session) {
              return (session.itemsCount || 0) + pendingHistoryItemsForSession(session).length;
            }

            function renderHistoryStrip(session) {
              const open = historyOpenCount(session);
              const done = historyDoneCount(session);
              const total = historyPointsCount(session);
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
              const number = item.sequenceNumber ? item.sequenceNumber : index + 1;
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function formatSavedEvidenceItemLabel(item, index) {
              return '#' + (index + 1) + ' ' + firstLine(item.comment || '(No comment)');
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

            function formatBatchLabel(batch) {
              return 'Batch #' + (batch.sequenceNumber || '-');
            }

            function batchItems(batch) {
              const itemsById = new Map((state.session?.items || []).map(item => [item.itemId, item]));
              return (batch.itemIds || []).map(itemId => itemsById.get(itemId) || { itemId: itemId, missing: true });
            }

            function formatBatchItemSummary(item) {
              if (item.missing) return 'Missing feedback item metadata.';
              return firstLine(item.comment || '(No comment)');
            }

            function formatBatchDetails(batch, items) {
              const count = (batch.itemIds || []).length;
              const itemCount = count + ' item' + (count === 1 ? '' : 's');
              return formatTime(batch.createdAtEpochMillis) + ' | ' + itemCount + ' | ' + (items.map(formatBatchItemSummary).join('; ') || 'No feedback items recorded.');
            }


            function hasActiveHistorySessionForAnnotating() {
              return Boolean(
                state.session &&
                state.session.status !== 'ready_for_agent' &&
                state.session.status !== 'closed' &&
                (state.sessionSummaries || []).some(session =>
                  session.sessionId === state.session.sessionId &&
                  session.status !== 'ready_for_agent' &&
                  session.status !== 'closed'
                )
              );
            }

            async function ensureSessionForAnnotating() {
              if (hasActiveHistorySessionForAnnotating()) return;
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refreshSessions();
            }

            async function enterAnnotateMode() {
              await ensureSessionForAnnotating();
              toolMode = 'annotate';
              renderCurrentSessionList();
              if (!addItemsFlow) {
                await startAddItemsFlow();
              } else {
                renderPreviewOnly();
                renderInspectorRegion();
              }
            }

            async function enterNewHistoryAnnotateMode() {
              if (newHistoryAnnotateModeStarting) return;
              newHistoryAnnotateModeStarting = true;
              toolMode = 'annotate';
              renderCurrentSessionList();
              try {
                await newSession();
                scrollActiveHistoryItemIntoView();
                await enterAnnotateMode();
                scrollActiveHistoryItemIntoView();
              } finally {
                newHistoryAnnotateModeStarting = false;
                renderCurrentSessionList();
              }
            }

            function scrollActiveHistoryItemIntoView() {
              const activeRow = sessions.querySelector('.session-row.is-active');
              activeRow?.scrollIntoView({ block: 'nearest' });
            }

            function historyStartAnnotatingItemHtml() {
              if (newHistoryAnnotateModeStarting) return '';
              return '<button type="button" class="history-item history-add-row" data-start-new-history-annotating aria-label="Start annotating">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>' +
              '</button>';
            }


            function renderSessionsListFromPayload(sessionSummaries) {
              state.sessionSummaries = sessionSummaries;
              const activeId = state.session?.sessionId;
              const activeSummaries = sessionSummaries.filter(session => session.status !== 'ready_for_agent' && session.status !== 'closed');
              const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);
              sessionCount.textContent = String(activeSummaries.length);
              const renderedSessions = activeSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const points = historyPointsCount(session);
                const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
                return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<span class="hi-head">' +
                    '<span class="hi-title">' + escapeHtml(label) + '</span>' +
                    '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
                  '</span>' +
                  '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                  '<span class="hi-stats">' +
                    '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' +
                    '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'done', 'done')) + '</span>' +
                    '<span class="hi-pip points">' + escapeHtml(countLabel(points, 'pt', 'pts')) + '</span>' +
                  '</span>' +
                  '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
                '</div>';
              }).join('');
              sessions.innerHTML = renderedSessions
                ? renderedSessions + historyStartAnnotatingItemHtml()
                : historyStartAnnotatingItemHtml() + emptySessionsHtml();
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  openSession(row.dataset.sessionId).catch(showError);
                });
                row.addEventListener('keydown', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openSession(row.dataset.sessionId).catch(showError);
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

            function sentHistorySummaries() {
              return (state.sessionSummaries || []).filter(session => session.status === 'ready_for_agent' || (session.sentBatchesCount || 0) > 0);
            }

            function renderSentHistory() {
              const session = state.session;
              const allItems = session?.items || [];
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
              const batchIds = new Set(handoffBatches.map(batch => batch.batchId));
              const batchedItemIds = new Set(handoffBatches.flatMap(batch => batch.itemIds || []));
              const batchRows = handoffBatches.map(batch => {
                const items = batchItems(batch);
                return '<div class="row">' +
                  '<strong>' + escapeHtml(formatBatchLabel(batch)) + '</strong>' +
                  '<span>' + escapeHtml(formatBatchDetails(batch, items)) + '</span>' +
                '</div>';
              });
              const unbatchedRows = sentItems
                .filter(item => !item.handoffBatchId || !batchIds.has(item.handoffBatchId) || !batchedItemIds.has(item.itemId))
                .map(item => {
                  const label = item.handoffBatchId ? 'Missing batch metadata' : 'Unbatched sent item';
                  const detail = item.handoffBatchId ? 'No batch metadata' : 'Sent outside a batch';
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(label) + '</strong>' +
                    '<span>' + escapeHtml(firstLine(item.comment || '(No comment)')) + ' · ' + escapeHtml(detail) + '</span>' +
                  '</div>';
                });
              const renderedSessionIds = new Set(session && (handoffBatches.length || sentItems.length) ? [session.sessionId] : []);
              const summaryRows = sentHistorySummaries()
                .filter(summary => !renderedSessionIds.has(summary.sessionId))
                .map((summary, index) => {
                  const sentCount = summary.itemsCount || summary.sentItemsCount || summary.sentBatchesCount || 0;
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(formatSessionLabel(summary, index)) + '</strong>' +
                    '<span>' + escapeHtml(countLabel(sentCount, 'annotation', 'annotations')) + ' sent · ' + escapeHtml(formatSessionSummary(summary)) + '</span>' +
                  '</div>';
                });
              const rows = batchRows.concat(unbatchedRows, summaryRows);
              clearSentHistoryButton.hidden = rows.length === 0;
              sentHistory.innerHTML = rows.join('') || '<div class="row"><span>No sent handoff history.</span></div>';
            }


            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              renderSessionsListFromPayload(response.sessions || []);
              return response.sessions || [];
            }

            async function refresh() {
              error.textContent = '';
              state.session = await requestJson('/api/session');
              await refreshSessions();
              await refreshDevices();
              await refreshConnection();
              render();
            }

            async function openSession(sessionId) {
              error.textContent = '';
              stopLivePreviewPolling();
              await flushPendingAnnotationsBeforeSessionChange();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              await refresh();
              if (!latestPersistedScreen() && shouldAutoFetchPreview()) {
                await refreshPreview();
              }
              startLivePreviewPolling();
            }

            async function newSession() {
              error.textContent = '';
              await flushPendingAnnotationsBeforeSessionChange();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refresh();
              startLivePreviewPolling();
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              resetAnnotationComposerState();
              invalidatePreviewContext();
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: state.session.sessionId })
              });
              state.session = null;
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function deleteHistorySession(sessionId) {
              error.textContent = '';
              if (!sessionId) return;
              const isDisplayedSession = () => state.session?.sessionId === sessionId;
              if (isDisplayedSession()) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
              }
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              if (isDisplayedSession()) {
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
              await requestJson('/api/items/draft', { method: 'DELETE' });
              clearSelection();
              await refresh();
            }

            async function clearSentHistory() {
              error.textContent = '';
              const ids = new Set(sentHistorySummaries().map(session => session.sessionId));
              if (state.session && ((state.session.handoffBatches || []).length || (state.session.items || []).some(item => item.delivery === 'sent'))) {
                ids.add(state.session.sessionId);
              }
              if (ids.size === 0) return;
              if (!window.confirm('Clear all sent history? Sent handoff records will be removed from this console.')) return;
              for (const sessionId of ids) {
                await requestJson('/api/session/close', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ sessionId: sessionId })
                });
              }
              if (state.session && ids.has(state.session.sessionId)) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
                state.session = null;
              }
              await refreshSessions();
              render();
              await refreshDevices();
            }
