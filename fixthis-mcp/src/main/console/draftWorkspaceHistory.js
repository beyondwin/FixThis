// draftWorkspaceHistory.js - pure undo/redo helpers for DraftWorkspace items.

const DraftHistoryMaxDepth = 50;

function createEmptyDraftHistory() {
  return { undoStack: [], redoStack: [] };
}

function cloneDraftHistoryValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function pushDraftHistoryOp(stack, op) {
  const next = stack.concat(cloneDraftHistoryValue(op));
  while (next.length > DraftHistoryMaxDepth) next.shift();
  return next;
}

function recordDraftAdd(history, item) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'add', after: item }),
    redoStack: [],
  };
}

function recordDraftDelete(history, item, index) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'delete', before: item, index }),
    redoStack: [],
  };
}

function recordDraftUpdate(history, before, after) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'update', before, after }),
    redoStack: [],
  };
}

function sameDraftHistoryItem(left, right) {
  return Boolean(left?.draftItemId && right?.draftItemId && left.draftItemId === right.draftItemId);
}

function applyDraftInverse(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') {
    return next.filter((item) => !sameDraftHistoryItem(item, op.after));
  }
  if (op.kind === 'delete') {
    const restored = cloneDraftHistoryValue(op.before);
    const index = Number.isInteger(op.index) ? Math.max(0, Math.min(op.index, next.length)) : next.length;
    next.splice(index, 0, restored);
    return next;
  }
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.before) ? cloneDraftHistoryValue(op.before) : item);
  }
  return next;
}

function applyDraftForward(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') return next.concat(cloneDraftHistoryValue(op.after));
  if (op.kind === 'delete') return next.filter((item) => !sameDraftHistoryItem(item, op.before));
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.after) ? cloneDraftHistoryValue(op.after) : item);
  }
  return next;
}

function undoDraftHistory(history, items) {
  const undoStack = (history?.undoStack || []).slice();
  const op = undoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack,
      redoStack: pushDraftHistoryOp(history?.redoStack || [], op),
    },
    items: applyDraftInverse(op, items || []),
  };
}

function redoDraftHistory(history, items) {
  const redoStack = (history?.redoStack || []).slice();
  const op = redoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack: pushDraftHistoryOp(history?.undoStack || [], op),
      redoStack,
    },
    items: applyDraftForward(op, items || []),
  };
}
