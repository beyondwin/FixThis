// @requires state.js, connection.js, devices.js, preview.js, annotations.js, history.js, prompt.js, rendering.js, sessions-polling.js, shortcuts.js, draftUseCases.js, draftCommandQueue.js
            let pendingRecovery = null;
            const activePendingMirrorSessions = new Set();

            selectToolButton.addEventListener('click', enterSelectMode);
            annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));
            zoomOutButton.addEventListener('click', () => setPreviewZoom(previewUseCases.getState().zoom - PreviewZoomStep));
            zoomInButton.addEventListener('click', () => setPreviewZoom(previewUseCases.getState().zoom + PreviewZoomStep));
            addItemButton.addEventListener('click', () => {
              try {
                createAnnotationFromSelection(currentSelection);
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
                const result = undo(undoRedoHistory, { draftFeedbackItems }, activeDraftFlow?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  e.preventDefault();
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              } else if (matchesRedo(e, active)) {
                const result = redo(undoRedoHistory, { draftFeedbackItems }, activeDraftFlow?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  e.preventDefault();
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              }
            });
            // ALH-1: warn user if they try to leave with unsaved pending items.
            window.addEventListener('beforeunload', (e) => {
              if (shouldGuardUnload(draftFeedbackItems.length)) {
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
                const result = undo(undoRedoHistory, { draftFeedbackItems }, activeDraftFlow?.context ?? null);
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

            function hasPendingRecoveryItems() {
              return pendingRecoveryItems(pendingRecovery).length > 0;
            }

            function requirePendingRecoveryChoiceBeforeSessionChange() {
              if (!hasPendingRecoveryItems()) return true;
              renderPendingRecoveryBanner();
              showError('Recover, recapture, or discard unsaved annotations before changing sessions.');
              return false;
            }

            async function resolvePendingBeforeBoundary(action, sessionId = null) {
              const hasActivePending = Boolean(draftWorkspace?.workspaceId && draftWorkspaceItems(draftWorkspace).length);
              if (!hasActivePending && !hasPendingRecoveryItems()) return 'continue';
              if (hasPendingRecoveryItems() && !hasActivePending) {
                renderPendingRecoveryBanner();
                showError('Recover, recapture, or discard unsaved annotations before changing sessions.');
                return 'cancel';
              }
              const pendingSessionId = draftWorkspace?.context?.sessionId || null;
              if (sessionId && pendingSessionId && sessionId !== pendingSessionId) {
                createBrowserDraftPorts().storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
                activePendingMirrorSessions.add(pendingSessionId);
                setDraftWorkspace(createEmptyDraftWorkspace());
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
              const current = toolModeUseCases.getState().annotationSequence;
              const next = (items || [])
                .map(item => String(item?.annotationId || '').match(/^local-(\d+)$/))
                .filter(Boolean)
                .map(match => Number(match[1]))
                .filter(Number.isFinite)
                .reduce((max, value) => Math.max(max, value + 1), current);
              toolModeUseCases.setAnnotationSequenceAtLeast(next);
            }

	            function restorePendingRecoveryContext(recovery) {
	              const workspace = recoverDraftWorkspaceFromEnvelope(recovery);
	              setDraftWorkspace(workspace);
	              setConsolePreview({
	                previewId: workspace.context.previewId,
	                screen: workspace.screen,
	                activity: workspace.context.activityName,
	                frozenAtEpochMillis: workspace.context.frozenAtEpochMillis,
	                stale: false
	              });
              updateAnnotationSequenceFromPendingItems(workspace.items);
              focusedPendingItemIndex = null;
              toolModeUseCases.focusSavedItem(null, null);
              currentSelection = null;
              toolModeUseCases.setHoveredTarget(null);
              toolModeUseCases.enterSelect();
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
              const recoveryItems = pendingRecoveryItems(pendingRecovery);
              if (!pendingRecovery || !recoveryItems.length || activeDraftFlow || draftFeedbackItems.length) {
                banner.hidden = true;
                return;
              }
              const canRecover = hasRecoverablePreviewContext(pendingRecovery);
              const itemLabel = countLabel(recoveryItems.length, 'annotation', 'annotations');
              const detail = canRecover
                ? 'Recover restores the frozen preview and pins from this session.'
                : 'This older saved draft has pins only. Recapture to attach them to a new frozen preview, or discard it.';
              banner.hidden = false;
              banner.innerHTML =
                '<div class="pending-recovery-copy" data-pending-recovery-copy>' +
                  '<strong>Unsaved ' + escapeHtml(itemLabel) + ' found</strong>' +
                  '<div>' + escapeHtml(detail) + '</div>' +
                '</div>' +
                '<div class="annotation-actions pending-recovery-actions">' +
                  (canRecover ? '<button type="button" class="annotation-done" data-recover-pending>Recover</button>' : '') +
                  '<button type="button" class="annotation-done" data-recapture-pending>Recapture</button>' +
                  '<button type="button" class="annotation-danger" data-discard-pending>Discard</button>' +
                '</div>';
              banner.querySelector('[data-recover-pending]')?.addEventListener('click', () => {
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
              banner.querySelector('[data-discard-pending]')?.addEventListener('click', () => {
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
              if (activeDraftFlow || draftFeedbackItems.length) {
                renderPendingRecoveryBanner();
                return;
              }
              const restored = loadDraftRecoveryForSession(sessionId) || restorePendingState(sessionId);
              if (activePendingMirrorSessions.has(sessionId) && pendingRecoveryItems(restored).length && hasRecoverablePreviewContext(restored)) {
                restorePendingRecoveryContext(restored);
                pendingRecovery = null;
                renderPendingRecoveryBanner();
                return;
              }
              pendingRecovery = pendingRecoveryItems(restored).length ? restored : null;
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
              invalidateCanonicalPreviewContext();
              await startDraftAnnotationFlow();
              if (!activeDraftFlow) return;
              const recoveredItems = items.map((item, index) => ({
                ...item,
                draftItemId: item?.draftItemId || item?.annotationId || ('recovered-' + (index + 1)),
              }));
              setDraftWorkspace({
                ...draftWorkspace,
                revision: draftWorkspace.revision + 1,
                items: recoveredItems,
                history: { undoStack: [], redoStack: [] },
              });
              updateAnnotationSequenceFromPendingItems(recoveredItems);
              pendingRecovery = null;
              focusedPendingItemIndex = null;
              toolModeUseCases.focusSavedItem(null, null);
              currentSelection = null;
              toolModeUseCases.setHoveredTarget(null);
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
                checkServerStaleness().catch(() => { /* silent */ });
                startHeartbeatPolling();
                startLivePreviewPolling();
                startSessionsPolling();
              })
              .catch(showError);
