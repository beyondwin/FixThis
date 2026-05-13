// draftApiAdapter.js - explicit-session HTTP adapter for DraftWorkspace use cases.

function draftItemToAnnotationDraftDto(item, options = {}) {
  const rawComment = String(item.comment || '');
  const fallbackComment = String(item.label || '').trim() || (item.targetType === 'node' ? 'Component target' : 'Custom area');
  return {
    targetType: item.targetType,
    bounds: item.bounds,
    nodeUid: item.nodeUid,
    label: String(item.label || '').trim() || null,
    severity: item.severity || 'med',
    status: String(item.status || 'open').replace('-', '_'),
    comment: options.allowFallbackComments ? (rawComment.trim() || fallbackComment) : rawComment,
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
    screen: workspace.screen || null,
    items: (workspace.items || [])
      .filter((item) => options.allowBlankComments || options.allowFallbackComments || String(item.comment || '').trim())
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
      throw new Error(await response.text?.() || 'HTTP ' + response.status);
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
