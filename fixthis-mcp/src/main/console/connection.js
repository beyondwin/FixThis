// @requires state.js, api.js
            // Heartbeat timer handles. These are connection-internal browser
            // timer state (not reducer state, not pure) so they live here
            // rather than in connectionFsm.js or state.js.
            let heartbeatTimer = null;
            let heartbeatPolling = false;
            const LaunchConnectionRefreshDelaysMs = Object.freeze([800, 1200, 2000, 3000, 5000]);
            let launchRefreshTimer = null;

            function connectionActionLabel(action) {
              if (action === 'START') return 'Start';
              if (action === 'OPEN_APP') return 'Open app';
              if (action === 'RECONNECT') return 'Reconnect';
              if (action === 'TRY_AGAIN') return 'Try again';
              if (action === 'CHOOSE_DEVICE') return 'Choose device';
              if (action === 'CAPTURE') return 'Capture screen';
              return 'Continue';
            }

            function userConnectionState(status) {
              if (!status) return 'welcome';
              const rawState = String(status.state || 'WELCOME').toLowerCase();
              if (rawState === 'open_app' && connectionUseCases.getState().hasEverConnected) return 'reconnect';
              return rawState;
            }

            function shouldContinueLaunchConnectionRefresh(status) {
              const rawState = String(status?.state || '').toLowerCase();
              return rawState === 'starting' || rawState === 'open_app' || rawState === 'reconnect';
            }

            function clearLaunchConnectionRefresh() {
              if (launchRefreshTimer) clearTimeout(launchRefreshTimer);
              launchRefreshTimer = null;
            }

            function scheduleLaunchConnectionRefresh(attempt = 0) {
              clearLaunchConnectionRefresh();
              const currentStatus = connectionUseCases.getState().current;
              if (!shouldContinueLaunchConnectionRefresh(currentStatus)) return;
              if (attempt >= LaunchConnectionRefreshDelaysMs.length) return;
              const delayMs = LaunchConnectionRefreshDelaysMs[attempt];
              launchRefreshTimer = setTimeout(() => {
                launchRefreshTimer = null;
                refreshConnection({ preservePreviewStale: true })
                  .then((status) => {
                    if (!status) {
                      scheduleLaunchConnectionRefresh(attempt + 1);
                      return;
                    }
                    if (userConnectionState(status) === 'ready') {
                      if (state.session && !draftFlow()) refreshPreview().catch(showError);
                      return;
                    }
                    if (shouldContinueLaunchConnectionRefresh(status)) {
                      scheduleLaunchConnectionRefresh(attempt + 1);
                    }
                  })
                  .catch((cause) => {
                    console.warn('[fixthis] launch connection refresh failed:', cause);
                    scheduleLaunchConnectionRefresh(attempt + 1);
                  });
              }, delayMs);
            }

            function connectionDetailsText(status) {
              if (!status) return 'No connection check has run yet.';
              const details = status.details || {};
              const connectionState = connectionUseCases.getState();
              return [
                'Device: ' + (status.selectedDevice ? deviceLabel(status.selectedDevice) + ' - ' + text(status.selectedDevice.state) : 'none'),
                'Package: ' + text(status.packageName),
                'Bridge: ' + text(details.bridgeState),
                'Last connected: ' + (connectionState.lastReadyAt ? new Date(connectionState.lastReadyAt).toLocaleTimeString() : '-'),
                'Raw error: ' + text(details.rawError)
              ].join('\n');
            }

            function markPreviewStale(stale) {
              const hasPreviewSurface = Boolean(state.preview || draftFlow()?.screen || latestPersistedScreen());
              if (stale && hasPreviewSurface) {
                statusSurfaceRegistry.show('previewStaleBadge', {
                  surfaceClass: 'badge',
                  priority: 3,
                  element: previewStaleBadge,
                  content: 'Connection paused - showing last preview',
                });
              } else {
                statusSurfaceRegistry.hide('previewStaleBadge');
              }
            }

            function applyDisconnect({ hasDirtyDraft = false } = {}) {
              const overlay = document.getElementById('canvasBlockedOverlay');
              if (overlay) {
                statusSurfaceRegistry.show('canvasBlockedOverlay', {
                  surfaceClass: 'modalCanvas',
                  priority: 1,
                  element: overlay,
                  content: {
                    headline: 'Device disconnected',
                    detail: 'Reconnecting...',
                    retry: true,
                  },
                });
              }
              statusSurfaceRegistry.hide('canvasStaleNotice');
              statusSurfaceRegistry.hide('previewStaleBadge');
              const banner = document.getElementById('stalenessBanner');
              if (hasDirtyDraft && banner) {
                statusSurfaceRegistry.show('stalenessBanner', {
                  surfaceClass: 'banner',
                  priority: 2,
                  element: banner,
                  content: '1 unsaved draft preserved locally',
                });
              } else {
                statusSurfaceRegistry.hide('stalenessBanner');
              }
            }

            function applyReconnect({ targetStale = false } = {}) {
              statusSurfaceRegistry.hide('canvasBlockedOverlay');
              if (targetStale) {
                const notice = document.getElementById('canvasStaleNotice');
                if (notice) {
                  const title = notice.querySelector('[data-stale-title]');
                  const detail = notice.querySelector('[data-stale-detail]');
                  if (title) title.textContent = 'Recovered draft';
                  if (detail) detail.textContent = 'Live preview paused for this frozen frame.';
                  statusSurfaceRegistry.show('canvasStaleNotice', {
                    surfaceClass: 'inline',
                    priority: 3,
                    element: notice,
                  });
                }
                statusSurfaceRegistry.hide('previewStaleBadge');
                return;
              }
              statusSurfaceRegistry.hide('canvasStaleNotice');
              const badge = document.getElementById('previewStaleBadge');
              if (badge) {
                statusSurfaceRegistry.show('previewStaleBadge', {
                  surfaceClass: 'badge',
                  priority: 3,
                  element: badge,
                  content: 'Connection restored - refreshing preview',
                  autoDismissMs: 2000,
                });
              }
            }

            function shouldShowDisconnectChoreography(viewState) {
              return viewState === 'check_phone' || viewState === 'reconnect';
            }

            function syncSelectedDeviceFromConnection(status) {
              const selectedDevice = status?.selectedDevice;
              if (!selectedDevice?.serial) return;

              const connectionDevices = Array.isArray(status.devices) && status.devices.length
                ? status.devices
                : state.devices;
              if (connectionDevices && connectionDevices.length) {
                state.devices = connectionDevices;
              }
              if (!deviceBySerial(state.devices, selectedDevice.serial)) {
                state.devices = (state.devices || []).concat([selectedDevice]);
              }

              state.selectedDeviceSerial = selectedDevice.serial;
              devicePicker.disabled = false;
              devicePicker.innerHTML = '';
              state.devices.forEach(device => {
                const option = document.createElement('option');
                option.value = device.serial;
                option.textContent = deviceOptionLabel(device);
                option.disabled = device.state !== 'device';
                option.selected = device.serial === selectedDevice.serial;
                devicePicker.appendChild(option);
              });

              const selected = deviceBySerial(state.devices, selectedDevice.serial) || selectedDevice;
              devicePicker.value = selectedDevice.serial;
              setDeviceUiState(
                selected.state === 'device' ? DeviceUiState.CONNECTED : DeviceUiState.UNAVAILABLE,
                selected
              );
            }

            function computeCurrentBlockedReason(statusAvailability) {
              const annotate = toolMode.isAnnotateMode();
              const availability = statusAvailability ?? connectionUseCases.getState().availability;
              const resolverInput = availability
                ? { ...availability, unresponsive: unresponsiveTracker.isUnresponsive() }
                : { unresponsive: unresponsiveTracker.isUnresponsive() };
              const rawReason = computeBlockedReason(resolverInput, annotate);
              return blockedReasonDebouncer.observe(rawReason);
            }

            function recomputeInteractionBlockedReason() {
              const reason = computeCurrentBlockedReason();
              connectionUseCases.interactionBlocked(reason);
            }

            function applyConnectionStatus(status, options) {
              const connectionOptions = options || {};
              const previousConnectionState = connectionUseCases.getState();
              const hadEverConnected = previousConnectionState.hasEverConnected;
              const previousViewState = userConnectionState(previousConnectionState.current);

              // Compute the new blocked reason from the incoming availability
              // BEFORE dispatching, so STATUS_RECEIVED can write it atomically
              // (avoiding a stale-availability window between dispatches).
              const newBlockedReason = computeCurrentBlockedReason(status?.availability ?? null);

              // Capture the prior previousBlockedReason for transition detection.
              // STATUS_RECEIVED will roll it forward on dispatch.
              const priorPreviousBlockedReason = connectionUseCases.getState().previousBlockedReason;

              connectionUseCases.setStatus(status, newBlockedReason);
              const currentConnectionState = connectionUseCases.getState();

              // success → clear failure streak
              unresponsiveTracker.observeSuccess();

              // Mark frozen preview stale when the device's foreground activity has changed.
              const restoredActivity = state.preview?.activity ?? null;
              const currentActivity = status?.availability?.activity ?? null;
              if (state.preview?.obstructedBySystemUi) {
                state.preview.stale = true;
              } else if (state.preview && restoredActivity && currentActivity) {
                state.preview.stale = restoredActivity !== currentActivity;
              } else if (state.preview) {
                state.preview.stale = false;
              }
              // SIF-5: also evaluate time-based + disconnect-based staleness on every
              // status tick. OR'd with the activity-drift result above so existing
              // semantics are preserved.
              if (state.preview) {
                const bridgeConnection = userConnectionState(status) === 'ready' ? 'connected' : 'disconnected';
                const stalenessInput = {
                  preview: state.preview,
                  bridgeStatus: { connection: bridgeConnection },
                };
                state.preview.stale = state.preview.stale || evaluateStale(stalenessInput, Date.now());
              }

              // Detect blocked → unblocked transitions for select-mode auto-resume.
              // Use the captured prior previousBlockedReason vs the new reason.
              if (priorPreviousBlockedReason !== null && newBlockedReason === null) {
                if (toolMode.isSelectMode() && state.session) {
                  refreshPreview().catch(showError);
                }
              }
              // previousBlockedReason rolling is handled inside STATUS_RECEIVED.

              syncSelectedDeviceFromConnection(status);
              const viewState = userConnectionState(status);
              if (!shouldContinueLaunchConnectionRefresh(status)) clearLaunchConnectionRefresh();
              if (viewState === 'ready') {
                // hasEverConnected and lastReadyAt are set by STATUS_RECEIVED above.
                if (!connectionOptions.preservePreviewStale && !state.preview?.obstructedBySystemUi) markPreviewStale(false);
                startLivePreviewPolling();
              } else {
                stopLivePreviewPolling();
                if (currentConnectionState.hasEverConnected) markPreviewStale(true);
              }
              renderConnection(status);
              // Re-render the preview region so the canvas blocked-reason overlay and
              // stale-frame notice reflect the latest interactionBlockedReason / preview.stale.
              // Without this, the heartbeat-driven state update never reaches the canvas.
              renderPreviewRegion();
              const hasDirtyDraft = draftItemList().length > 0;
              if (viewState === 'ready') {
                if (hadEverConnected && previousViewState !== 'ready' && !currentConnectionState.interactionBlockedReason) {
                  applyReconnect({ targetStale: Boolean(state.preview?.stale) });
                }
              } else if (shouldShowDisconnectChoreography(viewState) && !state.preview && (hadEverConnected || hasDirtyDraft)) {
                // When a salvageable preview exists, the milder previewStaleBadge
                // ("Connection paused - showing last preview") owns the UX. The
                // modalCanvas overlay would suspend the badge surface class and
                // contradict that messaging, so we skip applyDisconnect here.
                applyDisconnect({ hasDirtyDraft });
              } else if (!currentConnectionState.interactionBlockedReason) {
                statusSurfaceRegistry.hide('canvasBlockedOverlay');
              }
              checkProtocolCompat(status);
              checkSidekickBuildEpoch(status);
              if (viewState === 'ready' && annotateIntent && !draftFlow()) {
                const shouldResumeAnnotating = annotateIntentMatchesCurrentContext();
                clearAnnotateIntent();
                if (shouldResumeAnnotating) startDraftAnnotationFlow().catch(showError);
              }
            }

            function renderConnection(status) {
              const connectionState = connectionUseCases.getState();
              const viewState = userConnectionState(status);
              connectionCard.dataset.connectionState = viewState;
              connectionCard.dataset.reconnectVisible = viewState === 'reconnect' || connectionState.sessionsPollingPaused ? 'true' : 'false';
              connectionHeadline.textContent = viewState === 'reconnect'
                ? 'Reconnect'
                : (status?.headline || 'Connect to your app');
              connectionMessage.textContent = viewState === 'reconnect'
                ? 'The connection was interrupted. Your work is saved.'
                : (status?.message || "We'll find your phone and open the app for you.");
              const action = viewState === 'reconnect'
                ? 'RECONNECT'
                : (status?.primaryAction || (viewState === 'starting' ? 'OPEN_APP' : null));
              connectionPrimaryAction.textContent = connectionActionLabel(action);
              connectionPrimaryAction.disabled = connectionState.launchInFlight;
              connectionPrimaryAction.dataset.connectionAction = action || 'START';
              if (connectionState.sessionsPollingPaused) {
                // Surface a sub-line indicating sessions polling is paused. The details panel
                // preserves line breaks and wraps long tokens through .connection-details pre.
                const baseDetails = connectionDetailsText(status);
                connectionDetailsBody.textContent = baseDetails
                  ? baseDetails + '\nReconnecting feedback updates…'
                  : 'Reconnecting feedback updates…';
              } else {
                connectionDetailsBody.textContent = connectionDetailsText(status);
              }
              connectionDetails.hidden = viewState === 'ready'
                && !connectionState.hasEverConnected
                && !connectionState.sessionsPollingPaused;
            }

            async function refreshConnection(options) {
              try {
                const status = await requestJson('/api/connection');
                applyConnectionStatus(status, options);
                return status;
              } catch (error) {
                unresponsiveTracker.observeFailure();
                recomputeInteractionBlockedReason();
                showError(error);
              }
            }

            function welcomeConnectionStatus() {
              return {
                state: 'WELCOME',
                headline: 'Connect to your app',
                message: "We'll find your phone and open the app for you.",
                primaryAction: 'START',
                packageName: state.session?.packageName || '',
                details: { deviceState: 'none', bridgeState: 'not checked' }
              };
            }

            async function launchApp() {
              clearLaunchConnectionRefresh();
              connectionUseCases.launchRequested();
              renderConnection(connectionUseCases.getState().current);
              let succeeded = false;
              try {
                const status = await requestJson('/api/app/launch', { method: 'POST' });
                applyConnectionStatus(status);
                scheduleLaunchConnectionRefresh();
                succeeded = true;
              } finally {
                if (succeeded) connectionUseCases.launchSucceeded();
                else connectionUseCases.launchFailed();
                renderConnection(connectionUseCases.getState().current);
              }
            }

            async function captureScreen() {
              if (!state.session) {
                showStatus('Connect to a session before capturing a screen.', { variant: 'warn', durationMs: 3000 });
                return;
              }
              await refreshPreview();
            }

            async function handleConnectionPrimaryAction() {
              const action = connectionPrimaryAction.dataset.connectionAction || 'START';
              if (action === 'START' || action === 'OPEN_APP' || action === 'RECONNECT') {
                await launchApp();
                return;
              }
              if (action === 'TRY_AGAIN') {
                await refreshDevices();
                await refreshConnection();
                return;
              }
              if (action === 'CHOOSE_DEVICE') {
                devicePicker.focus();
                return;
              }
              if (action === 'CAPTURE') {
                await captureScreen();
              }
            }


            let lastHeartbeatError = null;

            function handleHeartbeatError(cause) {
              const message = cause && cause.message ? cause.message : String(cause);
              if (message === lastHeartbeatError) return;
              lastHeartbeatError = message;
              showError(cause);
            }

            async function sendBridgeHeartbeat() {
              if (!state.session || !state.selectedDeviceSerial) return;
              await refreshConnection();
              lastHeartbeatError = null;
            }

            function scheduleNextHeartbeat() {
              if (!heartbeatPolling) return;
              const nextDelayMs = unresponsiveTracker.nextBackoffMs();
              heartbeatTimer = setTimeout(() => {
                heartbeatTimer = null;
                if (!heartbeatPolling) return;
                if (!state.selectedDeviceSerial) {
                  scheduleNextHeartbeat();
                  return;
                }
                sendBridgeHeartbeat()
                  .catch(handleHeartbeatError)
                  .finally(scheduleNextHeartbeat);
              }, nextDelayMs);
            }

            function startHeartbeatPolling() {
              stopHeartbeatPolling();
              heartbeatPolling = true;
              sendBridgeHeartbeat()
                .catch(handleHeartbeatError)
                .finally(scheduleNextHeartbeat);
            }

            function stopHeartbeatPolling() {
              heartbeatPolling = false;
              if (heartbeatTimer) clearTimeout(heartbeatTimer);
              heartbeatTimer = null;
            }
