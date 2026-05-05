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
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 16px;
              padding: 18px 24px;
              border-bottom: 1px solid #dde2ea;
              background: #ffffff;
            }
            h1 { margin: 0; font-size: 20px; font-weight: 700; letter-spacing: 0; }
            .meta { color: #5d6675; font-size: 13px; }
            main {
              display: grid;
              grid-template-columns: minmax(220px, 300px) minmax(320px, 1fr) minmax(280px, 380px);
              gap: 1px;
              min-height: calc(100vh - 69px);
              background: #dde2ea;
            }
            section {
              min-width: 0;
              background: #ffffff;
              padding: 18px;
              overflow: auto;
            }
            h2 { margin: 0 0 12px; font-size: 14px; font-weight: 700; letter-spacing: 0; }
            h2.section-heading { margin-top: 18px; }
            select {
              min-height: 34px;
              border: 1px solid #b9c2cf;
              border-radius: 6px;
              background: #ffffff;
              color: #171b22;
              padding: 0 10px;
              font: inherit;
              font-size: 13px;
            }
            button {
              border: 1px solid #b9c2cf;
              border-radius: 6px;
              background: #ffffff;
              color: #171b22;
              min-height: 34px;
              padding: 0 12px;
              font: inherit;
              font-size: 13px;
              cursor: pointer;
            }
            button.primary { background: #116a5c; border-color: #116a5c; color: #ffffff; }
            button:disabled { opacity: .55; cursor: default; }
            .device-strip {
              display: flex;
              align-items: center;
              flex-wrap: wrap;
              gap: 8px;
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
            textarea {
              width: 100%;
              min-height: 96px;
              resize: vertical;
              border: 1px solid #c8d0dc;
              border-radius: 6px;
              padding: 10px;
              font: inherit;
              font-size: 13px;
            }
            .toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
            .toolbar label {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              color: #3f4754;
              font-size: 13px;
            }
            .list { display: grid; gap: 8px; }
            .row {
              border: 1px solid #e1e6ee;
              border-radius: 8px;
              padding: 10px;
              background: #fbfcfe;
            }
            .session-row {
              display: block;
              width: 100%;
              min-height: 0;
              text-align: left;
            }
            .session-row.active {
              border-color: #116a5c;
              background: #eef7f5;
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
            .segmented {
              display: inline-flex;
              overflow: hidden;
              border: 1px solid #b9c2cf;
              border-radius: 6px;
              background: #ffffff;
            }
            .segmented button {
              border: 0;
              border-radius: 0;
              min-height: 32px;
            }
            .segmented button + button { border-left: 1px solid #b9c2cf; }
            .segmented button.active {
              background: #116a5c;
              color: #ffffff;
            }
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
            .snapshot-frame img { display: block; max-width: 100%; height: auto; }
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
            .snapshot-frame img { cursor: crosshair; }
            .error { color: #9c2d2d; font-size: 13px; min-height: 18px; }
            @media (max-width: 900px) {
              header { align-items: flex-start; flex-direction: column; }
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
              <button id="refreshDevicesButton">Refresh Devices</button>
              <button id="disconnectDeviceButton">Disconnect</button>
              <span id="deviceStatus" class="status-pill">No device selected</span>
            </div>
            <div class="toolbar">
              <button id="refreshButton">Refresh</button>
              <button id="captureButton" class="primary">Capture</button>
              <button id="copyMarkdownButton">Copy Agent Context</button>
            </div>
          </header>
          <main>
            <section class="sidebar">
              <div class="toolbar">
                <button id="newSessionButton">New Session</button>
                <button id="closeSessionButton">Close</button>
              </div>
              <h2>Sessions</h2>
              <div id="sessions" class="list"></div>
              <h2 class="section-heading">Screens</h2>
              <div id="screens" class="list"></div>
              <h2 class="section-heading">Sent History</h2>
              <div id="sentHistory" class="list"></div>
            </section>
            <section class="snapshot-pane">
              <div class="snapshot-header">
                <h2>Snapshot</h2>
                <div class="segmented" role="group" aria-label="Snapshot mode">
                  <button id="modeSelect" class="active" type="button">Select</button>
                  <button id="modeNavigate" type="button">Navigate</button>
                </div>
              </div>
              <div id="navigationControls" class="toolbar">
                <button id="backButton">Back</button>
                <button id="swipeUpButton">Swipe Up</button>
                <button id="swipeDownButton">Swipe Down</button>
                <button id="swipeLeftButton">Swipe Left</button>
                <button id="swipeRightButton">Swipe Right</button>
                <label><input id="captureAfterNavigation" type="checkbox"> Capture after navigation</label>
              </div>
              <div id="snapshot" class="snapshot">
                <div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>
                <div>Capture a screen to begin.</div>
              </div>
            </section>
            <section class="queue-pane">
              <h2>Current Selection</h2>
              <div id="selectionSummary" class="selection-summary">No selection.</div>
              <div class="toolbar">
                <button id="clearSelectionButton">Clear Selection</button>
                <button id="clearCommentButton">Clear Comment</button>
              </div>
              <h2 class="section-heading">Comment</h2>
              <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
              <div class="toolbar">
                <button id="addItemButton" class="primary" disabled>Add Item</button>
                <button id="sendDraftButton">Send Draft to Agent</button>
                <button id="clearDraftButton">Clear Draft</button>
              </div>
              <h2 class="section-heading">Draft</h2>
              <div id="draftItems" class="list"></div>
              <p id="error" class="error"></p>
            </section>
          </main>
          <script>
            const state = { session: null };
            const sessionMeta = document.getElementById('sessionMeta');
            const sessions = document.getElementById('sessions');
            const screens = document.getElementById('screens');
            const sentHistory = document.getElementById('sentHistory');
            const snapshot = document.getElementById('snapshot');
            const draftItems = document.getElementById('draftItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const captureAfterNavigation = document.getElementById('captureAfterNavigation');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const modeSelect = document.getElementById('modeSelect');
            const modeNavigate = document.getElementById('modeNavigate');
            const navigationControls = document.getElementById('navigationControls');
            const selectionSummary = document.getElementById('selectionSummary');
            const addItemButton = document.getElementById('addItemButton');
            const Mode = { SELECT: 'select', NAVIGATE: 'navigate' };
            let currentMode = Mode.SELECT;
            let currentScreenId = null;
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

            function shortId(value) {
              return text(value).slice(0, 8) + (text(value).length > 8 ? '...' : '');
            }

            function formatTime(epochMillis) {
              if (!epochMillis) return '-';
              return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            }

            function formatSessionLabel(session, index) {
              return `Session ${'$'}{index + 1} - ${'$'}{session.status}`;
            }

            function countLabel(count, singular, plural) {
              return `${'$'}{count} ${'$'}{count === 1 ? singular : plural}`;
            }

            function formatSessionSummary(session) {
              return [
                session.packageName,
                humanize(session.status),
                countLabel(session.screensCount, 'screen', 'screens'),
                countLabel(session.draftItemsCount, 'draft item', 'draft items'),
                countLabel(session.sentBatchesCount, 'sent batch', 'sent batches'),
                `updated ${'$'}{formatTime(session.updatedAtEpochMillis)}`
              ].join(' | ');
            }

            function formatSessionHeader(session, itemCount) {
              return [
                session.packageName,
                humanize(session.status),
                countLabel(session.screens?.length || 0, 'screen', 'screens'),
                countLabel(itemCount, 'feedback item', 'feedback items'),
                `updated ${'$'}{formatTime(session.updatedAtEpochMillis)}`
              ].join(' | ');
            }

            function formatScreenLabel(screen, index) {
              return `Screen ${'$'}{index + 1} - ${'$'}{screen.displayName || screen.activityName || 'Screen'}`;
            }

            function screenshotDimensions(screen) {
              const screenshot = screen?.screenshot;
              return screenshot?.width && screenshot?.height ? `${'$'}{screenshot.width}x${'$'}{screenshot.height}` : null;
            }

            function formatScreenDetails(screen) {
              return [
                formatTime(screen.capturedAtEpochMillis),
                screen.activityName || screen.displayName,
                screenshotDimensions(screen),
                countLabel(screenFeedbackCount(screen.screenId), 'feedback item', 'feedback items')
              ].filter(Boolean).join(' | ');
            }

            function findScreen(screenId) {
              return (state.session?.screens || []).find(screen => screen.screenId === screenId) || null;
            }

            function screenLabelForId(screenId) {
              const screen = findScreen(screenId);
              if (!screen) return 'Unknown screen';
              return formatScreenLabel(screen, (state.session?.screens || []).indexOf(screen));
            }

            function screenFeedbackCount(screenId) {
              return (state.session?.items || []).filter(item => item.screenId === screenId).length;
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

            function targetLabel(item) {
              const target = item?.target || {};
              if (target.type === 'semantics_node' || target.nodeUid) return 'Node target';
              if (target.type === 'visual_area' || target.boundsInWindow) return 'Area target';
              return 'Unknown target';
            }

            function deliveryLabel(delivery) {
              if (!delivery || delivery === 'draft') return 'Not sent';
              if (delivery === 'sent') return 'Sent';
              return humanize(delivery);
            }

            function formatItemLabel(item) {
              const number = item.sequenceNumber ? `#${'$'}{item.sequenceNumber}` : '#-';
              const title = firstLine(item.comment || '(No comment)');
              return `${'$'}{number} ${'$'}{title}`;
            }

            function formatBatchLabel(batch) {
              return `Batch #${'$'}{batch.sequenceNumber || '-'}`;
            }

            function batchItems(batch) {
              const itemsById = new Map((state.session?.items || []).map(item => [item.itemId, item]));
              return (batch.itemIds || []).map(itemId => itemsById.get(itemId) || { itemId, missing: true });
            }

            function formatBatchItemSummary(item) {
              if (item.missing) return 'Missing feedback item metadata.';
              return formatItemLabel(item);
            }

            function formatBatchDetails(batch, items) {
              const count = (batch.itemIds || []).length;
              const itemCount = `${'$'}{count} item${'$'}{count === 1 ? '' : 's'}`;
              const screenDetails = Array.from(new Set(
                items
                  .filter(item => !item.missing)
                  .map(item => `${'$'}{screenLabelForId(item.screenId)} - ${'$'}{humanize(item.status)}`)
              ));
              return `${'$'}{formatTime(batch.createdAtEpochMillis)} | ${'$'}{itemCount} | ${'$'}{screenDetails.join('; ') || 'No item screen details'}`;
            }

            function firstLine(value) {
              return text(value).split(/\r?\n/)[0] || '(No comment)';
            }

            async function requestJson(path, options = {}) {
              const response = await fetch(path, options);
              if (!response.ok) {
                throw new Error(await response.text() || `HTTP ${'$'}{response.status}`);
              }
              return response.json();
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
              devicePicker.innerHTML = '';
              if (!devices.length) {
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
                option.textContent = `${'$'}{deviceLabel(device)} - ${'$'}{device.state}${'$'}{device.state === 'device' ? '' : ' (unavailable)'}`;
                option.disabled = device.state !== 'device';
                option.selected = Boolean(device.selected) || device.serial === payload.selectedSerial;
                devicePicker.appendChild(option);
              });

              deviceStatus.textContent = selected
                ? `Selected ${'$'}{selected.serial}`
                : 'No device selected';
            }

            async function refreshDevices() {
              renderDeviceList(await requestJson('/api/devices'));
            }

            async function selectDevice() {
              const option = devicePicker.selectedOptions[0];
              if (!option || !option.value || option.disabled) return;
              renderDeviceList(await requestJson('/api/device/select', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serial: option.value })
              }));
            }

            async function disconnectDevice() {
              renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
            }

            function latestScreen() {
              const all = state.session?.screens || [];
              if (currentScreenId) {
                return all.find(screen => screen.screenId === currentScreenId) || all[all.length - 1] || null;
              }
              return all.length ? all[all.length - 1] : null;
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
              return `Component ${'$'}{textValue}`;
            }

            function formatBounds(bounds) {
              return `${'$'}{Math.round(bounds.left)},${'$'}{Math.round(bounds.top)} - ${'$'}{Math.round(bounds.right)},${'$'}{Math.round(bounds.bottom)}`;
            }

            function updateComposerState() {
              addItemButton.disabled = !currentSelection || !comment.value.trim();
              selectionSummary.textContent = currentSelection
                ? `${'$'}{currentSelection.label} - ${'$'}{formatBounds(currentSelection.bounds)}`
                : 'No selection.';
            }

            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false) {
              if (!bounds) return;
              const left = bounds.left * 100 / image.naturalWidth;
              const top = bounds.top * 100 / image.naturalHeight;
              const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
              const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
              const box = document.createElement('div');
              box.className = isDragPreview ? 'selection-box drag-preview' : 'selection-box';
              box.style.left = `${'$'}{left}%`;
              box.style.top = `${'$'}{top}%`;
              box.style.width = `${'$'}{width}%`;
              box.style.height = `${'$'}{height}%`;
              overlay.appendChild(box);

              if (!labelText) return;
              const label = document.createElement('div');
              label.className = 'selection-label';
              label.style.left = `${'$'}{left}%`;
              label.style.top = `${'$'}{top}%`;
              label.textContent = labelText;
              overlay.appendChild(label);
            }

            function renderSelectionOverlay() {
              const overlay = document.getElementById('selectionOverlay');
              const image = document.getElementById('snapshotImage');
              if (!overlay) {
                updateComposerState();
                return;
              }
              overlay.innerHTML = '';
              if ((!currentSelection && !dragPreview) || !image) {
                updateComposerState();
                return;
              }
              if (!image.naturalWidth || !image.naturalHeight) {
                image.addEventListener('load', renderSelectionOverlay, { once: true });
                updateComposerState();
                return;
              }

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
                .map((node, order) => ({ node, order }))
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
              currentScreenId = screen.screenId;
              currentSelection = {
                targetType: 'node',
                nodeUid: node.uid,
                bounds: node.boundsInWindow,
                label: componentLabel(node)
              };
              error.textContent = '';
              renderSelectionOverlay();
              updateComposerState();
            }

            function finishAreaSelection(bounds) {
              const screen = latestScreen();
              if (screen) currentScreenId = screen.screenId;
              currentSelection = {
                targetType: 'area',
                bounds,
                label: `Custom area ${'$'}{Math.round(bounds.right - bounds.left)}x${'$'}{Math.round(bounds.bottom - bounds.top)}`
              };
              error.textContent = '';
              renderSelectionOverlay();
              updateComposerState();
            }

            function setMode(mode) {
              currentMode = mode;
              if (mode !== Mode.SELECT) clearDragState();
              modeSelect.classList.toggle('active', mode === Mode.SELECT);
              modeNavigate.classList.toggle('active', mode === Mode.NAVIGATE);
              navigationControls.hidden = mode !== Mode.NAVIGATE;
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
              currentScreenId = null;
              currentSelection = null;
              clearDragState();
              renderSelectionOverlay();
              updateComposerState();
            }

            function clearComment() {
              comment.value = '';
              updateComposerState();
            }

            function render() {
              const session = state.session;
              const allItems = session?.items || [];
              const queuedItems = allItems.filter(item => item.delivery !== 'sent');
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
              sessionMeta.textContent = session
                ? formatSessionHeader(session, allItems.length)
                : 'No active session';

              screens.innerHTML = (session?.screens || []).map((screen, index) => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(formatScreenLabel(screen, index))}</strong>
                  <span>${'$'}{escapeHtml(formatScreenDetails(screen))}</span>
                  <button class="delete-screen-button" type="button" data-screen-id="${'$'}{escapeHtml(screen.screenId)}">Delete</button>
                </div>
              `).join('') || '<div class="row"><span>No screens captured.</span></div>';
              document.querySelectorAll('.delete-screen-button').forEach(button => {
                button.addEventListener('click', event => {
                  event.stopPropagation();
                  deleteScreen(button.dataset.screenId).catch(showError);
                });
              });

              const screen = latestScreen();
              if (currentSelection) {
                currentScreenId = screen?.screenId || null;
              }
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              snapshot.innerHTML = hasScreenshot
                ? `<div class="snapshot-frame">
                     <img id="snapshotImage" alt="Latest PointPatch snapshot" src="/api/screens/${'$'}{encodeURIComponent(screen.screenId)}/screenshot/full">
                     <div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div>
                   </div>`
                : `<div>${'$'}{screen ? 'No screenshot artifact for selected screen.' : 'Capture a screen to begin.'}</div>`;
              attachSnapshotHandlers();
              renderSelectionOverlay();

              draftItems.innerHTML = queuedItems.map(item => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(formatItemLabel(item))}</strong>
                  <span>${'$'}{escapeHtml(screenLabelForId(item.screenId))} | ${'$'}{escapeHtml(targetLabel(item))} | ${'$'}{escapeHtml(deliveryLabel(item.delivery))}</span>
                  <span>${'$'}{escapeHtml(humanize(item.status))}</span>
                </div>
              `).join('') || '<div class="row"><span>No draft feedback items.</span></div>';

              const batchIds = new Set(handoffBatches.map(batch => batch.batchId));
              const batchedItemIds = new Set(handoffBatches.flatMap(batch => batch.itemIds || []));
              const batchRows = handoffBatches.map(batch => {
                const items = batchItems(batch);
                return `
                <div class="row">
                  <strong>${'$'}{escapeHtml(formatBatchLabel(batch))}</strong>
                  <span>${'$'}{escapeHtml(formatBatchDetails(batch, items))}</span>
                  <span>${'$'}{escapeHtml(items.map(formatBatchItemSummary).join('; ') || 'No feedback items recorded.')}</span>
                </div>
              `;
              });
              const unbatchedRows = sentItems
                .filter(item => !item.handoffBatchId || !batchIds.has(item.handoffBatchId) || !batchedItemIds.has(item.itemId))
                .map(item => {
                  const label = item.handoffBatchId ? 'Missing batch metadata' : 'Unbatched sent item';
                  return `
                <div class="row">
                  <strong>${'$'}{escapeHtml(label)}</strong>
                  <span>${'$'}{escapeHtml(formatItemLabel(item))} | ${'$'}{escapeHtml(formatTime(item.sentAtEpochMillis))} | ${'$'}{escapeHtml(screenLabelForId(item.screenId))} - ${'$'}{escapeHtml(humanize(item.status))}</span>
                </div>
              `;
                });
              sentHistory.innerHTML = batchRows.concat(unbatchedRows).join('') || '<div class="row"><span>No sent handoff history.</span></div>';
            }

            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              const activeId = state.session?.sessionId;
              sessions.innerHTML = (response.sessions || []).map((session, index) => `
                <button class="row session-row ${'$'}{session.sessionId === activeId ? 'active' : ''}" data-session-id="${'$'}{escapeHtml(session.sessionId)}">
                  <strong>${'$'}{escapeHtml(formatSessionLabel(session, index))}</strong>
                  <span>${'$'}{escapeHtml(formatSessionSummary(session))}</span>
                </button>
              `).join('') || '<div class="row"><span>No saved sessions.</span></div>';
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', () => openSession(row.dataset.sessionId).catch(showError));
              });
            }

            async function refresh() {
              error.textContent = '';
              state.session = await requestJson('/api/session');
              await refreshSessions();
              render();
              await refreshDevices();
            }

            async function openSession(sessionId) {
              error.textContent = '';
              currentScreenId = null;
              clearSelection();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId })
              });
              await refresh();
            }

            async function newSession() {
              error.textContent = '';
              currentScreenId = null;
              clearSelection();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ newSession: true })
              });
              await refresh();
            }

            async function closeSession() {
              error.textContent = '';
              if (!state.session) return;
              currentScreenId = null;
              clearSelection();
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

            async function capture() {
              error.textContent = '';
              currentScreenId = null;
              clearSelection();
              await requestJson('/api/capture', { method: 'POST' });
              await refresh();
            }

            async function deleteScreen(screenId) {
              error.textContent = '';
              if (!screenId) return;
              const feedbackCount = screenFeedbackCount(screenId);
              const message = feedbackCount > 0
                ? `Delete this captured screen and ${'$'}{feedbackCount} linked feedback item${'$'}{feedbackCount === 1 ? '' : 's'}?`
                : 'Delete this captured screen?';
              if (!window.confirm(message)) return;
              currentScreenId = null;
              clearSelection();
              state.session = await requestJson(`/api/screens/${'$'}{encodeURIComponent(screenId)}`, { method: 'DELETE' });
              await refresh();
            }

            async function addItem() {
              error.textContent = '';
              const feedbackComment = comment.value.trim();
              if (!feedbackComment) {
                throw new Error('Enter a comment before adding feedback.');
              }
              const screen = latestScreen();
              if (!screen || !currentSelection) {
                throw new Error('Select a component or area before adding feedback.');
              }
              await requestJson('/api/items', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  screenId: screen.screenId,
                  comment: feedbackComment,
                  targetType: currentSelection.targetType,
                  nodeUid: currentSelection.nodeUid,
                  bounds: currentSelection.bounds
                })
              });
              comment.value = '';
              await refresh();
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
                  action,
                  captureAfter: captureAfterNavigation.checked,
                  ...extras
                })
              });
              const captureErrorMessage = navigation.captureError
                ? `Navigation performed, but capture failed: ${'$'}{navigation.captureError}`
                : '';
              currentScreenId = null;
              clearSelection();
              await refresh();
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
                  if (currentMode === Mode.NAVIGATE) {
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
                if (currentMode !== Mode.SELECT) return;
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
                if (currentMode !== Mode.SELECT || !dragStart) return;
                try {
                  dragPreview = normalizeBounds(dragStart, naturalPointFromEvent(event, image));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                if (currentMode !== Mode.SELECT || !dragStart) return;
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
              if (!response.ok) throw new Error(await response.text() || `HTTP ${'$'}{response.status}`);
              const markdown = await response.text();
              await navigator.clipboard.writeText(markdown);
            }

            document.getElementById('refreshButton').addEventListener('click', () => refresh().catch(showError));
            document.getElementById('captureButton').addEventListener('click', () => capture().catch(showError));
            addItemButton.addEventListener('click', () => addItem().catch(showError));
            document.getElementById('copyMarkdownButton').addEventListener('click', () => copyMarkdown().catch(showError));
            document.getElementById('newSessionButton').addEventListener('click', () => newSession().catch(showError));
            document.getElementById('closeSessionButton').addEventListener('click', () => closeSession().catch(showError));
            document.getElementById('refreshDevicesButton').addEventListener('click', () => refreshDevices().catch(showError));
            document.getElementById('disconnectDeviceButton').addEventListener('click', () => disconnectDevice().catch(showError));
            devicePicker.addEventListener('change', () => selectDevice().catch(showError));
            modeSelect.addEventListener('click', () => setMode(Mode.SELECT));
            modeNavigate.addEventListener('click', () => setMode(Mode.NAVIGATE));
            document.getElementById('clearSelectionButton').addEventListener('click', clearSelection);
            document.getElementById('clearCommentButton').addEventListener('click', clearComment);
            document.getElementById('clearDraftButton').addEventListener('click', () => clearDraft().catch(showError));
            document.getElementById('sendDraftButton').addEventListener('click', () => sendDraftToAgent().catch(showError));
            document.getElementById('backButton').addEventListener('click', () => navigate('back').catch(showError));
            document.getElementById('swipeUpButton').addEventListener('click', () => navigate('swipe', { direction: 'up' }).catch(showError));
            document.getElementById('swipeDownButton').addEventListener('click', () => navigate('swipe', { direction: 'down' }).catch(showError));
            document.getElementById('swipeLeftButton').addEventListener('click', () => navigate('swipe', { direction: 'left' }).catch(showError));
            document.getElementById('swipeRightButton').addEventListener('click', () => navigate('swipe', { direction: 'right' }).catch(showError));
            comment.addEventListener('input', updateComposerState);
            setMode(Mode.SELECT);

            function showError(cause) {
              error.textContent = cause && cause.message ? cause.message : String(cause);
            }

            refresh().catch(showError);
          </script>
        </body>
        </html>
    """.trimIndent()
}
