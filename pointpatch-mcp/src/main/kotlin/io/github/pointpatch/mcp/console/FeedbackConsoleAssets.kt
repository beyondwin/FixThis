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
              grid-template-columns: minmax(180px, 260px) minmax(280px, 1fr) minmax(260px, 360px);
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
            <div class="toolbar">
              <button id="refreshButton">Refresh</button>
              <button id="captureButton" class="primary">Capture</button>
              <button id="copyMarkdownButton">Copy Markdown</button>
            </div>
          </header>
          <main>
            <section>
              <div class="toolbar">
                <button id="newSessionButton">New Session</button>
                <button id="closeSessionButton">Close</button>
              </div>
              <h2>Sessions</h2>
              <div id="sessions" class="list"></div>
              <h2 class="section-heading">Screens</h2>
              <div id="screens" class="list"></div>
            </section>
            <section>
              <h2>Snapshot</h2>
              <div class="toolbar">
                <button id="backButton">Back</button>
                <button id="swipeUpButton">Swipe Up</button>
                <button id="swipeDownButton">Swipe Down</button>
                <button id="swipeLeftButton">Swipe Left</button>
                <button id="swipeRightButton">Swipe Right</button>
                <label><input id="captureAfterNavigation" type="checkbox" checked> Capture after navigation</label>
              </div>
              <div id="snapshot" class="snapshot">Capture a screen to begin.</div>
            </section>
            <section>
              <h2>Queue</h2>
              <div class="toolbar">
                <button id="addItemButton" class="primary">Add Item</button>
              </div>
              <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
              <div id="items" class="list" style="margin-top: 12px;"></div>
              <p id="error" class="error"></p>
            </section>
          </main>
          <script>
            const state = { session: null };
            const sessionMeta = document.getElementById('sessionMeta');
            const sessions = document.getElementById('sessions');
            const screens = document.getElementById('screens');
            const snapshot = document.getElementById('snapshot');
            const items = document.getElementById('items');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const captureAfterNavigation = document.getElementById('captureAfterNavigation');

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

            async function requestJson(path, options = {}) {
              const response = await fetch(path, options);
              if (!response.ok) {
                throw new Error(await response.text() || `HTTP ${'$'}{response.status}`);
              }
              return response.json();
            }

            function latestScreen() {
              const all = state.session?.screens || [];
              return all.length ? all[all.length - 1] : null;
            }

            function render() {
              const session = state.session;
              sessionMeta.textContent = session
                ? `${'$'}{session.packageName} | ${'$'}{session.status} | ${'$'}{session.sessionId} | ${'$'}{session.items.length} item(s)`
                : 'No active session';

              screens.innerHTML = (session?.screens || []).map(screen => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(screen.displayName)}</strong>
                  <span>${'$'}{escapeHtml(screen.screenId)}</span>
                </div>
              `).join('') || '<div class="row"><span>No screens captured.</span></div>';

              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              snapshot.innerHTML = hasScreenshot
                ? `<img alt="Latest PointPatch snapshot" src="/api/screens/${'$'}{encodeURIComponent(screen.screenId)}/screenshot/full">`
                : `<div>${'$'}{screen ? 'No screenshot artifact for latest screen.' : 'Capture a screen to begin.'}</div>`;
              attachSnapshotTapHandler();

              items.innerHTML = (session?.items || []).map(item => `
                <div class="row">
                  <strong>${'$'}{escapeHtml(item.comment || '(No comment)')}</strong>
                  <span>${'$'}{escapeHtml(item.status)} | ${'$'}{escapeHtml(item.itemId)}</span>
                </div>
              `).join('') || '<div class="row"><span>No feedback items queued.</span></div>';
            }

            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              const activeId = state.session?.sessionId;
              sessions.innerHTML = (response.sessions || []).map(session => `
                <button class="row session-row ${'$'}{session.sessionId === activeId ? 'active' : ''}" data-session-id="${'$'}{escapeHtml(session.sessionId)}">
                  <strong>${'$'}{escapeHtml(session.packageName)}</strong>
                  <span>${'$'}{escapeHtml(session.sessionId)} | ${'$'}{escapeHtml(session.status)} | ${'$'}{session.itemsCount} item(s)</span>
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
              await requestJson('/api/items', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  comment: comment.value,
                  bounds: { left: 0, top: 0, right: 100, bottom: 100 }
                })
              });
              comment.value = '';
              await refresh();
            }

            async function navigate(action, extras = {}) {
              error.textContent = '';
              await requestJson('/api/navigation', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  action,
                  captureAfter: captureAfterNavigation.checked,
                  ...extras
                })
              });
              await refresh();
            }

            function attachSnapshotTapHandler() {
              const image = snapshot.querySelector('img');
              if (!image) return;
              image.addEventListener('click', event => {
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
            document.getElementById('addItemButton').addEventListener('click', () => addItem().catch(showError));
            document.getElementById('copyMarkdownButton').addEventListener('click', () => copyMarkdown().catch(showError));
            document.getElementById('newSessionButton').addEventListener('click', () => newSession().catch(showError));
            document.getElementById('closeSessionButton').addEventListener('click', () => closeSession().catch(showError));
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
