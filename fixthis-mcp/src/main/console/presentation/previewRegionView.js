// @requires annotations.js, preview.js, previewStaleness.js, presentation/annotationListView.js, presentation/selectionOverlayView.js, presentation/annotationDetailView.js
            function workflowActiveStep() {
              if (!state.connection?.hasEverConnected && userConnectionState(state.connection?.current) !== 'ready') return 'connect';
              if (draftFlow() || toolMode.isAnnotateMode()) return 'annotate';
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
              inspectorCount.textContent = String(draftItemList().length + savedItems.length);
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = false;
              draftItems.hidden = savedItems.length === 0;
              if (savedSectionHeader) savedSectionHeader.hidden = savedItems.length === 0;
              inspectorFooter.hidden = savedItems.length === 0;
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
              renderSavedEvidenceGroups();
            }

            function renderInspectorRegion() {
              if (draftFlow()) {
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
              if (draftFlow()) return { state: 'frozen', label: 'Frozen for annotation' };
              if (toolMode.getState().focusedSavedItemId || toolMode.getState().focusedSavedScreenId || (!state.preview && screen?.screenId)) return { state: 'saved', label: 'Saved screen' };
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
	              const isDraft = canvasModel ? canvasModel.mode === 'frozenDraft' : Boolean(draftFlow());
	              if (!isDraft) {
	                root.textContent = '';
	                statusSurfaceRegistry.hide('draftLockBar');
	                return;
	              }
	              const label = canvasModel?.lockLabel || (
	                'Locked: Session ' + (draftFlow()?.context?.sessionId || state.session?.sessionId || 'current') +
	                ' · Preview ' + (draftFlow()?.previewId || draftFlow()?.context?.previewId || 'frozen')
	              );
	              statusSurfaceRegistry.show('draftLockBar', {
	                surfaceClass: 'inline',
	                priority: 3,
	                element: root,
	                content: label,
	              });
	            }

	            function renderBoundaryFromModel(boundary) {
	              const root = document.getElementById('sessionBoundarySheet');
	              if (!root) return;
	              if (!boundary) {
	                statusSurfaceRegistry.hide('sessionBoundarySheet');
	                return;
	              }
	              root.querySelector('[data-boundary-title]').textContent = boundary.title;
	              root.querySelector('[data-boundary-summary]').textContent =
	                boundary.draftSummary.itemCount + ' draft annotations · ' + boundary.draftSummary.missingCommentCount + ' missing comments';
	              root.querySelectorAll('[data-boundary-action]').forEach((button, index) => {
	                const action = boundary.actions[index];
	                button.onclick = null;
	                button.hidden = !action;
	                if (action) {
	                  button.textContent = action.label;
	                  button.onclick = () => {
	                    if (typeof consoleStore !== 'undefined') consoleStore.dispatch({ type: action.type });
	                  };
	                }
	              });
	              statusSurfaceRegistry.show('sessionBoundarySheet', {
	                surfaceClass: 'modal',
	                priority: 1,
	                element: root,
	              });
	            }

            function hasRenderablePreviewImage(screen) {
              if (draftFlow()?.screenshotUrl) return true;
              return Boolean(screen?.screenshot?.desktopFullPath);
            }

            function renderPreviewRegion() {
              const screen = latestScreen();
              const hasScreenshot = hasRenderablePreviewImage(screen);
              const mode = draftFlow() ? 'frozen' : (state.preview ? 'live' : (screen ? 'frozen' : 'idle'));
              snapshot.dataset.toolMode = toolMode.isAnnotateMode() ? 'annotate' : 'select';
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
              if (toolMode.isAnnotateMode()) {
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
