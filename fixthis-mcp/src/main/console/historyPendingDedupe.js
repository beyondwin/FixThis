// @requires state.js
// historyPendingDedupe.js - removes local recovery items already persisted by the server.

function persistedItemsForHistoryDedupe(session) {
  if (state.session?.sessionId === session?.sessionId && Array.isArray(state.session?.items)) return state.session.items;
  return Array.isArray(session?.items) ? session.items : [];
}

function persistedClientDraftKey(item) {
  if (!item?.clientWorkspaceId || !item?.clientDraftItemId) return null;
  return item.clientWorkspaceId + '\u0000' + item.clientDraftItemId;
}

function pendingClientDraftKey(item, workspaceId) {
  if (!workspaceId || !item?.draftItemId) return null;
  return workspaceId + '\u0000' + item.draftItemId;
}

function dropEmptyHistoryPendingItems(items) {
  let dropped = 0;
  const kept = (items || []).filter((item) => {
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
  return kept;
}

function dedupePendingHistoryItemsForSession(session, pendingItems, workspaceId) {
  const filteredPendingItems = dropEmptyHistoryPendingItems(pendingItems);
  const persisted = persistedItemsForHistoryDedupe(session);
  const persistedClientKeys = new Set(persisted.map(persistedClientDraftKey).filter(Boolean));
  return filteredPendingItems.filter((item) => {
    const clientKey = pendingClientDraftKey(item, workspaceId);
    if (!clientKey) return false;
    return !persistedClientKeys.has(clientKey);
  });
}

function newestDedupedHistoryRecoveryItems(session, recoveryCandidates) {
  return [...(recoveryCandidates || [])]
    .map((workspace) => ({
      workspace,
      items: dedupePendingHistoryItemsForSession(
        session,
        Array.isArray(workspace?.items) ? workspace.items : [],
        workspace?.workspaceId || null
      ),
    }))
    .filter((candidate) => candidate.items.length)
    .sort((left, right) =>
      (left.workspace?.updatedAtEpochMillis || 0) - (right.workspace?.updatedAtEpochMillis || 0)
    )
    .at(-1)?.items || [];
}
