// @requires annotations.js, draftWorkspace.js
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
                renderOverlayBox(overlay, image, item.bounds, String(displayNumber), false, index === draftFocusIndex(), index, '', severityColor(annotationSeverity(item)));
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
              const toolModeState = toolMode.getState();
              if (!draftFlow()) {
                const focusedItem = savedEvidenceItems().find(item => item.itemId === toolModeState.focusedSavedItemId);
                const savedScreenId = focusedItem?.screenId || toolModeState.focusedSavedScreenId;
                const sameScreenItems = savedEvidenceItems().filter(item => item.screenId === savedScreenId);
                if (sameScreenItems.length) renderSavedEvidenceOverlay(overlay, image, sameScreenItems);
              }
              if (draftSelection()) {
                renderOverlayBox(overlay, image, draftSelection().bounds, draftSelection().label);
              }
              if (draftFlow() && toolMode.isAnnotateMode() && toolModeState.hoveredTarget && !toolModeState.drag?.preview) {
                renderOverlayBox(overlay, image, toolModeState.hoveredTarget.bounds, null, false, false, null, 'hover-preview');
              }
              if (toolModeState.drag?.preview) {
                renderOverlayBox(overlay, image, toolModeState.drag.preview, null, true);
              }
              updateComposerState();
            }
