// @requires (none)
            // sse.js — late-SSE-message session-equality gate.
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
