            function isInteractionBlocked() {
              return Boolean(state.connection?.interactionBlockedReason);
            }

            function boundsForTarget(target) {
              return target?.boundsInWindow || null;
            }

            function targetLabel(item) {
              if (String(item?.label || '').trim()) return item.label;
              const target = item?.target || {};
              if (target.type === 'semantics_node' || target.nodeUid) {
                return item.selectedNode ? componentLabel(item.selectedNode) : 'Component target';
              }
              if (target.type === 'visual_area' || target.boundsInWindow) return 'Custom area';
              return 'Unknown target';
            }

            function pendingTargetLabel(item) {
              return item.targetType === 'node' ? 'Component target' : 'Custom area';
            }

            function annotationTitle(item, index) {
              return item.label || pendingTargetLabel(item) + ' #' + (index + 1);
            }

            function annotationSeverity(item) {
              return item.severity || 'med';
            }

            function annotationStatus(item) {
              return String(item.status || 'open').replace('_', '-');
            }

            function severityColor(severity) {
              if (severity === 'high') return '#f26d6d';
              if (severity === 'low') return '#5bb4e8';
              return '#e6b45a';
            }

            function colorWithAlpha(color, alpha) {
              const match = String(color || '').match(/^#([0-9a-f]{6})$/i);
              if (!match) return color;
              const hex = match[1];
              const red = parseInt(hex.slice(0, 2), 16);
              const green = parseInt(hex.slice(2, 4), 16);
              const blue = parseInt(hex.slice(4, 6), 16);
              return 'rgba(' + red + ', ' + green + ', ' + blue + ', ' + alpha + ')';
            }

            function lifecyclePhase(item) {
              const status = String(item?.status || 'open').replace('-', '_');
              if (status === 'resolved') return 'resolved';
              if (status === 'wont_fix') return 'wont_fix';
              if (status === 'needs_clarification') return 'needs_clarification';
              if (status === 'in_progress') return 'in_progress';
              if (item?.delivery === 'sent') {
                return item?.staleAfterHandoff ? 'sent_modified' : 'sent';
              }
              return 'draft';
            }

            function statusLabel(item) {
              switch (lifecyclePhase(item)) {
                case 'resolved': return 'Resolved';
                case 'wont_fix': return 'Won\'t Fix';
                case 'needs_clarification': return 'Needs Clarification';
                case 'in_progress': return 'In Progress';
                case 'sent_modified': return 'Sent · Modified';
                case 'sent': return 'Sent';
                default: return 'Draft';
              }
            }

            function statusClass(item) {
              return 'st-' + lifecyclePhase(item).replace('_', '-');
            }

            function statusValueLabel(value) {
              const normalized = String(value || 'open').replace('-', '_');
              if (normalized === 'in_progress') return 'In Progress';
              if (normalized === 'needs_clarification') return 'Needs Clarification';
              if (normalized === 'wont_fix') return 'Won\'t Fix';
              if (normalized === 'resolved') return 'Resolved';
              return 'Open';
            }

            // Display-side annotations for the toolbar counter and the right Annotations panel.
            // Includes already-sent items so the count matches the sidebar Session card's lifetime total
            // (sidebar uses server-side unresolvedItemsCount, which counts by status only — not delivery).
            // The send/copy path uses currentPromptAnnotations(), which re-applies the delivery filter
            // so already-sent items are not re-sent.
            function toolbarAnnotations() {
              if (addItemsFlow) return pendingFeedbackItems;
              return state.session?.items || [];
            }

            function hasWrittenAnnotationComment(item) {
              return Boolean(String(item?.comment || '').trim());
            }

            function currentPromptAnnotations() {
              if (!state.session) return [];
              return toolbarAnnotations()
                .filter(item => item.delivery !== 'sent')
                .filter(hasWrittenAnnotationComment);
            }


            function naturalPointFromEvent(event, image) {
              const rect = image.getBoundingClientRect();
              if (!image.naturalWidth || !image.naturalHeight || !rect.width || !rect.height) {
                throw new Error('Snapshot image dimensions are not available.');
              }
              return {
                x: clamp((event.clientX - rect.left) * image.naturalWidth / rect.width, 0, image.naturalWidth),
                y: clamp((event.clientY - rect.top) * image.naturalHeight / rect.height, 0, image.naturalHeight)
              };
            }

            function normalizeBounds(a, b) {
              return {
                left: Math.min(a.x, b.x),
                top: Math.min(a.y, b.y),
                right: Math.max(a.x, b.x),
                bottom: Math.max(a.y, b.y)
              };
            }

            function containsPoint(bounds, point) {
              return Boolean(bounds) &&
                point.x >= bounds.left &&
                point.x <= bounds.right &&
                point.y >= bounds.top &&
                point.y <= bounds.bottom;
            }

            function area(bounds) {
              if (!bounds) return Number.MAX_VALUE;
              return Math.max(0, bounds.right - bounds.left) * Math.max(0, bounds.bottom - bounds.top);
            }

            function componentLabel(node) {
              const clean = value => {
                const raw = String(value || '').trim();
                if (!raw || raw.startsWith('compose:')) return '';
                return raw;
              };
              const firstMeaningful = values => (values || []).map(clean).find(Boolean) || '';
              const role = clean(node.role);
              const label = firstMeaningful([...(node.text || []), node.editableText, ...(node.contentDescription || [])]);
              const testTag = clean(node.testTag);
              if (role && label) return humanize(role) + ' "' + label + '"';
              if (label) return label;
              if (role && testTag) return humanize(role) + ' #' + testTag;
              if (testTag) return '#' + testTag;
              if (role) return humanize(role);
              const bounds = node.boundsInWindow;
              if (bounds) return 'Component ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top);
              return 'Component';
            }

            function formatBounds(bounds) {
              return Math.round(bounds.left) + ',' + Math.round(bounds.top) + ' - ' + Math.round(bounds.right) + ',' + Math.round(bounds.bottom);
            }

            function focusedPendingSelectionSummary() {
              if (focusedPendingItemIndex != null) {
                return pendingFeedbackItems[focusedPendingItemIndex] || null;
              }
              return null;
            }

            function updateComposerState() {
              const hasPromptAnnotations = currentPromptAnnotations().length > 0;
              const promptDisabled = !hasPromptAnnotations || promptActionInFlight;
              copyPromptButton.disabled = promptDisabled;
              sendAgentButton.disabled = promptDisabled;
              cancelAddFlowButton.disabled = !addItemsFlow;
              addItemButton.hidden = true;
              addItemButton.disabled = true;
              annotateToolButton.disabled = addItemsFlowStarting;
              selectToolButton.setAttribute('aria-pressed', String(toolMode === 'select'));
              annotateToolButton.setAttribute('aria-pressed', String(toolMode === 'annotate'));
              toolStatus.innerHTML = toolMode === 'annotate'
                ? '<span class="ts-hint"><span class="ts-dot"></span><span>Click a widget — or drag to draw a region</span></span>'
                : '<span class="ts-meta">' +
                    '<span class="ts-dot-label"><span class="ts-dot"></span>' + toolbarOpenCount() + ' open</span>' +
                    '<span class="ts-dot-label"><span class="ts-dot resolved"></span>' + toolbarResolvedCount() + ' resolved</span>' +
                  '</span>';
              const item = focusedPendingSelectionSummary();
              selectionSummary.textContent = currentSelection
                ? currentSelection.label + ' - ' + formatBounds(currentSelection.bounds)
                : (item
                  ? 'Focused #' + (focusedPendingItemIndex + 1) + ' - ' + formatBounds(item.bounds)
                  : (toolMode === 'annotate' ? 'Click a component or drag a region to create an annotation.' : 'No annotation selected.'));
            }


            function nodesForHitTest(screen, nodesSelector) {
              const nodes = [];
              const seenNodeIds = new Set();
              const appendNodes = candidates => {
                (candidates || []).forEach(node => {
                  if (!node || !node.boundsInWindow) return;
                  if (node.uid) {
                    if (seenNodeIds.has(node.uid)) return;
                    seenNodeIds.add(node.uid);
                  }
                  nodes.push(node);
                });
              };
              const roots = screen?.roots || [];
              roots.forEach(root => appendNodes(nodesSelector(root)));
              return nodes;
            }

            function smallestContainingNode(nodes, point) {
              return nodes
                .map((node, order) => ({ node: node, order: order }))
                .filter(candidate => containsPoint(candidate.node.boundsInWindow, point))
                .sort((a, b) => area(a.node.boundsInWindow) - area(b.node.boundsInWindow) || a.order - b.order)[0]?.node;
            }

            function hitTestNodes(screen) {
              return nodesForHitTest(screen, root => [
                ...(root?.mergedNodes || []),
                ...(root?.unmergedNodes || [])
              ]);
            }

            function selectionForNode(node) {
              return {
                targetType: 'node',
                nodeUid: node.uid,
                bounds: node.boundsInWindow,
                label: componentLabel(node)
              };
            }

            function nodeSelectionAtPoint(event, image) {
              const point = naturalPointFromEvent(event, image);
              const screen = latestScreen();
              const node = smallestContainingNode(hitTestNodes(screen), point);
              return node ? selectionForNode(node) : null;
            }

            function selectNodeAtPoint(event, image) {
              if (isInteractionBlocked()) return;
              const selection = nodeSelectionAtPoint(event, image);
              if (!selection) {
                if (!isInteractionBlocked()) {
                  showError(new Error('No component found at that point. Drag to select a custom area.'));
                }
                return;
              }
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function previewNodeAtPoint(event, image) {
              if (isInteractionBlocked()) return;
              const selection = nodeSelectionAtPoint(event, image);
              const nextId = selection?.nodeUid || null;
              const currentId = hoveredAnnotationTarget?.nodeUid || null;
              if (nextId === currentId) return;
              hoveredAnnotationTarget = selection;
              renderSelectionOverlay();
            }

            function confirmHoveredAnnotationTarget(event, image) {
              if (isInteractionBlocked()) return;
              if (hoveredAnnotationTarget) {
                const point = naturalPointFromEvent(event, image);
                if (containsPoint(hoveredAnnotationTarget.bounds, point)) {
                  const selection = hoveredAnnotationTarget;
                  hoveredAnnotationTarget = null;
                  createAnnotationFromSelection(selection);
                  error.textContent = '';
                  return;
                }
              }
              selectNodeAtPoint(event, image);
            }

            function finishAreaSelection(bounds) {
              if (isInteractionBlocked()) return;
              const selection = {
                targetType: 'area',
                bounds: bounds,
                label: 'Custom area ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top)
              };
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function clearDragState() {
              if (!dragStart && !dragPreview) return;
              dragStart = null;
              dragPreview = null;
              renderSelectionOverlay();
            }

            function clearHoverPreview() {
              if (!hoveredAnnotationTarget) return;
              hoveredAnnotationTarget = null;
              renderSelectionOverlay();
            }

            function resetAnnotationComposerState(clearFlow = true, clearMirror = true) {
              if (clearFlow) addItemsFlow = null;
              if (clearMirror) {
                clearPendingMirror(state.session?.sessionId);
                activePendingMirrorSessions.delete(state.session?.sessionId);
              }
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              toolMode = 'select';
              comment.value = '';
              clearDragState();
            }

            function currentPendingStateEnvelope(items = pendingFeedbackItems) {
              return {
                context: addItemsFlow?.context ?? null,
                previewId: addItemsFlow?.previewId ?? addItemsFlow?.context?.previewId ?? null,
                screen: addItemsFlow?.screen ?? null,
                screenshotUrl: addItemsFlow?.screenshotUrl ?? null,
                frozenAtEpochMillis: addItemsFlow?.frozenAtEpochMillis ?? addItemsFlow?.context?.frozenAtEpochMillis ?? null,
                items: items,
              };
            }

            function persistCurrentPendingState() {
              const sessionId = state.session?.sessionId;
              const envelope = currentPendingStateEnvelope();
              persistPendingState(sessionId, envelope);
              if (sessionId && pendingRecoveryItems(envelope).length) {
                activePendingMirrorSessions.add(sessionId);
              } else {
                activePendingMirrorSessions.delete(sessionId);
              }
            }

            function releaseSnapshotPointerCapture(image, event) {
              try {
                if (image.hasPointerCapture?.(event.pointerId)) {
                  image.releasePointerCapture?.(event.pointerId);
                }
              } catch (_) {
              }
            }

            function clearSelection() {
              currentSelection = null;
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              hoveredAnnotationTarget = null;
              comment.value = '';
              clearDragState();
              renderSelectionOverlay();
              renderInspectorRegion();
              updateComposerState();
            }


            async function startAddItemsFlow() {
              if (addItemsFlowStarting) return;
              error.textContent = '';
              addItemsFlowStarting = true;
              updateComposerState();
              stopLivePreviewPolling();
              try {
                const addFlowContextGeneration = previewRequestContextGeneration;
                previewRequestGeneration++;
                let preview = state.preview;
                if (previewRequestInFlight || !preview) {
                  preview = await requestLivePreview();
                  if (addFlowContextGeneration !== previewRequestContextGeneration) return;
                  preview = {
                    ...preview,
                    activity: state.connection?.availability?.activity ?? null,
                    frozenAtEpochMillis: Date.now(),
                    stale: false,
                  };
                  state.preview = preview;
                }
                if (!state.preview) {
                  return;
                }
                const ports = createBrowserDraftPorts();
                const nextWorkspace = await startDraftFreeze(draftWorkspace, {
                  sessionId: state.session?.sessionId || null,
                  selectedDeviceSerial: state.selectedDeviceSerial || null,
                  activityName: state.connection?.availability?.activity ?? null,
                }, {
                  ...ports,
                  preview: {
                    ...ports.preview,
                    capture: async () => state.preview,
                  },
                });
                setDraftWorkspace(nextWorkspace);
                toolMode = 'annotate';
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
                render();
              } finally {
                addItemsFlowStarting = false;
                updateComposerState();
                if (!addItemsFlow) startLivePreviewPolling();
              }
            }

            function flushFocusedPendingComment() {
              if (focusedPendingItemIndex == null) return;
              const item = pendingFeedbackItems[focusedPendingItemIndex];
              if (!item) return;
              const commentInput = pendingItems.querySelector('#annotationCommentInput');
              item.comment = commentInput ? commentInput.value : comment.value;
            }

            function createAnnotationFromSelection(selection) {
              if (!addItemsFlow) throw new Error('Switch to Annotate before selecting feedback.');
              if (!selection) throw new Error('Select a component or area first.');
              flushFocusedPendingComment();
              const ports = createBrowserDraftPorts();
              let nextWorkspace = addDraftItem(draftWorkspace, selection, ports);
              // SIF-6: re-check activity drift after each pending item is
              // appended. Uses the existing status-poll-derived availability
              // — no extra fetch is issued.
              if (nextWorkspace.context) {
                const currentActivitySnapshot = {
                  activity: state.connection?.availability?.activity ?? null
                };
                nextWorkspace = {
                  ...nextWorkspace,
                  activityDriftWarning: checkActivityDrift({ activity: nextWorkspace.context.activityName }, currentActivitySnapshot),
                };
              }
              setDraftWorkspace(nextWorkspace);
              const createdItem = nextWorkspace.items[nextWorkspace.items.length - 1];
              hoveredAnnotationTarget = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              toolMode = 'annotate';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
              return createdItem;
            }

            function deletePendingFeedbackItem(index) {
              const removed = draftWorkspace.items[index];
              if (!removed) return;
              setDraftWorkspace(deleteDraftItem(draftWorkspace, removed.draftItemId));
              showUndoToast(removed.draftItemId);
              focusedPendingItemIndex = null;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
            }

            function focusPendingFeedbackItem(index) {
              flushFocusedPendingComment();
              focusedPendingItemIndex = index;
              focusedSavedItemId = null;
              focusedSavedSessionId = null;
              currentSelection = null;
              toolMode = 'select';
              comment.value = pendingFeedbackItems[index]?.comment || '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function focusSavedEvidenceItem(itemId) {
              focusedSavedItemId = itemId;
              focusedSavedSessionId = state.session?.sessionId || null;
              focusedPendingItemIndex = null;
              currentSelection = null;
              toolMode = 'select';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function normalizedPersistedStatus(status) {
              return String(status || 'open').replace('-', '_');
            }

            function applySavedSessionUpdate(updatedSession, sessionId) {
              const updatedSessionId = updatedSession?.sessionId || sessionId;
              if (state.session?.sessionId === updatedSessionId) {
                state.session = updatedSession;
                renderCurrentSessionList();
                updateComposerState();
                refreshSessions().catch(showError);
              } else {
                refreshSessions().catch(showError);
              }
              return updatedSession;
            }

            async function persistSavedEvidenceItem(item, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!item?.itemId) return state.session;
              const updatedSession = await withMutationLock(() => requestJson('/api/items/' + encodeURIComponent(item.itemId), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  sessionId: sessionId,
                  label: String(item.label || '').trim() || null,
                  severity: annotationSeverity(item),
                  comment: String(item.comment || ''),
                  status: normalizedPersistedStatus(annotationStatus(item))
                })
              }));
              return applySavedSessionUpdate(updatedSession, sessionId);
            }

            async function deleteSavedEvidenceItem(itemId, sessionId = focusedSavedSessionId || state.session?.sessionId || null) {
              if (!itemId) return;
              const sessionQuery = sessionId
                ? '?sessionId=' + encodeURIComponent(sessionId)
                : '';
              const updatedSession = await withMutationLock(() => requestJson('/api/items/' + encodeURIComponent(itemId) + sessionQuery, { method: 'DELETE' }));
              if (state.session?.sessionId === (updatedSession?.sessionId || sessionId)) {
                focusedSavedItemId = null;
                focusedSavedSessionId = null;
                state.session = updatedSession;
                renderPreviewOnly();
                renderInspectorRegion();
                renderCurrentSessionList();
                refreshSessions().catch(showError);
              } else {
                refreshSessions().catch(showError);
              }
            }

            function updateSelectedAnnotationComment() {
              const item = selectedAnnotation();
              if (!item) return;
              item.comment = comment.value;
              if (addItemsFlow) persistCurrentPendingState();
              renderPendingItems();
              updateComposerState();
            }

            function pendingPayloadItems(options = {}) {
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              const items = onlyWrittenComments ? pendingFeedbackItems.filter(hasWrittenAnnotationComment) : pendingFeedbackItems;
              return items.map(item => ({
                targetType: item.targetType,
                bounds: item.bounds,
                nodeUid: item.nodeUid,
                label: String(item.label || '').trim() || null,
                severity: annotationSeverity(item),
                status: normalizedPersistedStatus(annotationStatus(item)),
                comment: allowFallbackComments
                  ? (String(item.comment || '').trim() || item.label || pendingTargetLabel(item))
                  : (allowBlankComments ? String(item.comment || '') : item.comment)
              }));
            }

            async function persistPendingFeedbackItems(options = {}) {
              if (!addItemsFlow) return;
              if (!pendingFeedbackItems.length) throw new Error('Add at least one pending feedback item.');
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              const forceMismatchOverride = Boolean(options.forceMismatchOverride);
              if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && pendingFeedbackItems.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
              if (onlyWrittenComments && !pendingFeedbackItems.some(hasWrittenAnnotationComment)) throw new Error('Add a comment to at least one annotation before sending.');
              const payloadItems = pendingPayloadItems({ allowFallbackComments: allowFallbackComments, onlyWrittenComments: onlyWrittenComments, allowBlankComments: allowBlankComments });
              if (!addItemsFlow.context?.sessionId || !addItemsFlow.context?.previewId) {
                throw new Error('Annotation context is missing. Re-capture the screen and try again.');
              }
              const requestGeneration = sessionMutationGeneration;
              const expectedSessionId = addItemsFlow.context.sessionId;
              const sendBatch = async (overrideMismatch) => {
                return await withMutationLock(() => savePreviewBatchOrConflict({
                  sessionId: expectedSessionId,
                  previewId: addItemsFlow.context.previewId,
                  screen: addItemsFlow.screen,
                  items: payloadItems,
                  frozenFingerprint: addItemsFlow.context.screenFingerprint,
                  forceMismatchOverride: Boolean(overrideMismatch)
                }));
              };
              let result = await sendBatch(forceMismatchOverride);
              if (result && result.conflict === 'screen_fingerprint_mismatch') {
                const choice = await promptScreenFingerprintMismatch(result.frozenFingerprint, result.currentFingerprint);
                if (choice === 'force') {
                  result = await sendBatch(true);
                  if (result && result.conflict === 'screen_fingerprint_mismatch') {
                    throw new Error('Save was rejected after force override. Re-capture and try again.');
                  }
                } else if (choice === 'recapture') {
                  // Drop the stale preview so the next Annotate-mode entry re-freezes.
                  resetAnnotationComposerState();
                  state.preview = null;
                  startLivePreviewPolling();
                  return null;
                } else {
                  // Cancelled.
                  return null;
                }
              }
              if (requestGeneration !== sessionMutationGeneration) {
                await refreshSessions();
                return null;
              }
              if (result.session?.sessionId !== expectedSessionId) {
                await refreshSessions();
                throw new Error('Save returned a different feedback session. Refresh and try again.');
              }
              state.session = result.session;
              resetAnnotationComposerState();
              state.preview = null;
              return state.session;
            }

            async function savePreviewBatchOrConflict(body) {
              const headers = new Headers({ 'Content-Type': 'application/json' });
              const token = window.FixThisConsoleConfig?.consoleToken;
              if (token) headers.set('X-FixThis-Console-Token', token);
              const response = await fetch('/api/items/batch', {
                method: 'POST',
                headers: headers,
                body: JSON.stringify(body)
              });
              if (response.status === 409) {
                const conflict = await response.json().catch(() => ({}));
                if (conflict && conflict.error === 'screen_fingerprint_mismatch') {
                  return {
                    conflict: 'screen_fingerprint_mismatch',
                    frozenFingerprint: conflict.frozenFingerprint || null,
                    currentFingerprint: conflict.currentFingerprint || null
                  };
                }
                throw new Error('Save conflict: ' + JSON.stringify(conflict));
              }
              if (!response.ok) {
                throw new Error(await response.text() || 'HTTP ' + response.status);
              }
              return { session: await response.json() };
            }

            function promptScreenFingerprintMismatch(frozenFingerprint, currentFingerprint) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptFingerprintMismatch === 'function') {
                return Promise.resolve(window.fixThisPromptFingerprintMismatch({ frozenFingerprint: frozenFingerprint, currentFingerprint: currentFingerprint }));
              }
              const message = '화면이 캡처 이후 변경되었을 수 있습니다.\n\n' +
                '확인 = 현재 화면 다시 캡처\n' +
                '취소 = 다음 단계로 (강제 저장 여부 묻기)';
              if (typeof window === 'undefined' || typeof window.confirm !== 'function') {
                return Promise.resolve('cancel');
              }
              const recapture = window.confirm(message);
              if (recapture) return Promise.resolve('recapture');
              const force = window.confirm('강제로 저장하시겠습니까?\n확인 = 강제 저장\n취소 = 취소');
              return Promise.resolve(force ? 'force' : 'cancel');
            }

            async function flushPendingAnnotationsBeforeSessionChange() {
              if (!addItemsFlow || !pendingFeedbackItems.length) return;
              await persistPendingFeedbackItems({ allowBlankComments: true });
            }

            async function savePendingFeedbackItems() {
              await persistPendingFeedbackItems();
              await refresh();
              startLivePreviewPolling();
            }

            function cancelAddItemsFlow() {
              resetAnnotationComposerState();
              render();
              startLivePreviewPolling();
            }

            function enterSelectMode() {
              toolMode = 'select';
              currentSelection = null;
              clearHoverPreview();
              clearDragState();
              renderCurrentSessionList();
              renderPreviewOnly();
              renderInspectorRegion();
            }
