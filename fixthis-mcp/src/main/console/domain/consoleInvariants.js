// @requires domain/workspaceState.js
function assertConsoleInvariants(state) {
  if (!state) throw new Error('Console state is required');
  const workspace = state.workspace;
  if (!workspace?.kind) throw new Error('Console state requires workspace.kind');
  if (workspace.kind === WorkspaceKind.DRAFT) {
    if (!workspace.context?.sessionId) throw new Error('Draft workspace requires context.sessionId');
    if (state.activeSessionId !== workspace.context.sessionId) {
      throw new Error('Draft workspace session must match activeSessionId');
    }
    if (!workspace.context.previewId) throw new Error('Draft workspace requires context.previewId');
    if (!workspace.context.screenId) throw new Error('Draft workspace requires context.screenId');
    if (workspace.focusedItemId && workspace.kind === WorkspaceKind.SAVED_FOCUS) {
      throw new Error('Draft focus and saved focus cannot coexist');
    }
  }
  if (workspace.kind === WorkspaceKind.SAVED_FOCUS && workspace.itemId && workspace.focusedItemId) {
    throw new Error('Saved focus cannot carry draft focus');
  }
  if (state.pendingBoundary && workspace.kind !== WorkspaceKind.DRAFT) {
    throw new Error('pendingBoundary requires an active draft workspace');
  }
  return true;
}
