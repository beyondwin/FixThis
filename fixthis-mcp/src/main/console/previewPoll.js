// @requires (none)
            // previewPoll.js — late-preview-poll session-equality gate.
            // Returns true (and emits a diagnostic warn) when response.sessionId
            // no longer matches the active session; callers MUST early-return
            // without mutating state.preview or notifying.
            function dropStalePreviewPoll(response, activeSessionId) {
              if (response?.sessionId && response.sessionId !== activeSessionId) {
                console.warn('[previewPoll] dropping stale response for session', response.sessionId);
                return true;
              }
              return false;
            }
