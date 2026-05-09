// state.js
            const DefaultLivePreviewIntervalMs = 1000;
            const MinLivePreviewIntervalMs = 1000;
            const HeartbeatIntervalMs = 2000;
            const PreviewIntervalStorageKey = 'fixthis.previewIntervalMs.v2';
            const state = {
              session: null,
              preview: null,
              sessionSummaries: [],
              selectedDeviceSerial: null,
              devices: [],
              connection: {
                current: null,
                hasEverConnected: false,
                lastReadyAt: null,
                launchInFlight: false
              }
            };
            const sessions = document.getElementById('sessions');
            const sentHistory = document.getElementById('sentHistory');
            const snapshot = document.getElementById('snapshot');
            const connectionCard = document.getElementById('connectionCard');
            const connectionHeadline = document.getElementById('connectionHeadline');
            const connectionMessage = document.getElementById('connectionMessage');
            const connectionPrimaryAction = document.getElementById('connectionPrimaryAction');
            const connectionDetails = document.getElementById('connectionDetails');
            const connectionDetailsBody = document.getElementById('connectionDetailsBody');
            const inspectorTitle = document.getElementById('inspectorTitle');
            const inspectorCount = document.getElementById('inspectorCount');
            const inspectorBody = document.getElementById('inspectorBody');
            const inspectorFooter = document.getElementById('inspectorFooter');
            const draftItems = document.getElementById('draftItems');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const deviceControl = document.getElementById('deviceControl');
            const deviceName = document.getElementById('deviceName');
            const deviceConnectionState = document.getElementById('deviceConnectionState');
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const selectionSummary = document.getElementById('selectionSummary');
            const clearSelectionButton = document.getElementById('clearSelectionButton');
            const addItemButton = document.getElementById('addItemButton');
            const copyPromptButton = document.getElementById('copyPromptButton');
            const sendAgentButton = document.getElementById('sendAgentButton');
            const clearSentHistoryButton = document.getElementById('clearSentHistoryButton');
            const cancelAddFlowButton = document.getElementById('cancelAddFlowButton');
            const clearDraftButton = document.getElementById('clearDraftButton');
            const selectToolButton = document.getElementById('selectToolButton');
            const annotateToolButton = document.getElementById('annotateToolButton');
            const toolStatus = document.getElementById('toolStatus');
            const zoomOutButton = document.getElementById('zoomOutButton');
            const zoomInButton = document.getElementById('zoomInButton');
            const zoomPercent = document.getElementById('zoomPercent');
            const previewStaleBadge = document.getElementById('previewStaleBadge');
            let livePreviewTimer = null;
            let heartbeatTimer = null;
            let previewRequestGeneration = 0;
            let previewRequestContextGeneration = 0;
            let previewRequestInFlight = null;
            let previewRequestInFlightContextGeneration = null;
            let addItemsFlow = null;
            let addItemsFlowStarting = false;
            let newHistoryAnnotateModeStarting = false;
            let promptActionInFlight = false;
            let pendingFeedbackItems = [];
            let focusedPendingItemIndex = null;
            let focusedSavedItemId = null;
            let focusedSavedSessionId = null;
            let currentSelection = null;
            let toolMode = 'select';
            let annotationSequence = 1;
            let hoveredAnnotationTarget = null;
            let dragStart = null;
            let dragPreview = null;
            let suppressNextClick = false;
            let previewZoom = 1;

            const PreviewZoomMin = 0.5;
            const PreviewZoomMax = 2;
            const PreviewZoomStep = 0.1;

            const DeviceUiState = {
              NONE: 'none',
              CONNECTING: 'connecting',
              CONNECTED: 'connected',
              UNAVAILABLE: 'unavailable'
            };

            const DeviceStateCopy = {
              [DeviceUiState.NONE]: 'No device',
              [DeviceUiState.CONNECTING]: 'Connecting',
              [DeviceUiState.CONNECTED]: 'Connected',
              [DeviceUiState.UNAVAILABLE]: 'Unavailable'
            };

            function text(value) {
              return value == null || value === '' ? '-' : String(value);
            }

            function escapeHtml(value) {
              return text(value)
                .replaceAll('&', '&amp;')
                .replaceAll('<', '&lt;')
                .replaceAll('>', '&gt;')
                .replaceAll('"', '&quot;')
                .replaceAll("'", '&#39;');
            }

            function escapeHtmlValue(value) {
              return String(value ?? '')
                .replaceAll('&', '&amp;')
                .replaceAll('<', '&lt;')
                .replaceAll('>', '&gt;')
                .replaceAll('"', '&quot;')
                .replaceAll("'", '&#39;');
            }

            function formatTime(epochMillis) {
              if (!epochMillis) return '-';
              return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }

            function formatHistoryDate(epochMillis) {
              if (!epochMillis) return '-';
              const date = new Date(epochMillis);
              const day = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
              const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
              return day + ' · ' + time;
            }

            function humanize(value) {
              const normalized = text(value);
              if (normalized === '-') return normalized;
              return normalized
                .split('_')
                .join(' ')
                .split('.')
                .filter(Boolean)
                .map(part => part.charAt(0).toUpperCase() + part.slice(1))
                .join(' ');
            }

            function countLabel(count, singular, plural) {
              return String(count) + ' ' + (count === 1 ? singular : plural);
            }

            let successClearTimeout = null;

            function showSuccess(message, durationMs = 2000) {
              if (successClearTimeout) {
                clearTimeout(successClearTimeout);
                successClearTimeout = null;
              }
              error.textContent = message;
              error.classList.add('status-success');
              successClearTimeout = setTimeout(() => {
                error.textContent = '';
                error.classList.remove('status-success');
                successClearTimeout = null;
              }, durationMs);
            }

            function clearSuccessStatus() {
              if (successClearTimeout) {
                clearTimeout(successClearTimeout);
                successClearTimeout = null;
              }
              error.classList.remove('status-success');
            }

// api.js
            async function requestJson(path, options = {}) {
              const method = (options.method || 'GET').toUpperCase();
              const headers = new Headers(options.headers || {});
              if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
                const token = window.FixThisConsoleConfig?.consoleToken;
                if (token) headers.set('X-FixThis-Console-Token', token);
              }
              const response = await fetch(path, { ...options, headers });
              if (!response.ok) {
                throw new Error(await response.text() || 'HTTP ' + response.status);
              }
              return response.json();
            }


            async function copyTextToClipboard(text) {
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(text);
                  return;
                }
              } catch (cause) {
                // Fall back below for browser surfaces that deny Clipboard API writes.
              }
              const fallback = document.createElement('textarea');
              fallback.value = text;
              fallback.setAttribute('readonly', '');
              fallback.style.position = 'fixed';
              fallback.style.top = '-9999px';
              fallback.style.left = '-9999px';
              document.body.appendChild(fallback);
              fallback.focus();
              fallback.select();
              const copied = document.execCommand('copy');
              fallback.remove();
              if (!copied) throw new Error('Copy failed. Select the prompt and copy it manually.');
            }

