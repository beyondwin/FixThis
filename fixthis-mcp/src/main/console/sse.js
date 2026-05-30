// @requires (none)
            // sse.js — late-SSE-message session-equality gate and connection
            // state that gates both preview AND session fallback polling
            // (shouldUsePreviewFallbackPolling / shouldUseSessionFallbackPolling).
            let consoleEventsConnected = false;
            let consoleEventsLastConnectedAt = 0;

            function setConsoleEventsConnected(connected) {
              consoleEventsConnected = connected === true;
              if (consoleEventsConnected) consoleEventsLastConnectedAt = Date.now();
              return consoleEventsConnected;
            }

            function isConsoleEventsConnected() {
              return consoleEventsConnected;
            }

            function wasConsoleEventsRecentlyConnected(maxAgeMs = 1000) {
              return consoleEventsLastConnectedAt > 0 && (Date.now() - consoleEventsLastConnectedAt) <= maxAgeMs;
            }

            function shouldUsePreviewFallbackPolling() {
              return !consoleEventsConnected;
            }

            function shouldUseSessionFallbackPolling() {
              return !consoleEventsConnected;
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
