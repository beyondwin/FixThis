// @requires annotations.js, viewmodel/reliabilityPresentation.js, domain/targetReliabilityViewModel.js, presentation/selectionOverlayView.js
            function itemBounds(item) {
              return boundsForTarget(item?.target) || item?.bounds || item?.target?.bounds || null;
            }

            function compactValue(value) {
              if (Array.isArray(value)) return value.filter(Boolean).join(', ') || '-';
              return text(value);
            }

            function sourceCandidateLine(candidate) {
              if (!candidate) return '-';
              const location = candidate.line == null ? candidate.file : candidate.file + ':' + candidate.line;
              const reasons = candidate.reason || candidate.matchReason || (candidate.matchReasons || []).join(', ');
              const confidence = candidate.confidence ? String(candidate.confidence).toLowerCase() : '';
              return location + (confidence ? ' · ' + confidence : '') + (reasons ? ' · ' + reasons : '');
            }

            function reliabilityBadgeHtml(item) {
              const model = targetReliabilityBadgeModel(item?.targetReliability);
              if (model.tone === 'muted') return '';
              return '<span class="ann-row-reliability" data-confidence="' + escapeHtml(model.confidence) + '">' +
                escapeHtml(model.label) +
                (model.scoreLabel ? ' · ' + escapeHtml(model.scoreLabel) : '') +
              '</span>';
            }

            function targetKindLabel(item) {
              const target = item?.target || {};
              if (target.type === 'semantics_node' || target.nodeUid) return 'Semantics node';
              if (target.type === 'visual_area' || target.boundsInWindow) return 'Visual region';
              return 'Unknown target';
            }

            function detailMetaHtml(item, index) {
              const time = item?.updatedAtEpochMillis || item?.createdAtEpochMillis || null;
              return '<div class="detail-meta">' +
                '<span>#' + escapeHtml(String(annotationDisplayNumber(item, index))) + '</span>' +
                '<span>' + escapeHtml(statusLabel(item)) + '</span>' +
                '<span>' + escapeHtml(time ? formatTime(time) : '-') + '</span>' +
              '</div>';
            }

            function evidenceGridHtml(rows) {
              return '<div class="evidence-grid">' +
                rows.map(row => '<span>' + escapeHtml(row[0]) + '</span><strong>' + escapeHtml(row[1]) + '</strong>').join('') +
              '</div>';
            }

            function targetDetailsHtml(item) {
              const node = item?.selectedNode || {};
              const bounds = itemBounds(item);
              return evidenceGridHtml([
                ['Target', targetLabel(item)],
                ['Kind', targetKindLabel(item)],
                ['Role', compactValue(node.role)],
                ['Text', compactValue([...(node.text || []), node.editableText].filter(Boolean))],
                ['Content description', compactValue(node.contentDescription)],
                ['Test tag', compactValue(node.testTag)],
                ['Bounds', bounds ? formatBounds(bounds) : '-'],
              ]);
            }

            function evidenceDetailsHtml(item) {
              const sourceCandidates = item?.sourceCandidates || [];
              const targetEvidence = item?.targetEvidence || {};
              const evidenceRows = [
                ['Source', sourceCandidateLine(sourceCandidates[0])],
                ['Identity', targetEvidence.identityHint?.stableLabel || targetEvidence.identityHint?.composableNameHint || '-'],
                ['Occurrence', targetEvidence.occurrence ? targetEvidence.occurrence.selectedOrdinal + ' of ' + targetEvidence.occurrence.count : '-'],
                ['Screenshots', compactValue(targetEvidence.screenshotKinds)],
              ];
              const candidates = sourceCandidates.slice(1, 3).map((candidate, index) => ['Source ' + (index + 2), sourceCandidateLine(candidate)]);
              const warnings = (targetEvidence.warnings || []).map((warning, index) => ['Warning ' + (index + 1), warning]);
              const reliability = item?.targetReliability || {};
              const hasReliability = Boolean(reliability.confidence || (reliability.warnings || []).length);
              const reliabilityRows = reliability.confidence
                ? [['Target confidence', String(reliability.confidence).toLowerCase()]]
                    .concat((reliability.warnings || []).map((warning, index) => ['Target warning ' + (index + 1), reliabilityWarningLabel(warning)]))
                : [];
              const bodyRows = evidenceRows.concat(reliabilityRows, candidates, warnings);
              const empty = sourceCandidates.length === 0 &&
                !targetEvidence.identityHint &&
                !targetEvidence.occurrence &&
                !targetEvidence.screenshotKinds?.length &&
                !hasReliability;
              return '<details class="evidence-details" open>' +
                '<summary>Evidence</summary>' +
                (empty
                  ? '<div class="empty-evidence">No source candidates or structured target evidence captured yet.</div>'
                  : evidenceGridHtml(bodyRows)) +
              '</details>';
            }

            function renderAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              pendingItems.innerHTML =
                '<div class="annotation-detail">' +
                  '<button type="button" class="annotation-back" data-back-annotations>← All annotations</button>' +
                  '<section class="annotation-section">' +
                    '<h3>Target</h3>' +
                    '<div class="annotation-field">' +
                      '<label for="annotationLabelInput">Label</label>' +
                      '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(annotationTitle(item, index)) + '">' +
                    '</div>' +
                    evidenceGridHtml([['Bounds', item.bounds ? formatBounds(item.bounds) : '-']]) +
                  '</section>' +
                  '<section class="annotation-section">' +
                    '<h3>Request</h3>' +
                    '<div class="annotation-field">' +
                      '<label for="annotationCommentInput">Comment</label>' +
                      '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtmlValue(item.comment) + '</textarea>' +
                    '</div>' +
                  '</section>' +
                  '<section class="annotation-section">' +
                    '<h3>Status</h3>' +
                    '<div class="annotation-field">' +
                      '<label>Severity</label>' +
                      '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                        ['high', 'med', 'low'].map(value =>
                          '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                            ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                            (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                            escapeHtml(value === 'med' ? 'Med' : value) +
                          '</button>'
                        ).join('') +
                      '</div>' +
                    '</div>' +
                    '<div class="annotation-field">' +
                      '<label>Status</label>' +
                      '<div class="annotation-segmented" role="group" aria-label="Status">' +
                        ['open', 'in-progress', 'resolved'].map(value =>
                          '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                            ' aria-pressed="' + (status === value ? 'true' : 'false') + '">' +
                            escapeHtml(statusValueLabel(value)) +
                          '</button>'
                        ).join('') +
                      '</div>' +
                    '</div>' +
                  '</section>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = document.getElementById('annotationLabelInput');
              const commentInput = document.getElementById('annotationCommentInput');
              labelInput.addEventListener('input', event => {
                item.label = event.target.value;
                persistCurrentPendingState();
                updateComposerState();
                renderPreviewOnly();
              });
              commentInput.addEventListener('input', event => {
                item.comment = event.target.value;
                persistCurrentPendingState();
                updateComposerState();
              });
              pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  item.severity = button.dataset.setSeverity;
                  persistCurrentPendingState();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  item.status = button.dataset.setStatus;
                  persistCurrentPendingState();
                  renderPreviewOnly();
                  renderInspectorRegion();
                  renderCurrentSessionList();
                });
              });
              pendingItems.querySelectorAll('[data-back-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  setDraftFocusIndex(null);
                  renderPreviewOnly();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelector('[data-delete-current]').addEventListener('click', () => {
                deletePendingFeedbackItem(index);
              });
            }

            function savedEvidenceGroups() {
              const groups = new Map();
              savedEvidenceItems().forEach(item => {
                const key = item.screenId;
                if (!groups.has(key)) groups.set(key, []);
                groups.get(key).push(item);
              });
              return Array.from(groups.entries()).map(entry => ({ screenId: entry[0], items: entry[1] }));
            }

            // Right-panel "Annotations" rows. Includes sent items so the panel count matches the
            // sidebar Session card. Each row carries its own status badge (Open/Resolved); rows tied
            // to delivered handoffs render the same as drafts here. Send/copy logic that must skip
            // already-sent items uses currentPromptAnnotations() instead.
            function savedEvidenceItems() {
              return state.session?.items || [];
            }

            function selectedSavedAnnotation() {
              const focused = toolMode.getState().focusedSavedItemId;
              if (!focused) return null;
              return savedEvidenceItems().find(item => item.itemId === focused) || null;
            }

            function renderSavedEvidenceOverlay(overlay, image, items) {
              const allSavedItems = savedEvidenceItems();
              const focused = toolMode.getState().focusedSavedItemId;
              items.forEach((item, index) => {
                const savedIndex = Math.max(0, allSavedItems.findIndex(savedItem => savedItem.itemId === item.itemId));
                const displayNumber = annotationDisplayNumber(item, savedIndex);
                renderOverlayBox(
                  overlay,
                  image,
                  boundsForTarget(item.target),
                  String(displayNumber),
                  false,
                  item.itemId === focused,
                  savedIndex,
                  '',
                  severityColor(annotationSeverity(item)),
                  () => focusSavedEvidenceItem(item.itemId)
                );
              });
            }

            function savedScreenOrdinalLookup() {
              const screens = state.session?.screens || [];
              const ordered = [...screens].sort((left, right) =>
                (left.capturedAtEpochMillis || 0) - (right.capturedAtEpochMillis || 0) ||
                String(left.screenId || '').localeCompare(String(right.screenId || ''))
              );
              const ordinalByScreenId = new Map();
              ordered.forEach((screen, index) => ordinalByScreenId.set(screen.screenId, index + 1));
              return ordinalByScreenId;
            }

            function savedScreenHeaderHtml(item, ordinalByScreenId, isFirst) {
              const screen = (state.session?.screens || []).find(s => s.screenId === item.screenId);
              const ordinal = ordinalByScreenId.get(item.screenId) || ordinalByScreenId.size + 1;
              const time = screen?.capturedAtEpochMillis ? formatTime(screen.capturedAtEpochMillis) : '-';
              return '<div class="ann-screen-header' + (isFirst ? ' first' : '') + '">' +
                escapeHtml('Screen ' + ordinal + ' · ' + time) +
              '</div>';
            }

            function renderSavedEvidenceGroups() {
              const items = savedEvidenceItems();
              const selected = selectedSavedAnnotation();
              if (selected) {
                renderSavedAnnotationDetail(selected, items.findIndex(item => item.itemId === selected.itemId));
                return;
              }
              if (items.length) {
                const ordinalByScreenId = savedScreenOrdinalLookup();
                let prevScreenId = null;
                const rows = items.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const phase = lifecyclePhase(item);
                  const color = severityColor(annotationSeverity(item));
                  const displayNumber = annotationDisplayNumber(item, index);
                  let header = '';
                  if (item.screenId !== prevScreenId) {
                    header = savedScreenHeaderHtml(item, ordinalByScreenId, prevScreenId === null);
                    prevScreenId = item.screenId;
                  }
                  return header +
                    '<button type="button" class="ann-row saved-item-row ' + (item.itemId === toolMode.getState().focusedSavedItemId ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '" data-item-id="' + escapeHtml(item.itemId) + '" data-focus-saved="' + escapeHtml(item.itemId) + '">' +
                      '<span class="ann-row-num" style="background:' + color + '">' + escapeHtml(String(displayNumber)) + '</span>' +
                      '<span class="ann-row-body">' +
                        '<span class="ann-row-title">' + escapeHtml(targetLabel(item)) + '</span>' +
                        '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                        reliabilityBadgeHtml(item) +
                      '</span>' +
                      '<span class="ann-row-status ' + statusClass(item) + '">' + escapeHtml(statusLabel(item)) + '</span>' +
                    '</button>';
                }).join('');
                draftItems.innerHTML = '<div class="ann-list">' + rows + '</div>';
              } else {
                draftItems.innerHTML = '<div class="empty-state"><div class="empty-title">No saved annotations yet.</div><div class="empty-body">Use <b>Annotate</b> to freeze the preview and add comments.</div>' + startAnnotatingButtonHtml() + '</div>';
              }
              draftItems.querySelectorAll('[data-focus-saved]').forEach(button => {
                button.addEventListener('click', () => focusSavedEvidenceItem(button.dataset.focusSaved));
              });
              bindStartAnnotatingButtons(draftItems);
            }

            function renderSavedAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              const editSessionId = toolMode.getState().focusedSavedSessionId || state.session?.sessionId || null;
              const phase = lifecyclePhase(item);
              const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified' || phase === 'needs_clarification';
              const dis = editable ? '' : ' disabled';
              const deletable = phase !== 'in_progress' && phase !== 'resolved' && phase !== 'wont_fix';
              const deleteDis = deletable ? '' : ' disabled';
              const banner = (() => {
                if (phase === 'sent_modified') {
                  return '<div class="annotation-banner annotation-banner-warn">' +
                    '<span>⚠ Modified after Save — agent will see the previous version.</span>' +
                    '<button type="button" class="annotation-resave" data-resave-current>Re-save</button>' +
                    '</div>';
                }
                if (phase === 'sent') {
                  const ts = item.lastHandedOffAtEpochMillis ? formatTime(item.lastHandedOffAtEpochMillis) : '';
                  return '<div class="annotation-banner annotation-banner-info">' +
                    'Sent to MCP' + (ts ? ' · ' + escapeHtml(ts) : '') +
                    '. Modify to refine before agent picks up.' +
                    '</div>';
                }
                if (phase === 'in_progress') {
                  const note = item.agentSummary ? '<pre class="annotation-summary">' + escapeHtml(item.agentSummary) + '</pre>' : '';
                  return '<div class="annotation-banner annotation-banner-locked">' +
                    '<div>🔒 Agent working on this — edits locked.</div>' +
                    note +
                    '</div>';
                }
                if (phase === 'needs_clarification') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no detail provided)';
                  return '<div class="annotation-banner annotation-banner-question">' +
                    '<div>? Agent needs clarification</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '<div class="annotation-banner-subtle">Edits remain enabled. Re-save after updating.</div>' +
                    '</div>';
                }
                if (phase === 'wont_fix') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no summary provided)';
                  return '<div class="annotation-banner annotation-banner-wont-fix">' +
                    '<div>× Agent marked won\'t fix</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '</div>';
                }
                if (phase === 'resolved') {
                  const summary = item.agentSummary ? escapeHtml(item.agentSummary) : '(no summary provided)';
                  return '<div class="annotation-banner annotation-banner-done">' +
                    '<div>✓ Agent completed</div>' +
                    '<pre class="annotation-summary">' + summary + '</pre>' +
                    '</div>';
                }
                return '';
              })();
              draftItems.innerHTML =
                '<div class="annotation-detail" data-phase="' + escapeHtml(phase) + '">' +
                  '<button type="button" class="annotation-back" data-back-saved-annotations>← All annotations</button>' +
                  banner +
                  '<section class="annotation-section request-section">' +
                    '<h3>Request</h3>' +
                    detailMetaHtml(item, index) +
                    '<div class="annotation-field">' +
                      '<label for="annotationCommentInput">Comment</label>' +
                      '<textarea id="annotationCommentInput" class="annotation-textarea"' + dis + '>' + escapeHtmlValue(item.comment) + '</textarea>' +
                    '</div>' +
                    '<div class="annotation-field">' +
                      '<label>Severity</label>' +
                      '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                        ['high', 'med', 'low'].map(value =>
                          '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                            ' aria-pressed="' + (severity === value ? 'true' : 'false') + '"' +
                            (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + dis + '>' +
                            escapeHtml(value === 'med' ? 'Med' : value) +
                          '</button>'
                        ).join('') +
                      '</div>' +
                    '</div>' +
                    '<div class="annotation-field">' +
                      '<label>Status</label>' +
                      '<div class="annotation-segmented" role="group" aria-label="Status">' +
                        ['open', 'in-progress', 'resolved'].map(value =>
                          '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '"' +
                            ' aria-pressed="' + (status === value ? 'true' : 'false') + '"' + dis + '>' +
                            escapeHtml(statusValueLabel(value)) +
                          '</button>'
                        ).join('') +
                      '</div>' +
                    '</div>' +
                  '</section>' +
                  '<section class="annotation-section target-section">' +
                    '<h3>Target</h3>' +
                    '<div class="annotation-field">' +
                      '<label for="annotationLabelInput">Label</label>' +
                      '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(targetLabel(item)) + '"' + dis + '>' +
                    '</div>' +
                    targetDetailsHtml(item) +
                  '</section>' +
                  '<section class="annotation-section evidence-section">' +
                    evidenceDetailsHtml(item) +
                  '</section>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current' + deleteDis + '>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-saved-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = draftItems.querySelector('#annotationLabelInput');
              const commentInput = draftItems.querySelector('#annotationCommentInput');
              labelInput.addEventListener('input', event => {
                if (!editable) return;
                item.label = event.target.value;
                updateComposerState();
                renderPreviewOnly();
              });
              labelInput.addEventListener('change', () => {
                if (!editable) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('input', event => {
                if (!editable) return;
                item.comment = event.target.value;
                updateComposerState();
              });
              commentInput.addEventListener('change', () => {
                if (!editable) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              commentInput.addEventListener('blur', event => {
                if (!editable) return;
                if (event.relatedTarget?.hasAttribute?.('data-back-saved-annotations')) return;
                persistSavedEvidenceItem(item, editSessionId).catch(showError);
              });
              draftItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  if (!editable) return;
                  item.severity = button.dataset.setSeverity;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => renderInspectorRegion())
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  if (!editable) return;
                  item.status = button.dataset.setStatus;
                  persistSavedEvidenceItem(item, editSessionId)
                    .then(() => {
                      renderPreviewOnly();
                      renderInspectorRegion();
                    })
                    .catch(showError);
                });
              });
              draftItems.querySelectorAll('[data-resave-current]').forEach(button => {
                button.addEventListener('click', async () => {
                  try {
                    const result = await requestJson('/api/agent-handoffs', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ itemIds: [item.itemId] }),
                    });
                    setConsoleSession(result.session);
                    renderInspectorRegion();
                    renderPreviewOnly();
                    showSuccess('Re-saved to MCP ✓', 2000);
                  } catch (error) {
                    showError(error);
                  }
                });
              });
              draftItems.querySelectorAll('[data-back-saved-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  // Navigation must always succeed; persist is best-effort.
                  // Editable phases (draft/sent/sent_modified/needs_clarification) attempt persist;
                  // non-editable phases (in_progress/resolved/wont_fix) skip persist. The server would
                  // reject a PATCH on non-editable items with ITEM_NOT_EDITABLE — we should
                  // still let the user leave the detail view.
                  const goBack = () => {
                    toolMode.focusSavedItem(null, editSessionId, item.screenId || null);
                    renderPreviewOnly();
                    renderInspectorRegion();
                  };
                  goBack();
                  if (editable) {
                    persistSavedEvidenceItem(item, editSessionId).catch(showError);
                  }
                });
              });
              const deleteButton = draftItems.querySelector('[data-delete-current]');
              if (deleteButton) {
                deleteButton.addEventListener('click', () => {
                  if (!deletable) return;
                  deleteSavedEvidenceItem(item.itemId, editSessionId).catch(showError);
                });
              }
            }
