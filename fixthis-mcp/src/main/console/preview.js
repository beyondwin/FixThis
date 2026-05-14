            // Raw HTTP fetch — the previewUseCases layer provides dedup and
            // race-fence via the FSM. Cross-caller dedup (across draft port
            // and FSM) is achieved by routing all callers through
            // previewUseCases.request().
            function requestLivePreview() {
              return requestJson('/api/preview');
            }

            function invalidatePreviewContext() {
              previewUseCases.contextChanged();
              state.preview = null;
            }

            function scopedQuery(sessionId) {
              return sessionId ? '?sessionId=' + encodeURIComponent(sessionId) : '';
            }

            function previewScreenshotUrl(previewId, sessionId = state.session?.sessionId || null) {
              return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full' + scopedQuery(sessionId);
            }

            function screenScreenshotUrl(screenId, sessionId = state.session?.sessionId || null) {
              return '/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full' + scopedQuery(sessionId);
            }

            function screenImageUrl(screen) {
              if (addItemsFlow) return addItemsFlow.screenshotUrl;
              if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId, state.session?.sessionId || null);
              if (screen?.screenId) return screenScreenshotUrl(screen.screenId, state.session?.sessionId || toolModeUseCases.getState().focusedSavedSessionId || null);
              return '';
            }


            function configuredPreviewIntervalMs() {
              const rawValue = previewIntervalSelect.value;
              if (rawValue === 'manual') return null;
              const parsed = Number(rawValue || localStorage.getItem(PreviewIntervalStorageKey) || DefaultLivePreviewIntervalMs);
              return Math.max(1000, parsed);
            }

            function shouldPollPreview() {
              return !document.hidden && !addItemsFlow && Boolean(state.session) && Boolean(state.selectedDeviceSerial) && userConnectionState(state.connection.current) === 'ready';
            }

            function shouldAutoFetchPreview() {
              return configuredPreviewIntervalMs() != null && shouldPollPreview();
            }

            // livePreviewTimer is a closure-scoped browser timer handle.
            // It is not reducer state (not pure), so it lives here rather
            // than in previewFsm.js.
            let livePreviewTimer = null;
            function startLivePreviewPolling() {
              stopLivePreviewPolling();
              const intervalMs = configuredPreviewIntervalMs();
              if (!intervalMs) return;
              livePreviewTimer = setInterval(() => {
                if (shouldPollPreview()) refreshPreview().catch(showError);
              }, intervalMs);
            }

            function stopLivePreviewPolling() {
              if (livePreviewTimer) clearInterval(livePreviewTimer);
              livePreviewTimer = null;
            }

            function initializePreviewIntervalSelect() {
              const stored = localStorage.getItem(PreviewIntervalStorageKey);
              previewIntervalSelect.value = stored || String(DefaultLivePreviewIntervalMs);
              if (!previewIntervalSelect.value) previewIntervalSelect.value = String(DefaultLivePreviewIntervalMs);
            }

            function visibleScreenNodeUids(screen) {
              const uids = new Set();
              if (!screen) return uids;
              (screen.roots || []).forEach(root => {
                (root.mergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
                (root.unmergedNodes || []).forEach(node => { if (node?.uid) uids.add(node.uid); });
              });
              return uids;
            }

            function latestPersistedScreen() {
              const screens = state.session?.screens || [];
              const persistedScreenIds = new Set(
                (state.session?.items || []).map(item => item.screenId)
              );
              const screenshotScreens = screens
                .filter(screen => screen?.screenshot?.desktopFullPath);
              return screenshotScreens
                .filter(screen => persistedScreenIds.has(screen.screenId))
                .sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0))[0] ||
                screenshotScreens
                .sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0))[0] || null;
            }

            function latestScreen() {
              if (addItemsFlow) return addItemsFlow.screen;
              const focusedSavedItemId = toolModeUseCases.getState().focusedSavedItemId;
              if (focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const focusedScreen = (state.session?.screens || []).find(s => s.screenId === focusedItem.screenId);
                  if (focusedScreen) return focusedScreen;
                }
              }
              return state.preview?.screen || latestPersistedScreen();
            }

            function clamp(value, min, max) {
              return Math.min(Math.max(value, min), max);
            }

            function applyPreviewZoom() {
              const frame = document.getElementById('snapshotFrame');
              const zoom = previewUseCases.getState().zoom;
              zoomPercent.textContent = Math.round(zoom * 100) + '%';
              zoomOutButton.disabled = zoom <= PreviewZoomMin;
              zoomInButton.disabled = zoom >= PreviewZoomMax;
              if (frame) {
                frame.style.setProperty('--preview-zoom', String(zoom));
              }
            }

            function setPreviewZoom(nextZoom) {
              previewUseCases.setZoom(nextZoom);
              applyPreviewZoom();
            }


            async function refreshPreview() {
              error.textContent = '';
              if (!state.session || addItemsFlow) return;
              const requestGeneration = previewUseCases.getState().requestGeneration + 1;
              try {
                const preview = await previewUseCases.request();
                if (addItemsFlow || requestGeneration !== previewUseCases.getState().requestGeneration) return;
                state.preview = {
                  ...preview,
                  activity: state.connection?.availability?.activity ?? null,
                  frozenAtEpochMillis: Date.now(),
                  stale: false,
                };
                if (userConnectionState(state.connection.current) === 'ready') markPreviewStale(false);
                renderPreviewOnly();
              } catch (cause) {
                markPreviewStale(true);
                refreshConnection({ preservePreviewStale: true }).catch(() => {});
                throw cause;
              }
            }


            async function navigate(action, extras = {}) {
              error.textContent = '';
              if (!state.session) return;
              const navigation = await requestJson('/api/navigation', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  action: action,
                  captureAfter: false,
                  ...extras
                })
              });
              clearSelection();
              await refresh();
              await refreshPreview();
              if (navigation.captureError) {
                error.textContent = 'Navigation performed, but capture failed: ' + navigation.captureError;
              }
            }

            function renderCanvasBlockedOverlay() {
              const overlay = document.getElementById('canvasBlockedOverlay');
              if (!overlay) return;
              const reason = state.connection?.interactionBlockedReason ?? null;
              if (!reason) {
                overlay.hidden = true;
                return;
              }
              overlay.hidden = false;
              const headlines = {
                screenOff: 'Device screen is off',
                locked: 'Device is locked',
                background: 'Sample app is in the background',
                pictureInPicture: 'Sample app is in Picture-in-Picture',
                unresponsive: 'Sample app is unresponsive',
                noComposeUi: 'No Compose UI on this screen',
              };
              const details = {
                screenOff: 'Wake the device to continue.',
                locked: 'Unlock the device to continue.',
                background: 'Bring the sample app to the foreground.',
                pictureInPicture: 'Exit Picture-in-Picture to continue.',
                unresponsive: 'Retrying…',
                noComposeUi: 'Switch to a screen with Compose content to annotate.',
              };
              overlay.querySelector('[data-headline]').textContent = headlines[reason] ?? '';
              overlay.querySelector('[data-detail]').textContent = details[reason] ?? '';
              const retry = overlay.querySelector('[data-retry]');
              retry.hidden = reason !== 'unresponsive';
            }

            document.getElementById('canvasBlockedOverlay')?.querySelector('[data-retry]')?.addEventListener('click', () => {
              refreshConnection().catch(showError);
            });

            function renderStaleFrameNotice() {
              const root = document.getElementById('canvasStaleNotice');
              if (!root) return;
              if (state.preview?.stale) {
                root.hidden = false;
              } else {
                root.hidden = true;
              }
            }

            async function useLatestStaleFrame() {
              const wasAnnotating = toolModeUseCases.isAnnotateMode() || Boolean(addItemsFlow);
              flushFocusedPendingComment();
              const pendingItems = draftWorkspaceItems(draftWorkspace).slice();
              const previousWorkspace = draftWorkspace;
              const previousPreview = state.preview;
              invalidatePreviewContext();
              if (wasAnnotating) {
                setDraftWorkspace(createEmptyDraftWorkspace());
                try {
                  await startAddItemsFlow();
                } catch (cause) {
                  setDraftWorkspace(previousWorkspace);
                  state.preview = previousPreview;
                  if (addItemsFlow) persistCurrentPendingState();
                  render();
                  throw cause;
                }
                if (!addItemsFlow) {
                  setDraftWorkspace(previousWorkspace);
                  state.preview = previousPreview;
                  render();
                  return;
                }
                setDraftWorkspace({
                  ...draftWorkspace,
                  revision: draftWorkspace.revision + 1,
                  items: pendingItems,
                  history: { undoStack: [], redoStack: [] },
                });
                updateAnnotationSequenceFromPendingItems(pendingItems);
                focusedPendingItemIndex = null;
                toolModeUseCases.focusSavedItem(null, null);
                currentSelection = null;
                toolModeUseCases.setHoveredTarget(null);
                persistCurrentPendingState();
              } else {
                await refreshPreview();
              }
              render();
            }

            document.getElementById('canvasStaleNotice')?.querySelector('[data-use-latest]')?.addEventListener('click', () => {
              useLatestStaleFrame().catch(showError);
            });
