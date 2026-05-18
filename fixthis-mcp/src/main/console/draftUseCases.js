// @requires draftWorkspace.js, draftWorkspaceHistory.js, draftPorts.js, boundaryPolicy.js, toastCopy.js
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

function ensureWorkspaceForSelection(state, selection, ports = {}) {
  const existing = state?.draftWorkspace || state;
  if (existing?.workspaceId) return existing;
  if (!selection?.context) throw new Error('selection with context required to allocate workspace');
  const nextWorkspaceId = ports.nextWorkspaceId || ports.ids?.nextWorkspaceId;
  if (typeof nextWorkspaceId !== 'function') throw new Error('nextWorkspaceId port is required');
  return createFrozenDraftWorkspace({
    workspaceId: nextWorkspaceId(),
    context: selection.context,
    screen: selection.screen || null,
    screenshotUrl: selection.screenshotUrl || null,
  });
}

function maybeFreeWorkspaceOnEmpty(workspace, draftItemId) {
  const items = workspace?.items || [];
  if (items.length !== 1) return workspace;
  if (items[0]?.draftItemId !== draftItemId) return workspace;
  if (String(items[0]?.comment || '').length !== 0) return workspace;
  return null;
}

function draftItemHasWrittenComment(item) {
  return String(item?.comment || '').trim().length > 0;
}

function draftWorkspaceWithItems(workspace, items) {
  return {
    ...workspace,
    items: cloneDraftValue(items || []),
  };
}

function resolveTrigger(trigger, context, ports = {}) {
  const verdict = boundaryPolicy(trigger, context?.state || 'none', context || {});
  switch (verdict) {
    case 'pass':
      return verdict;
    case 'discardWithToast': {
      ports.silentDiscard?.(context);
      const text = toastTextForTrigger(trigger);
      if (text) ports.showToast?.(text, { className: 'info', duration: 2000, trigger });
      return verdict;
    }
    case 'silentSwap':
      ports.silentDiscard?.(context);
      return verdict;
    case 'boundaryDialog':
      ports.openBoundaryDialog?.(boundaryVariantForTrigger(trigger), context);
      return verdict;
    case 'discardConfirm':
      ports.openBoundaryDialog?.('editorBack', context);
      return verdict;
    case 'beforeunloadGuard':
      ports.beforeUnloadGuard?.(context);
      return verdict;
    case 'preserve':
    case 'retain':
    case 'retainWithBadge':
    case 'staleRevalidate':
    case 'retainAndMigrate':
      ports.preserve?.(context, verdict);
      return verdict;
    case 'silentLoss':
    default:
      return verdict;
  }
}

function boundaryVariantForTrigger(trigger) {
  switch (trigger) {
    case Trigger.SESSION_SWITCH:
      return 'sessionSwitch';
    case Trigger.SESSION_CREATE:
      return 'sessionCreate';
    case Trigger.SESSION_DELETE:
      return 'sessionDelete';
    case Trigger.ROUTE_CHANGE:
      return 'routeChange';
    case Trigger.EDITOR_BACK:
    case Trigger.ESCAPE_KEY:
      return 'editorBack';
    default:
      return 'editorBack';
  }
}

