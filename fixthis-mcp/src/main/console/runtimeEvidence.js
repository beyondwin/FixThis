// @requires api.js, state.js
            const RuntimeEvidencePresets = ['baseline', 'logs', 'memory', 'performance'];
            const RuntimeEvidencePolicies = ['auto_on_handoff', 'manual', 'off'];

            function runtimeEvidenceEscapeHtml(value) {
              return String(value ?? '').replace(/[&<>"']/g, char => ({
                '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;'
              })[char]);
            }

            function runtimeEvidenceWarningLabel(value) {
              return String(value || '').replaceAll('_', ' ');
            }

            function runtimeEvidenceStatusModel(value) {
              const allowed = ['collecting', 'complete', 'partial', 'failed', 'unsupported', 'disabled'];
              const status = allowed.includes(value?.status) ? value.status : 'unsupported';
              return { status, label: 'Diagnostics: ' + (status === 'disabled' ? 'off' : status) };
            }

            function renderRuntimeEvidenceRows(value) {
              const rows = [
                ['Captured', value?.capturedAtEpochMillis ? new Date(value.capturedAtEpochMillis).toLocaleTimeString() : null],
                ['Proximity', value?.proximity],
                ['Type', value?.type],
                ['Summary', value?.summary],
                ['Artifact', value?.artifactPath],
                ['Reason', value?.failureReason],
                ...(value?.warnings || []).slice(0, 4).map((warning, index) => ['Warning ' + (index + 1), warning]),
              ].filter(row => row[1]).map(row => row.map(entry => runtimeEvidenceWarningLabel(entry).slice(0, 500)));
              return rows.map(row => '<span>' + runtimeEvidenceEscapeHtml(row[0]) + '</span><strong>' +
                runtimeEvidenceEscapeHtml(row[1]) + '</strong>').join('');
            }

            function createRuntimeEvidenceController(ports) {
              const captures = ports.captureState || new Map();
              const captureSeq = new Map();
              const policyQueues = new Map();
              const keyOf = (sessionId, itemId) => sessionId + '\u0000' + itemId;
              const next = (map, key) => (map.set(key, (map.get(key) || 0) + 1), map.get(key));
              const current = input => {
                const session = ports.activeSession();
                const item = (session?.items || []).find(candidate => candidate.itemId === input.itemId);
                return session?.sessionId === input.sessionId && item?.screenId === input.screenId;
              };
              const stale = (key, generation, input) => {
                if (captureSeq.get(key) !== generation) return true;
                if (current(input)) return false;
                captures.delete(key);
                return true;
              };

              async function collect(input) {
                if (!RuntimeEvidencePresets.includes(input.preset)) throw new Error('Unknown runtime evidence preset');
                const key = keyOf(input.sessionId, input.itemId);
                const generation = next(captureSeq, key);
                captures.set(key, { status: 'collecting', generation, screenId: input.screenId });
                ports.render?.();
                try {
                  const result = await ports.request('/api/items/' + encodeURIComponent(input.itemId) + '/runtime-evidence/collect', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ sessionId: input.sessionId, preset: input.preset }),
                  });
                  if (stale(key, generation, input)) return null;
                  const safe = {
                    status: result?.status || (result?.skippedReason ? 'unsupported' : 'failed'),
                    attachmentIds: [...(result?.attachmentIds || [])],
                    linkedItemIds: [...(result?.linkedItemIds || [])],
                    warnings: [...(result?.warnings || [])].slice(0, 4),
                    failureReason: result?.failureReason || null,
                    skippedReason: result?.skippedReason || null,
                    generation,
                    screenId: input.screenId,
                  };
                  captures.set(key, safe);
                  return safe;
                } catch (error) {
                  if (stale(key, generation, input)) return null;
                  captures.set(key, { status: 'failed', generation, screenId: input.screenId, failureReason: error?.error || 'collection_failed' });
                  throw error;
                } finally {
                  ports.render?.();
                }
              }

              function effectivePolicy(session = ports.activeSession()) {
                if (!session?.sessionId) return 'manual';
                return policyQueues.get(session.sessionId)?.desired || session.runtimeEvidencePolicy || 'manual';
              }

              function drainPolicyQueue(sessionId, queue) {
                if (queue.running || !queue.queued) return;
                const mutation = queue.queued;
                queue.queued = null;
                queue.running = true;
                let request;
                try {
                  request = ports.request('/api/sessions/' + encodeURIComponent(sessionId) + '/runtime-evidence-policy', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ policy: mutation.policy }),
                  });
                } catch (error) {
                  request = Promise.reject(error);
                }
                Promise.resolve(request).then(updated => {
                  const latest = queue.generation === mutation.generation;
                  const active = ports.activeSession()?.sessionId === sessionId;
                  if (latest && active) ports.applySession(updated);
                  if (latest) queue.desired = null;
                  queue.running = false;
                  drainPolicyQueue(sessionId, queue);
                  if (latest && active) ports.render?.();
                  mutation.resolve(latest && active ? updated : null);
                }, error => {
                  const latest = queue.generation === mutation.generation;
                  const active = ports.activeSession()?.sessionId === sessionId;
                  if (latest) queue.desired = null;
                  queue.running = false;
                  drainPolicyQueue(sessionId, queue);
                  if (latest && active) ports.render?.();
                  if (latest && active) mutation.reject(error);
                  else mutation.resolve(null);
                });
              }

              function updatePolicy(policy) {
                if (!RuntimeEvidencePolicies.includes(policy)) throw new Error('Unknown runtime evidence policy');
                const sessionId = ports.activeSession()?.sessionId;
                if (!sessionId) throw new Error('No active feedback session');
                const queue = policyQueues.get(sessionId) || { desired: null, generation: 0, queued: null, running: false };
                policyQueues.set(sessionId, queue);
                const generation = ++queue.generation;
                queue.desired = policy;
                queue.queued?.resolve(null);
                const promise = new Promise((resolve, reject) => {
                  queue.queued = { generation, policy, reject, resolve };
                });
                queue.latestPromise = promise;
                ports.render?.();
                drainPolicyQueue(sessionId, queue);
                return promise;
              }

              const stateFor = (itemId, sessionId = ports.activeSession()?.sessionId) =>
                sessionId ? captures.get(keyOf(sessionId, itemId)) || null : null;
              const awaitPolicySettled = (sessionId = ports.activeSession()?.sessionId) =>
                sessionId ? (policyQueues.get(sessionId)?.latestPromise || Promise.resolve(null)) : Promise.resolve(null);
              return { awaitPolicySettled, collect, effectivePolicy, stateFor, updatePolicy };
            }

            let browserRuntimeEvidenceController;

            function runtimeEvidenceController() {
              return browserRuntimeEvidenceController ||= createRuntimeEvidenceController({
                activeSession: () => state.session,
                request: requestJson,
                applySession: setConsoleSession,
                captureState: state.runtimeEvidenceByItem,
                render: () => {
                  renderRuntimeEvidencePolicyControl();
                  renderInspectorRegion();
                },
              });
            }

            function collectRuntimeEvidenceForItem(item, preset = 'baseline') {
              const sessionId = state.session?.sessionId;
              if (!sessionId || !item?.itemId || !item?.screenId) return Promise.reject(new Error('Annotation context is unavailable'));
              if (state.session.status === 'closed') return Promise.reject(new Error('Diagnostics are unavailable for a closed session'));
              if (runtimeEvidenceController().effectivePolicy() === 'off') return Promise.resolve(null);
              return runtimeEvidenceController().collect({ sessionId, itemId: item.itemId, screenId: item.screenId, preset });
            }

            function updateRuntimeEvidencePolicy(policy) {
              return runtimeEvidenceController().updatePolicy(policy);
            }

            function runtimeEvidenceAttachmentsForItem(item) {
              const ids = new Set(item?.runtimeEvidenceIds || []);
              return (state.session?.runtimeEvidence || []).filter(value => ids.has(value.evidenceId))
                .sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0));
            }

            function runtimeEvidenceSectionHtml(item) {
              const attachments = runtimeEvidenceAttachmentsForItem(item);
              const current = runtimeEvidenceController().stateFor(item?.itemId) || attachments[0];
              const closed = state.session?.status === 'closed';
              const off = runtimeEvidenceController().effectivePolicy() === 'off';
              const model = closed ? { status: 'disabled', label: 'Diagnostics: unavailable' }
                : (off ? runtimeEvidenceStatusModel({ status: 'disabled' }) : (current && runtimeEvidenceStatusModel(current)));
              const collecting = model?.status === 'collecting';
              const label = collecting ? 'Capturing diagnostics…' : ((attachments.length || current) ? 'Capture again' : 'Capture diagnostics');
              const entries = attachments.slice(0, 4).map(value => {
                const status = runtimeEvidenceStatusModel(value).status;
                return '<div class="runtime-evidence-entry" data-status="' + runtimeEvidenceEscapeHtml(status) + '"><strong>' +
                  runtimeEvidenceEscapeHtml(runtimeEvidenceWarningLabel(value.type)) + ' · ' + runtimeEvidenceEscapeHtml(status) +
                  '</strong><div class="evidence-grid">' + renderRuntimeEvidenceRows(value) + '</div></div>';
              }).join('');
              const pendingRows = current && !attachments.length ? '<div class="evidence-grid">' + renderRuntimeEvidenceRows(current) + '</div>' : '';
              return '<section class="annotation-section runtime-evidence-section" role="status" aria-live="polite"><div class="runtime-evidence-heading"><h3>Diagnostics</h3>' +
                (model ? '<span class="runtime-evidence-status" data-status="' + model.status + '">' + model.label + '</span>' : '') + '</div>' +
                (closed ? '<p class="runtime-evidence-help">Diagnostics are unavailable for a closed session.</p>'
                  : (off ? '<p class="runtime-evidence-help">Diagnostics are Off for this session.</p>' : '')) + pendingRows + entries +
                '<button id="collectRuntimeEvidenceButton" type="button" aria-busy="' + collecting + '"' + (collecting || off || closed ? ' disabled' : '') + '>' + label + '</button></section>';
            }

            function bindRuntimeEvidenceCollection(root, item) {
              root.querySelector('#collectRuntimeEvidenceButton')?.addEventListener('click', () => {
                collectRuntimeEvidenceForItem(item).then(result => {
                  if (result) showSuccess('Diagnostics captured', 2e3);
                }).catch(showError);
              });
            }

            function renderRuntimeEvidencePolicyControl() {
              const control = document.getElementById('runtimeEvidencePolicy');
              if (!control) return;
              control.disabled = !state.session?.sessionId || state.session.status === 'closed';
              control.value = runtimeEvidenceController().effectivePolicy();
            }
