// @requires studioWorkflow.js, state.js, draftWorkspace.js
function currentStudioWorkflowConnection() {
  const raw = String(state.connection?.current?.state || '').toLowerCase();
  if (raw === 'ready') return 'ready';
  if (raw === 'no_device') return 'no-device';
  if (raw === 'unsupported_build') return 'unsupported';
  if (raw === 'open_app' || raw === 'reconnect' || raw === 'starting') return 'reconnecting';
  if (!state.selectedDeviceSerial && Array.isArray(state.devices) && state.devices.length === 0) return 'no-device';
  if (raw === 'check_phone' || raw === 'choose_device') return 'blocked';
  return 'initializing';
}

function currentStudioWorkflowWorkspace() {
  if (draftFlow()) return 'frozen-draft';
  if (state.preview) return 'live-preview';
  if (toolMode?.getState?.().focusedSavedItemId) return 'saved-focus';
  return 'empty';
}

function currentStudioWorkflowOperation() {
  if (pollingUseCases?.getState?.().promptActionInFlight) return 'saving-handoff';
  if (previewUseCases?.getState?.().inFlight) return 'capturing';
  if (toolMode?.getState?.().draftFlowStarting) return 'capturing';
  return 'idle';
}

function currentStudioWorkflowRisks() {
  const risks = [];
  if (draftFlow() && draftItemList().length) risks.push('dirty-draft');
  if (draftFlow() && draftWorkspace?.activityDriftWarning) risks.push('activity-drift');
  if (state.preview?.stale) risks.push('stale-preview');
  if (pollingUseCases?.getState?.().promptActionInFlight) risks.push('in-flight-mutation');
  return risks;
}

function currentStudioWorkflowSnapshot(patch = {}) {
  // `currentGeneration` reflects the polling FSM mutation counter so that
  // ASYNC_RESPONSE_RECEIVED can detect stale responses. The variable
  // `sessionMutationGeneration` is only referenced in a comment in `state.js`;
  // the live counter lives at `pollingUseCases.getState().mutationGeneration`.
  const generation = pollingUseCases?.getState?.()?.mutationGeneration;
  return {
    connection: currentStudioWorkflowConnection(),
    workspace: currentStudioWorkflowWorkspace(),
    operation: currentStudioWorkflowOperation(),
    risks: currentStudioWorkflowRisks(),
    activeSessionId: state.session?.sessionId || null,
    activeSessionStatus: state.session?.status || 'active',
    currentGeneration: typeof generation === 'number' ? generation : null,
    ...patch,
  };
}

function decideCurrentStudioWorkflow(action, patch = {}) {
  return decideStudioWorkflow(action, currentStudioWorkflowSnapshot(patch));
}

function studioWorkflowMessage(decision) {
  if (decision.reason === 'operation-in-flight') return 'Finish action first.';
  if (decision.reason === 'connection-no-device') return 'Connect a device first.';
  if (decision.reason === 'connection-unsupported') return 'Install a supported debug build.';
  if (decision.reason === 'connection-blocked') return 'Check your phone or choose a device.';
  if (decision.reason === 'connection-not-ready') return 'Open the app first.';
  if (decision.reason === 'closed-session') return 'Open a session first.';
  if (decision.reason === 'missing-item') return 'Item unavailable.';
  return 'Action blocked';
}

function focusStudioWorkflowSurface(decision) {
  if (decision.displaySurface !== 'connection-card') return;
  const raw = String(state.connection?.current?.state || '').toLowerCase();
  if (raw === 'choose_device') {
    devicePicker?.focus({ preventScroll: true });
    return;
  }
  if (decision.reason === 'connection-no-device') {
    document.getElementById('refreshDevicesButton')?.focus({ preventScroll: true });
    return;
  }
  connectionPrimaryAction?.focus({ preventScroll: true });
}

function surfaceStudioWorkflowDecision(decision) {
  if (!decision || decision.type === StudioWorkflowDecisionType.ALLOW || decision.type === StudioWorkflowDecisionType.IGNORE) return false;
  if (decision.type === StudioWorkflowDecisionType.CONFIRM) return false;
  const message = studioWorkflowMessage(decision);
  focusStudioWorkflowSurface(decision);
  if (typeof showStatus === 'function') {
    showStatus(message, { variant: 'warn', durationMs: 3500 });
  } else if (typeof showWarning === 'function') {
    showWarning(message);
  }
  return true;
}
