// @requires (none)
// draftPorts.js - narrow port helpers for DraftWorkspace use cases.
// Port shape:
// {
//   ids: { nextWorkspaceId(): string, nextDraftItemId(): string },
//   clock: { now(): number },
//   preview: { capture(): Promise<FeedbackPreviewSnapshot>, screenshotUrl(previewId, sessionId): string },
//   feedbackApi: { saveDraftWorkspace(request): Promise<object>, saveToMcp(sessionId, itemIds): Promise<object>, handoffPreview(sessionId, itemIds): Promise<string>, markHandedOff(sessionId, itemIds): Promise<object> },
//   storage: { saveWorkspace(envelope): void, deleteWorkspace(sessionId, workspaceId): void, loadWorkspacesForSession(sessionId): object[] },
//   clipboard: { writeText(text): Promise<void> },
//   boundaryPrompt: { choose(workspace, boundaryAction): Promise<'save'|'keep'|'discard'|'cancel'> },
//   recoveryPrompt: { choose(recovery, boundaryAction): Promise<'resume'|'recapture'|'clear'|'cancel'> },
//   refresh: { sessions(): Promise<void> }
// }

function requireDraftPort(ports, name) {
  const value = ports?.[name];
  if (!value) throw new Error('Missing draft port: ' + name);
  return value;
}

function createFakeDraftPorts(overrides = {}) {
  let id = 0;
  return {
    ids: {
      nextWorkspaceId: () => 'workspace-' + (++id),
      nextDraftItemId: () => 'draft-' + (++id),
    },
    clock: { now: () => 1000 },
    preview: {
      capture: async () => { throw new Error('preview.capture fake not configured'); },
      screenshotUrl: (previewId, sessionId) => '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full?sessionId=' + encodeURIComponent(sessionId),
    },
    feedbackApi: {
      saveDraftWorkspace: async () => { throw new Error('feedbackApi.saveDraftWorkspace fake not configured'); },
      saveToMcp: async () => { throw new Error('feedbackApi.saveToMcp fake not configured'); },
      handoffPreview: async () => { throw new Error('feedbackApi.handoffPreview fake not configured'); },
      markHandedOff: async () => { throw new Error('feedbackApi.markHandedOff fake not configured'); },
    },
    storage: {
      saveWorkspace: () => {},
      deleteWorkspace: () => {},
      loadWorkspacesForSession: () => [],
    },
    clipboard: { writeText: async () => {} },
    boundaryPrompt: { choose: async () => 'cancel' },
    recoveryPrompt: { choose: async () => 'cancel' },
    refresh: { sessions: async () => {} },
    ...overrides,
  };
}
