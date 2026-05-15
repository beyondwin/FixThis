// @requires state.js, connection.js, devices.js, preview.js, history.js, rendering.js, sessions-polling.js
            let consoleEventsSource = null;
            function startConsoleEvents() {
              if (typeof EventSource === 'undefined' || consoleEventsSource) return;
              const source = consoleEventsSource = new EventSource('/api/events');
              const json = (event) => { try { return JSON.parse(event.data || '{}'); } catch (_) { return {}; } };
              const on = (name, fn) => source.addEventListener(name, (event) => fn(json(event)));
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
                  refreshSessions().catch(showError);
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
              on('sessions-updated', (data) => data.sessions?.sessions ? renderSessionsListFromPayload(data.sessions.sessions) : refreshSessions().catch(showError));
              on('preview-ready', (data) => {
                if (!data.preview || draftFlow()) return;
                if (data.sessionId !== state.session?.sessionId) return;
                setConsolePreview({
                  ...data.preview,
                  activity: state.connection?.availability?.activity ?? null,
                  frozenAtEpochMillis: Date.now(),
                  stale: false
                });
                renderPreviewOnly();
              });
              on('devices-updated', (data) => renderDeviceList(data.devices || data));
              on('connection-updated', (data) => data.connection && applyConnectionStatus(data.connection));
              source.addEventListener('replay-overflow', () => refresh().catch(showError));
              source.onerror = () => state.connection && !state.connection.sessionsPollingPaused && setSessionsPollingPaused(true);
            }
