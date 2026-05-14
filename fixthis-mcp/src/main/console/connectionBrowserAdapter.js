// @requires connectionFsm.js, connectionUseCases.js
// connectionBrowserAdapter.js — wires connectionUseCases into the browser
// `state.connection` projection. Keeping this glue in its own file keeps
// state.js free of FSM details and gives the use cases an explicit entry
// point (so tests can stub it out if needed).
//
// Returns the use-cases object. The caller passes a mutator that copies
// the frozen FSM state into the legacy `state.connection` object so
// non-FSM readers (rendering, devices, preview) keep observing changes.

function createBrowserConnectionUseCases(projectState) {
  const onChange = typeof projectState === 'function' ? projectState : () => {};
  return createConnectionUseCases({
    initialState: createInitialConnectionState(),
    onChange,
  });
}
