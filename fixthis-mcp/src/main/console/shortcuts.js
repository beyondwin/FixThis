// @requires state.js, undoRedo.js, boundaryTriggers.js, draftUseCases.js, editorState.js
            function isTextInputFocused(target = document.activeElement) {
              const element = target?.nodeType === Node.ELEMENT_NODE ? target : target?.parentElement || document.activeElement;
              const tag = element?.tagName;
              return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || element?.isContentEditable;
            }

            function handleGlobalShortcut(event) {
              if (event.repeat) return;
              if (isTextInputFocused(event.target)) return;
              if (event.key === 'Escape') {
                event.preventDefault();
                const editorState = deriveEditorState(currentDraftWorkspace(), draftSelection(), null);
                const verdict = resolveTrigger(Trigger.ESCAPE_KEY, { state: editorState }, {
                  silentDiscard: clearSelection,
                  showToast: (text) => showStatus(text, { variant: 'info' }),
                  openBoundaryDialog: cancelAddItemsFlow,
                });
                if (verdict === 'pass') clearSelection();
                return;
              }
              if (event.key.toLowerCase() === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
                event.preventDefault();
                enterAnnotateMode().catch(showError);
                return;
              }
            }
