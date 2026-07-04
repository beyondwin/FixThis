// @requires (none)
            // sse.js — late-SSE-message session-equality gate and connection
            // state that gates both preview AND session fallback polling
            // (shouldUsePreviewFallbackPolling / shouldUseSessionFallbackPolling).
            let consoleEventsLastConnectedAt = 0;
            const consoleEventsDiagnosticState = {
              connected: false,
              connectCount: 0,
              disconnectCount: 0,
              reconnectCount: 0,
              replayOverflowCount: 0,
              lastConnectedAt: null,
              lastDisconnectedAt: null,
              lastFallbackReason: null,
            };

            function nowMs(options) {
              return typeof options?.nowMs === 'number' ? options.nowMs : Date.now();
            }

            function setConsoleEventsConnected(connected, options = {}) {
              const nextConnected = connected === true;
              if (nextConnected) {
                const wasConnectedBefore = consoleEventsDiagnosticState.connectCount > 0;
                consoleEventsDiagnosticState.connectCount += 1;
                if (wasConnectedBefore && !consoleEventsDiagnosticState.connected) {
                  consoleEventsDiagnosticState.reconnectCount += 1;
                }
                consoleEventsLastConnectedAt = nowMs(options);
                consoleEventsDiagnosticState.lastConnectedAt = consoleEventsLastConnectedAt;
              } else if (consoleEventsDiagnosticState.connected) {
                consoleEventsDiagnosticState.disconnectCount += 1;
                consoleEventsDiagnosticState.lastDisconnectedAt = nowMs(options);
                consoleEventsDiagnosticState.lastFallbackReason = options.reason || 'eventsource_disconnected';
              } else if (options.reason) {
                consoleEventsDiagnosticState.lastFallbackReason = options.reason;
              }
              consoleEventsDiagnosticState.connected = nextConnected;
              return consoleEventsDiagnosticState.connected;
            }

            function recordConsoleEventsOverflow(options = {}) {
              consoleEventsDiagnosticState.replayOverflowCount += 1;
              consoleEventsDiagnosticState.lastFallbackReason = 'replay_overflow';
              return consoleEventsDiagnostics();
            }

            function consoleEventsDiagnostics() {
              return { ...consoleEventsDiagnosticState };
            }

            function isConsoleEventsConnected() {
              return consoleEventsDiagnosticState.connected;
            }

            function wasConsoleEventsRecentlyConnected(maxAgeMs = 1000) {
              return consoleEventsLastConnectedAt > 0 && (Date.now() - consoleEventsLastConnectedAt) <= maxAgeMs;
            }

            function shouldUsePreviewFallbackPolling() {
              return !consoleEventsDiagnosticState.connected;
            }

            function shouldUseSessionFallbackPolling() {
              return !consoleEventsDiagnosticState.connected;
            }

            // Returns true (and emits a diagnostic warn) when msg.sessionId no
            // longer matches the active session; callers MUST early-return
            // without mutating state or notifying. Broadcasts without a
            // sessionId pass through.
            function dropStaleSse(msg, activeSessionId) {
              if (msg?.sessionId && msg.sessionId !== activeSessionId) {
                console.warn('[sse] dropping stale response for session', msg.sessionId);
                return true;
              }
              return false;
            }
