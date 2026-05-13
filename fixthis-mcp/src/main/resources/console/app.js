// state.js
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
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const selectionSummary = document.getElementById('selectionSummary');
            const clearSelectionButton = document.getElementById('clearSelectionButton');
            const addItemButton = document.getElementById('addItemButton');
            const copyPromptButton = document.getElementById('copyPromptButton');
            const sendAgentButton = document.getElementById('sendAgentButton');
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

// build-header
const ConsoleBuildEpochMs = 1778701380000;
const ConsoleBuildGitSha = '49b3bb4';

// staleness.js
            // staleness.js — detects stale fixthis-mcp / sidekick by comparing build epochs.
            const StaleThresholdMs = 5 * 60 * 1000;
            const StalenessDismissKey = 'fixthis.console.stalenessDismissedHash';

            async function checkServerStaleness() {
              try {
                const resp = await fetch('/api/server-version');
                if (resp.status === 404) {
                  // /api/server-version not present = pre-Task-5 fixthis-mcp = stale
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server is older than this console',
                    detail: 'Restart fixthis-mcp to apply the latest server code.',
                    hash: 'pre-version-endpoint',
                  });
                  return;
                }
                if (!resp.ok) return; // 5xx etc. ambiguous — silent
                const server = await resp.json();
                const drift = Math.abs(server.serverBuildEpochMs - ConsoleBuildEpochMs);
                if (drift > StaleThresholdMs) {
                  const hash = `${server.serverGitSha}-${ConsoleBuildGitSha}`;
                  renderStalenessBanner({
                    severity: 'warning',
                    headline: 'Server build is older than this console',
                    detail: `Client ${ConsoleBuildGitSha} → Server ${server.serverGitSha}. Restart fixthis-mcp.`,
                    hash,
                  });
                }
              } catch (_err) {
                // network or JSON error — silent
              }
            }

            function renderStalenessBanner(info) {
              const banner = document.getElementById('stalenessBanner');
              if (!banner) return;
              const dismissed = sessionStorage.getItem(StalenessDismissKey);
              if (dismissed === info.hash) return;
              banner.dataset.severity = info.severity || 'warning';
              const headlineSlot = banner.querySelector('[data-headline]');
              if (headlineSlot) headlineSlot.textContent = info.headline;
              const detailSlot = banner.querySelector('[data-detail]');
              if (detailSlot) detailSlot.textContent = info.detail;
              banner.dataset.hash = info.hash;
              banner.hidden = false;
            }

            document.getElementById('stalenessBanner')?.querySelector('[data-dismiss]')
              ?.addEventListener('click', (event) => {
                const banner = event.currentTarget.closest('#stalenessBanner');
                if (!banner) return;
                sessionStorage.setItem(StalenessDismissKey, banner.dataset.hash || '');
                banner.hidden = true;
              });

            const MinimumSupportedProtocolVersion = '1.3';

            // Parse "1.1" -> [1, 1]; "1" -> [1]; non-numeric / null / undefined -> null.
            function parseProtocolVersion(s) {
              if (typeof s !== 'string') return null;
              const parts = s.split('.').map((token) => Number(token));
              if (parts.length === 0) return null;
              if (parts.some((n) => !Number.isFinite(n))) return null;
              return parts;
            }

            // Returns negative / 0 / positive. Shorter array right-padded with 0.
            function compareProtocolVersion(a, b) {
              const len = Math.max(a.length, b.length);
              for (let i = 0; i < len; i++) {
                const ai = a[i] ?? 0;
                const bi = b[i] ?? 0;
                if (ai !== bi) return ai - bi;
              }
              return 0;
            }

            function checkProtocolCompat(status) {
              const reported = parseProtocolVersion(status?.bridgeProtocolVersion);
              const expected = parseProtocolVersion(MinimumSupportedProtocolVersion);
              if (!reported || !expected) return;
              const cmp = compareProtocolVersion(reported, expected);
              if (cmp === 0) return;
              if (cmp < 0) {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `Sample app bridge protocol v${status.bridgeProtocolVersion} is older than this console (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Reinstall the sample APK (./gradlew :app:installDebug) to update sidekick.',
                  hash: `protocol-sidekick-old-${status.bridgeProtocolVersion}`,
                });
              } else {
                renderStalenessBanner({
                  severity: 'critical',
                  headline: `This console is older than sample app bridge protocol v${status.bridgeProtocolVersion} (expects v${MinimumSupportedProtocolVersion})`,
                  detail: 'Restart fixthis-mcp + hard reload the browser tab to update console.',
                  hash: `protocol-console-old-${status.bridgeProtocolVersion}`,
                });
              }
            }

            function checkSidekickBuildEpoch(status) {
              const sidekickEpoch = status?.sidekickBuildEpochMs;
              if (typeof sidekickEpoch !== 'number') return;
              const drift = Math.abs(sidekickEpoch - ConsoleBuildEpochMs);
              if (drift > StaleThresholdMs) {
                renderStalenessBanner({
                  severity: 'warning',
                  headline: 'Sample app sidekick is older than this console',
                  detail: 'Reinstall the sample APK to refresh.',
                  hash: `sidekick-${sidekickEpoch}`,
                });
              }
            }

// pendingPersistence.js
            // pendingPersistence.js — write-through mirror to localStorage
            // for pending feedback state (ALH-1/STAB-5). Functions are bare so the
            // concat bundle exposes them in shared closure scope.

            const PENDING_KEY_PREFIX = 'fixthis.pending.';

            function pendingKey(sessionId) {
              return PENDING_KEY_PREFIX + sessionId;
            }

            function persistPendingState(sessionId, value) {
              if (!sessionId || typeof localStorage === 'undefined') return;
              try {
                const envelope = {
                  schemaVersion: 1,
                  sessionId: sessionId,
                  context: value?.context ?? null,
                  previewId: value?.previewId ?? null,
                  screen: value?.screen ?? null,
                  screenshotUrl: value?.screenshotUrl ?? null,
                  frozenAtEpochMillis: value?.frozenAtEpochMillis ?? Date.now(),
                  items: Array.isArray(value?.items) ? value.items : [],
                };
                localStorage.setItem(pendingKey(sessionId), JSON.stringify(envelope));
              } catch (e) {
                // Quota exceeded or storage disabled — best-effort, don't block UX
              }
            }

            function restorePendingState(sessionId) {
              if (!sessionId || typeof localStorage === 'undefined') return null;
              const raw = localStorage.getItem(pendingKey(sessionId));
              if (!raw) return null;
              try {
                const parsed = JSON.parse(raw);
                if (Array.isArray(parsed)) {
                  return {
                    schemaVersion: 0,
                    sessionId: sessionId,
                    context: null,
                    previewId: null,
                    screen: null,
                    screenshotUrl: null,
                    frozenAtEpochMillis: null,
                    items: parsed
                  };
                }
                if (parsed && Array.isArray(parsed.items)) {
                  return {
                    schemaVersion: parsed.schemaVersion === 1 ? 1 : parsed.schemaVersion,
                    sessionId: parsed.sessionId ?? sessionId,
                    context: parsed.context ?? null,
                    previewId: parsed.previewId ?? null,
                    screen: parsed.screen ?? null,
                    screenshotUrl: parsed.screenshotUrl ?? null,
                    frozenAtEpochMillis: parsed.frozenAtEpochMillis ?? null,
                    items: parsed.items
                  };
                }
                return null;
              } catch (e) {
                return null;
              }
            }

            function persistPendingItems(sessionId, items) {
              persistPendingState(sessionId, { items: items || [] });
            }

            function restorePendingItems(sessionId) {
              return restorePendingState(sessionId)?.items || [];
            }

            function clearPendingMirror(sessionId) {
              if (!sessionId || typeof localStorage === 'undefined') return;
              try {
                localStorage.removeItem(pendingKey(sessionId));
              } catch (e) { /* ignore */ }
            }

// draftWorkspace.js
// draftWorkspace.js - pure DraftWorkspace domain policy.
// No DOM, fetch, localStorage, timers, or global console state in this file.

const DraftLifecycle = Object.freeze({
  EMPTY: 'empty',
  CAPTURING: 'capturing',
  EDITING: 'editing',
  SAVING: 'saving',
  RECOVERING: 'recovering',
  CONFLICT: 'conflict',
  CLOSED: 'closed',
});

function createEmptyDraftWorkspace() {
  return {
    workspaceId: null,
    revision: 0,
    lifecycle: 'empty',
    context: null,
    screen: null,
    screenshotUrl: null,
    items: [],
    history: { undoStack: [], redoStack: [] },
    focusedItemId: null,
    currentSelection: null,
    activityDriftWarning: null,
    lastError: null,
  };
}

function cloneDraftValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function createDraftContext({ sessionId, previewId, screenId, screenFingerprint, deviceSerial, frozenAtEpochMillis, activityName }) {
  return {
    sessionId: sessionId || null,
    previewId: previewId || null,
    screenId: screenId || null,
    screenFingerprint: screenFingerprint ?? null,
    deviceSerial: deviceSerial || null,
    frozenAtEpochMillis: frozenAtEpochMillis || null,
    activityName: activityName || null,
  };
}

function createFrozenDraftWorkspace({ workspaceId, context, screen, screenshotUrl, history = null }) {
  if (!workspaceId) throw new Error('workspaceId is required');
  if (!context?.sessionId || !context?.previewId) throw new Error('Draft context requires sessionId and previewId');
  return {
    ...createEmptyDraftWorkspace(),
    workspaceId,
    revision: 1,
    lifecycle: DraftLifecycle.EDITING,
    context: cloneDraftValue(context),
    screen: cloneDraftValue(screen),
    screenshotUrl: screenshotUrl || null,
    history: history || { undoStack: [], redoStack: [] },
  };
}

function draftWorkspaceItems(workspace) {
  return Array.isArray(workspace?.items) ? workspace.items : [];
}

function requireDraftContext(workspace) {
  const context = workspace?.context;
  if (!context?.sessionId || !context?.previewId) {
    throw new Error('Annotation context is missing. Re-capture the screen and try again.');
  }
  return context;
}

function shouldIgnoreDraftAction(workspace, action) {
  if (!workspace) return true;
  if (action.workspaceId && workspace.workspaceId && action.workspaceId !== workspace.workspaceId) return true;
  if (Number.isInteger(action.expectedRevision) && workspace.revision !== action.expectedRevision) return true;
  return false;
}

function bumpDraftRevision(workspace, patch) {
  return {
    ...workspace,
    ...patch,
    revision: workspace.revision + 1,
  };
}

function reduceDraftWorkspace(workspace = createEmptyDraftWorkspace(), action = {}) {
  if (shouldIgnoreDraftAction(workspace, action)) return workspace;
  switch (action.type) {
    case 'FREEZE_STARTED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CAPTURING,
        lastError: null,
      });
    case 'FREEZE_SUCCEEDED':
      return createFrozenDraftWorkspace(action);
    case 'ADD_ITEM':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        items: draftWorkspaceItems(workspace).concat(cloneDraftValue(action.draftItem)),
        focusedItemId: action.draftItem?.draftItemId || workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'UPDATE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).map((item) =>
          item.draftItemId === action.draftItemId ? { ...item, ...cloneDraftValue(action.patch) } : item
        ),
        lastError: null,
      });
    case 'DELETE_ITEM':
      return bumpDraftRevision(workspace, {
        items: draftWorkspaceItems(workspace).filter((item) => item.draftItemId !== action.draftItemId),
        focusedItemId: workspace.focusedItemId === action.draftItemId ? null : workspace.focusedItemId,
        currentSelection: null,
        lastError: null,
      });
    case 'FOCUS_ITEM':
      return bumpDraftRevision(workspace, { focusedItemId: action.draftItemId || null });
    case 'CLEAR_FOCUS':
      return bumpDraftRevision(workspace, { focusedItemId: null, currentSelection: null });
    case 'RESTORE_RECOVERY':
      return bumpDraftRevision({
        ...createFrozenDraftWorkspace(action.workspace),
        revision: action.workspace?.revision || 1,
        items: cloneDraftValue(action.workspace?.items || []),
        history: cloneDraftValue(action.workspace?.history || { undoStack: [], redoStack: [] }),
        lifecycle: DraftLifecycle.EDITING,
      }, {});
    case 'SAVE_STARTED':
      return bumpDraftRevision(workspace, { lifecycle: DraftLifecycle.SAVING, lastError: null });
    case 'SAVE_SUCCEEDED':
      return createEmptyDraftWorkspace();
    case 'SAVE_CONFLICT':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.CONFLICT,
        lastError: cloneDraftValue(action.conflict || { error: 'conflict' }),
      });
    case 'SAVE_FAILED':
      return bumpDraftRevision(workspace, {
        lifecycle: DraftLifecycle.EDITING,
        lastError: cloneDraftValue(action.error || { error: 'save_failed' }),
      });
    case 'DISCARD':
    case 'SESSION_BOUNDARY_CLOSED':
      return createEmptyDraftWorkspace();
    default:
      return workspace;
  }
}

// draftWorkspaceHistory.js
// draftWorkspaceHistory.js - pure undo/redo helpers for DraftWorkspace items.

const DraftHistoryMaxDepth = 50;

function createEmptyDraftHistory() {
  return { undoStack: [], redoStack: [] };
}

function cloneDraftHistoryValue(value) {
  if (value == null) return value;
  return JSON.parse(JSON.stringify(value));
}

function pushDraftHistoryOp(stack, op) {
  const next = stack.concat(cloneDraftHistoryValue(op));
  while (next.length > DraftHistoryMaxDepth) next.shift();
  return next;
}

function recordDraftAdd(history, item) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'add', after: item }),
    redoStack: [],
  };
}

function recordDraftDelete(history, item, index) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'delete', before: item, index }),
    redoStack: [],
  };
}

function recordDraftUpdate(history, before, after) {
  return {
    undoStack: pushDraftHistoryOp(history?.undoStack || [], { kind: 'update', before, after }),
    redoStack: [],
  };
}

function sameDraftHistoryItem(left, right) {
  return Boolean(left?.draftItemId && right?.draftItemId && left.draftItemId === right.draftItemId);
}

function applyDraftInverse(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') {
    return next.filter((item) => !sameDraftHistoryItem(item, op.after));
  }
  if (op.kind === 'delete') {
    const restored = cloneDraftHistoryValue(op.before);
    const index = Number.isInteger(op.index) ? Math.max(0, Math.min(op.index, next.length)) : next.length;
    next.splice(index, 0, restored);
    return next;
  }
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.before) ? cloneDraftHistoryValue(op.before) : item);
  }
  return next;
}

function applyDraftForward(op, items) {
  const next = items.map(cloneDraftHistoryValue);
  if (op.kind === 'add') return next.concat(cloneDraftHistoryValue(op.after));
  if (op.kind === 'delete') return next.filter((item) => !sameDraftHistoryItem(item, op.before));
  if (op.kind === 'update') {
    return next.map((item) => sameDraftHistoryItem(item, op.after) ? cloneDraftHistoryValue(op.after) : item);
  }
  return next;
}

function undoDraftHistory(history, items) {
  const undoStack = (history?.undoStack || []).slice();
  const op = undoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack,
      redoStack: pushDraftHistoryOp(history?.redoStack || [], op),
    },
    items: applyDraftInverse(op, items || []),
  };
}

function redoDraftHistory(history, items) {
  const redoStack = (history?.redoStack || []).slice();
  const op = redoStack.pop();
  if (!op) return { applied: false, reason: 'empty', history: history || createEmptyDraftHistory(), items };
  return {
    applied: true,
    history: {
      undoStack: pushDraftHistoryOp(history?.undoStack || [], op),
      redoStack,
    },
    items: applyDraftForward(op, items || []),
  };
}

// draftPorts.js
// draftPorts.js - narrow port helpers for DraftWorkspace use cases.
// Port shape:
// {
//   ids: { nextWorkspaceId(): string, nextDraftItemId(): string },
//   clock: { now(): number },
//   preview: { capture(): Promise<FeedbackPreviewSnapshot>, screenshotUrl(previewId, sessionId): string },
//   feedbackApi: { saveDraftWorkspace(request): Promise<object>, saveToMcp(sessionId, itemIds): Promise<object>, handoffPreview(sessionId, itemIds): Promise<string>, markHandedOff(sessionId, itemIds): Promise<object> },
//   storage: { saveWorkspace(envelope): void, deleteWorkspace(sessionId, workspaceId): void, loadWorkspacesForSession(sessionId): object[], migrateLegacyPending(sessionId): object[] },
//   clipboard: { writeText(text): Promise<void> },
//   boundaryPrompt: { choose(workspace, boundaryAction): Promise<'save'|'keep'|'discard'|'cancel'> },
//   refresh: { sessions(): Promise<void> }
// }

function requireDraftPort(ports, name) {
  const value = ports?.[name];
  if (!value) throw new Error('Missing draft port: ' + name);
  return value;
}

function createFakeDraftPorts(overrides = {}) {
  let id = 0;
  return {
    ids: {
      nextWorkspaceId: () => 'workspace-' + (++id),
      nextDraftItemId: () => 'draft-' + (++id),
    },
    clock: { now: () => 1000 },
    preview: {
      capture: async () => { throw new Error('preview.capture fake not configured'); },
      screenshotUrl: (previewId, sessionId) => '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full?sessionId=' + encodeURIComponent(sessionId),
    },
    feedbackApi: {
      saveDraftWorkspace: async () => { throw new Error('feedbackApi.saveDraftWorkspace fake not configured'); },
      saveToMcp: async () => { throw new Error('feedbackApi.saveToMcp fake not configured'); },
      handoffPreview: async () => { throw new Error('feedbackApi.handoffPreview fake not configured'); },
      markHandedOff: async () => { throw new Error('feedbackApi.markHandedOff fake not configured'); },
    },
    storage: {
      saveWorkspace: () => {},
      deleteWorkspace: () => {},
      loadWorkspacesForSession: () => [],
      migrateLegacyPending: () => [],
    },
    clipboard: { writeText: async () => {} },
    boundaryPrompt: { choose: async () => 'cancel' },
    refresh: { sessions: async () => {} },
    ...overrides,
  };
}

// draftStorageAdapter.js
// draftStorageAdapter.js - browser storage adapter for DraftWorkspace recovery.

const DraftWorkspaceKeyPrefix = 'fixthis.workspace.';
const LegacyPendingKeyPrefix = 'fixthis.pending.';

function draftWorkspaceKey(sessionId, workspaceId) {
  return DraftWorkspaceKeyPrefix + sessionId + '.' + workspaceId;
}

function draftWorkspaceIndexKey(sessionId) {
  return DraftWorkspaceKeyPrefix + 'index.' + sessionId;
}

function parseDraftStorageJson(raw) {
  if (!raw) return null;
  try { return JSON.parse(raw); } catch (_) { return null; }
}

function normalizeLegacyDraftItem(item, index) {
  return {
    ...item,
    draftItemId: item?.draftItemId || item?.annotationId || ('legacy-' + (index + 1)),
  };
}

function createDraftStorageAdapter(localStorageLike, ids = {}) {
  const nextWorkspaceId = ids.nextWorkspaceId || (() => 'workspace-' + Date.now());

  function readIndex(sessionId) {
    const parsed = parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceIndexKey(sessionId)));
    return Array.isArray(parsed) ? parsed : [];
  }

  function writeIndex(sessionId, workspaceIds) {
    localStorageLike.setItem(draftWorkspaceIndexKey(sessionId), JSON.stringify(Array.from(new Set(workspaceIds))));
  }

  function saveWorkspace(envelope) {
    const sessionId = envelope?.sessionId || envelope?.context?.sessionId;
    const workspaceId = envelope?.workspaceId;
    if (!sessionId || !workspaceId) throw new Error('Workspace storage requires sessionId and workspaceId');
    const normalized = { ...envelope, schemaVersion: 2, sessionId, workspaceId };
    localStorageLike.setItem(draftWorkspaceKey(sessionId, workspaceId), JSON.stringify(normalized));
    writeIndex(sessionId, readIndex(sessionId).concat(workspaceId));
  }

  function loadWorkspacesForSession(sessionId) {
    return readIndex(sessionId)
      .map((workspaceId) => parseDraftStorageJson(localStorageLike.getItem(draftWorkspaceKey(sessionId, workspaceId))))
      .filter((value) => value?.schemaVersion === 2 && value?.context?.sessionId === sessionId);
  }

  function deleteWorkspace(sessionId, workspaceId) {
    localStorageLike.removeItem(draftWorkspaceKey(sessionId, workspaceId));
    writeIndex(sessionId, readIndex(sessionId).filter((id) => id !== workspaceId));
  }

  function migrateLegacyPending(sessionId) {
    const raw = localStorageLike.getItem(LegacyPendingKeyPrefix + sessionId);
    const legacy = parseDraftStorageJson(raw);
    if (!legacy || !Array.isArray(legacy.items) || !legacy.items.length) return [];
    if (legacy.schemaVersion === 0 || (!legacy.context && !legacy.previewId)) {
      return [{
        schemaVersion: 0,
        sessionId,
        requiresRecapture: true,
        items: legacy.items.map(normalizeLegacyDraftItem),
      }];
    }
    const workspaceId = nextWorkspaceId();
    const envelope = {
      schemaVersion: 2,
      sessionId,
      workspaceId,
      revision: 1,
      lifecycle: 'editing',
      context: legacy.context || {
        sessionId,
        previewId: legacy.previewId,
        screenId: legacy.screen?.screenId || null,
        screenFingerprint: legacy.screen?.fingerprint ?? null,
        deviceSerial: null,
        frozenAtEpochMillis: legacy.frozenAtEpochMillis || null,
        activityName: legacy.activity || legacy.screen?.activityName || null,
      },
      screen: legacy.screen || null,
      screenshotUrl: legacy.screenshotUrl || null,
      items: legacy.items.map(normalizeLegacyDraftItem),
      history: { undoStack: [], redoStack: [] },
      updatedAtEpochMillis: Date.now(),
    };
    saveWorkspace(envelope);
    return [envelope];
  }

  return { saveWorkspace, loadWorkspacesForSession, deleteWorkspace, migrateLegacyPending };
}

// beforeunloadGuard.js
// beforeunloadGuard.js — ALH-1: pure decision helper for the
// beforeunload prompt. The window.addEventListener call lives in
// main.js and delegates here.

function shouldGuardUnload(pendingItemsCount) {
  return Number(pendingItemsCount) > 0;
}

// undoRedo.js
            // undoRedo.js — ALH-2 pure undo/redo for pending feedback items.
            // Caller passes a state shape { pendingFeedbackItems: [...] }; no
            // closure reference needed.

            const UNDO_MAX_DEPTH = 50;

            function createHistory(context = null) {
              return { context: cloneHistoryContext(context), undoStack: [], redoStack: [] };
            }

            function pushOp(stack, op) {
              stack.push(op);
              if (stack.length > UNDO_MAX_DEPTH) stack.shift();
            }

            function cloneHistoryContext(context) {
              if (!context) return null;
              return {
                sessionId: context.sessionId || null,
                previewId: context.previewId || null,
                screenId: context.screenId || null,
                screenFingerprint: context.screenFingerprint || null,
                deviceSerial: context.deviceSerial || null
              };
            }

            function sameHistoryContext(left, right) {
              const a = cloneHistoryContext(left);
              const b = cloneHistoryContext(right);
              if (!a && !b) return true;
              return Boolean(a && b) &&
                a.sessionId === b.sessionId &&
                a.previewId === b.previewId &&
                a.screenId === b.screenId &&
                a.screenFingerprint === b.screenFingerprint &&
                a.deviceSerial === b.deviceSerial;
            }

            function clearHistory(history) {
              history.undoStack.length = 0;
              history.redoStack.length = 0;
            }

            function itemStableId(item) {
              return item?.itemId ?? item?.annotationId ?? null;
            }

            function sameHistoryItem(left, right) {
              const leftId = itemStableId(left);
              const rightId = itemStableId(right);
              return leftId != null && rightId != null && leftId === rightId;
            }

            function recordAdd(history, item, context = history.context) {
              pushOp(history.undoStack, {
                kind: 'add',
                after: { ...item },
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function recordDelete(history, before, index = null, context = history.context) {
              if (!before) return;
              pushOp(history.undoStack, {
                kind: 'delete',
                before: { ...before },
                index: Number.isInteger(index) ? index : null,
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function recordUpdate(history, before, after, context = history.context) {
              if (!before || !after) return;
              pushOp(history.undoStack, {
                kind: 'update',
                before: { ...before },
                after: { ...after },
                context: cloneHistoryContext(context),
                createdAtEpochMillis: Date.now()
              });
              history.redoStack.length = 0;
            }

            function applyInverse(op, state) {
              const items = state.pendingFeedbackItems;
              if (op.kind === 'add') {
                const idx = items.findIndex((item) => sameHistoryItem(item, op.after));
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'delete') {
                const restored = { ...op.before };
                if (Number.isInteger(op.index)) {
                  const index = Math.max(0, Math.min(op.index, items.length));
                  items.splice(index, 0, restored);
                } else {
                  items.push(restored);
                  items.sort((a, b) => (a.sequenceNumber || 0) - (b.sequenceNumber || 0));
                }
              } else if (op.kind === 'update') {
                const target = items.find((item) => sameHistoryItem(item, op.before));
                if (target) Object.assign(target, op.before);
              }
            }

            function applyForward(op, state) {
              const items = state.pendingFeedbackItems;
              if (op.kind === 'add') {
                items.push({ ...op.after });
              } else if (op.kind === 'delete') {
                const idx = items.findIndex((item) => sameHistoryItem(item, op.before));
                if (idx >= 0) items.splice(idx, 1);
              } else if (op.kind === 'update') {
                const target = items.find((item) => sameHistoryItem(item, op.after));
                if (target) Object.assign(target, op.after);
              }
            }

            function undo(history, state, context = history.context) {
              const op = history.undoStack.pop();
              if (!op) return { applied: false, reason: 'empty' };
              if (!sameHistoryContext(op.context, context)) {
                clearHistory(history);
                return { applied: false, reason: 'context_mismatch' };
              }
              applyInverse(op, state);
              pushOp(history.redoStack, op);
              return { applied: true };
            }

            function redo(history, state, context = history.context) {
              const op = history.redoStack.pop();
              if (!op) return { applied: false, reason: 'empty' };
              if (!sameHistoryContext(op.context, context)) {
                clearHistory(history);
                return { applied: false, reason: 'context_mismatch' };
              }
              applyForward(op, state);
              pushOp(history.undoStack, op);
              return { applied: true };
            }

// undoKeymatch.js
            // undoKeymatch.js — ALH-2 pure undo/redo keyboard match helpers.
            // The actual addEventListener('keydown', ...) wraps in main.js.

            function isInEditableField(activeElement) {
              if (!activeElement) return false;
              const tag = activeElement.tagName || '';
              if (tag === 'INPUT' || tag === 'TEXTAREA') return true;
              if (activeElement.isContentEditable) return true;
              return false;
            }

            function matchesUndo(event, activeElement) {
              if (!event) return false;
              if (isInEditableField(activeElement)) return false;
              const meta = !!(event.metaKey || event.ctrlKey);
              const key = (event.key || '').toLowerCase();
              return meta && key === 'z' && !event.shiftKey;
            }

            function matchesRedo(event, activeElement) {
              if (!event) return false;
              if (isInEditableField(activeElement)) return false;
              const meta = !!(event.metaKey || event.ctrlKey);
              const key = (event.key || '').toLowerCase();
              return meta && key === 'z' && event.shiftKey === true;
            }

// previewStaleness.js
// previewStaleness.js — SIF-5: pure decision helper for time-based and
// disconnect-based preview staleness. A frozen preview becomes stale when
// either (a) it is older than MAX_PREVIEW_AGE_MS, or (b) the bridge is not
// in the "connected" state. The status-tick site in main.js / connection.js
// is responsible for calling evaluateStale on each refresh and writing the
// result back into state.preview.stale.
//
// NOTE: This file is intentionally separate from staleness.js, which handles
// build-epoch / protocol-version drift between the server JAR and the bundled
// console assets — an unrelated concern.

const MAX_PREVIEW_AGE_MS = 30000;

function evaluateStale(state, now) {
  const frozenAt = state && state.preview && state.preview.frozenAtEpochMillis;
  if (!frozenAt) return false;
  const age = now - frozenAt;
  if (age > MAX_PREVIEW_AGE_MS) return true;
  const connection = state && state.bridgeStatus && state.bridgeStatus.connection;
  if (connection !== 'connected') return true;
  return false;
}

// activityDrift.js
// activityDrift.js — SIF-6: pure decision helper that decides whether the
// currently foregrounded activity differs from the activity captured when
// the addItemsFlow freeze was taken. Returning drift=true tells the caller
// to surface the inline warning + "분리 (새 freeze 시작)" button on the
// next pin form.
//
// Bare-function module — concatenated into resources/console/app.js by
// scripts/build-console-assets.mjs. Must be registered BEFORE annotations.js
// (its sole caller) in the sources array.

function checkActivityDrift(flow, current) {
  const expected = flow && flow.activity ? flow.activity : null;
  const actual = current && current.activity ? current.activity : null;
  if (!expected || !actual) {
    return { drift: false };
  }
  if (expected === actual) {
    return { drift: false };
  }
  return { drift: true, expected: expected, actual: actual };
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

// draftApiAdapter.js
// draftApiAdapter.js - explicit-session HTTP adapter for DraftWorkspace use cases.

function draftItemToAnnotationDraftDto(item, options = {}) {
  const rawComment = String(item.comment || '');
  const fallbackComment = String(item.label || '').trim() || (item.targetType === 'node' ? 'Component target' : 'Custom area');
  return {
    targetType: item.targetType,
    bounds: item.bounds,
    nodeUid: item.nodeUid,
    label: String(item.label || '').trim() || null,
    severity: item.severity || 'med',
    status: String(item.status || 'open').replace('-', '_'),
    comment: options.allowFallbackComments ? (rawComment.trim() || fallbackComment) : rawComment,
  };
}

function buildDraftWorkspaceSaveRequest(workspace, options = {}) {
  const context = workspace?.context || {};
  if (!context.sessionId) throw new Error('Draft save requires sessionId');
  if (!context.previewId) throw new Error('Draft save requires previewId');
  requireDraftContext(workspace);
  return {
    sessionId: context.sessionId,
    previewId: context.previewId,
    screen: workspace.screen || null,
    items: (workspace.items || [])
      .filter((item) => options.allowBlankComments || options.allowFallbackComments || String(item.comment || '').trim())
      .map((item) => draftItemToAnnotationDraftDto(item, options)),
    frozenFingerprint: context.screenFingerprint,
    forceMismatchOverride: Boolean(options.forceMismatchOverride),
  };
}

function createDraftApiHeaders(consoleToken) {
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (consoleToken) headers.set('X-FixThis-Console-Token', consoleToken);
  return headers;
}

function createDraftApiAdapter({ fetchImpl = fetch, consoleToken = null } = {}) {
  async function requestJson(path, body) {
    const response = await fetchImpl(path, {
      method: 'POST',
      headers: createDraftApiHeaders(consoleToken),
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      if (response.status === 409) {
        const conflict = await response.json().catch(() => ({}));
        return { conflict };
      }
      throw new Error(await response.text?.() || 'HTTP ' + response.status);
    }
    return await response.json();
  }

  return {
    saveDraftWorkspace: (request) => requestJson('/api/items/batch', request),
    saveToMcp: (sessionId, itemIds) => requestJson('/api/agent-handoffs', { sessionId, itemIds }),
    handoffPreview: async (sessionId, itemIds) => {
      const response = await fetchImpl('/api/sessions/' + encodeURIComponent(sessionId) + '/handoff-preview', {
        method: 'POST',
        headers: createDraftApiHeaders(consoleToken),
        body: JSON.stringify({ itemIds }),
      });
      if (!response.ok) throw new Error(await response.text?.() || 'HTTP ' + response.status);
      return await response.text();
    },
    markHandedOff: (sessionId, itemIds) =>
      requestJson('/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off', { itemIds }),
  };
}

// draftUseCases.js
// draftUseCases.js - DraftWorkspace application workflows over narrow ports.

async function startDraftFreeze(workspace, input, ports) {
  requireDraftPort(ports, 'preview');
  requireDraftPort(ports, 'ids');
  requireDraftPort(ports, 'clock');
  const preview = await ports.preview.capture();
  const sessionId = input?.sessionId;
  if (!sessionId) throw new Error('Cannot annotate without an active feedback session.');
  const context = createDraftContext({
    sessionId,
    previewId: preview.previewId,
    screenId: preview.screen?.screenId || null,
    screenFingerprint: preview.screen?.fingerprint ?? null,
    deviceSerial: input?.selectedDeviceSerial || null,
    frozenAtEpochMillis: ports.clock.now(),
    activityName: preview.activity || input?.activityName || null,
  });
  return createFrozenDraftWorkspace({
    workspaceId: ports.ids.nextWorkspaceId(),
    context,
    screen: preview.screen,
    screenshotUrl: ports.preview.screenshotUrl(preview.previewId, sessionId),
  });
}

function draftSelectionToItem(selection, ports) {
  return {
    draftItemId: ports.ids.nextDraftItemId(),
    targetType: selection.targetType,
    nodeUid: selection.nodeUid,
    bounds: selection.bounds,
    label: selection.label || null,
    severity: 'med',
    status: 'open',
    comment: '',
  };
}

function addDraftItem(workspace, selection, ports) {
  const draftItem = draftSelectionToItem(selection, ports);
  const next = reduceDraftWorkspace(workspace, { type: 'ADD_ITEM', workspaceId: workspace.workspaceId, draftItem });
  return { ...next, history: recordDraftAdd(next.history, draftItem) };
}

function updateDraftItem(workspace, draftItemId, patch) {
  const before = (workspace.items || []).find((item) => item.draftItemId === draftItemId);
  const next = reduceDraftWorkspace(workspace, { type: 'UPDATE_ITEM', workspaceId: workspace.workspaceId, draftItemId, patch });
  const after = (next.items || []).find((item) => item.draftItemId === draftItemId);
  return before && after ? { ...next, history: recordDraftUpdate(next.history, before, after) } : next;
}

function deleteDraftItem(workspace, draftItemId) {
  const index = (workspace.items || []).findIndex((item) => item.draftItemId === draftItemId);
  const before = index >= 0 ? workspace.items[index] : null;
  const next = reduceDraftWorkspace(workspace, { type: 'DELETE_ITEM', workspaceId: workspace.workspaceId, draftItemId });
  return before ? { ...next, history: recordDraftDelete(next.history, before, index) } : next;
}

async function persistDraftWorkspace(workspace, ports, options = {}) {
  const started = reduceDraftWorkspace(workspace, { type: 'SAVE_STARTED', workspaceId: workspace.workspaceId });
  const request = buildDraftWorkspaceSaveRequest(started, options);
  const response = await ports.feedbackApi.saveDraftWorkspace(request);
  if (response?.conflict?.error) {
    return {
      workspace: reduceDraftWorkspace(started, {
        type: 'SAVE_CONFLICT',
        workspaceId: started.workspaceId,
        expectedRevision: started.revision,
        conflict: response.conflict,
      }),
      session: null,
      conflict: response.conflict,
    };
  }
  ports.storage?.deleteWorkspace?.(started.context.sessionId, started.workspaceId);
  return {
    workspace: reduceDraftWorkspace(started, {
      type: 'SAVE_SUCCEEDED',
      workspaceId: started.workspaceId,
      expectedRevision: started.revision,
    }),
    session: response,
    itemIds: (response?.items || []).map((item) => item.itemId),
  };
}

function draftWorkspaceRecoveryEnvelope(workspace) {
  return {
    schemaVersion: 2,
    sessionId: workspace.context?.sessionId,
    workspaceId: workspace.workspaceId,
    revision: workspace.revision,
    lifecycle: workspace.lifecycle,
    context: workspace.context,
    screen: workspace.screen,
    screenshotUrl: workspace.screenshotUrl,
    items: workspace.items || [],
    history: workspace.history || { undoStack: [], redoStack: [] },
    updatedAtEpochMillis: Date.now(),
  };
}

function recoverDraftWorkspaceFromEnvelope(envelope) {
  if (envelope?.schemaVersion !== 2) throw new Error('Draft recovery requires schema v2 workspace envelope.');
  return {
    ...createFrozenDraftWorkspace({
      workspaceId: envelope.workspaceId,
      context: envelope.context,
      screen: envelope.screen,
      screenshotUrl: envelope.screenshotUrl,
      history: envelope.history || { undoStack: [], redoStack: [] },
    }),
    revision: envelope.revision || 1,
    items: envelope.items || [],
  };
}

async function resolveDraftBoundary(workspace, boundaryAction, ports) {
  if (!workspace?.workspaceId || !(workspace.items || []).length) return { choice: 'continue', workspace };
  const choice = await ports.boundaryPrompt.choose(workspace, boundaryAction);
  if (choice === 'keep') {
    ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(workspace));
    return { choice, workspace };
  }
  if (choice === 'discard') {
    ports.storage.deleteWorkspace(workspace.context?.sessionId, workspace.workspaceId);
    return { choice, workspace: reduceDraftWorkspace(workspace, { type: 'DISCARD', workspaceId: workspace.workspaceId }) };
  }
  if (choice === 'save') {
    const result = await persistDraftWorkspace(workspace, ports, { allowBlankComments: true });
    return { choice, ...result };
  }
  return { choice: 'cancel', workspace };
}

// draftCommandQueue.js
// draftCommandQueue.js - serialize DraftWorkspace application commands.

function createDraftCommandQueue({ getWorkspace, setWorkspace, onStaleResponse = () => {}, onError = () => {} }) {
  let tail = Promise.resolve();
  let pendingCount = 0;

  function matchesMeta(workspace, meta) {
    if (meta.workspaceId && workspace?.workspaceId !== meta.workspaceId) return false;
    if (Number.isInteger(meta.expectedRevision) && workspace?.revision !== meta.expectedRevision) return false;
    return true;
  }

  function enqueue(meta, run) {
    const execute = async () => {
      pendingCount += 1;
      try {
        const before = getWorkspace();
        if (!matchesMeta(before, meta)) {
          onStaleResponse(meta);
          return { applied: false, reason: 'stale_before' };
        }
        const result = await run(before);
        const current = getWorkspace();
        if (!matchesMeta(current, meta) && result?.workspace?.workspaceId !== current?.workspaceId) {
          onStaleResponse(meta, result);
          return { applied: false, reason: 'stale_after' };
        }
        if (result?.workspace) setWorkspace(result.workspace);
        return { applied: true, result };
      } catch (error) {
        onError(error, meta);
        throw error;
      } finally {
        pendingCount -= 1;
      }
    };
    const promise = tail.then(execute, execute);
    tail = promise.catch(() => {});
    return promise;
  }

  return {
    enqueue,
    isIdle: () => pendingCount === 0,
    pendingCount: () => pendingCount,
  };
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

            function recomputeInteractionBlockedReason() {
              const annotate = toolMode === 'annotate';
              const resolverInput = state.connection.availability
                ? { ...state.connection.availability, unresponsive: unresponsiveTracker.isUnresponsive() }
                : { unresponsive: unresponsiveTracker.isUnresponsive() };
              const rawReason = computeBlockedReason(resolverInput, annotate);
              state.connection.interactionBlockedReason = blockedReasonDebouncer.observe(rawReason);
            }

            function applyConnectionStatus(status, options) {
              const connectionOptions = options || {};
              state.connection.current = status;
              state.connection.availability = status?.availability ?? null;

              // Combine availability with the unresponsive tracker for the resolver input.
              recomputeInteractionBlockedReason();

              // success → clear failure streak
              unresponsiveTracker.observeSuccess();

              // Mark frozen preview stale when the device's foreground activity has changed.
              const restoredActivity = state.preview?.activity ?? null;
              const currentActivity = status?.availability?.activity ?? null;
              if (state.preview && restoredActivity && currentActivity) {
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
              if (
                state.connection.previousBlockedReason !== null &&
                state.connection.interactionBlockedReason === null
              ) {
                if (toolMode === 'select' && state.session) {
                  refreshPreview().catch(showError);
                }
              }
              state.connection.previousBlockedReason = state.connection.interactionBlockedReason;

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
              // Re-render the preview region so the canvas blocked-reason overlay and
              // stale-frame notice reflect the latest interactionBlockedReason / preview.stale.
              // Without this, the heartbeat-driven state update never reaches the canvas.
              renderPreviewRegion();
              checkProtocolCompat(status);
              checkSidekickBuildEpoch(status);
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
              if (state.connection.sessionsPollingPaused) {
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
                && !state.connection.hasEverConnected
                && !state.connection.sessionsPollingPaused;
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

// availability.js
// availability.js
function computeBlockedReason(status, isAnnotateMode) {
  if (!status) return null;
  if (status.screenInteractive === false) return 'screenOff';
  if (status.keyguardLocked === true) return 'locked';
  if (status.appForeground === false) return 'background';
  if (status.pictureInPicture === true) return 'pictureInPicture';
  if (status.unresponsive === true) return 'unresponsive';
  if (isAnnotateMode && (status.rootsCount === 0)) return 'noComposeUi';
  return null;
}

function createBlockedReasonDebouncer({ delayMs = 300, now = () => Date.now() } = {}) {
  let committed = null;
  let pending = null;
  let pendingSince = 0;
  return {
    observe(reason) {
      if (reason === null) {
        pending = null;
        pendingSince = 0;
        committed = null;
        return null;
      }
      if (reason === committed) return committed;
      if (pending !== reason) {
        pending = reason;
        pendingSince = now();
        return committed;
      }
      if (now() - pendingSince >= delayMs) {
        committed = reason;
        pending = null;
        pendingSince = 0;
        return committed;
      }
      return committed;
    }
  };
}

const UNRESPONSIVE_BACKOFF_MS = [1000, 2000, 5000, 10000, 30000];

function createUnresponsiveTracker({ threshold = 3 } = {}) {
  let streak = 0;
  return {
    observeFailure() {
      streak += 1;
      return streak >= threshold;
    },
    observeSuccess() {
      streak = 0;
    },
    isUnresponsive() {
      return streak >= threshold;
    },
    nextBackoffMs() {
      const idx = Math.min(streak, UNRESPONSIVE_BACKOFF_MS.length - 1);
      return UNRESPONSIVE_BACKOFF_MS[idx];
    },
  };
}

// devices.js
            const BLOCKED_SUFFIX = {
              screenOff: 'Screen off',
              locked: 'Locked',
              background: 'In background',
              pictureInPicture: 'PiP',
              unresponsive: 'Unresponsive',
              noComposeUi: 'No Compose UI',
            };

            function decorateConnectionLabel(baseLabel, reason) {
              if (!reason) return baseLabel;
              const suffix = BLOCKED_SUFFIX[reason];
              return suffix ? `${baseLabel} · ${suffix}` : baseLabel;
            }

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
              const baseLabel = DeviceStateCopy[uiState];
              const reason = uiState === DeviceUiState.CONNECTED
                ? (state.connection?.interactionBlockedReason || null)
                : null;
              deviceConnectionState.textContent = decorateConnectionLabel(baseLabel, reason);
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
                  bumpSessionMutationGeneration();
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
                bumpSessionMutationGeneration();
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
                bumpSessionMutationGeneration();
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
              bumpSessionMutationGeneration();
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
              bumpSessionMutationGeneration();
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
              if (screen?.screenId) return screenScreenshotUrl(screen.screenId, state.session?.sessionId || focusedSavedSessionId || null);
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
              const wasAnnotating = toolMode === 'annotate' || Boolean(addItemsFlow);
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
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
                currentSelection = null;
                hoveredAnnotationTarget = null;
                persistCurrentPendingState();
              } else {
                await refreshPreview();
              }
              render();
            }

            document.getElementById('canvasStaleNotice')?.querySelector('[data-use-latest]')?.addEventListener('click', () => {
              useLatestStaleFrame().catch(showError);
            });

// annotations.js
            function isInteractionBlocked() {
              return Boolean(state.connection?.interactionBlockedReason);
            }

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

            function lifecyclePhase(item) {
              const status = String(item?.status || 'open').replace('-', '_');
              if (status === 'resolved') return 'resolved';
              if (status === 'wont_fix') return 'wont_fix';
              if (status === 'needs_clarification') return 'needs_clarification';
              if (status === 'in_progress') return 'in_progress';
              if (item?.delivery === 'sent') {
                return item?.staleAfterHandoff ? 'sent_modified' : 'sent';
              }
              return 'draft';
            }

            function statusLabel(item) {
              switch (lifecyclePhase(item)) {
                case 'resolved': return 'Resolved';
                case 'wont_fix': return 'Won\'t Fix';
                case 'needs_clarification': return 'Needs Clarification';
                case 'in_progress': return 'In Progress';
                case 'sent_modified': return 'Sent · Modified';
                case 'sent': return 'Sent';
                default: return 'Draft';
              }
            }

            function statusClass(item) {
              return 'st-' + lifecyclePhase(item).replace('_', '-');
            }

            function statusValueLabel(value) {
              const normalized = String(value || 'open').replace('-', '_');
              if (normalized === 'in_progress') return 'In Progress';
              if (normalized === 'needs_clarification') return 'Needs Clarification';
              if (normalized === 'wont_fix') return 'Won\'t Fix';
              if (normalized === 'resolved') return 'Resolved';
              return 'Open';
            }

            // Display-side annotations for the toolbar counter and the right Annotations panel.
            // Includes already-sent items so the count matches the sidebar Session card's lifetime total
            // (sidebar uses server-side unresolvedItemsCount, which counts by status only — not delivery).
            // The send/copy path uses currentPromptAnnotations(), which re-applies the delivery filter
            // so already-sent items are not re-sent.
            function toolbarAnnotations() {
              if (addItemsFlow) return pendingFeedbackItems;
              return state.session?.items || [];
            }

            function hasWrittenAnnotationComment(item) {
              return Boolean(String(item?.comment || '').trim());
            }

            function currentPromptAnnotations() {
              if (!state.session) return [];
              return toolbarAnnotations()
                .filter(item => item.delivery !== 'sent')
                .filter(hasWrittenAnnotationComment);
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
              if (isInteractionBlocked()) return;
              const selection = nodeSelectionAtPoint(event, image);
              if (!selection) {
                if (!isInteractionBlocked()) {
                  showError(new Error('No component found at that point. Drag to select a custom area.'));
                }
                return;
              }
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function previewNodeAtPoint(event, image) {
              if (isInteractionBlocked()) return;
              const selection = nodeSelectionAtPoint(event, image);
              const nextId = selection?.nodeUid || null;
              const currentId = hoveredAnnotationTarget?.nodeUid || null;
              if (nextId === currentId) return;
              hoveredAnnotationTarget = selection;
              renderSelectionOverlay();
            }

            function confirmHoveredAnnotationTarget(event, image) {
              if (isInteractionBlocked()) return;
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
              if (isInteractionBlocked()) return;
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

            function resetAnnotationComposerState(clearFlow = true, clearMirror = true) {
              if (clearFlow) setDraftWorkspace(createEmptyDraftWorkspace());
              if (clearMirror) {
                clearPendingMirror(state.session?.sessionId);
                activePendingMirrorSessions.delete(state.session?.sessionId);
              }
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              toolMode = 'select';
              comment.value = '';
              clearDragState();
            }

            function currentPendingStateEnvelope(items = pendingFeedbackItems) {
              return {
                context: addItemsFlow?.context ?? null,
                previewId: addItemsFlow?.previewId ?? addItemsFlow?.context?.previewId ?? null,
                screen: addItemsFlow?.screen ?? null,
                screenshotUrl: addItemsFlow?.screenshotUrl ?? null,
                frozenAtEpochMillis: addItemsFlow?.frozenAtEpochMillis ?? addItemsFlow?.context?.frozenAtEpochMillis ?? null,
                items: items,
              };
            }

            function persistCurrentPendingState() {
              persistCurrentDraftWorkspaceIfNeeded();
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
                  preview = {
                    ...preview,
                    activity: state.connection?.availability?.activity ?? null,
                    frozenAtEpochMillis: Date.now(),
                    stale: false,
                  };
                  state.preview = preview;
                }
                if (!state.preview) {
                  return;
                }
                const ports = createBrowserDraftPorts();
                const nextWorkspace = await startDraftFreeze(draftWorkspace, {
                  sessionId: state.session?.sessionId || null,
                  selectedDeviceSerial: state.selectedDeviceSerial || null,
                  activityName: state.connection?.availability?.activity ?? null,
                }, {
                  ...ports,
                  preview: {
                    ...ports.preview,
                    capture: async () => state.preview,
                  },
                });
                setDraftWorkspace(nextWorkspace);
                toolMode = 'annotate';
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
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
              const commentInput = pendingItems.querySelector('#annotationCommentInput');
              item.comment = commentInput ? commentInput.value : comment.value;
            }

            function createAnnotationFromSelection(selection) {
              if (!addItemsFlow) throw new Error('Switch to Annotate before selecting feedback.');
              if (!selection) throw new Error('Select a component or area first.');
              flushFocusedPendingComment();
              const ports = createBrowserDraftPorts();
              let nextWorkspace = addDraftItem(draftWorkspace, selection, ports);
              // SIF-6: re-check activity drift after each pending item is
              // appended. Uses the existing status-poll-derived availability
              // — no extra fetch is issued.
              if (nextWorkspace.context) {
                const currentActivitySnapshot = {
                  activity: state.connection?.availability?.activity ?? null
                };
                nextWorkspace = {
                  ...nextWorkspace,
                  activityDriftWarning: checkActivityDrift({ activity: nextWorkspace.context.activityName }, currentActivitySnapshot),
                };
              }
              setDraftWorkspace(nextWorkspace);
              const createdItem = nextWorkspace.items[nextWorkspace.items.length - 1];
              hoveredAnnotationTarget = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              toolMode = 'annotate';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
              return createdItem;
            }

            function deletePendingFeedbackItem(index) {
              const removed = draftWorkspace.items[index];
              if (!removed) return;
              setDraftWorkspace(deleteDraftItem(draftWorkspace, removed.draftItemId));
              showUndoToast(removed.draftItemId);
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
                refreshSessions().catch(showError);
              } else {
                refreshSessions().catch(showError);
              }
              return updatedSession;
            }

            async function persistSavedEvidenceItem(item, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!item?.itemId) return state.session;
              const updatedSession = await withMutationLock(() => requestJson('/api/items/' + encodeURIComponent(item.itemId), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  sessionId: sessionId,
                  label: String(item.label || '').trim() || null,
                  severity: annotationSeverity(item),
                  comment: String(item.comment || ''),
                  status: normalizedPersistedStatus(annotationStatus(item))
                })
              }));
              return applySavedSessionUpdate(updatedSession, sessionId);
            }

            async function deleteSavedEvidenceItem(itemId, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!itemId) return;
              const sessionQuery = sessionId
                ? '?sessionId=' + encodeURIComponent(sessionId)
                : '';
              const updatedSession = await withMutationLock(() => requestJson('/api/items/' + encodeURIComponent(itemId) + sessionQuery, { method: 'DELETE' }));
              if (state.session?.sessionId === (updatedSession?.sessionId || sessionId)) {
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
                state.session = updatedSession;
                renderPreviewOnly();
                renderInspectorRegion();
                renderCurrentSessionList();
                refreshSessions().catch(showError);
              } else {
                refreshSessions().catch(showError);
              }
            }

            function updateSelectedAnnotationComment() {
              const item = selectedAnnotation();
              if (!item) return;
              item.comment = comment.value;
              if (addItemsFlow) persistCurrentPendingState();
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
              if (!draftWorkspace?.workspaceId) return;
              if (!draftWorkspaceItems(draftWorkspace).length) throw new Error('Add at least one pending feedback item.');
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              const forceMismatchOverride = Boolean(options.forceMismatchOverride);
              const items = draftWorkspaceItems(draftWorkspace);
              if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && items.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
              if (onlyWrittenComments && !items.some(hasWrittenAnnotationComment)) throw new Error('Add a comment to at least one annotation before sending.');
              const meta = {
                kind: 'persist-draft-workspace',
                workspaceId: draftWorkspace.workspaceId,
                expectedRevision: draftWorkspace.revision,
              };
              const result = await ensureDraftCommandQueue().enqueue(meta, async (workspace) => {
                const saveWorkspace = onlyWrittenComments
                  ? { ...workspace, items: draftWorkspaceItems(workspace).filter(hasWrittenAnnotationComment) }
                  : workspace;
                return await persistDraftWorkspace(saveWorkspace, createBrowserDraftPorts(), {
                  allowBlankComments,
                  onlyWrittenComments,
                  allowFallbackComments,
                  forceMismatchOverride,
                });
              });
              if (result?.result?.conflict?.error === 'screen_fingerprint_mismatch') {
                const conflict = result.result.conflict;
                const choice = await promptScreenFingerprintMismatch(conflict.frozenFingerprint, conflict.currentFingerprint);
                if (choice === 'force') {
                  return await persistPendingFeedbackItems({ ...options, forceMismatchOverride: true });
                }
                if (choice === 'recapture') {
                  setDraftWorkspace(createEmptyDraftWorkspace());
                  state.preview = null;
                  startLivePreviewPolling();
                  return null;
                }
                return null;
              }
              if (result?.result?.session) {
                state.session = result.result.session;
                state.preview = null;
                return state.session;
              }
              await refreshSessions();
              return state.session;
            }

            async function savePreviewBatchOrConflict(body) {
              const headers = new Headers({ 'Content-Type': 'application/json' });
              const token = window.FixThisConsoleConfig?.consoleToken;
              if (token) headers.set('X-FixThis-Console-Token', token);
              const response = await fetch('/api/items/batch', {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(body)
              });
              if (response.status === 409) {
                const conflict = await response.json().catch(() => ({}));
                if (conflict && conflict.error === 'screen_fingerprint_mismatch') {
                  return {
                    conflict: 'screen_fingerprint_mismatch',
                    frozenFingerprint: conflict.frozenFingerprint || null,
                    currentFingerprint: conflict.currentFingerprint || null
                  };
                }
                throw new Error('Save conflict: ' + JSON.stringify(conflict));
              }
              if (!response.ok) {
                throw new Error(await response.text() || 'HTTP ' + response.status);
              }
              return { session: await response.json() };
            }

            function promptScreenFingerprintMismatch(frozenFingerprint, currentFingerprint) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptFingerprintMismatch === 'function') {
                return Promise.resolve(window.fixThisPromptFingerprintMismatch({ frozenFingerprint: frozenFingerprint, currentFingerprint: currentFingerprint }));
              }
              const message = '화면이 캡처 이후 변경되었을 수 있습니다.\n\n' +
                '확인 = 현재 화면 다시 캡처\n' +
                '취소 = 다음 단계로 (강제 저장 여부 묻기)';
              if (typeof window === 'undefined' || typeof window.confirm !== 'function') {
                return Promise.resolve('cancel');
              }
              const recapture = window.confirm(message);
              if (recapture) return Promise.resolve('recapture');
              const force = window.confirm('강제로 저장하시겠습니까?\n확인 = 강제 저장\n취소 = 취소');
              return Promise.resolve(force ? 'force' : 'cancel');
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
              const screens = Number(session?.screensCount || 0);
              const screensLabel = screens > 0 ? countLabel(screens, 'screen', 'screens') + ' · ' : '';
              return screensLabel + formatHistoryDate(session.updatedAtEpochMillis);
            }

            function pendingHistoryItemsForSession(session) {
              if (!addItemsFlow || state.session?.sessionId !== session?.sessionId) return [];
              return pendingFeedbackItems;
            }

            function historyOpenCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              return (session.unresolvedItemsCount || 0) + (session.inProgressItemsCount || 0) + pending.filter(item => annotationStatus(item) !== 'resolved').length;
            }

            function historyDoneCount(session) {
              const pending = pendingHistoryItemsForSession(session);
              const persistedDone = Math.max(0, (session.itemsCount || 0) - (session.unresolvedItemsCount || 0));
              return persistedDone + pending.filter(item => annotationStatus(item) === 'resolved').length;
            }

            function renderHistoryStrip(session) {
              const open = historyOpenCount(session);
              const done = historyDoneCount(session);
              const total = open + done;
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
              const number = item.sequenceNumber ?? (index + 1);
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function formatSavedEvidenceItemLabel(item, index) {
              const number = item.sequenceNumber ?? (index + 1);
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
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

            function hasActiveHistorySessionForAnnotating() {
              return Boolean(
                state.session &&
                state.session.status !== 'closed' &&
                (state.sessionSummaries || []).some(session =>
                  session.sessionId === state.session.sessionId &&
                  session.status !== 'closed'
                )
              );
            }

            async function ensureSessionForAnnotating() {
              if (hasActiveHistorySessionForAnnotating()) return;
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) {
                throw new Error('Recover, recapture, or discard unsaved annotations before changing sessions.');
              }
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              }));
              await refreshSessions();
            }

            async function enterAnnotateMode() {
              if (!requirePendingRecoveryChoiceBeforeSessionChange()) return;
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
              const activeSummaries = sessionSummaries.filter(session => session.status !== 'closed');
              const ordinalBySessionId = sessionOrdinalLookup(activeSummaries);
              const renderedActiveSummaries = stableHistorySessions(activeSummaries);
              sessionCount.textContent = String(activeSummaries.length);
              const renderedSessions = renderedActiveSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const label = formatSessionLabel(session, ordinalBySessionId.get(session.sessionId) || index + 1);
                const pips = [
                  open > 0 ? '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' : '',
                  done > 0 ? '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'resolved', 'resolved')) + '</span>' : '',
                ].join('');
                return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<span class="hi-head">' +
                    '<span class="hi-title">' + escapeHtml(label) + '</span>' +
                    '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
                  '</span>' +
                  '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                  '<span class="hi-stats">' + pips + '</span>' +
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

            // Sync sidebar summaries and the active session in lockstep so the panel/toolbar
            // (driven by state.session) cannot drift behind the sidebar (driven by summaries).
            // Both endpoints fetched in parallel; if one fails the call rejects as before.
            async function refreshSessions() {
              const [response, currentSession] = await Promise.all([
                requestJson('/api/sessions'),
                requestJson('/api/session'),
              ]);
              state.session = currentSession || null;
              renderSessionsListFromPayload(response.sessions || []);
              return response.sessions || [];
            }

            async function refresh() {
              error.textContent = '';
              await refreshSessions();
              await refreshDevices();
              await refreshConnection();
              loadPendingRecoveryForCurrentSession();
              render();
            }

            async function openSession(sessionId) {
              error.textContent = '';
              if (await resolvePendingBeforeBoundary('open-session', sessionId) !== 'continue') return;
              bumpSessionMutationGeneration();
              stopLivePreviewPolling();
              resetAnnotationComposerState(true, false);
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              await refresh();
              if (!latestPersistedScreen() && shouldAutoFetchPreview()) {
                await refreshPreview();
              }
              startLivePreviewPolling();
            }

            async function newSession() {
              error.textContent = '';
              if (await resolvePendingBeforeBoundary('new-session') !== 'continue') return;
              bumpSessionMutationGeneration();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await withMutationLock(() => requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              }));
              await refresh();
              startLivePreviewPolling();
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              if (await resolvePendingBeforeBoundary('close-session', state.session.sessionId) !== 'continue') return;
              const sessionId = state.session.sessionId;
              bumpSessionMutationGeneration();
              await withMutationLock(() => requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = null;
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function deleteHistorySession(sessionId) {
              error.textContent = '';
              if (!sessionId) return;
              if (await resolvePendingBeforeBoundary('delete-session', sessionId) !== 'continue') return;
              const isDisplayedSession = () => state.session?.sessionId === sessionId;
              const wasDisplayedSession = isDisplayedSession();
              bumpSessionMutationGeneration();
              await withMutationLock(() => requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              }));
              if (wasDisplayedSession) {
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
              await withMutationLock(() => requestJson('/api/items/draft', { method: 'DELETE' }));
              clearSelection();
              await refresh();
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

            async function persistAndCollectItemIds() {
                const before = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
                const beforeIds = new Set(before.map(item => item.itemId));
                if (addItemsFlow) {
                    flushFocusedPendingComment();
                    if (pendingFeedbackItems.some(item => !hasWrittenAnnotationComment(item))) {
                        throw new Error('Add a comment to every annotation before saving.');
                    }
                    await persistPendingFeedbackItems();
                }
                const after = (state.session && Array.isArray(state.session.items)) ? state.session.items : [];
                const newlyPersisted = after.filter(item => !beforeIds.has(item.itemId)).map(item => item.itemId);
                if (newlyPersisted.length === 0) {
                    // No new items: fall back to currently-pending (already-sent or already-drafted) prompt selection.
                    const fallback = currentPromptAnnotations().map(item => item.itemId).filter(Boolean);
                    if (fallback.length === 0) throw new Error('Add a comment to at least one annotation before sending.');
                    return fallback;
                }
                return newlyPersisted;
            }

            async function fetchHandoffPreview(sessionId, itemIds) {
                const headers = new Headers({ 'Content-Type': 'application/json' });
                const token = window.FixThisConsoleConfig?.consoleToken;
                if (token) headers.set('X-FixThis-Console-Token', token);
                const response = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/handoff-preview`, {
                    method: 'POST',
                    headers,
                    body: JSON.stringify({ itemIds }),
                });
                if (!response.ok) {
                    const text = await response.text();
                    throw new Error(`Preview fetch failed (${response.status}): ${text}`);
                }
                return response.text();
            }

            async function markItemsHandedOff(sessionId, itemIds) {
                return await requestJson(
                    '/api/sessions/' + encodeURIComponent(sessionId) + '/items/mark-handed-off',
                    {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ itemIds }),
                    }
                );
            }

            async function copyPrompt() {
                if (promptActionInFlight) return;
                await withMutationLock(async () => {
                    clearSuccessStatus();
                    ensurePromptAnnotationsAvailable();
                    promptActionInFlight = true;
                    updateComposerState();
                    const labelSpan = copyPromptButton.querySelector('span:not(.button-icon)');
                    const originalLabel = labelSpan ? labelSpan.textContent : null;
                    let copied = false;
                    try {
                        const itemIds = await persistAndCollectItemIds();
                        const sessionId = draftWorkspace?.context?.sessionId || state.session?.sessionId;
                        if (!sessionId) throw new Error('Feedback session context is missing. Re-capture and try again.');
                        const markdown = await fetchHandoffPreview(sessionId, itemIds);
                        await copyTextToClipboard(markdown);
                        copied = true;
                        try {
                            const updated = await markItemsHandedOff(sessionId, itemIds);
                            state.session = updated;
                            renderInspectorRegion();
                        } catch (markError) {
                            showWarning('Copied, but MCP handoff status was not updated. Copy again after the connection recovers to update item state.');
                        }
                    } finally {
                        promptActionInFlight = false;
                        updateComposerState();
                        if (copied && labelSpan) {
                            labelSpan.textContent = 'Copied ✓';
                            setTimeout(() => {
                                if (labelSpan.textContent === 'Copied ✓') labelSpan.textContent = originalLabel;
                            }, 1500);
                        }
                    }
                });
            }

            async function sendAgentPrompt() {
                if (promptActionInFlight) return;
                await withMutationLock(async () => {
                    clearSuccessStatus();
                    ensurePromptAnnotationsAvailable();
                    promptActionInFlight = true;
                    updateComposerState();
                    let sent = false;
                    try {
                        const itemIds = await persistAndCollectItemIds();
                        const sessionId = draftWorkspace?.context?.sessionId || state.session?.sessionId;
                        if (!sessionId) throw new Error('Feedback session context is missing. Re-capture and try again.');
                        const result = await requestJson('/api/agent-handoffs', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({
                                sessionId,
                                itemIds
                            }),
                        });
                        state.session = result.session;
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
                        if (sent) showSuccess('Saved to MCP ✓ — agent will pick up', 3000);
                    }
                });
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
              draftWorkspaceItems(draftWorkspace).forEach((item, index) => {
                const displayNumber = index + 1;
                renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)));
              });
            }

            function annotationDisplayNumber(item, index) {
              return item?.sequenceNumber ?? (index + 1);
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
                  if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
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
              // SIF-6: inline activity-drift warning + restart button. Visible
              // only while an addItemsFlow is active and the most recent
              // checkActivityDrift() result reported drift=true.
              const driftWarningHtml = (addItemsFlow && addItemsFlow.activityDriftWarning && addItemsFlow.activityDriftWarning.drift)
                ? '<div class="activity-drift-warning" role="status" aria-live="polite" data-activity-drift>' +
                    '<div class="activity-drift-warning-body">' +
                      '<div class="activity-drift-warning-title">Activity changed during freeze</div>' +
                      '<div class="activity-drift-warning-detail">Frozen: ' + escapeHtml(String(addItemsFlow.activityDriftWarning.expected)) + ' · Now: ' + escapeHtml(String(addItemsFlow.activityDriftWarning.actual)) + '</div>' +
                    '</div>' +
                    '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>' +
                  '</div>'
                : '';
              pendingItems.innerHTML = driftWarningHtml + (pendingFeedbackItems.length
                ? '<div class="ann-list">' + pendingFeedbackItems.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const phase = lifecyclePhase(item);
                  const color = severityColor(annotationSeverity(item));
                  const pendingItemIdAttr = item.itemId ? ' data-item-id="' + escapeHtml(item.itemId) + '"' : '';
                  return '<button type="button" class="ann-row pending-item-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '"' + pendingItemIdAttr + ' data-focus-pending="' + index + '">' +
                    '<span class="ann-row-num" style="background:' + severityColor(annotationSeverity(item)) + '">' + (index + 1) + '</span>' +
                    '<span class="ann-row-body">' +
                      '<span class="ann-row-title">' + escapeHtml(annotationTitle(item, index)) + '</span>' +
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                    '</span>' +
                    '<span class="ann-row-status ' + statusClass(item) + '">' + escapeHtml(statusLabel(item)) + '</span>' +
                  '</button>';
                }).join('') + '</div>'
                : '<div class="empty-state"><div class="empty-title">No annotations yet.</div><div class="empty-body">Switch to <b>Annotate</b>, then click or drag on the preview.</div>' + startAnnotatingButtonHtml() + '</div>');
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              const driftRestartButton = pendingItems.querySelector('[data-activity-drift-restart]');
              if (driftRestartButton) {
                driftRestartButton.addEventListener('click', () => {
                  // SIF-6: discard the stale freeze and start a fresh one.
                  resetAnnotationComposerState(true);
                  startAddItemsFlow().catch(showError);
                });
              }
              bindStartAnnotatingButtons(pendingItems);
            }

            function bindStartAnnotatingButtons(container) {
              container.querySelectorAll('[data-start-annotating]').forEach(button => {
                button.addEventListener('click', () => enterAnnotateMode().catch(showError));
              });
            }

            function focusCommentInputAtEnd(commentInput) {
              commentInput.focus();
              const end = commentInput.value.length;
              commentInput.setSelectionRange(end, end);
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
                          escapeHtml(statusValueLabel(value)) +
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
                persistCurrentPendingState();
                updateComposerState();
                renderPreviewOnly();
              });
              commentInput.addEventListener('input', event => {
                item.comment = event.target.value;
                persistCurrentPendingState();
                updateComposerState();
              });
              pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  item.severity = button.dataset.setSeverity;
                  persistCurrentPendingState();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  item.status = button.dataset.setStatus;
                  persistCurrentPendingState();
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
              focusCommentInputAtEnd(commentInput);
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

            // Right-panel "Annotations" rows. Includes sent items so the panel count matches the
            // sidebar Session card. Each row carries its own status badge (Open/Resolved); rows tied
            // to delivered handoffs render the same as drafts here. Send/copy logic that must skip
            // already-sent items uses currentPromptAnnotations() instead.
            function savedEvidenceItems() {
              return state.session?.items || [];
            }

            function selectedSavedAnnotation() {
              if (!focusedSavedItemId) return null;
              return savedEvidenceItems().find(item => item.itemId === focusedSavedItemId) || null;
            }

            function renderSavedEvidenceOverlay(overlay, image, items) {
              const allSavedItems = savedEvidenceItems();
              items.forEach((item, index) => {
                const savedIndex = Math.max(0, allSavedItems.findIndex(savedItem => savedItem.itemId === item.itemId));
                const displayNumber = annotationDisplayNumber(item, savedIndex);
                renderOverlayBox(
                  overlay,
                  image,
                  boundsForTarget(item.target),
                  String(displayNumber),
                  false,
                  item.itemId === focusedSavedItemId,
                  savedIndex,
                  '',
                  severityColor(annotationSeverity(item)),
                  () => focusSavedEvidenceItem(item.itemId)
                );
              });
            }

            function savedScreenOrdinalLookup() {
              const screens = state.session?.screens || [];
              const ordered = [...screens].sort((left, right) =>
                (left.capturedAtEpochMillis || 0) - (right.capturedAtEpochMillis || 0) ||
                String(left.screenId || '').localeCompare(String(right.screenId || ''))
              );
              const ordinalByScreenId = new Map();
              ordered.forEach((screen, index) => ordinalByScreenId.set(screen.screenId, index + 1));
              return ordinalByScreenId;
            }

            function savedScreenHeaderHtml(item, ordinalByScreenId, isFirst) {
              const screen = (state.session?.screens || []).find(s => s.screenId === item.screenId);
              const ordinal = ordinalByScreenId.get(item.screenId) || ordinalByScreenId.size + 1;
              const time = screen?.capturedAtEpochMillis ? formatTime(screen.capturedAtEpochMillis) : '-';
              return '<div class="ann-screen-header' + (isFirst ? ' first' : '') + '">' +
                escapeHtml('Screen ' + ordinal + ' · ' + time) +
              '</div>';
            }

            function renderSavedEvidenceGroups() {
              const items = savedEvidenceItems();
              const selected = selectedSavedAnnotation();
              if (selected) {
                renderSavedAnnotationDetail(selected, items.findIndex(item => item.itemId === selected.itemId));
                return;
              }
              if (items.length) {
                const ordinalByScreenId = savedScreenOrdinalLookup();
                let prevScreenId = null;
                const rows = items.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const phase = lifecyclePhase(item);
                  const color = severityColor(annotationSeverity(item));
                  const displayNumber = annotationDisplayNumber(item, index);
                  let header = '';
                  if (item.screenId !== prevScreenId) {
                    header = savedScreenHeaderHtml(item, ordinalByScreenId, prevScreenId === null);
                    prevScreenId = item.screenId;
                  }
                  return header +
                    '<button type="button" class="ann-row saved-item-row ' + (item.itemId === focusedSavedItemId ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '" data-item-id="' + escapeHtml(item.itemId) + '" data-focus-saved="' + escapeHtml(item.itemId) + '">' +
                      '<span class="ann-row-num" style="background:' + color + '">' + escapeHtml(String(displayNumber)) + '</span>' +
                      '<span class="ann-row-body">' +
                        '<span class="ann-row-title">' + escapeHtml(targetLabel(item)) + '</span>' +
                        '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                      '</span>' +
                      '<span class="ann-row-status ' + statusClass(item) + '">' + escapeHtml(statusLabel(item)) + '</span>' +
                    '</button>';
                }).join('');
                draftItems.innerHTML = '<div class="ann-list">' + rows + '</div>';
              } else {
                draftItems.innerHTML = '<div class="empty-state"><div class="empty-title">No saved annotations yet.</div><div class="empty-body">Use <b>Annotate</b> to freeze the preview and add comments.</div>' + startAnnotatingButtonHtml() + '</div>';
              }
              draftItems.querySelectorAll('[data-focus-saved]').forEach(button => {
                button.addEventListener('click', () => focusSavedEvidenceItem(button.dataset.focusSaved));
              });
              bindStartAnnotatingButtons(draftItems);
            }

            function renderSavedAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              const editSessionId = focusedSavedSessionId || state.session?.sessionId || null;
              const phase = lifecyclePhase(item);
              const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified' || phase === 'needs_clarification';
              const dis = editable ? '' : ' disabled';
              const deletable = phase !== 'in_progress' && phase !== 'resolved' && phase !== 'wont_fix';
              const deleteDis = deletable ? '' : ' disabled';
              const banner = (() => {
                if (phase === 'sent_modified') {
                  return '<div class="annotation-banner annotation-banner-warn">' +
                    '<span>⚠ Modified after Save — agent will see the previous version.</span>' +
                    '<button type="button" class="annotation-resave" data-resave-current>Re-save</button>' +
                    '</div>';
                }
                if (phase === 'sent') {
                  const ts = item.lastHandedOffAtEpochMillis ? formatTime(item.lastHandedOffAtEpochMillis) : '';
                  return '<div class="annotation-banner annotation-banner-info">' +
                    'Sent to MCP' + (ts ? ' · ' + escapeHtml(ts) : '') +
                    '. Modify to refine before agent picks up.' +
                    '</div>';
                }
                if (phase === 'in_progress') {
                  const note = item.agentSummary ? '<pre class="annotation-summary">' + escapeHtml(item.agentSummary) + '</pre>' : '';
                  return '<div class="annotation-banner annotation-banner-locked">' +
                    '<div>🔒 Agent working on this — edits locked.</div>' +
                    note +
                    '</div>';
                }
                if (phase === 'needs_clarification') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no detail provided)';
                  return '<div class="annotation-banner annotation-banner-question">' +
                    '<div>? Agent needs clarification</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '<div class="annotation-banner-subtle">Edits remain enabled. Re-save after updating.</div>' +
                    '</div>';
                }
                if (phase === 'wont_fix') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no summary provided)';
                  return '<div class="annotation-banner annotation-banner-wont-fix">' +
                    '<div>× Agent marked won\'t fix</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '</div>';
                }
                if (phase === 'resolved') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no summary provided)';
                  return '<div class="annotation-banner annotation-banner-done">' +
                    '<div>✓ Agent completed</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '</div>';
                }
                return '';
              })();
              draftItems.innerHTML =
                '<div class="annotation-detail" data-phase="' + escapeHtml(phase) + '">' +
                  '<button type="button" class="annotation-back" data-back-saved-annotations>← All annotations</button>' +
                  banner +
                  '<div class="annotation-field">' +
                    '<label for="annotationLabelInput">Label</label>' +
                    '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(targetLabel(item)) + '"' + dis + '>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Severity</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                      ['high', 'med', 'low'].map(value =>
                        '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                          ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                          (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + dis + '>' +
                          escapeHtml(value === 'med' ? 'Med' : value) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationCommentInput">Comment</label>' +
                    '<textarea id="annotationCommentInput" class="annotation-textarea"' + dis + '>' + escapeHtmlValue(item.comment) + '</textarea>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Status</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Status">' +
                      ['open', 'in-progress', 'resolved'].map(value =>
                        '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                          ' aria-pressed="' + (status === value ? 'true' : 'false') + '"' + dis + '>' +
                          escapeHtml(statusValueLabel(value)) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current' + deleteDis + '>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-saved-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = draftItems.querySelector('#annotationLabelInput');
              const commentInput = draftItems.querySelector('#annotationCommentInput');
              labelInput.addEventListener('input', event => {
                if (!editable) return;
                item.label = event.target.value;
                updateComposerState();
                renderPreviewOnly();
              });
              labelInput.addEventListener('change', () => {
                if (!editable) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('input', event => {
                if (!editable) return;
                item.comment = event.target.value;
                updateComposerState();
              });
              commentInput.addEventListener('change', () => {
                if (!editable) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('blur', () => {
                if (!editable) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              draftItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  if (!editable) return;
                  item.severity = button.dataset.setSeverity;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => renderInspectorRegion())
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  if (!editable) return;
                  item.status = button.dataset.setStatus;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => {
                      renderPreviewOnly();
                      renderInspectorRegion();
                    })
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-resave-current]').forEach(button => {
                button.addEventListener('click', async () => {
                  try {
                    const result = await requestJson('/api/agent-handoffs', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ itemIds: [item.itemId] }),
                    });
                    state.session = result.session;
                    renderInspectorRegion();
                    renderPreviewOnly();
                    showSuccess('Re-saved to MCP ✓', 2000);
                  } catch (error) {
                    showError(error);
                  }
                });
              });
              draftItems.querySelectorAll('[data-back-saved-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  // Navigation must always succeed; persist is best-effort.
                  // Editable phases (draft/sent/sent_modified/needs_clarification) attempt persist;
                  // non-editable phases (in_progress/resolved/wont_fix) skip persist. The server would
                  // reject a PATCH on non-editable items with ITEM_NOT_EDITABLE — we should
                  // still let the user leave the detail view.
                  const goBack = () => {
                    focusedSavedItemId = null;
                    focusedSavedSessionId = null;
                    renderPreviewOnly();
                    renderInspectorRegion();
                  };
                  if (!editable) {
                    goBack();
                  } else {
                    persistSavedEvidenceItem(item, editSessionId)
                      .then(goBack)
                      .catch(error => { showError(error); goBack(); });
                  }
                });
              });
              const deleteButton = draftItems.querySelector('[data-delete-current]');
              if (deleteButton) {
                deleteButton.addEventListener('click', () => {
                  if (!deletable) return;
                  deleteSavedEvidenceItem(item.itemId, editSessionId).catch(showError);
                });
              }
              if (editable) focusCommentInputAtEnd(commentInput);
            }


            function workflowActiveStep() {
              if (!state.connection?.hasEverConnected && userConnectionState(state.connection?.current) !== 'ready') return 'connect';
              if (addItemsFlow || toolMode === 'annotate') return 'annotate';
              if (currentPromptAnnotations().length > 0) return 'handoff';
              return 'preview';
            }

            function workflowStepOrder(step) {
              return ['connect', 'preview', 'annotate', 'handoff'].indexOf(step);
            }

            function renderWorkflowProgress() {
              if (!workflowProgress) return;
              const active = workflowActiveStep();
              const activeOrder = workflowStepOrder(active);
              workflowProgress.querySelectorAll('[data-workflow-step]').forEach(node => {
                const step = node.getAttribute('data-workflow-step');
                const order = workflowStepOrder(step);
                node.dataset.state = order < activeOrder ? 'complete' : (step === active ? 'active' : 'upcoming');
              });
            }

            function renderSessionRegions() {
              renderSessionsList();
            }

            function renderComposerInspector() {
              const item = selectedAnnotation();
              const savedItems = savedEvidenceItems();
              inspectorTitle.textContent = item ? 'Annotation' : 'Annotations';
              inspectorCount.textContent = String(pendingFeedbackItems.length + savedItems.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = false;
              draftItems.hidden = savedItems.length === 0;
              if (savedSectionHeader) savedSectionHeader.hidden = savedItems.length === 0;
              inspectorFooter.hidden = savedItems.length === 0;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = savedItems.length === 0;
              renderPendingItems();
              if (savedItems.length) renderSavedEvidenceGroups();
            }

            function renderSavedAnnotationsInspector() {
              const items = savedEvidenceItems();
              inspectorTitle.textContent = 'Annotations';
              inspectorCount.textContent = String(items.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = true;
              draftItems.hidden = false;
              if (savedSectionHeader) savedSectionHeader.hidden = true;
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
                // Even with no screenshot, surface the blocked-reason overlay and
                // stale-frame notice so users see WHY there's no capture yet.
                renderCanvasBlockedOverlay();
                renderStaleFrameNotice();
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
              renderCanvasBlockedOverlay();
              renderStaleFrameNotice();
            }

            function renderPreviewOnly() {
              renderPreviewRegion();
              renderSelectionOverlay();
              updateComposerState();
              renderWorkflowProgress();
            }

            function render() {
              renderSessionRegions();
              renderPreviewRegion();
              renderInspectorRegion();
              renderConnection(state.connection.current);
              renderWorkflowProgress();
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

            const BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6;

            function mergeSessionIntoState(fresh) {
              const previous = state.session;
              const preservedComment = comment.value;
              const preservedFocusedSavedItemId = focusedSavedItemId;
              const preservedFocusedPendingIndex = focusedPendingItemIndex;
              const preservedSelection = currentSelection;

              state.session = fresh;

              comment.value = preservedComment;
              focusedSavedItemId = preservedFocusedSavedItemId;
              focusedPendingItemIndex = preservedFocusedPendingIndex;
              currentSelection = preservedSelection;

              // Compute which items actually changed status since last tick.
              const previousStatusById = new Map(
                (previous?.items || []).map(item => [item.itemId, item.status])
              );
              const changed = (fresh.items || []).filter(item => {
                const before = previousStatusById.get(item.itemId);
                return before && before !== item.status;
              });

              // Bulk-change guard: skip highlight cascade for ticks with too many transitions.
              if (changed.length >= BULK_CHANGE_HIGHLIGHT_THRESHOLD) return;

              // Per-item highlight (existing logic, unchanged).
              changed.forEach(item => {
                const changedItemId = item.itemId;
                requestAnimationFrame(() => {
                  document.querySelectorAll('[data-item-id="' + CSS.escape(changedItemId) + '"]').forEach(node => {
                    node.setAttribute('data-just-changed', 'true');
                    setTimeout(() => node.removeAttribute('data-just-changed'), 800);
                  });
                });
              });
            }

// sessions-polling.js
            const SessionsPollIntervalMs = 2000;

            function setSessionsPollingPaused(paused) {
              if (state.connection.sessionsPollingPaused === paused) return;
              state.connection.sessionsPollingPaused = paused;
              // Re-render the connection card to surface the change.
              if (state.connection.current) renderConnection(state.connection.current);
            }

            function shouldPollSessions() {
              return !document.hidden && pendingMutationCount === 0 && !isEditingAnnotation();
            }

            function isEditingAnnotation() {
              const active = document.activeElement;
              if (!active) return false;
              return active.id === 'annotationCommentInput' || active.id === 'annotationLabelInput';
            }

            function startSessionsPolling() {
              stopSessionsPolling();
              consecutivePollFailures = 0;
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(() => {
                  // pollSessionsTick already handles its own failures; this catch is defensive.
                });
              }, SessionsPollIntervalMs);
            }

            function stopSessionsPolling() {
              if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
              sessionsPollingTimer = null;
            }

            async function pollSessionsTick() {
              try {
                const listResp = await fetch('/api/sessions', {
                  headers: lastSessionsEtag ? { 'If-None-Match': lastSessionsEtag } : {}
                });
                if (listResp.status === 200) {
                  lastSessionsEtag = listResp.headers.get('ETag');
                  const data = await listResp.json();
                  state.sessionSummaries = data.sessions || [];
                  renderSessionsList();
                }

                if (state.session?.sessionId) {
                  const sessResp = await fetch('/api/session', {
                    headers: lastSessionEtag ? { 'If-None-Match': lastSessionEtag } : {}
                  });
                  if (sessResp.status === 200) {
                    lastSessionEtag = sessResp.headers.get('ETag');
                    const fresh = await sessResp.json();
                    if (fresh) {
                      mergeSessionIntoState(fresh);
                      renderInspectorRegion();
                    }
                  }
                }

                // success path: reset counter and ensure not paused
                consecutivePollFailures = 0;
                if (state.connection?.sessionsPollingPaused) {
                  setSessionsPollingPaused(false);
                }
              } catch (err) {
                consecutivePollFailures++;
                if (consecutivePollFailures >= MaxConsecutivePollFailures) {
                  setSessionsPollingPaused(true);
                  stopSessionsPolling();
                }
                // Swallow error silently while in backoff window — no toast for transient failures.
              }
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
            let pendingRecovery = null;
            const activePendingMirrorSessions = new Set();

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
                const result = undo(undoRedoHistory, { pendingFeedbackItems }, addItemsFlow?.context ?? null);
                if (result.reason === 'context_mismatch') showError('Undo history was cleared because the annotation session changed.');
                if (result.applied) {
                  e.preventDefault();
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                }
              } else if (matchesRedo(e, active)) {
                const result = redo(undoRedoHistory, { pendingFeedbackItems }, addItemsFlow?.context ?? null);
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
              if (shouldGuardUnload(pendingFeedbackItems.length)) {
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
                const result = undo(undoRedoHistory, { pendingFeedbackItems }, addItemsFlow?.context ?? null);
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
              const next = (items || [])
                .map(item => String(item?.annotationId || '').match(/^local-(\d+)$/))
                .filter(Boolean)
                .map(match => Number(match[1]))
                .filter(Number.isFinite)
                .reduce((max, value) => Math.max(max, value + 1), annotationSequence);
              annotationSequence = Math.max(annotationSequence, next);
            }

            function restorePendingRecoveryContext(recovery) {
              const workspace = recoverDraftWorkspaceFromEnvelope(recovery);
              setDraftWorkspace(workspace);
              state.preview = {
                previewId: workspace.context.previewId,
                screen: workspace.screen,
                activity: workspace.context.activityName,
                frozenAtEpochMillis: workspace.context.frozenAtEpochMillis,
                stale: false
              };
              updateAnnotationSequenceFromPendingItems(workspace.items);
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              toolMode = 'select';
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
              if (!pendingRecovery || !recoveryItems.length || addItemsFlow || pendingFeedbackItems.length) {
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
                restorePendingRecoveryContext(pendingRecovery);
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
              if (addItemsFlow || pendingFeedbackItems.length) {
                renderPendingRecoveryBanner();
                return;
              }
              const restored = loadDraftRecoveryForSession(sessionId) || restorePendingState(sessionId);
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
              invalidatePreviewContext();
              await startAddItemsFlow();
              if (!addItemsFlow) return;
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
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
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
