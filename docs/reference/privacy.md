# FixThis Privacy

FixThis V1 is a local-first debug tool for Android Jetpack Compose apps.

## Local-First Behavior

The MCP feedback console workflow uses a localhost Studio UI and local feedback session files. The Android app sidekick only provides debug runtime evidence and MCP browser connection status. FixThis does not upload annotations, screenshots, UI text, source hints, target evidence, or source candidates.

The core sidekick does not require Android network permission. MCP uses a desktop stdio process plus ADB and the app-local bridge; it does not make the Android app open an external network server.

The feedback console is served from localhost by the desktop MCP process. The Android app does not host the console and does not need network permissions.

The localhost console adds a per-server browser token to served HTML and requires `X-FixThis-Console-Token` on mutating `/api/*` requests. Mutating console requests with a non-localhost `Origin` are rejected. Read-only console pages and `GET` APIs remain available from localhost without that token.

Feedback workspace files are local project artifacts under `.fixthis/feedback-sessions/`. They include feedback session metadata, saved evidence screenshots, source hints, optional target evidence, comments, handoff batches, and append-only event logs used to resume the console after MCP or console restarts. Live preview frames are transient and are not appended to the feedback session history.

`Save to MCP` stores a local handoff batch in the feedback session so MCP tools can read it. It does not upload feedback or screenshots and does not call an external AI API.

Unsaved pending annotations are mirrored in browser `localStorage` as
DraftWorkspace envelopes under
`fixthis.workspace.<sessionId>.<workspaceId>`, with a per-session index at
`fixthis.workspace.index.<sessionId>`. The mirror may contain comments, target
bounds, frozen screen metadata, the preview id, a local screenshot URL, a
frozen timestamp, lifecycle/revision state, and undo/redo history. Legacy
`fixthis.pending.<sessionId>` mirrors may still be read for migration. Browser
draft mirrors stay local until you recover, recapture, discard, or persist the
batch.

## Debug Scope

FixThis is for debug builds only. The sidekick checks that the app is debuggable before starting the bridge. Do not document or ship it as a production feedback feature.

FixThis does not use AccessibilityService and does not inspect other apps.

## Redaction Defaults

Editable text and password fields are redacted from semantics-derived data by default. Semantic metadata such as bounds, role, actions, labels, test tags, and nearby non-sensitive text may still be exported.

Target reliability warnings do not reveal redacted text. When sensitive text
reduces available evidence, FixThis reports `SENSITIVE_TEXT_REDACTED` rather
than the original value.

Redaction applies to structured semantics data. It does not guarantee that pixels in screenshots are redacted.

## Screenshot Limitations

Screenshots are captured from the app window. They may include names, email addresses, account data, messages, payment details, or other sensitive information visible on screen.

FixThis hides its own overlay hosts during capture when possible, but it does not perform automatic PII redaction on screenshot pixels. Review artifacts before sharing them outside the local development workflow.

Review screenshots before sharing exported Markdown or JSON, because those exports can reference local screenshot artifacts and include visible screen context.

## Cache Storage

Screenshots are stored in the debug app cache:

```text
context.cacheDir/fixthis/<yyyy-MM-dd>/
```

CLI/MCP flows may copy screenshot artifacts into the project:

```text
.fixthis/artifacts/<annotation-id>/
.fixthis/feedback-sessions/<session-id>/
.fixthis/feedback-sessions/<session-id>/events/
.fixthis/preview-cache/<session-id>/
```

These files are local artifacts and are ignored by git. Treat them like screenshots from a debug device and delete them when no longer needed.

Use `fixthis clean --project-dir <path>` to remove only the known local artifact directories listed above plus `.fixthis/smoke-reports/`. The command does not remove `.fixthis/project.json` or unknown `.fixthis` files and directories. Add `--dry-run` to inspect what would be removed, or `--older-than-days <n>` to remove only artifact directories older than the cutoff by last-modified time.

## MCP And ADB Bridge

`fixthis mcp` runs on the desktop as a stdio JSON-RPC server. The MCP server talks to the Android sidekick through ADB port forwarding to a `localabstract:fixthis_<packageName>` socket.

The sidekick writes a session token under app-private storage. The desktop CLI reads it with `adb shell run-as <package> cat files/fixthis/session.json`, and every bridge request includes the token. A mismatched token is rejected by the bridge.

Feedback navigation actions are debug-only touch or key events dispatched inside the app process through the existing sidekick bridge. They do not make the Android app open a network service.

Device selection is local FixThis console state. Clicking `Clear selection` in the console does not run `adb disconnect` and does not detach USB or Wi-Fi ADB; it clears FixThis's active device selection and owned bridge resources.

The browser console's recovery card uses local MCP-process HTTP endpoints to diagnose and recover the app connection. `GET /api/connection` checks ADB device state and the sidekick bridge. `POST /api/app/launch` starts the selected or only ready debug app through ADB when recovery requires opening the app. These endpoints are bound to localhost with the rest of the console and do not upload diagnostics, raw bridge errors, screenshots, or feedback.

This bridge is intended for a trusted local development machine with ADB access to a debug app. Do not use it as a remote service boundary.
