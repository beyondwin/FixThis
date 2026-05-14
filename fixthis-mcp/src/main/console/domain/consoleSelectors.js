// @requires domain/workspaceState.js
function selectHistoryModel(state) {
  return Object.freeze({
    activeSessionId: state.activeSessionId,
    sessionIds: Object.freeze([...state.sessionOrder]),
    sessionsById: Object.freeze({ ...state.sessionsById }),
    workspaceKind: state.workspace.kind,
    draftSessionId: state.workspace.kind === WorkspaceKind.DRAFT ? state.workspace.context.sessionId : null,
    draftBadges: state.workspace.kind === WorkspaceKind.DRAFT
      ? Object.freeze([`${state.workspace.items.length} draft pins`, 'live paused'])
      : Object.freeze([]),
    pendingBoundary: state.pendingBoundary,
  });
}

function selectInspectorModel(state) {
  const workspace = state.workspace;
  if (workspace.kind === WorkspaceKind.DRAFT) {
    return Object.freeze({
      mode: 'draft',
      title: 'Draft Annotations',
      count: workspace.items.length,
      rows: Object.freeze([...workspace.items]),
      primaryAction: Object.freeze({ label: 'Add annotation', type: 'DRAFT_ADD_ANNOTATION_CLICKED' }),
      secondaryAction: Object.freeze({ label: 'Exit Annotate', type: 'DRAFT_EXIT_CLICKED' }),
    });
  }
  if (workspace.kind === WorkspaceKind.RECOVERY) {
    return Object.freeze({
      mode: 'recovery',
      title: 'Recovered Draft',
      count: 0,
      rows: Object.freeze([]),
      primaryAction: Object.freeze({ label: 'Recover', type: 'RECOVERY_RECOVER_CLICKED' }),
      secondaryAction: Object.freeze({ label: 'Discard', type: 'RECOVERY_DISCARD_CLICKED' }),
    });
  }
  if (workspace.kind === WorkspaceKind.SAVED_FOCUS) {
    return Object.freeze({
      mode: 'saved',
      title: 'Saved Annotations',
      count: 0,
      rows: Object.freeze([]),
      primaryAction: null,
      secondaryAction: null,
    });
  }
  return Object.freeze({
    mode: 'empty',
    title: 'Annotations',
    count: 0,
    rows: Object.freeze([]),
    primaryAction: Object.freeze({ label: 'Start annotating', type: 'ANNOTATE_CLICKED' }),
    secondaryAction: null,
  });
}

function selectCanvasModel(state) {
  const workspace = state.workspace;
  if (workspace.kind === WorkspaceKind.DRAFT) {
    return Object.freeze({
      mode: 'frozenDraft',
      sessionId: workspace.context.sessionId,
      screen: workspace.screen,
      screenshotUrl: workspace.screenshotUrl,
      pins: Object.freeze([...workspace.items]),
      lockLabel: 'Locked: Session ' + workspace.context.sessionId + ' · Preview ' + workspace.context.previewId + ' · Live preview paused',
    });
  }
  if (workspace.kind === WorkspaceKind.LIVE_PREVIEW) {
    return Object.freeze({ mode: 'livePreview', sessionId: workspace.sessionId, preview: workspace.preview });
  }
  if (workspace.kind === WorkspaceKind.SAVED_FOCUS) {
    return Object.freeze({ mode: 'savedFocus', sessionId: workspace.sessionId, screenId: workspace.screenId, itemId: workspace.itemId });
  }
  if (workspace.kind === WorkspaceKind.RECOVERY) {
    return Object.freeze({ mode: 'recovery', sessionId: workspace.sessionId, recovery: workspace.recovery });
  }
  return Object.freeze({ mode: 'empty' });
}

function selectBoundarySheet(state) {
  if (!state.pendingBoundary) return null;
  return Object.freeze({
    kind: state.pendingBoundary.kind,
    title: 'Switch to ' + state.pendingBoundary.targetSessionId + '?',
    fromSessionId: state.pendingBoundary.fromSessionId,
    targetSessionId: state.pendingBoundary.targetSessionId,
    draftSummary: state.pendingBoundary.draftSummary,
    actions: Object.freeze([
      Object.freeze({ label: 'Save draft', type: 'BOUNDARY_SAVE_DRAFT_CLICKED' }),
      Object.freeze({ label: 'Keep in recovery', type: 'BOUNDARY_KEEP_RECOVERY_CLICKED' }),
      Object.freeze({ label: 'Discard', type: 'BOUNDARY_DISCARD_CLICKED' }),
      Object.freeze({ label: 'Cancel', type: 'BOUNDARY_CANCEL_CLICKED' }),
    ]),
  });
}

function selectPromptReadiness(state) {
  const workspace = state.workspace;
  if (workspace.kind !== WorkspaceKind.DRAFT || workspace.items.length === 0) {
    return Object.freeze({ state: 'empty', label: 'No annotations ready', disabled: true });
  }
  const missing = workspace.items.filter((item) => !String(item.comment || '').trim()).length;
  if (missing) {
    return Object.freeze({
      state: 'blocked',
      label: workspace.items.length + ' drafts · ' + missing + ' missing comment',
      disabled: true,
    });
  }
  return Object.freeze({ state: 'ready', label: workspace.items.length + ' drafts ready', disabled: false });
}
