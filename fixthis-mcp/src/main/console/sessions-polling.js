            const SessionsPollIntervalMs = 2000;

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
              sessionsPollingTimer = setInterval(() => {
                if (shouldPollSessions()) pollSessionsTick().catch(showError);
              }, SessionsPollIntervalMs);
            }

            function stopSessionsPolling() {
              if (sessionsPollingTimer) clearInterval(sessionsPollingTimer);
              sessionsPollingTimer = null;
            }

            async function pollSessionsTick() {
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
            }
