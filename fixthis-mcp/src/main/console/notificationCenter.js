// @requires statusSurfaceRegistry.js
            const SupportedNotificationSurfaces = Object.freeze(new Set(['toast', 'banner', 'inline']));

            function notificationContent(input) {
              return {
                headline: input.title || input.message || '',
                detail: input.message || '',
                retry: Boolean(input.primaryAction),
              };
            }

            function normalizedNotificationSurface(input) {
              const requested = input.surface || (input.severity === 'success' ? 'toast' : 'banner');
              const surface = SupportedNotificationSurfaces.has(requested) ? requested : 'banner';
              // Auto-promote: severity:error never lands on toast unless the caller
              // explicitly opts in via allowErrorToast (e.g. retried-and-recovered).
              if (input.severity === 'error' && surface === 'toast' && !input.allowErrorToast) {
                return 'banner';
              }
              return surface;
            }

            function createNotificationCenter({
              registry = statusSurfaceRegistry,
              setTimeoutImpl = (typeof window !== 'undefined' ? window.setTimeout : setTimeout),
            } = {}) {
              const activeKeys = new Map(); // id -> { releaseTimer }

              function notify(input = {}) {
                const surfaceClass = normalizedNotificationSurface(input);
                const id = input.dedupeKey || [
                  input.severity || 'info',
                  surfaceClass,
                  input.title || '',
                  input.message || '',
                ].join(':');
                if (activeKeys.has(id)) return id;

                const autoDismissMs = input.ttlMs != null
                  ? input.ttlMs
                  : (surfaceClass === 'toast' ? 3000 : 0);
                const opts = {
                  surfaceClass,
                  priority: input.severity === 'error' ? 1 : input.severity === 'warning' ? 2 : 3,
                  content: surfaceClass === 'toast' ? (input.message || input.title || '') : notificationContent(input),
                  autoDismissMs,
                };
                // Track the entry, and release it once the surface auto-dismisses so a
                // later notify with the same dedupeKey can re-display (polling failure
                // recurrence, retried operations, etc.).
                let releaseTimer = null;
                if (autoDismissMs > 0) {
                  releaseTimer = setTimeoutImpl(() => {
                    activeKeys.delete(id);
                  }, autoDismissMs);
                }
                activeKeys.set(id, { releaseTimer });
                registry.show(id, opts);
                return id;
              }

              function hide(id) {
                const entry = activeKeys.get(id);
                if (entry && entry.releaseTimer != null && typeof clearTimeout === 'function') {
                  clearTimeout(entry.releaseTimer);
                }
                activeKeys.delete(id);
                registry.hide(id);
              }

              function clearRecoverable(id) {
                hide(id);
              }

              return { clearRecoverable, hide, notify };
            }