// connection.js
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
              if (rawState === 'open_app' && state.connection.hasEverConnected) return 'reconnect';
              return rawState;
            }

            function connectionDetailsText(status) {
              if (!status) return 'No connection check has run yet.';
              const details = status.details || {};
              return [
                'Device: ' + (status.selectedDevice ? deviceLabel(status.selectedDevice) + ' - ' + text(status.selectedDevice.state) : 'none'),
                'Package: ' + text(status.packageName),
                'Bridge: ' + text(details.bridgeState),
                'Last connected: ' + (state.connection.lastReadyAt ? new Date(state.connection.lastReadyAt).toLocaleTimeString() : '-'),
                'Raw error: ' + text(details.rawError)
              ].join('\n');
            }

            function markPreviewStale(stale) {
              const hasPreviewSurface = Boolean(state.preview || addItemsFlow?.screen || latestPersistedScreen());
              previewStaleBadge.hidden = !stale || !hasPreviewSurface;
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

            function applyConnectionStatus(status, options) {
              const connectionOptions = options || {};
              state.connection.current = status;
              syncSelectedDeviceFromConnection(status);
              const viewState = userConnectionState(status);
              if (viewState === 'ready') {
                state.connection.hasEverConnected = true;
                state.connection.lastReadyAt = Date.now();
                if (!connectionOptions.preservePreviewStale) markPreviewStale(false);
                startLivePreviewPolling();
              } else {
                stopLivePreviewPolling();
                if (pendingFeedbackItems.length || state.preview) markPreviewStale(true);
              }
              renderConnection(status);
            }

            function renderConnection(status) {
              const viewState = userConnectionState(status);
              connectionCard.dataset.connectionState = viewState;
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
              connectionPrimaryAction.disabled = state.connection.launchInFlight;
              connectionPrimaryAction.dataset.connectionAction = action || 'START';
              connectionDetailsBody.textContent = connectionDetailsText(status);
              connectionDetails.hidden = viewState === 'ready' && !state.connection.hasEverConnected;
            }

            async function refreshConnection(options) {
              const status = await requestJson('/api/connection');
              applyConnectionStatus(status, options);
              return status;
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
              state.connection.launchInFlight = true;
              renderConnection(state.connection.current);
              try {
                const status = await requestJson('/api/app/launch', { method: 'POST' });
                applyConnectionStatus(status);
                setTimeout(() => refreshConnection().catch(showError), 800);
              } finally {
                state.connection.launchInFlight = false;
                renderConnection(state.connection.current);
              }
            }

            async function captureScreen() {
              if (!state.session) return;
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

            function startHeartbeatPolling() {
              stopHeartbeatPolling();
              sendBridgeHeartbeat().catch(handleHeartbeatError);
              heartbeatTimer = setInterval(() => {
                if (state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
              }, HeartbeatIntervalMs);
            }

            function stopHeartbeatPolling() {
              if (heartbeatTimer) clearInterval(heartbeatTimer);
              heartbeatTimer = null;
            }

// devices.js
            function shortenDeviceSerial(serial) {
              const raw = String(serial || '').trim();
              if (!raw) return '';
              const withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];
              if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);
              if (withoutServiceSuffix.length <= 24) return withoutServiceSuffix;
              return withoutServiceSuffix.slice(0, 10) + '...' + withoutServiceSuffix.slice(-6);
            }

            function deviceLabel(device) {
              if (!device) return 'No device';
              return device.label || device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device';
            }

            function deviceDetail(device) {
              if (!device) return 'No device';
              if (device.state === 'device') return 'connected';
              return (device.state || 'unknown') + ' · unavailable';
            }

            function deviceOptionLabel(device) {
              return deviceLabel(device) + ' - ' + deviceDetail(device);
            }

            function setDeviceUiState(uiState, device = null) {
              deviceControl.dataset.connectionState = uiState;
              deviceName.textContent = device ? deviceLabel(device) : 'No device';
              deviceConnectionState.textContent = DeviceStateCopy[uiState];
              deviceStatus.textContent = deviceName.textContent + ' - ' + deviceConnectionState.textContent;
            }


            function deviceBySerial(devices, serial) {
              if (!serial) return null;
              return (devices || []).find(device => device.serial === serial) || null;
            }

            function renderDeviceList(payload) {
              const devices = payload.devices || [];
              state.devices = devices;
              const previousSelectedDeviceSerial = state.selectedDeviceSerial;
              devicePicker.innerHTML = '';

              if (!devices.length) {
                const selectedSerial = null;
                if (previousSelectedDeviceSerial !== selectedSerial) {
                  invalidatePreviewContext();
                  renderPreviewOnly();
                }
                state.selectedDeviceSerial = selectedSerial;
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'No devices available';
                devicePicker.appendChild(option);
                devicePicker.disabled = true;
                setDeviceUiState(DeviceUiState.NONE);
                return;
              }

              const selectedSerialFromPayload = payload.selectedSerial || null;
              const selected = devices.find(device => device.selected || device.serial === selectedSerialFromPayload) || null;
              devicePicker.disabled = false;

              if (!selected) {
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'Select device...';
                option.disabled = true;
                option.selected = true;
                devicePicker.appendChild(option);
              }

              devices.forEach(device => {
                const option = document.createElement('option');
                option.value = device.serial;
                option.textContent = deviceOptionLabel(device);
                option.disabled = device.state !== 'device';
                option.selected = Boolean(device.selected) || device.serial === selectedSerialFromPayload;
                devicePicker.appendChild(option);
              });

              const selectedSerial = selected && selected.state === 'device' ? selected.serial : null;
              if (previousSelectedDeviceSerial !== selectedSerial) {
                invalidatePreviewContext();
                renderPreviewOnly();
              }
              state.selectedDeviceSerial = selectedSerial;

              if (!selected) {
                setDeviceUiState(DeviceUiState.NONE);
              } else if (selected.state === 'device') {
                setDeviceUiState(DeviceUiState.CONNECTED, selected);
              } else {
                setDeviceUiState(DeviceUiState.UNAVAILABLE, selected);
              }
            }

            async function refreshDevices() {
              if (state.selectedDeviceSerial) {
                setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, state.selectedDeviceSerial));
              }
              let payload = await requestJson('/api/devices');
              const devices = payload.devices || [];
              const connectedDevices = (payload.devices || []).filter(device => device.state === 'device');
              if (!payload.selectedSerial && devices.length === 1 && connectedDevices.length === 1) {
                setDeviceUiState(DeviceUiState.CONNECTING, connectedDevices[0]);
                payload = await requestJson('/api/device/select', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ serial: connectedDevices[0].serial })
                });
              }
              renderDeviceList(payload);
            }

            async function selectDevice() {
              const option = devicePicker.selectedOptions[0];
              if (!option || !option.value || option.disabled) return;
              setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));
              invalidatePreviewContext();
              try {
                renderDeviceList(await requestJson('/api/device/select', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ serial: option.value })
                }));
                startHeartbeatPolling();
                await refreshConnection();
                if (state.session && userConnectionState(state.connection.current) === 'ready') {
                  await refreshPreview();
                  startLivePreviewPolling();
                }
              } catch (cause) {
                state.selectedDeviceSerial = null;
                stopHeartbeatPolling();
                stopLivePreviewPolling();
                setDeviceUiState(DeviceUiState.UNAVAILABLE, deviceBySerial(state.devices, option.value) || { serial: option.value });
                throw cause;
              }
            }

            async function disconnectDevice() {
              invalidatePreviewContext();
              renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
              setDeviceUiState(DeviceUiState.NONE);
              render();
              stopHeartbeatPolling();
              applyConnectionStatus(welcomeConnectionStatus());
            }

