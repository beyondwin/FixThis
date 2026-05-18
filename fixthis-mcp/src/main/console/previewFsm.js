// @requires (none)
// previewFsm.js — pure reducer for the live preview lifecycle.
//
// Owns the preview request/zoom/poll counters previously held as
// module-level lets in state.js (previewRequestGeneration,
// previewRequestContextGeneration, previewRequestInFlight,
// previewRequestInFlightContextGeneration, previewZoom). No DOM,
// fetch, timers, or globals here.
//
// Action set (per console-state-machine-expansion §3.3):
//   REQUEST_STARTED      — bump requestGeneration; capture (generation,
//                          contextGeneration) into inFlight; lifecycle → REQUESTING
//   REQUEST_SUCCEEDED    — applied only if BOTH action.generation and
//                          action.contextGeneration match the inFlight tuple;
//                          otherwise dropped (race-fence)
//   APPLY_READY          — accept externally delivered preview payloads
//                          such as SSE preview-ready events; supplied
//                          contextGeneration fences stale delivery.
//   REQUEST_FAILED       — same race-fence as REQUEST_SUCCEEDED; lifecycle → ERROR
//   CONTEXT_CHANGED      — bump contextGeneration; clear inFlight and current;
//                          lifecycle → IDLE (so the next request re-fetches)
//   SET_STALE            — toggle lifecycle between READY ↔ STALE
//   SET_ZOOM             — clamp to [MinPreviewZoom, MaxPreviewZoom], round to 0.1
//   SET_POLL_INTERVAL    — set pollIntervalMs (or null for manual)

const PreviewLifecycle = Object.freeze({
  IDLE: 'idle',
  REQUESTING: 'requesting',
  READY: 'ready',
  STALE: 'stale',
  ERROR: 'error',
});

const MinPreviewZoom = 0.5;
const MaxPreviewZoom = 2;

function createInitialPreviewState() {
  return Object.freeze({
    lifecycle: PreviewLifecycle.IDLE,
    requestGeneration: 0,
    contextGeneration: 0,
    inFlight: null,
    current: null,
    zoom: 1,
    pollIntervalMs: null,
    error: null,
  });
}

function clampPreviewZoom(value) {
  const numeric = typeof value === 'number' && !Number.isNaN(value) ? value : 1;
  const clamped = Math.min(Math.max(numeric, MinPreviewZoom), MaxPreviewZoom);
  return Math.round(clamped * 10) / 10;
}

function isStaleFence(state, action) {
  if (!state.inFlight) return true;
  if (action.generation !== state.inFlight.generation) return true;
  if (action.contextGeneration !== state.inFlight.contextGeneration) return true;
  return false;
}

function reducePreview(state, action) {
  if (!state) state = createInitialPreviewState();
  if (!action || typeof action.type !== 'string') return state;
  switch (action.type) {
    case 'REQUEST_STARTED': {
      const nextGeneration = state.requestGeneration + 1;
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.REQUESTING,
        requestGeneration: nextGeneration,
        inFlight: Object.freeze({
          generation: nextGeneration,
          contextGeneration: state.contextGeneration,
        }),
        error: null,
      });
    }
    case 'REQUEST_SUCCEEDED': {
      if (isStaleFence(state, action)) return state;
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.READY,
        current: action.preview ?? null,
        inFlight: null,
        error: null,
      });
    }
    case 'APPLY_READY': {
      if (
        Number.isInteger(action.contextGeneration) &&
        action.contextGeneration !== state.contextGeneration
      ) {
        return state;
      }
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.READY,
        current: action.preview ?? null,
        inFlight: null,
        error: null,
      });
    }
    case 'REQUEST_FAILED': {
      if (isStaleFence(state, action)) return state;
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.ERROR,
        inFlight: null,
        error: action.error ?? null,
      });
    }
    case 'CONTEXT_CHANGED': {
      return Object.freeze({
        ...state,
        lifecycle: PreviewLifecycle.IDLE,
        requestGeneration: state.requestGeneration + 1,
        contextGeneration: state.contextGeneration + 1,
        inFlight: null,
        current: null,
        error: null,
      });
    }
    case 'SET_STALE': {
      const stale = !!action.stale;
      if (stale && state.lifecycle === PreviewLifecycle.READY) {
        return Object.freeze({ ...state, lifecycle: PreviewLifecycle.STALE });
      }
      if (!stale && state.lifecycle === PreviewLifecycle.STALE) {
        return Object.freeze({ ...state, lifecycle: PreviewLifecycle.READY });
      }
      return state;
    }
    case 'SET_ZOOM': {
      const zoom = clampPreviewZoom(action.zoom);
      if (zoom === state.zoom) return state;
      return Object.freeze({ ...state, zoom });
    }
    case 'SET_POLL_INTERVAL': {
      const intervalMs = typeof action.intervalMs === 'number' ? action.intervalMs : null;
      if (intervalMs === state.pollIntervalMs) return state;
      return Object.freeze({ ...state, pollIntervalMs: intervalMs });
    }
    default:
      return state;
  }
}
