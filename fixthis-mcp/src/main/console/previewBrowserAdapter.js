// previewBrowserAdapter.js — wires previewUseCases into the browser
// environment. Lives separately so state.js stays free of FSM details
// and the use cases get an explicit entry point for tests.
//
// The caller passes a mutator (onChange) that copies the frozen FSM
// state into a legacy projection (state.previewFsm) for any non-FSM
// readers. The api.capture port resolves to the requestLivePreview
// function declared in preview.js — these symbols share lexical scope
// inside the bundled IIFE, so the adapter sees them at call time.

function createBrowserPreviewUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  return createPreviewUseCases({
    initialState: createInitialPreviewState(),
    onChange,
    api: {
      capture: () => requestLivePreview(),
    },
    storage: typeof localStorage !== 'undefined' ? localStorage : { setItem: () => {} },
  });
}
