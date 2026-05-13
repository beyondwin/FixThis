            const DefaultLivePreviewIntervalMs = 1000;
            const MinLivePreviewIntervalMs = 1000;
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
                launchInFlight: false,
                availability: null,
                interactionBlockedReason: null,
                previousBlockedReason: null,
                sessionsPollingPaused: false
              }
            };
            const blockedReasonDebouncer = createBlockedReasonDebouncer({ delayMs: 300 });
            const unresponsiveTracker = createUnresponsiveTracker({ threshold: 3 });
            const sessions = document.getElementById('sessions');
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
            const savedSectionHeader = document.getElementById('savedSectionHeader');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const deviceControl = document.getElementById('deviceControl');
            const deviceName = document.getElementById('deviceName');
            const deviceConnectionState = document.getElementById('deviceConnectionState');
            const historyToggleButton = document.getElementById('historyToggleButton');
            const historyDrawerScrim = document.getElementById('historyDrawerScrim');
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const selectionSummary = document.getElementById('selectionSummary');
            const clearSelectionButton = document.getElementById('clearSelectionButton');
            const addItemButton = document.getElementById('addItemButton');
            const copyPromptButton = document.getElementById('copyPromptButton');
            const sendAgentButton = document.getElementById('sendAgentButton');
            const promptReadiness = document.getElementById('promptReadiness');
            const cancelAddFlowButton = document.getElementById('cancelAddFlowButton');
            const clearDraftButton = document.getElementById('clearDraftButton');
            const selectToolButton = document.getElementById('selectToolButton');
            const annotateToolButton = document.getElementById('annotateToolButton');
            const toolStatus = document.getElementById('toolStatus');
            const zoomOutButton = document.getElementById('zoomOutButton');
            const zoomInButton = document.getElementById('zoomInButton');
            const zoomPercent = document.getElementById('zoomPercent');
            const previewStaleBadge = document.getElementById('previewStaleBadge');
            const workflowProgress = document.getElementById('workflowProgress');
            let livePreviewTimer = null;
            let heartbeatTimer = null;
            let heartbeatPolling = false;
            let previewRequestGeneration = 0;
            let previewRequestContextGeneration = 0;
            let sessionMutationGeneration = 0;
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
            let historyDrawerOpen = false;
            let sessionsPollingTimer = null;
            let lastSessionsEtag = null;
            let lastSessionEtag = null;
            let pendingMutationCount = 0;
            let consecutivePollFailures = 0;
            const MaxConsecutivePollFailures = 5;
            // ALH-2: Undo/redo history singleton for pending feedback items.
            let undoRedoHistory = createHistory();
            let draftWorkspace = createEmptyDraftWorkspace();
            let draftCommandQueue = null;

            function currentDraftWorkspace() {
              return draftWorkspace;
            }

            function setDraftWorkspace(nextWorkspace) {
              draftWorkspace = nextWorkspace || createEmptyDraftWorkspace();
              syncDraftWorkspaceCompatibility();
              persistCurrentDraftWorkspaceIfNeeded();
            }

            function syncDraftWorkspaceCompatibility() {
              if (draftWorkspace.lifecycle === DraftLifecycle.EMPTY) {
                addItemsFlow = null;
                pendingFeedbackItems = [];
                focusedPendingItemIndex = null;
                currentSelection = null;
                undoRedoHistory = createHistory();
                return;
              }
              addItemsFlow = {
                context: draftWorkspace.context,
                previewId: draftWorkspace.context?.previewId || null,
                screen: draftWorkspace.screen,
                screenshotUrl: draftWorkspace.screenshotUrl,
                frozenAtEpochMillis: draftWorkspace.context?.frozenAtEpochMillis || null,
                activity: draftWorkspace.context?.activityName || null,
                activityDriftWarning: draftWorkspace.activityDriftWarning || null,
              };
              pendingFeedbackItems = draftWorkspace.items;
              focusedPendingItemIndex = draftWorkspace.focusedItemId
                ? draftWorkspace.items.findIndex((item) => item.draftItemId === draftWorkspace.focusedItemId)
                : null;
              if (focusedPendingItemIndex < 0) focusedPendingItemIndex = null;
              currentSelection = draftWorkspace.currentSelection;
              undoRedoHistory = draftWorkspace.history || createHistory(draftWorkspace.context);
            }

            function persistCurrentDraftWorkspaceIfNeeded() {
              if (!draftWorkspace?.workspaceId || !(draftWorkspace.items || []).length) return;
              const storage = createBrowserDraftPorts().storage;
              storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
            }

            function createBrowserDraftPorts() {
              return createFakeDraftPorts({
                ids: {
                  nextWorkspaceId: () => 'workspace-' + Date.now() + '-' + Math.random().toString(36).slice(2),
                  nextDraftItemId: () => 'draft-' + annotationSequence++,
                },
                clock: { now: () => Date.now() },
                preview: {
                  capture: requestLivePreview,
                  screenshotUrl: previewScreenshotUrl,
                },
                feedbackApi: createDraftApiAdapter({
                  fetchImpl: fetch.bind(window),
                  consoleToken: window.FixThisConsoleConfig?.consoleToken || null,
                }),
                storage: createDraftStorageAdapter(localStorage, {
                  nextWorkspaceId: () => 'workspace-' + Date.now() + '-' + Math.random().toString(36).slice(2),
                }),
                clipboard: { writeText: copyTextToClipboard },
                boundaryPrompt: {
                  choose: (workspace, boundaryAction) =>
                    promptPendingBoundaryChoice(boundaryAction?.kind || boundaryAction, draftWorkspaceItems(workspace).length),
                },
                refresh: { sessions: refreshSessions },
              });
            }

            function ensureDraftCommandQueue() {
              if (draftCommandQueue) return draftCommandQueue;
              draftCommandQueue = createDraftCommandQueue({
                getWorkspace: currentDraftWorkspace,
                setWorkspace: setDraftWorkspace,
                onStaleResponse: () => refreshSessions().catch(showError),
                onError: showError,
              });
              return draftCommandQueue;
            }

            function bumpSessionMutationGeneration() {
              sessionMutationGeneration += 1;
              return sessionMutationGeneration;
            }

            async function withMutationLock(fn) {
              pendingMutationCount++;
              let succeeded = false;
              try {
                const result = await fn();
                succeeded = true;
                return result;
              } finally {
                pendingMutationCount--;
                if (succeeded && pendingMutationCount === 0 && state.connection?.sessionsPollingPaused) {
                  startSessionsPolling();
                }
              }
            }

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
              return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
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

            let statusClearTimeout = null;

            function syncStatusVisibility() {
              error.hidden = !String(error.textContent || '').trim();
            }

            if (typeof MutationObserver !== 'undefined') {
              new MutationObserver(syncStatusVisibility).observe(error, {
                childList: true,
                characterData: true,
                subtree: true,
              });
            }
            syncStatusVisibility();

            function clearStatusTimer() {
              if (statusClearTimeout) {
                clearTimeout(statusClearTimeout);
                statusClearTimeout = null;
              }
            }

            function resetStatusSurface() {
              error.textContent = '';
              error.hidden = true;
              error.className = 'global-status';
              error.setAttribute('role', 'status');
              error.setAttribute('aria-live', 'polite');
            }

            function showStatus(message, { variant = 'info', durationMs = 0, assertive = false } = {}) {
              clearStatusTimer();
              error.textContent = message;
              error.hidden = !message;
              error.className = 'global-status status-' + variant;
              error.setAttribute('role', assertive ? 'alert' : 'status');
              error.setAttribute('aria-live', assertive ? 'assertive' : 'polite');
              if (durationMs > 0) {
                statusClearTimeout = setTimeout(() => {
                  resetStatusSurface();
                  statusClearTimeout = null;
                }, durationMs);
              }
            }

            function showSuccess(message, durationMs = 2000) {
              showStatus(message, { variant: 'success', durationMs });
            }

            function showWarning(message, durationMs = 0) {
              showStatus(message, { variant: 'warning', durationMs });
            }

            function clearSuccessStatus() {
              clearStatusTimer();
              resetStatusSurface();
            }