// preview.js
            function requestLivePreview() {
              if (previewRequestInFlight && previewRequestInFlightContextGeneration === previewRequestContextGeneration) return previewRequestInFlight;
              const requestContextGeneration = previewRequestContextGeneration;
              const request = requestJson('/api/preview')
                .finally(() => {
                  if (previewRequestInFlight === request) {
                    previewRequestInFlight = null;
                    previewRequestInFlightContextGeneration = null;
                  }
                });
              previewRequestInFlight = request;
              previewRequestInFlightContextGeneration = requestContextGeneration;
              return previewRequestInFlight;
            }

            function invalidatePreviewContext() {
              previewRequestGeneration++;
              previewRequestContextGeneration++;
              state.preview = null;
              previewRequestInFlight = null;
              previewRequestInFlightContextGeneration = null;
            }

            function previewScreenshotUrl(previewId) {
              return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full';
            }

            function screenImageUrl(screen) {
              if (addItemsFlow) return addItemsFlow.screenshotUrl;
              if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId);
              if (screen?.screenId) return '/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full';
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

            function latestPersistedScreen() {
              const screens = state.session?.screens || [];
              const persistedScreenIds = new Set(
                (state.session?.items || [])
                  .filter(item => item.delivery !== 'sent')
                  .map(item => item.screenId)
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
              zoomPercent.textContent = Math.round(previewZoom * 100) + '%';
              zoomOutButton.disabled = previewZoom <= PreviewZoomMin;
              zoomInButton.disabled = previewZoom >= PreviewZoomMax;
              if (frame) {
                frame.style.setProperty('--preview-zoom', String(previewZoom));
              }
            }

            function setPreviewZoom(nextZoom) {
              previewZoom = Math.round(clamp(nextZoom, PreviewZoomMin, PreviewZoomMax) * 10) / 10;
              applyPreviewZoom();
            }


            async function refreshPreview() {
              error.textContent = '';
              if (!state.session || addItemsFlow) return;
              const requestGeneration = ++previewRequestGeneration;
              try {
                const preview = await requestLivePreview();
                if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;
                state.preview = preview;
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

// annotations.js
            function boundsForTarget(target) {
              return target?.boundsInWindow || null;
            }

            function targetLabel(item) {
              if (String(item?.label || '').trim()) return item.label;
              const target = item?.target || {};
              if (target.type === 'semantics_node' || target.nodeUid) {
                return item.selectedNode ? componentLabel(item.selectedNode) : 'Component target';
              }
              if (target.type === 'visual_area' || target.boundsInWindow) return 'Custom area';
              return 'Unknown target';
            }

            function pendingTargetLabel(item) {
              return item.targetType === 'node' ? 'Component target' : 'Custom area';
            }

            function annotationTitle(item, index) {
              return item.label || pendingTargetLabel(item) + ' #' + (index + 1);
            }

            function annotationSeverity(item) {
              return item.severity || 'med';
            }

            function annotationStatus(item) {
              return String(item.status || 'open').replace('_', '-');
            }

            function severityColor(severity) {
              if (severity === 'high') return '#f26d6d';
              if (severity === 'low') return '#5bb4e8';
              return '#e6b45a';
            }

            function colorWithAlpha(color, alpha) {
              const match = String(color || '').match(/^#([0-9a-f]{6})$/i);
              if (!match) return color;
              const hex = match[1];
              const red = parseInt(hex.slice(0, 2), 16);
              const green = parseInt(hex.slice(2, 4), 16);
              const blue = parseInt(hex.slice(4, 6), 16);
              return 'rgba(' + red + ', ' + green + ', ' + blue + ', ' + alpha + ')';
            }

            function statusLabel(status) {
              if (status === 'in-progress') return 'In-progress';
              if (status === 'resolved') return 'Resolved';
              return 'Open';
            }

            function statusClass(status) {
              if (status === 'in-progress') return 'st-in-progress';
              if (status === 'resolved') return 'st-resolved';
              return 'st-open';
            }

            function toolbarAnnotations() {
              if (addItemsFlow) return pendingFeedbackItems;
              return (state.session?.items || []).filter(item => item.delivery !== 'sent');
            }

            function hasWrittenAnnotationComment(item) {
              return Boolean(String(item?.comment || '').trim());
            }

            function currentPromptAnnotations() {
              if (!state.session) return [];
              return toolbarAnnotations().filter(hasWrittenAnnotationComment);
            }


            function naturalPointFromEvent(event, image) {
              const rect = image.getBoundingClientRect();
              if (!image.naturalWidth || !image.naturalHeight || !rect.width || !rect.height) {
                throw new Error('Snapshot image dimensions are not available.');
              }
              return {
                x: clamp((event.clientX - rect.left) * image.naturalWidth / rect.width, 0, image.naturalWidth),
                y: clamp((event.clientY - rect.top) * image.naturalHeight / rect.height, 0, image.naturalHeight)
              };
            }

            function normalizeBounds(a, b) {
              return {
                left: Math.min(a.x, b.x),
                top: Math.min(a.y, b.y),
                right: Math.max(a.x, b.x),
                bottom: Math.max(a.y, b.y)
              };
            }

            function containsPoint(bounds, point) {
              return Boolean(bounds) &&
                point.x >= bounds.left &&
                point.x <= bounds.right &&
                point.y >= bounds.top &&
                point.y <= bounds.bottom;
            }

            function area(bounds) {
              if (!bounds) return Number.MAX_VALUE;
              return Math.max(0, bounds.right - bounds.left) * Math.max(0, bounds.bottom - bounds.top);
            }

            function componentLabel(node) {
              const clean = value => {
                const raw = String(value || '').trim();
                if (!raw || raw.startsWith('compose:')) return '';
                return raw;
              };
              const firstMeaningful = values => (values || []).map(clean).find(Boolean) || '';
              const role = clean(node.role);
              const label = firstMeaningful([...(node.text || []), node.editableText, ...(node.contentDescription || [])]);
              const testTag = clean(node.testTag);
              if (role && label) return humanize(role) + ' "' + label + '"';
              if (label) return label;
              if (role && testTag) return humanize(role) + ' #' + testTag;
              if (testTag) return '#' + testTag;
              if (role) return humanize(role);
              const bounds = node.boundsInWindow;
              if (bounds) return 'Component ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top);
              return 'Component';
            }

            function formatBounds(bounds) {
              return Math.round(bounds.left) + ',' + Math.round(bounds.top) + ' - ' + Math.round(bounds.right) + ',' + Math.round(bounds.bottom);
            }

            function focusedPendingSelectionSummary() {
              if (focusedPendingItemIndex != null) {
                return pendingFeedbackItems[focusedPendingItemIndex] || null;
              }
              return null;
            }

            function updateComposerState() {
              const hasPromptAnnotations = currentPromptAnnotations().length > 0;
              const promptDisabled = !hasPromptAnnotations || promptActionInFlight;
              copyPromptButton.disabled = promptDisabled;
              sendAgentButton.disabled = promptDisabled;
              cancelAddFlowButton.disabled = !addItemsFlow;
              addItemButton.hidden = true;
              addItemButton.disabled = true;
              annotateToolButton.disabled = addItemsFlowStarting;
              selectToolButton.setAttribute('aria-pressed', String(toolMode === 'select'));
              annotateToolButton.setAttribute('aria-pressed', String(toolMode === 'annotate'));
              toolStatus.innerHTML = toolMode === 'annotate'
                ? '<span class="ts-hint"><span class="ts-dot"></span><span>Click a widget — or drag to draw a region</span></span>'
                : '<span class="ts-meta">' +
                    '<span class="ts-dot-label"><span class="ts-dot"></span>' + toolbarOpenCount() + ' open</span>' +
                    '<span class="ts-dot-label"><span class="ts-dot resolved"></span>' + toolbarResolvedCount() + ' resolved</span>' +
                  '</span>';
              const item = focusedPendingSelectionSummary();
              selectionSummary.textContent = currentSelection
                ? currentSelection.label + ' - ' + formatBounds(currentSelection.bounds)
                : (item
                  ? 'Focused #' + (focusedPendingItemIndex + 1) + ' - ' + formatBounds(item.bounds)
                  : (toolMode === 'annotate' ? 'Click a component or drag a region to create an annotation.' : 'No annotation selected.'));
            }


            function nodesForHitTest(screen, nodesSelector) {
              const nodes = [];
              const seenNodeIds = new Set();
              const appendNodes = candidates => {
                (candidates || []).forEach(node => {
                  if (!node || !node.boundsInWindow) return;
                  if (node.uid) {
                    if (seenNodeIds.has(node.uid)) return;
                    seenNodeIds.add(node.uid);
                  }
                  nodes.push(node);
                });
              };
              const roots = screen?.roots || [];
              roots.forEach(root => appendNodes(nodesSelector(root)));
              return nodes;
            }

            function smallestContainingNode(nodes, point) {
              return nodes
                .map((node, order) => ({ node: node, order: order }))
                .filter(candidate => containsPoint(candidate.node.boundsInWindow, point))
                .sort((a, b) => area(a.node.boundsInWindow) - area(b.node.boundsInWindow) || a.order - b.order)[0]?.node;
            }

            function hitTestNodes(screen) {
              return nodesForHitTest(screen, root => [
                ...(root?.mergedNodes || []),
                ...(root?.unmergedNodes || [])
              ]);
            }

            function selectionForNode(node) {
              return {
                targetType: 'node',
                nodeUid: node.uid,
                bounds: node.boundsInWindow,
                label: componentLabel(node)
              };
            }

            function nodeSelectionAtPoint(event, image) {
              const point = naturalPointFromEvent(event, image);
              const screen = latestScreen();
              const node = smallestContainingNode(hitTestNodes(screen), point);
              return node ? selectionForNode(node) : null;
            }

            function selectNodeAtPoint(event, image) {
              const selection = nodeSelectionAtPoint(event, image);
              if (!selection) {
                showError(new Error('No component found at that point. Drag to select a custom area.'));
                return;
              }
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function previewNodeAtPoint(event, image) {
              const selection = nodeSelectionAtPoint(event, image);
              const nextId = selection?.nodeUid || null;
              const currentId = hoveredAnnotationTarget?.nodeUid || null;
              if (nextId === currentId) return;
              hoveredAnnotationTarget = selection;
              renderSelectionOverlay();
            }

            function confirmHoveredAnnotationTarget(event, image) {
              if (hoveredAnnotationTarget) {
                const point = naturalPointFromEvent(event, image);
                if (containsPoint(hoveredAnnotationTarget.bounds, point)) {
                  const selection = hoveredAnnotationTarget;
                  hoveredAnnotationTarget = null;
                  createAnnotationFromSelection(selection);
                  error.textContent = '';
                  return;
                }
              }
              selectNodeAtPoint(event, image);
            }

            function finishAreaSelection(bounds) {
              const selection = {
                targetType: 'area',
                bounds: bounds,
                label: 'Custom area ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top)
              };
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function clearDragState() {
              if (!dragStart && !dragPreview) return;
              dragStart = null;
              dragPreview = null;
              renderSelectionOverlay();
            }

            function clearHoverPreview() {
              if (!hoveredAnnotationTarget) return;
              hoveredAnnotationTarget = null;
              renderSelectionOverlay();
            }

            function resetAnnotationComposerState(clearFlow = true) {
              if (clearFlow) addItemsFlow = null;
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              toolMode = 'select';
              comment.value = '';
              clearDragState();
            }

            function releaseSnapshotPointerCapture(image, event) {
              try {
                if (image.hasPointerCapture?.(event.pointerId)) {
                  image.releasePointerCapture?.(event.pointerId);
                }
              } catch (_) {
              }
            }

            function clearSelection() {
              currentSelection = null;
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              hoveredAnnotationTarget = null;
              comment.value = '';
              clearDragState();
              renderSelectionOverlay();
              renderInspectorRegion();
              updateComposerState();
            }


            async function startAddItemsFlow() {
              if (addItemsFlowStarting) return;
              error.textContent = '';
              addItemsFlowStarting = true;
              updateComposerState();
              stopLivePreviewPolling();
              try {
                const addFlowContextGeneration = previewRequestContextGeneration;
                previewRequestGeneration++;
                let preview = state.preview;
                if (previewRequestInFlight || !preview) {
                  preview = await requestLivePreview();
                  if (addFlowContextGeneration !== previewRequestContextGeneration) return;
                  state.preview = preview;
                }
                if (!state.preview) {
                  return;
                }
                addItemsFlow = {
                  previewId: state.preview.previewId,
                  screen: state.preview.screen,
                  screenshotUrl: previewScreenshotUrl(state.preview.previewId)
                };
                toolMode = 'annotate';
                focusedPendingItemIndex = null;
                currentSelection = null;
                render();
              } finally {
                addItemsFlowStarting = false;
                updateComposerState();
                if (!addItemsFlow) startLivePreviewPolling();
              }
            }

            function flushFocusedPendingComment() {
              if (focusedPendingItemIndex == null) return;
              const item = pendingFeedbackItems[focusedPendingItemIndex];
              if (!item) return;
              item.comment = comment.value;
            }

            function createAnnotationFromSelection(selection) {
              if (!addItemsFlow) throw new Error('Switch to Annotate before selecting feedback.');
              if (!selection) throw new Error('Select a component or area first.');
              flushFocusedPendingComment();
              const annotation = {
                annotationId: 'local-' + annotationSequence++,
                targetType: selection.targetType,
                nodeUid: selection.nodeUid,
                bounds: selection.bounds,
                label: selection.label,
                severity: 'med',
                status: 'open',
                comment: ''
              };
              pendingFeedbackItems.push(annotation);
              currentSelection = null;
              hoveredAnnotationTarget = null;
              focusedPendingItemIndex = pendingFeedbackItems.length - 1;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              toolMode = 'annotate';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
            }

            function deletePendingFeedbackItem(index) {
              pendingFeedbackItems.splice(index, 1);
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
            }

            function focusPendingFeedbackItem(index) {
              flushFocusedPendingComment();
              focusedPendingItemIndex = index;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              toolMode = 'select';
              comment.value = pendingFeedbackItems[index]?.comment || '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function focusSavedEvidenceItem(itemId) {
              focusedSavedItemId = itemId;
              focusedSavedSessionId = state.session?.sessionId || null;
              focusedPendingItemIndex = null;
              currentSelection = null;
              toolMode = 'select';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function normalizedPersistedStatus(status) {
              return String(status || 'open').replace('-', '_');
            }

            function applySavedSessionUpdate(updatedSession, sessionId) {
              const updatedSessionId = updatedSession?.sessionId || sessionId;
              if (state.session?.sessionId === updatedSessionId) {
                state.session = updatedSession;
                renderCurrentSessionList();
                updateComposerState();
              } else {
                refreshSessions().catch(showError);
              }
              return updatedSession;
            }

            async function persistSavedEvidenceItem(item, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!item?.itemId) return state.session;
              const updatedSession = await requestJson('/api/items/' + encodeURIComponent(item.itemId), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  sessionId: sessionId,
                  label: String(item.label || '').trim() || null,
                  severity: annotationSeverity(item),
                  comment: String(item.comment || ''),
                  status: normalizedPersistedStatus(annotationStatus(item))
                })
              });
              return applySavedSessionUpdate(updatedSession, sessionId);
            }

            async function deleteSavedEvidenceItem(itemId, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!itemId) return;
              const sessionQuery = sessionId
                ? '?sessionId=' + encodeURIComponent(sessionId)
                : '';
              const updatedSession = await requestJson('/api/items/' + encodeURIComponent(itemId) + sessionQuery, { method: 'DELETE' });
              if (state.session?.sessionId === (updatedSession?.sessionId || sessionId)) {
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
                state.session = updatedSession;
                renderPreviewOnly();
                renderInspectorRegion();
                renderCurrentSessionList();
              } else {
                refreshSessions().catch(showError);
              }
            }

            function updateSelectedAnnotationComment() {
              const item = selectedAnnotation();
              if (!item) return;
              item.comment = comment.value;
              renderPendingItems();
              updateComposerState();
            }

            function pendingPayloadItems(options = {}) {
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              const items = onlyWrittenComments ? pendingFeedbackItems.filter(hasWrittenAnnotationComment) : pendingFeedbackItems;
              return items.map(item => ({
                targetType: item.targetType,
                bounds: item.bounds,
                nodeUid: item.nodeUid,
                label: String(item.label || '').trim() || null,
                severity: annotationSeverity(item),
                status: normalizedPersistedStatus(annotationStatus(item)),
                comment: allowFallbackComments
                  ? (String(item.comment || '').trim() || item.label || pendingTargetLabel(item))
                  : (allowBlankComments ? String(item.comment || '') : item.comment)
              }));
            }

            async function persistPendingFeedbackItems(options = {}) {
              if (!addItemsFlow) return;
              if (!pendingFeedbackItems.length) throw new Error('Add at least one pending feedback item.');
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && pendingFeedbackItems.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
              if (onlyWrittenComments && !pendingFeedbackItems.some(hasWrittenAnnotationComment)) throw new Error('Add a comment to at least one annotation before sending.');
              state.session = await requestJson('/api/items/batch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  previewId: addItemsFlow.previewId,
                  screen: addItemsFlow.screen,
                  items: pendingPayloadItems({ allowFallbackComments: allowFallbackComments, onlyWrittenComments: onlyWrittenComments, allowBlankComments: allowBlankComments })
                })
              });
              resetAnnotationComposerState();
              state.preview = null;
              return state.session;
            }

            async function flushPendingAnnotationsBeforeSessionChange() {
              if (!addItemsFlow || !pendingFeedbackItems.length) return;
              await persistPendingFeedbackItems({ allowBlankComments: true });
            }

            async function savePendingFeedbackItems() {
              await persistPendingFeedbackItems();
              await refresh();
              startLivePreviewPolling();
            }

            function cancelAddItemsFlow() {
              resetAnnotationComposerState();
              render();
              startLivePreviewPolling();
            }

            function enterSelectMode() {
              toolMode = 'select';
              currentSelection = null;
              clearHoverPreview();
              clearDragState();
              renderCurrentSessionList();
              renderPreviewOnly();
              renderInspectorRegion();
            }

