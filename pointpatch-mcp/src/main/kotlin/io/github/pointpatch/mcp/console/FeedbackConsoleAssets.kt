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
              position: relative;
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
            .selection-overlay {
              position: absolute;
              inset: 24px;
              pointer-events: none;
              border: 1px dashed #116a5c;
              border-radius: 6px;
              opacity: 0;
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
            .snapshot img { cursor: crosshair; }
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
                <label><input id="captureAfterNavigation" type="checkbox" checked> Capture after navigation</label>
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
            const selectionSummary = document.getElementById('selectionSummary');
            const addItemButton = document.getElementById('addItemButton');
            let snapshotMode = 'select';

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
                countLabel(session.screensCount, 'screen', 'screens'),
                countLabel(session.draftItemsCount, 'draft item', 'draft items'),
                countLabel(session.sentBatchesCount, 'sent batch', 'sent batches'),
                `updated ${'$'}{formatTime(session.updatedAtEpochMillis)}`,
                `id ${'$'}{shortId(session.sessionId)}`
              ].join(' | ');
            }

            function formatScreenLabel(screen, index) {
              return `Screen ${'$'}{index + 1} - ${'$'}{screen.displayName || screen.activityName || 'Screen'}`;
            }

            function findScreen(screenId) {
              return (state.session?.screens || []).find(screen => screen.screenId === screenId) || null;
            }

            function screenLabelForId(screenId) {
              const screen = findScreen(screenId);
              if (!screen) return 'Unknown screen';
              return formatScreenLabel(screen, (state.session?.screens || []).indexOf(screen));
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
              if (item.missing) return `Missing item ${'$'}{shortId(item.itemId)}`;
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

            function switchSnapshotMode(mode) {
              snapshotMode = mode;
              modeSelect.classList.toggle('active', mode === 'select');
              modeNavigate.classList.toggle('active', mode === 'navigate');
            }

            function clearVisibleSelection() {
              selectionSummary.textContent = 'No selection.';
              addItemButton.disabled = true;
            }

            function clearComment() {
              comment.value = '';
            }

            function latestScreen() {
              const all = state.session?.screens || [];
              return all.length ? all[all.length - 1] : null;
            }

            function render() {
              const session = state.session;
              const allItems = session?.items || [];
              const queuedItems = allItems.filter(item => item.delivery !== 'sent');
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
              sessionMeta.textContent = session
                ? `${'$'}{session.packageName} | ${'$'}{session.status} | ${'$'}{shortId(session.sessionId)} | ${'$'}{allItems.length} item(s)`
                : 'No active session';

              screens.innerHTML = (session?.screens || []).map((screen, index) => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(formatScreenLabel(screen, index))}</strong>
                  <span>${'$'}{escapeHtml(formatTime(screen.capturedAtEpochMillis))} | ${'$'}{escapeHtml(shortId(screen.screenId))}</span>
                </div>
              `).join('') || '<div class="row"><span>No screens captured.</span></div>';

              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              snapshot.innerHTML = hasScreenshot
                ? `<div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div><img alt="Latest PointPatch snapshot" src="/api/screens/${'$'}{encodeURIComponent(screen.screenId)}/screenshot/full">`
                : `<div id="selectionOverlay" class="selection-overlay" aria-hidden="true"></div><div>${'$'}{screen ? 'No screenshot artifact for latest screen.' : 'Capture a screen to begin.'}</div>`;
              attachSnapshotTapHandler();

              draftItems.innerHTML = queuedItems.map(item => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(formatItemLabel(item))}</strong>
                  <span>${'$'}{escapeHtml(screenLabelForId(item.screenId))} | ${'$'}{escapeHtml(targetLabel(item))} | ${'$'}{escapeHtml(deliveryLabel(item.delivery))}</span>
                  <span>${'$'}{escapeHtml(humanize(item.status))} | item ${'$'}{escapeHtml(shortId(item.itemId))} | screen ${'$'}{escapeHtml(shortId(item.screenId))}</span>
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
                  <span>batch ${'$'}{escapeHtml(shortId(batch.batchId))} | items ${'$'}{escapeHtml((batch.itemIds || []).map(shortId).join(', ') || '-')}</span>
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
                  <span>batch ${'$'}{escapeHtml(shortId(item.handoffBatchId))} | item ${'$'}{escapeHtml(shortId(item.itemId))} | screen ${'$'}{escapeHtml(shortId(item.screenId))}</span>
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
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId })
              });
              await refresh();
            }

            async function newSession() {
              error.textContent = '';
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
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: state.session.sessionId })
              });
              await refresh();
            }

            async function capture() {
              error.textContent = '';
              await requestJson('/api/capture', { method: 'POST' });
              await refresh();
            }

            async function addItem() {
              error.textContent = '';
              const feedbackComment = comment.value.trim();
              if (!feedbackComment) {
                error.textContent = 'Enter a comment before adding feedback.';
                return;
              }
              const screen = latestScreen();
              if (!screen) {
                error.textContent = 'Capture a screen before adding feedback.';
                return;
              }
              await requestJson('/api/items', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  screenId: screen.screenId,
                  comment: feedbackComment,
                  targetType: 'area',
                  bounds: { left: 0, top: 0, right: 100, bottom: 100 }
                })
              });
              comment.value = '';
              await refresh();
            }

            async function clearDraft() {
              error.textContent = '';
              if (!window.confirm('Clear all draft feedback items?')) return;
              state.session = await requestJson('/api/items/draft', { method: 'DELETE' });
              await refresh();
            }

            async function sendDraft() {
              error.textContent = '';
              state.session = await requestJson('/api/agent-handoffs', { method: 'POST' });
              clearComment();
              clearVisibleSelection();
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
              await refresh();
              if (captureErrorMessage) {
                error.textContent = captureErrorMessage;
              }
            }

            function attachSnapshotTapHandler() {
              const image = snapshot.querySelector('img');
              if (!image) return;
              image.addEventListener('click', event => {
                if (snapshotMode !== 'navigate') {
                  error.textContent = 'Switch to Navigate mode to tap the device.';
                  return;
                }
                const rect = image.getBoundingClientRect();
                if (!image.naturalWidth || !image.naturalHeight || !rect.width || !rect.height) {
                  showError(new Error('Snapshot image dimensions are not available for tap navigation.'));
                  return;
                }
                navigate('tap', {
                  x: (event.clientX - rect.left) * image.naturalWidth / rect.width,
                  y: (event.clientY - rect.top) * image.naturalHeight / rect.height
                }).catch(showError);
              });
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
            modeSelect.addEventListener('click', () => switchSnapshotMode('select'));
            modeNavigate.addEventListener('click', () => switchSnapshotMode('navigate'));
            document.getElementById('clearSelectionButton').addEventListener('click', clearVisibleSelection);
            document.getElementById('clearCommentButton').addEventListener('click', clearComment);
            document.getElementById('clearDraftButton').addEventListener('click', () => clearDraft().catch(showError));
            document.getElementById('sendDraftButton').addEventListener('click', () => sendDraft().catch(showError));
            document.getElementById('backButton').addEventListener('click', () => navigate('back').catch(showError));
            document.getElementById('swipeUpButton').addEventListener('click', () => navigate('swipe', { direction: 'up' }).catch(showError));
            document.getElementById('swipeDownButton').addEventListener('click', () => navigate('swipe', { direction: 'down' }).catch(showError));
            document.getElementById('swipeLeftButton').addEventListener('click', () => navigate('swipe', { direction: 'left' }).catch(showError));
            document.getElementById('swipeRightButton').addEventListener('click', () => navigate('swipe', { direction: 'right' }).catch(showError));

            function showError(cause) {
              error.textContent = cause && cause.message ? cause.message : String(cause);
            }

            refresh().catch(showError);
          </script>
        </body>
        </html>
    """.trimIndent()
}
