// @requires notificationCenter.js, connectionFsm.js, previewFsm.js, pollingFsm.js, toolModeFsm.js, connectionUseCases.js, previewUseCases.js, pollingUseCases.js, toolModeUseCases.js, draftRuntimeState.js
            // Console teardown registry: each module that registers global
            // listeners (document/window) returns a `{ dispose }` slot from
            // its `init({...deps})` factory and pushes it here. main.js
            // exposes `window.__fixthisDispose()` so tests can confirm every
            // registered listener is removable. See history.js initHistory.
            const __fixthisDisposers = [];
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

	            function setConsoleSession(session) {
	              state['session'] = session;
	              return state['session'];
	            }

	            function setConsolePreview(preview) {
	              state['preview'] = preview;
	              return state['preview'];
	            }
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
            // toolMode.getState() directly. Owns toolMode,
            // annotationSequence, hoveredAnnotationTarget, dragStart/
            // dragPreview, suppressNextClick, draftFlowStarting,
            // newHistoryAnnotateModeStarting, historyDrawerOpen,
            // focusedSavedItemId, focusedSavedSessionId, focusedSavedScreenId.
            const consoleApp = createConsoleApp({ state });
            const connectionUseCases = consoleApp.connection;
            const previewUseCases = consoleApp.preview;
            const pollingUseCases = consoleApp.polling;
            const statusSurfaceRegistry = consoleApp.statusSurfaceRegistry;
            const notificationCenter = createNotificationCenter({ registry: statusSurfaceRegistry });
            const toolMode = consoleApp.toolMode;
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
            const editorBack = document.getElementById('editorBack');
            const draftItems = document.getElementById('draftItems');
            const savedSectionHeader = document.getElementById('savedSectionHeader');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const toastContainer = document.getElementById('toastContainer');
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
            const copyPromptButton = document.getElementById('copyPromptButton');
            const sendAgentButton = document.getElementById('sendAgentButton');
            const promptReadiness = document.getElementById('promptReadiness');
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
            // Tool-mode-owned state (toolMode, annotationSequence,
            // hoveredAnnotationTarget, dragStart, dragPreview,
            // suppressNextClick, draftFlowStarting,
            // newHistoryAnnotateModeStarting, historyDrawerOpen,
            // focusedSavedItemId, focusedSavedSessionId,
            // focusedSavedScreenId) now lives in
            // toolMode (see toolModeFsm.js / toolModeUseCases.js).
            // Polling-owned state (sessionsPollingTimer, lastSessionsEtag,
            // lastSessionEtag, pendingMutationCount, sessionMutationGeneration,
            // consecutivePollFailures, promptActionInFlight) now lives in
            // pollingUseCases (see pollingFsm.js / pollingUseCases.js).
            // MaxConsecutivePollFailures is declared in pollingFsm.js.
            const draftRuntime = createDraftRuntimeState({
              persistCurrentDraftWorkspaceIfNeeded: () => persistCurrentDraftWorkspaceIfNeeded(),
            });
            // Compatibility mirrors for legacy renderers that still read these
            // module-level names directly while draft state ownership migrates
            // into draftRuntimeState.
            let draftWorkspace = draftRuntime.currentDraftWorkspace();
            let undoRedoHistory = draftRuntime.currentUndoRedoHistory();

            function syncDraftRuntimeCompatibility() {
              draftWorkspace = draftRuntime.currentDraftWorkspace();
              undoRedoHistory = draftRuntime.currentUndoRedoHistory();
            }

            function currentDraftWorkspace() {
              syncDraftRuntimeCompatibility();
              return draftWorkspace;
            }

            function draftFlow() {
              return draftRuntime.draftFlow();
            }

            function draftItemList() {
              return draftRuntime.draftItemList();
            }

            function draftFocusIndex() {
              return draftRuntime.draftFocusIndex();
            }

            function setDraftFocusIndex(index) {
              draftRuntime.setDraftFocusIndex(index);
            }

            function draftSelection() {
              return draftRuntime.draftSelection();
            }

            function setDraftSelection(selection) {
              draftRuntime.setDraftSelection(selection);
            }

            function replaceDraftWorkspace(nextWorkspace) {
              draftRuntime.replaceDraftWorkspace(nextWorkspace);
              syncDraftRuntimeCompatibility();
            }

            function persistCurrentDraftWorkspaceIfNeeded() {
              const draftWorkspace = currentDraftWorkspace();
              if (!draftWorkspace?.workspaceId) return;
              if (!(draftWorkspace.items || []).length) {
                deleteCurrentDraftWorkspaceStorage();
                return;
              }
              const storage = createBrowserDraftPorts().storage;
              storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(draftWorkspace));
            }

            function deleteCurrentDraftWorkspaceStorage() {
              const draftWorkspace = currentDraftWorkspace();
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
                  nextDraftItemId: () => 'draft-' + toolMode.nextAnnotationSeq(),
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
                recoveryPrompt: {
                  choose: (recovery, boundaryAction) =>
                    promptPendingRecoveryBoundaryChoice(recovery, boundaryAction?.kind || boundaryAction),
                },
                refresh: { sessions: refreshSessions },
              });
            }

            function ensureDraftCommandQueue() {
              const existingQueue = draftRuntime.currentDraftCommandQueue();
              if (existingQueue) return existingQueue;
              const nextQueue = createDraftCommandQueue({
                getWorkspace: currentDraftWorkspace,
                setWorkspace: replaceDraftWorkspace,
                onStaleResponse: () => {
                  showWarning('An older draft save finished after newer edits. Your latest local edits are still here.');
                  refreshSessions().catch(showError);
                },
                onError: showError,
              });
              draftRuntime.replaceDraftCommandQueue(nextQueue);
              return nextQueue;
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
              if (!String(error.textContent || '').trim()) notificationCenter.hide('global-error');
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
              notificationCenter.hide('global-error');
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
                error.className = 'global-status status-' + variant;
                error.setAttribute('role', assertive ? 'alert' : 'status');
                error.setAttribute('aria-live', assertive ? 'assertive' : 'polite');
                if (message) {
                  notificationCenter.notify({
                    severity: variant === 'error' ? 'error' : variant === 'warning' ? 'warning' : variant === 'success' ? 'success' : 'info',
                    // Successes are dismissible toasts; everything else (including
                    // errors with a TTL) stays as a banner so a recoverable error
                    // never reduces to a toast-only surface.
                    surface: variant === 'success' ? 'toast' : 'banner',
                    title: variant === 'error' ? 'FixThis needs attention' : '',
                    message,
                    primaryAction: assertive ? 'Open details' : null,
                    // The dedupeKey MUST stay 'global-error' because three existing
                    // call sites in this same file (`syncStatusVisibility`,
                    // `resetStatusSurface`, and the message-empty branch) call
                    // `statusSurfaceRegistry.hide('global-error')` to clear the
                    // singleton status line. Changing this key strands those hides.
                    dedupeKey: 'global-error',
                    ttlMs: durationMs,
                  });
                } else {
                  notificationCenter.hide('global-error');
                }
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

            // Spec S1.4.1: surface a recovery banner with a "Reload console"
            // CTA when requestJson throws a ConsoleRequestError carrying
            // action === 'reload_console' (HTTP 403 from origin/token check).
            function surfaceReloadConsoleNotice(err, deps) {
              const d = deps || {};
              if (typeof ConsoleRequestError === 'undefined' || !(err instanceof ConsoleRequestError)) return false;
              if (err.action !== 'reload_console') return false;
              const center = d.notificationCenter || (typeof notificationCenter !== 'undefined' ? notificationCenter : null);
              if (!center) return false;
              const reload = d.reload || (() => { window.location.reload(); });
              center.notify({
                severity: 'error',
                surface: 'banner',
                dedupeKey: 'reload_console_403',
                title: 'Reload required',
                detail: 'Your session token expired or origin changed.',
                primaryAction: { label: 'Reload console', onSelect: () => reload() },
              });
              return true;
            }