// history.js
            function sessionOrdinalLookup(sessions) {
              const ordinalBySessionId = new Map();
              stableHistorySessions(sessions)
                .forEach((session, index) => {
                  ordinalBySessionId.set(session.sessionId, index + 1);
                });
              return ordinalBySessionId;
            }

            function stableHistorySessions(sessions) {
              return [...(sessions || [])].sort((left, right) =>
                (left.createdAtEpochMillis || 0) - (right.createdAtEpochMillis || 0) ||
                String(left.sessionId || '').localeCompare(String(right.sessionId || ''))
              );
            }

            function formatSessionLabel(session, ordinal) {
              const safeOrdinal = Math.max(1, Number(ordinal || 1));
              return 'Session ' + safeOrdinal;
            }

            function formatSessionSummary(session) {
              return 'You · ' + formatHistoryDate(session.updatedAtEpochMillis);
            }

            function pendingHistoryItemsForSession(session) {
              if (!addItemsFlow || state.session?.sessionId !== session?.sessionId) return [];
              return pendingFeedbackItems;
            }

            function historyOpenCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              return (session.unresolvedItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length;
            }

            function historyDoneCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              const persistedDone = Math.max(0, (session.itemsCount || 0) - (session.unresolvedItemsCount || 0));
              return persistedDone + pending.filter(item => annotationStatus(item) === 'resolved').length;
            }

            function historyPointsCount(session) {
              return (session.itemsCount || 0) + pendingHistoryItemsForSession(session).length;
            }

            function renderHistoryStrip(session) {
              const open = historyOpenCount(session);
              const done = historyDoneCount(session);
              const total = historyPointsCount(session);
              if (!total) return '<span class="hi-strip-cell empty"></span>';
              return [
                ...Array.from({ length: open }, () => '<span class="hi-strip-cell"></span>'),
                ...Array.from({ length: done }, () => '<span class="hi-strip-cell done"></span>')
              ].join('');
            }

            function firstLine(value) {
              return text(value).split(/\r?\n/)[0] || '(No comment)';
            }

            function formatItemLabel(item, index) {
              const number = item.sequenceNumber ? item.sequenceNumber : index + 1;
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function formatSavedEvidenceItemLabel(item, index) {
              return '#' + (index + 1) + ' ' + firstLine(item.comment || '(No comment)');
            }

            function findScreen(screenId) {
              return (state.session?.screens || []).find(screen => screen.screenId === screenId) || null;
            }


            function selectedHistorySummary() {
              const sessionId = state.session?.sessionId;
              if (!sessionId) return null;
              return (state.sessionSummaries || []).find(session => session.sessionId === sessionId) || null;
            }

            function toolbarAnnotationCounts() {
              if (addItemsFlow) {
                const pending = pendingFeedbackItems;
                return {
                  open: pending.filter(item => annotationStatus(item) !== 'resolved').length,
                  resolved: pending.filter(item => annotationStatus(item) === 'resolved').length
                };
              }
              const annotations = toolbarAnnotations();
              return {
                open: annotations.filter(item => annotationStatus(item) !== 'resolved').length,
                resolved: annotations.filter(item => annotationStatus(item) === 'resolved').length
              };
            }

            function toolbarOpenCount() {
              return toolbarAnnotationCounts().open;
            }

            function toolbarResolvedCount() {
              return toolbarAnnotationCounts().resolved;
            }

            function selectedAnnotation() {
              if (focusedPendingItemIndex == null) return null;
              return pendingFeedbackItems[focusedPendingItemIndex] || null;
            }

            function sourceHintLabel(item) {
              return (item.sourceCandidates || []).length ? 'Source hint available' : 'No source hint';
            }

            function formatBatchLabel(batch) {
              return 'Batch #' + (batch.sequenceNumber || '-');
            }

            function batchItems(batch) {
              const itemsById = new Map((state.session?.items || []).map(item => [item.itemId, item]));
              return (batch.itemIds || []).map(itemId => itemsById.get(itemId) || { itemId: itemId, missing: true });
            }

            function formatBatchItemSummary(item) {
              if (item.missing) return 'Missing feedback item metadata.';
              return firstLine(item.comment || '(No comment)');
            }

            function formatBatchDetails(batch, items) {
              const count = (batch.itemIds || []).length;
              const itemCount = count + ' item' + (count === 1 ? '' : 's');
              return formatTime(batch.createdAtEpochMillis) + ' | ' + itemCount + ' | ' + (items.map(formatBatchItemSummary).join('; ') || 'No feedback items recorded.');
            }


            function hasActiveHistorySessionForAnnotating() {
              return Boolean(
                state.session &&
                state.session.status !== 'ready_for_agent' &&
                state.session.status !== 'closed' &&
                (state.sessionSummaries || []).some(session =>
                  session.sessionId === state.session.sessionId &&
                  session.status !== 'ready_for_agent' &&
                  session.status !== 'closed'
                )
              );
            }

            async function ensureSessionForAnnotating() {
              if (hasActiveHistorySessionForAnnotating()) return;
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refreshSessions();
            }

            async function enterAnnotateMode() {
              await ensureSessionForAnnotating();
              toolMode = 'annotate';
              renderCurrentSessionList();
              if (!addItemsFlow) {
                await startAddItemsFlow();
              } else {
                renderPreviewOnly();
                renderInspectorRegion();
              }
            }

            async function enterNewHistoryAnnotateMode() {
              if (newHistoryAnnotateModeStarting) return;
              newHistoryAnnotateModeStarting = true;
              toolMode = 'annotate';
              renderCurrentSessionList();
              try {
                await newSession();
                scrollActiveHistoryItemIntoView();
                await enterAnnotateMode();
                scrollActiveHistoryItemIntoView();
              } finally {
                newHistoryAnnotateModeStarting = false;
                renderCurrentSessionList();
              }
            }

            function scrollActiveHistoryItemIntoView() {
              const activeRow = sessions.querySelector('.session-row.is-active');
              activeRow?.scrollIntoView({ block: 'nearest' });
            }

            function historyStartAnnotatingItemHtml() {
              if (newHistoryAnnotateModeStarting) return '';
              return '<button type="button" class="history-item history-add-row" data-start-new-history-annotating aria-label="Start annotating">' +
                '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 5v14"/><path d="M5 12h14"/></svg>' +
              '</button>';
            }


            function renderSessionsListFromPayload(sessionSummaries) {
              state.sessionSummaries = sessionSummaries;
              const activeId = state.session?.sessionId;
              const activeSummaries = sessionSummaries.filter(session => session.status !== 'ready_for_agent' && session.status !== 'closed');
              const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);
              const renderedActiveSummaries = stableHistorySessions(activeSummaries);
              sessionCount.textContent = String(activeSummaries.length);
              const renderedSessions = renderedActiveSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const points = historyPointsCount(session);
                const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
                return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<span class="hi-head">' +
                    '<span class="hi-title">' + escapeHtml(label) + '</span>' +
                    '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
                  '</span>' +
                  '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                  '<span class="hi-stats">' +
                    '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' +
                    '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'done', 'done')) + '</span>' +
                    '<span class="hi-pip points">' + escapeHtml(countLabel(points, 'pt', 'pts')) + '</span>' +
                  '</span>' +
                  '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
                '</div>';
              }).join('');
              sessions.innerHTML = renderedSessions
                ? renderedSessions + historyStartAnnotatingItemHtml()
                : historyStartAnnotatingItemHtml() + emptySessionsHtml();
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  openSession(row.dataset.sessionId).catch(showError);
                });
                row.addEventListener('keydown', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openSession(row.dataset.sessionId).catch(showError);
                  }
                });
              });
              document.querySelectorAll('[data-delete-session-id]').forEach(button => {
                button.addEventListener('click', event => {
                  event.stopPropagation();
                  deleteHistorySession(button.dataset.deleteSessionId).catch(showError);
                });
              });
              document.querySelectorAll('[data-start-new-history-annotating]').forEach(button => {
                button.addEventListener('click', () => enterNewHistoryAnnotateMode().catch(showError));
              });
            }

            function renderSessionsList() {
              const activeId = state.session?.sessionId;
              document.querySelectorAll('.session-row').forEach(row => {
                row.classList.toggle('is-active', row.dataset.sessionId === activeId);
              });
            }

            function renderCurrentSessionList() {
              renderSessionsListFromPayload(state.sessionSummaries || []);
            }

            function sentHistorySummaries() {
              return (state.sessionSummaries || []).filter(session => session.status === 'ready_for_agent' || (session.sentBatchesCount || 0) > 0);
            }

            function renderSentHistory() {
              const session = state.session;
              const allItems = session?.items || [];
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
              const batchIds = new Set(handoffBatches.map(batch => batch.batchId));
              const batchedItemIds = new Set(handoffBatches.flatMap(batch => batch.itemIds || []));
              const batchRows = handoffBatches.map(batch => {
                const items = batchItems(batch);
                return '<div class="row">' +
                  '<strong>' + escapeHtml(formatBatchLabel(batch)) + '</strong>' +
                  '<span>' + escapeHtml(formatBatchDetails(batch, items)) + '</span>' +
                '</div>';
              });
              const unbatchedRows = sentItems
                .filter(item => !item.handoffBatchId || !batchIds.has(item.handoffBatchId) || !batchedItemIds.has(item.itemId))
                .map(item => {
                  const label = item.handoffBatchId ? 'Missing batch metadata' : 'Unbatched sent item';
                  const detail = item.handoffBatchId ? 'No batch metadata' : 'Sent outside a batch';
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(label) + '</strong>' +
                    '<span>' + escapeHtml(firstLine(item.comment || '(No comment)')) + ' · ' + escapeHtml(detail) + '</span>' +
                  '</div>';
                });
              const renderedSessionIds = new Set(session && (handoffBatches.length || sentItems.length) ? [session.sessionId] : []);
              const summaryRows = sentHistorySummaries()
                .filter(summary => !renderedSessionIds.has(summary.sessionId))
                .map((summary, index) => {
                  const sentCount = summary.itemsCount || summary.sentItemsCount || summary.sentBatchesCount || 0;
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(formatSessionLabel(summary, index)) + '</strong>' +
                    '<span>' + escapeHtml(countLabel(sentCount, 'annotation', 'annotations')) + ' sent · ' + escapeHtml(formatSessionSummary(summary)) + '</span>' +
                  '</div>';
                });
              const rows = batchRows.concat(unbatchedRows, summaryRows);
              clearSentHistoryButton.hidden = rows.length === 0;
              sentHistory.innerHTML = rows.join('') || '<div class="row"><span>No sent handoff history.</span></div>';
            }


            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              renderSessionsListFromPayload(response.sessions || []);
              return response.sessions || [];
            }

            async function refresh() {
              error.textContent = '';
              state.session = await requestJson('/api/session');
              await refreshSessions();
              await refreshDevices();
              await refreshConnection();
              render();
            }

            async function openSession(sessionId) {
              error.textContent = '';
              stopLivePreviewPolling();
              await flushPendingAnnotationsBeforeSessionChange();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              await refresh();
              if (!latestPersistedScreen() && shouldAutoFetchPreview()) {
                await refreshPreview();
              }
              startLivePreviewPolling();
            }

            async function newSession() {
              error.textContent = '';
              await flushPendingAnnotationsBeforeSessionChange();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refresh();
              startLivePreviewPolling();
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              resetAnnotationComposerState();
              invalidatePreviewContext();
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: state.session.sessionId })
              });
              state.session = null;
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function deleteHistorySession(sessionId) {
              error.textContent = '';
              if (!sessionId) return;
              const isDisplayedSession = () => state.session?.sessionId === sessionId;
              if (isDisplayedSession()) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
              }
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              if (isDisplayedSession()) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
                state.session = null;
              }
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function clearDraft() {
              error.textContent = '';
              if (!window.confirm('Discard all unsent draft feedback items?')) return;
              await requestJson('/api/items/draft', { method: 'DELETE' });
              clearSelection();
              await refresh();
            }

            async function clearSentHistory() {
              error.textContent = '';
              const ids = new Set(sentHistorySummaries().map(session => session.sessionId));
              if (state.session && ((state.session.handoffBatches || []).length || (state.session.items || []).some(item => item.delivery === 'sent'))) {
                ids.add(state.session.sessionId);
              }
              if (ids.size === 0) return;
              if (!window.confirm('Clear all sent history? Sent handoff records will be removed from this console.')) return;
              for (const sessionId of ids) {
                await requestJson('/api/session/close', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ sessionId: sessionId })
                });
              }
              if (state.session && ids.has(state.session.sessionId)) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
                state.session = null;
              }
              await refreshSessions();
              render();
              await refreshDevices();
            }

