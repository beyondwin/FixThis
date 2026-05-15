// @requires draftStorageAdapter.js
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
      saveDraft: async () => {
        throw new Error('Canonical saveDraft effect is disabled until DraftWorkspace save request construction is canonical.');
      },
    }),
    previewApi: Object.freeze({
      capturePreview: async () => requestJson_('/api/preview'),
    }),
    draftStorage: Object.freeze({
      saveRecovery: async (sessionId, workspace) => {
        const adapter = createDraftStorageAdapter(localStorage_);
        adapter.saveWorkspace({
          ...workspace,
          schemaVersion: 2,
          sessionId: sessionId || workspace?.context?.sessionId,
          workspaceId: workspace?.workspaceId || workspace?.context?.workspaceId,
        });
      },
      deleteRecovery: async (sessionId, workspaceId) => {
        const adapter = createDraftStorageAdapter(localStorage_);
        adapter.deleteWorkspace(sessionId, workspaceId);
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
