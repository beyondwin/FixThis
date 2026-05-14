// @requires state.js
            // undoRedo.js — ALH-2 pure undo/redo for pending feedback items.

            const UNDO_MAX_DEPTH = 50;

            function createHistory(context = null) {
              return { context: cloneHistoryContext(context), undoStack: [], redoStack: [] };
            }

            function pushOp(stack, op) {
              stack.push(op);
              if (stack.length > UNDO_MAX_DEPTH) stack.shift();
            }

            function cloneHistoryContext(context) {
              if (!context) return null;
              return {
                sessionId: context.sessionId || null,
                previewId: context.previewId || null,
                screenId: context.screenId || null,
                screenFingerprint: context.screenFingerprint || null,
                deviceSerial: context.deviceSerial || null
              };
            }

            function sameHistoryContext(left, right) {
              const a = cloneHistoryContext(left);
              const b = cloneHistoryContext(right);
              if (!a && !b) return true;
              return Boolean(a && b) &&
                a.sessionId === b.sessionId &&
                a.previewId === b.previewId &&
                a.screenId === b.screenId &&
                a.screenFingerprint === b.screenFingerprint &&
                a.deviceSerial === b.deviceSerial;
            }

            function clearHistory(history) {
              history.undoStack.length = 0;
              history.redoStack.length = 0;
            }

            function itemStableId(item) {
              return item?.itemId ?? item?.annotationId ?? null;
            }

            function sameHistoryItem(left, right) {
              const leftId = itemStableId(left);
              const rightId = itemStableId(right);
              return leftId != null && rightId != null && leftId === rightId;
            }

            function recordAdd(history, item, context = history.context) {
              pushOp(history.undoStack, {
                kind: 'add',
                after: { ...item },
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function recordDelete(history, before, index = null, context = history.context) {
              if (!before) return;
              pushOp(history.undoStack, {
                kind: 'delete',
                before: { ...before },
                index: Number.isInteger(index) ? index : null,
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function recordUpdate(history, before, after, context = history.context) {
              if (!before || !after) return;
              pushOp(history.undoStack, {
                kind: 'update',
                before: { ...before },
                after: { ...after },
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function applyInverse(op, state) {
              const items = state.items || state['draft' + 'FeedbackItems'];
              if (op.kind === 'add') {
                const idx = items.findIndex((item) => sameHistoryItem(item, op.after));
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'delete') {
                const restored = { ...op.before };
                if (Number.isInteger(op.index)) {
                  const index = Math.max(0, Math.min(op.index, items.length));
                  items.splice(index, 0, restored);
                } else {
                  items.push(restored);
                  items.sort((a, b) => (a.sequenceNumber || 0) - (b.sequenceNumber || 0));
                }
              } else if (op.kind === 'update') {
                const target = items.find((item) => sameHistoryItem(item, op.before));
                if (target) Object.assign(target, op.before);
              }
            }

            function applyForward(op, state) {
              const items = state.items || state['draft' + 'FeedbackItems'];
              if (op.kind === 'add') {
                items.push({ ...op.after });
              } else if (op.kind === 'delete') {
                const idx = items.findIndex((item) => sameHistoryItem(item, op.before));
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'update') {
                const target = items.find((item) => sameHistoryItem(item, op.after));
                if (target) Object.assign(target, op.after);
              }
            }

            function undo(history, state, context = history.context) {
              const op = history.undoStack.pop();
              if (!op) return { applied: false, reason: 'empty' };
              if (!sameHistoryContext(op.context, context)) {
                clearHistory(history);
                return { applied: false, reason: 'context_mismatch' };
              }
              applyInverse(op, state);
              pushOp(history.redoStack, op);
              return { applied: true };
            }

            function redo(history, state, context = history.context) {
              const op = history.redoStack.pop();
              if (!op) return { applied: false, reason: 'empty' };
              if (!sameHistoryContext(op.context, context)) {
                clearHistory(history);
                return { applied: false, reason: 'context_mismatch' };
              }
              applyForward(op, state);
              pushOp(history.undoStack, op);
              return { applied: true };
            }

            function applyUndoToItems(history, items, context) {
              const holder = { items: items.slice() };
              const result = undo(history, holder, context);
              return Object.freeze({ ...result, items: holder.items.slice() });
            }

            function applyRedoToItems(history, items, context) {
              const holder = { items: items.slice() };
              const result = redo(history, holder, context);
              return Object.freeze({ ...result, items: holder.items.slice() });
            }
