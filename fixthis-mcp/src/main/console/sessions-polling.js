// @requires state.js, api.js
            const SessionsPollIntervalMs = 2000;

            // sessionsPollingTimer is the setInterval handle; lives in this
            // closure (no longer at module scope) so polling-owned timer
            // state never leaks into state.js.
            let sessionsPollingTimer = null;

            function setSessionsPollingPaused(paused) {
              if (state.connection.sessionsPollingPaused === paused) return;
              connectionUseCases.setSessionsPollingPaused(paused);
              // Mirror into the polling FSM via visibility-style transitions
              // so the lifecycle stays consistent.
              if (paused) pollingUseCases.visibilityHidden();
              else pollingUseCases.visibilityVisible();
              // Re-render the connection card to surface the change.
              if (state.connection.current) renderConnection(state.connection.current);
            }

            function shouldPollSessions() {
              return !document.hidden &&
                pollingUseCases.getState().pendingMutationCount === 0 &&
                !isEditingAnnotation();
            }

            function isEditingAnnotation() {
              const active = document.activeElement;
              if (!active) return false;
              return active.id === 'annotationCommentInput' || active.id === 'annotationLabelInput';
            }

            function startSessionsPolling() {
              stopSessionsPolling();
              pollingUseCases.startSessionsPolling();
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(() => {
                  // pollSessionsTick already handles its own failures; this catch is defensive.
                });
              }, SessionsPollIntervalMs);
            }

            function stopSessionsPolling() {
              if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
              sessionsPollingTimer = null;
              pollingUseCases.stopSessionsPolling();
            }

            // pollSessionsTick delegates the HTTP + FSM bookkeeping to
            // pollingUseCases.pollSessionsTick (api.sessions performs the
            // actual fetches and side-effects; the use case dispatches
            // TICK_OK / TICK_FAILED). At the threshold, the FSM transitions
            // to POLLING_BACKOFF; this wrapper observes that transition and
            // mirrors it into the connection FSM via setSessionsPollingPaused
            // so legacy UI continues to surface the pause.
            async function pollSessionsTick() {
              try {
                await pollingUseCases.pollSessionsTick();
              } catch (err) {
                if (pollingUseCases.getState().lifecycle === PollingLifecycle.POLLING_BACKOFF) {
                  setSessionsPollingPaused(true);
                  stopSessionsPolling();
                }
                // Swallow error silently while in backoff window — no toast for transient failures.
                return;
              }
              // success path: ensure not paused (drain any stale paused projection)
              if (state.connection?.sessionsPollingPaused) {
                setSessionsPollingPaused(false);
              }
            }
