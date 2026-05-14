// previewUseCases.js — action dispatchers for the preview FSM.
//
// Pure: no DOM, fetch, timers, localStorage. Takes async primitives
// (capture) and storage via ports. The browser adapter
// (previewBrowserAdapter.js) wires in the real implementations.
//
// Factory shape:
//   createPreviewUseCases({ onChange, api, storage })
//     -> { getState, dispatch, request, contextChanged, setStale,
//          setZoom, setPollInterval }
//
// Race-fence contract (normative): when request() awaits api.capture,
// the (generation, contextGeneration) tuple captured immediately after
// REQUEST_STARTED is passed to REQUEST_SUCCEEDED / REQUEST_FAILED.
// The reducer drops the action if EITHER counter mismatches.

const PreviewIntervalStorageKeyConst = 'fixthis.previewIntervalMs.v2';

function createPreviewUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  const api = options.api || {};
  const storage = options.storage || {};
  let current = options.initialState ?? createInitialPreviewState();
  if (!Object.isFrozen(current)) current = Object.freeze({ ...current });
  let inFlightPromise = null;

  function dispatch(action) {
    const next = reducePreview(current, action);
    if (next !== current) {
      current = next;
      onChange(current);
    }
    return current;
  }

  function getState() {
    return current;
  }

  function request() {
    // Dedup: if an in-flight promise exists AND the FSM still considers it
    // current (lifecycle REQUESTING with a matching inFlight snapshot),
    // return the existing promise rather than starting a new fetch.
    if (inFlightPromise && current.inFlight) {
      return inFlightPromise;
    }
    dispatch({ type: 'REQUEST_STARTED' });
    const captured = current.inFlight;
    const promise = Promise.resolve()
      .then(() => api.capture())
      .then((preview) => {
        if (inFlightPromise === promise) inFlightPromise = null;
        dispatch({
          type: 'REQUEST_SUCCEEDED',
          generation: captured.generation,
          contextGeneration: captured.contextGeneration,
          preview,
        });
        return preview;
      })
      .catch((cause) => {
        if (inFlightPromise === promise) inFlightPromise = null;
        const message = cause && cause.message ? cause.message : String(cause);
        dispatch({
          type: 'REQUEST_FAILED',
          generation: captured.generation,
          contextGeneration: captured.contextGeneration,
          error: message,
        });
        throw cause;
      });
    inFlightPromise = promise;
    return promise;
  }

  function contextChanged() {
    inFlightPromise = null;
    dispatch({ type: 'CONTEXT_CHANGED' });
  }

  function setStale(stale) {
    dispatch({ type: 'SET_STALE', stale });
  }

  function setZoom(zoom) {
    dispatch({ type: 'SET_ZOOM', zoom });
  }

  function setPollInterval(intervalMs) {
    dispatch({ type: 'SET_POLL_INTERVAL', intervalMs });
    if (typeof storage.setItem === 'function' && intervalMs != null) {
      storage.setItem(PreviewIntervalStorageKeyConst, String(intervalMs));
    }
  }

  return {
    getState,
    dispatch,
    request,
    contextChanged,
    setStale,
    setZoom,
    setPollInterval,
  };
}
