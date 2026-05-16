// @requires draftPorts.js, draftWorkspace.js, domain/consoleBoundary.js
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

function dropEmptyEntries(envelope) {
  if (!envelope) return { items: [] };
  let dropped = 0;
  const items = (envelope.items || []).filter((item) => {
    const comment = String(item?.comment || '');
    if (comment.length === 0) {
      dropped += 1;
      return false;
    }
    return true;
  });
  if (dropped > 0) {
    console.info(`[draft-recovery] skipped ${dropped} empty-comment entries`);
  }
  return { ...envelope, items };
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
      .map((workspaceId) => {
        const key = draftWorkspaceKey(sessionId, workspaceId);
        const parsed = normalizeStoredJson(localStorageLike.getItem(key));
        if (!parsed.ok) {
          localStorageLike.removeItem(key);
          return null;
        }
        const filtered = dropEmptyEntries(parsed.value);
        if (!filtered.items.length) {
          localStorageLike.removeItem(key);
          return null;
        }
        return filtered;
      })
      .filter((value) => value?.schemaVersion === 2 && value?.context?.sessionId === sessionId);
  }

  function deleteWorkspace(sessionId, workspaceId) {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
    writeIndex(sessionId, readIndex(sessionId).filter((id) => id !== workspaceId));
  }

  function deleteWorkspacesForSession(sessionId) {
    if (!sessionId) return;
    readIndex(sessionId).forEach((workspaceId) => {
      localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
    });
    localStorageLike.removeItem(draftWorkspaceIndexKey(sessionId));
  }

  function clearLegacyPending(sessionId) {
    if (!sessionId) return;
    localStorageLike.removeItem(LegacyPendingKeyPrefix + sessionId);
  }

  function migrateLegacyPending(sessionId) {
    const raw = localStorageLike.getItem(LegacyPendingKeyPrefix + sessionId);
    const legacy = parseDraftStorageJson(raw);
    if (!legacy || !Array.isArray(legacy.items) || !legacy.items.length) return [];
    const filteredLegacy = dropEmptyEntries(legacy);
    if (!filteredLegacy.items.length) {
      clearLegacyPending(sessionId);
      return [];
    }
    if (legacy.schemaVersion === 0 || (!legacy.context && !legacy.previewId)) {
      return [{
        schemaVersion: 0,
        sessionId,
        requiresRecapture: true,
        items: filteredLegacy.items.map(normalizeLegacyDraftItem),
      }];
    }
    const workspaceId = nextWorkspaceId();
    const envelope = {
      schemaVersion: 2,
      sessionId,
      workspaceId,
      revision: 1,
      lifecycle: 'editing',
      context: filteredLegacy.context || {
        sessionId,
        previewId: filteredLegacy.previewId,
        screenId: filteredLegacy.screen?.screenId || null,
        screenFingerprint: filteredLegacy.screen?.fingerprint ?? null,
        deviceSerial: null,
        frozenAtEpochMillis: filteredLegacy.frozenAtEpochMillis || null,
        activityName: filteredLegacy.activity || filteredLegacy.screen?.activityName || null,
      },
      screen: filteredLegacy.screen || null,
      screenshotUrl: filteredLegacy.screenshotUrl || null,
      items: filteredLegacy.items.map(normalizeLegacyDraftItem),
      history: { undoStack: [], redoStack: [] },
      updatedAtEpochMillis: Date.now(),
    };
    saveWorkspace(envelope);
    clearLegacyPending(sessionId);
    return [envelope];
  }

  return { saveWorkspace, loadWorkspacesForSession, deleteWorkspace, deleteWorkspacesForSession, clearLegacyPending, migrateLegacyPending };
}
