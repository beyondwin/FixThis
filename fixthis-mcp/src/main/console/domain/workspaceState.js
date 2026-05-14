// @requires (none)
const WorkspaceKind = Object.freeze({
  EMPTY: 'empty',
  LIVE_PREVIEW: 'livePreview',
  DRAFT: 'draft',
  SAVED_FOCUS: 'savedFocus',
  RECOVERY: 'recovery',
});

function emptyWorkspace() {
  return Object.freeze({ kind: WorkspaceKind.EMPTY });
}

function livePreviewWorkspace(sessionId, preview = null) {
  return Object.freeze({
    kind: WorkspaceKind.LIVE_PREVIEW,
    sessionId: sessionId || null,
    preview: cloneConsoleValue(preview),
  });
}

function draftWorkspaceFromPreview(sessionId, preview, options = {}) {
  if (!sessionId) throw new Error('Draft workspace requires sessionId');
  if (!preview?.previewId) throw new Error('Draft workspace requires previewId');
  const screen = preview.screen || null;
  if (!screen?.screenId) throw new Error('Draft workspace requires screenId');
  return Object.freeze({
    kind: WorkspaceKind.DRAFT,
    context: Object.freeze({
      sessionId,
      previewId: preview.previewId,
      screenId: screen.screenId,
      screenFingerprint: screen.fingerprint ?? null,
      deviceSerial: options.deviceSerial || null,
      frozenAtEpochMillis: preview.frozenAtEpochMillis || options.frozenAtEpochMillis || null,
      activityName: preview.activity || options.activityName || null,
      workspaceId: options.workspaceId || `draft-${sessionId}-${preview.previewId}`,
    }),
    screen: cloneConsoleValue(screen),
    screenshotUrl: preview.screenshotUrl || options.screenshotUrl || '',
    items: Object.freeze([...(options.items || [])].map(cloneConsoleValue)),
    focusedItemId: options.focusedItemId || null,
    currentSelection: cloneConsoleValue(options.currentSelection || null),
    activityDriftWarning: cloneConsoleValue(options.activityDriftWarning || null),
  });
}

function draftWorkspaceWithPatch(workspace, patch = {}) {
  if (!isDraftWorkspace(workspace)) return workspace;
  return Object.freeze({
    ...workspace,
    ...patch,
    context: Object.freeze({ ...workspace.context, ...(patch.context || {}) }),
    items: Object.freeze([...(patch.items || workspace.items || [])].map(cloneConsoleValue)),
  });
}

function savedFocusWorkspace(sessionId, screenId, itemId = null) {
  return Object.freeze({
    kind: WorkspaceKind.SAVED_FOCUS,
    sessionId: sessionId || null,
    screenId: screenId || null,
    itemId: itemId || null,
  });
}

function recoveryWorkspace(sessionId, recovery) {
  return Object.freeze({
    kind: WorkspaceKind.RECOVERY,
    sessionId: sessionId || null,
    recovery: cloneConsoleValue(recovery),
  });
}

function isDraftWorkspace(workspace) {
  return workspace?.kind === WorkspaceKind.DRAFT;
}

function cloneConsoleValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}
