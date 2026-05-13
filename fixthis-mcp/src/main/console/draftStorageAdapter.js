// draftStorageAdapter.js - browser storage adapter for DraftWorkspace recovery.

const DraftWorkspaceKeyPrefix = 'fixthis.workspace.';
const LegacyPendingKeyPrefix = 'fixthis.pending.';

function draftWorkspaceKey(sessionId, workspaceId) {
  return DraftWorkspaceKeyPrefix + sessionId + '.' + workspaceId;
}

function draftWorkspaceIndexKey(sessionId) {
  return DraftWorkspaceKeyPrefix + 'index.' + sessionId;
}

function parseDraftStorageJson(raw) {
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

function normalizeLegacyDraftItem(item, index) {
  return {
    ...item,
    draftItemId: item?.draftItemId || item?.annotationId || ('legacy-' + (index + 1)),
  };
}

function createDraftStorageAdapter(localStorageLike, ids = {}) {
  const nextWorkspaceId = ids.nextWorkspaceId || (() => 'workspace-' + Date.now());

  function readIndex(sessionId) {
    const parsed = parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceIndexKey(sessionId)));
    return Array.isArray(parsed) ? parsed : [];
  }

  function writeIndex(sessionId, workspaceIds) {
    localStorageLike.setItem(draftWorkspaceIndexKey(sessionId), JSON.stringify(Array.from(new Set(workspaceIds))));
  }

  function saveWorkspace(envelope) {
    const sessionId = envelope?.sessionId || envelope?.context?.sessionId;
    const workspaceId = envelope?.workspaceId;
    if (!sessionId || !workspaceId) throw new Error('Workspace storage requires sessionId and workspaceId');
    const normalized = { ...envelope, schemaVersion: 2, sessionId, workspaceId };
    localStorageLike.setItem(draftWorkspaceKey(sessionId, workspaceId), JSON.stringify(normalized));
    writeIndex(sessionId, readIndex(sessionId).concat(workspaceId));
  }

  function loadWorkspacesForSession(sessionId) {
    return readIndex(sessionId)
      .map((workspaceId) => parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceKey(sessionId, workspaceId))))
      .filter((value) => value?.schemaVersion === 2 && value?.context?.sessionId === sessionId);
  }

  function deleteWorkspace(sessionId, workspaceId) {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
    writeIndex(sessionId, readIndex(sessionId).filter((id) => id !== workspaceId));
  }

  function migrateLegacyPending(sessionId) {
    const raw = localStorageLike.getItem(LegacyPendingKeyPrefix + sessionId);
    const legacy = parseDraftStorageJson(raw);
    if (!legacy || !Array.isArray(legacy.items) || !legacy.items.length) return [];
    if (legacy.schemaVersion === 0 || (!legacy.context && !legacy.previewId)) {
      return [{
        schemaVersion: 0,
        sessionId,
        requiresRecapture: true,
        items: legacy.items.map(normalizeLegacyDraftItem),
      }];
    }
    const workspaceId = nextWorkspaceId();
    const envelope = {
      schemaVersion: 2,
      sessionId,
      workspaceId,
      revision: 1,
      lifecycle: 'editing',
      context: legacy.context || {
        sessionId,
        previewId: legacy.previewId,
        screenId: legacy.screen?.screenId || null,
        screenFingerprint: legacy.screen?.fingerprint ?? null,
        deviceSerial: null,
        frozenAtEpochMillis: legacy.frozenAtEpochMillis || null,
        activityName: legacy.activity || legacy.screen?.activityName || null,
      },
      screen: legacy.screen || null,
      screenshotUrl: legacy.screenshotUrl || null,
      items: legacy.items.map(normalizeLegacyDraftItem),
      history: { undoStack: [], redoStack: [] },
      updatedAtEpochMillis: Date.now(),
    };
    saveWorkspace(envelope);
    return [envelope];
  }

  return { saveWorkspace, loadWorkspacesForSession, deleteWorkspace, migrateLegacyPending };
}
