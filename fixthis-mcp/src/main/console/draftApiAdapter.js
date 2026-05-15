// @requires draftPorts.js, draftWorkspace.js
// draftApiAdapter.js - explicit-session HTTP adapter for DraftWorkspace use cases.

function draftItemToAnnotationDraftDto(item, options = {}) {
  const rawComment = String(item.comment || '');
  const fallbackComment = String(item.label || '').trim() || (item.targetType === 'node' ? 'Component target' : 'Custom area');
  return {
    draftItemId: item.draftItemId || null,
    targetType: item.targetType,
    bounds: item.bounds,
    nodeUid: item.nodeUid,
    label: String(item.label || '').trim() || null,
    severity: item.severity || 'med',
    status: String(item.status || 'open').replace('-', '_'),
    comment: options.allowFallbackComments ? (rawComment.trim() || fallbackComment) : rawComment,
  };
}

function createDraftApiError({ error, message, action, status }) {
  const typed = new Error(message || error || ('HTTP ' + status));
  typed.code = error || 'http_error';
  typed.action = action || null;
  typed.status = status || null;
  return typed;
}

function isValidSelectionBounds(bounds) {
  return Number.isFinite(bounds?.left) &&
    Number.isFinite(bounds?.top) &&
    Number.isFinite(bounds?.right) &&
    Number.isFinite(bounds?.bottom) &&
    bounds.right > bounds.left &&
    bounds.bottom > bounds.top;
}

function requireValidSelectionBounds(item) {
  if (item?.targetType !== 'area' && item?.targetType !== 'node') return;
  if (isValidSelectionBounds(item.bounds)) return;
  throw createDraftApiError({
    error: 'invalid_selection_bounds',
    message: 'Selection bounds are invalid for draft item: ' + (item?.draftItemId || 'unknown'),
    action: 'recapture_or_select_area',
  });
}

function screenHasNodeUid(screen, nodeUid) {
  if (!screen || !nodeUid) return false;
  return (screen.roots || []).some((root) => {
    return (root?.mergedNodes || []).some((node) => node?.uid === nodeUid) ||
      (root?.unmergedNodes || []).some((node) => node?.uid === nodeUid);
  });
}

function normalizeDraftItemForScreen(item, screen) {
  if (item?.targetType !== 'node' || !item.nodeUid || !screen || screenHasNodeUid(screen, item.nodeUid)) {
    return item;
  }
  return {
    ...item,
    targetType: 'area',
    nodeUid: undefined,
  };
}

function buildDraftWorkspaceSaveRequest(workspace, options = {}) {
  const context = workspace?.context || {};
  if (!context.sessionId) throw new Error('Draft save requires sessionId');
  if (!context.previewId) throw new Error('Draft save requires previewId');
  requireDraftContext(workspace);
  return {
    sessionId: context.sessionId,
    previewId: context.previewId,
    workspaceId: workspace.workspaceId || null,
    screen: workspace.screen || null,
    items: (workspace.items || [])
      .filter((item) => options.allowBlankComments || options.allowFallbackComments || String(item.comment || '').trim())
      .map((item) => {
        requireValidSelectionBounds(item);
        return item;
      })
      .map((item) => normalizeDraftItemForScreen(item, workspace.screen))
      .map((item) => draftItemToAnnotationDraftDto(item, options)),
    frozenFingerprint: context.screenFingerprint,
    forceMismatchOverride: Boolean(options.forceMismatchOverride),
  };
}

function createDraftApiHeaders(consoleToken) {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (consoleToken) headers.set('X-FixThis-Console-Token', consoleToken);
  return headers;
}

function createDraftApiAdapter({ fetchImpl = fetch, consoleToken = null } = {}) {
  async function readErrorBody(response) {
    const parsed = await response.json?.().catch(() => null);
    if (parsed && typeof parsed === 'object') return parsed;
    const message = await response.text?.().catch(() => '') || 'HTTP ' + response.status;
    return { error: 'http_error', message };
  }

  async function requestJson(path, body) {
    const response = await fetchImpl(path, {
      method: 'POST',
      headers: createDraftApiHeaders(consoleToken),
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      if (response.status === 409) {
        const conflict = await response.json().catch(() => ({}));
        return { conflict };
      }
      const errorBody = await readErrorBody(response);
      throw createDraftApiError({ ...errorBody, status: response.status });
    }
    return await response.json();
  }

  return {
    saveDraftWorkspace: (request) => requestJson('/api/items/batch', request),
    saveToMcp: (sessionId, itemIds) => requestJson('/api/agent-handoffs', { sessionId, itemIds }),
    handoffPreview: async (sessionId, itemIds) => {
      const response = await fetchImpl('/api/sessions/' + encodeURIComponent(sessionId) + '/handoff-preview', {
        method: 'POST',
        headers: createDraftApiHeaders(consoleToken),
        body: JSON.stringify({ itemIds }),
      });
      if (!response.ok) throw new Error(await response.text?.() || 'HTTP ' + response.status);
      return await response.text();
    },
    markHandedOff: (sessionId, itemIds) =>
      requestJson('/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off', { itemIds }),
  };
}
