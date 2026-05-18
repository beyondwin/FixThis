// @requires state.js, api.js, draftWorkspace.js, previewPoll.js, sse.js
            // Raw HTTP fetch — the previewUseCases layer provides dedup and
            // race-fence via the FSM. Cross-caller dedup (across draft port
            // and FSM) is achieved by routing all callers through
            // previewUseCases.request().
            function requestLivePreview() {
              return requestJson('/api/preview');
            }

            function requestCanonicalPreviewCapture() {
              // Canonical preview effects are disabled while legacy draft freeze owns runtime capture.
            }

            function clearPreview() {
              previewUseCases.contextChanged();
              setConsolePreview(null);
            }

            function capturePreviewContext() {
              return {
                sessionId: state.session?.sessionId || null,
                contextGeneration: previewUseCases.getState().contextGeneration,
              };
            }

            function previewContextStillCurrent(context) {
              return Boolean(context) &&
                context.sessionId === (state.session?.sessionId || null) &&
                context.contextGeneration === previewUseCases.getState().contextGeneration;
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
              if (draftFlow()) return draftFlow().screenshotUrl;
              if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId, state.session?.sessionId || null);
              const savedContext = toolMode.getState();
              const savedSessionId = screen?.screenId === savedContext.focusedSavedScreenId ? savedContext.focusedSavedSessionId : null;
              if (screen?.screenId) return screenScreenshotUrl(screen.screenId, savedSessionId || state.session?.sessionId || null);
              return '';
            }


            function configuredPreviewIntervalMs() {
              const rawValue = previewIntervalSelect.value;
              if (rawValue === 'manual') return null;
              const parsed = Number(rawValue || localStorage.getItem(PreviewIntervalStorageKey) || DefaultLivePreviewIntervalMs);
              return Math.max(1000, parsed);
            }

            function shouldPollPreview() {
              return !document.hidden && !draftFlow() && Boolean(state.session) && Boolean(state.selectedDeviceSerial) && userConnectionState(state.connection.current) === 'ready';
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
              if (draftFlow()) return draftFlow().screen;
              const toolModeState = toolMode.getState();
              const focusedSavedItemId = toolModeState.focusedSavedItemId;
              let savedScreen = null;
              if (focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const focusedScreen = (state.session?.screens || []).find(s => s.screenId === focusedItem.screenId);
                  if (focusedScreen) savedScreen = focusedScreen;
                }
              }
              if (!savedScreen && toolModeState.focusedSavedScreenId) {
                savedScreen = (state.session?.screens || []).find(s => s.screenId === toolModeState.focusedSavedScreenId) || null;
              }
              return savedScreen || state.preview?.screen || latestPersistedScreen();
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


            // Render the CAPTURE_UNAVAILABLE readiness in the connection-card readiness
            // slot when /api/preview reports `previewAvailable: false`, and restore the
            // Capture button label / clear the slot when previewAvailable recovers.
            function applyPreviewReadinessToConnectionCard(preview) {
              const slot = document.getElementById('connectionReadiness');
              const button = document.getElementById('connectionPrimaryAction');
              if (preview && preview.previewAvailable === false && preview.readiness) {
                if (slot) {
                  slot.hidden = false;
                  slot.dataset.state = preview.readiness.state || '';
                  slot.textContent = (preview.readiness.cause || '') +
                    (preview.readiness.verify ? ' ' + preview.readiness.verify : '');
                  slot.title = preview.readiness.verify || '';
                }
                if (button) {
                  button.textContent = preview.readiness.nextAction || 'Retry capture';
                  button.dataset.connectionAction = 'CAPTURE';
                }
                return;
              }
              if (slot) {
                slot.hidden = true;
                slot.dataset.state = '';
                slot.textContent = '';
                slot.title = '';
              }
              if (button) {
                button.textContent = 'Capture screen';
              }
            }

            function applyLivePreview(preview, options = {}) {
              if (!preview || draftFlow()) return false;
              const activeSessionId = state.session?.sessionId || null;
              const ownerSessionId = options.sessionId || preview.sessionId || null;
              if (ownerSessionId && (options.source === 'sse'
                ? dropStaleSse({ sessionId: ownerSessionId }, activeSessionId)
                : dropStalePreviewPoll({ sessionId: ownerSessionId }, activeSessionId))) return false;
              applyPreviewReadinessToConnectionCard(preview);
              if (preview?.previewAvailable === false) {
                renderPreviewOnly();
                return true;
              }
              if (preview.screen?.systemUiVisible && state.preview) {
                state.preview.stale = true;
                state.preview.obstructedBySystemUi = preview.screen.systemUiKind || 'system_ui';
                markPreviewStale(true);
                renderPreviewOnly();
                return true;
              }
              previewUseCases.applyReady(preview);
              setConsolePreview({
                ...preview,
                activity: state.connection?.availability?.activity ?? null,
                frozenAtEpochMillis: Date.now(),
                stale: false,
              });
              if (userConnectionState(state.connection.current) === 'ready') markPreviewStale(false);
              renderPreviewOnly();
              return true;
            }

            async function refreshPreview() {
              error.textContent = '';
              if (!state.session || draftFlow()) return;
              const previewContext = capturePreviewContext();
              try {
                const preview = await previewUseCases.request();
                if (draftFlow() || !previewContextStillCurrent(previewContext)) return;
                applyLivePreview(preview, {
                  source: 'poll',
                  sessionId: preview?.sessionId || state.session?.sessionId || null,
                });
              } catch (cause) {
                markPreviewStale(true);
                refreshConnection({ preservePreviewStale: true }).catch((err) => console.warn('[fixthis] background connection refresh failed:', err));
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
                statusSurfaceRegistry.hide('canvasBlockedOverlay');
                return;
              }
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
              statusSurfaceRegistry.show('canvasBlockedOverlay', {
                surfaceClass: 'modalCanvas',
                priority: 1,
                element: overlay,
                content: {
                  headline: headlines[reason] ?? '',
                  detail: details[reason] ?? '',
                  retry: reason === 'unresponsive',
                },
              });
            }

            document.getElementById('canvasBlockedOverlay')?.querySelector('[data-retry]')?.addEventListener('click', () => {
              refreshConnection().catch(showError);
            });

            function renderStaleFrameNotice() {
              const root = document.getElementById('canvasStaleNotice');
              if (!root) return;
              if (!state.preview?.stale || state.connection?.interactionBlockedReason) {
                statusSurfaceRegistry.hide('canvasStaleNotice');
                return;
              }
              const title = root.querySelector('[data-stale-title]');
              const detail = root.querySelector('[data-stale-detail]');
              if (title) title.textContent = 'Recovered draft';
              if (detail) detail.textContent = 'Live preview paused for this frozen frame.';
              statusSurfaceRegistry.show('canvasStaleNotice', {
                surfaceClass: 'inline',
                priority: 3,
                element: root,
              });
            }

            async function useLatestStaleFrame() {
              const wasAnnotating = toolMode.isAnnotateMode() || Boolean(draftFlow());
              flushFocusedPendingComment();
              const pendingItems = draftWorkspaceItems(draftWorkspace).slice();
              const previousWorkspace = draftWorkspace;
              const previousPreview = state.preview;
              clearPreview();
              if (wasAnnotating) {
                replaceDraftWorkspace(createEmptyDraftWorkspace());
                try {
                  await startDraftAnnotationFlow();
                } catch (cause) {
                  replaceDraftWorkspace(previousWorkspace);
                  setConsolePreview(previousPreview);
                  if (draftFlow()) persistCurrentPendingState();
                  render();
                  throw cause;
                }
                if (!draftFlow()) {
                  replaceDraftWorkspace(previousWorkspace);
                  setConsolePreview(previousPreview);
                  render();
                  return;
                }
                replaceDraftWorkspace({
                  ...draftWorkspace,
                  revision: draftWorkspace.revision + 1,
                  items: pendingItems,
                  history: { undoStack: [], redoStack: [] },
                });
                updateAnnotationSequenceFromPendingItems(pendingItems);
                setDraftFocusIndex(null);
                toolMode.focusSavedItem(null, null);
                setDraftSelection(null);
                toolMode.setHoveredTarget(null);
                persistCurrentPendingState();
              } else {
                await refreshPreview();
              }
              render();
            }

            document.getElementById('canvasStaleNotice')?.querySelector('[data-use-latest]')?.addEventListener('click', () => {
              useLatestStaleFrame().catch(showError);
            });
