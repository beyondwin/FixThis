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
              color-scheme: dark;
              --bg-0: #0d0e10;
              --bg-1: #131418;
              --bg-2: #1a1c21;
              --bg-3: #21242b;
              --line: #2a2d35;
              --line-soft: rgba(42, 45, 53, .72);
              --txt-0: #e8e9eb;
              --txt-1: #b6b8be;
              --txt-2: #7d8089;
              --accent: #b8d36a;
              --danger: #f26d6d;
              --warning: #e6b45a;
              font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
              background: var(--bg-0);
              color: var(--txt-0);
            }
            * { box-sizing: border-box; }
            body { margin: 0; height: 100vh; overflow: hidden; background: var(--bg-0); }
            .studio-shell {
              display: grid;
              grid-template-rows: 56px 1fr;
              height: 100vh;
              overflow: hidden;
            }
            .studio-topbar {
              display: grid;
              grid-template-columns: 220px minmax(360px, 1fr) auto;
              align-items: center;
              gap: 16px;
              padding: 0 16px;
              background: var(--bg-1);
              border-bottom: 1px solid var(--line);
            }
            .studio-body {
              display: grid;
              grid-template-columns: 280px minmax(480px, 1fr) 340px;
              min-height: 0;
              overflow: hidden;
            }
            .studio-history,
            .studio-canvas,
            .studio-inspector {
              min-width: 0;
              min-height: 0;
              overflow: hidden;
              background: var(--bg-0);
            }
            .studio-history,
            .studio-inspector {
              display: flex;
              flex-direction: column;
              border-right: 1px solid var(--line);
            }
            .studio-inspector { border-right: 0; border-left: 1px solid var(--line); }
            .studio-brand { display: flex; align-items: center; gap: 10px; min-width: 0; }
            .studio-mark {
              width: 30px;
              height: 30px;
              border-radius: 8px;
              display: grid;
              place-items: center;
              background: var(--accent);
              color: var(--bg-0);
              font-weight: 800;
              font-size: 18px;
            }
            h1 { margin: 0; font-size: 15px; line-height: 1.1; font-weight: 700; letter-spacing: 0; }
            .brand-caption,
            .panel-title {
              color: var(--txt-2);
              font-size: 10px;
              font-weight: 700;
              letter-spacing: .14em;
              text-transform: uppercase;
            }
            .session-meta {
              min-width: 120px;
              color: var(--txt-1);
              font-size: 12px;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
            .studio-context,
            .studio-actions,
            .navigation-controls,
            .inspector-footer,
            .toolbar {
              display: flex;
              align-items: center;
              gap: 8px;
              min-width: 0;
            }
            .studio-context { overflow: hidden; }
            .studio-actions { justify-content: flex-end; flex-wrap: wrap; }
            .toolbar { flex-wrap: wrap; margin-top: 8px; }
            select, button {
              min-height: 32px;
              border: 1px solid var(--line);
              border-radius: 7px;
              background: var(--bg-2);
              color: var(--txt-1);
              padding: 0 10px;
              font: inherit;
              font-size: 12px;
            }
            button {
              cursor: pointer;
              transition: background 120ms ease, color 120ms ease, border-color 120ms ease, transform 120ms ease;
            }
            button:hover:not(:disabled) { background: var(--bg-3); color: var(--txt-0); }
            button.primary { background: var(--accent); border-color: var(--accent); color: var(--bg-0); font-weight: 700; }
            button.primary:hover:not(:disabled) { transform: translateY(-1px); }
            button:disabled { opacity: .4; cursor: default; }
            .device-control {
              position: relative;
              display: inline-flex;
              align-items: center;
              gap: 8px;
              min-width: 210px;
              max-width: 260px;
              min-height: 32px;
              padding: 0 10px;
              border: 1px solid var(--line);
              border-radius: 8px;
              background: var(--bg-2);
              color: var(--txt-0);
              overflow: hidden;
            }
            .device-control:focus-within {
              border-color: var(--accent);
              box-shadow: 0 0 0 3px rgba(184, 211, 106, .12);
            }
            .device-state-dot {
              width: 8px;
              height: 8px;
              border-radius: 999px;
              flex: 0 0 auto;
              background: var(--txt-2);
            }
            .device-copy {
              display: grid;
              gap: 1px;
              min-width: 0;
              line-height: 1.1;
            }
            .device-name {
              min-width: 0;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
              font-size: 12px;
              font-weight: 700;
              color: var(--txt-0);
            }
            .device-connection-state {
              min-width: 0;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
              font-size: 10px;
              font-weight: 700;
              text-transform: uppercase;
              letter-spacing: 0;
              color: var(--txt-2);
            }
            .device-chevron {
              margin-left: auto;
              color: var(--txt-2);
              font-size: 11px;
              flex: 0 0 auto;
            }
            .device-control select {
              position: absolute;
              inset: 0;
              width: 100%;
              height: 100%;
              opacity: 0;
              cursor: pointer;
            }
            .device-control[data-connection-state="connected"] {
              border-color: rgba(111, 207, 151, .34);
            }
            .device-control[data-connection-state="connected"] .device-state-dot {
              background: #6fcf97;
              box-shadow: 0 0 0 3px rgba(111, 207, 151, .16);
            }
            .device-control[data-connection-state="connected"] .device-connection-state {
              color: #6fcf97;
            }
            .device-control[data-connection-state="connecting"] {
              border-color: rgba(230, 180, 90, .34);
            }
            .device-control[data-connection-state="connecting"] .device-state-dot {
              background: #e6b45a;
              box-shadow: 0 0 0 3px rgba(230, 180, 90, .16);
            }
            .device-control[data-connection-state="connecting"] .device-connection-state {
              color: #e6b45a;
            }
            .device-control[data-connection-state="unavailable"] {
              border-color: rgba(242, 109, 109, .34);
            }
            .device-control[data-connection-state="unavailable"] .device-state-dot {
              background: #f26d6d;
              box-shadow: 0 0 0 3px rgba(242, 109, 109, .16);
            }
            .device-control[data-connection-state="unavailable"] .device-connection-state {
              color: #f26d6d;
            }
            .device-control[data-connection-state="none"] .device-state-dot {
              background: var(--txt-2);
            }
            .device-control[data-connection-state="none"] .device-connection-state {
              color: var(--txt-1);
            }
            .icon-button {
              width: 32px;
              padding: 0;
              display: inline-grid;
              place-items: center;
              font-size: 15px;
            }
            .clear-device-button {
              white-space: nowrap;
            }
            textarea {
              width: 100%;
              min-height: 92px;
              resize: vertical;
              border: 1px solid var(--line);
              border-radius: 8px;
              background: var(--bg-1);
              color: var(--txt-0);
              padding: 10px;
              font: inherit;
              font-size: 13px;
            }
            textarea::placeholder { color: var(--txt-2); }
            .capture-toggle {
              display: flex;
              align-items: center;
              gap: 6px;
              color: var(--txt-2);
              font-size: 12px;
            }
            .panel-head {
              display: flex;
              align-items: center;
              justify-content: space-between;
              min-height: 48px;
              padding: 0 14px;
              border-bottom: 1px solid var(--line);
            }
            .panel-count,
            .status-pill {
              border-radius: 999px;
              background: var(--bg-3);
              color: var(--txt-1);
              padding: 4px 8px;
              font-size: 11px;
              font-variant-numeric: tabular-nums;
            }
            .history-list,
            .inspector-body,
            .list {
              display: grid;
              gap: 6px;
            }
            .history-list,
            .inspector-body {
              overflow: auto;
              padding: 8px;
            }
            .studio-history > .history-list { flex: 1 1 auto; min-height: 0; }
            .inspector-body { flex: 1 1 auto; align-content: start; min-height: 0; }
            .inspector-footer {
              flex-wrap: wrap;
              padding: 10px;
              border-top: 1px solid var(--line);
              background: var(--bg-1);
            }
            .sent-history-drawer {
              border-top: 1px solid var(--line);
              padding: 8px;
              flex: 0 0 auto;
              min-height: 0;
              overflow: hidden;
            }
            .sent-history-drawer summary {
              cursor: pointer;
              color: var(--txt-2);
              font-size: 10px;
              font-weight: 700;
              letter-spacing: .14em;
              text-transform: uppercase;
              padding: 8px 4px;
            }
            .sent-history-drawer .history-list {
              max-height: 160px;
              overflow: auto;
              padding: 0;
            }
            .row {
              border: 1px solid var(--line);
              border-radius: 8px;
              padding: 10px;
              background: var(--bg-1);
            }
            .row.active, .session-row.active {
              background: var(--bg-2);
              box-shadow: inset 2px 0 0 var(--accent);
            }
            .session-row {
              display: block;
              width: 100%;
              min-height: 0;
              text-align: left;
            }
            .row strong { display: block; color: var(--txt-0); font-size: 12px; margin-bottom: 4px; }
            .row span { display: block; color: var(--txt-2); font-size: 11px; overflow-wrap: anywhere; }
            .row span + span { display: block; margin-top: 3px; }
            .studio-canvas {
              display: grid;
              grid-template-rows: 48px 1fr;
            }
            .canvas-toolbar {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 14px;
              padding: 0 14px;
              border-bottom: 1px solid var(--line);
              background: var(--bg-0);
            }
            .canvas-tool-status {
              display: flex;
              align-items: center;
              gap: 10px;
              color: var(--txt-1);
              font-size: 12px;
              min-width: 0;
            }
            .tool-group,
            .zoom-control {
              display: inline-flex;
              align-items: center;
              gap: 2px;
              padding: 2px;
              border: 1px solid var(--line);
              border-radius: 8px;
              background: var(--bg-1);
            }
            .tool-button,
            .zoom-button {
              min-width: 0;
              min-height: 24px;
              padding: 0 10px;
              border: 0;
              border-radius: 6px;
              background: transparent;
              color: var(--txt-1);
              font-size: 11px;
              font-weight: 700;
            }
            .tool-button[aria-pressed="true"] {
              background: var(--bg-3);
              color: var(--accent);
            }
            .tool-status {
              flex: 1;
              display: flex;
              align-items: center;
              justify-content: center;
              min-width: 160px;
              color: var(--txt-2);
              font-size: 11px;
              font-weight: 700;
              text-transform: uppercase;
              letter-spacing: .08em;
            }
            .zoom-control {
              color: var(--txt-2);
              font-size: 11px;
              font-weight: 700;
            }
            .zoom-button {
              width: 24px;
              height: 24px;
              padding: 0;
            }
            .mode-badge {
              display: inline-flex;
              align-items: center;
              min-height: 24px;
              border-radius: 999px;
              padding: 0 8px;
              background: var(--bg-3);
              color: var(--txt-1);
              font-size: 11px;
              font-weight: 700;
              text-transform: uppercase;
            }
            .mode-badge[data-mode="live"] { color: var(--accent); }
            .mode-badge[data-mode="frozen"] { background: var(--accent); color: var(--bg-0); }
            .snapshot-stage {
              display: grid;
              place-items: center;
              min-height: 0;
              overflow: hidden;
              padding: 24px;
              background: radial-gradient(circle at 50% 50%, var(--bg-1) 0%, var(--bg-0) 72%);
              color: var(--txt-2);
              text-align: center;
            }
            .empty-stage { color: var(--txt-2); font-size: 13px; }
            .snapshot-frame {
              position: relative;
              display: inline-block;
              max-width: min(100%, 420px);
              max-height: 100%;
              padding: 8px;
              border-radius: 36px;
              background: linear-gradient(180deg, #2a2a2e 0%, #1a1a1d 100%);
              box-shadow: 0 0 0 2px #3a3a40, 0 30px 60px -20px rgba(0, 0, 0, .6);
            }
            .snapshot-frame[data-mode="frozen"] {
              box-shadow: 0 0 0 2px var(--accent), 0 0 0 6px rgba(184, 211, 106, .10), 0 30px 60px -20px rgba(0, 0, 0, .6);
            }
            .snapshot-frame img {
              display: block;
              max-width: 100%;
              max-height: calc(100vh - 160px);
              width: auto;
              height: auto;
              border: 0;
              border-radius: 28px;
              cursor: pointer;
            }
            .selection-overlay {
              position: absolute;
              inset: 8px;
              pointer-events: none;
            }
            .selection-box {
              position: absolute;
              border: 1.5px solid var(--accent);
              background: rgba(184, 211, 106, .12);
              border-radius: 6px;
            }
            .selection-box.drag-preview {
              border-style: dashed;
              background: rgba(184, 211, 106, .08);
            }
            .selection-box.focused {
              border-color: var(--warning);
              background: rgba(230, 180, 90, .16);
            }
            .selection-box.annotation-pin {
              pointer-events: auto;
              cursor: pointer;
            }
            .selection-box.annotation-pin:hover {
              background: rgba(184, 211, 106, .20);
            }
            .selection-label {
              position: absolute;
              transform: translateY(-100%);
              min-width: 24px;
              min-height: 24px;
              display: grid;
              place-items: center;
              border-radius: 999px;
              background: var(--accent);
              color: var(--bg-0);
              font-size: 11px;
              font-weight: 800;
            }
            .selection-label.focused { background: var(--warning); }
            .annotate-hint {
              position: absolute;
              top: 16px;
              left: 50%;
              transform: translateX(-50%);
              z-index: 3;
              display: flex;
              align-items: center;
              gap: 10px;
              border-radius: 999px;
              padding: 8px 14px;
              background: var(--accent);
              color: var(--bg-0);
              font-size: 12px;
              font-weight: 800;
              box-shadow: 0 12px 28px -12px rgba(0, 0, 0, .70);
            }
            .annotate-hint::before {
              content: '';
              width: 8px;
              height: 8px;
              border-radius: 999px;
              background: var(--bg-0);
              animation: pulse-a 1.4s infinite;
            }
            @keyframes pulse-a {
              0%, 100% { opacity: 1; transform: scale(1); }
              50% { opacity: .5; transform: scale(1.3); }
            }
            .selection-summary {
              border: 1px solid var(--line);
              border-radius: 8px;
              background: var(--bg-1);
              color: var(--txt-1);
              min-height: 44px;
              padding: 10px;
              font-size: 13px;
            }
            .annotation-row {
              display: grid;
              grid-template-columns: 28px 1fr auto;
              gap: 10px;
              align-items: start;
              width: 100%;
              border: 0;
              text-align: left;
            }
            .annotation-main {
              display: contents;
              border: 0;
              padding: 0;
              background: transparent;
              color: inherit;
              text-align: left;
            }
            .annotation-number {
              width: 26px;
              height: 26px;
              display: grid;
              place-items: center;
              border-radius: 999px;
              background: var(--accent);
              color: var(--bg-0);
              font-size: 11px;
              font-weight: 800;
            }
            .annotation-copy {
              min-width: 0;
            }
            .annotation-title {
              color: var(--txt-0);
              font-size: 13px;
              font-weight: 700;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
            .annotation-comment {
              margin-top: 3px;
              color: var(--txt-2);
              font-size: 11px;
              line-height: 1.4;
              overflow-wrap: anywhere;
            }
            .annotation-delete {
              width: 26px;
              min-width: 26px;
              height: 26px;
              padding: 0;
              border-radius: 6px;
            }
            .annotation-detail {
              display: grid;
              gap: 12px;
            }
            .annotation-detail label {
              display: grid;
              gap: 6px;
              color: var(--txt-2);
              font-size: 10px;
              font-weight: 800;
              letter-spacing: .12em;
              text-transform: uppercase;
            }
            img { max-width: 100%; height: auto; border-radius: 6px; border: 1px solid var(--line); }
            .evidence-card {
              display: grid;
              gap: 8px;
              border: 1px solid var(--line);
              border-radius: 8px;
              padding: 10px;
              background: var(--bg-1);
            }
            .evidence-card-head {
              display: flex;
              align-items: flex-start;
              justify-content: space-between;
              gap: 10px;
            }
            .evidence-card-head strong {
              font-size: 13px;
              color: var(--txt-0);
            }
            .evidence-card-head span {
              font-size: 11px;
              color: var(--txt-2);
            }
            .saved-evidence-preview { margin: 10px 0; }
            .saved-evidence-frame {
              position: relative;
              overflow: hidden;
              border-radius: 8px;
              border: 1px solid var(--line);
              background: var(--bg-2);
            }
            .saved-evidence-frame img {
              display: block;
              width: 100%;
              height: auto;
              border: 0;
              border-radius: 0;
            }
            .saved-evidence-frame .selection-overlay { inset: 0; }
            .empty-state {
              display: grid;
              place-items: center;
              align-content: center;
              gap: 8px;
              min-height: 220px;
              color: var(--txt-2);
              text-align: center;
            }
            .empty-title {
              color: var(--txt-0);
              font-size: 13px;
              font-weight: 700;
            }
            .empty-body {
              max-width: 240px;
              font-size: 12px;
              line-height: 1.5;
            }
            .error {
              color: var(--danger);
              font-size: 13px;
              min-height: 18px;
              margin: 0;
              padding: 0 10px 10px;
            }
            @media (max-width: 1099px) {
              .studio-topbar {
                grid-template-columns: 140px minmax(0, 1fr) auto;
                gap: 8px;
                padding: 0 10px;
              }
              .studio-brand { gap: 8px; }
              .studio-mark {
                width: 26px;
                height: 26px;
                border-radius: 7px;
                font-size: 15px;
              }
              .studio-context {
                display: grid;
                grid-template-columns: minmax(72px, 1fr) minmax(150px, 190px) 64px auto auto;
                gap: 6px;
              }
              .studio-actions {
                flex-wrap: nowrap;
                gap: 6px;
              }
              .studio-topbar button,
              .studio-topbar select {
                min-height: 30px;
                padding: 0 7px;
                font-size: 11px;
              }
              .session-meta { min-width: 0; }
              .device-control {
                min-width: 150px;
                max-width: 190px;
                width: 100%;
              }
              .device-name {
                font-size: 11px;
              }
              .device-connection-state {
                font-size: 9px;
              }
              .clear-device-button {
                width: 64px;
                min-width: 64px;
                max-width: 64px;
                overflow: hidden;
                text-overflow: ellipsis;
              }
              #previewIntervalSelect {
                width: 64px;
                padding: 0 6px;
              }
              .studio-context .status-pill { display: none; }
              .studio-body { grid-template-columns: 220px minmax(380px, 1fr) 300px; }
            }
            @media (max-width: 899px) {
              .studio-shell::before {
                content: "Resize to >= 900px wide";
                position: fixed;
                inset: 0;
                display: grid;
                place-items: center;
                z-index: 999;
                background: var(--bg-0);
                color: var(--txt-1);
                font-size: 14px;
              }
            }
            @media (prefers-reduced-motion: reduce) {
              * { animation-duration: .01ms !important; transition-duration: .01ms !important; }
            }
          </style>
        </head>
        <body>
          <div class="studio-shell">
            <header class="studio-topbar">
              <div class="studio-brand">
                <div class="studio-mark" aria-hidden="true">P</div>
                <div>
                  <h1>PointPatch</h1>
                  <div class="brand-caption">Studio</div>
                </div>
              </div>
              <div class="studio-context">
                <span id="sessionMeta" class="session-meta">Loading session...</span>
                <div id="deviceControl" class="device-control" data-connection-state="none">
                  <span class="device-state-dot" aria-hidden="true"></span>
                  <span class="device-copy">
                    <span id="deviceName" class="device-name">No device</span>
                    <span id="deviceConnectionState" class="device-connection-state">No device</span>
                  </span>
                  <span class="device-chevron" aria-hidden="true">▾</span>
                  <select id="devicePicker" aria-label="Android device"></select>
                </div>
                <select id="previewIntervalSelect" aria-label="Preview interval">
                  <option value="manual">Manual</option>
                  <option value="1000">1s</option>
                  <option value="2000" selected>2s</option>
                  <option value="5000">5s</option>
                </select>
                <button id="refreshDevicesButton" class="icon-button" type="button" title="Refresh devices" aria-label="Refresh devices">↻</button>
                <button id="disconnectDeviceButton" class="clear-device-button" type="button" title="Clear PointPatch device selection" aria-label="Clear PointPatch device selection">Clear selection</button>
                <span id="deviceStatus" class="status-pill" hidden>No device</span>
              </div>
              <div class="studio-actions">
                <button id="refreshButton">Refresh</button>
                <button id="saveButton" class="primary" disabled>Save snapshot</button>
                <button id="copyMarkdownButton">Copy</button>
                <button id="sendDraftButton">Send</button>
                <button id="newSessionButton">New</button>
                <button id="closeSessionButton">Close</button>
              </div>
            </header>
            <main class="studio-body">
              <aside class="studio-history">
                <div class="panel-head">
                  <div class="panel-title">History</div>
                  <div id="sessionCount" class="panel-count">0</div>
                </div>
                <div id="sessions" class="history-list"></div>
                <details class="sent-history-drawer">
                  <summary>Sent History</summary>
                  <div id="sentHistory" class="history-list"></div>
                </details>
              </aside>
              <section class="studio-canvas">
                <div id="canvasToolbar" class="canvas-toolbar">
                  <div class="tool-group" role="group" aria-label="Canvas tool">
                    <button id="selectToolButton" class="tool-button" type="button" aria-pressed="true">Select</button>
                    <button id="annotateToolButton" class="tool-button" type="button" aria-pressed="false">Annotate</button>
                  </div>
                  <div id="toolStatus" class="tool-status">Select mode</div>
                  <div class="canvas-tool-status">
                    <span id="previewModeBadge" class="mode-badge" data-mode="idle">Live</span>
                    <span id="snapshotTitle">Live Preview</span>
                  </div>
                  <div class="zoom-control" aria-label="Zoom controls">
                    <button class="zoom-button" type="button" aria-label="Zoom out">−</button>
                    <span>100%</span>
                    <button class="zoom-button" type="button" aria-label="Zoom in">+</button>
                  </div>
                  <div id="navigationControls" class="navigation-controls">
                    <button id="backButton" aria-label="Back">Back</button>
                    <button id="swipeUpButton" aria-label="Swipe up">Up</button>
                    <button id="swipeDownButton" aria-label="Swipe down">Down</button>
                    <button id="swipeLeftButton" aria-label="Swipe left">Left</button>
                    <button id="swipeRightButton" aria-label="Swipe right">Right</button>
                    <label class="capture-toggle"><input id="captureAfterNavigation" type="checkbox"> Capture</label>
                  </div>
                </div>
                <div id="snapshot" class="snapshot-stage">
                  <div id="selectionOverlay" class="selection-overlay"></div>
                  <div class="empty-stage">Refresh the live preview to begin.</div>
                </div>
              </section>
              <aside class="studio-inspector">
                <div class="panel-head">
                  <div id="inspectorTitle" class="panel-title">Annotations</div>
                  <div id="inspectorCount" class="panel-count">0</div>
                </div>
                <div id="inspectorBody" class="inspector-body">
                  <div id="selectionSummary" class="selection-summary">No selection.</div>
                  <textarea id="comment" placeholder="Describe the UI change needed"></textarea>
                  <div id="pendingItems" class="list"></div>
                  <div id="draftItems" class="list"></div>
                </div>
                <div id="inspectorFooter" class="inspector-footer">
                  <button id="clearSelectionButton">Clear Selection</button>
                  <button id="cancelAddFlowButton" disabled>Exit Annotate</button>
                  <button id="addItemButton" class="primary" disabled hidden>Add annotation</button>
                  <button id="clearDraftButton">Clear Draft</button>
                </div>
                <p id="error" class="error" role="status" aria-live="polite"></p>
              </aside>
            </main>
          </div>
          <script>
            const DefaultLivePreviewIntervalMs = 2000;
            const MinLivePreviewIntervalMs = 1000;
            const PreviewIntervalStorageKey = 'pointpatch.previewIntervalMs';
            const state = { session: null, preview: null, selectedDeviceSerial: null, devices: [] };
            const sessionMeta = document.getElementById('sessionMeta');
            const sessions = document.getElementById('sessions');
            const sentHistory = document.getElementById('sentHistory');
            const snapshot = document.getElementById('snapshot');
            const snapshotTitle = document.getElementById('snapshotTitle');
            const previewModeBadge = document.getElementById('previewModeBadge');
            const inspectorTitle = document.getElementById('inspectorTitle');
            const inspectorCount = document.getElementById('inspectorCount');
            const inspectorBody = document.getElementById('inspectorBody');
            const inspectorFooter = document.getElementById('inspectorFooter');
            const draftItems = document.getElementById('draftItems');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const captureAfterNavigation = document.getElementById('captureAfterNavigation');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const deviceControl = document.getElementById('deviceControl');
            const deviceName = document.getElementById('deviceName');
            const deviceConnectionState = document.getElementById('deviceConnectionState');
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const navigationControls = document.getElementById('navigationControls');
            const selectionSummary = document.getElementById('selectionSummary');
            const clearSelectionButton = document.getElementById('clearSelectionButton');
            const addItemButton = document.getElementById('addItemButton');
            const saveButton = document.getElementById('saveButton');
            const cancelAddFlowButton = document.getElementById('cancelAddFlowButton');
            const clearDraftButton = document.getElementById('clearDraftButton');
            const selectToolButton = document.getElementById('selectToolButton');
            const annotateToolButton = document.getElementById('annotateToolButton');
            const toolStatus = document.getElementById('toolStatus');
            let livePreviewTimer = null;
            let previewRequestGeneration = 0;
            let previewRequestContextGeneration = 0;
            let previewRequestInFlight = null;
            let previewRequestInFlightContextGeneration = null;
            let addItemsFlow = null;
            let pendingFeedbackItems = [];
            let focusedPendingItemIndex = null;
            let currentSelection = null;
            let toolMode = 'select';
            let annotationSequence = 1;
            let dragStart = null;
            let dragPreview = null;
            let suppressNextClick = false;

            const DeviceUiState = {
              NONE: 'none',
              CONNECTING: 'connecting',
              CONNECTED: 'connected',
              UNAVAILABLE: 'unavailable'
            };

            const DeviceStateCopy = {
              [DeviceUiState.NONE]: 'No device',
              [DeviceUiState.CONNECTING]: 'Connecting',
              [DeviceUiState.CONNECTED]: 'Connected',
              [DeviceUiState.UNAVAILABLE]: 'Unavailable'
            };

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

            function annotationTitle(item, index) {
              return item.label || pendingTargetLabel(item) + ' #' + (index + 1);
            }

            function selectedAnnotation() {
              if (focusedPendingItemIndex == null) return null;
              return pendingFeedbackItems[focusedPendingItemIndex] || null;
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

            function shortenDeviceSerial(serial) {
              const raw = String(serial || '').trim();
              if (!raw) return '';
              const withoutServiceSuffix = raw.split('._adb-tls-connect._tcp')[0];
              if (withoutServiceSuffix.startsWith('adb-')) return withoutServiceSuffix.substring(4);
              if (withoutServiceSuffix.length <= 24) return withoutServiceSuffix;
              return withoutServiceSuffix.slice(0, 10) + '...' + withoutServiceSuffix.slice(-6);
            }

            function deviceLabel(device) {
              if (!device) return 'No device';
              return device.model || device.deviceName || device.product || shortenDeviceSerial(device.serial) || 'Unknown device';
            }

            function deviceDetail(device) {
              if (!device) return 'No device';
              if (device.state === 'device') return 'connected';
              return (device.state || 'unknown') + ' · unavailable';
            }

            function deviceOptionLabel(device) {
              return deviceLabel(device) + ' - ' + deviceDetail(device);
            }

            function setDeviceUiState(uiState, device = null) {
              deviceControl.dataset.connectionState = uiState;
              deviceName.textContent = device ? deviceLabel(device) : 'No device';
              deviceConnectionState.textContent = DeviceStateCopy[uiState];
              deviceStatus.textContent = deviceName.textContent + ' - ' + deviceConnectionState.textContent;
            }

            function deviceBySerial(devices, serial) {
              if (!serial) return null;
              return (devices || []).find(device => device.serial === serial) || null;
            }

            function renderDeviceList(payload) {
              const devices = payload.devices || [];
              state.devices = devices;
              const previousSelectedDeviceSerial = state.selectedDeviceSerial;
              devicePicker.innerHTML = '';

              if (!devices.length) {
                const selectedSerial = null;
                if (previousSelectedDeviceSerial !== selectedSerial) {
                  invalidatePreviewContext();
                  renderPreviewOnly();
                }
                state.selectedDeviceSerial = selectedSerial;
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'No devices available';
                devicePicker.appendChild(option);
                devicePicker.disabled = true;
                setDeviceUiState(DeviceUiState.NONE);
                return;
              }

              const selectedSerialFromPayload = payload.selectedSerial || null;
              const selected = devices.find(device => device.selected || device.serial === selectedSerialFromPayload) || null;
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
                option.textContent = deviceOptionLabel(device);
                option.disabled = device.state !== 'device';
                option.selected = Boolean(device.selected) || device.serial === selectedSerialFromPayload;
                devicePicker.appendChild(option);
              });

              const selectedSerial = selected && selected.state === 'device' ? selected.serial : null;
              if (previousSelectedDeviceSerial !== selectedSerial) {
                invalidatePreviewContext();
                renderPreviewOnly();
              }
              state.selectedDeviceSerial = selectedSerial;

              if (!selected) {
                setDeviceUiState(DeviceUiState.NONE);
              } else if (selected.state === 'device') {
                setDeviceUiState(DeviceUiState.CONNECTED, selected);
              } else {
                setDeviceUiState(DeviceUiState.UNAVAILABLE, selected);
              }
            }

            async function refreshDevices() {
              if (state.selectedDeviceSerial) {
                setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, state.selectedDeviceSerial));
              }
              renderDeviceList(await requestJson('/api/devices'));
            }

            async function selectDevice() {
              const option = devicePicker.selectedOptions[0];
              if (!option || !option.value || option.disabled) return;
              setDeviceUiState(DeviceUiState.CONNECTING, deviceBySerial(state.devices, option.value));
              invalidatePreviewContext();
              try {
                renderDeviceList(await requestJson('/api/device/select', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ serial: option.value })
                }));
                await refreshPreview();
                startLivePreviewPolling();
              } catch (cause) {
                state.selectedDeviceSerial = null;
                stopLivePreviewPolling();
                setDeviceUiState(DeviceUiState.UNAVAILABLE, deviceBySerial(state.devices, option.value) || { serial: option.value });
                throw cause;
              }
            }

            async function disconnectDevice() {
              invalidatePreviewContext();
              renderDeviceList(await requestJson('/api/device/disconnect', { method: 'POST' }));
              setDeviceUiState(DeviceUiState.NONE);
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
              saveButton.disabled = !addItemsFlow || pendingFeedbackItems.length === 0 || pendingFeedbackItems.some(item => !String(item.comment || '').trim());
              cancelAddFlowButton.disabled = !addItemsFlow;
              addItemButton.hidden = true;
              addItemButton.disabled = true;
              navigationControls.hidden = Boolean(addItemsFlow) || toolMode !== 'select';
              selectToolButton.setAttribute('aria-pressed', String(toolMode === 'select'));
              annotateToolButton.setAttribute('aria-pressed', String(toolMode === 'annotate'));
              toolStatus.textContent = toolMode === 'annotate'
                ? 'Annotate: click or drag the frozen preview'
                : 'Select: choose an existing annotation';
              const item = focusedPendingSelectionSummary();
              selectionSummary.textContent = currentSelection
                ? currentSelection.label + ' - ' + formatBounds(currentSelection.bounds)
                : (item
                  ? 'Focused #' + (focusedPendingItemIndex + 1) + ' - ' + formatBounds(item.bounds)
                  : (toolMode === 'annotate' ? 'Click a component or drag a region to create an annotation.' : 'No annotation selected.'));
            }

            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false, isFocused = false, annotationIndex = null) {
              if (!bounds) return;
              const left = bounds.left * 100 / image.naturalWidth;
              const top = bounds.top * 100 / image.naturalHeight;
              const width = (bounds.right - bounds.left) * 100 / image.naturalWidth;
              const height = (bounds.bottom - bounds.top) * 100 / image.naturalHeight;
              const box = document.createElement('div');
              box.className = 'selection-box' + (isDragPreview ? ' drag-preview' : '') + (isFocused ? ' focused' : '') + (annotationIndex == null ? '' : ' annotation-pin');
              box.style.left = left + '%';
              box.style.top = top + '%';
              box.style.width = width + '%';
              box.style.height = height + '%';
              if (annotationIndex != null) {
                box.setAttribute('role', 'button');
                box.setAttribute('aria-label', 'Select annotation ' + (annotationIndex + 1));
                box.tabIndex = 0;
                box.addEventListener('click', event => {
                  event.stopPropagation();
                  focusPendingFeedbackItem(annotationIndex);
                });
                box.addEventListener('keydown', event => {
                  if (event.key !== 'Enter' && event.key !== ' ') return;
                  event.preventDefault();
                  focusPendingFeedbackItem(annotationIndex);
                });
              }
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
                renderOverlayBox(overlay, image, item.bounds, '#' + (index + 1), false, index === focusedPendingItemIndex, index);
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
              const selection = {
                targetType: 'node',
                nodeUid: node.uid,
                bounds: node.boundsInWindow,
                label: componentLabel(node)
              };
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function finishAreaSelection(bounds) {
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
              comment.value = '';
              clearDragState();
              renderSelectionOverlay();
              renderInspectorRegion();
              updateComposerState();
            }

            async function refreshPreview() {
              error.textContent = '';
              if (addItemsFlow) return;
              const requestGeneration = ++previewRequestGeneration;
              const preview = await requestLivePreview();
              if (addItemsFlow || requestGeneration !== previewRequestGeneration) return;
              state.preview = preview;
              renderPreviewOnly();
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
                toolMode = 'annotate';
                focusedPendingItemIndex = null;
                currentSelection = null;
                render();
              } finally {
                if (!addItemsFlow) startLivePreviewPolling();
              }
            }

            function createAnnotationFromSelection(selection) {
              if (!addItemsFlow) throw new Error('Switch to Annotate before selecting feedback.');
              if (!selection) throw new Error('Select a component or area first.');
              const annotation = {
                annotationId: 'local-' + annotationSequence++,
                targetType: selection.targetType,
                nodeUid: selection.nodeUid,
                bounds: selection.bounds,
                label: selection.label,
                comment: ''
              };
              pendingFeedbackItems.push(annotation);
              currentSelection = null;
              focusedPendingItemIndex = pendingFeedbackItems.length - 1;
              toolMode = 'select';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function deletePendingFeedbackItem(index) {
              pendingFeedbackItems.splice(index, 1);
              focusedPendingItemIndex = null;
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function focusPendingFeedbackItem(index) {
              focusedPendingItemIndex = index;
              currentSelection = null;
              toolMode = 'select';
              comment.value = pendingFeedbackItems[index]?.comment || '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function updateSelectedAnnotationComment() {
              const item = selectedAnnotation();
              if (!item) return;
              item.comment = comment.value;
              renderPendingItems();
              updateComposerState();
            }

            function pendingPayloadItems() {
              return pendingFeedbackItems.map(item => ({
                targetType: item.targetType,
                bounds: item.bounds,
                nodeUid: item.nodeUid,
                comment: item.comment
              }));
            }

            async function savePendingFeedbackItems() {
              if (!addItemsFlow) return;
              if (!pendingFeedbackItems.length) throw new Error('Add at least one pending feedback item.');
              if (pendingFeedbackItems.some(item => !String(item.comment || '').trim())) throw new Error('Add a comment to every annotation before saving.');
              state.session = await requestJson('/api/items/batch', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  previewId: addItemsFlow.previewId,
                  items: pendingPayloadItems()
                })
              });
              addItemsFlow = null;
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              currentSelection = null;
              toolMode = 'select';
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
              toolMode = 'select';
              comment.value = '';
              clearDragState();
              render();
              startLivePreviewPolling();
            }

            function enterSelectMode() {
              toolMode = 'select';
              currentSelection = null;
              clearDragState();
              renderPreviewOnly();
              renderInspectorRegion();
            }

            async function enterAnnotateMode() {
              toolMode = 'annotate';
              renderPreviewOnly();
              renderInspectorRegion();
              if (!addItemsFlow) {
                await startAddItemsFlow();
              } else {
                renderPreviewOnly();
                renderInspectorRegion();
              }
            }

            function renderPendingItems() {
              pendingItems.innerHTML = pendingFeedbackItems.map((item, index) =>
                '<div class="row pending-item-row annotation-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '">' +
                  '<button type="button" class="annotation-main" data-focus-pending="' + index + '">' +
                    '<span class="annotation-number">' + (index + 1) + '</span>' +
                    '<span class="annotation-copy">' +
                      '<span class="annotation-title">' + escapeHtml(annotationTitle(item, index)) + '</span>' +
                      '<span class="annotation-comment">' + escapeHtml(firstLine(item.comment || 'No comment yet.')) + '</span>' +
                    '</span>' +
                  '</button>' +
                  '<button type="button" class="annotation-delete" aria-label="Delete annotation ' + (index + 1) + '" data-delete-pending="' + index + '">×</button>' +
                '</div>'
              ).join('') || '<div class="empty-state"><div class="empty-title">No annotations yet.</div><div class="empty-body">Switch to <b>Annotate</b>, then click or drag on the preview.</div></div>';
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              pendingItems.querySelectorAll('[data-delete-pending]').forEach(button => {
                button.addEventListener('click', event => {
                  event.stopPropagation();
                  deletePendingFeedbackItem(Number(button.dataset.deletePending));
                });
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
                return '<article class="evidence-card">' +
                  '<div class="evidence-card-head">' +
                    '<strong>' + escapeHtml(screen?.displayName || 'Saved evidence') + '</strong>' +
                    '<span>' + group.items.length + ' item' + (group.items.length === 1 ? '' : 's') + ' · screenshot attached</span>' +
                  '</div>' +
                  '<div class="saved-evidence-preview" data-screen-id="' + escapeHtml(group.screenId) + '"></div>' +
                  group.items.map((item, index) =>
                    '<div class="row evidence-item-row">' +
                      '<strong>' + escapeHtml(formatSavedEvidenceItemLabel(item, index)) + '</strong>' +
                      '<span>' + escapeHtml(targetLabel(item)) + ' · ' + escapeHtml(sourceHintLabel(item)) + '</span>' +
                    '</div>'
                  ).join('') +
                '</article>';
              }).join('') || '<div class="empty-state"><div class="empty-title">No saved annotations yet.</div><div class="empty-body">Use <b>Annotate</b> to freeze the preview, add comments, then Save snapshot.</div></div>';
              hydrateSavedEvidencePreviews();
            }

            function hydrateSavedEvidencePreviews() {
              draftItems.querySelectorAll('.saved-evidence-preview').forEach(container => {
                const screenId = container.dataset.screenId;
                const group = savedEvidenceGroups().find(candidate => candidate.screenId === screenId);
                const screen = findScreen(screenId);
                if (!screen?.screenshot?.desktopFullPath || !group) {
                  container.textContent = 'Evidence: screenshot attached';
                  return;
                }
                container.innerHTML =
                  '<div class="saved-evidence-frame">' +
                    '<img alt="Saved evidence screenshot" src="/api/screens/' + encodeURIComponent(screenId) + '/screenshot/full">' +
                    '<div class="selection-overlay" aria-hidden="true"></div>' +
                  '</div>';
                const image = container.querySelector('img');
                const overlay = container.querySelector('.selection-overlay');
                const renderOverlay = () => renderSavedEvidenceOverlay(overlay, image, group.items);
                if (image.complete && image.naturalWidth) {
                  renderOverlay();
                } else {
                  image.addEventListener('load', renderOverlay, { once: true });
                }
              });
            }

            function renderSessionsListFromPayload(sessionSummaries) {
              const activeId = state.session?.sessionId;
              sessionCount.textContent = String(sessionSummaries.length);
              sessions.innerHTML = sessionSummaries.map((session, index) =>
                '<button class="row session-row ' + (session.sessionId === activeId ? 'active' : '') + '" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<strong>' + escapeHtml(formatSessionLabel(session, index)) + '</strong>' +
                  '<span>' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                '</button>'
              ).join('') || '<div class="row"><span>No saved sessions.</span></div>';
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', () => openSession(row.dataset.sessionId).catch(showError));
              });
            }

            function renderSessionsList() {
              const activeId = state.session?.sessionId;
              document.querySelectorAll('.session-row').forEach(row => {
                row.classList.toggle('active', row.dataset.sessionId === activeId);
              });
            }

            function renderSentHistory() {
              const session = state.session;
              const allItems = session?.items || [];
              const sentItems = allItems.filter(item => item.delivery === 'sent');
              const handoffBatches = session ? session.handoffBatches || [] : [];
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
                  const detail = item.handoffBatchId ? 'No batch metadata' : 'Sent outside a batch';
                  return '<div class="row">' +
                    '<strong>' + escapeHtml(label) + '</strong>' +
                    '<span>' + escapeHtml(firstLine(item.comment || '(No comment)')) + ' · ' + escapeHtml(detail) + '</span>' +
                  '</div>';
                });
              sentHistory.innerHTML = batchRows.concat(unbatchedRows).join('') || '<div class="row"><span>No sent handoff history.</span></div>';
            }

            function renderSessionRegions() {
              const session = state.session;
              const allItems = session?.items || [];
              sessionMeta.textContent = session ? formatSessionHeader(session, allItems.length) : 'No active session';
              renderSessionsList();
              renderSentHistory();
            }

            function renderComposerInspector() {
              const item = selectedAnnotation();
              inspectorTitle.textContent = item ? 'Annotation' : 'Annotations';
              inspectorCount.textContent = String(pendingFeedbackItems.length);
              selectionSummary.hidden = Boolean(item);
              comment.hidden = !item;
              if (item) {
                comment.value = item.comment || '';
              }
              pendingItems.hidden = false;
              draftItems.hidden = true;
              clearSelectionButton.hidden = !item;
              cancelAddFlowButton.hidden = toolMode !== 'annotate';
              addItemButton.hidden = false;
              clearDraftButton.hidden = true;
              renderPendingItems();
            }

            function renderDraftInspector() {
              const groups = savedEvidenceGroups();
              inspectorTitle.textContent = 'Draft';
              inspectorCount.textContent = String(groups.reduce((sum, group) => sum + group.items.length, 0));
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = true;
              draftItems.hidden = false;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = groups.length === 0;
              renderSavedEvidenceGroups();
            }

            function renderInspectorRegion() {
              if (addItemsFlow) {
                renderComposerInspector();
              } else {
                renderDraftInspector();
              }
              updateComposerState();
            }

            function ensurePreviewFrame() {
              let frame = document.getElementById('snapshotFrame');
              if (frame) return frame;
              snapshot.innerHTML =
	                '<div id="snapshotFrame" class="snapshot-frame">' +
	                  '<img id="snapshotImage" alt="PointPatch preview" aria-label="PointPatch preview">' +
	                  '<div id="selectionOverlay" class="selection-overlay"></div>' +
	                '</div>';
              attachSnapshotHandlers();
              return document.getElementById('snapshotFrame');
            }

            function renderPreviewRegion() {
              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              const mode = addItemsFlow ? 'frozen' : (state.preview ? 'live' : 'idle');
              previewModeBadge.dataset.mode = mode;
              previewModeBadge.textContent = mode === 'frozen' ? 'Frozen' : mode === 'live' ? 'Live' : 'Idle';
              snapshotTitle.textContent = addItemsFlow ? 'Frozen Feedback Snapshot' : 'Live Preview';
              if (!hasScreenshot) {
                snapshot.innerHTML = '<div class="empty-stage">' + (screen ? 'No screenshot artifact for this preview.' : 'Refresh the live preview to begin.') + '</div>';
                updateComposerState();
                return;
              }
              const frame = ensurePreviewFrame();
              frame.dataset.mode = mode;
              const image = document.getElementById('snapshotImage');
              const src = addItemsFlow?.screenshotUrl || previewScreenshotUrl(state.preview.previewId);
              if (image.getAttribute('src') !== src) {
                image.setAttribute('src', src);
              }
              let hint = document.getElementById('annotateHint');
              if (toolMode === 'annotate') {
                if (!hint) {
                  hint = document.createElement('div');
                  hint.id = 'annotateHint';
                  hint.className = 'annotate-hint';
                  frame.appendChild(hint);
                }
                hint.textContent = 'Annotate mode';
              } else if (hint) {
                hint.remove();
              }
              renderSelectionOverlay();
            }

            function renderPreviewOnly() {
              renderPreviewRegion();
              renderSelectionOverlay();
              updateComposerState();
            }

            function render() {
              renderSessionRegions();
              renderPreviewRegion();
              renderInspectorRegion();
              updateComposerState();
            }

            async function refreshSessions() {
              const response = await requestJson('/api/sessions');
              renderSessionsListFromPayload(response.sessions || []);
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
                  if (toolMode === 'select' && addItemsFlow) {
                    clearSelection();
                    return;
                  }
                  if (!addItemsFlow) {
                    const point = naturalPointFromEvent(event, image);
                    navigate('tap', { x: point.x, y: point.y }).catch(showError);
                    return;
                  }
                  if (toolMode === 'annotate' && !dragStart) {
                    selectNodeAtPoint(event, image);
                  }
                } catch (cause) {
                  showError(cause);
                }
              });
              image.addEventListener('pointerdown', event => {
                if (!addItemsFlow || toolMode !== 'annotate') return;
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
                if (!addItemsFlow || toolMode !== 'annotate' || !dragStart) return;
                try {
                  dragPreview = normalizeBounds(dragStart, naturalPointFromEvent(event, image));
                  renderSelectionOverlay();
                } catch (cause) {
                  clearDragState();
                  showError(cause);
                }
              });
              image.addEventListener('pointerup', event => {
                if (!addItemsFlow || toolMode !== 'annotate' || !dragStart) return;
                try {
                  const end = naturalPointFromEvent(event, image);
                  const bounds = normalizeBounds(dragStart, end);
                  clearDragState();
                  releaseSnapshotPointerCapture(image, event);
                  if ((bounds.right - bounds.left) >= 8 && (bounds.bottom - bounds.top) >= 8) {
                    suppressNextClick = true;
                    finishAreaSelection(bounds);
	                  } else {
	                    suppressNextClick = true;
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

            function isTextInputFocused(target = document.activeElement) {
              const element = target?.nodeType === Node.ELEMENT_NODE ? target : target?.parentElement || document.activeElement;
              const tag = element?.tagName;
              return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || element?.isContentEditable;
            }

            function handleGlobalShortcut(event) {
              if (event.repeat) return;
              if (isTextInputFocused(event.target)) return;
              if (event.key === 'Escape') {
                event.preventDefault();
                if (addItemsFlow) {
                  cancelAddItemsFlow();
                } else {
                  clearSelection();
                }
                return;
              }
              if (event.key.toLowerCase() === 'a' && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
                event.preventDefault();
                enterAnnotateMode().catch(showError);
                return;
              }
              if (event.key.toLowerCase() === 's' && (event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey) {
                event.preventDefault();
                savePendingFeedbackItems().catch(showError);
                return;
              }
              if (event.key.toLowerCase() === 'n' && (event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey) {
                event.preventDefault();
                newSession().catch(showError);
              }
            }

            document.getElementById('refreshButton').addEventListener('click', () => refreshPreview().catch(showError));
            selectToolButton.addEventListener('click', enterSelectMode);
            annotateToolButton.addEventListener('click', () => enterAnnotateMode().catch(showError));
            saveButton.addEventListener('click', () => savePendingFeedbackItems().catch(showError));
            addItemButton.addEventListener('click', () => {
              try {
                createAnnotationFromSelection(currentSelection);
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
            document.addEventListener('keydown', handleGlobalShortcut);
            document.addEventListener('visibilitychange', () => {
              if (!document.hidden && shouldAutoFetchPreview()) refreshPreview().catch(showError);
              startLivePreviewPolling();
            });
            clearSelectionButton.addEventListener('click', clearSelection);
            cancelAddFlowButton.addEventListener('click', cancelAddItemsFlow);
            clearDraftButton.addEventListener('click', () => clearDraft().catch(showError));
            document.getElementById('sendDraftButton').addEventListener('click', () => sendDraftToAgent().catch(showError));
            document.getElementById('backButton').addEventListener('click', () => navigate('back').catch(showError));
            document.getElementById('swipeUpButton').addEventListener('click', () => navigate('swipe', { direction: 'up' }).catch(showError));
            document.getElementById('swipeDownButton').addEventListener('click', () => navigate('swipe', { direction: 'down' }).catch(showError));
            document.getElementById('swipeLeftButton').addEventListener('click', () => navigate('swipe', { direction: 'left' }).catch(showError));
            document.getElementById('swipeRightButton').addEventListener('click', () => navigate('swipe', { direction: 'right' }).catch(showError));
            comment.addEventListener('input', updateSelectedAnnotationComment);

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
