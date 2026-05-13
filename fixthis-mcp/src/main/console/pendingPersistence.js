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
