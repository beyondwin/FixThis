// @requires domain/workspaceState.js, domain/consoleAppState.js, domain/consoleInvariants.js
function reduceConsoleAppState(state = createInitialConsoleAppState(), event = {}) {
  let result;
  switch (event.type) {
    case 'SESSION_ROW_CLICKED':
      result = reduceSessionRowClicked(state, event);
      break;
    case 'SESSION_OPEN_SUCCEEDED':
      result = reduceSessionOpenSucceeded(state, event);
      break;
    case 'SESSIONS_POLL_SUCCEEDED':
      result = reduceSessionsPollSucceeded(state, event);
      break;
    case 'ANNOTATE_CLICKED':
      result = reduceAnnotateClicked(state);
      break;
    case 'DRAFT_STARTED_FROM_PREVIEW':
      result = reduceDraftStartedFromPreview(state, event);
      break;
    case 'DRAFT_TARGET_SELECTED':
      result = reduceDraftTargetSelected(state, event);
      break;
    case 'DRAFT_COMMENT_CHANGED':
      result = reduceDraftCommentChanged(state, event);
      break;
    case 'DRAFT_ITEM_FOCUSED':
      result = reduceDraftItemFocused(state, event);
      break;
    case 'DRAFT_ITEM_DELETED':
      result = reduceDraftItemDeleted(state, event);
      break;
    case 'DRAFT_SELECTION_CLEARED':
      result = isDraftWorkspace(state.workspace)
        ? { state: replaceConsoleState(state, { workspace: draftWorkspaceWithPatch(state.workspace, { currentSelection: null }) }), effects: [] }
        : { state, effects: [] };
      break;
    case 'DRAFT_DISCARDED':
      result = reduceDraftDiscarded(state);
      break;
    case 'PREVIEW_CAPTURE_SUCCEEDED':
      result = reducePreviewCaptureSucceeded(state, event);
      break;
    case 'BOUNDARY_CANCEL_CLICKED':
      result = { state: replaceConsoleState(state, { pendingBoundary: null }), effects: [] };
      break;
    case 'BOUNDARY_KEEP_RECOVERY_CLICKED':
      result = reduceBoundaryKeepRecoveryClicked(state);
      break;
    case 'BOUNDARY_DISCARD_CLICKED':
      result = reduceBoundaryDiscardClicked(state);
      break;
    case 'BOUNDARY_SAVE_DRAFT_CLICKED':
    case 'SAVE_TO_MCP_CLICKED':
      result = reduceSaveDraftClicked(state);
      break;
    case 'DRAFT_SAVE_SUCCEEDED':
      result = reduceDraftSaveSucceeded(state, event);
      break;
    case 'DRAFT_SAVE_FAILED':
      result = reduceDraftSaveFailed(state, event);
      break;
    case 'UNDO_CLICKED':
    case 'REDO_CLICKED':
      result = { state, effects: [] };
      break;
    default:
      result = { state, effects: [] };
  }
  assertConsoleInvariants(result.state);
  return result;
}

function reduceSessionRowClicked(state, event) {
  const sessionId = event.sessionId || null;
  if (!sessionId || sessionId === state.activeSessionId) return { state, effects: [] };
  if (isDraftWorkspace(state.workspace) && state.workspace.context.sessionId !== sessionId) {
    return {
      state: replaceConsoleState(state, {
        pendingBoundary: Object.freeze({
          kind: 'session-switch',
          fromSessionId: state.workspace.context.sessionId,
          targetSessionId: sessionId,
          draftSummary: Object.freeze({
            itemCount: state.workspace.items.length,
            missingCommentCount: state.workspace.items.filter((item) => !String(item.comment || '').trim()).length,
            previewId: state.workspace.context.previewId,
          }),
        }),
      }),
      effects: [],
    };
  }
  const generation = nextGeneration(state);
  return {
    state: replaceConsoleState(state, { effectsGeneration: generation }),
    effects: [Object.freeze({ kind: 'openSession', requestId: nextRequestId('open-session', generation), sessionId, generation })],
  };
}

function reduceSessionOpenSucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  let next = upsertSession(state, event.session);
  next = replaceConsoleState(next, {
    activeSessionId: event.sessionId,
    workspace: livePreviewWorkspace(event.sessionId, null),
    pendingBoundary: null,
    tool: Object.freeze({ mode: 'select' }),
  });
  return { state: next, effects: [] };
}

function reduceSessionsPollSucceeded(state, event) {
  if (event.generation && event.generation !== state.effectsGeneration) return { state, effects: [] };
  return { state: replaceSessions(state, event.sessions || []), effects: [] };
}

function reduceAnnotateClicked(state) {
  if (!state.activeSessionId || isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const generation = nextGeneration(state);
  return {
    state: replaceConsoleState(state, {
      effectsGeneration: generation,
      tool: Object.freeze({ mode: 'annotate', hoveredTarget: null, drag: null }),
    }),
    effects: [Object.freeze({ kind: 'capturePreview', requestId: nextRequestId('capture-preview', generation), sessionId: state.activeSessionId, generation })],
  };
}

function reduceDraftStartedFromPreview(state, event) {
  return {
    state: replaceConsoleState(state, {
      activeSessionId: event.sessionId,
      workspace: draftWorkspaceFromPreview(event.sessionId, event.preview, event),
      tool: Object.freeze({ mode: 'annotate', hoveredTarget: null, drag: null }),
      pendingBoundary: null,
    }),
    effects: [],
  };
}

function reduceDraftTargetSelected(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const item = Object.freeze({
    itemId: event.itemId || event.annotationId || `draft-${state.workspace.items.length + 1}`,
    annotationId: event.annotationId || event.itemId || `draft-${state.workspace.items.length + 1}`,
    sequenceNumber: state.workspace.items.length + 1,
    comment: event.comment || '',
    selection: cloneConsoleValue(event.selection || null),
    targetEvidence: cloneConsoleValue(event.targetEvidence || null),
  });
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, {
        items: [...state.workspace.items, item],
        focusedItemId: item.itemId,
        currentSelection: item.selection,
      }),
    }),
    effects: [],
  };
}

function reduceDraftCommentChanged(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const itemId = event.itemId || state.workspace.focusedItemId;
  const items = state.workspace.items.map((item) =>
    (item.itemId === itemId || item.annotationId === itemId) ? { ...item, comment: event.comment || '' } : item,
  );
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, { items }),
    }),
    effects: [],
  };
}

function reduceDraftItemFocused(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const item = state.workspace.items.find((candidate) => candidate.itemId === event.itemId || candidate.annotationId === event.itemId);
  if (!item) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, {
        focusedItemId: item.itemId,
        currentSelection: item.selection || null,
      }),
    }),
    effects: [],
  };
}

function reduceDraftItemDeleted(state, event) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const removed = state.workspace.items.find((item) => item.itemId === event.itemId || item.annotationId === event.itemId);
  if (!removed) return { state, effects: [] };
  const items = state.workspace.items.filter((item) => item.itemId !== event.itemId && item.annotationId !== event.itemId);
  const focusedItemId = state.workspace.focusedItemId === removed.itemId ? null : state.workspace.focusedItemId;
  return {
    state: replaceConsoleState(state, {
      workspace: draftWorkspaceWithPatch(state.workspace, {
        items,
        focusedItemId,
        currentSelection: focusedItemId ? state.workspace.currentSelection : null,
      }),
    }),
    effects: [],
  };
}

function reduceDraftDiscarded(state) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: livePreviewWorkspace(state.workspace.context.sessionId, null),
      pendingBoundary: null,
      promptAction: Object.freeze({ inFlight: false }),
      tool: Object.freeze({ mode: 'select' }),
    }),
    effects: [Object.freeze({
      kind: 'deleteRecovery',
      sessionId: state.workspace.context.sessionId,
      workspaceId: state.workspace.context.workspaceId,
    })],
  };
}

function reducePreviewCaptureSucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (event.sessionId !== state.activeSessionId) return { state, effects: [] };
  if (isDraftWorkspace(state.workspace)) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      workspace: livePreviewWorkspace(event.sessionId, event.preview),
    }),
    effects: [],
  };
}

function reduceBoundaryKeepRecoveryClicked(state) {
  if (!state.pendingBoundary || !isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const generation = nextGeneration(state);
  const recovery = cloneConsoleValue(state.workspace);
  const targetSessionId = state.pendingBoundary.targetSessionId;
  return {
    state: replaceConsoleState(state, {
      effectsGeneration: generation,
      activeSessionId: targetSessionId,
      workspace: livePreviewWorkspace(targetSessionId, null),
      pendingBoundary: null,
      tool: Object.freeze({ mode: 'select' }),
    }),
    effects: [
      Object.freeze({ kind: 'persistRecovery', sessionId: recovery.context.sessionId, workspace: recovery }),
      Object.freeze({ kind: 'openSession', requestId: nextRequestId('open-session', generation), sessionId: targetSessionId, generation }),
    ],
  };
}

function reduceBoundaryDiscardClicked(state) {
  if (!state.pendingBoundary) return { state, effects: [] };
  const generation = nextGeneration(state);
  const targetSessionId = state.pendingBoundary.targetSessionId;
  return {
    state: replaceConsoleState(state, {
      effectsGeneration: generation,
      activeSessionId: targetSessionId,
      workspace: livePreviewWorkspace(targetSessionId, null),
      pendingBoundary: null,
      tool: Object.freeze({ mode: 'select' }),
    }),
    effects: [Object.freeze({ kind: 'openSession', requestId: nextRequestId('open-session', generation), sessionId: targetSessionId, generation })],
  };
}

function reduceSaveDraftClicked(state) {
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  const generation = nextGeneration(state);
  const targetSessionId = state.pendingBoundary?.targetSessionId || state.activeSessionId;
  return {
    state: replaceConsoleState(state, {
      effectsGeneration: generation,
      promptAction: Object.freeze({ inFlight: true }),
    }),
    effects: [Object.freeze({
      kind: 'saveDraft',
      requestId: nextRequestId('save-draft', generation),
      sessionId: state.workspace.context.sessionId,
      targetSessionId,
      workspaceId: state.workspace.context.workspaceId,
      items: cloneConsoleValue(state.workspace.items),
      generation,
    })],
  };
}

function reduceDraftSaveSucceeded(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  if (event.workspaceId && event.workspaceId !== state.workspace.context.workspaceId) return { state, effects: [] };
  const nextSessionId = state.pendingBoundary?.targetSessionId || event.targetSessionId || event.sessionId || state.activeSessionId;
  let next = event.session ? upsertSession(state, event.session) : state;
  next = replaceConsoleState(next, {
    activeSessionId: nextSessionId,
    workspace: livePreviewWorkspace(nextSessionId, null),
    pendingBoundary: null,
    promptAction: Object.freeze({ inFlight: false }),
    tool: Object.freeze({ mode: 'select' }),
  });
  return { state: next, effects: [] };
}

function reduceDraftSaveFailed(state, event) {
  if (event.generation !== state.effectsGeneration) return { state, effects: [] };
  if (!isDraftWorkspace(state.workspace)) return { state, effects: [] };
  if (event.workspaceId && event.workspaceId !== state.workspace.context.workspaceId) return { state, effects: [] };
  return {
    state: replaceConsoleState(state, {
      promptAction: Object.freeze({ inFlight: false }),
      status: Object.freeze({ message: event.error || 'Could not save draft.', variant: 'error', assertive: true }),
    }),
    effects: [],
  };
}
