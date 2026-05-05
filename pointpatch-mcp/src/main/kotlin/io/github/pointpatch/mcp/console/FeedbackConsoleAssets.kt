package io.github.pointpatch.mcp.console

internal object FeedbackConsoleAssets {
    val indexHtml: String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>PointPatch Feedback Console</title>
          <style>
            :root {
              color-scheme: light;
              font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background: #f6f7f9;
              color: #171b22;
            }
            * { box-sizing: border-box; }
            body { margin: 0; min-height: 100vh; }
            header {
              display: grid;
              grid-template-columns: minmax(220px, 1fr) minmax(360px, 2fr) auto;
              align-items: center;
              gap: 14px;
              padding: 14px 18px;
              border-bottom: 1px solid #dde2ea;
              background: #ffffff;
            }
            h1 { margin: 0; font-size: 18px; font-weight: 700; letter-spacing: 0; }
            .meta { color: #5d6675; font-size: 12px; margin-top: 2px; }
            main {
              display: grid;
              grid-template-columns: minmax(220px, 300px) minmax(360px, 1fr) minmax(300px, 400px);
              gap: 1px;
              min-height: calc(100vh - 67px);
              background: #dde2ea;
            }
            section {
              min-width: 0;
              background: #ffffff;
              padding: 16px;
              overflow: auto;
            }
            h2 { margin: 0 0 12px; font-size: 14px; font-weight: 700; letter-spacing: 0; }
            h2.section-heading { margin-top: 18px; }
            select, button {
              min-height: 34px;
              border: 1px solid #b9c2cf;
              border-radius: 6px;
              background: #ffffff;
              color: #171b22;
              padding: 0 10px;
              font: inherit;
              font-size: 13px;
            }
            button { cursor: pointer; }
            button.primary { background: #116a5c; border-color: #116a5c; color: #ffffff; }
            button:disabled { opacity: .55; cursor: default; }
            textarea {
              width: 100%;
              min-height: 92px;
              resize: vertical;
              border: 1px solid #c8d0dc;
              border-radius: 6px;
              padding: 10px;
              font: inherit;
              font-size: 13px;
            }
            .toolbar, .device-strip {
              display: flex;
              align-items: center;
              flex-wrap: wrap;
              gap: 8px;
            }
            .toolbar label {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              color: #3f4754;
              font-size: 13px;
            }
            .status-pill {
              border: 1px solid #d0d7e2;
              border-radius: 999px;
              background: #f7f9fc;
              color: #4b5563;
              min-height: 28px;
              padding: 5px 10px;
              font-size: 12px;
            }
            .list { display: grid; gap: 8px; }
            .row {
              border: 1px solid #e1e6ee;
              border-radius: 8px;
              padding: 10px;
              background: #fbfcfe;
            }
            .row.active, .session-row.active {
              border-color: #116a5c;
              background: #eef7f5;
            }
            .session-row {
              display: block;
              width: 100%;
              min-height: 0;
              text-align: left;
            }
            .row strong { display: block; font-size: 13px; margin-bottom: 4px; }
            .row span { color: #667085; font-size: 12px; overflow-wrap: anywhere; }
            .row span + span { display: block; margin-top: 3px; }
            .snapshot-header {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 12px;
              margin-bottom: 12px;
            }
            .snapshot-header h2 { margin: 0; }
            .snapshot {
              display: grid;
              place-items: center;
              min-height: 360px;
              border: 1px solid #e1e6ee;
              border-radius: 8px;
              background: #f9fafb;
              color: #667085;
              text-align: center;
              padding: 24px;
            }
            .snapshot-frame { position: relative; display: inline-block; max-width: 100%; }
            .snapshot-frame img { display: block; max-width: 100%; height: auto; cursor: pointer; }
            .selection-overlay {
              position: absolute;
              inset: 0;
              pointer-events: none;
            }
            .selection-box {
              position: absolute;
              border: 2px solid #116a5c;
              background: rgba(17, 106, 92, .12);
              border-radius: 4px;
            }
            .selection-box.drag-preview {
              border-style: dashed;
              background: rgba(17, 106, 92, .08);
            }
            .selection-box.focused {
              border-color: #b45309;
              background: rgba(180, 83, 9, .16);
            }
            .selection-label {
              position: absolute;
              transform: translateY(-100%);
              background: #116a5c;
              color: #ffffff;
              font-size: 12px;
              padding: 3px 6px;
              border-radius: 4px 4px 0 0;
              max-width: 100%;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
            .selection-label.focused { background: #b45309; }
            .selection-summary {
              border: 1px solid #e1e6ee;
              border-radius: 8px;
              background: #fbfcfe;
              color: #667085;
              min-height: 44px;
              padding: 10px;
              font-size: 13px;
            }
            img { max-width: 100%; height: auto; border-radius: 6px; border: 1px solid #d8dee8; }
            details.evidence-group {
              border: 1px solid #e1e6ee;
              border-radius: 8px;
              padding: 10px;
              background: #fbfcfe;
            }
            details.evidence-group summary {
              cursor: pointer;
              font-size: 13px;
              font-weight: 700;
            }
            .saved-evidence-preview { margin: 10px 0; }
            .error { color: #9c2d2d; font-size: 13px; min-height: 18px; }
            @media (max-width: 900px) {
              header { display: flex; align-items: flex-start; flex-direction: column; }
              main { grid-template-columns: 1fr; min-height: auto; }
              section { min-height: 260px; }
            }
          </style>
        </head>
        <body>
          <header>
            <div>
              <h1>PointPatch Feedback Console</h1>
              <div id="sessionMeta" class="meta">Loading session...</div>
            </div>
            <div class="device-strip">
              <select id="devicePicker"></select>
              <select id="previewIntervalSelect" aria-label="Preview interval">
                <option value="manual">Manual</option>
                <option value="1000">1s</option>
                <option value="2000" selected>2s</option>
                <option value="5000">5s</option>
              </select>
              <button id="refreshDevicesButton">Refresh Devices</button>
              <button id="disconnectDeviceButton">Disconnect</button>
              <span id="deviceStatus" class="status-pill">No device selected</span>
            </div>
            <div class="toolbar">
              <button id="refreshButton">Refresh</button>
              <button id="addFlowButton" class="primary">Add</button>
              <button id="saveButton" disabled>Save</button>
              <button id="copyMarkdownButton">Copy</button>
              <button id="sendDraftButton">Send</button>
              <button id="newSessionButton">New</button>
              <button id="closeSessionButton">Close</button>
            </div>
          </header>
          <main>
            <section class="sidebar">
              <h2>Sessions</h2>
              <div id="sessions" class="list"></div>
              <h2 class="section-heading">Sent History</h2>
              <div id="sentHistory" class="list"></div>
            </section>
            <section class="snapshot-pane">
              <div class="snapshot-header">
                <h2 id="snapshotTitle">Live Preview</h2>
                <div id="navigationControls" class="toolbar">
                  <button id="backButton">Back</button>
                  <button id="swipeUpButton">Swipe Up</button>
                  <button id="swipeDownButton">Swipe Down</button>
                  <button id="swipeLeftButton">Swipe Left</button>
                  <button id="swipeRightButton">Swipe Right</button>
                  <label><input id="captureAfterNavigation" type="checkbox"> Capture after navigation</label>
                </div>
              </div>
              <div id="snapshot" class="snapshot">
                <div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>
                <div>Refresh the live preview to begin.</div>
              </div>
            </section>
            <section class="queue-pane">
              <h2>Current Selection</h2>
              <div id="selectionSummary" class="selection-summary">No selection.</div>
              <div class="toolbar">
                <button id="clearSelectionButton">Clear Selection</button>
                <button id="cancelAddFlowButton" disabled>Cancel</button>
              </div>
              <h2 class="section-heading">Comment</h2>
              <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
              <div class="toolbar">
                <button id="addItemButton" class="primary" disabled>Add to Pending</button>
              </div>
              <h2 class="section-heading">Pending Items</h2>
              <div id="pendingItems" class="list"></div>
              <h2 class="section-heading">Draft</h2>
              <div class="toolbar">
                <button id="clearDraftButton">Clear Draft</button>
              </div>
              <div id="draftItems" class="list"></div>
              <p id="error" class="error"></p>
            </section>
          </main>
          <script>
            const DefaultLivePreviewIntervalMs = 2000;
            const MinLivePreviewIntervalMs = 1000;
            const PreviewIntervalStorageKey = 'pointpatch.previewIntervalMs';
            const state = { session: null, preview: null, selectedDeviceSerial: null };
            const sessionMeta = document.getElementById('sessionMeta');
            const sessions = document.getElementById('sessions');
            const sentHistory = document.getElementById('sentHistory');
            const snapshot = document.getElementById('snapshot');
            const snapshotTitle = document.getElementById('snapshotTitle');
            const draftItems = document.getElementById('draftItems');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const captureAfterNavigation = document.getElementById('captureAfterNavigation');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const navigationControls = document.getElementById('navigationControls');
            const selectionSummary = document.getElementById('selectionSummary');
            const addItemButton = document.getElementById('addItemButton');
            const saveButton = document.getElementById('saveButton');
            const cancelAddFlowButton = document.getElementById('cancelAddFlowButton');
            let livePreviewTimer = null;
            let previewRequestGeneration = 0;
            let previewRequestContextGeneration = 0;
            let previewRequestInFlight = null;
            let previewRequestInFlightContextGeneration = null;
            let addItemsFlow = null;
            let pendingFeedbackItems = [];
            let focusedPendingItemIndex = null;
            let currentSelection = null;
            let dragStart = null;
            let dragPreview = null;
            let suppressNextClick = false;

            function text(value) {
              return value == null || value === '' ? '-' : String(value);
            }

            function escapeHtml(value) {
              return text(value)
                .replaceAll('&', '&amp;')
                .replaceAll('<', '&lt;')
                .replaceAll('>', '&gt;')
                .replaceAll('"', '&quot;')
                .replaceAll("'", '&#39;');
            }

            function formatTime(epochMillis) {
              if (!epochMillis) return '-';
              return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }

            function humanize(value) {
              const normalized = text(value);
              if (normalized === '-') return normalized;
              return normalized
                .split('_')
                .filter(Boolean)
                .map(part => part.charAt(0).toUpperCase() + part.slice(1))
                .join(' ');
            }

            function countLabel(count, singular, plural) {
              return String(count) + ' ' + (count === 1 ? singular : plural);
            }

            function formatSessionLabel(session, index) {
              return humanize(session.status || 'active');
            }

            function formatSessionSummary(session) {
              return [
                humanize(session.status || 'active'),
                countLabel(session.draftItemsCount || 0, 'draft', 'draft'),
                countLabel(session.sentBatchesCount || 0, 'sent', 'sent'),
                'updated ' + formatTime(session.updatedAtEpochMillis)
              ].join(' | ');
            }

            function formatSessionHeader(session, itemCount) {
              return [
                session.packageName,
                countLabel(itemCount, 'feedback item', 'feedback items'),
                'updated ' + formatTime(session.updatedAtEpochMillis)
              ].join(' | ');
            }

            function firstLine(value) {
              return text(value).split(/\r?\n/)[0] || '(No comment)';
            }

            function formatItemLabel(item, index) {
              const number = item.sequenceNumber ? item.sequenceNumber : index + 1;
              return '#' + number + ' ' + firstLine(item.comment || '(No comment)');
            }

            function formatSavedEvidenceItemLabel(item, index) {
              return '#' + (index + 1) + ' ' + firstLine(item.comment || '(No comment)');
            }

            function findScreen(screenId) {
              return (state.session?.screens || []).find(screen => screen.screenId === screenId) || null;
            }

            function boundsForTarget(target) {
              return target?.boundsInWindow || null;
            }

            function targetLabel(item) {
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

            function sourceHintLabel(item) {
              return (item.sourceCandidates || []).length ? 'Source hint available' : 'No source hint';
            }

            function formatBatchLabel(batch) {
              return 'Batch #' + (batch.sequenceNumber || '-');
            }

            function batchItems(batch) {
              const itemsById = new Map((state.session?.items || []).map(item => [item.itemId, item]));
              return (batch.itemIds || []).map(itemId => itemsById.get(itemId) || { itemId: itemId, missing: true });
            }

            function formatBatchItemSummary(item) {
              if (item.missing) return 'Missing feedback item metadata.';
              return firstLine(item.comment || '(No comment)');
            }

            function formatBatchDetails(batch, items) {
              const count = (batch.itemIds || []).length;
              const itemCount = count + ' item' + (count === 1 ? '' : 's');
              return formatTime(batch.createdAtEpochMillis) + ' | ' + itemCount + ' | ' + (items.map(formatBatchItemSummary).join('; ') || 'No feedback items recorded.');
            }

            async function requestJson(path, options = {}) {
              const response = await fetch(path, options);
              if (!response.ok) {
                throw new Error(await response.text() || 'HTTP ' + response.status);
              }
              return response.json();
            }

            function requestLivePreview() {
              if (previewRequestInFlight && previewRequestInFlightContextGeneration === previewRequestContextGeneration) return previewRequestInFlight;
              const requestContextGeneration = previewRequestContextGeneration;
              const request = requestJson('/api/preview')
                .finally(() => {
                  if (previewRequestInFlight === request) {
                    previewRequestInFlight = null;
                    previewRequestInFlightContextGeneration = null;
                  }
                });
              previewRequestInFlight = request;
              previewRequestInFlightContextGeneration = requestContextGeneration;
              return previewRequestInFlight;
            }

            function invalidatePreviewContext() {
              previewRequestGeneration++;
              previewRequestContextGeneration++;
              state.preview = null;
              previewRequestInFlight = null;
              previewRequestInFlightContextGeneration = null;
            }

            function previewScreenshotUrl(previewId) {
              return '/api/preview/' + encodeURIComponent(previewId) + '/screenshot/full';
            }

            function deviceLabel(device) {
              const labelParts = [device.model || 'Unknown model'];
              const productLabel = device.product || device.deviceName || 'device';
              if (productLabel !== device.serial) {
                labelParts.push(productLabel);
              }
              labelParts.push(device.serial);
              return labelParts.join(' | ');
            }

            function renderDeviceList(payload) {
              const devices = payload.devices || [];
              const previousSelectedDeviceSerial = state.selectedDeviceSerial;
              devicePicker.innerHTML = '';
              if (!devices.length) {
                const selectedSerial = null;
                if (previousSelectedDeviceSerial !== selectedSerial) invalidatePreviewContext();
                state.selectedDeviceSerial = selectedSerial;
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'No devices available';
                devicePicker.appendChild(option);
                devicePicker.disabled = true;
                deviceStatus.textContent = 'No device selected';
                return;
              }

              const selected = devices.find(device => device.selected || device.serial === payload.selectedSerial);
              devicePicker.disabled = false;
              if (!selected) {
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'Select device...';
                option.disabled = true;
                option.selected = true;
                devicePicker.appendChild(option);
              }
              devices.forEach(device => {
                const option = document.createElement('option');
                option.value = device.serial;
                option.textContent = deviceLabel(device) + ' - ' + device.state + (device.state === 'device' ? '' : ' (unavailable)');
                option.disabled = device.state !== 'device';
                option.selected = Boolean(device.selected) || device.serial === payload.selectedSerial;
                devicePicker.appendChild(option);
              });

              const selectedSerial = selected ? selected.serial : null;
              if (previousSelectedDeviceSerial !== selectedSerial) invalidatePreviewContext();
              state.selectedDeviceSerial = selectedSerial;
              deviceStatus.textContent = selected ? 'Selected ' + selected.serial : 'No device selected';
            }

            async function refreshDevices() {
              renderDeviceList(await requestJson('/api/devices'));
            }

            async function selectDevice() {
              const option = devicePicker.selectedOptions[0];
              if (!option || !option.value || option.disabled) return;
              invalidatePreviewContext();
              renderDeviceList(await requestJson('/api/device/select', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serial: option.value })
              }));
              await refreshPreview();
              startLivePreviewPolling();
            }

            async function disconnectDevice() {
              invalidatePreviewContext();
              renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
              render();
              startLivePreviewPolling();
            }

            function configuredPreviewIntervalMs() {
              const rawValue = previewIntervalSelect.value;
              if (rawValue === 'manual') return null;
              const parsed = Number(rawValue || localStorage.getItem(PreviewIntervalStorageKey) || DefaultLivePreviewIntervalMs);
              return Math.max(1000, parsed);
            }

            function shouldPollPreview() {
              return !document.hidden && !addItemsFlow && Boolean(state.selectedDeviceSerial);
            }

            function shouldAutoFetchPreview() {
              return configuredPreviewIntervalMs() != null && shouldPollPreview();
            }

            function startLivePreviewPolling() {
              stopLivePreviewPolling();
              const intervalMs = configuredPreviewIntervalMs();
              if (!intervalMs) return;
              livePreviewTimer = setInterval(() => {
                if (shouldPollPreview()) refreshPreview().catch(showError);
              }, intervalMs);
            }

            function stopLivePreviewPolling() {
              if (livePreviewTimer) clearInterval(livePreviewTimer);
              livePreviewTimer = null;
            }

            function initializePreviewIntervalSelect() {
              const stored = localStorage.getItem(PreviewIntervalStorageKey);
              previewIntervalSelect.value = stored || String(DefaultLivePreviewIntervalMs);
              if (!previewIntervalSelect.value) previewIntervalSelect.value = String(DefaultLivePreviewIntervalMs);
            }

            function latestScreen() {
              return addItemsFlow?.screen || state.preview?.screen || null;
            }

            function clamp(value, min, max) {
              return Math.min(Math.max(value, min), max);
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
              const textValue = (node.text || [])[0] || (node.contentDescription || [])[0] || node.uid;
              return 'Component ' + textValue;
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
              addItemButton.disabled = !addItemsFlow || !currentSelection || !comment.value.trim();
              saveButton.disabled = !addItemsFlow || pendingFeedbackItems.length === 0;
              cancelAddFlowButton.disabled = !addItemsFlow;
              navigationControls.hidden = Boolean(addItemsFlow);
              const item = focusedPendingSelectionSummary();
              selectionSummary.textContent = currentSelection
                ? currentSelection.label + ' - ' + formatBounds(currentSelection.bounds)
                : (item
                  ? 'Focused #' + (focusedPendingItemIndex + 1) + ' - ' + formatBounds(item.bounds)
                  : (addItemsFlow ? 'Select a component or drag a custom area.' : 'No selection.'));
            }

            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false, isFocused = false) {
              if (!bounds) return;
              const left = bounds.left * 100 / image.naturalWidth;
              const top = bounds.top * 100 / image.naturalHeight;
              const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
              const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
              const box = document.createElement('div');
              box.className = 'selection-box' + (isDragPreview ? ' drag-preview' : '') + (isFocused ? ' focused' : '');
              box.style.left = left + '%';
              box.style.top = top + '%';
              box.style.width = width + '%';
              box.style.height = height + '%';
              overlay.appendChild(box);

              if (!labelText) return;
              const label = document.createElement('div');
              label.className = 'selection-label' + (isFocused ? ' focused' : '');
              label.style.left = left + '%';
              label.style.top = top + '%';
              label.textContent = labelText;
              overlay.appendChild(label);
            }

            function renderNumberedFeedbackOverlay(overlay, image) {
              pendingFeedbackItems.forEach((item, index) => {
                renderOverlayBox(overlay, image, item.bounds, '#' + (index + 1), false, index === focusedPendingItemIndex);
              });
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
              if (currentSelection) {
                renderOverlayBox(overlay, image, currentSelection.bounds, currentSelection.label);
              }
              if (dragPreview) {
                renderOverlayBox(overlay, image, dragPreview, null, true);
              }
              updateComposerState();
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

            function selectNodeAtPoint(event, image) {
              const point = naturalPointFromEvent(event, image);
              const screen = latestScreen();
              const mergedNode = smallestContainingNode(nodesForHitTest(screen, root => root?.mergedNodes), point);
              const unmergedNode = smallestContainingNode(nodesForHitTest(screen, root => root?.unmergedNodes), point);
              const node = mergedNode || unmergedNode;
              if (!node) {
                showError(new Error('No component found at that point. Drag to select a custom area.'));
                return;
              }
              currentSelection = {
                targetType: 'node',
                nodeUid: node.uid,
                bounds: node.boundsInWindow,
                label: componentLabel(node)
              };
              focusedPendingItemIndex = null;
              error.textContent = '';
              renderSelectionOverlay();
              updateComposerState();
            }

            function finishAreaSelection(bounds) {
              currentSelection = {
                targetType: 'area',
                bounds: bounds,
                label: 'Custom area ' + Math.round(bounds.right - bounds.left) + 'x' + Math.round(bounds.bottom - bounds.top)
              };
              focusedPendingItemIndex = null;
              error.textContent = '';
              renderSelectionOverlay();
              updateComposerState();
            }

            function clearDragState() {
              if (!dragStart && !dragPreview) return;
              dragStart = null;
              dragPreview = null;
              renderSelectionOverlay();
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
              clearDragState();
              renderSelectionOverlay();
              updateComposerState();
            }

            async function refreshPreview() {
              error.textContent = '';
              if (addItemsFlow) return;
              const requestGeneration = ++previewRequestGeneration;
              const preview = await requestLivePreview();
              if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;
              state.preview = preview;
              render();
            }

            async function startAddItemsFlow() {
              error.textContent = '';
              stopLivePreviewPolling();
              try {
                const addFlowContextGeneration = previewRequestContextGeneration;
                previewRequestGeneration++;
                let preview = state.preview;
                if (previewRequestInFlight || !preview) {
                  preview = await requestLivePreview();
                  if (addFlowContextGeneration !== previewRequestContextGeneration) return;
                  state.preview = preview;
                }
                if (!state.preview) {
                  return;
                }
                addItemsFlow = {
                  previewId: state.preview.previewId,
                  screen: state.preview.screen,
                  screenshotUrl: previewScreenshotUrl(state.preview.previewId)
                };
                pendingFeedbackItems = [];
                focusedPendingItemIndex = null;
                currentSelection = null;
                render();
              } finally {
                if (!addItemsFlow) startLivePreviewPolling();
              }
            }

            function queuePendingFeedbackItem() {
              const feedbackComment = comment.value.trim();
              if (!addItemsFlow) throw new Error('Click Add before selecting feedback.');
              if (!currentSelection) throw new Error('Select a component or area first.');
              if (!feedbackComment) throw new Error('Enter a comment before adding it to the pending list.');
              pendingFeedbackItems.push({
                targetType: currentSelection.targetType,
                nodeUid: currentSelection.nodeUid,
                bounds: currentSelection.bounds,
                comment: feedbackComment
              });
              currentSelection = null;
              focusedPendingItemIndex = null;
              comment.value = '';
              renderSelectionOverlay();
              render();
            }

            function deletePendingFeedbackItem(index) {
              pendingFeedbackItems.splice(index, 1);
              focusedPendingItemIndex = null;
              render();
              renderSelectionOverlay();
            }

            function focusPendingFeedbackItem(index) {
              focusedPendingItemIndex = index;
              currentSelection = null;
              renderSelectionOverlay();
            }

            async function savePendingFeedbackItems() {
              if (!addItemsFlow) return;
              if (!pendingFeedbackItems.length) throw new Error('Add at least one pending feedback item.');
              state.session = await requestJson('/api/items/batch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  previewId: addItemsFlow.previewId,
                  items: pendingFeedbackItems
                })
              });
              addItemsFlow = null;
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              currentSelection = null;
              state.preview = null;
              comment.value = '';
              await refresh();
              startLivePreviewPolling();
            }

            function cancelAddItemsFlow() {
              addItemsFlow = null;
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              currentSelection = null;
              comment.value = '';
              clearDragState();
              render();
              startLivePreviewPolling();
            }

            function renderPendingItems() {
              pendingItems.innerHTML = pendingFeedbackItems.map((item, index) =>
                '<div class="row pending-item-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '">' +
                  '<strong>#' + (index + 1) + ' ' + escapeHtml(firstLine(item.comment)) + '</strong>' +
                  '<span>' + escapeHtml(pendingTargetLabel(item)) + '</span>' +
                  '<div class="toolbar">' +
                    '<button type="button" data-focus-pending="' + index + '">Focus</button>' +
                    '<button type="button" data-delete-pending="' + index + '">Delete</button>' +
                  '</div>' +
                '</div>'
              ).join('') || '<div class="row"><span>No pending feedback items.</span></div>';
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              pendingItems.querySelectorAll('[data-delete-pending]').forEach(button => {
                button.addEventListener('click', () => deletePendingFeedbackItem(Number(button.dataset.deletePending)));
              });
            }

            function savedEvidenceGroups() {
              const groups = new Map();
              (state.session?.items || [])
                .filter(item => item.delivery !== 'sent')
                .forEach(item => {
                  const key = item.screenId;
                  if (!groups.has(key)) groups.set(key, []);
                  groups.get(key).push(item);
                });
              return Array.from(groups.entries()).map(entry => ({ screenId: entry[0], items: entry[1] }));
            }

            function renderSavedEvidenceOverlay(overlay, image, items) {
              items.forEach((item, index) => {
                renderOverlayBox(overlay, image, boundsForTarget(item.target), '#' + (index + 1));
              });
            }

            function renderSavedEvidenceGroups() {
              draftItems.innerHTML = savedEvidenceGroups().map(group => {
                const screen = findScreen(group.screenId);
                return '<details class="evidence-group">' +
                  '<summary>' + escapeHtml(screen?.displayName || 'Saved evidence') + ' | ' + group.items.length + ' item' + (group.items.length === 1 ? '' : 's') + ' | screenshot attached</summary>' +
                  '<div class="saved-evidence-preview" data-screen-id="' + escapeHtml(group.screenId) + '"></div>' +
                  group.items.map((item, index) =>
                    '<div class="row">' +
                      '<strong>' + escapeHtml(formatSavedEvidenceItemLabel(item, index)) + '</strong>' +
                      '<span>' + escapeHtml(targetLabel(item)) + ' | ' + escapeHtml(sourceHintLabel(item)) + '</span>' +
                    '</div>'
                  ).join('') +
                '</details>';
              }).join('') || '<div class="row"><span>No draft feedback items.</span></div>';

              draftItems.querySelectorAll('.saved-evidence-preview').forEach(container => {
                const screenId = container.dataset.screenId;
                const group = savedEvidenceGroups().find(candidate => candidate.screenId === screenId);
                const screen = findScreen(screenId);
                if (!screen?.screenshot?.desktopFullPath || !group) {
                  container.textContent = 'Evidence: screenshot attached';
                  return;
                }
                container.innerHTML =
                  '<div class="snapshot-frame">' +
                    '<img alt="Saved evidence screenshot" src="/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full">' +
                    '<div class="selection-overlay" aria-hidden="true"></div>' +
                  '</div>';
                const image = container.querySelector('img');
                const overlay = container.querySelector('.selection-overlay');
                image.addEventListener('load', () => renderSavedEvidenceOverlay(overlay, image, group.items), { once: true });
              });
            }

            function renderSnapshot() {
              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              snapshotTitle.textContent = addItemsFlow ? 'Frozen Feedback Snapshot' : 'Live Preview';
              if (!hasScreenshot) {
                snapshot.innerHTML = '<div>' + (screen ? 'No screenshot artifact for this preview.' : 'Refresh the live preview to begin.') + '</div>';
                updateComposerState();
                return;
              }
              const src = addItemsFlow?.screenshotUrl || previewScreenshotUrl(state.preview.previewId);
              snapshot.innerHTML =
                '<div class="snapshot-frame">' +
                  '<img id="snapshotImage" alt="PointPatch preview" src="' + src + '">' +
                  '<div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>' +
                '</div>';
              attachSnapshotHandlers();
              renderSelectionOverlay();
            }

            function render() {
              const session = state.session;
              const allItems = session?.items || [];
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
              sessionMeta.textContent = session ? formatSessionHeader(session, allItems.length) : 'No active session';

              renderSnapshot();
              renderPendingItems();
              renderSavedEvidenceGroups();

              const batchIds = new Set(handoffBatches.map(batch => batch.batchId));
              const batchedItemIds = new Set(handoffBatches.flatMap(batch => batch.itemIds || []));
              const batchRows = handoffBatches.map(batch => {
                const items = batchItems(batch);
                return '<div class="row">' +
                  '<strong>' + escapeHtml(formatBatchLabel(batch)) + '</strong>' +
                  '<span>' + escapeHtml(formatBatchDetails(batch, items)) + '</span>' +
                '</div>';
              });
              const unbatchedRows = sentItems
                .filter(item => !item.handoffBatchId || !batchIds.has(item.handoffBatchId) || !batchedItemIds.has(item.itemId))
                .map(item => {
                  const label = item.handoffBatchId ? 'Missing batch metadata' : 'Unbatched sent item';
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(label) + '</strong>' +
                    '<span>' + escapeHtml(firstLine(item.comment || '(No comment)')) + ' | Not sent</span>' +
                  '</div>';
                });
              sentHistory.innerHTML = batchRows.concat(unbatchedRows).join('') || '<div class="row"><span>No sent handoff history.</span></div>';
              updateComposerState();
            }

            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              const activeId = state.session?.sessionId;
              sessions.innerHTML = (response.sessions || []).map((session, index) =>
                '<button class="row session-row ' + (session.sessionId === activeId ? 'active' : '') + '" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<strong>' + escapeHtml(formatSessionLabel(session, index)) + '</strong>' +
                  '<span>' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                '</button>'
              ).join('') || '<div class="row"><span>No saved sessions.</span></div>';
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', () => openSession(row.dataset.sessionId).catch(showError));
              });
            }

            async function refresh() {
              error.textContent = '';
              state.session = await requestJson('/api/session');
              await refreshSessions();
              await refreshDevices();
              render();
            }

            async function openSession(sessionId) {
              error.textContent = '';
              clearSelection();
              addItemsFlow = null;
              pendingFeedbackItems = [];
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              await refresh();
              startLivePreviewPolling();
            }

            async function newSession() {
              error.textContent = '';
              clearSelection();
              addItemsFlow = null;
              pendingFeedbackItems = [];
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refresh();
              startLivePreviewPolling();
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              clearSelection();
              addItemsFlow = null;
              pendingFeedbackItems = [];
              invalidatePreviewContext();
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: state.session.sessionId })
              });
              state.session = null;
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function clearDraft() {
              error.textContent = '';
              if (!window.confirm('Discard all unsent draft feedback items?')) return;
              await requestJson('/api/items/draft', { method: 'DELETE' });
              clearSelection();
              await refresh();
            }

            async function sendDraftToAgent() {
              error.textContent = '';
              await requestJson('/api/agent-handoffs', { method: 'POST' });
              comment.value = '';
              clearSelection();
              await refresh();
            }

            async function navigate(action, extras = {}) {
              error.textContent = '';
              const navigation = await requestJson('/api/navigation', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  action: action,
                  captureAfter: captureAfterNavigation.checked,
                  ...extras
                })
              });
              const captureErrorMessage = navigation.captureError
                ? 'Navigation performed, but capture failed: ' + navigation.captureError
                : '';
              clearSelection();
              await refresh();
              if (!captureAfterNavigation.checked) {
                await refreshPreview();
              }
              if (captureErrorMessage) {
                error.textContent = captureErrorMessage;
              }
            }

            function attachSnapshotHandlers() {
              const image = document.getElementById('snapshotImage');
              if (!image) return;
              image.draggable = false;
              image.addEventListener('dragstart', event => event.preventDefault());
              image.addEventListener('click', event => {
                try {
                  if (suppressNextClick) {
                    suppressNextClick = false;
                    return;
                  }
                  if (!addItemsFlow) {
                    const point = naturalPointFromEvent(event, image);
                    navigate('tap', { x: point.x, y: point.y }).catch(showError);
                    return;
                  }
                  if (!dragStart) {
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointerdown', event => {
                if (!addItemsFlow) return;
                try {
                  image.setPointerCapture?.(event.pointerId);
                  dragStart = naturalPointFromEvent(event, image);
                  dragPreview = normalizeBounds(dragStart, dragStart);
                  renderSelectionOverlay();
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointermove', event => {
                if (!addItemsFlow || !dragStart) return;
                try {
                  dragPreview = normalizeBounds(dragStart, naturalPointFromEvent(event, image));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                if (!addItemsFlow || !dragStart) return;
                try {
                  const end = naturalPointFromEvent(event, image);
                  const bounds = normalizeBounds(dragStart, end);
                  clearDragState();
                  releaseSnapshotPointerCapture(image, event);
                  if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
                    suppressNextClick = true;
                    finishAreaSelection(bounds);
                  } else {
                    renderSelectionOverlay();
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointercancel', clearDragState);
              image.addEventListener('lostpointercapture', clearDragState);
            }

            async function copyMarkdown() {
              error.textContent = '';
              const response = await fetch('/api/export/markdown');
              if (!response.ok) throw new Error(await response.text() || 'HTTP ' + response.status);
              const markdown = await response.text();
              await navigator.clipboard.writeText(markdown);
            }

            document.getElementById('refreshButton').addEventListener('click', () => refreshPreview().catch(showError));
            document.getElementById('addFlowButton').addEventListener('click', () => startAddItemsFlow().catch(showError));
            saveButton.addEventListener('click', () => savePendingFeedbackItems().catch(showError));
            addItemButton.addEventListener('click', () => {
              try {
                queuePendingFeedbackItem();
              } catch (cause) {
                showError(cause);
              }
            });
            document.getElementById('copyMarkdownButton').addEventListener('click', () => copyMarkdown().catch(showError));
            document.getElementById('newSessionButton').addEventListener('click', () => newSession().catch(showError));
            document.getElementById('closeSessionButton').addEventListener('click', () => closeSession().catch(showError));
            document.getElementById('refreshDevicesButton').addEventListener('click', () => refreshDevices().catch(showError));
            document.getElementById('disconnectDeviceButton').addEventListener('click', () => disconnectDevice().catch(showError));
            devicePicker.addEventListener('change', () => selectDevice().catch(showError));
            previewIntervalSelect.addEventListener('change', () => {
              localStorage.setItem(PreviewIntervalStorageKey, previewIntervalSelect.value);
              startLivePreviewPolling();
            });
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              startLivePreviewPolling();
            });
            document.getElementById('clearSelectionButton').addEventListener('click', clearSelection);
            cancelAddFlowButton.addEventListener('click', cancelAddItemsFlow);
            document.getElementById('clearDraftButton').addEventListener('click', () => clearDraft().catch(showError));
            document.getElementById('sendDraftButton').addEventListener('click', () => sendDraftToAgent().catch(showError));
            document.getElementById('backButton').addEventListener('click', () => navigate('back').catch(showError));
            document.getElementById('swipeUpButton').addEventListener('click', () => navigate('swipe', { direction: 'up' }).catch(showError));
            document.getElementById('swipeDownButton').addEventListener('click', () => navigate('swipe', { direction: 'down' }).catch(showError));
            document.getElementById('swipeLeftButton').addEventListener('click', () => navigate('swipe', { direction: 'left' }).catch(showError));
            document.getElementById('swipeRightButton').addEventListener('click', () => navigate('swipe', { direction: 'right' }).catch(showError));
            comment.addEventListener('input', updateComposerState);

            function showError(cause) {
              error.textContent = cause && cause.message ? cause.message : String(cause);
            }

            initializePreviewIntervalSelect();
            refresh()
              .then(() => {
                if (shouldAutoFetchPreview()) return refreshPreview();
                return null;
              })
              .then(startLivePreviewPolling)
              .catch(showError);
          </script>
        </body>
        </html>
    """.trimIndent()
}
