// @requires (none)
function createBrowserConsolePorts(options = {}) {
  const requestJson_ = options.requestJson;
  const localStorage_ = options.localStorage || localStorage;
  return Object.freeze({
    sessionApi: Object.freeze({
      openSession: async (sessionId) => requestJson_('/api/session/open', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId }),
      }),
      listSessions: async () => requestJson_('/api/sessions'),
      currentSession: async () => requestJson_('/api/session'),
      saveDraft: async (sessionId, items) => requestJson_('/api/feedback/items', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId, items }),
      }),
    }),
    previewApi: Object.freeze({
      capturePreview: async () => requestJson_('/api/preview'),
    }),
    draftStorage: Object.freeze({
      saveRecovery: async (sessionId, workspace) => {
        localStorage_.setItem('fixthis.recovery.' + sessionId, JSON.stringify(workspace));
      },
      deleteRecovery: async (sessionId, workspaceId) => {
        const prefix = 'fixthis.draftWorkspace.' + sessionId + '.';
        if (workspaceId) {
          localStorage_.removeItem(prefix + workspaceId);
        }
        localStorage_.removeItem('fixthis.recovery.' + sessionId);
      },
    }),
    clipboard: Object.freeze({
      writeText: async (text) => navigator.clipboard.writeText(text),
    }),
    timer: Object.freeze({
      setTimeout: (callback, delayMs) => setTimeout(callback, delayMs),
      clearTimeout: (id) => clearTimeout(id),
    }),
    clock: Object.freeze({ now: () => Date.now() }),
  });
}
