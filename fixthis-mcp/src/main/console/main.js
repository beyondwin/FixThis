// @requires state.js, connection.js, devices.js, preview.js, annotations.js, history.js, prompt.js, rendering.js, sessions-polling.js, events.js, shortcuts.js, draftUseCases.js, draftCommandQueue.js, editorBackButton.js, inspectorFooterActions.js, pendingRecoveryUi.js, application/consoleStore.js, application/consoleEffects.js, adapters/browserPorts.js, adapters/browserRenderer.js
            let pendingRecovery = null;
            const canonicalPorts = createBrowserConsolePorts({
              requestJson,
              localStorage,
              navigator,
            });
            const canonicalRenderer = createBrowserRenderer({
              renderHistory: renderCanonicalHistoryModel,
              renderCanvas: renderCanonicalCanvasModel,
              renderInspector: renderCanonicalInspectorModel,
              renderPrompt: renderCanonicalPromptModel,
              renderBoundary: renderCanonicalBoundaryModel,
              renderDraftLock: renderCanonicalDraftLockModel,
              renderToolbar: renderCanonicalToolbarModel,
            });
            let store;
            let consoleStore;
            store = createConsoleStore({
              render: canonicalRenderer.render,
              onEffects: (effects) => {
                for (const effect of effects) {
                  runConsoleEffect(effect, { ports: canonicalPorts, dispatch: store.dispatch }).catch(showError);
                }
              },
            });
            consoleStore = store;
            store.dispatch({ type: 'CONSOLE_BOOTSTRAPPED' });

            selectToolButton.addEventListener('click', enterSelectMode);
            annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));
            zoomOutButton.addEventListener('click', () => setPreviewZoom(previewUseCases.getState().zoom - PreviewZoomStep));
            zoomInButton.addEventListener('click', () => setPreviewZoom(previewUseCases.getState().zoom + PreviewZoomStep));
            copyPromptButton.addEventListener('click', () => copyPrompt().catch(showError));
            sendAgentButton.addEventListener('click', () => sendAgentPrompt().catch(showError));
            connectionPrimaryAction.addEventListener('click', () => handleConnectionPrimaryAction().catch(showError));
            document.getElementById('refreshDevicesButton').addEventListener('click', () => {
              refreshDevices()
                .then(refreshConnection)
                .catch(showError);
            });
            document.getElementById('forgetDeviceButton')?.addEventListener('click', () => forgetDevice().catch(showError));
            devicePicker.addEventListener('change', () => selectDevice().catch(showError));
            previewIntervalSelect.addEventListener('change', () => {
              localStorage.setItem(PreviewIntervalStorageKey, previewIntervalSelect.value);
              startLivePreviewPolling();
            });
            document.addEventListener('keydown', handleGlobalShortcut);
            // ALH-2: Undo/redo via Cmd+Z / Cmd+Shift+Z.
            window.addEventListener('keydown', (e) => {
              const active = document.activeElement;
              if (matchesUndo(e, active)) {
                e.preventDefault();
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') {
                  showError('Undo history was cleared because the annotation session changed.');
                } else if (!result.applied) {
                  showStatus('Nothing to undo.', { variant: 'info', durationMs: 2000 });
                } else {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              } else if (matchesRedo(e, active)) {
                e.preventDefault();
                const result = redo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') {
                  showError('Redo history was cleared because the annotation session changed.');
                } else if (!result.applied) {
                  showStatus('Nothing to redo.', { variant: 'info', durationMs: 2000 });
                } else {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              }
            });
            // ALH-1: warn user if they try to leave with unsaved pending items.
            window.addEventListener('beforeunload', (e) => {
              const editorState = deriveEditorState(currentDraftWorkspace(), draftSelection(), null);
              if (shouldGuardUnload(draftItemList().length, editorState)) {
                e.preventDefault();
                e.returnValue = 'You have unsaved annotations. Are you sure you want to leave?';
                return e.returnValue;
              }
            });
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
              startLivePreviewPolling();
              startSessionsPolling();
            });
            inspectorFooter?.addEventListener('click', (event) => {
              const actionTarget = event.target?.closest?.('[data-action]');
              const action = actionTarget?.dataset.action;
              handleInspectorFooterAction(action);
            });
            editorBack?.addEventListener('click', (event) => {
              event.preventDefault();
              const editorState = deriveEditorState(currentDraftWorkspace(), draftSelection(), selectedSavedAnnotation());
              if (editorState === 'saved') {
                closeSelectedSavedAnnotationDetail();
                return;
              }
              handleEditorBackClick({ state: editorState }, {
                silentDiscard: clearSelection,
                showToast: showStatus,
                navigateToList: navigateEditorToList,
                openBoundaryDialog: () => showStatus('Resolve this draft before leaving the editor.', { variant: 'warn' }),
              });
            });
            document.getElementById('sessionBoundarySheet')?.addEventListener('click', (event) => {
              const actionTarget = event.target?.closest?.('[data-boundary-action]');
              const action = actionTarget?.dataset.boundaryAction;
              if (!action || event.currentTarget?.dataset.boundaryVariant !== 'pendingRecovery') return;
              handlePendingRecoveryBoundaryAction(action);
            });
            comment.addEventListener('input', updateSelectedAnnotationComment);

            function friendlyErrorMessage(message) {
              const raw = String(message || '');
              const lower = raw.toLowerCase();
              if (
                raw.includes('Bridge closed before sending a response') ||
                (lower.includes('bridge') && lower.includes('timed out')) ||
                raw.includes('Could not connect to FixThis bridge')
              ) {
                return 'Connection paused. Your work is saved.';
              }
              if (raw.includes('DEVICE_NOT_AVAILABLE')) return 'Check your phone, then try again.';
              return raw;
            }

            function showError(cause) {
              showStatus(
                friendlyErrorMessage(cause && cause.message ? cause.message : cause),
                { variant: 'error', assertive: true },
              );
            }

            // ALH-2: 5-second undo toast shown after a pending item is deleted.
            function showUndoToast(itemId) {
              if (typeof document === 'undefined') return;
              const existing = document.querySelector('.fixthis-undo-toast');
              if (existing) existing.remove();
              const toast = document.createElement('div');
              toast.className = 'fixthis-undo-toast';
              toast.style.cssText = 'position:fixed;bottom:20px;right:20px;background:#323232;color:#fff;padding:12px 16px;border-radius:4px;display:flex;align-items:center;gap:12px;z-index:9999;font-size:14px;';
              const msg = document.createElement('span');
              msg.textContent = 'Annotation deleted';
              const btn = document.createElement('button');
              btn.textContent = 'Undo';
              btn.style.cssText = 'background:none;border:none;color:#bb86fc;cursor:pointer;font-size:14px;padding:0;font-weight:500;';
              btn.addEventListener('click', () => {
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                } else if (result.reason !== 'context_mismatch') {
                  showStatus('Nothing to undo.', { variant: 'info', durationMs: 2000 });
                }
                toast.remove();
              });
              toast.appendChild(msg);
              toast.appendChild(btn);
              document.body.appendChild(toast);
              setTimeout(() => { if (toast.parentNode) toast.remove(); }, 5000);
            }

            function pendingRecoveryItems(recovery) {
              return Array.isArray(recovery?.items) ? recovery.items : [];
            }

            function requirePendingRecoveryChoiceBeforeSessionChange() {
              if (hasCommentedDraftItems(pendingRecovery)) renderPendingRecoveryBanner();
              return true;
            }

            async function resolvePendingBeforeBoundary(action, sessionId = null) {
              const activeWorkspace = currentDraftWorkspace();
              const meta = {
                kind: 'session-boundary',
              };
              if (activeWorkspace?.workspaceId) {
                meta.workspaceId = activeWorkspace.workspaceId;
                meta.expectedRevision = activeWorkspace.revision;
              }
              const result = await ensureDraftCommandQueue().enqueue(meta, async (workspace) => {
                const boundaryResult = await resolveLifecycleBoundary({
                  action,
                  targetSessionId: sessionId,
                  activeWorkspace: workspace,
                  pendingRecovery,
                  ports: createBrowserDraftPorts(),
                });
                return { ...boundaryResult, workspace: boundaryResult.nextWorkspace };
              });
              const boundary = result?.result;
              if (boundary?.nextPendingRecovery !== pendingRecovery) {
                pendingRecovery = boundary?.nextPendingRecovery || null;
              }
              renderPendingRecoveryBanner();
              if (boundary?.conflict) {
                showError('Resolve the draft save conflict before changing sessions.');
                return 'cancel';
              }
              return boundary?.outcome === 'cancel' ? 'cancel' : 'continue';
            }

            function promptBoundaryDialogChoice(variant, context = {}) {
              return new Promise((resolve) => {
                renderBoundaryDialog(variant, context);
                const root = document.getElementById('sessionBoundarySheet');
                if (!root) {
                  resolve('cancel');
                  return;
                }
                function onClick(event) {
                  const action = event.target?.dataset?.boundaryAction;
                  if (!action) return;
                  root.removeEventListener('click', onClick);
                  statusSurfaceRegistry.hide('sessionBoundarySheet');
                  resolve(action);
                }
                root.addEventListener('click', onClick);
              });
            }

            function promptPendingBoundaryChoice(action, count) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingBoundary === 'function') {
                // Override path bypasses promptBoundaryDialogChoice click handlers,
                // so dismiss the sheet to match real-user dismissal.
                return Promise.resolve(window.fixThisPromptPendingBoundary({ action, count })).then((choice) => {
                  if (typeof statusSurfaceRegistry !== 'undefined') statusSurfaceRegistry.hide('sessionBoundarySheet');
                  else if (typeof document !== 'undefined') { const r = document.getElementById('sessionBoundarySheet'); if (r) r.hidden = true; }
                  return choice;
                });
              }
              if (action === 'delete-session') {
                return promptBoundaryDialogChoice('sessionDelete', { annotationCount: count, screenCount: 0 })
                  .then((choice) => choice === 'discardAndProceed' || choice === 'saveAndProceed' ? 'discard' : 'cancel');
              }
              if (action === 'new-session') {
                return promptBoundaryDialogChoice('sessionCreate', { itemCount: count })
                  .then((choice) => choice === 'saveAndProceed' ? 'save' : choice === 'discardAndProceed' ? 'discard' : 'cancel');
              }
              return promptBoundaryDialogChoice('sessionSwitch', { itemCount: count })
                .then((choice) => choice === 'saveAndProceed' ? 'save' : choice === 'discardAndProceed' ? 'discard' : 'cancel');
            }

            function promptPendingRecoveryBoundaryChoice(recovery, action) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingRecoveryBoundary === 'function') {
                return Promise.resolve(window.fixThisPromptPendingRecoveryBoundary({ recovery, action }));
              }
              return promptBoundaryDialogChoice('pendingRecovery', {
                canResume: hasRecoverablePreviewContext(recovery),
                itemCount: pendingRecoveryItems(recovery).length,
              }).then((choice) => choice === 'discard' ? 'clear' : 'cancel');
            }

            function hasRecoverablePreviewContext(recovery) {
              if (recovery?.schemaVersion === 2) {
                return Boolean(
                  recovery.context?.previewId &&
                  recovery.screen &&
                  recovery.screenshotUrl &&
                  recovery.context?.frozenAtEpochMillis
                );
              }
              return recovery?.schemaVersion === 1 &&
                Boolean(recovery.previewId) &&
                Boolean(recovery.screen) &&
                Boolean(recovery.screenshotUrl) &&
                Boolean(recovery.frozenAtEpochMillis);
            }

            function updateAnnotationSequenceFromPendingItems(items) {
              const current = toolMode.getState().annotationSequence;
              const next = nextAnnotationSequenceFromPendingItems(items, current);
              toolMode.setAnnotationSequenceAtLeast(next);
            }

            function restorePendingRecoveryContext(recovery) {
              const workspace = recoverDraftWorkspaceFromEnvelope(recovery);
              replaceDraftWorkspace(workspace);
              setConsolePreview({
                previewId: workspace.context.previewId,
                screen: workspace.screen,
                activity: workspace.context.activityName,
                frozenAtEpochMillis: workspace.context.frozenAtEpochMillis,
                stale: false
              });
              updateAnnotationSequenceFromPendingItems(workspace.items);
              setDraftFocusIndex(null);
              toolMode.focusSavedItem(null, null);
              setDraftSelection(null);
              toolMode.setHoveredTarget(null);
              toolMode.enterSelect();
              stopLivePreviewPolling();
              persistCurrentPendingState();
            }

            function newestDraftRecovery(workspaces) {
              return [...(workspaces || [])]
                .filter((workspace) => pendingRecoveryItems(workspace).length)
                .sort((left, right) => (left.updatedAtEpochMillis || 0) - (right.updatedAtEpochMillis || 0))
                .at(-1) || null;
            }

            function loadDraftRecoveryForSession(sessionId) {
              const storage = createBrowserDraftPorts().storage;
              return newestDraftRecovery(storage.loadWorkspacesForSession(sessionId));
            }

            function loadPendingRecoveryForCurrentSession() {
              if (!state.session?.sessionId) {
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              const sessionId = state.session.sessionId;
              if (draftFlow() || draftItemList().length) {
                renderPendingRecoveryBanner();
                return;
              }
              const restored = loadDraftRecoveryForSession(sessionId);
              const restoredSummary = draftRecoverySummary(restored);
              const ownership = draftRecoveryOwnership(restored, state.session);
              if (
                restoredSummary.total &&
                ownership.shouldAutoRestore &&
                ownership.canResume &&
                hasRecoverablePreviewContext(restored)
              ) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              pendingRecovery = restoredSummary.total ? { ...restored, recoveryOwnership: ownership } : null;
              renderPendingRecoveryBanner();
            }

            function refreshPendingRecoveryFromExternalStorageChange() {
              if (!state.session?.sessionId) return;
              if (draftFlow() || draftItemList().length) return;
              loadPendingRecoveryForCurrentSession();
              renderCurrentSessionList();
              renderInspectorRegion();
            }

            async function recapturePendingRecovery() {
              const recovery = pendingRecovery;
              const items = pendingRecoveryItems(recovery).slice();
              if (!recovery || !items.length) return;
              const accepted = await promptBoundaryDialogChoice('recaptureRecoveredDraft', {});
              if (accepted !== 'confirm') return;
              clearPreview();
              await startDraftAnnotationFlow();
              if (!draftFlow()) return;
              const recoveredItems = items.map((item, index) => ({
                ...item,
                draftItemId: item?.draftItemId || item?.annotationId || ('recovered-' + (index + 1)),
              }));
              const workspace = currentDraftWorkspace();
              replaceDraftWorkspace({
                ...workspace,
                revision: workspace.revision + 1,
                items: recoveredItems,
                history: { undoStack: [], redoStack: [] },
              });
              updateAnnotationSequenceFromPendingItems(recoveredItems);
              pendingRecovery = null;
              setDraftFocusIndex(null);
              toolMode.focusSavedItem(null, null);
              setDraftSelection(null);
              toolMode.setHoveredTarget(null);
              persistCurrentPendingState();
              renderPendingRecoveryBanner();
              render();
            }

            if (typeof window !== 'undefined') {
              window.addEventListener('storage', (event) => {
                if (!event.key || !event.key.startsWith(DraftWorkspaceKeyPrefix)) return;
                refreshPendingRecoveryFromExternalStorageChange();
              });
            }

            window.FixThisConsoleDebug = Object.freeze({
              getDraftWorkspace: () => currentDraftWorkspace(),
              getState: () => state,
            });

            // Debug-only teardown hook for the per-module dispose convention
            // (see state.js __fixthisDisposers, history.js initHistory).
            window.__fixthisDispose = () => {
              for (const slot of __fixthisDisposers) slot?.dispose?.();
            };

            initializePreviewIntervalSelect();
            applyPreviewZoom();
            refresh()
              .then(() => {
                loadPendingRecoveryForCurrentSession();
                if (shouldAutoFetchPreview()) return refreshPreview();
                return null;
              })
              .then(() => {
                startConsoleEvents();
                checkServerStaleness().catch((err) => console.warn('[fixthis] server staleness check failed:', err));
                startHeartbeatPolling();
                startLivePreviewPolling();
                startSessionsPolling();
              })
              .catch(showError);
