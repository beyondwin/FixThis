// @requires (none)
function okBoundary(value) {
  return Object.freeze({ ok: true, value });
}

function errorBoundary(code, message) {
  return Object.freeze({ ok: false, error: Object.freeze({ code, message }) });
}

function isObjectLike(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function cloneBoundaryValue(value) {
  if (value === null || typeof value !== 'object') return value;
  return JSON.parse(JSON.stringify(value));
}

function normalizeSessionPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_session_payload', 'Session payload must be an object.');
  }
  const sessionId = String(payload.sessionId || '').trim();
  if (!sessionId) {
    return errorBoundary('missing_session_id', 'Session payload is missing sessionId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    sessionId,
    items: Array.isArray(payload.items) ? cloneBoundaryValue(payload.items) : [],
    screens: Array.isArray(payload.screens) ? cloneBoundaryValue(payload.screens) : [],
  }));
}

function normalizePreviewPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_preview_payload', 'Preview payload must be an object.');
  }
  const previewId = String(payload.previewId || '').trim();
  const screen = isObjectLike(payload.screen) ? payload.screen : null;
  const screenId = String(screen?.screenId || payload.screenId || '').trim();
  if (!previewId) {
    return errorBoundary('missing_preview_id', 'Preview payload is missing previewId.');
  }
  if (!screenId) {
    return errorBoundary('missing_screen_id', 'Preview payload is missing screenId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    previewId,
    screen: Object.freeze({ ...cloneBoundaryValue(screen || {}), screenId }),
  }));
}

function normalizeDraftItemPayload(payload) {
  if (!isObjectLike(payload)) {
    return errorBoundary('invalid_draft_item', 'Draft item payload must be an object.');
  }
  const itemId = String(payload.itemId || payload.annotationId || '').trim();
  if (!itemId) {
    return errorBoundary('missing_item_id', 'Draft item payload is missing itemId.');
  }
  return okBoundary(Object.freeze({
    ...cloneBoundaryValue(payload),
    itemId,
    annotationId: String(payload.annotationId || itemId),
    comment: String(payload.comment || ''),
    selection: cloneBoundaryValue(payload.selection || null),
    targetEvidence: cloneBoundaryValue(payload.targetEvidence || null),
  }));
}

function normalizeStoredJson(text) {
  if (!text) return okBoundary(null);
  try {
    return okBoundary(JSON.parse(text));
  } catch (_) {
    return errorBoundary('invalid_storage_payload', 'Storage payload is not valid JSON.');
  }
}