function updateDraftItem(workspace, draftItemId, patch, options = {}) {
  const before = (workspace.items || []).find((item) => item.draftItemId === draftItemId);
  const next = reduceDraftWorkspace(workspace, { type: 'UPDATE_ITEM', workspaceId: workspace.workspaceId, draftItemId, patch });
  const after = (next.items || []).find((item) => item.draftItemId === draftItemId);
  if (!before || !after || options.recordHistory === false) return next;
  return { ...next, history: recordDraftUpdate(next.history, before, after) };
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

async function persistDraftWorkspaceForBoundary(workspace, ports) {
  const items = workspace?.items || [];
  const writtenItems = items.filter(draftItemHasWrittenComment);
  if (!writtenItems.length) {
    ports.storage?.saveWorkspace?.(draftWorkspaceRecoveryEnvelope(workspace));
    return {
      workspace: reduceDraftWorkspace(workspace, { type: 'DISCARD', workspaceId: workspace.workspaceId }),
      session: null,
      itemIds: [],
    };
  }

  const result = await persistDraftWorkspace(
    draftWorkspaceWithItems(workspace, writtenItems),
    ports,
    { allowBlankComments: false },
  );
  return result;
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

function recoverySessionId(recovery) {
  return recovery?.sessionId || recovery?.context?.sessionId || null;
}

function recoveryWorkspaceId(recovery) {
  return recovery?.workspaceId || recovery?.context?.workspaceId || null;
}

function recoveryItems(recovery) {
  if (Array.isArray(recovery?.items)) return recovery.items;
  return [];
}

function hasCommentedRecovery(recovery) {
  return recoveryItems(recovery).some((item) => String(item?.comment || '').trim());
}

function draftRecoveryOwnership(recovery, session = null) {
  const total = recoveryItems(recovery).length;
  const commented = hasCommentedRecovery(recovery);
  const status = String(session?.status || '').toLowerCase();
  if (!total) {
    return Object.freeze({
      mode: 'none',
      total,
      commented,
      canResume: false,
      canRecapture: false,
      shouldAutoRestore: false,
    });
  }
  if (session?.deleted === true || status === 'deleted' || status === 'missing') {
    return Object.freeze({
      mode: 'deleted',
      total,
      commented,
      canResume: false,
      canRecapture: false,
      shouldAutoRestore: false,
    });
  }
  if (status === 'closed') {
    return Object.freeze({
      mode: 'closed',
      total,
      commented,
      canResume: false,
      canRecapture: true,
      shouldAutoRestore: false,
    });
  }
  return Object.freeze({
    mode: commented ? 'commented' : 'pin-only',
    total,
    commented,
    canResume: true,
    canRecapture: true,
    shouldAutoRestore: !commented,
  });
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
    const result = await persistDraftWorkspaceForBoundary(workspace, ports);
    return { choice, ...result };
  }
  return { choice: 'cancel', workspace };
}

async function resolveLifecycleBoundary({
  action,
  targetSessionId = null,
  boundaryContext = {},
  activeWorkspace,
  pendingRecovery,
  ports,
}) {
  requireDraftPort(ports, 'storage');
  const boundaryAction = { kind: action, sessionId: targetSessionId, context: boundaryContext || {} };
  let nextWorkspace = activeWorkspace || createEmptyDraftWorkspace();
  let nextPendingRecovery = pendingRecovery || null;
  let savedSession = null;
  const activeItems = nextWorkspace?.workspaceId ? (nextWorkspace.items || []) : [];

  if (activeItems.length) {
    const activeCommented = activeItems.some((item) => String(item?.comment || '').trim());
    if (!activeCommented && action !== 'delete-session' && action !== 'clear-local-draft' && action !== 'clear-server-drafts') {
      ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(nextWorkspace));
      nextWorkspace = createEmptyDraftWorkspace();
    } else {
      const result = await resolveDraftBoundary(nextWorkspace, boundaryAction, ports);
      nextWorkspace = result.workspace || nextWorkspace;
      savedSession = result.session || null;
      if (result.choice === 'cancel' || result.conflict) {
        return { outcome: 'cancel', nextWorkspace, nextPendingRecovery, savedSession, targetSessionId };
      }
      if (result.choice === 'save' || result.choice === 'discard' || result.choice === 'keep') {
        nextWorkspace = result.workspace || createEmptyDraftWorkspace();
        if (result.choice === 'keep') nextWorkspace = createEmptyDraftWorkspace();
      }
    }
  }

  if (!recoveryItems(nextPendingRecovery).length) {
    return { outcome: 'continue', nextWorkspace, nextPendingRecovery: null, savedSession, targetSessionId };
  }

  requireDraftPort(ports, 'recoveryPrompt');
  const choice = await ports.recoveryPrompt.choose(nextPendingRecovery, boundaryAction);
  if (choice === 'clear') {
    ports.storage.deleteWorkspace(recoverySessionId(nextPendingRecovery), recoveryWorkspaceId(nextPendingRecovery));
    return { outcome: 'continue', nextWorkspace, nextPendingRecovery: null, savedSession, targetSessionId };
  }
  if (choice === 'resume') {
    return {
      outcome: 'cancel',
      nextWorkspace: recoverDraftWorkspaceFromEnvelope(nextPendingRecovery),
      nextPendingRecovery: null,
      savedSession,
      targetSessionId,
    };
  }
  if (choice === 'recapture') {
    return { outcome: 'cancel', nextWorkspace, nextPendingRecovery, savedSession, targetSessionId };
  }
  return { outcome: 'cancel', nextWorkspace, nextPendingRecovery, savedSession, targetSessionId };
}
