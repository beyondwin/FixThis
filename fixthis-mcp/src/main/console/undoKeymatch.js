            // undoKeymatch.js — ALH-2 pure undo/redo keyboard match helpers.
            // The actual addEventListener('keydown', ...) wraps in main.js.

            function isInEditableField(activeElement) {
              if (!activeElement) return false;
              const tag = activeElement.tagName || '';
              if (tag === 'INPUT' || tag === 'TEXTAREA') return true;
              if (activeElement.isContentEditable) return true;
              return false;
            }

            function matchesUndo(event, activeElement) {
              if (!event) return false;
              if (isInEditableField(activeElement)) return false;
              const meta = !!(event.metaKey || event.ctrlKey);
              const key = (event.key || '').toLowerCase();
              return meta && key === 'z' && !event.shiftKey;
            }

            function matchesRedo(event, activeElement) {
              if (!event) return false;
              if (isInEditableField(activeElement)) return false;
              const meta = !!(event.metaKey || event.ctrlKey);
              const key = (event.key || '').toLowerCase();
              return meta && key === 'z' && event.shiftKey === true;
            }
