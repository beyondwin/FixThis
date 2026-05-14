// @requires pollingFsm.js, pollingUseCases.js, state.js, history.js, rendering.js
// pollingBrowserAdapter.js — wires pollingUseCases into the browser
// environment. Lives separately so state.js stays free of FSM details
// and the use cases get an explicit entry point for tests.
//
// The api.sessions port performs the actual HTTP fetches (and side-
// effects on state.sessionSummaries / renderSessionsList / etc.). It
// returns the new ETag headers so the FSM can persist them via TICK_OK.
//
// The closure-over-state is deliberate: this adapter sees the legacy
// state, fetch, and renderers via the bundled IIFE's shared lexical
// scope.

function createBrowserPollingUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  return createPollingUseCases({
    initialState: createInitialPollingState(),
    onChange,
    api: {
      sessions: async ({ sessionsEtag, sessionEtag }) => {
        let nextSessionsEtag = sessionsEtag ?? null;
        let nextSessionEtag = sessionEtag ?? null;
        const listResp = await fetch('/api/sessions', {
          headers: sessionsEtag ? { 'If-None-Match': sessionsEtag } : {},
        });
        if (listResp.status === 200) {
          nextSessionsEtag = listResp.headers.get('ETag');
          const data = await listResp.json();
          state.sessionSummaries = data.sessions || [];
          renderSessionsList();
        }
        if (state.session?.sessionId) {
          const sessResp = await fetch('/api/session', {
            headers: sessionEtag ? { 'If-None-Match': sessionEtag } : {},
          });
          if (sessResp.status === 200) {
            nextSessionEtag = sessResp.headers.get('ETag');
            const fresh = await sessResp.json();
            if (fresh) {
              mergeSessionIntoState(fresh);
              renderInspectorRegion();
            }
          }
        }
        return { sessionsEtag: nextSessionsEtag, sessionEtag: nextSessionEtag };
      },
    },
  });
}
