// @requires (none)
            // previewPoll.js — FALLBACK-ONLY preview polling helpers. Preview
            // polling is active only while SSE is unavailable
            // (shouldUsePreviewFallbackPolling()). Under a healthy EventSource
            // connection no preview poll occurs.
            // Late-preview-poll session-equality gate: returns true (and emits a
            // diagnostic warn) when response.sessionId no longer matches the
            // active session; callers MUST early-return without mutating
            // state.preview or notifying.
            function dropStalePreviewPoll(response, activeSessionId) {
              if (response?.sessionId && response.sessionId !== activeSessionId) {
                console.warn('[previewPoll] dropping stale response for session', response.sessionId);
                return true;
              }
              return false;
            }
