            // pendingPersistence.js — write-through mirror to localStorage
            // for pendingFeedbackItems (ALH-1). Functions are bare so the
            // concat bundle exposes them in shared closure scope.

            const PENDING_KEY_PREFIX = 'fixthis.pending.';

            function pendingKey(sessionId) {
              return PENDING_KEY_PREFIX + sessionId;
            }

            function persistPendingItems(sessionId, items) {
              if (!sessionId || typeof localStorage === 'undefined') return;
              try {
                localStorage.setItem(pendingKey(sessionId), JSON.stringify(items || []));
              } catch (e) {
                // Quota exceeded or storage disabled — best-effort, don't block UX
              }
            }

            function restorePendingItems(sessionId) {
              if (!sessionId || typeof localStorage === 'undefined') return [];
              const raw = localStorage.getItem(pendingKey(sessionId));
              if (!raw) return [];
              try {
                const parsed = JSON.parse(raw);
                return Array.isArray(parsed) ? parsed : [];
              } catch (e) {
                return [];
              }
            }

            function clearPendingMirror(sessionId) {
              if (!sessionId || typeof localStorage === 'undefined') return;
              try {
                localStorage.removeItem(pendingKey(sessionId));
              } catch (e) { /* ignore */ }
            }
