// @requires state.js, draftWorkspace.js, boundaryDialogVariants.js
// historySessionRow.js - session row HTML helpers for the history drawer.

function historySessionRowHtml({ session, label, pendingSummary, pips, activeId, busy, navigationBusy }) {
  return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active ' : '') + (busy ? 'is-busy' : '') + '" role="button" tabindex="0" aria-busy="' + (busy ? 'true' : 'false') + '" aria-disabled="' + (navigationBusy ? 'true' : 'false') + '" data-session-id="' + escapeHtml(session.sessionId) + '">' +
    '<span class="hi-head">' +
      '<span class="hi-title">' + escapeHtml(label) + '</span>' +
      historySessionDeleteButtonHtml(session, label, navigationBusy) +
    '</span>' +
    '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
    (pendingSummary ? '<div class="session-pending-summary">' + escapeHtml(pendingSummary) + '</div>' : '') +
    '<span class="hi-stats">' + pips + '</span>' +
    '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
  '</div>';
}

function historySessionDeleteButtonHtml(session, label, navigationBusy) {
  return '<button type="button" class="session-row-delete icon-button" data-delete-session-id="' + escapeHtml(session.sessionId) + '" data-delete-session-label="' + escapeHtml(label) + '" data-delete-session-annotations="' + escapeHtml(String(session.itemsCount || 0)) + '" data-delete-session-screens="' + escapeHtml(String(session.screensCount || 0)) + '" aria-label="Delete session" title="Delete session"' + (navigationBusy ? ' disabled' : '') + '>' +
    '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M6 6l1 14a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-14" stroke="currentColor"/>' +
    '</svg>' +
  '</button>';
}

function bindHistorySessionRowEvents(root) {
  root.querySelectorAll('.session-row').forEach(row => {
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
  root.querySelectorAll('[data-delete-session-id]').forEach(button => {
    button.addEventListener('click', event => {
      event.stopPropagation();
      deleteHistorySession(button.dataset.deleteSessionId, {
        sessionLabel: button.dataset.deleteSessionLabel,
        annotationCount: Number(button.dataset.deleteSessionAnnotations || 0),
        screenCount: Number(button.dataset.deleteSessionScreens || 0),
      }).catch(showError);
    });
  });
}
