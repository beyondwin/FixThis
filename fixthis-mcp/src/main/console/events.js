// @requires state.js, connection.js, devices.js, preview.js, history.js, rendering.js, sessions-polling.js, sse.js, devReload.js, serverBuildChip.js
            let consoleEventsSource = null;
            let consoleEventsHasOpenedBefore = false;
            function startConsoleEvents() {
              if (typeof EventSource === 'undefined' || consoleEventsSource) return;
              const source = consoleEventsSource = new EventSource(consoleCapabilityUrl('/api/events'));
              const json = (event) => { try { return JSON.parse(event.data || '{}'); } catch (_) { return {}; } };
              const on = (name, fn) => source.addEventListener(name, (event) => fn(json(event)));
              source.onopen = () => {
                setConsoleEventsConnected(true);
                stopLivePreviewPolling();
                stopSessionsPolling();
                if (state.connection?.sessionsPollingPaused) setSessionsPollingPaused(false);
                const wasReconnect = consoleEventsHasOpenedBefore;
                consoleEventsHasOpenedBefore = true;
                if (wasReconnect) {
                  fetch('/api/server-version', { cache: 'no-store' })
                    .then((res) => res.ok ? res.json() : null)
                    .then((payload) => {
                      const node = getServerBuildChipNode();
                      if (!node) return;
                      updateServerBuildChipState(node, {
                        state: 'connected',
                        buildHash: payload?.serverGitSha,
                      });
                    })
                    .catch(() => {
                      const node = getServerBuildChipNode();
                      if (node) updateServerBuildChipState(node, { state: 'connected' });
                    });
                }
              };
              const activeSessionId = () => state.session?.sessionId || null;
              const matchesActiveSession = (data) => Boolean(data?.sessionId && data.sessionId === state.session?.sessionId);
              function applySessionFromServer(session) {
                const previousSessionId = state.session?.sessionId || null;
                const nextSessionId = session?.sessionId || null;
                if (previousSessionId !== nextSessionId) clearPreview();
                setConsoleSession(session || null);
              }
              on('snapshot', (data) => {
                const previousDisplayedSessionId = displayedSessionId();
                if ('session' in data) {
                  if (!data.session && previousDisplayedSessionId) clearDisplayedSessionState();
                  else applySessionFromServer(data.session || null);
                }
                if (data.sessions?.sessions) renderSessionsListFromPayload(data.sessions.sessions);
                if (data.devices) renderDeviceList(data.devices);
                if (data.connection) applyConnectionStatus(data.connection);
                render();
              });
              on('session-updated', (data) => {
                if (!data.session) return;
                if (activeSessionId() && !matchesActiveSession(data)) {
                  refreshSessionsWhenEventsDisconnected().catch(showError);
                  return;
                }
                if (isClosedSession(data.session) && displayedSessionId() === data.sessionId) {
                  clearDisplayedSessionState();
                  render();
                  return;
                }
                const session = data.session;
                applySessionFromServer(session);
                loadPendingRecoveryForCurrentSession();
                render();
              });
              on('sessions-updated', (data) => {
                if (data.summary) {
                  applySessionSummaryFromPayload(data.summary);
                  return;
                }
                if (data.sessions?.sessions) {
                  renderSessionsListFromPayload(data.sessions.sessions);
                  return;
                }
                refreshSessionsWhenEventsDisconnected().catch(showError);
              });
              on('preview-ready', (data) => {
                if (!data.preview || draftFlow()) return;
                applyLivePreview(data.preview, {
                  source: 'sse',
                  sessionId: data.sessionId,
                });
              });
              on('devices-updated', (data) => renderDeviceList(data.devices || data));
              on('connection-updated', (data) => data.connection && applyConnectionStatus(data.connection));
              on('console-assets-changed', (data) => handleConsoleAssetsChanged(data));
              source.addEventListener('replay-overflow', () => {
                recordConsoleEventsOverflow();
                refresh().catch(showError);
              });
              source.onerror = () => {
                setConsoleEventsConnected(false, { reason: 'eventsource_error' });
                if (state.connection && !state.connection.sessionsPollingPaused) setSessionsPollingPaused(true);
                startLivePreviewPolling();
                startSessionsPolling();
                const node = getServerBuildChipNode();
                if (node) updateServerBuildChipState(node, { state: 'reconnecting' });
              };
            }