// prompt.js
            function promptUnavailableMessage() {
              if (!state.session) return 'Select a history item before copying or sending annotations.';
              const annotations = toolbarAnnotations();
              if (!annotations.length) return 'The selected history item has no annotations to send.';
              if (!annotations.some(hasWrittenAnnotationComment)) return 'Add a comment to at least one annotation before copying or sending it.';
              return 'No completed annotations are ready to send.';
            }

            function ensurePromptAnnotationsAvailable() {
              const annotations = currentPromptAnnotations();
              if (annotations.length) return annotations;
              const message = promptUnavailableMessage();
              error.textContent = message;
              throw new Error(message);
            }

            function promptItemTitle(item, index) {
              return item.label || (item.comment ? firstLine(item.comment) : targetLabel(item)) || ('Annotation ' + (index + 1));
            }

            function promptItemBounds(item) {
              return item.bounds || boundsForTarget(item.target);
            }

            function promptListValue(values) {
              const joined = (values || [])
                .map(value => String(value || '').trim())
                .filter(Boolean)
                .join(' | ');
              return joined || null;
            }

            function promptScalarValue(value) {
              const scalar = String(value || '').trim();
              return scalar || null;
            }

            function promptNodeEvidence(node) {
              if (!node) return [];
              const lines = [];
              const textValue = promptListValue(node.text);
              const editableText = promptScalarValue(node.editableText);
              const contentDescription = promptListValue(node.contentDescription);
              const testTag = promptScalarValue(node.testTag);
              const role = promptScalarValue(node.role);
              if (textValue) lines.push('   UI Text: ' + textValue);
              if (editableText) lines.push('   Editable Text: ' + editableText);
              if (contentDescription) lines.push('   Content Description: ' + contentDescription);
              if (testTag) lines.push('   Test Tag: ' + testTag);
              if (role) lines.push('   Role: ' + role);
              return lines;
            }

            function promptTargetEvidence(item) {
              const evidence = item.targetEvidence || {};
              const lines = [];
              const hint = evidence.identityHint || {};
              const identity = [hint.composableNameHint, hint.variantHint]
                .map(promptScalarValue)
                .filter(Boolean)
                .join(':');
              if (identity) lines.push('   Identity: ' + identity);
              if (hint.stableLabel) lines.push('   Stable Label: ' + hint.stableLabel);
              if (evidence.occurrence) {
                lines.push('   Occurrence: ' + evidence.occurrence.selectedOrdinal + '/' + evidence.occurrence.count);
              }
              const interpretation = evidence.sourceInterpretation || {};
              if (interpretation.caution) lines.push('   Source Caution: ' + interpretation.caution);
              if ((evidence.warnings || []).length) lines.push('   Warnings: ' + evidence.warnings.join(', '));
              return lines;
            }

            const FIXTHIS_IOSA_THRESHOLD = 0.25;
            const FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP = 24;

            function rectArea(r) {
              if (!r) return 0;
              return Math.max(r.right - r.left, 0) * Math.max(r.bottom - r.top, 0);
            }
            function rectIntersection(a, b) {
              const left = Math.max(a.left, b.left);
              const top = Math.max(a.top, b.top);
              const right = Math.min(a.right, b.right);
              const bottom = Math.min(a.bottom, b.bottom);
              return Math.max(right - left, 0) * Math.max(bottom - top, 0);
            }
            function rectCenterDistance(a, b) {
              const ax = (a.left + a.right) / 2;
              const ay = (a.top + a.bottom) / 2;
              const bx = (b.left + b.right) / 2;
              const by = (b.top + b.bottom) / 2;
              return Math.hypot(ax - bx, ay - by);
            }
            function compactOverlapItems(item) {
              const t = item.target || {};
              const bounds = (t.boundsInWindow) || (item.bounds) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
              const isAreaSelection = (t.type === 'visual_area');
              const hasWeakLabels = !item.selectedNode || !(item.selectedNode.text && item.selectedNode.text.length);
              return { item: item, bounds: bounds, isAreaSelection: isAreaSelection, hasWeakLabels: hasWeakLabels };
            }
            function compactOverlapsBetween(a, b, density) {
              const inter = rectIntersection(a.bounds, b.bounds);
              if (a.isAreaSelection && b.isAreaSelection) return inter > 0;
              const smaller = Math.min(rectArea(a.bounds), rectArea(b.bounds));
              if (smaller > 0 && (inter / smaller) >= FIXTHIS_IOSA_THRESHOLD) return true;
              if (a.hasWeakLabels || b.hasWeakLabels) {
                const threshold = FIXTHIS_WEAK_LABEL_CENTER_DISTANCE_DP * (density || 1);
                if (rectCenterDistance(a.bounds, b.bounds) <= threshold) return true;
              }
              return false;
            }
            function compactDetectOverlap(items, density) {
              if (!items || items.length < 2) return (items || []).map(it => [it]);
              const wrapped = items.map(compactOverlapItems);
              const parent = wrapped.map((_, i) => i);
              function find(i) { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
              function union(a, b) { const ra = find(a); const rb = find(b); if (ra !== rb) parent[rb] = ra; }
              for (let i = 0; i < wrapped.length; i++) {
                for (let j = i + 1; j < wrapped.length; j++) {
                  if (compactOverlapsBetween(wrapped[i], wrapped[j], density)) union(i, j);
                }
              }
              const groups = {};
              wrapped.forEach((w, i) => {
                const r = find(i);
                if (!groups[r]) groups[r] = [];
                groups[r].push(w.item);
              });
              return Object.keys(groups).map(k => groups[k]);
            }

            const FIXTHIS_REASON_TOKEN_MAP = {
              'selected text': 'text',
              'selected contentDescription': 'contentDescription',
              'selected testTag': 'tag',
              'selected testTag convention composable': 'compTag',
              'selected role': 'role',
              'nearby text': 'nearbyText',
              'nearby contentDescription': 'nearbyContentDescription',
              'nearby testTag': 'nearbyTag',
              'nearby role': 'nearbyRole',
              'activity': 'activity',
              'selected stringResource': 'stringRes',
              'arbitrary literal': 'literal',
              'legacy fallback': 'legacy'
            };

            function compactReasonTokens(reasons) {
              const seen = new Set();
              const tokens = [];
              (reasons || []).forEach(reason => {
                const token = FIXTHIS_REASON_TOKEN_MAP[String(reason || '').trim()];
                if (token && !seen.has(token)) {
                  seen.add(token);
                  tokens.push(token);
                }
              });
              return tokens.join('+');
            }

            // Task 5.2: formatBox — emits (L,T)-(R,B) [W×H] using integer dimensions
            function formatBox(bounds) {
              const l = Math.floor(bounds.left);
              const t = Math.floor(bounds.top);
              const r = Math.floor(bounds.right);
              const b = Math.floor(bounds.bottom);
              const w = Math.max(Math.floor(bounds.right - bounds.left), 0);
              const h = Math.max(Math.floor(bounds.bottom - bounds.top), 0);
              return '(' + l + ',' + t + ')-(' + r + ',' + b + ') [' + w + '×' + h + ']';
            }

            // Task 5.2: compactUiLine — replaces compactTargetLine; emits ui: role tag=tag  box=(L,T)-(R,B) [W×H]
            function compactUiLine(item, isOverlap, instanceLabel, dupRefMarker) {
              const node = item.selectedNode || {};
              const role = promptScalarValue(node.role) || (item.target && item.target.type === 'visual_area' ? 'Area' : 'Node');
              const tag = promptScalarValue(node.testTag) || '(none)';
              const bounds = (item.target && item.target.boundsInWindow) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
              let base = '  ui: ' + role + ' tag=' + tag + '  box=' + formatBox(bounds);
              if (instanceLabel) {
                base += '  instance ' + instanceLabel.index + '/' + instanceLabel.total;
              }
              if (dupRefMarker != null) {
                return base + '; targetRisk=duplicate-of-marker-' + dupRefMarker;
              }
              if (isOverlap) {
                return base + '; targetRisk=overlap';
              }
              return base;
            }

            // Task 5.4: compactCandidatesBlock — replaces compactSourceLine; returns array of lines
            function compactCandidatesBlock(item) {
              const lines = [];
              lines.push('  candidates:');
              const candidates = item.sourceCandidates || [];
              if (candidates.length === 0) {
                lines.push('    ~ unknown');
                return lines;
              }
              const rank1 = candidates[0];
              const rank2 = candidates[1];
              const computedMargin = (rank1 && rank2 && (rank1.score - rank2.score) > 0)
                ? (rank1.score - rank2.score)
                : null;
              const maxCandidates = 3;
              candidates.slice(0, maxCandidates).forEach(function(candidate, idx) {
                const rank = idx + 1;
                const file = promptScalarValue(candidate.file);
                const location = file ? (file + (candidate.line ? ':' + candidate.line : '')) : 'unknown';
                const confidence = String(candidate.confidence || 'unknown').toLowerCase();
                let line = '    ~ ' + location + '  conf=' + confidence;
                if (rank === 1) {
                  const effectiveMargin = (candidate.scoreMargin != null) ? candidate.scoreMargin : computedMargin;
                  if (effectiveMargin != null) {
                    line += '  margin=' + effectiveMargin.toFixed(2);
                  }
                  // Build matched=[...] using FIXTHIS_REASON_TOKEN_MAP, deduped, max 4
                  const reasons = candidate.matchReasons || [];
                  const seen = new Set();
                  const tokens = [];
                  reasons.forEach(function(reason) {
                    const token = FIXTHIS_REASON_TOKEN_MAP[String(reason || '').trim()];
                    if (token && !seen.has(token)) {
                      seen.add(token);
                      tokens.push(token);
                    }
                  });
                  const capped = tokens.slice(0, 4);
                  if (capped.length > 0) {
                    line += '  matched=[' + capped.join(', ') + ']';
                  }
                }
                lines.push(line);
              });
              // Caution note from rank-1 candidate (Task 5.4, parity Phase 2.5)
              const caution = rank1 && rank1.caution ? String(rank1.caution).trim() : null;
              if (caution) {
                lines.push('  note: ' + caution);
              }
              return lines;
            }

            // Task 5.5: computeInstanceLabels — mirrors Kotlin InstanceGroupingHelper.compute
            function computeInstanceLabels(items) {
              const labels = new Map(); // itemId -> {index, total}
              const leaderItemIds = new Set();

              // Filter to eligible items (have selectedNode and sourceCandidates)
              const eligible = (items || []).filter(function(item) {
                return item.selectedNode != null && (item.sourceCandidates || []).length > 0;
              });

              // Group by (fileLine + testTag)
              const groups = new Map(); // key -> [item]
              eligible.forEach(function(item) {
                const cand = item.sourceCandidates[0];
                const file = promptScalarValue(cand.file);
                const fileLine = file ? (file + (cand.line ? ':' + cand.line : '')) : '';
                const testTag = item.selectedNode.testTag || '';
                const key = fileLine + '|' + testTag;
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(item);
              });

              groups.forEach(function(group) {
                if (group.length < 2) return;
                // Sort by path.join('/')
                const ordered = group.slice().sort(function(a, b) {
                  const pa = (a.selectedNode.path || []).join('/');
                  const pb = (b.selectedNode.path || []).join('/');
                  if (pa < pb) return -1;
                  if (pa > pb) return 1;
                  return 0;
                });
                ordered.forEach(function(item, idx) {
                  labels.set(item.itemId, { index: idx + 1, total: ordered.length });
                });
                leaderItemIds.add(ordered[0].itemId);
              });

              return { labels: labels, leaderItemIds: leaderItemIds };
            }

            // Task 5.7: computeDuplicateMarkers — mirrors Kotlin DuplicateMarkerDetector.detect
            // detectorItems: Array of { itemId, markerNumber, fileLine, testTag, pathLeaves, bounds }
            function computeDuplicateMarkers(detectorItems) {
              const result = new Map(); // itemId -> canonical markerNumber
              const keyGroups = new Map(); // key string -> [detectorItem]
              (detectorItems || []).forEach(function(di) {
                const bounds = di.bounds || { left: 0, top: 0, right: 0, bottom: 0 };
                const key = (di.fileLine || '') + '|' + (di.testTag || '') + '|' + (di.pathLeaves || []).join('/') + '|' + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom;
                if (!keyGroups.has(key)) keyGroups.set(key, []);
                keyGroups.get(key).push(di);
              });
              keyGroups.forEach(function(group) {
                if (group.length < 2) return;
                const canonical = group[0].markerNumber;
                group.slice(1).forEach(function(dup) {
                  result.set(dup.itemId, canonical);
                });
              });
              return result;
            }

            // Task 5.5/5.6/5.7: compactItemLines — updated to use new helpers
            function compactItemLines(item, marker, isOverlap, instanceLabel, dupRefMarker, isInstanceLeader, groupSize) {
              const lines = [];
              const rawTitle = (String(item.comment || '').split('\n')[0] || '').trim() || promptItemTitle(item, marker - 1);
              // Task 5.3: severity prefix
              const title = (item.severity === 'high') ? '[!] ' + rawTitle : rawTitle;
              lines.push(String(marker) + '. [marker ' + marker + '] ' + title);
              lines.push(compactUiLine(item, isOverlap, instanceLabel, dupRefMarker));
              const candidatesBlock = compactCandidatesBlock(item);
              candidatesBlock.forEach(function(l) { lines.push(l); });
              // Task 5.6: collision note on group leader (suppressed for overlap groups)
              if (isInstanceLeader && groupSize >= 2 && !isOverlap) {
                lines.push('  note: ' + groupSize + ' markers map to same call site — likely list-rendered; disambiguate by instance index');
              }
              return lines;
            }

            function compactScreenHeader(screenId, screen) {
              const shortId = String(screenId).slice(0, 8);
              const lines = ['Screen ' + shortId + ': ' + (screen && screen.displayName ? screen.displayName : 'Screen')];
              const screenshotPath = screen && screen.screenshot && (screen.screenshot.desktopFullPath || screen.screenshot.fullPath);
              if (screenshotPath) lines.push('screenshot: ' + screenshotPath);
              const w = screen && screen.screenshot && screen.screenshot.width;
              const h = screen && screen.screenshot && screen.screenshot.height;
              if (w && h) lines.push('viewport: ' + w + '×' + h);
              const activityName = screen && screen.activityName;
              const displayName = screen && screen.displayName;
              if (activityName && activityName !== displayName) lines.push('activity: ' + activityName);
              return lines;
            }

            function currentAnnotationsPromptCompact(annotations) {
              const list = annotations || currentPromptAnnotations();
              if (!state.session || list.length === 0) {
                throw new Error('Select a history item with annotations before sending it to an agent.');
              }
              const lines = [
                'FixThis feedback handoff',
                '',
                'Rule: source hints are candidates; verify screenshot, target, and code before editing.',
                '',
                'Package: ' + (state.session.packageName || 'unknown'),
                'Annotations: ' + list.length,
                ''
              ];
              const screensById = {};
              (state.session.screens || []).forEach(function(screen) {
                if (screen && screen.screenId) screensById[screen.screenId] = screen;
              });
              const grouped = {};
              list.forEach(function(item) {
                const id = item.screenId || 'unknown-screen';
                if (!grouped[id]) grouped[id] = [];
                grouped[id].push(item);
              });
              let counter = 0;
              Object.keys(grouped).forEach(function(screenId) {
                lines.push.apply(lines, compactScreenHeader(screenId, screensById[screenId]));
                lines.push('');
                const itemsForScreen = grouped[screenId];

                // Task 5.5: compute instance grouping for this screen
                const instanceGrouping = computeInstanceLabels(itemsForScreen);

                const overlapGroups = compactDetectOverlap(itemsForScreen, 1);

                // Task 5.7: pre-pass to assign marker numbers, then build duplicate map
                let preCounter = counter;
                const markerByItemId = new Map();
                overlapGroups.forEach(function(group) {
                  group.forEach(function(item) {
                    preCounter += 1;
                    markerByItemId.set(item.itemId, preCounter);
                  });
                });
                const detectorItems = list.filter(function(item) {
                  return markerByItemId.has(item.itemId);
                }).map(function(item) {
                  const cand = (item.sourceCandidates || [])[0];
                  const file = cand && promptScalarValue(cand.file);
                  const fileLine = file ? (file + (cand.line ? ':' + cand.line : '')) : null;
                  const testTag = item.selectedNode && item.selectedNode.testTag;
                  const pathLeaves = (item.selectedNode && item.selectedNode.path) || [];
                  const bounds = (item.target && item.target.boundsInWindow) || promptItemBounds(item) || { left: 0, top: 0, right: 0, bottom: 0 };
                  return {
                    itemId: item.itemId,
                    markerNumber: markerByItemId.get(item.itemId),
                    fileLine: fileLine,
                    testTag: testTag,
                    pathLeaves: pathLeaves,
                    bounds: bounds
                  };
                });
                const duplicateMap = computeDuplicateMarkers(detectorItems);

                let overlapGroupCounter = 0;
                overlapGroups.forEach(function(group) {
                  const isOverlap = group.length > 1;
                  if (isOverlap) {
                    overlapGroupCounter += 1;
                    lines.push('Overlap group ' + overlapGroupCounter + ' (resolve one marker at a time):');
                  }
                  group.forEach(function(item) {
                    counter += 1;
                    const dupRefMarker = duplicateMap.has(item.itemId) ? duplicateMap.get(item.itemId) : null;
                    // Task 5.7: suppress instance label for duplicates
                    const instanceLabel = (dupRefMarker == null) ? (instanceGrouping.labels.get(item.itemId) || null) : null;
                    const isInstanceLeader = instanceGrouping.leaderItemIds.has(item.itemId);
                    const groupSize = instanceLabel ? instanceLabel.total : 0;
                    lines.push.apply(lines, compactItemLines(item, counter, isOverlap, instanceLabel, dupRefMarker, isInstanceLeader, groupSize));
                    lines.push('');
                  });
                });
              });
              return lines.join('\n').replace(/\n+$/, '\n');
            }

            function currentAnnotationsPrompt(annotations) {
              return currentAnnotationsPromptCompact(annotations);
            }


            async function sendAgentPrompt() {
              error.textContent = '';
              if (promptActionInFlight) return;
              ensurePromptAnnotationsAvailable();
              promptActionInFlight = true;
              updateComposerState();
              let sent = false;
              try {
                if (addItemsFlow) {
                  await persistPendingFeedbackItems({ onlyWrittenComments: true });
                }
                const prompt = currentAnnotationsPrompt();
                state.session = await requestJson('/api/agent-handoffs', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ prompt: prompt })
                });
                comment.value = '';
                resetAnnotationComposerState();
                invalidatePreviewContext();
                await refreshSessions();
                render();
                startLivePreviewPolling();
                sent = true;
              } finally {
                promptActionInFlight = false;
                updateComposerState();
                if (sent) {
                  showSuccess('Sent to agent ✓', 3000);
                }
              }
            }

            async function copyPrompt() {
              error.textContent = '';
              if (promptActionInFlight) return;
              ensurePromptAnnotationsAvailable();
              promptActionInFlight = true;
              updateComposerState();
              const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
              const originalLabel = labelSpan ? labelSpan.textContent : null;
              let copied = false;
              try {
                if (addItemsFlow) {
                  await persistPendingFeedbackItems({ onlyWrittenComments: true });
                }
                await copyTextToClipboard(currentAnnotationsPrompt());
                copied = true;
              } finally {
                promptActionInFlight = false;
                updateComposerState();
                if (copied && labelSpan) {
                  labelSpan.textContent = 'Copied ✓';
                  setTimeout(() => {
                    if (labelSpan.textContent === 'Copied ✓') {
                      labelSpan.textContent = originalLabel;
                    }
                  }, 1500);
                }
              }
            }

