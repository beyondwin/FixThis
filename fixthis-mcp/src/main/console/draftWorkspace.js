// draftWorkspace.js - pure DraftWorkspace domain policy.
// No DOM, fetch, localStorage, timers, or global console state in this file.

const DraftLifecycle = Object.freeze({
  EMPTY: 'empty',
  CAPTURING: 'capturing',
  EDITING: 'editing',
  SAVING: 'saving',
  RECOVERING: 'recovering',
  CONFLICT: 'conflict',
  CLOSED: 'closed',
});

function createEmptyDraftWorkspace() {
  return {
    workspaceId: null,
    revision: 0,
    lifecycle: DraftLifecycle.EMPTY,
    context: null,
    screen: null,
    screenshotUrl: null,
    items: [],
    history: { undoStack: [], redoStack: [] },
    focusedItemId: null,
    currentSelection: null,
    activityDriftWarning: null,
    lastError: null,
  };
}

function cloneDraftValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function createDraftContext({ sessionId, previewId, screenId, screenFingerprint, deviceSerial, frozenAtEpochMillis, activityName }) {
  return {
    sessionId: sessionId || null,
    previewId: previewId || null,
    screenId: screenId || null,
    screenFingerprint: screenFingerprint ?? null,
    deviceSerial: deviceSerial || null,
    frozenAtEpochMillis: frozenAtEpochMillis || null,
    activityName: activityName || null,
  };
}

function createFrozenDraftWorkspace({ workspaceId, context, screen, screenshotUrl, history = null }) {
  if (!workspaceId) throw new Error('workspaceId is required');
  if (!context?.sessionId || !context?.previewId) throw new Error('Draft context requires sessionId and previewId');
  return {
    ...createEmptyDraftWorkspace(),
    workspaceId,
    revision: 1,
    lifecycle: DraftLifecycle.EDITING,
    context: cloneDraftValue(context),
    screen: cloneDraftValue(screen),
    screenshotUrl: screenshotUrl || null,
    history: history || { undoStack: [], redoStack: [] },
  };
}

function draftWorkspaceItems(workspace) {
  return Array.isArray(workspace?.items) ? workspace.items : [];
}

function requireDraftContext(workspace) {
  const context = workspace?.context;
  if (!context?.sessionId || !context?.previewId) {
    throw new Error('Annotation context is missing. Re-capture the screen and try again.');
  }
  return context;
}

function shouldIgnoreDraftAction(workspace, action) {
  if (!workspace) return true;
  if (action.workspaceId && workspace.workspaceId && action.workspaceId !== workspace.workspaceId) return true;
  if (Number.isInteger(action.expectedRevision) && workspace.revision !== action.expectedRevision) return true;
  return false;
}

function bumpDraftRevision(workspace, patch) {
  return {
    ...workspace,
    ...patch,
    revision: workspace.revision + 1,
  };
}

function reduceDraftWorkspace(workspace = createEmptyDraftWorkspace(), action = {}) {
  if (shouldIgnoreDraftAction(workspace, action)) return workspace;
  switch (action.type) {
    case 'FREEZE_STARTED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CAPTURING,
        lastError: null,
      });
    case 'FREEZE_SUCCEEDED':
      return createFrozenDraftWorkspace(action);
    case 'ADD_ITEM':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        items: draftWorkspaceItems(workspace).concat(cloneDraftValue(action.draftItem)),
        focusedItemId: action.draftItem?.draftItemId || workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'UPDATE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).map((item) =>
          item.draftItemId === action.draftItemId ? { ...item, ...cloneDraftValue(action.patch) } : item
        ),
        lastError: null,
      });
    case 'DELETE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).filter((item) => item.draftItemId !== action.draftItemId),
        focusedItemId: workspace.focusedItemId === action.draftItemId ? null : workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'FOCUS_ITEM':
      return bumpDraftRevision(workspace, { focusedItemId: action.draftItemId || null });
    case 'CLEAR_FOCUS':
      return bumpDraftRevision(workspace, { focusedItemId: null, currentSelection: null });
    case 'RESTORE_RECOVERY':
      return bumpDraftRevision({
        ...createFrozenDraftWorkspace(action.workspace),
        revision: action.workspace?.revision || 1,
        items: cloneDraftValue(action.workspace?.items || []),
        history: cloneDraftValue(action.workspace?.history || { undoStack: [], redoStack: [] }),
        lifecycle: DraftLifecycle.EDITING,
      }, {});
    case 'SAVE_STARTED':
      return bumpDraftRevision(workspace, { lifecycle: DraftLifecycle.SAVING, lastError: null });
    case 'SAVE_SUCCEEDED':
      return createEmptyDraftWorkspace();
    case 'SAVE_CONFLICT':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CONFLICT,
        lastError: cloneDraftValue(action.conflict || { error: 'conflict' }),
      });
    case 'SAVE_FAILED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        lastError: cloneDraftValue(action.error || { error: 'save_failed' }),
      });
    case 'DISCARD':
    case 'SESSION_BOUNDARY_CLOSED':
      return createEmptyDraftWorkspace();
    default:
      return workspace;
  }
}
