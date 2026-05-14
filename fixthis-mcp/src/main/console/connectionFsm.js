// connectionFsm.js — pure reducer for the bridge connection lifecycle.
//
// Owns the entire state.connection.* object plus heartbeat-related fields
// previously held as module-level lets in state.js. No DOM, fetch, timers,
// or globals here.
//
// Action set (per console-state-machine-expansion §3.2):
//   LAUNCH_REQUESTED       — user requested app launch
//   LAUNCH_SUCCEEDED       — launch RPC returned a status
//   LAUNCH_FAILED          — launch RPC failed
//   HEARTBEAT_OK           — heartbeat ping succeeded
//   HEARTBEAT_FAILED       — heartbeat ping failed (carries error message)
//   INTERACTION_BLOCKED    — resolver decided interaction is blocked
//   INTERACTION_UNBLOCKED  — resolver cleared the blocked reason
//   AVAILABILITY_UPDATED   — availability snapshot arrived without a full status
//   DISCONNECT_REQUESTED   — user/UI explicitly tore the connection down
//
// Plus two non-spec actions added during extraction so that **no** state.connection.*
// mutation lives outside this reducer (R3 mitigation: FSM is the single source
// of truth from creation):
//   STATUS_RECEIVED         — a full /api/connection status payload arrived
//   POLLING_PAUSED_CHANGED  — sessions polling backed off / resumed
//
// MaxHeartbeatFailures is exposed as a constant; consumers may import via the
// IIFE-shared lexical scope (no module object exists at runtime).

const MaxHeartbeatFailures = 3;

const ConnectionLifecycle = Object.freeze({
  WELCOME: 'welcome',
  STARTING: 'starting',
  READY: 'ready',
  RECONNECT: 'reconnect',
  ERROR: 'error',
});

function createInitialConnectionState() {
  return Object.freeze({
    current: null,
    hasEverConnected: false,
    lastReadyAt: null,
    launchInFlight: false,
    availability: null,
    interactionBlockedReason: null,
    previousBlockedReason: null,
    sessionsPollingPaused: false,
    heartbeatPolling: false,
    lastHeartbeatError: null,
  });
}

function statusIsReady(status) {
  if (!status) return false;
  const raw = String(status.state || '').toLowerCase();
  return raw === 'ready';
}

function reduceConnection(state, action) {
  if (!state) state = createInitialConnectionState();
  if (!action || typeof action.type !== 'string') return state;
  switch (action.type) {
    case 'LAUNCH_REQUESTED':
      return Object.freeze({ ...state, launchInFlight: true });
    case 'LAUNCH_SUCCEEDED':
    case 'LAUNCH_FAILED':
      return Object.freeze({ ...state, launchInFlight: false });
    case 'STATUS_RECEIVED': {
      const status = action.status ?? null;
      const next = {
        ...state,
        current: status,
        availability: status?.availability ?? null,
        previousBlockedReason: state.interactionBlockedReason,
        interactionBlockedReason: action.blockedReason ?? null,
      };
      if (statusIsReady(status)) {
        next.hasEverConnected = true;
        next.lastReadyAt = typeof action.nowMs === 'number' ? action.nowMs : state.lastReadyAt;
      }
      return Object.freeze(next);
    }
    case 'AVAILABILITY_UPDATED':
      return Object.freeze({ ...state, availability: action.availability ?? null });
    case 'INTERACTION_BLOCKED':
      return Object.freeze({
        ...state,
        previousBlockedReason: state.interactionBlockedReason,
        interactionBlockedReason: action.reason ?? null,
      });
    case 'INTERACTION_UNBLOCKED':
      return Object.freeze({
        ...state,
        previousBlockedReason: state.interactionBlockedReason,
        interactionBlockedReason: null,
      });
    case 'HEARTBEAT_OK':
      return Object.freeze({ ...state, lastHeartbeatError: null });
    case 'HEARTBEAT_FAILED':
      return Object.freeze({ ...state, lastHeartbeatError: action.error ?? null });
    case 'POLLING_PAUSED_CHANGED':
      return Object.freeze({ ...state, sessionsPollingPaused: !!action.paused });
    case 'DISCONNECT_REQUESTED':
      return Object.freeze({
        ...state,
        current: null,
        availability: null,
        heartbeatPolling: false,
      });
    default:
      return state;
  }
}