// rendering.js
            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false, isFocused = false, annotationIndex = null, extraClass = '', color = null, selectHandler = focusPendingFeedbackItem) {
              if (!bounds) return;
              const left = bounds.left * 100 / image.naturalWidth;
              const top = bounds.top * 100 / image.naturalHeight;
              const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
              const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
              const box = document.createElement('div');
              box.className = 'selection-box' + (isDragPreview ? ' drag-preview' : '') + (isFocused ? ' focused' : '') + (annotationIndex == null ? '' : ' annotation-pin') + (extraClass ? ' ' + extraClass : '');
              box.style.left = left + '%';
              box.style.top = top + '%';
              box.style.width = width + '%';
              box.style.height = height + '%';
              if (color) {
                box.style.setProperty('--selection-color', color);
                box.style.setProperty('--selection-fill', colorWithAlpha(color, .12));
                box.style.setProperty('--selection-fill-strong', colorWithAlpha(color, .22));
                box.style.setProperty('--selection-halo', colorWithAlpha(color, .24));
              }
              if (annotationIndex != null) {
                box.setAttribute('role', 'button');
                box.setAttribute('aria-label', 'Select annotation ' + (annotationIndex + 1));
                box.tabIndex = 0;
                box.addEventListener('click', event => {
                  event.stopPropagation();
                  selectHandler(annotationIndex);
                });
                box.addEventListener('keydown', event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectHandler(annotationIndex);
                });
              }
              overlay.appendChild(box);

              if (!labelText) return;
              const label = document.createElement('div');
              label.className = 'selection-label' + (isFocused ? ' focused' : '');
              label.style.left = left + '%';
              label.style.top = top + '%';
              if (color) {
                label.style.setProperty('--selection-color', color);
                label.style.setProperty('--selection-halo', colorWithAlpha(color, .24));
              }
              label.textContent = labelText;
              overlay.appendChild(label);
            }

            function renderNumberedFeedbackOverlay(overlay, image) {
              pendingFeedbackItems.forEach((item, index) => {
                renderOverlayBox(overlay, image, item.bounds, String(index + 1), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)));
              });
            }

            function renderSelectionOverlay() {
              const overlay = document.getElementById('selectionOverlay');
              const image = document.getElementById('snapshotImage');
              if (!overlay) {
                updateComposerState();
                return;
              }
              overlay.innerHTML = '';
              if (!image) {
                updateComposerState();
                return;
              }
              if (!image.naturalWidth || !image.naturalHeight) {
                image.addEventListener('load', renderSelectionOverlay, { once: true });
                updateComposerState();
                return;
              }

              renderNumberedFeedbackOverlay(overlay, image);
              if (!addItemsFlow && focusedSavedItemId) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === focusedSavedItemId);
                if (focusedItem) {
                  const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === focusedItem.screenId);
                  renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
                }
              }
              if (currentSelection) {
                renderOverlayBox(overlay, image, currentSelection.bounds, currentSelection.label);
              }
              if (addItemsFlow && toolMode === 'annotate' && hoveredAnnotationTarget && !dragPreview) {
                renderOverlayBox(overlay, image, hoveredAnnotationTarget.bounds, null, false, false, null, 'hover-preview');
              }
              if (dragPreview) {
                renderOverlayBox(overlay, image, dragPreview, null, true);
              }
              updateComposerState();
            }


            function emptySessionsHtml() {
              return '<div class="empty-state"><div class="empty-title">No saved sessions.</div></div>';
            }

            function startAnnotatingButtonHtml() {
              if (toolMode === 'annotate') return '';
              return '<button type="button" class="primary" data-start-annotating>Start annotating</button>';
            }

            function renderPendingItems() {
              if (focusedPendingItemIndex != null && selectedAnnotation()) {
                renderAnnotationDetail(selectedAnnotation(), focusedPendingItemIndex);
                return;
              }
              pendingItems.innerHTML = pendingFeedbackItems.length
                ? '<div class="ann-list">' + pendingFeedbackItems.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const status = annotationStatus(item);
                  const color = severityColor(annotationSeverity(item));
                  return '<button type="button" class="ann-row pending-item-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '" style="--annotation-color:' + color + '" data-focus-pending="' + index + '">' +
                    '<span class="ann-row-num" style="background:' + severityColor(annotationSeverity(item)) + '">' + (index + 1) + '</span>' +
                    '<span class="ann-row-body">' +
                      '<span class="ann-row-title">' + escapeHtml(annotationTitle(item, index)) + '</span>' +
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                    '</span>' +
                    '<span class="ann-row-status ' + statusClass(status) + '">' + escapeHtml(statusLabel(status)) + '</span>' +
                  '</button>';
                }).join('') + '</div>'
                : '<div class="empty-state"><div class="empty-title">No annotations yet.</div><div class="empty-body">Switch to <b>Annotate</b>, then click or drag on the preview.</div>' + startAnnotatingButtonHtml() + '</div>';
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              bindStartAnnotatingButtons(pendingItems);
            }

            function bindStartAnnotatingButtons(container) {
              container.querySelectorAll('[data-start-annotating]').forEach(button => {
                button.addEventListener('click', () => enterAnnotateMode().catch(showError));
              });
            }

            function renderAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              pendingItems.innerHTML =
                '<div class="annotation-detail">' +
                  '<button type="button" class="annotation-back" data-back-annotations>← All annotations</button>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationLabelInput">Label</label>' +
                    '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(annotationTitle(item, index)) + '">' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Severity</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                      ['high', 'med', 'low'].map(value =>
                        '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                          ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                          (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                          escapeHtml(value === 'med' ? 'Med' : value) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationCommentInput">Comment</label>' +
                    '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtmlValue(item.comment) + '</textarea>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Status</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Status">' +
                      ['open', 'in-progress', 'resolved'].map(value =>
                        '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                          ' aria-pressed="' + (status === value ? 'true' : 'false') + '">' +
                          escapeHtml(statusLabel(value)) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = document.getElementById('annotationLabelInput');
              const commentInput = document.getElementById('annotationCommentInput');
              labelInput.addEventListener('input', event => {
                item.label = event.target.value;
                updateComposerState();
                renderPreviewOnly();
              });
              commentInput.addEventListener('input', event => {
                item.comment = event.target.value;
                updateComposerState();
              });
              pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  item.severity = button.dataset.setSeverity;
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  item.status = button.dataset.setStatus;
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                });
              });
              pendingItems.querySelectorAll('[data-back-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  focusedPendingItemIndex = null;
                  renderPreviewOnly();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelector('[data-delete-current]').addEventListener('click', () => {
                deletePendingFeedbackItem(index);
              });
              commentInput.focus();
            }

            function savedEvidenceGroups() {
              const groups = new Map();
              savedEvidenceItems().forEach(item => {
                const key = item.screenId;
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(item);
              });
              return Array.from(groups.entries()).map(entry => ({ screenId: entry[0], items: entry[1] }));
            }

            function savedEvidenceItems() {
              return (state.session?.items || []).filter(item => item.delivery !== 'sent');
            }

            function selectedSavedAnnotation() {
              if (!focusedSavedItemId) return null;
              return savedEvidenceItems().find(item => item.itemId === focusedSavedItemId) || null;
            }

            function renderSavedEvidenceOverlay(overlay, image, items) {
              const allSavedItems = savedEvidenceItems();
              items.forEach((item, index) => {
                const savedIndex = Math.max(0, allSavedItems.findIndex(savedItem => savedItem.itemId === item.itemId));
                renderOverlayBox(
                  overlay,
                  image,
                  boundsForTarget(item.target),
                  String(savedIndex + 1),
                  false,
                  item.itemId === focusedSavedItemId,
                  savedIndex,
                  '',
                  severityColor(annotationSeverity(item)),
                  () => focusSavedEvidenceItem(item.itemId)
                );
              });
            }

            function renderSavedEvidenceGroups() {
              const items = savedEvidenceItems();
              const selected = selectedSavedAnnotation();
              if (selected) {
                renderSavedAnnotationDetail(selected, items.findIndex(item => item.itemId === selected.itemId));
                return;
              }
              draftItems.innerHTML = items.length
                ? '<div class="ann-list">' + items.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const status = annotationStatus(item);
                  const color = severityColor(annotationSeverity(item));
                  return '<button type="button" class="ann-row saved-item-row ' + (item.itemId === focusedSavedItemId ? 'active' : '') + '" style="--annotation-color:' + color + '" data-focus-saved="' + escapeHtml(item.itemId) + '">' +
                    '<span class="ann-row-num" style="background:' + color + '">' + (index + 1) + '</span>' +
                    '<span class="ann-row-body">' +
                      '<span class="ann-row-title">' + escapeHtml(targetLabel(item)) + '</span>' +
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                    '</span>' +
                    '<span class="ann-row-status ' + statusClass(status) + '">' + escapeHtml(statusLabel(status)) + '</span>' +
                  '</button>';
                }).join('') + '</div>'
                : '<div class="empty-state"><div class="empty-title">No saved annotations yet.</div><div class="empty-body">Use <b>Annotate</b> to freeze the preview and add comments.</div>' + startAnnotatingButtonHtml() + '</div>';
              draftItems.querySelectorAll('[data-focus-saved]').forEach(button => {
                button.addEventListener('click', () => focusSavedEvidenceItem(button.dataset.focusSaved));
              });
              bindStartAnnotatingButtons(draftItems);
            }

            function renderSavedAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              const editSessionId = focusedSavedSessionId || state.session?.sessionId || null;
              draftItems.innerHTML =
                '<div class="annotation-detail">' +
                  '<button type="button" class="annotation-back" data-back-saved-annotations>← All annotations</button>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationLabelInput">Label</label>' +
                    '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(targetLabel(item)) + '">' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Severity</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                      ['high', 'med', 'low'].map(value =>
                        '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                          ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                          (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                          escapeHtml(value === 'med' ? 'Med' : value) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationCommentInput">Comment</label>' +
                    '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtmlValue(item.comment) + '</textarea>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Status</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Status">' +
                      ['open', 'in-progress', 'resolved'].map(value =>
                        '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                          ' aria-pressed="' + (status === value ? 'true' : 'false') + '">' +
                          escapeHtml(statusLabel(value)) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-saved-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = draftItems.querySelector('#annotationLabelInput');
              const commentInput = draftItems.querySelector('#annotationCommentInput');
              labelInput.addEventListener('input', event => {
                item.label = event.target.value;
                updateComposerState();
                renderPreviewOnly();
              });
              labelInput.addEventListener('change', () => {
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('input', event => {
                item.comment = event.target.value;
                updateComposerState();
              });
              commentInput.addEventListener('change', () => {
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('blur', () => {
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              draftItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  item.severity = button.dataset.setSeverity;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => renderInspectorRegion())
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  item.status = button.dataset.setStatus;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => {
                      renderPreviewOnly();
                      renderInspectorRegion();
                    })
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-back-saved-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => {
                      focusedSavedItemId = null;
                      focusedSavedSessionId = null;
                      renderPreviewOnly();
                      renderInspectorRegion();
                    })
                    .catch(showError);
                });
              });
              draftItems.querySelector('[data-delete-current]').addEventListener('click', () => {
                deleteSavedEvidenceItem(item.itemId, editSessionId).catch(showError);
              });
              commentInput.focus();
            }


            function renderSessionRegions() {
              const session = state.session;
              renderSessionsList();
              renderSentHistory();
            }

            function renderComposerInspector() {
              const item = selectedAnnotation();
              inspectorTitle.textContent = item ? 'Annotation' : 'Annotations';
              inspectorCount.textContent = String(pendingFeedbackItems.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = false;
              draftItems.hidden = true;
              inspectorFooter.hidden = true;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = true;
              renderPendingItems();
            }

            function renderSavedAnnotationsInspector() {
              const items = savedEvidenceItems();
              inspectorTitle.textContent = 'Annotations';
              inspectorCount.textContent = String(items.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = true;
              draftItems.hidden = false;
              inspectorFooter.hidden = false;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = items.length === 0;
              renderSavedEvidenceGroups();
            }

            function renderInspectorRegion() {
              if (addItemsFlow) {
                renderComposerInspector();
              } else {
                renderSavedAnnotationsInspector();
              }
              updateComposerState();
            }

            function ensurePreviewFrame() {
              let frame = document.getElementById('snapshotFrame');
              if (frame) return frame;
              snapshot.innerHTML =
	                '<div id="annotateHintSlot" class="annotate-hint-slot" aria-live="polite"></div>' +
	                '<div id="snapshotFrame" class="snapshot-frame">' +
	                  '<img id="snapshotImage" alt="FixThis preview" aria-label="FixThis preview">' +
	                  '<div id="selectionOverlay" class="selection-overlay"></div>' +
	                '</div>';
              attachSnapshotHandlers();
              applyPreviewZoom();
              return document.getElementById('snapshotFrame');
            }

            function renderPreviewRegion() {
              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              const mode = addItemsFlow ? 'frozen' : (state.preview ? 'live' : (screen ? 'frozen' : 'idle'));
              snapshot.dataset.toolMode = toolMode;
              if (!hasScreenshot) {
                const emptyMessage = screen
                  ? 'No screenshot artifact for this preview.'
                  : (state.session
                    ? 'Waiting for first capture from device…'
                    : 'Connect a device to get started.');
                snapshot.innerHTML = '<div class="empty-stage">' + emptyMessage + '</div>';
                updateComposerState();
                return;
              }
              const frame = ensurePreviewFrame();
              frame.dataset.mode = mode;
              const image = document.getElementById('snapshotImage');
              const src = screenImageUrl(screen);
              if (image.getAttribute('src') !== src) {
                image.setAttribute('src', src);
              }
              const hintSlot = document.getElementById('annotateHintSlot');
              let hint = document.getElementById('annotateHint');
              if (toolMode === 'annotate') {
                if (!hint) {
                  hint = document.createElement('div');
                  hint.id = 'annotateHint';
                  hint.className = 'annotate-hint';
                  hintSlot.appendChild(hint);
                }
                hint.textContent = 'Annotate mode';
              } else if (hint) {
                hint.remove();
              }
              renderSelectionOverlay();
            }

            function renderPreviewOnly() {
              renderPreviewRegion();
              renderSelectionOverlay();
              updateComposerState();
            }

            function render() {
              renderSessionRegions();
              renderPreviewRegion();
              renderInspectorRegion();
              renderConnection(state.connection.current);
              updateComposerState();
            }

            function attachSnapshotHandlers() {
              const image = document.getElementById('snapshotImage');
              if (!image) return;
              image.draggable = false;
              image.addEventListener('dragstart', event => event.preventDefault());
              image.addEventListener('click', event => {
                try {
                  if (addItemsFlowStarting) {
                    event.preventDefault();
                    return;
                  }
                  if (suppressNextClick) {
                    suppressNextClick = false;
                    return;
                  }
                  if (toolMode === 'select' && addItemsFlow) {
                    clearSelection();
                    return;
                  }
                  if (!addItemsFlow) {
                    const point = naturalPointFromEvent(event, image);
                    navigate('tap', { x: point.x, y: point.y }).catch(showError);
                    return;
                  }
                  if (toolMode === 'annotate' && !dragStart) {
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointerdown', event => {
                if (addItemsFlowStarting) {
                  event.preventDefault();
                  return;
                }
                if (!addItemsFlow || toolMode !== 'annotate') return;
                try {
                  image.setPointerCapture?.(event.pointerId);
                  dragStart = naturalPointFromEvent(event, image);
                  dragPreview = normalizeBounds(dragStart, dragStart);
                  renderSelectionOverlay();
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointermove', event => {
                if (!addItemsFlow || toolMode !== 'annotate') return;
                try {
                  if (!dragStart) {
                    previewNodeAtPoint(event, image);
                    return;
                  }
                  dragPreview = normalizeBounds(dragStart, naturalPointFromEvent(event, image));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                if (!addItemsFlow || toolMode !== 'annotate' || !dragStart) return;
                try {
                  const end = naturalPointFromEvent(event, image);
                  const bounds = normalizeBounds(dragStart, end);
                  clearDragState();
                  releaseSnapshotPointerCapture(image, event);
                  if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
                    suppressNextClick = true;
                    finishAreaSelection(bounds);
	                  } else {
	                    suppressNextClick = true;
	                    renderSelectionOverlay();
	                    confirmHoveredAnnotationTarget(event, image);
	                  }
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointercancel', clearDragState);
              image.addEventListener('pointercancel', clearHoverPreview);
              image.addEventListener('lostpointercapture', clearDragState);
              image.addEventListener('lostpointercapture', clearHoverPreview);
              image.addEventListener('pointerleave', clearHoverPreview);
            }

// shortcuts.js
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
                if (addItemsFlow) {
                  cancelAddItemsFlow();
                } else {
                  clearSelection();
                }
                return;
              }
              if (event.key.toLowerCase() === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
                event.preventDefault();
                enterAnnotateMode().catch(showError);
                return;
              }
            }

// main.js
            selectToolButton.addEventListener('click', enterSelectMode);
            annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));
            zoomOutButton.addEventListener('click', () => setPreviewZoom(previewZoom - PreviewZoomStep));
            zoomInButton.addEventListener('click', () => setPreviewZoom(previewZoom + PreviewZoomStep));
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
            clearSentHistoryButton.addEventListener('click', event => {
              event.preventDefault();
              event.stopPropagation();
              clearSentHistory().catch(showError);
            });
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
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              if (!document.hidden && state.selectedDeviceSerial) sendBridgeHeartbeat().catch(handleHeartbeatError);
              startLivePreviewPolling();
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
              clearSuccessStatus();
              error.textContent = friendlyErrorMessage(cause && cause.message ? cause.message : cause);
            }

            initializePreviewIntervalSelect();
            applyPreviewZoom();
            refresh()
              .then(() => {
                if (shouldAutoFetchPreview()) return refreshPreview();
                return null;
              })
              .then(() => {
                startHeartbeatPolling();
                startLivePreviewPolling();
              })
              .catch(showError);
