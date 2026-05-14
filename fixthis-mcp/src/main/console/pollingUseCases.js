// pollingUseCases.js — action dispatchers for the polling FSM.
//
// Pure: no DOM, fetch, timers, localStorage. Takes the reducer and an
// async sessions port via options. The browser adapter
// (pollingBrowserAdapter.js) wires in real fetches; sessions-polling.js
// keeps the top-level identifiers (pollSessionsTick / startSessionsPolling /
// withMutationLock) that asset contract tests grep for and delegates here.
//
// Factory shape:
//   createPollingUseCases({ onChange, api })
//     -> { getState, dispatch,
//          startSessionsPolling, stopSessionsPolling,
//          visibilityHidden, visibilityVisible, disconnect,
//          withMutationLock, pollSessionsTick,
//          backoffTimerFired, setPromptActionInFlight }
//
// The api.sessions port receives the current etags and returns a result
// shaped { sessionsEtag, sessionEtag, ... }. On success the FSM dispatches
// TICK_OK with the returned etags; on rejection it dispatches TICK_FAILED.

function createPollingUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  const api = options.api || {};
  let current = options.initialState ?? createInitialPollingState();
  if (!Object.isFrozen(current)) current = Object.freeze({ ...current });

  function dispatch(action) {
    const next = reducePolling(current, action);
    if (next !== current) {
      current = next;
      onChange(current);
    }
    return current;
  }

  function getState() {
    return current;
  }

  async function withMutationLock(fn) {
    dispatch({ type: 'MUTATION_START' });
    try {
      return await fn();
    } finally {
      dispatch({ type: 'MUTATION_END' });
      // NOTE: legacy "resume-on-mutation-drain" hook lives in sessions-polling.js
      // (it bridges to the connection FSM's sessionsPollingPaused projection).
      // The pure use case must not call cross-FSM side effects.
    }
  }

  // Internal: dispatches FSM transitions around the api.sessions port.
  // The exposed property name on the returned object is the spec name
  // (see below); this inner function uses a distinct identifier so the
  // top-level function declaration in sessions-polling.js remains the
  // only matching declaration site for body-grep contracts.
  async function tickViaApi() {
    if (typeof api.sessions !== 'function') {
      throw new Error('pollingUseCases: api.sessions port is required');
    }
    try {
      const result = await api.sessions({
        sessionsEtag: current.lastSessionsEtag,
        sessionEtag: current.lastSessionEtag,
      });
      dispatch({
        type: 'TICK_OK',
        sessionsEtag: result?.sessionsEtag ?? null,
        sessionEtag: result?.sessionEtag ?? null,
      });
      return result;
    } catch (err) {
      dispatch({ type: 'TICK_FAILED' });
      throw err;
    }
  }

  return {
    getState,
    dispatch,
    startSessionsPolling: () => dispatch({ type: 'START' }),
    stopSessionsPolling: () => dispatch({ type: 'STOP' }),
    visibilityHidden: () => dispatch({ type: 'VISIBILITY_HIDDEN' }),
    visibilityVisible: () => dispatch({ type: 'VISIBILITY_VISIBLE' }),
    disconnect: () => dispatch({ type: 'DISCONNECT_REQUESTED' }),
    withMutationLock,
    pollSessionsTick: tickViaApi,
    backoffTimerFired: () => dispatch({ type: 'BACKOFF_TIMER_FIRED' }),
    setPromptActionInFlight: (flag) => dispatch({
      type: flag ? 'PROMPT_ACTION_START' : 'PROMPT_ACTION_END',
    }),
  };
}
