// @requires state.js, annotations.js, draftWorkspace.js
            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false, isFocused = false, annotationIndex = null, extraClass = '', color = null, selectHandler = focusPendingFeedbackItem) {
              if (!bounds) return;
              const left = bounds.left * 100 / image.naturalWidth;
              const top = bounds.top * 100 / image.naturalHeight;
              const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
              const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
              const box = document.createElement('div');
              box.className = 'selection-box' + (isDragPreview ? ' drag-preview' : '') + (isFocused ? ' focused' : '') + (annotationIndex == null ? '' : ' annotation-pin') + (extraClass ? ' ' + extraClass : '');
              box.style.left = left + '%';
              box.style.top = top + '%';
              box.style.width = width + '%';
              box.style.height = height + '%';
              if (color) {
                box.style.setProperty('--selection-color', color);
                box.style.setProperty('--selection-fill', colorWithAlpha(color, .12));
                box.style.setProperty('--selection-fill-strong', colorWithAlpha(color, .22));
                box.style.setProperty('--selection-halo', colorWithAlpha(color, .24));
              }
              if (annotationIndex != null) {
                box.setAttribute('role', 'button');
                box.setAttribute('aria-label', 'Select annotation ' + (annotationIndex + 1));
                box.tabIndex = 0;
                box.addEventListener('click', event => {
                  event.stopPropagation();
                  selectHandler(annotationIndex);
                });
                box.addEventListener('keydown', event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  selectHandler(annotationIndex);
                });
              }
              overlay.appendChild(box);

              if (!labelText) return;
              const label = document.createElement('div');
              label.className = 'selection-label' + (isFocused ? ' focused' : '');
              label.style.left = left + '%';
              label.style.top = top + '%';
              if (color) {
                label.style.setProperty('--selection-color', color);
                label.style.setProperty('--selection-halo', colorWithAlpha(color, .24));
              }
              label.textContent = labelText;
              overlay.appendChild(label);
            }

            function renderNumberedFeedbackOverlay(overlay, image) {
              draftWorkspaceItems(draftWorkspace).forEach((item, index) => {
                const displayNumber = index + 1;
                renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false, index === focusedPendingItemIndex, index, '', severityColor(annotationSeverity(item)));
              });
            }

            function annotationDisplayNumber(item, index) {
              return item?.sequenceNumber ?? (index + 1);
            }

            function renderSelectionOverlay() {
              const overlay = document.getElementById('selectionOverlay');
              const image = document.getElementById('snapshotImage');
              if (!overlay) {
                updateComposerState();
                return;
              }
              overlay.innerHTML = '';
              if (!image) {
                updateComposerState();
                return;
              }
              if (!image.naturalWidth || !image.naturalHeight) {
                image.addEventListener('load', renderSelectionOverlay, { once: true });
                updateComposerState();
                return;
              }

              renderNumberedFeedbackOverlay(overlay, image);
              const toolModeState = toolModeUseCases.getState();
              if (!activeDraftFlow) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === toolModeState.focusedSavedItemId);
                const savedScreenId = focusedItem?.screenId || toolModeState.focusedSavedScreenId;
                const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === savedScreenId);
                if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
              }
              if (currentSelection) {
                renderOverlayBox(overlay, image, currentSelection.bounds, currentSelection.label);
              }
              if (activeDraftFlow && toolModeUseCases.isAnnotateMode() && toolModeState.hoveredTarget && !toolModeState.drag?.preview) {
                renderOverlayBox(overlay, image, toolModeState.hoveredTarget.bounds, null, false, false, null, 'hover-preview');
              }
              if (toolModeState.drag?.preview) {
                renderOverlayBox(overlay, image, toolModeState.drag.preview, null, true);
              }
              updateComposerState();
            }


            function emptySessionsHtml() {
              return '<div class="empty-state"><div class="empty-title">No saved sessions.</div></div>';
            }

            function startAnnotatingButtonHtml() {
              if (toolModeUseCases.isAnnotateMode()) return '';
              return '<button type="button" class="primary" data-start-annotating>Start annotating</button>';
            }

            function renderPendingItems() {
              if (focusedPendingItemIndex != null && selectedAnnotation()) {
                renderAnnotationDetail(selectedAnnotation(), focusedPendingItemIndex);
                return;
              }
              // SIF-6: inline activity-drift warning + restart button. Visible
              // only while an activeDraftFlow is active and the most recent
              // checkActivityDrift() result reported drift=true.
              const driftWarningHtml = (activeDraftFlow && activeDraftFlow.activityDriftWarning && activeDraftFlow.activityDriftWarning.drift)
                ? '<div class="activity-drift-warning" role="status" aria-live="polite" data-activity-drift>' +
                    '<div class="activity-drift-warning-body">' +
                      '<div class="activity-drift-warning-title">Activity changed during freeze</div>' +
                      '<div class="activity-drift-warning-detail">Frozen: ' + escapeHtml(String(activeDraftFlow.activityDriftWarning.expected)) + ' · Now: ' + escapeHtml(String(activeDraftFlow.activityDriftWarning.actual)) + '</div>' +
                    '</div>' +
                    '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>' +
                  '</div>'
                : '';
              pendingItems.innerHTML = driftWarningHtml + (draftFeedbackItems.length
                ? '<div class="ann-list">' + draftFeedbackItems.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const phase = lifecyclePhase(item);
                  const color = severityColor(annotationSeverity(item));
                  const pendingItemIdAttr = item.itemId ? ' data-item-id="' + escapeHtml(item.itemId) + '"' : '';
                  return '<button type="button" class="ann-row pending-item-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '"' + pendingItemIdAttr + ' data-focus-pending="' + index + '">' +
                    '<span class="ann-row-num" style="background:' + severityColor(annotationSeverity(item)) + '">' + (index + 1) + '</span>' +
                    '<span class="ann-row-body">' +
                      '<span class="ann-row-title">' + escapeHtml(annotationTitle(item, index)) + '</span>' +
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                      reliabilityBadgeHtml(item) +
                    '</span>' +
                    '<span class="ann-row-status ' + statusClass(item) + '">' + escapeHtml(statusLabel(item)) + '</span>' +
                  '</button>';
                }).join('') + '</div>'
                : '<div class="empty-state"><div class="empty-title">No annotations yet.</div><div class="empty-body">Switch to <b>Annotate</b>, then click or drag on the preview.</div>' + startAnnotatingButtonHtml() + '</div>');
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              const driftRestartButton = pendingItems.querySelector('[data-activity-drift-restart]');
              if (driftRestartButton) {
                driftRestartButton.addEventListener('click', () => {
                  // SIF-6: discard the stale freeze and start a fresh one.
                  resetCanonicalAnnotationComposerState(true);
                  startDraftAnnotationFlow().catch(showError);
                });
              }
              bindStartAnnotatingButtons(pendingItems);
            }

            function bindStartAnnotatingButtons(container) {
              container.querySelectorAll('[data-start-annotating]').forEach(button => {
                button.addEventListener('click', () => enterAnnotateMode().catch(showError));
              });
            }

            function focusCommentInputAtEnd(commentInput) {
              commentInput.focus();
              const end = commentInput.value.length;
              commentInput.setSelectionRange(end, end);
            }

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

            function reliabilityWarningLabel(warning) {
              const value = String(warning || '').toLowerCase();
              if (value === 'possible_view_interop') return 'Possible AndroidView/WebView';
              if (value === 'no_meaningful_compose_target') return 'No Compose target';
              if (value === 'visual_area_only') return 'Visual only';
              if (value === 'low_source_candidate_margin') return 'Low source margin';
              if (value === 'source_index_stale') return 'Stale source';
              if (value === 'screen_fingerprint_mismatch_forced') return 'Forced screen mismatch';
              if (value === 'screen_fingerprint_unavailable') return 'No fingerprint';
              if (value === 'sensitive_text_redacted') return 'Redacted';
              return value.replaceAll('_', ' ');
            }

            function reliabilityBadgeHtml(item) {
              const reliability = item?.targetReliability;
              if (!reliability || !reliability.confidence || reliability.confidence === 'UNKNOWN') return '';
              const confidence = String(reliability.confidence).toLowerCase();
              const warnings = reliability.warnings || [];
              const label = warnings.length ? reliabilityWarningLabel(warnings[0]) : confidence;
              return '<span class="ann-row-reliability" data-confidence="' + escapeHtml(confidence) + '">' +
                escapeHtml(label) +
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
                  focusedPendingItemIndex = null;
                  renderPreviewOnly();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelector('[data-delete-current]').addEventListener('click', () => {
                deletePendingFeedbackItem(index);
              });
              focusCommentInputAtEnd(commentInput);
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
              const focused = toolModeUseCases.getState().focusedSavedItemId;
              if (!focused) return null;
              return savedEvidenceItems().find(item => item.itemId === focused) || null;
            }

            function renderSavedEvidenceOverlay(overlay, image, items) {
              const allSavedItems = savedEvidenceItems();
              const focused = toolModeUseCases.getState().focusedSavedItemId;
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
                    '<button type="button" class="ann-row saved-item-row ' + (item.itemId === toolModeUseCases.getState().focusedSavedItemId ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '" data-item-id="' + escapeHtml(item.itemId) + '" data-focus-saved="' + escapeHtml(item.itemId) + '">' +
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
              const editSessionId = toolModeUseCases.getState().focusedSavedSessionId || state.session?.sessionId || null;
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
              commentInput.addEventListener('blur', () => {
                if (!editable) return;
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
                    toolModeUseCases.focusSavedItem(null, editSessionId, item.screenId || null);
                    renderPreviewOnly();
                    renderInspectorRegion();
                  };
                  if (!editable) {
                    goBack();
                  } else {
                    persistSavedEvidenceItem(item, editSessionId)
                      .then(goBack)
                      .catch(error => { showError(error); goBack(); });
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
              if (editable) focusCommentInputAtEnd(commentInput);
            }


            function workflowActiveStep() {
              if (!state.connection?.hasEverConnected && userConnectionState(state.connection?.current) !== 'ready') return 'connect';
              if (activeDraftFlow || toolModeUseCases.isAnnotateMode()) return 'annotate';
              if (currentPromptAnnotations().length > 0) return 'handoff';
              return 'preview';
            }

            function workflowStepOrder(step) {
              return ['connect', 'preview', 'annotate', 'handoff'].indexOf(step);
            }

            function renderWorkflowProgress() {
              if (!workflowProgress) return;
              const active = workflowActiveStep();
              const activeOrder = workflowStepOrder(active);
              workflowProgress.querySelectorAll('[data-workflow-step]').forEach(node => {
                const step = node.getAttribute('data-workflow-step');
                const order = workflowStepOrder(step);
                node.dataset.state = order < activeOrder ? 'complete' : (step === active ? 'active' : 'upcoming');
              });
            }

            function renderSessionRegions() {
              renderCurrentSessionList();
            }

            function renderComposerInspector() {
              const item = selectedAnnotation();
              const savedItems = savedEvidenceItems();
              inspectorTitle.textContent = item ? 'Annotation' : 'Annotations';
              inspectorCount.textContent = String(draftFeedbackItems.length + savedItems.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = false;
              draftItems.hidden = savedItems.length === 0;
              if (savedSectionHeader) savedSectionHeader.hidden = savedItems.length === 0;
              inspectorFooter.hidden = savedItems.length === 0;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = savedItems.length === 0;
              renderPendingItems();
              if (savedItems.length) renderSavedEvidenceGroups();
            }

            function renderSavedAnnotationsInspector() {
              const items = savedEvidenceItems();
              inspectorTitle.textContent = 'Annotations';
              inspectorCount.textContent = String(items.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = true;
              draftItems.hidden = false;
              if (savedSectionHeader) savedSectionHeader.hidden = true;
              inspectorFooter.hidden = false;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = items.length === 0;
              renderSavedEvidenceGroups();
            }

            function renderInspectorRegion() {
              if (activeDraftFlow) {
                renderComposerInspector();
              } else {
                renderSavedAnnotationsInspector();
              }
              updateComposerState();
            }

            function ensurePreviewFrame() {
              let frame = document.getElementById('snapshotFrame');
              if (frame) return frame;
              snapshot.innerHTML =
	                '<div id="annotateHintSlot" class="annotate-hint-slot" aria-live="polite"></div>' +
	                '<div id="snapshotFrame" class="snapshot-frame">' +
                    '<span id="previewFrameStatus" class="preview-frame-status" data-state="unavailable">No screenshot</span>' +
	                  '<img id="snapshotImage" alt="FixThis preview" aria-label="FixThis preview">' +
	                  '<div id="selectionOverlay" class="selection-overlay"></div>' +
	                '</div>';
              attachSnapshotHandlers();
              applyPreviewZoom();
              return document.getElementById('snapshotFrame');
            }

            function previewFrameStatus(screen, hasScreenshot) {
              if (state.connection?.interactionBlockedReason) return { state: 'blocked', label: 'Interaction blocked' };
              if (!hasScreenshot) return { state: 'unavailable', label: 'No screenshot' };
              if (state.preview?.stale) return { state: 'stale', label: 'Stale frame' };
              if (activeDraftFlow) return { state: 'frozen', label: 'Frozen for annotation' };
              if (toolModeUseCases.getState().focusedSavedItemId || toolModeUseCases.getState().focusedSavedScreenId || (!state.preview && screen?.screenId)) return { state: 'saved', label: 'Saved screen' };
              if (state.preview) return { state: 'live', label: 'Live preview' };
              return { state: 'unavailable', label: 'No screenshot' };
            }

	            function renderPreviewFrameStatus(screen, hasScreenshot) {
	              const status = previewFrameStatus(screen, hasScreenshot);
	              let badge = document.getElementById('previewFrameStatus');
	              if (!badge) return;
	              badge.dataset.state = status.state;
	              badge.textContent = status.label;
	            }

	            function renderDraftLockBar(canvasModel = null) {
	              const root = document.getElementById('draftLockBar');
	              if (!root) return;
	              const isDraft = canvasModel ? canvasModel.mode === 'frozenDraft' : Boolean(activeDraftFlow);
	              root.hidden = !isDraft;
	              if (!isDraft) {
	                root.textContent = '';
	                return;
	              }
	              root.textContent = canvasModel?.lockLabel || (
	                'Locked: Session ' + (activeDraftFlow?.context?.sessionId || state.session?.sessionId || 'current') +
	                ' · Preview ' + (activeDraftFlow?.previewId || activeDraftFlow?.context?.previewId || 'frozen') +
	                ' · Live preview paused'
	              );
	            }

	            function renderBoundaryFromModel(boundary) {
	              const root = document.getElementById('sessionBoundarySheet');
	              if (!root) return;
	              root.hidden = !boundary;
	              if (!boundary) return;
	              root.querySelector('[data-boundary-title]').textContent = boundary.title;
	              root.querySelector('[data-boundary-summary]').textContent =
	                boundary.draftSummary.itemCount + ' draft annotations · ' + boundary.draftSummary.missingCommentCount + ' missing comments';
	              root.querySelectorAll('[data-boundary-action]').forEach((button, index) => {
	                const action = boundary.actions[index];
	                button.hidden = !action;
	                if (action) {
	                  button.textContent = action.label;
	                  button.onclick = () => {
	                    if (typeof consoleStore !== 'undefined') consoleStore.dispatch({ type: action.type });
	                  };
	                }
	              });
	            }

            function renderPreviewRegion() {
              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              const mode = activeDraftFlow ? 'frozen' : (state.preview ? 'live' : (screen ? 'frozen' : 'idle'));
              snapshot.dataset.toolMode = toolModeUseCases.isAnnotateMode() ? 'annotate' : 'select';
              if (!hasScreenshot) {
                const emptyMessage = screen
                  ? 'No screenshot artifact for this preview.'
                  : (state.session
                    ? 'Waiting for first capture from device…'
                    : 'Connect a device to get started.');
                snapshot.innerHTML = '<div class="empty-stage"><span id="previewFrameStatus" class="preview-frame-status empty" data-state="unavailable">No screenshot</span><span>' + emptyMessage + '</span></div>';
                renderPreviewFrameStatus(screen, hasScreenshot);
                updateComposerState();
                // Even with no screenshot, surface the blocked-reason overlay and
                // stale-frame notice so users see WHY there's no capture yet.
	                renderCanvasBlockedOverlay();
	                renderStaleFrameNotice();
	                renderDraftLockBar();
	                return;
	              }
              const frame = ensurePreviewFrame();
              frame.dataset.mode = mode;
              renderPreviewFrameStatus(screen, hasScreenshot);
              const image = document.getElementById('snapshotImage');
              const src = screenImageUrl(screen);
              if (image.getAttribute('src') !== src) {
                image.setAttribute('src', src);
              }
              const hintSlot = document.getElementById('annotateHintSlot');
              let hint = document.getElementById('annotateHint');
              if (toolModeUseCases.isAnnotateMode()) {
                if (!hint) {
                  hint = document.createElement('div');
                  hint.id = 'annotateHint';
                  hint.className = 'annotate-hint';
                  hintSlot.appendChild(hint);
                }
                hint.textContent = 'Annotate mode';
              } else if (hint) {
                hint.remove();
              }
	              renderSelectionOverlay();
	              renderCanvasBlockedOverlay();
	              renderStaleFrameNotice();
	              renderDraftLockBar();
	            }

            function renderPreviewOnly() {
              renderPreviewRegion();
              renderSelectionOverlay();
              updateComposerState();
              renderWorkflowProgress();
            }

            function render() {
              renderSessionRegions();
              renderPreviewRegion();
              renderInspectorRegion();
              renderConnection(state.connection.current);
              renderWorkflowProgress();
              updateComposerState();
            }

            function renderCanonicalHistoryModel(model) {
              if (model) renderCurrentSessionList();
            }

            function renderCanonicalCanvasModel(model) {
              if (model) renderPreviewOnly();
            }

            function renderCanonicalInspectorModel(model) {
              if (model) renderInspectorRegion();
            }

            function renderCanonicalPromptModel(model) {
              if (model) renderPromptReadiness();
            }

            function renderCanonicalBoundaryModel(model) {
              renderBoundaryFromModel(model);
            }

            function attachSnapshotHandlers() {
              const image = document.getElementById('snapshotImage');
              if (!image) return;
              image.draggable = false;
              image.addEventListener('dragstart', event => event.preventDefault());
              image.addEventListener('click', event => {
                try {
                  if (toolModeUseCases.getState().draftFlowStarting) {
                    event.preventDefault();
                    return;
                  }
                  if (toolModeUseCases.getState().suppressNextClick) {
                    toolModeUseCases.setSuppressNextClick(false);
                    return;
                  }
                  if (toolModeUseCases.isSelectMode() && activeDraftFlow) {
                    clearSelection();
                    return;
                  }
                  if (!activeDraftFlow) {
                    const point = naturalPointFromEvent(event, image);
                    navigate('tap', { x: point.x, y: point.y }).catch(showError);
                    return;
                  }
                  if (toolModeUseCases.isAnnotateMode() && !toolModeUseCases.isDragging()) {
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointerdown', event => {
                if (toolModeUseCases.getState().draftFlowStarting) {
                  event.preventDefault();
                  return;
                }
                if (!activeDraftFlow || !toolModeUseCases.isAnnotateMode()) return;
                try {
                  image.setPointerCapture?.(event.pointerId);
                  const point = naturalPointFromEvent(event, image);
                  toolModeUseCases.startDrag(point);
                  toolModeUseCases.updateDragPreview(normalizeBounds(point, point));
                  renderSelectionOverlay();
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointermove', event => {
                if (!activeDraftFlow || !toolModeUseCases.isAnnotateMode()) return;
                try {
                  const dragState = toolModeUseCases.getState().drag;
                  if (!dragState) {
                    previewNodeAtPoint(event, image);
                    return;
                  }
                  toolModeUseCases.updateDragPreview(normalizeBounds(dragState.start, naturalPointFromEvent(event, image)));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                const dragState = toolModeUseCases.getState().drag;
                if (!activeDraftFlow || !toolModeUseCases.isAnnotateMode() || !dragState) return;
                try {
                  const end = naturalPointFromEvent(event, image);
                  const bounds = normalizeBounds(dragState.start, end);
                  clearDragState();
                  releaseSnapshotPointerCapture(image, event);
                  if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
                    toolModeUseCases.setSuppressNextClick(true);
                    finishAreaSelection(bounds);
	                  } else {
	                    toolModeUseCases.setSuppressNextClick(true);
	                    renderSelectionOverlay();
	                    confirmHoveredAnnotationTarget(event, image);
	                  }
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointercancel', clearDragState);
              image.addEventListener('pointercancel', clearHoverPreview);
              image.addEventListener('lostpointercapture', clearDragState);
              image.addEventListener('lostpointercapture', clearHoverPreview);
              image.addEventListener('pointerleave', clearHoverPreview);
            }

            const BULK_CHANGE_HIGHLIGHT_THRESHOLD = 6;

            function mergeSessionIntoState(fresh) {
              const previous = state.session;
              const preservedComment = comment.value;
              const toolModeStateBefore = toolModeUseCases.getState();
              const preservedFocusedSavedItemId = toolModeStateBefore.focusedSavedItemId;
              const preservedFocusedSavedSessionId = toolModeStateBefore.focusedSavedSessionId;
              const preservedFocusedSavedScreenId = toolModeStateBefore.focusedSavedScreenId;
              const preservedFocusedPendingIndex = focusedPendingItemIndex;
              const preservedSelection = currentSelection;

              setConsoleSession(fresh);

              comment.value = preservedComment;
              const freshItems = fresh?.items || [];
              const freshScreens = fresh?.screens || [];
              const focusedItem = freshItems.find(item => item.itemId === preservedFocusedSavedItemId);
              const nextSavedScreenId = focusedItem?.screenId || preservedFocusedSavedScreenId;
              const savedScreenStillExists = nextSavedScreenId && freshScreens.some(screen => screen.screenId === nextSavedScreenId);
              toolModeUseCases.focusSavedItem(
                focusedItem ? preservedFocusedSavedItemId : null,
                savedScreenStillExists ? preservedFocusedSavedSessionId : null,
                savedScreenStillExists ? nextSavedScreenId : null
              );
              focusedPendingItemIndex = preservedFocusedPendingIndex;
              currentSelection = preservedSelection;

              // Compute which items actually changed status since last tick.
              const previousStatusById = new Map(
                (previous?.items || []).map(item => [item.itemId, item.status])
              );
              const changed = (fresh.items || []).filter(item => {
                const before = previousStatusById.get(item.itemId);
                return before && before !== item.status;
              });

              // Bulk-change guard: skip highlight cascade for ticks with too many transitions.
              if (changed.length >= BULK_CHANGE_HIGHLIGHT_THRESHOLD) return;

              // Per-item highlight (existing logic, unchanged).
              changed.forEach(item => {
                const changedItemId = item.itemId;
                requestAnimationFrame(() => {
                  document.querySelectorAll('[data-item-id="' + CSS.escape(changedItemId) + '"]').forEach(node => {
                    node.setAttribute('data-just-changed', 'true');
                    setTimeout(() => node.removeAttribute('data-just-changed'), 800);
                  });
                });
              });
            }
