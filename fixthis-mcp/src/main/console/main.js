// @requires state.js, connection.js, devices.js, preview.js, annotations.js, history.js, prompt.js, rendering.js, sessions-polling.js, events.js, shortcuts.js, draftUseCases.js, draftCommandQueue.js, application/consoleStore.js, application/consoleEffects.js, adapters/browserPorts.js, adapters/browserRenderer.js
            let pendingRecovery = null;
            const activePendingMirrorSessions = new Set();
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
            addItemButton.addEventListener('click', () => {
              try {
                createAnnotationFromSelection(draftSelection());
              } catch (cause) {
                showError(cause);
              }
            });
            copyPromptButton.addEventListener('click', () => copyPrompt().catch(showError));
            sendAgentButton.addEventListener('click', () => sendAgentPrompt().catch(showError));
            connectionPrimaryAction.addEventListener('click', () => handleConnectionPrimaryAction().catch(showError));
            document.getElementById('refreshDevicesButton').addEventListener('click', () => {
              refreshDevices()
                .then(refreshConnection)
                .catch(showError);
            });
            document.getElementById('disconnectDeviceButton').addEventListener('click', () => disconnectDevice().catch(showError));
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
                store.dispatch({ type: 'UNDO_CLICKED' });
              } else if (matchesRedo(e, active)) {
                e.preventDefault();
                store.dispatch({ type: 'REDO_CLICKED' });
              }
            });
            // ALH-1: warn user if they try to leave with unsaved pending items.
            window.addEventListener('beforeunload', (e) => {
              if (shouldGuardUnload(draftItemList().length)) {
                e.preventDefault();
                e.returnValue = '저장하지 않은 어노테이션이 있습니다. 정말 떠나시겠습니까?';
                return e.returnValue;
              }
            });
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
              startLivePreviewPolling();
              startSessionsPolling();
            });
            clearSelectionButton.addEventListener('click', clearSelection);
            cancelAddFlowButton.addEventListener('click', cancelAddItemsFlow);
            clearDraftButton.addEventListener('click', () => clearDraft().catch(showError));
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
              msg.textContent = '어노테이션 삭제됨';
              const btn = document.createElement('button');
              btn.textContent = '되돌리기';
              btn.style.cssText = 'background:none;border:none;color:#bb86fc;cursor:pointer;font-size:14px;padding:0;font-weight:500;';
              btn.addEventListener('click', () => {
                const result = undo(undoRedoHistory, { items: draftItemList() }, draftFlow()?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
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
              const hasActivePending = Boolean(draftWorkspace?.workspaceId && draftWorkspaceItems(draftWorkspace).length);
              if (!hasActivePending && !pendingRecoveryItems(pendingRecovery).length) return 'continue';
              if (pendingRecoveryItems(pendingRecovery).length && !hasActivePending) {
                renderPendingRecoveryBanner();
                return 'continue';
              }
              const pendingSessionId = draftWorkspace?.context?.sessionId || null;
              const activeCommented = commentedDraftItems(draftWorkspaceItems(draftWorkspace));
              if (hasActivePending && activeCommented.length === 0) {
                createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
                if (pendingSessionId) activePendingMirrorSessions.add(pendingSessionId);
                replaceDraftWorkspace(createEmptyDraftWorkspace());
                return 'continue';
              }
              if (sessionId && pendingSessionId && sessionId !== pendingSessionId) {
                createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
                activePendingMirrorSessions.add(pendingSessionId);
                replaceDraftWorkspace(createEmptyDraftWorkspace());
                return 'continue';
              }
              const result = await ensureDraftCommandQueue().enqueue({
                kind: 'session-boundary',
                workspaceId: draftWorkspace.workspaceId,
                expectedRevision: draftWorkspace.revision,
              }, async (workspace) => {
                return await resolveDraftBoundary(workspace, { kind: action, sessionId }, createBrowserDraftPorts());
              });
              if (result?.result?.conflict) {
                showError('Resolve the draft save conflict before changing sessions.');
                return 'cancel';
              }
              return result?.result?.choice === 'cancel' ? 'cancel' : 'continue';
            }

            function promptPendingBoundaryChoice(action, count) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptPendingBoundary === 'function') {
                return Promise.resolve(window.fixThisPromptPendingBoundary({ action, count }));
              }
              if (typeof window === 'undefined' || typeof window.confirm !== 'function') return Promise.resolve('cancel');
              if (action === 'delete-session') {
                const discard = window.confirm('Discard unsaved annotations and delete this session?\n확인 = Delete session\n취소 = Keep editing');
                return Promise.resolve(discard ? 'discard' : 'cancel');
              }
              const save = window.confirm('Save draft before changing sessions?\n확인 = Save draft\n취소 = Keep editing or discard');
              if (save) return Promise.resolve('save');
              const discard = window.confirm('Discard unsaved annotations?\n확인 = Discard\n취소 = Keep editing');
              return Promise.resolve(discard ? 'discard' : 'cancel');
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
              const next = (items || [])
                .map(item => String(item?.annotationId || '').match(/^local-(\d+)$/))
                .filter(Boolean)
                .map(match => Number(match[1]))
                .filter(Number.isFinite)
                .reduce((max, value) => Math.max(max, value + 1), current);
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
              const stored = storage.loadWorkspacesForSession(sessionId);
              const migrated = storage.migrateLegacyPending(sessionId);
              return newestDraftRecovery(stored.concat(migrated));
            }

            function ensurePendingRecoveryBanner() {
              let banner = document.getElementById('pendingRecoveryBanner');
              if (banner) return banner;
              banner = document.createElement('div');
              banner.id = 'pendingRecoveryBanner';
              banner.setAttribute('data-testid', 'pending-recovery-banner');
              banner.className = 'annotation-banner annotation-banner-warn pending-recovery-banner';
              banner.setAttribute('role', 'status');
              banner.setAttribute('aria-live', 'polite');
              const parent = pendingItems?.parentElement || inspectorBody || document.body;
              if (pendingItems && pendingItems.parentElement === parent) {
                parent.insertBefore(banner, pendingItems);
              } else {
                parent.prepend(banner);
              }
              return banner;
            }

            function renderPendingRecoveryBanner() {
              const banner = ensurePendingRecoveryBanner();
              const summary = draftRecoverySummary(pendingRecovery);
              if (!pendingRecovery || !summary.total || draftFlow() || draftItemList().length) {
                banner.hidden = true;
                return;
              }
              const canResume = hasRecoverablePreviewContext(pendingRecovery);
              const summaryParts = [];
              if (summary.commented) summaryParts.push(countLabel(summary.commented, 'draft comment', 'draft comments'));
              if (summary.pinOnly) summaryParts.push(countLabel(summary.pinOnly, 'pin without comment', 'pins without comments'));
              const detail = canResume
                ? 'Resume the local draft for this session.'
                : 'Recapture the current app screen to continue this local draft.';
              banner.hidden = false;
              banner.innerHTML =
                '<div class="pending-recovery-copy" data-pending-recovery-copy>' +
                  '<strong>' + escapeHtml(summaryParts.join(' · ')) + '</strong>' +
                  '<div>' + escapeHtml(detail) + '</div>' +
                '</div>' +
                '<div class="annotation-actions pending-recovery-actions">' +
                  (canResume ? '<button type="button" class="annotation-done" data-resume-pending>Resume draft</button>' : '') +
                  '<button type="button" class="annotation-done" data-recapture-pending>Recapture</button>' +
                  '<button type="button" class="annotation-danger" data-clear-pending>Clear local draft</button>' +
                '</div>';
              banner.querySelector('[data-resume-pending]')?.addEventListener('click', () => {
                if (!hasRecoverablePreviewContext(pendingRecovery)) return;
                const recoverySessionId = pendingRecovery?.sessionId || pendingRecovery?.context?.sessionId || state.session?.sessionId;
                restorePendingRecoveryContext(pendingRecovery);
                if (recoverySessionId) activePendingMirrorSessions.add(recoverySessionId);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                render();
              });
              banner.querySelector('[data-recapture-pending]')?.addEventListener('click', () => {
                recapturePendingRecovery().catch(showError);
              });
              banner.querySelector('[data-clear-pending]')?.addEventListener('click', () => {
                if (pendingRecovery?.schemaVersion === 2) {
                  createBrowserDraftPorts().storage.deleteWorkspace(pendingRecovery.sessionId || pendingRecovery.context?.sessionId, pendingRecovery.workspaceId);
                }
                clearPendingMirror(state.session?.sessionId);
                activePendingMirrorSessions.delete(state.session?.sessionId);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
              });
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
              const restored = loadDraftRecoveryForSession(sessionId) || restorePendingState(sessionId);
              const restoredSummary = draftRecoverySummary(restored);
              if (activePendingMirrorSessions.has(sessionId) && pendingRecoveryItems(restored).length && hasRecoverablePreviewContext(restored)) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              if (restoredSummary.total && restoredSummary.commented === 0 && hasRecoverablePreviewContext(restored)) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              pendingRecovery = restoredSummary.total ? restored : null;
              renderPendingRecoveryBanner();
            }

            async function recapturePendingRecovery() {
              const recovery = pendingRecovery;
              const items = pendingRecoveryItems(recovery).slice();
              if (!recovery || !items.length) return;
              if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
                const accepted = window.confirm('Recapture the current app screen and remap recovered pins to the new frozen preview?');
                if (!accepted) return;
              }
              clearPreview();
              await startDraftAnnotationFlow();
              if (!draftFlow()) return;
              const recoveredItems = items.map((item, index) => ({
                ...item,
                draftItemId: item?.draftItemId || item?.annotationId || ('recovered-' + (index + 1)),
              }));
              replaceDraftWorkspace({
                ...draftWorkspace,
                revision: draftWorkspace.revision + 1,
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

            window.FixThisConsoleDebug = Object.freeze({
              getDraftWorkspace: () => draftWorkspace,
              getState: () => state,
            });

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
                checkServerStaleness().catch(() => { /* silent */ });
                startHeartbeatPolling();
                startLivePreviewPolling();
                startSessionsPolling();
              })
              .catch(showError);
