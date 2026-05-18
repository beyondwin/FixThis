// @requires studioWorkflow.js, domain/workspaceState.js
function workflowConnectionFromState(state) {
  const raw = String(state.connection?.current?.state || '').toLowerCase();
  if (raw === 'ready') return 'ready';
  if (raw === 'check_phone' || raw === 'choose_device') return 'blocked';
  if (raw === 'unsupported_build') return 'unsupported';
  if (raw === 'open_app' || raw === 'reconnect' || raw === 'starting') return 'reconnecting';
  return 'initializing';
}

function workflowWorkspaceFromState(state) {
  if (state.workspace?.kind === WorkspaceKind.LIVE_PREVIEW) return 'live-preview';
  if (state.workspace?.kind === WorkspaceKind.DRAFT) return 'frozen-draft';
  if (state.workspace?.kind === WorkspaceKind.SAVED_FOCUS) return 'saved-focus';
  if (state.workspace?.kind === WorkspaceKind.RECOVERY) return 'recovery';
  return 'empty';
}

function workflowRisksFromState(state) {
  const risks = [];
  if (state.workspace?.kind === WorkspaceKind.DRAFT && state.workspace.items?.length) risks.push('dirty-draft');
  if (state.workspace?.kind === WorkspaceKind.DRAFT && state.workspace.activityDriftWarning) risks.push('activity-drift');
  if (state.workspace?.kind === WorkspaceKind.LIVE_PREVIEW && state.workspace.preview?.stale) risks.push('stale-preview');
  if (state.promptAction?.inFlight) risks.push('in-flight-mutation');
  return risks;
}

function workflowSnapshotFromState(state, patch = {}) {
  return Object.freeze({
    connection: workflowConnectionFromState(state),
    workspace: workflowWorkspaceFromState(state),
    operation: state.promptAction?.inFlight ? 'saving-handoff' : 'idle',
    risks: workflowRisksFromState(state),
    activeSessionId: state.activeSessionId || null,
    activeSessionStatus: state.sessionsById?.[state.activeSessionId]?.status || 'active',
    currentGeneration: state.effectsGeneration,
    ...patch,
  });
}

function workflowStatus(decision) {
  if (decision.reason === 'operation-in-flight') {
    return Object.freeze({ message: 'Finish the current handoff action before switching sessions.', variant: 'warn', assertive: false });
  }
  if (decision.reason === 'connection-blocked' || decision.reason === 'connection-not-ready') {
    return Object.freeze({ message: 'Connect the app before annotating.', variant: 'warn', assertive: false });
  }
  if (decision.reason === 'closed-session') {
    return Object.freeze({ message: 'Reopen the session or create a new active session before changing feedback.', variant: 'warn', assertive: false });
  }
  return Object.freeze({ message: 'This action is blocked by the current Studio workflow state.', variant: 'warn', assertive: false });
}
