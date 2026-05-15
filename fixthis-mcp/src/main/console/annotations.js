// @requires state.js, draftWorkspace.js, draftUseCases.js, draftCommandQueue.js, viewmodel/reliabilityPresentation.js, viewmodel/annotationPresentation.js
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

            // Display-side annotations for the toolbar counter and the right Annotations panel.
            // Includes already-sent items so the count matches the sidebar Session card's lifetime total
            // (sidebar uses server-side unresolvedItemsCount, which counts by status only — not delivery).
            // The send/copy path uses currentPromptAnnotations(), which re-applies the delivery filter
            // so already-sent items are not re-sent.
            function toolbarAnnotations() {
              if (draftFlow()) return draftItemList();
              return state.session?.items || [];
            }

            function hasWrittenAnnotationComment(item) {
              return Boolean(String(item?.comment || '').trim());
            }

            function draftItemsFromValue(value) {
              if (Array.isArray(value)) return value;
              return Array.isArray(value?.items) ? value.items : [];
            }

            function commentedDraftItems(value) {
              return draftItemsFromValue(value).filter(hasWrittenAnnotationComment);
            }

            function pinOnlyDraftItems(value) {
              return draftItemsFromValue(value).filter(item => !hasWrittenAnnotationComment(item));
            }

            function draftRecoverySummary(value) {
              const items = draftItemsFromValue(value);
              const commented = commentedDraftItems(items);
              const pinOnly = pinOnlyDraftItems(items);
              return {
                total: items.length,
                commented: commented.length,
                pinOnly: pinOnly.length,
              };
            }

            function hasCommentedDraftItems(value) {
              return draftRecoverySummary(value).commented > 0;
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
              if (draftFocusIndex() != null) {
                return draftItemList()[draftFocusIndex()] || null;
              }
              return null;
            }

            function updateComposerState() {
              const hasPromptAnnotations = currentPromptAnnotations().length > 0;
              const promptDisabled = !hasPromptAnnotations || pollingUseCases.getState().promptActionInFlight;
              copyPromptButton.disabled = promptDisabled;
              sendAgentButton.disabled = promptDisabled;
              cancelAddFlowButton.disabled = !draftFlow();
              addItemButton.hidden = true;
              addItemButton.disabled = true;
              annotateToolButton.disabled = toolMode.getState().draftFlowStarting;
              selectToolButton.setAttribute('aria-pressed', String(toolMode.isSelectMode()));
              annotateToolButton.setAttribute('aria-pressed', String(toolMode.isAnnotateMode()));
              toolStatus.innerHTML = toolMode.isAnnotateMode()
                ? '<span class="ts-hint"><span class="ts-dot"></span><span>Click a widget — or drag to draw a region</span></span>'
                : '<span class="ts-meta">' +
                    '<span class="ts-dot-label"><span class="ts-dot"></span>' + toolbarOpenCount() + ' open</span>' +
                    '<span class="ts-dot-label"><span class="ts-dot resolved"></span>' + toolbarResolvedCount() + ' resolved</span>' +
                  '</span>';
              const item = focusedPendingSelectionSummary();
              selectionSummary.textContent = draftSelection()
                ? draftSelection().label + ' - ' + formatBounds(draftSelection().bounds)
                : (item
                  ? 'Focused #' + (draftFocusIndex() + 1) + ' - ' + formatBounds(item.bounds)
                  : (toolMode.isAnnotateMode() ? 'Click a component or drag a region to create an annotation.' : 'No annotation selected.'));
              renderPromptReadiness();
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
              setDraftSelection(selection);
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function previewNodeAtPoint(event, image) {
              if (isInteractionBlocked()) return;
              const selection = nodeSelectionAtPoint(event, image);
              const nextId = selection?.nodeUid || null;
              const currentId = toolMode.getState().hoveredTarget?.nodeUid || null;
              if (nextId === currentId) return;
              toolMode.setHoveredTarget(selection);
              renderSelectionOverlay();
            }

            function confirmHoveredAnnotationTarget(event, image) {
              if (isInteractionBlocked()) return;
              const hoveredTarget = toolMode.getState().hoveredTarget;
              if (hoveredTarget) {
                const point = naturalPointFromEvent(event, image);
                if (containsPoint(hoveredTarget.bounds, point)) {
                  const selection = hoveredTarget;
                  toolMode.setHoveredTarget(null);
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
              setDraftSelection(selection);
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function clearDragState() {
              const drag = toolMode.getState().drag;
              if (!drag) return;
              toolMode.dropDiscard();
              renderSelectionOverlay();
            }

            function clearHoverPreview() {
              if (!toolMode.getState().hoveredTarget) return;
              toolMode.setHoveredTarget(null);
              renderSelectionOverlay();
            }

            function resetComposer(clearFlow = true, clearMirror = true) {
              if (clearFlow && clearMirror) deleteCurrentDraftWorkspaceStorage();
              if (clearFlow) replaceDraftWorkspace(createEmptyDraftWorkspace());
              if (clearMirror) {
                clearPendingMirror(state.session?.sessionId);
                activePendingMirrorSessions.delete(state.session?.sessionId);
              }
              setDraftFocusIndex(null);
              toolMode.focusSavedItem(null, null);
              setDraftSelection(null);
              toolMode.setHoveredTarget(null);
              toolMode.enterSelect();
              comment.value = '';
              clearDragState();
            }

            function persistCurrentPendingState() {
              persistCurrentDraftWorkspaceIfNeeded();
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
              setDraftSelection(null);
              setDraftFocusIndex(null);
              toolMode.focusSavedItem(null, null);
              toolMode.setHoveredTarget(null);
              comment.value = '';
              clearDragState();
              renderSelectionOverlay();
              renderInspectorRegion();
              updateComposerState();
            }


            async function startDraftAnnotationFlow() {
              if (toolMode.getState().draftFlowStarting) return;
              error.textContent = '';
              toolMode.setAddItemsFlowStarting(true);
              updateComposerState();
              stopLivePreviewPolling();
              try {
                const addFlowContextGeneration = previewUseCases.getState().contextGeneration;
                let preview = state.preview;
                if (previewUseCases.getState().inFlight || !preview) {
                  preview = await previewUseCases.request();
                  if (addFlowContextGeneration !== previewUseCases.getState().contextGeneration) return;
                  preview = {
                    ...preview,
                    activity: state.connection?.availability?.activity ?? null,
                    frozenAtEpochMillis: Date.now(),
                    stale: false,
                  };
                  setConsolePreview(preview);
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
                replaceDraftWorkspace(nextWorkspace);
                toolMode.enterAnnotate();
                toolMode.focusSavedItem(null, null);
                render();
              } finally {
                toolMode.setAddItemsFlowStarting(false);
                updateComposerState();
                if (!draftFlow()) startLivePreviewPolling();
              }
            }

            function flushFocusedPendingComment() {
              if (draftFocusIndex() == null) return;
              const item = draftItemList()[draftFocusIndex()];
              if (!item) return;
              const commentInput = pendingItems.querySelector('#annotationCommentInput');
              item.comment = commentInput ? commentInput.value : comment.value;
            }

            function createAnnotationFromSelection(selection) {
              if (!draftFlow()) throw new Error('Switch to Annotate before selecting feedback.');
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
              replaceDraftWorkspace(nextWorkspace);
              const createdItem = nextWorkspace.items[nextWorkspace.items.length - 1];
              toolMode.setHoveredTarget(null);
              toolMode.focusSavedItem(null, null);
              toolMode.enterAnnotate();
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
              return createdItem;
            }

            function deletePendingFeedbackItem(index) {
              const removed = draftWorkspace.items[index];
              if (!removed) return;
              const nextWorkspace = deleteDraftItem(draftWorkspace, removed.draftItemId);
              if (nextWorkspace.items.length === 0) {
                deleteCurrentDraftWorkspaceStorage();
              }
              replaceDraftWorkspace(nextWorkspace);
              showUndoToast(removed.draftItemId);
              setDraftFocusIndex(null);
              toolMode.focusSavedItem(null, null);
              setDraftSelection(null);
              toolMode.setHoveredTarget(null);
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
              renderCurrentSessionList();
            }

            function focusPendingFeedbackItem(index) {
              flushFocusedPendingComment();
              setDraftFocusIndex(index);
              toolMode.focusSavedItem(null, null);
              setDraftSelection(null);
              toolMode.enterSelect();
              comment.value = draftItemList()[index]?.comment || '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function focusSavedEvidenceItem(itemId) {
              const item = savedEvidenceItems().find(savedItem => savedItem.itemId === itemId);
              toolMode.focusSavedItem(item ? itemId : null, item ? state.session?.sessionId || null : null, item?.screenId || null);
              setDraftFocusIndex(null);
              setDraftSelection(null);
              toolMode.enterSelect();
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
                setConsoleSession(updatedSession);
                renderCurrentSessionList();
                updateComposerState();
                refreshSessions().catch(showError);
              } else {
                refreshSessions().catch(showError);
              }
              return updatedSession;
            }

            async function persistSavedEvidenceItem(item, sessionId = toolMode.getState().focusedSavedSessionId || state.session?.sessionId || null) {
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

            async function deleteSavedEvidenceItem(itemId, sessionId = toolMode.getState().focusedSavedSessionId || state.session?.sessionId || null) {
              if (!itemId) return;
              const sessionQuery = sessionId
                ? '?sessionId=' + encodeURIComponent(sessionId)
                : '';
              const updatedSession = await withMutationLock(() => requestJson('/api/items/' + encodeURIComponent(itemId) + sessionQuery, { method: 'DELETE' }));
              if (state.session?.sessionId === (updatedSession?.sessionId || sessionId)) {
                const previousScreenId = toolMode.getState().focusedSavedScreenId;
                const fallbackItem = (updatedSession?.items || []).find(item => item.screenId === previousScreenId) || null;
                const fallbackScreenId = fallbackItem?.screenId || ((updatedSession?.screens || []).some(screen => screen.screenId === previousScreenId) ? previousScreenId : null);
                toolMode.focusSavedItem(fallbackItem?.itemId || null, fallbackScreenId ? sessionId : null, fallbackScreenId);
                setConsoleSession(updatedSession);
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
              if (draftFlow()) persistCurrentPendingState();
              renderPendingItems();
              updateComposerState();
            }

            function saveResidualPinOnlyDraft(items, options = {}) {
              if (!draftWorkspace?.workspaceId || !items.length) return;
              const ports = createBrowserDraftPorts();
              const workspace = {
                ...draftWorkspace,
                workspaceId: ports.ids.nextWorkspaceId(),
                revision: 1,
                items,
                history: { undoStack: [], redoStack: [] },
              };
              ports.storage.saveWorkspace(draftWorkspaceRecoveryEnvelope(workspace));
              activePendingMirrorSessions.add(workspace.context?.sessionId);
              if (options.keepActive) {
                replaceDraftWorkspace(workspace);
                persistCurrentPendingState();
              }
              return workspace;
            }

            async function persistPendingFeedbackItems(options = {}) {
              if (!draftWorkspace?.workspaceId) return;
              if (!draftWorkspaceItems(draftWorkspace).length) throw new Error('Add at least one pending feedback item.');
              const allowFallbackComments = Boolean(options.allowFallbackComments);
              const onlyWrittenComments = Boolean(options.onlyWrittenComments);
              const allowBlankComments = Boolean(options.allowBlankComments);
              const forceMismatchOverride = Boolean(options.forceMismatchOverride);
              const keepResidualDraftActive = options.keepResidualDraftActive !== false;
              const items = draftWorkspaceItems(draftWorkspace);
              const residualPinOnlyItems = onlyWrittenComments ? pinOnlyDraftItems(items) : [];
              if (!allowFallbackComments && !onlyWrittenComments && !allowBlankComments && items.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
              if (onlyWrittenComments && !commentedDraftItems(items).length) throw new Error('Add a comment to at least one annotation before sending.');
              const meta = {
                kind: 'persist-draft-workspace',
                workspaceId: draftWorkspace.workspaceId,
                expectedRevision: draftWorkspace.revision,
              };
              const result = await ensureDraftCommandQueue().enqueue(meta, async (workspace) => {
                const saveWorkspace = onlyWrittenComments
                  ? { ...workspace, items: draftWorkspaceItems(workspace).filter(hasWrittenAnnotationComment) }
                  : workspace;
                return await persistDraftWorkspace(saveWorkspace, createBrowserDraftPorts(), {
                  allowBlankComments,
                  onlyWrittenComments,
                  allowFallbackComments,
                  forceMismatchOverride,
                });
              });
              if (result?.result?.conflict?.error === 'screen_fingerprint_mismatch') {
                const conflict = result.result.conflict;
                const choice = await promptScreenFingerprintMismatch(conflict.frozenFingerprint, conflict.currentFingerprint);
                if (choice === 'force') {
                  return await persistPendingFeedbackItems({ ...options, forceMismatchOverride: true });
                }
                if (choice === 'recapture') {
                  replaceDraftWorkspace(createEmptyDraftWorkspace());
                  setConsolePreview(null);
                  startLivePreviewPolling();
                  return null;
                }
                return null;
              }
              if (result?.result?.session) {
                setConsoleSession(result.result.session);
                setConsolePreview(null);
                if (onlyWrittenComments) saveResidualPinOnlyDraft(residualPinOnlyItems, { keepActive: keepResidualDraftActive });
                return state.session;
              }
              await refreshSessions();
              return state.session;
            }

            function promptScreenFingerprintMismatch(frozenFingerprint, currentFingerprint) {
              if (typeof window !== 'undefined' && typeof window.fixThisPromptFingerprintMismatch === 'function') {
                return Promise.resolve(window.fixThisPromptFingerprintMismatch({ frozenFingerprint: frozenFingerprint, currentFingerprint: currentFingerprint }));
              }
              const message = 'The screen may have changed since it was captured.\n\n' +
                'OK = Recapture the current screen\n' +
                'Cancel = Continue to the next step (ask whether to force save)';
              if (typeof window === 'undefined' || typeof window.confirm !== 'function') {
                return Promise.resolve('cancel');
              }
              const recapture = window.confirm(message);
              if (recapture) return Promise.resolve('recapture');
              const force = window.confirm('Force save anyway?\nOK = Force save\nCancel = Cancel');
              return Promise.resolve(force ? 'force' : 'cancel');
            }

            async function savePendingFeedbackItems() {
              store.dispatch({ type: 'SAVE_TO_MCP_CLICKED' });
              await persistPendingFeedbackItems();
              await refresh();
              startLivePreviewPolling();
            }

            function cancelAddItemsFlow() {
              resetComposer();
              render();
              startLivePreviewPolling();
            }

            function enterSelectMode() {
              toolMode.enterSelect();
              setDraftSelection(null);
              clearHoverPreview();
              clearDragState();
              renderCurrentSessionList();
              renderPreviewOnly();
              renderInspectorRegion();
            }
