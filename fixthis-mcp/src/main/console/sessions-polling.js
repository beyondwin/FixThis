            const SessionsPollIntervalMs = 2000;

            function setSessionsPollingPaused(paused) {
              if (state.connection.sessionsPollingPaused === paused) return;
              state.connection.sessionsPollingPaused = paused;
              // Re-render the connection card to surface the change.
              if (state.connection.current) renderConnection(state.connection.current);
            }

            function shouldPollSessions() {
              return !document.hidden && pendingMutationCount === 0 && !isEditingAnnotation();
            }

            function isEditingAnnotation() {
              const active = document.activeElement;
              if (!active) return false;
              return active.id === 'annotationCommentInput' || active.id === 'annotationLabelInput';
            }

            function startSessionsPolling() {
              stopSessionsPolling();
              consecutivePollFailures = 0;
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(() => {
                  // pollSessionsTick already handles its own failures; this catch is defensive.
                });
              }, SessionsPollIntervalMs);
            }

            function stopSessionsPolling() {
              if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
              sessionsPollingTimer = null;
            }

            async function pollSessionsTick() {
              try {
                const listResp = await fetch('/api/sessions', {
                  headers: lastSessionsEtag ? { 'If-None-Match': lastSessionsEtag } : {}
                });
                if (listResp.status === 200) {
                  lastSessionsEtag = listResp.headers.get('ETag');
                  const data = await listResp.json();
                  state.sessionSummaries = data.sessions || [];
                  renderSessionsList();
                }

                if (state.session?.sessionId) {
                  const sessResp = await fetch('/api/session', {
                    headers: lastSessionEtag ? { 'If-None-Match': lastSessionEtag } : {}
                  });
                  if (sessResp.status === 200) {
                    lastSessionEtag = sessResp.headers.get('ETag');
                    const fresh = await sessResp.json();
                    if (fresh) {
                      mergeSessionIntoState(fresh);
                      renderInspectorRegion();
                    }
                  }
                }

                // success path: reset counter and ensure not paused
                consecutivePollFailures = 0;
                if (state.connection?.sessionsPollingPaused) {
                  setSessionsPollingPaused(false);
                }
              } catch (err) {
                consecutivePollFailures++;
                if (consecutivePollFailures >= MaxConsecutivePollFailures) {
                  setSessionsPollingPaused(true);
                  stopSessionsPolling();
                }
                // Swallow error silently while in backoff window — no toast for transient failures.
              }
            }
