# FixThis Privacy

FixThis V1 is a local-first debug tool for Android Jetpack Compose apps.

## Local-First Behavior

The in-app annotation workflow uses local selection followed by clipboard or file export. The MCP feedback console workflow uses a localhost Studio UI and local feedback session files. FixThis does not upload annotations, screenshots, UI text, source hints, or source candidates.

The core sidekick does not require Android network permission. MCP uses a desktop stdio process plus ADB and the app-local bridge; it does not make the Android app open an external network server.

The feedback console is served from localhost by the desktop MCP process. The Android app does not host the console and does not need network permissions.

Feedback workspace files are local project artifacts under `.fixthis/feedback-sessions/`. They include feedback session metadata, saved evidence screenshots, source hints, comments, and handoff batches used to resume the console after MCP or console restarts. Live preview frames are transient and are not appended to the feedback session history.

Send stores a local handoff batch in the feedback session so MCP tools can read it. It does not upload feedback or screenshots and does not call an external AI API.

## Debug Scope

FixThis is for debug builds only. The sidekick checks that the app is debuggable before starting the bridge. Do not document or ship it as a production feedback feature.

FixThis does not use AccessibilityService and does not inspect other apps.

## Redaction Defaults

Editable text and password fields are redacted from semantics-derived data by default. Semantic metadata such as bounds, role, actions, labels, test tags, and nearby non-sensitive text may still be exported.

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
```

These files are local artifacts and are ignored by git. Treat them like screenshots from a debug device and delete them when no longer needed.

## MCP And ADB Bridge

`fixthis mcp` runs on the desktop as a stdio JSON-RPC server. The MCP server talks to the Android sidekick through ADB port forwarding to a `localabstract:fixthis_<packageName>` socket.

The sidekick writes a session token under app-private storage. The desktop CLI reads it with `adb shell run-as <package> cat files/fixthis/session.json`, and every bridge request includes the token. A mismatched token is rejected by the bridge.

Feedback navigation actions are debug-only touch or key events dispatched inside the app process through the existing sidekick bridge. They do not make the Android app open a network service.

Device selection is local FixThis console state. Clicking `Clear selection` in the console does not run `adb disconnect` and does not detach USB or Wi-Fi ADB; it clears FixThis's active device selection and owned bridge resources.

This bridge is intended for a trusted local development machine with ADB access to a debug app. Do not use it as a remote service boundary.
