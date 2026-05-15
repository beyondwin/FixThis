// @requires (none)
function requireEventString(name, value) {
  const normalized = String(value || '').trim();
  if (!normalized) throw new Error(`${name} is required`);
  return normalized;
}

function freezeEvent(event) {
  return Object.freeze(event);
}

const ConsoleEvents = Object.freeze({
  sessionRowClicked(sessionId) {
    return freezeEvent({
      type: 'SESSION_ROW_CLICKED',
      sessionId: requireEventString('sessionId', sessionId),
    });
  },

  annotateClicked() {
    return freezeEvent({ type: 'ANNOTATE_CLICKED' });
  },

  draftTargetSelected(payload) {
    return freezeEvent({
      type: 'DRAFT_TARGET_SELECTED',
      ...payload,
    });
  },

  draftCommentChanged(itemId, comment) {
    return freezeEvent({
      type: 'DRAFT_COMMENT_CHANGED',
      itemId: itemId || null,
      comment: String(comment || ''),
    });
  },

  previewCaptureSucceeded(sessionId, preview, generation) {
    return freezeEvent({
      type: 'PREVIEW_CAPTURE_SUCCEEDED',
      sessionId: requireEventString('sessionId', sessionId),
      preview,
      generation,
    });
  },
});
