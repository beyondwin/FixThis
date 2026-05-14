// @requires connectionFsm.js
// connectionUseCases.js — action dispatchers for the connection FSM.
//
// Pure: no DOM, fetch, timers, localStorage. Takes the reducer and an
// onChange observer (the compat shim in state.js projects the new state
// into the legacy state.connection object so non-FSM readers keep working).
//
// Factory shape:
//   createConnectionUseCases({ initialState, onChange })
//     -> { getState, dispatch, launchRequested, launchSucceeded, launchFailed,
//          setStatus, availabilityUpdated, interactionBlocked,
//          interactionUnblocked, heartbeatOk, heartbeatFailed,
//          setSessionsPollingPaused, disconnectRequested }

function createConnectionUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  let current = options.initialState ?? createInitialConnectionState();
  if (!Object.isFrozen(current)) current = Object.freeze({ ...current });

  function dispatch(action) {
    const next = reduceConnection(current, action);
    if (next !== current) {
      current = next;
      onChange(current);
    }
    return current;
  }

  function getState() {
    return current;
  }

  return {
    getState,
    dispatch,
    launchRequested: () => dispatch({ type: 'LAUNCH_REQUESTED' }),
    launchSucceeded: () => dispatch({ type: 'LAUNCH_SUCCEEDED' }),
    launchFailed: () => dispatch({ type: 'LAUNCH_FAILED' }),
    setStatus: (status, blockedReason, extras = {}) => dispatch({
      type: 'STATUS_RECEIVED',
      status,
      blockedReason: blockedReason ?? null,
      nowMs: typeof extras.nowMs === 'number' ? extras.nowMs : Date.now(),
    }),
    availabilityUpdated: (availability) => dispatch({
      type: 'AVAILABILITY_UPDATED',
      availability,
    }),
    interactionBlocked: (reason) => dispatch({ type: 'INTERACTION_BLOCKED', reason }),
    interactionUnblocked: () => dispatch({ type: 'INTERACTION_UNBLOCKED' }),
    heartbeatOk: () => dispatch({ type: 'HEARTBEAT_OK' }),
    heartbeatFailed: (error) => dispatch({ type: 'HEARTBEAT_FAILED', error }),
    setSessionsPollingPaused: (paused) => dispatch({
      type: 'POLLING_PAUSED_CHANGED',
      paused,
    }),
    disconnectRequested: () => dispatch({ type: 'DISCONNECT_REQUESTED' }),
  };
}
