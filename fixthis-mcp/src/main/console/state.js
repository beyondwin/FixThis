// @requires connectionFsm.js, previewFsm.js, pollingFsm.js, toolModeFsm.js, connectionUseCases.js, previewUseCases.js, pollingUseCases.js, toolModeUseCases.js
            const DefaultLivePreviewIntervalMs = 1000;
            const MinLivePreviewIntervalMs = 1000;
            const PreviewIntervalStorageKey = 'fixthis.previewIntervalMs.v2';
            const state = {
              session: null,
              preview: null,
              sessionSummaries: [],
              selectedDeviceSerial: null,
              devices: [],
              connection: null, // projected from connectionUseCases below
              previewFsm: null, // projected from previewUseCases below
              pollingFsm: null, // projected from pollingUseCases below
            };
            // Console FSM boot. createConsoleApp() wires the four sub-FSMs
            // (connection, preview, polling, tool-mode) and routes each
            // FSM's onChange into the corresponding legacy state.* slot so
            // non-FSM read sites (rendering, devices, preview, annotations)
            // keep observing changes. See consoleApp.js for the factory.
            //
            // Connection FSM: state.connection projection — all WRITES are
            // migrated to connectionUseCases dispatches (see
            // connectionUseCases.js / connection.js / sessions-polling.js).
            // Preview FSM: state.previewFsm projection — legacy
            // state.preview (current screenshot object) remains as-is for
            // existing renderers; FSM.current overlaps for race-fence
            // purposes only.
            // Polling FSM: state.pollingFsm projection — owns sessions-poll
            // lifecycle, mutation lock counters, sessions/session etags,
            // failure counter, and prompt-action-in-flight flag. Top-level
            // helpers below (startSessionsPolling / stopSessionsPolling /
            // pollSessionsTick / withMutationLock) delegate into
            // pollingUseCases (preserving the Kotlin grep contract
            // documented in ConsoleFeedbackItemRoutesTest).
            // Tool-mode FSM: no state.* projection; callers go through
            // toolModeUseCases.getState() directly. Owns toolMode,
            // annotationSequence, hoveredAnnotationTarget, dragStart/
            // dragPreview, suppressNextClick, addItemsFlowStarting,
            // newHistoryAnnotateModeStarting, historyDrawerOpen,
            // focusedSavedItemId, focusedSavedSessionId, focusedSavedScreenId.
            const consoleApp = createConsoleApp({ state });
            const connectionUseCases = consoleApp.connection;
            const previewUseCases = consoleApp.preview;
            const pollingUseCases = consoleApp.polling;
            const toolModeUseCases = consoleApp.toolMode;
            state.connection = { ...connectionUseCases.getState() };
            state.previewFsm = { ...previewUseCases.getState() };
            state.pollingFsm = { ...pollingUseCases.getState() };
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
            // heartbeatTimer / heartbeatPolling now live at connection.js
            // module scope (only used by sendBridgeHeartbeat /
            // scheduleNextHeartbeat / startHeartbeatPolling /
            // stopHeartbeatPolling). See connection.js.
            let addItemsFlow = null;
            let pendingFeedbackItems = [];
            let focusedPendingItemIndex = null;
            let currentSelection = null;
            // Tool-mode-owned state (toolMode, annotationSequence,
            // hoveredAnnotationTarget, dragStart, dragPreview,
            // suppressNextClick, addItemsFlowStarting,
            // newHistoryAnnotateModeStarting, historyDrawerOpen,
            // focusedSavedItemId, focusedSavedSessionId,
            // focusedSavedScreenId) now lives in
            // toolModeUseCases (see toolModeFsm.js / toolModeUseCases.js).
            // Polling-owned state (sessionsPollingTimer, lastSessionsEtag,
            // lastSessionEtag, pendingMutationCount, sessionMutationGeneration,
            // consecutivePollFailures, promptActionInFlight) now lives in
            // pollingUseCases (see pollingFsm.js / pollingUseCases.js).
            // MaxConsecutivePollFailures is declared in pollingFsm.js.
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
              if (!draftWorkspace?.workspaceId) return;
              if (!(draftWorkspace.items || []).length) {
                deleteCurrentDraftWorkspaceStorage();
                return;
              }
              const storage = createBrowserDraftPorts().storage;
              storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
            }

            function deleteCurrentDraftWorkspaceStorage() {
              if (!draftWorkspace?.workspaceId) return;
              const sessionId = draftWorkspace.context?.sessionId || state.session?.sessionId;
              if (!sessionId) return;
              const storage = createBrowserDraftPorts().storage;
              storage.deleteWorkspace(sessionId, draftWorkspace.workspaceId);
            }

            function createBrowserDraftPorts() {
              return createFakeDraftPorts({
                ids: {
                  nextWorkspaceId: () => 'workspace-' + Date.now() + '-' + Math.random().toString(36).slice(2),
                  nextDraftItemId: () => 'draft-' + toolModeUseCases.nextAnnotationSeq(),
                },
                clock: { now: () => Date.now() },
                preview: {
                  capture: () => previewUseCases.request(),
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
              // Delegates to the polling FSM; MUTATION_GENERATION_BUMP
              // increments only the generation counter (callers that need
              // pendingMutationCount accounting use withMutationLock).
              pollingUseCases.dispatch({ type: 'MUTATION_GENERATION_BUMP' });
              return pollingUseCases.getState().mutationGeneration;
            }

            async function withMutationLock(fn) {
              // Delegates to pollingUseCases.withMutationLock for the
              // pendingMutationCount accounting. The recovery hook
              // (restart polling if the connection FSM reports
              // sessionsPollingPaused once the queue drains) stays here
              // because it crosses the polling/connection FSM boundary;
              // Task 6 will move it into a coordinator.
              let succeeded = false;
              try {
                const result = await pollingUseCases.withMutationLock(fn);
                succeeded = true;
                return result;
              } finally {
                const drained = pollingUseCases.getState().pendingMutationCount === 0;
                if (succeeded && drained && state.connection?.sessionsPollingPaused) {
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

            function resetStatusSurface() {
              error.textContent = '';
              error.hidden = true;
              error.className = 'global-status';
              error.setAttribute('role', 'status');
              error.setAttribute('aria-live', 'polite');
            }

            // statusClearTimeout is encapsulated in an IIFE closure so it
            // does not appear as a module-level let in state.js (Task 6
            // closure-scoping pattern).
            const { clearStatusTimer, showStatus, showSuccess, showWarning, clearSuccessStatus } = (() => {
              let statusClearTimeout = null;

              function clearStatusTimer() {
                if (statusClearTimeout) {
                  clearTimeout(statusClearTimeout);
                  statusClearTimeout = null;
                }
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

              return { clearStatusTimer, showStatus, showSuccess, showWarning, clearSuccessStatus };
            })();
