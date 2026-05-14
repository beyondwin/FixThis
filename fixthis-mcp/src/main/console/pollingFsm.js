// @requires (none)
// pollingFsm.js — pure reducer for the sessions-polling lifecycle.
//
// Owns the polling-related state previously held as module-level lets in
// state.js: sessionsPollingTimer (the *concept* of running/stopped),
// lastSessionsEtag, lastSessionEtag, pendingMutationCount,
// sessionMutationGeneration, consecutivePollFailures, promptActionInFlight.
// No DOM, fetch, timers, or globals here.
//
// Lifecycle (per console-state-machine-expansion §3.5):
//   STOPPED          — no setInterval handle, no polling
//   POLLING_ACTIVE   — normal polling cadence
//   POLLING_BACKOFF  — too many consecutive failures; waiting for backoff
//                      timer or visibility/manual restart
//   POLLING_PAUSED   — pause requested (e.g. visibility hidden); will resume
//                      to pausedReturnLifecycle on VISIBILITY_VISIBLE
//
// Action set:
//   START                  — begin polling (resets consecutiveFailures)
//   STOP                   — stop polling
//   DISCONNECT_REQUESTED   — alias for STOP that fires from connection FSM
//   VISIBILITY_HIDDEN      — pause; preserve current lifecycle in
//                            pausedReturnLifecycle so we can resume
//   VISIBILITY_VISIBLE     — restore pausedReturnLifecycle
//   TICK_OK                — reset failure counter; update etags from payload
//   TICK_FAILED            — increment failure counter; at threshold,
//                            transition to POLLING_BACKOFF
//   BACKOFF_TIMER_FIRED    — POLLING_BACKOFF → POLLING_ACTIVE without
//                            resetting the counter (only TICK_OK resets it)
//   MUTATION_START         — bump pendingMutationCount AND mutationGeneration
//   MUTATION_END           — decrement pendingMutationCount (clamped ≥ 0)
//   MUTATION_GENERATION_BUMP — bump only mutationGeneration (no counter change);
//                              used by legacy bumpSessionMutationGeneration()
//                              callers that signal "session changed" outside a
//                              lock context.
//   PROMPT_ACTION_START    — set promptActionInFlight true
//   PROMPT_ACTION_END      — set promptActionInFlight false
//
// MaxConsecutivePollFailures is the spec constant 5; exposed via the
// IIFE-shared lexical scope (consumed by sessions-polling.js for the
// existing Kotlin grep contract, and by tests directly).

const MaxConsecutivePollFailures = 5;

const PollingLifecycle = Object.freeze({
  STOPPED: 'stopped',
  POLLING_ACTIVE: 'polling_active',
  POLLING_BACKOFF: 'polling_backoff',
  POLLING_PAUSED: 'polling_paused',
});

function createInitialPollingState() {
  return Object.freeze({
    lifecycle: PollingLifecycle.STOPPED,
    pausedReturnLifecycle: null,
    lastSessionsEtag: null,
    lastSessionEtag: null,
    pendingMutationCount: 0,
    mutationGeneration: 0,
    consecutiveFailures: 0,
    promptActionInFlight: false,
  });
}

function reducePolling(state, action) {
  if (!state) state = createInitialPollingState();
  if (!action || typeof action.type !== 'string') return state;
  switch (action.type) {
    case 'START':
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_ACTIVE,
        consecutiveFailures: 0,
        pausedReturnLifecycle: null,
      });
    case 'STOP':
    case 'DISCONNECT_REQUESTED':
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.STOPPED,
        pausedReturnLifecycle: null,
      });
    case 'VISIBILITY_HIDDEN': {
      if (state.lifecycle === PollingLifecycle.POLLING_PAUSED) return state;
      if (state.lifecycle === PollingLifecycle.STOPPED) return state;
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_PAUSED,
        pausedReturnLifecycle: state.lifecycle,
      });
    }
    case 'VISIBILITY_VISIBLE': {
      if (state.lifecycle !== PollingLifecycle.POLLING_PAUSED) return state;
      const target = state.pausedReturnLifecycle || PollingLifecycle.POLLING_ACTIVE;
      return Object.freeze({
        ...state,
        lifecycle: target,
        pausedReturnLifecycle: null,
      });
    }
    case 'TICK_OK':
      return Object.freeze({
        ...state,
        consecutiveFailures: 0,
        lastSessionsEtag: action.sessionsEtag !== undefined ? action.sessionsEtag : state.lastSessionsEtag,
        lastSessionEtag: action.sessionEtag !== undefined ? action.sessionEtag : state.lastSessionEtag,
      });
    case 'TICK_FAILED': {
      const nextFailures = state.consecutiveFailures + 1;
      const shouldBackoff = nextFailures >= MaxConsecutivePollFailures &&
        state.lifecycle === PollingLifecycle.POLLING_ACTIVE;
      return Object.freeze({
        ...state,
        consecutiveFailures: nextFailures,
        lifecycle: shouldBackoff ? PollingLifecycle.POLLING_BACKOFF : state.lifecycle,
      });
    }
    case 'BACKOFF_TIMER_FIRED': {
      if (state.lifecycle !== PollingLifecycle.POLLING_BACKOFF) return state;
      return Object.freeze({
        ...state,
        lifecycle: PollingLifecycle.POLLING_ACTIVE,
      });
    }
    case 'MUTATION_START':
      return Object.freeze({
        ...state,
        pendingMutationCount: state.pendingMutationCount + 1,
        mutationGeneration: state.mutationGeneration + 1,
      });
    case 'MUTATION_END':
      return Object.freeze({
        ...state,
        pendingMutationCount: Math.max(0, state.pendingMutationCount - 1),
      });
    case 'MUTATION_GENERATION_BUMP':
      return Object.freeze({
        ...state,
        mutationGeneration: state.mutationGeneration + 1,
      });
    case 'PROMPT_ACTION_START':
      return Object.freeze({ ...state, promptActionInFlight: true });
    case 'PROMPT_ACTION_END':
      return Object.freeze({ ...state, promptActionInFlight: false });
    default:
      return state;
  }
}
