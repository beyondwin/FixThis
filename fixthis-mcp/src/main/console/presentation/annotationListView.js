// @requires annotations.js, presentation/annotationDetailView.js
            function emptySessionsHtml() {
              return '<div class="empty-state"><div class="empty-title">No saved sessions.</div></div>';
            }

            function startAnnotatingButtonHtml() {
              if (toolMode.isAnnotateMode()) return '';
              const previewReady = Boolean(state.preview);
              return '<button type="button" class="primary" data-start-annotating>Start annotating</button>'
                + (previewReady ? '' : '<div class="empty-hint">Open the app to capture a screenshot before annotating.</div>');
            }

            function renderPendingItems() {
              if (draftFocusIndex() != null && selectedAnnotation()) {
                renderAnnotationDetail(selectedAnnotation(), draftFocusIndex());
                return;
              }
              // SIF-6: inline activity-drift warning + restart button. Visible
              // only while an draftFlow() is active and the most recent
              // checkActivityDrift() result reported drift=true.
              const driftWarningHtml = (draftFlow() && draftFlow().activityDriftWarning && draftFlow().activityDriftWarning.drift)
                ? '<div class="activity-drift-warning" role="status" aria-live="polite" data-activity-drift>' +
                    '<div class="activity-drift-warning-body">' +
                      '<div class="activity-drift-warning-title">Activity changed during freeze</div>' +
                      '<div class="activity-drift-warning-detail">Frozen: ' + escapeHtml(String(draftFlow().activityDriftWarning.expected)) + ' · Now: ' + escapeHtml(String(draftFlow().activityDriftWarning.actual)) + '</div>' +
                    '</div>' +
                    '<button type="button" class="activity-drift-warning-button" data-activity-drift-restart>Start new freeze</button>' +
                  '</div>'
                : '';
              pendingItems.innerHTML = driftWarningHtml + (draftItemList().length
                ? '<div class="ann-list">' + draftItemList().map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const phase = lifecyclePhase(item);
                  const color = severityColor(annotationSeverity(item));
                  const pendingItemIdAttr = item.itemId ? ' data-item-id="' + escapeHtml(item.itemId) + '"' : '';
                  return '<button type="button" class="ann-row pending-item-row ' + (index === draftFocusIndex() ? 'active' : '') + '" style="--annotation-color:' + color + '" data-phase="' + escapeHtml(phase) + '"' + pendingItemIdAttr + ' data-focus-pending="' + index + '">' +
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
                  resetComposer(true);
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
