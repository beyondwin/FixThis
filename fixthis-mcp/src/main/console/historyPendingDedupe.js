// @requires state.js
// historyPendingDedupe.js - removes local recovery items already persisted by the server.

function persistedItemsForHistoryDedupe(session) {
  if (state.session?.sessionId === session?.sessionId && Array.isArray(state.session?.items)) return state.session.items;
  return Array.isArray(session?.items) ? session.items : [];
}

function normalizedHistoryBounds(bounds) {
  if (!bounds) return '';
  return ['left', 'top', 'right', 'bottom']
    .map((key) => String(Math.round(Number(bounds[key] || 0))))
    .join(',');
}

function historyItemTargetType(item) {
  if (item?.targetType) return item.targetType;
  const target = item?.target || {};
  if (target.type === 'semantics_node' || target.nodeUid) return 'node';
  if (target.type === 'visual_area' || target.boundsInWindow) return 'area';
  return '';
}

function historyItemBounds(item) {
  return item?.bounds || item?.target?.boundsInWindow || null;
}

function historyItemSemanticKey(item) {
  return [
    historyItemTargetType(item),
    String(item?.nodeUid || item?.target?.nodeUid || ''),
    normalizedHistoryBounds(historyItemBounds(item)),
    String(item?.comment || '').trim(),
  ].join('\u0000');
}

function persistedClientDraftKey(item) {
  if (!item?.clientWorkspaceId || !item?.clientDraftItemId) return null;
  return item.clientWorkspaceId + '\u0000' + item.clientDraftItemId;
}

function pendingClientDraftKey(item, workspaceId) {
  if (!workspaceId || !item?.draftItemId) return null;
  return workspaceId + '\u0000' + item.draftItemId;
}

function hasLegacySemanticDedupeKey(item, workspaceId) {
  if (pendingClientDraftKey(item, workspaceId)) return false;
  return Boolean(String(item?.comment || '').trim());
}

function dedupePendingHistoryItemsForSession(session, pendingItems, workspaceId) {
  const persisted = persistedItemsForHistoryDedupe(session);
  if (!persisted.length) return pendingItems || [];
  const persistedClientKeys = new Set(persisted.map(persistedClientDraftKey).filter(Boolean));
  const persistedSemanticKeys = new Set(
    persisted
      .filter(item => String(item?.comment || '').trim())
      .map(historyItemSemanticKey)
      .filter(Boolean)
  );
  return (pendingItems || []).filter((item) => {
    const clientKey = pendingClientDraftKey(item, workspaceId);
    if (clientKey) return !persistedClientKeys.has(clientKey);
    if (!hasLegacySemanticDedupeKey(item, workspaceId)) return true;
    return !persistedSemanticKeys.has(historyItemSemanticKey(item));
  });
}
