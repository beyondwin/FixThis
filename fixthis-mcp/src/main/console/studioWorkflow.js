// @requires (none)
const StudioWorkflowDecisionType = Object.freeze({
  ALLOW: 'allow',
  BLOCK: 'block',
  CONFIRM: 'confirm',
  IGNORE: 'ignore',
});

const StudioWorkflowAction = Object.freeze({
  ANNOTATE_CLICKED: 'annotate-clicked',
  SAVE_DRAFT_CLICKED: 'save-draft-clicked',
  COPY_PROMPT_CLICKED: 'copy-prompt-clicked',
  SAVE_TO_MCP_CLICKED: 'save-to-mcp-clicked',
  SESSION_SWITCH_REQUESTED: 'session-switch-requested',
  ASYNC_RESPONSE_RECEIVED: 'async-response-received',
  CLAIM_ITEM_CLICKED: 'claim-item-clicked',
  RESOLVE_ITEM_CLICKED: 'resolve-item-clicked',
  CONNECTION_STATUS_RECEIVED: 'connection-status-received',
});

const durableMutationActions = new Set([
  StudioWorkflowAction.SAVE_DRAFT_CLICKED,
  StudioWorkflowAction.COPY_PROMPT_CLICKED,
  StudioWorkflowAction.SAVE_TO_MCP_CLICKED,
  StudioWorkflowAction.CLAIM_ITEM_CLICKED,
  StudioWorkflowAction.RESOLVE_ITEM_CLICKED,
]);

const inFlightOperations = new Set([
  'capturing',
  'saving-draft',
  'copying-prompt',
  'saving-handoff',
  'claiming',
  'resolving',
]);

function allowStudioWorkflow(effect) {
  return Object.freeze({ type: StudioWorkflowDecisionType.ALLOW, effect });
}

function blockStudioWorkflow(reason, displaySurface) {
  return Object.freeze({ type: StudioWorkflowDecisionType.BLOCK, reason, displaySurface });
}

function confirmStudioWorkflow(boundaryKind, choices, reason) {
  return Object.freeze({
    type: StudioWorkflowDecisionType.CONFIRM,
    boundaryKind,
    choices: Object.freeze([...choices]),
    reason,
  });
}

function ignoreStudioWorkflow(reason) {
  return Object.freeze({ type: StudioWorkflowDecisionType.IGNORE, reason });
}

function workflowRisks(snapshot) {
  return new Set(snapshot?.risks || []);
}

function hasWorkflowRisk(snapshot, risk) {
  return workflowRisks(snapshot).has(risk);
}

function connectionReady(snapshot) {
  return (snapshot?.connection || 'initializing') === 'ready';
}

function annotateConnectionBlockReason(state) {
  if (state === 'no-device') return 'connection-no-device';
  if (state === 'unsupported') return 'connection-unsupported';
  if (state === 'blocked') return 'connection-blocked';
  return 'connection-not-ready';
}

function sessionClosed(snapshot) {
  return (snapshot?.activeSessionStatus || 'active') === 'closed';
}

function durableMutationInFlight(snapshot) {
  return inFlightOperations.has(snapshot?.operation || 'idle');
}

function decideAsyncResponse(snapshot) {
  if (
    snapshot?.currentGeneration != null &&
    snapshot?.eventGeneration != null &&
    snapshot.eventGeneration !== snapshot.currentGeneration
  ) {
    return ignoreStudioWorkflow('stale-generation');
  }
  if (
    snapshot?.activeSessionId &&
    snapshot?.eventSessionId &&
    snapshot.eventSessionId !== snapshot.activeSessionId
  ) {
    return ignoreStudioWorkflow('session-mismatch');
  }
  return allowStudioWorkflow('apply-response');
}

function decideStudioWorkflow(action, snapshot = {}) {
  if (action === StudioWorkflowAction.ASYNC_RESPONSE_RECEIVED) {
    return decideAsyncResponse(snapshot);
  }

  if (durableMutationInFlight(snapshot) && !(action === StudioWorkflowAction.ANNOTATE_CLICKED && snapshot?.operation === 'capturing')) {
    return blockStudioWorkflow('operation-in-flight', 'prompt-readiness');
  }

  if (sessionClosed(snapshot) && durableMutationActions.has(action)) {
    return blockStudioWorkflow('closed-session', 'history-panel');
  }

  if (action === StudioWorkflowAction.ANNOTATE_CLICKED) {
    if (!connectionReady(snapshot)) {
      const state = snapshot.connection || 'initializing';
      return blockStudioWorkflow(annotateConnectionBlockReason(state), 'connection-card');
    }
    const workspace = snapshot.workspace || 'empty';
    const canCapture = (workspace === 'empty' || workspace === 'saved-focus') && Boolean(snapshot.activeSessionId);
    if (workspace !== 'live-preview' && !canCapture) {
      return blockStudioWorkflow('no-live-preview', 'preview-frame');
    }
    return allowStudioWorkflow('capture-preview');
  }

  if (action === StudioWorkflowAction.SESSION_SWITCH_REQUESTED) {
    if (hasWorkflowRisk(snapshot, 'dirty-draft')) {
      return confirmStudioWorkflow('sessionSwitch', ['saveAndProceed', 'discardAndProceed', 'cancel'], 'dirty-draft');
    }
    return allowStudioWorkflow('open-session');
  }

  if (
    action === StudioWorkflowAction.SAVE_DRAFT_CLICKED ||
    action === StudioWorkflowAction.COPY_PROMPT_CLICKED ||
    action === StudioWorkflowAction.SAVE_TO_MCP_CLICKED
  ) {
    if (hasWorkflowRisk(snapshot, 'stale-preview') || hasWorkflowRisk(snapshot, 'activity-drift')) {
      return confirmStudioWorkflow('fingerprintMismatch', ['recapture', 'force', 'cancel'], 'stale-preview');
    }
    if (!connectionReady(snapshot) && (snapshot.workspace || 'empty') === 'frozen-draft') {
      return confirmStudioWorkflow('fingerprintMismatch', ['recapture', 'force', 'cancel'], 'connection-paused');
    }
    return allowStudioWorkflow(action === StudioWorkflowAction.COPY_PROMPT_CLICKED ? 'copy-prompt' : 'persist-feedback');
  }

  if (action === StudioWorkflowAction.CLAIM_ITEM_CLICKED || action === StudioWorkflowAction.RESOLVE_ITEM_CLICKED) {
    if (snapshot.targetItemExists === false) {
      return blockStudioWorkflow('missing-item', 'handoff-queue');
    }
    return allowStudioWorkflow(action === StudioWorkflowAction.CLAIM_ITEM_CLICKED ? 'claim-item' : 'resolve-item');
  }

  return allowStudioWorkflow('continue');
}
