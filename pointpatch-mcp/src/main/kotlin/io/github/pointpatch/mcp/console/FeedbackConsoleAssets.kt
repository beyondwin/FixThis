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
            .with-icon {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              gap: 6px;
            }
            .button-icon {
              width: 16px;
              height: 16px;
              flex: 0 0 auto;
            }
            .button-icon svg {
              display: block;
              width: 16px;
              height: 16px;
              stroke: currentColor;
            }
            .btn-icon {
              width: 16px;
              height: 16px;
              display: inline-grid;
              place-items: center;
              flex: 0 0 auto;
              font-size: 13px;
              font-weight: 800;
              line-height: 1;
              opacity: .72;
            }
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
            .history-list { align-content: start; }
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
            .history-item {
              display: grid;
              gap: 0;
              width: 100%;
              border: 1px solid transparent;
              border-radius: 8px;
              padding: 12px;
              background: transparent;
              text-align: left;
              cursor: pointer;
              transition: background 120ms ease, border-color 120ms ease;
            }
            .history-item:hover,
            .history-item.is-active {
              background: var(--bg-2);
            }
            .history-item.is-active {
              border-color: var(--line);
              box-shadow: inset 2px 0 0 var(--accent);
            }
            .hi-head {
              display: flex;
              align-items: flex-start;
              justify-content: space-between;
              gap: 10px;
            }
            .hi-title {
              color: var(--txt-0);
              font-size: 13px;
              font-weight: 700;
              line-height: 1.25;
            }
            .hi-del {
              width: 20px;
              min-width: 20px;
              height: 20px;
              min-height: 20px;
              border: 0;
              border-radius: 4px;
              padding: 0;
              background: transparent;
              color: var(--txt-2);
              cursor: pointer;
              font-size: 16px;
              line-height: 1;
              opacity: 0;
              transition: background 120ms ease, color 120ms ease, opacity 120ms ease;
            }
            .history-item:hover .hi-del { opacity: 1; }
            .hi-del:hover { background: var(--bg-3); color: var(--danger); }
            .hi-meta {
              margin-top: 2px;
              color: var(--txt-2);
              font-size: 11px;
            }
            .hi-stats {
              display: flex;
              flex-wrap: wrap;
              gap: 10px;
              margin-top: 8px;
            }
            .hi-pip {
              display: inline-flex;
              align-items: center;
              gap: 5px;
              color: var(--txt-1);
              font-size: 11px;
              font-variant-numeric: tabular-nums;
            }
            .hi-pip::before {
              content: '';
              width: 9px;
              height: 9px;
              border-radius: 999px;
              background: var(--warning);
              box-shadow: 0 0 0 3px rgba(230, 180, 90, .18);
            }
            .hi-pip.done::before {
              background: #6fcf97;
              box-shadow: 0 0 0 3px rgba(111, 207, 151, .18);
            }
            .hi-pip.points::before {
              background: #5bb4e8;
              box-shadow: 0 0 0 3px rgba(91, 180, 232, .14);
            }
            .hi-strip {
              display: flex;
              gap: 2px;
              margin-top: 8px;
            }
            .hi-strip-cell {
              flex: 1;
              height: 4px;
              border-radius: 2px;
              background: var(--warning);
            }
            .hi-strip-cell.done {
              background: #6fcf97;
              opacity: .35;
            }
            .hi-strip-cell.empty {
              background: var(--line);
              opacity: .8;
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
              grid-template-rows: auto 1fr;
            }
            .canvas-toolbar {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 16px;
              padding: 10px 16px;
              border-bottom: 1px solid var(--line);
              background: var(--bg-1);
            }
            .tool-group,
            .zoom-control {
              display: inline-flex;
              align-items: center;
              gap: 4px;
              padding: 3px;
              border: 0;
              border-radius: 8px;
              background: var(--bg-2);
            }
            .tool-button,
            .zoom-button {
              min-width: 0;
              min-height: 30px;
              padding: 0 12px;
              border: 0;
              border-radius: 6px;
              background: transparent;
              color: var(--txt-1);
              font-size: 12px;
              font-weight: 500;
            }
            .tool-button[aria-pressed="true"] {
              background: var(--bg-3);
              color: var(--accent);
              box-shadow: 0 1px 2px rgba(0, 0, 0, .30);
            }
            .tool-status {
              flex: 1;
              display: flex;
              align-items: center;
              justify-content: center;
              min-width: 160px;
              color: var(--txt-1);
              font-size: 11px;
              font-weight: 500;
              font-variant-numeric: tabular-nums;
              letter-spacing: 0;
            }
            .ts-meta {
              display: flex;
              align-items: center;
              justify-content: center;
              gap: 16px;
            }
            .ts-dot-label {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              white-space: nowrap;
            }
            .ts-dot {
              width: 10px;
              height: 10px;
              border-radius: 999px;
              background: #f2c94c;
              box-shadow: 0 0 0 3px rgba(242, 201, 76, .18);
            }
            .ts-dot.resolved {
              background: #6fcf97;
              box-shadow: 0 0 0 3px rgba(111, 207, 151, .18);
            }
            .ts-hint {
              display: inline-flex;
              align-items: center;
              gap: 8px;
              border: 1px solid rgba(184, 211, 106, .25);
              border-radius: 999px;
              padding: 5px 12px;
              background: rgba(184, 211, 106, .08);
              color: var(--accent);
            }
            .ts-hint .ts-dot {
              width: 6px;
              height: 6px;
              background: var(--accent);
              box-shadow: none;
              animation: pulse-a 1.4s infinite;
            }
            .zoom-control {
              color: var(--txt-2);
              font-size: 11px;
              font-weight: 700;
              border-radius: 6px;
              gap: 6px;
              font-variant-numeric: tabular-nums;
            }
            .zoom-button {
              width: 24px;
              height: 24px;
              padding: 0;
              border-radius: 4px;
              font-size: 14px;
            }
            .zoom-button:disabled {
              opacity: .45;
              cursor: default;
            }
            .snapshot-stage {
              display: grid;
              place-items: center;
              min-height: 0;
              overflow: auto;
              padding: 24px;
              background:
                radial-gradient(circle at 50% 50%, var(--bg-1) 0%, var(--bg-0) 70%),
                var(--bg-0);
              color: var(--txt-2);
              text-align: center;
            }
            .empty-stage { color: var(--txt-2); font-size: 13px; }
            .snapshot-frame {
              position: relative;
              display: inline-block;
              max-width: min(100%, 320px);
              max-height: 100%;
              padding: 8px;
              border-radius: 44px;
              background: linear-gradient(180deg, #2a2a2e 0%, #1a1a1d 100%);
              box-shadow:
                0 0 0 2px #3a3a40,
                0 30px 60px -20px rgba(0, 0, 0, .6),
                0 12px 24px -8px rgba(0, 0, 0, .4),
                inset 0 1px 0 rgba(255, 255, 255, .06);
              transform: scale(var(--preview-zoom, 1));
              transform-origin: center center;
              transition: transform 120ms ease;
            }
            .snapshot-frame[data-mode="frozen"] {
              box-shadow:
                0 0 0 2px var(--accent),
                0 0 0 6px rgba(184, 211, 106, .10),
                0 30px 60px -20px rgba(0, 0, 0, .6),
                0 12px 24px -8px rgba(0, 0, 0, .4),
                inset 0 1px 0 rgba(255, 255, 255, .06);
            }
            .snapshot-frame::before {
              content: '';
              position: absolute;
              top: 18px;
              left: 50%;
              width: 86px;
              height: 22px;
              transform: translateX(-50%);
              border-radius: 999px;
              background: #000;
              z-index: 2;
              pointer-events: none;
            }
            .snapshot-frame img {
              display: block;
              max-width: 100%;
              max-height: calc(100vh - 160px);
              width: auto;
              height: auto;
              border: 0;
              border-radius: 36px;
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
            .selection-box.hover-preview {
              border-style: dashed;
              background: rgba(184, 211, 106, .10);
              box-shadow: 0 0 0 2px rgba(184, 211, 106, .14);
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
            .ann-list {
              flex: 1 1 auto;
              overflow-y: auto;
              padding: 8px;
              display: grid;
              gap: 4px;
              align-content: start;
            }
            .ann-row {
              display: grid;
              grid-template-columns: 28px 1fr auto;
              gap: 10px;
              align-items: center;
              width: 100%;
              border: 0;
              border-radius: 8px;
              padding: 10px;
              background: transparent;
              text-align: left;
              transition: background 120ms ease;
            }
            .ann-row:hover,
            .ann-row.active {
              background: var(--bg-2);
            }
            .ann-row-num {
              width: 24px;
              height: 24px;
              display: inline-grid;
              place-items: center;
              border-radius: 6px;
              color: var(--bg-0);
              font-size: 11px;
              font-weight: 700;
            }
            .ann-row-body {
              min-width: 0;
            }
            .ann-row-title {
              color: var(--txt-0);
              font-size: 13px;
              font-weight: 700;
              overflow: hidden;
              text-overflow: ellipsis;
              white-space: nowrap;
            }
            .ann-row-comment {
              margin-top: 3px;
              color: var(--txt-1);
              font-size: 11px;
              line-height: 1.4;
              display: -webkit-box;
              -webkit-line-clamp: 2;
              -webkit-box-orient: vertical;
              overflow: hidden;
            }
            .ann-row-comment.empty-comment {
              color: #999;
              font-style: italic;
            }
            .ann-row-status {
              border-radius: 999px;
              padding: 2px 7px;
              font-size: 10px;
              font-weight: 800;
              letter-spacing: .08em;
              text-transform: uppercase;
              white-space: nowrap;
            }
            .ann-row-status.st-open { background: rgba(230, 180, 90, .16); color: #e6b45a; }
            .ann-row-status.st-in-progress { background: rgba(91, 157, 255, .16); color: #5b9dff; }
            .ann-row-status.st-resolved { background: rgba(111, 207, 151, .16); color: #6fcf97; }
            .annotation-detail {
              flex: 1 1 auto;
              overflow-y: auto;
              padding: 16px;
              display: flex;
              flex-direction: column;
              gap: 12px;
            }
            .annotation-back {
              width: fit-content;
              border: 0;
              background: transparent;
              padding: 4px 0;
              color: var(--txt-2);
              font-size: 11px;
            }
            .annotation-back:hover { color: var(--txt-0); background: transparent; }
            .annotation-field {
              display: flex;
              flex-direction: column;
              gap: 6px;
            }
            .annotation-field label {
              color: var(--txt-2);
              font-size: 10px;
              font-weight: 800;
              letter-spacing: .12em;
              text-transform: uppercase;
            }
            .annotation-input,
            .annotation-textarea {
              width: 100%;
              border: 1px solid var(--line);
              border-radius: 6px;
              background: var(--bg-2);
              color: var(--txt-0);
              padding: 8px 10px;
              font: inherit;
              font-size: 13px;
              outline: none;
              transition: border-color 120ms ease;
            }
            .annotation-input:focus,
            .annotation-textarea:focus {
              border-color: var(--accent);
            }
            .annotation-textarea {
              min-height: 96px;
              line-height: 1.5;
              resize: vertical;
            }
            .annotation-segmented {
              display: flex;
              gap: 2px;
              padding: 2px;
              border-radius: 7px;
              background: var(--bg-2);
            }
            .annotation-segmented button {
              flex: 1;
              border: 0;
              border-radius: 5px;
              background: transparent;
              padding: 6px 10px;
              color: var(--txt-1);
              font-size: 11px;
              font-weight: 700;
              letter-spacing: .06em;
              text-transform: uppercase;
            }
            .annotation-segmented button.active {
              background: var(--bg-3);
              color: var(--txt-0);
            }
            .annotation-actions {
              display: flex;
              justify-content: space-between;
              gap: 10px;
              border-top: 1px solid var(--line-soft);
              padding-top: 12px;
              margin-top: 4px;
            }
            .annotation-danger,
            .annotation-done {
              border: 0;
              background: transparent;
              padding: 6px 0;
              font-size: 13px;
            }
            .annotation-danger { color: var(--danger); }
            .annotation-done { color: var(--txt-1); }
            .annotation-danger:hover,
            .annotation-done:hover {
              background: transparent;
              color: var(--txt-0);
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
              body {
                height: auto;
                min-height: 100vh;
                overflow: auto;
              }
              .studio-shell {
                min-height: 100vh;
                height: auto;
                overflow: visible;
                grid-template-rows: auto 1fr;
              }
              .studio-topbar {
                position: sticky;
                top: 0;
                z-index: 10;
                grid-template-columns: 1fr;
                align-items: stretch;
                gap: 10px;
                padding: 10px;
              }
              .studio-brand {
                justify-content: flex-start;
              }
              .studio-context {
                grid-template-columns: minmax(0, 1fr) auto auto;
                gap: 6px;
              }
              .session-meta {
                grid-column: 1 / -1;
              }
              .device-control {
                min-width: 0;
                max-width: none;
              }
              .clear-device-button {
                width: auto;
                min-width: 0;
                max-width: none;
              }
              .studio-actions {
                justify-content: flex-start;
                flex-wrap: wrap;
              }
              .studio-body {
                grid-template-columns: 1fr;
                grid-template-rows: auto minmax(420px, 72vh) auto;
                overflow: visible;
              }
              .studio-history {
                max-height: 180px;
                border-right: 0;
                border-bottom: 1px solid var(--line);
                overflow: auto;
              }
              .studio-canvas {
                grid-template-rows: auto minmax(360px, 1fr);
                min-height: 420px;
              }
              .canvas-toolbar {
                flex-wrap: wrap;
                justify-content: flex-start;
                gap: 8px;
                padding: 8px;
              }
              .tool-status {
                flex: 1 1 140px;
                min-width: 0;
                justify-content: flex-start;
              }
              .snapshot-stage {
                min-height: 360px;
                overflow: auto;
                padding: 14px;
              }
              .snapshot-frame {
                max-width: min(100%, 320px);
                padding: 6px;
                border-radius: 38px;
              }
              .snapshot-frame img {
                max-height: calc(100vh - 240px);
                border-radius: 30px;
              }
              .selection-overlay {
                inset: 6px;
              }
              .annotate-hint {
                top: 10px;
                width: calc(100% - 24px);
                justify-content: center;
                white-space: normal;
                padding: 7px 10px;
              }
              .studio-inspector {
                min-height: 280px;
                border-left: 0;
                border-top: 1px solid var(--line);
                overflow: visible;
              }
              .inspector-body {
                max-height: none;
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
                <button id="refreshButton" class="with-icon" type="button">
                  <span class="button-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 0 1-15.5 6.2"/><path d="M3 12A9 9 0 0 1 18.5 5.8"/><path d="M18 2v4h4"/><path d="M6 22v-4H2"/></svg></span>
                  <span>Refresh</span>
                </button>
                <button id="saveButton" class="primary with-icon" type="button" disabled>
                  <span class="btn-icon" aria-hidden="true">⌘</span>
                  <span>Save snapshot</span>
                </button>
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
                    <button id="selectToolButton" class="tool-button with-icon" type="button" aria-pressed="true">
                      <span class="button-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 3l7.5 18 2.3-7.2L21 11.5 4 3z"/></svg></span>
                      <span>Select</span>
                    </button>
                    <button id="annotateToolButton" class="tool-button with-icon" type="button" aria-pressed="false">
                      <span class="button-icon" aria-hidden="true"><svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="5" width="16" height="14" rx="2" stroke-dasharray="3 3"/></svg></span>
                      <span>Annotate</span>
                    </button>
                  </div>
                  <div id="toolStatus" class="tool-status">Select mode</div>
                  <div class="zoom-control" aria-label="Zoom controls">
                    <button id="zoomOutButton" class="zoom-button" type="button" aria-label="Zoom out">−</button>
                    <span id="zoomPercent">100%</span>
                    <button id="zoomInButton" class="zoom-button" type="button" aria-label="Zoom in">+</button>
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
            const inspectorTitle = document.getElementById('inspectorTitle');
            const inspectorCount = document.getElementById('inspectorCount');
            const inspectorBody = document.getElementById('inspectorBody');
            const inspectorFooter = document.getElementById('inspectorFooter');
            const draftItems = document.getElementById('draftItems');
            const pendingItems = document.getElementById('pendingItems');
            const error = document.getElementById('error');
            const comment = document.getElementById('comment');
            const devicePicker = document.getElementById('devicePicker');
            const deviceStatus = document.getElementById('deviceStatus');
            const deviceControl = document.getElementById('deviceControl');
            const deviceName = document.getElementById('deviceName');
            const deviceConnectionState = document.getElementById('deviceConnectionState');
            const previewIntervalSelect = document.getElementById('previewIntervalSelect');
            const selectionSummary = document.getElementById('selectionSummary');
            const clearSelectionButton = document.getElementById('clearSelectionButton');
            const addItemButton = document.getElementById('addItemButton');
            const saveButton = document.getElementById('saveButton');
            const cancelAddFlowButton = document.getElementById('cancelAddFlowButton');
            const clearDraftButton = document.getElementById('clearDraftButton');
            const selectToolButton = document.getElementById('selectToolButton');
            const annotateToolButton = document.getElementById('annotateToolButton');
            const toolStatus = document.getElementById('toolStatus');
            const zoomOutButton = document.getElementById('zoomOutButton');
            const zoomInButton = document.getElementById('zoomInButton');
            const zoomPercent = document.getElementById('zoomPercent');
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
            let hoveredAnnotationTarget = null;
            let dragStart = null;
            let dragPreview = null;
            let suppressNextClick = false;
            let previewZoom = 1;

            const PreviewZoomMin = 0.5;
            const PreviewZoomMax = 2;
            const PreviewZoomStep = 0.1;

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

            function formatHistoryDate(epochMillis) {
              if (!epochMillis) return '-';
              const date = new Date(epochMillis);
              const day = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
              const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
              return day + ' · ' + time;
            }

            function humanize(value) {
              const normalized = text(value);
              if (normalized === '-') return normalized;
              return normalized
                .split('_')
                .join(' ')
                .split('.')
                .filter(Boolean)
                .map(part => part.charAt(0).toUpperCase() + part.slice(1))
                .join(' ');
            }

            function countLabel(count, singular, plural) {
              return String(count) + ' ' + (count === 1 ? singular : plural);
            }

            function formatSessionLabel(session, index) {
              if (state.session?.sessionId === session.sessionId) {
                const latestScreen = [...(state.session.screens || [])].sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0))[0];
                if (latestScreen?.displayName) return latestScreen.displayName;
              }
              const packageTail = text(session.packageName || '').split('.').filter(Boolean).pop();
              return (packageTail ? humanize(packageTail) : 'Feedback snapshot') + ' v' + (index + 1);
            }

            function formatSessionSummary(session) {
              return 'You · ' + formatHistoryDate(session.updatedAtEpochMillis);
            }

            function historyOpenCount(session) {
              return session.unresolvedItemsCount || 0;
            }

            function historyDoneCount(session) {
              return Math.max(0, (session.itemsCount || 0) - historyOpenCount(session));
            }

            function historyPointsCount(session) {
              return session.itemsCount || 0;
            }

            function renderHistoryStrip(session) {
              const open = historyOpenCount(session);
              const done = historyDoneCount(session);
              const total = historyPointsCount(session);
              if (!total) return '<span class="hi-strip-cell empty"></span>';
              return [
                ...Array.from({ length: open }, () => '<span class="hi-strip-cell"></span>'),
                ...Array.from({ length: done }, () => '<span class="hi-strip-cell done"></span>')
              ].join('');
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

            function statusLabel(status) {
              if (status === 'in-progress') return 'In-progress';
              if (status === 'resolved') return 'Resolved';
              return 'Open';
            }

            function statusClass(status) {
              if (status === 'in-progress') return 'st-in-progress';
              if (status === 'resolved') return 'st-resolved';
              return 'st-open';
            }

            function toolbarAnnotations() {
              if (addItemsFlow) return pendingFeedbackItems;
              return state.session?.items || [];
            }

            function toolbarResolvedCount() {
              return toolbarAnnotations().filter(item => annotationStatus(item) === 'resolved').length;
            }

            function toolbarOpenCount() {
              return toolbarAnnotations().filter(item => annotationStatus(item) !== 'resolved').length;
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

            function screenImageUrl(screen) {
              if (addItemsFlow) return addItemsFlow.screenshotUrl;
              if (state.preview?.screen === screen && state.preview?.previewId) return previewScreenshotUrl(state.preview.previewId);
              if (screen?.screenId) return '/api/screens/' + encodeURIComponent(screen.screenId) + '/screenshot/full';
              return '';
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

            function latestPersistedScreen() {
              const screens = state.session?.screens || [];
              const persistedScreenIds = new Set(
                (state.session?.items || [])
                  .filter(item => item.delivery !== 'sent')
                  .map(item => item.screenId)
              );
              const screenshotScreens = screens
                .filter(screen => screen?.screenshot?.desktopFullPath);
              return screenshotScreens
                .filter(screen => persistedScreenIds.has(screen.screenId))
                .sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0))[0] ||
                screenshotScreens
                .sort((left, right) => (right.capturedAtEpochMillis || 0) - (left.capturedAtEpochMillis || 0))[0] || null;
            }

            function latestScreen() {
              return addItemsFlow?.screen || state.preview?.screen || latestPersistedScreen();
            }

            function clamp(value, min, max) {
              return Math.min(Math.max(value, min), max);
            }

            function applyPreviewZoom() {
              const frame = document.getElementById('snapshotFrame');
              zoomPercent.textContent = Math.round(previewZoom * 100) + '%';
              zoomOutButton.disabled = previewZoom <= PreviewZoomMin;
              zoomInButton.disabled = previewZoom >= PreviewZoomMax;
              if (frame) {
                frame.style.setProperty('--preview-zoom', String(previewZoom));
              }
            }

            function setPreviewZoom(nextZoom) {
              previewZoom = Math.round(clamp(nextZoom, PreviewZoomMin, PreviewZoomMax) * 10) / 10;
              applyPreviewZoom();
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

            function renderOverlayBox(overlay, image, bounds, labelText, isDragPreview = false, isFocused = false, annotationIndex = null, extraClass = '') {
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
              const screen = latestScreen();
              const persistedItems = persistedItemsForScreen(screen?.screenId);
              if (!addItemsFlow && !state.preview && persistedItems.length) {
                renderSavedEvidenceOverlay(overlay, image, persistedItems);
              }
              if (currentSelection) {
                renderOverlayBox(overlay, image, currentSelection.bounds, currentSelection.label);
              }
              if (addItemsFlow && toolMode === 'annotate' && hoveredAnnotationTarget && !dragPreview) {
                renderOverlayBox(overlay, image, hoveredAnnotationTarget.bounds, null, false, false, null, 'hover-preview');
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
              const selection = nodeSelectionAtPoint(event, image);
              if (!selection) {
                showError(new Error('No component found at that point. Drag to select a custom area.'));
                return;
              }
              currentSelection = selection;
              createAnnotationFromSelection(selection);
              error.textContent = '';
            }

            function previewNodeAtPoint(event, image) {
              const selection = nodeSelectionAtPoint(event, image);
              const nextId = selection?.nodeUid || null;
              const currentId = hoveredAnnotationTarget?.nodeUid || null;
              if (nextId === currentId) return;
              hoveredAnnotationTarget = selection;
              renderSelectionOverlay();
            }

            function confirmHoveredAnnotationTarget(event, image) {
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

            function resetAnnotationComposerState(clearFlow = true) {
              if (clearFlow) addItemsFlow = null;
              pendingFeedbackItems = [];
              focusedPendingItemIndex = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              toolMode = 'select';
              comment.value = '';
              clearDragState();
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
              hoveredAnnotationTarget = null;
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
                severity: 'med',
                status: 'open',
                comment: ''
              };
              pendingFeedbackItems.push(annotation);
              currentSelection = null;
              hoveredAnnotationTarget = null;
              focusedPendingItemIndex = pendingFeedbackItems.length - 1;
              toolMode = 'select';
              comment.value = '';
              renderPreviewOnly();
              renderInspectorRegion();
            }

            function deletePendingFeedbackItem(index) {
              pendingFeedbackItems.splice(index, 1);
              focusedPendingItemIndex = null;
              currentSelection = null;
              hoveredAnnotationTarget = null;
              comment.value = '';
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
              resetAnnotationComposerState();
              state.preview = null;
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
              if (focusedPendingItemIndex != null && selectedAnnotation()) {
                renderAnnotationDetail(selectedAnnotation(), focusedPendingItemIndex);
                return;
              }
              pendingItems.innerHTML = pendingFeedbackItems.length
                ? '<div class="ann-list">' + pendingFeedbackItems.map((item, index) => {
                  const commentText = firstLine(item.comment || 'No comment');
                  const hasComment = Boolean(String(item.comment || '').trim());
                  const status = annotationStatus(item);
                  return '<button type="button" class="ann-row pending-item-row ' + (index === focusedPendingItemIndex ? 'active' : '') + '" data-focus-pending="' + index + '">' +
                    '<span class="ann-row-num" style="background:' + severityColor(annotationSeverity(item)) + '">' + (index + 1) + '</span>' +
                    '<span class="ann-row-body">' +
                      '<span class="ann-row-title">' + escapeHtml(annotationTitle(item, index)) + '</span>' +
                      '<span class="ann-row-comment ' + (hasComment ? '' : 'empty-comment') + '">' + escapeHtml(commentText) + '</span>' +
                    '</span>' +
                    '<span class="ann-row-status ' + statusClass(status) + '">' + escapeHtml(statusLabel(status)) + '</span>' +
                  '</button>';
                }).join('') + '</div>'
                : '<div class="empty-state"><div class="empty-title">No annotations yet.</div><div class="empty-body">Switch to <b>Annotate</b>, then click or drag on the preview.</div><button type="button" class="primary" data-start-annotating>Start annotating</button></div>';
              pendingItems.querySelectorAll('[data-focus-pending]').forEach(button => {
                button.addEventListener('click', () => focusPendingFeedbackItem(Number(button.dataset.focusPending)));
              });
              pendingItems.querySelectorAll('[data-start-annotating]').forEach(button => {
                button.addEventListener('click', () => enterAnnotateMode().catch(showError));
              });
            }

            function renderAnnotationDetail(item, index) {
              const severity = annotationSeverity(item);
              const status = annotationStatus(item);
              pendingItems.innerHTML =
                '<div class="annotation-detail">' +
                  '<button type="button" class="annotation-back" data-back-annotations>← All annotations</button>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationLabelInput">Label</label>' +
                    '<input id="annotationLabelInput" class="annotation-input" value="' + escapeHtml(annotationTitle(item, index)) + '">' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Severity</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Severity">' +
                      ['high', 'med', 'low'].map(value =>
                        '<button type="button" class="' + (severity === value ? 'active' : '') + '" data-set-severity="' + value + '"' +
                          (severity === value ? ' style="background:' + severityColor(value) + '; color: var(--bg-0);"' : '') + '>' +
                          escapeHtml(value === 'med' ? 'Med' : value) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label for="annotationCommentInput">Comment</label>' +
                    '<textarea id="annotationCommentInput" class="annotation-textarea">' + escapeHtml(item.comment || '') + '</textarea>' +
                  '</div>' +
                  '<div class="annotation-field">' +
                    '<label>Status</label>' +
                    '<div class="annotation-segmented" role="group" aria-label="Status">' +
                      ['open', 'in-progress', 'resolved'].map(value =>
                        '<button type="button" class="' + (status === value ? 'active' : '') + '" data-set-status="' + value + '">' +
                          escapeHtml(statusLabel(value)) +
                        '</button>'
                      ).join('') +
                    '</div>' +
                  '</div>' +
                  '<div class="annotation-actions">' +
                    '<button type="button" class="annotation-danger" data-delete-current>Delete</button>' +
                    '<button type="button" class="annotation-done" data-back-annotations>Done</button>' +
                  '</div>' +
                '</div>';
              const labelInput = document.getElementById('annotationLabelInput');
              const commentInput = document.getElementById('annotationCommentInput');
              labelInput.addEventListener('input', event => {
                item.label = event.target.value;
                updateComposerState();
                renderPreviewOnly();
              });
              commentInput.addEventListener('input', event => {
                item.comment = event.target.value;
                updateComposerState();
              });
              pendingItems.querySelectorAll('[data-set-severity]').forEach(button => {
                button.addEventListener('click', () => {
                  item.severity = button.dataset.setSeverity;
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelectorAll('[data-set-status]').forEach(button => {
                button.addEventListener('click', () => {
                  item.status = button.dataset.setStatus;
                  renderPreviewOnly();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelectorAll('[data-back-annotations]').forEach(button => {
                button.addEventListener('click', () => {
                  focusedPendingItemIndex = null;
                  renderPreviewOnly();
                  renderInspectorRegion();
                });
              });
              pendingItems.querySelector('[data-delete-current]').addEventListener('click', () => {
                deletePendingFeedbackItem(index);
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

            function persistedItemsForScreen(screenId) {
              if (!screenId) return [];
              return (state.session?.items || [])
                .filter(item => item.delivery !== 'sent' && item.screenId === screenId);
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
              sessions.innerHTML = sessionSummaries.map((session, index) => {
                const open = historyOpenCount(session);
                const done = historyDoneCount(session);
                const points = historyPointsCount(session);
                const label = formatSessionLabel(session, index);
                return '<div class="history-item session-row ' + (session.sessionId === activeId ? 'is-active' : '') + '" role="button" tabindex="0" data-session-id="' + escapeHtml(session.sessionId) + '">' +
                  '<span class="hi-head">' +
                    '<span class="hi-title">' + escapeHtml(label) + '</span>' +
                    '<button type="button" class="hi-del" data-delete-session-id="' + escapeHtml(session.sessionId) + '" aria-label="Delete history item ' + escapeHtml(label) + '">×</button>' +
                  '</span>' +
                  '<span class="hi-meta">' + escapeHtml(formatSessionSummary(session)) + '</span>' +
                  '<span class="hi-stats">' +
                    '<span class="hi-pip open">' + escapeHtml(countLabel(open, 'open', 'open')) + '</span>' +
                    '<span class="hi-pip done">' + escapeHtml(countLabel(done, 'done', 'done')) + '</span>' +
                    '<span class="hi-pip points">' + escapeHtml(countLabel(points, 'pt', 'pts')) + '</span>' +
                  '</span>' +
                  '<span class="hi-strip">' + renderHistoryStrip(session) + '</span>' +
                '</div>';
              }).join('') || '<div class="empty-state"><div class="empty-title">No saved sessions.</div></div>';
              document.querySelectorAll('.session-row').forEach(row => {
                row.addEventListener('click', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  openSession(row.dataset.sessionId).catch(showError);
                });
                row.addEventListener('keydown', event => {
                  if (event.target.closest('[data-delete-session-id]')) return;
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    openSession(row.dataset.sessionId).catch(showError);
                  }
                });
              });
              document.querySelectorAll('[data-delete-session-id]').forEach(button => {
                button.addEventListener('click', event => {
                  event.stopPropagation();
                  deleteHistorySession(button.dataset.deleteSessionId).catch(showError);
                });
              });
            }

            function renderSessionsList() {
              const activeId = state.session?.sessionId;
              document.querySelectorAll('.session-row').forEach(row => {
                row.classList.toggle('is-active', row.dataset.sessionId === activeId);
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
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = false;
              draftItems.hidden = true;
              inspectorFooter.hidden = true;
              clearSelectionButton.hidden = true;
              cancelAddFlowButton.hidden = true;
              addItemButton.hidden = true;
              clearDraftButton.hidden = true;
              renderPendingItems();
            }

            function renderSavedAnnotationsInspector() {
              const groups = savedEvidenceGroups();
              inspectorTitle.textContent = 'Annotations';
              inspectorCount.textContent = String(groups.reduce((sum, group) => sum + group.items.length, 0));
              selectionSummary.hidden = true;
              comment.hidden = true;
              pendingItems.hidden = true;
              draftItems.hidden = false;
              inspectorFooter.hidden = false;
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
                renderSavedAnnotationsInspector();
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
              applyPreviewZoom();
              return document.getElementById('snapshotFrame');
            }

            function renderPreviewRegion() {
              const screen = latestScreen();
              const hasScreenshot = Boolean(screen?.screenshot?.desktopFullPath);
              const mode = addItemsFlow ? 'frozen' : (state.preview ? 'live' : (screen ? 'frozen' : 'idle'));
              if (!hasScreenshot) {
                snapshot.innerHTML = '<div class="empty-stage">' + (screen ? 'No screenshot artifact for this preview.' : 'Refresh the live preview to begin.') + '</div>';
                updateComposerState();
                return;
              }
              const frame = ensurePreviewFrame();
              frame.dataset.mode = mode;
              const image = document.getElementById('snapshotImage');
              const src = screenImageUrl(screen);
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
              stopLivePreviewPolling();
              resetAnnotationComposerState();
              invalidatePreviewContext();
              state.session = await requestJson('/api/session/open', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              await refresh();
            }

            async function newSession() {
              error.textContent = '';
              resetAnnotationComposerState();
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
              resetAnnotationComposerState();
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

            async function deleteHistorySession(sessionId) {
              error.textContent = '';
              if (!sessionId) return;
              const isActive = state.session?.sessionId === sessionId;
              if (isActive) {
                resetAnnotationComposerState();
                invalidatePreviewContext();
              }
              await requestJson('/api/session/close', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sessionId: sessionId })
              });
              if (isActive) {
                state.session = null;
              }
              await refreshSessions();
              if (isActive) {
                render();
                await refreshDevices();
              } else {
                renderSessionsList();
              }
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
                  captureAfter: false,
                  ...extras
                })
              });
              clearSelection();
              await refresh();
              await refreshPreview();
              if (navigation.captureError) {
                error.textContent = 'Navigation performed, but capture failed: ' + navigation.captureError;
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
                if (!addItemsFlow || toolMode !== 'annotate') return;
                try {
                  if (!dragStart) {
                    previewNodeAtPoint(event, image);
                    return;
                  }
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
            zoomOutButton.addEventListener('click', () => setPreviewZoom(previewZoom - PreviewZoomStep));
            zoomInButton.addEventListener('click', () => setPreviewZoom(previewZoom + PreviewZoomStep));
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
            comment.addEventListener('input', updateSelectedAnnotationComment);

            function showError(cause) {
              error.textContent = cause && cause.message ? cause.message : String(cause);
            }

            initializePreviewIntervalSelect();
            applyPreviewZoom();
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
