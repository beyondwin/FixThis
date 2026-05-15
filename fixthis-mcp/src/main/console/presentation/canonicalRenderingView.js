// @requires state.js, connection.js, presentation/previewRegionView.js, presentation/promptReadinessView.js
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

            function renderCanonicalDraftLockModel(model) {
              renderDraftLockBar(model?.visible ? {
                mode: 'frozenDraft',
                lockLabel: 'Locked: Session ' + model.sessionId + ' · Preview ' + model.previewId,
              } : { mode: 'livePreview' });
            }

            function renderCanonicalToolbarModel(model) {
              if (!model) return;
              annotateToolButton.disabled = !model.canAnnotate || toolMode.getState().draftFlowStarting;
              copyPromptButton.disabled = !model.canCopy;
              sendAgentButton.disabled = !model.canSave;
            }

            function attachSnapshotHandlers() {
              const image = document.getElementById('snapshotImage');
              if (!image) return;
              image.draggable = false;
              image.addEventListener('dragstart', event => event.preventDefault());
              image.addEventListener('click', event => {
                try {
                  if (toolMode.getState().draftFlowStarting) {
                    event.preventDefault();
                    return;
                  }
                  if (toolMode.getState().suppressNextClick) {
                    toolMode.setSuppressNextClick(false);
                    return;
                  }
                  if (toolMode.isSelectMode() && draftFlow()) {
                    clearSelection();
                    return;
                  }
                  if (!draftFlow()) {
                    const point = naturalPointFromEvent(event, image);
                    navigate('tap', { x: point.x, y: point.y }).catch(showError);
                    return;
                  }
                  if (toolMode.isAnnotateMode() && !toolMode.isDragging()) {
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointerdown', event => {
                if (toolMode.getState().draftFlowStarting) {
                  event.preventDefault();
                  return;
                }
                if (!draftFlow() || !toolMode.isAnnotateMode()) return;
                try {
                  image.setPointerCapture?.(event.pointerId);
                  const point = naturalPointFromEvent(event, image);
                  toolMode.startDrag(point);
                  toolMode.updateDragPreview(normalizeBounds(point, point));
                  renderSelectionOverlay();
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointermove', event => {
                if (!draftFlow() || !toolMode.isAnnotateMode()) return;
                try {
                  const dragState = toolMode.getState().drag;
                  if (!dragState) {
                    previewNodeAtPoint(event, image);
                    return;
                  }
                  toolMode.updateDragPreview(normalizeBounds(dragState.start, naturalPointFromEvent(event, image)));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                const dragState = toolMode.getState().drag;
                if (!draftFlow() || !toolMode.isAnnotateMode() || !dragState) return;
                try {
                  const end = naturalPointFromEvent(event, image);
                  const bounds = normalizeBounds(dragState.start, end);
                  clearDragState();
                  releaseSnapshotPointerCapture(image, event);
                  if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
                    toolMode.setSuppressNextClick(true);
                    finishAreaSelection(bounds);
	                  } else {
	                    toolMode.setSuppressNextClick(true);
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
              const toolModeStateBefore = toolMode.getState();
              const preservedFocusedSavedItemId = toolModeStateBefore.focusedSavedItemId;
              const preservedFocusedSavedSessionId = toolModeStateBefore.focusedSavedSessionId;
              const preservedFocusedSavedScreenId = toolModeStateBefore.focusedSavedScreenId;
              const preservedFocusedPendingIndex = draftFocusIndex();
              const preservedSelection = draftSelection();

              setConsoleSession(fresh);

              comment.value = preservedComment;
              const freshItems = fresh?.items || [];
              const freshScreens = fresh?.screens || [];
              const focusedItem = freshItems.find(item => item.itemId === preservedFocusedSavedItemId);
              const nextSavedScreenId = focusedItem?.screenId || preservedFocusedSavedScreenId;
              const savedScreenStillExists = nextSavedScreenId && freshScreens.some(screen => screen.screenId === nextSavedScreenId);
              toolMode.focusSavedItem(
                focusedItem ? preservedFocusedSavedItemId : null,
                savedScreenStillExists ? preservedFocusedSavedSessionId : null,
                savedScreenStillExists ? nextSavedScreenId : null
              );
              setDraftFocusIndex(preservedFocusedPendingIndex);
              setDraftSelection(preservedSelection);

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
