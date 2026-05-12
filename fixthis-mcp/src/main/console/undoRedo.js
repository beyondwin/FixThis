            // undoRedo.js — ALH-2 pure undo/redo for pending feedback items.
            // Caller passes a state shape { pendingFeedbackItems: [...] }; no
            // closure reference needed.

            const UNDO_MAX_DEPTH = 50;

            function createHistory() {
              return { undoStack: [], redoStack: [] };
            }

            function pushOp(stack, op) {
              stack.push(op);
              if (stack.length > UNDO_MAX_DEPTH) stack.shift();
            }

            function recordAdd(history, item) {
              pushOp(history.undoStack, { kind: 'add', after: { ...item } });
              history.redoStack.length = 0;
            }

            function recordDelete(history, before) {
              if (!before) return;
              pushOp(history.undoStack, { kind: 'delete', before: { ...before } });
              history.redoStack.length = 0;
            }

            function recordUpdate(history, before, after) {
              if (!before || !after) return;
              pushOp(history.undoStack, { kind: 'update', before: { ...before }, after: { ...after } });
              history.redoStack.length = 0;
            }

            function applyInverse(op, state) {
              const items = state.pendingFeedbackItems;
              if (op.kind === 'add') {
                const idx = items.findIndex((i) => i.itemId === op.after.itemId);
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'delete') {
                items.push({ ...op.before });
                items.sort((a, b) => (a.sequenceNumber || 0) - (b.sequenceNumber || 0));
              } else if (op.kind === 'update') {
                const target = items.find((i) => i.itemId === op.before.itemId);
                if (target) Object.assign(target, op.before);
              }
            }

            function applyForward(op, state) {
              const items = state.pendingFeedbackItems;
              if (op.kind === 'add') {
                items.push({ ...op.after });
              } else if (op.kind === 'delete') {
                const idx = items.findIndex((i) => i.itemId === op.before.itemId);
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'update') {
                const target = items.find((i) => i.itemId === op.after.itemId);
                if (target) Object.assign(target, op.after);
              }
            }

            function undo(history, state) {
              const op = history.undoStack.pop();
              if (!op) return false;
              applyInverse(op, state);
              pushOp(history.redoStack, op);
              return true;
            }

            function redo(history, state) {
              const op = history.redoStack.pop();
              if (!op) return false;
              applyForward(op, state);
              pushOp(history.undoStack, op);
              return true;
            }
