// @requires draftWorkspace.js, draftWorkspaceHistory.js, draftPorts.js
// draftUseCases.js - DraftWorkspace application workflows over narrow ports.

async function startDraftFreeze(workspace, input, ports) {
  requireDraftPort(ports, 'preview');
  requireDraftPort(ports, 'ids');
  requireDraftPort(ports, 'clock');
  const preview = await ports.preview.capture();
  const sessionId = input?.sessionId;
  if (!sessionId) throw new Error('Cannot annotate without an active feedback session.');
  const context = createDraftContext({
    sessionId,
    previewId: preview.previewId,
    screenId: preview.screen?.screenId || null,
    screenFingerprint: preview.screen?.fingerprint ?? null,
    deviceSerial: input?.selectedDeviceSerial || null,
    frozenAtEpochMillis: ports.clock.now(),
    activityName: preview.activity || input?.activityName || null,
  });
  return createFrozenDraftWorkspace({
    workspaceId: ports.ids.nextWorkspaceId(),
    context,
    screen: preview.screen,
    screenshotUrl: ports.preview.screenshotUrl(preview.previewId, sessionId),
  });
}

function draftSelectionToItem(selection, ports) {
  return {
    draftItemId: ports.ids.nextDraftItemId(),
    targetType: selection.targetType,
    nodeUid: selection.nodeUid,
    bounds: selection.bounds,
    label: selection.label || null,
    severity: 'med',
    status: 'open',
    comment: '',
  };
}

function addDraftItem(workspace, selection, ports) {
  const draftItem = draftSelectionToItem(selection, ports);
  const next = reduceDraftWorkspace(workspace, { type: 'ADD_ITEM', workspaceId: workspace.workspaceId, draftItem });
  return { ...next, history: recordDraftAdd(next.history, draftItem) };
}

function updateDraftItem(workspace, draftItemId, patch) {
  const before = (workspace.items || []).find((item) => item.draftItemId === draftItemId);
  const next = reduceDraftWorkspace(workspace, { type: 'UPDATE_ITEM', workspaceId: workspace.workspaceId, draftItemId, patch });
  const after = (next.items || []).find((item) => item.draftItemId === draftItemId);
  return before && after ? { ...next, history: recordDraftUpdate(next.history, before, after) } : next;
}

function deleteDraftItem(workspace, draftItemId) {
  const index = (workspace.items || []).findIndex((item) => item.draftItemId === draftItemId);
  const before = index >= 0 ? workspace.items[index] : null;
  const next = reduceDraftWorkspace(workspace, { type: 'DELETE_ITEM', workspaceId: workspace.workspaceId, draftItemId });
  return before ? { ...next, history: recordDraftDelete(next.history, before, index) } : next;
}

async function persistDraftWorkspace(workspace, ports, options = {}) {
  const started = reduceDraftWorkspace(workspace, { type: 'SAVE_STARTED', workspaceId: workspace.workspaceId });
  const request = buildDraftWorkspaceSaveRequest(started, options);
  const response = await ports.feedbackApi.saveDraftWorkspace(request);
  if (response?.conflict?.error) {
    return {
      workspace: reduceDraftWorkspace(started, {
        type: 'SAVE_CONFLICT',
        workspaceId: started.workspaceId,
        expectedRevision: started.revision,
        conflict: response.conflict,
      }),
      session: null,
      conflict: response.conflict,
    };
  }
  ports.storage?.deleteWorkspace?.(started.context.sessionId, started.workspaceId);
  return {
    workspace: reduceDraftWorkspace(started, {
      type: 'SAVE_SUCCEEDED',
      workspaceId: started.workspaceId,
      expectedRevision: started.revision,
    }),
    session: response,
    itemIds: (response?.items || []).map((item) => item.itemId),
  };
}

function draftWorkspaceRecoveryEnvelope(workspace) {
  return {
    schemaVersion: 2,
    sessionId: workspace.context?.sessionId,
    workspaceId: workspace.workspaceId,
    revision: workspace.revision,
    lifecycle: workspace.lifecycle,
    context: workspace.context,
    screen: workspace.screen,
    screenshotUrl: workspace.screenshotUrl,
    items: workspace.items || [],
    history: workspace.history || { undoStack: [], redoStack: [] },
    updatedAtEpochMillis: Date.now(),
  };
}

function recoverDraftWorkspaceFromEnvelope(envelope) {
  if (envelope?.schemaVersion !== 2) throw new Error('Draft recovery requires schema v2 workspace envelope.');
  return {
    ...createFrozenDraftWorkspace({
      workspaceId: envelope.workspaceId,
      context: envelope.context,
      screen: envelope.screen,
      screenshotUrl: envelope.screenshotUrl,
      history: envelope.history || { undoStack: [], redoStack: [] },
    }),
    revision: envelope.revision || 1,
    items: envelope.items || [],
  };
}

async function resolveDraftBoundary(workspace, boundaryAction, ports) {
  if (!workspace?.workspaceId || !(workspace.items || []).length) return { choice: 'continue', workspace };
  const choice = await ports.boundaryPrompt.choose(workspace, boundaryAction);
  if (choice === 'keep') {
    ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(workspace));
    return { choice, workspace };
  }
  if (choice === 'discard') {
    ports.storage.deleteWorkspace(workspace.context?.sessionId, workspace.workspaceId);
    return { choice, workspace: reduceDraftWorkspace(workspace, { type: 'DISCARD', workspaceId: workspace.workspaceId }) };
  }
  if (choice === 'save') {
    const result = await persistDraftWorkspace(workspace, ports, { allowBlankComments: true });
    return { choice, ...result };
  }
  return { choice: 'cancel', workspace };
}
