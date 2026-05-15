// @requires state.js, connection.js, devices.js, preview.js, history.js, rendering.js, sessions-polling.js
            let consoleEventsSource = null;
            function startConsoleEvents() {
              if (typeof EventSource === 'undefined' || consoleEventsSource) return;
              const source = consoleEventsSource = new EventSource('/api/events');
              const json = (event) => { try { return JSON.parse(event.data || '{}'); } catch (_) { return {}; } };
              const on = (name, fn) => source.addEventListener(name, (event) => fn(json(event)));
              on('snapshot', (data) => {
                if ('session' in data) setConsoleSession(data.session || null);
                if (data.sessions?.sessions) renderSessionsListFromPayload(data.sessions.sessions);
                if (data.devices) renderDeviceList(data.devices);
                if (data.connection) applyConnectionStatus(data.connection);
                render();
              });
              on('session-updated', (data) => {
                if (!data.session) return;
                setConsoleSession(data.session);
                loadPendingRecoveryForCurrentSession();
                render();
              });
              on('sessions-updated', (data) => data.sessions?.sessions ? renderSessionsListFromPayload(data.sessions.sessions) : refreshSessions().catch(showError));
              on('preview-ready', (data) => {
                if (!data.preview || draftFlow()) return;
                setConsolePreview({ ...data.preview, activity: state.connection?.availability?.activity ?? null, frozenAtEpochMillis: Date.now(), stale: false });
                renderPreviewOnly();
              });
              on('devices-updated', (data) => renderDeviceList(data.devices || data));
              on('connection-updated', (data) => data.connection && applyConnectionStatus(data.connection));
              source.addEventListener('replay-overflow', () => refresh().catch(showError));
              source.onerror = () => state.connection && !state.connection.sessionsPollingPaused && setSessionsPollingPaused(true);
            }
