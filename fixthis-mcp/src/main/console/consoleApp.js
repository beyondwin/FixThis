// @requires connectionBrowserAdapter.js, previewBrowserAdapter.js, pollingBrowserAdapter.js, toolModeUseCases.js, statusSurfaceRegistry.js
            // consoleApp.js — top-level FSM boot factory.
            // Aggregates the four sub-FSMs introduced by the
            // console-state-machine-expansion plan plus the already-migrated
            // draft FSM into one structured handle. Cross-FSM coordination
            // wires happen here at boot time (not in a global event bus).
            //
            // Each sub-FSM's onChange continues to project its frozen state
            // into the appropriate legacy `state.*` slot so READ-only sites
            // in rendering.js / devices.js / preview.js / annotations.js
            // observe changes without going through a new accessor.
            //
            // @requires connectionBrowserAdapter.js, previewBrowserAdapter.js,
            //           pollingBrowserAdapter.js, toolModeUseCases.js
            function createConsoleApp({ state, render } = {}) {
              const render_ = typeof render === 'function' ? render : () => {};
              const project = (slot) => (next) => {
                if (state) state[slot] = { ...next };
                render_();
              };
              // Remaining projection: annotations.js, devices.js, events.js,
              // preview.js, presentation views, and state.js still read
              // state.connection until selector-backed connection models own them.
              const connection = createBrowserConnectionUseCases(project('connection'));
              // Remaining projection: state.js keeps state.previewFsm mirrored
              // until preview model construction moves to console selectors.
              const preview = createBrowserPreviewUseCases({ onChange: project('previewFsm') });
              // Remaining projection: state.js keeps state.pollingFsm mirrored
              // until polling lifecycle reads move fully behind pollingUseCases.
              const polling = createBrowserPollingUseCases({ onChange: project('pollingFsm') });
              // Tool-mode has no legacy state.* projection (callers go through
              // toolMode.getState() directly), so onChange is only
              // used to trigger a render when supplied.
              const toolMode = createToolModeUseCases({ onChange: render_ });
              const statusSurfaceRegistry = createStatusSurfaceRegistry();
              if (typeof window !== 'undefined') window.__statusSurfaceRegistry = statusSurfaceRegistry;
              return { connection, preview, polling, statusSurfaceRegistry, toolMode };
            }
