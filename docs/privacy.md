# PointPatch Privacy

PointPatch V1 is a local-first debug tool for Android Jetpack Compose apps.

## Local-First Behavior

The default workflow is in-app selection followed by local clipboard or file export. PointPatch does not upload annotations, screenshots, UI text, source hints, or source candidates.

The core sidekick does not require Android network permission. MCP uses a desktop stdio process plus ADB and the app-local bridge; it does not make the Android app open an external network server.

The feedback console is served from localhost by the desktop MCP process. The Android app does not host the console and does not need network permissions. Console screenshots are local debug artifacts under `.pointpatch/artifacts/`.

Feedback workspace files are local project artifacts under `.pointpatch/feedback-sessions/`. They include feedback session metadata and session-owned screenshots used to resume the console after MCP or console restarts.

## Debug Scope

PointPatch is for debug builds only. The sidekick checks that the app is debuggable before starting the bridge. Do not document or ship it as a production feedback feature.

PointPatch does not use AccessibilityService and does not inspect other apps.

## Redaction Defaults

Editable text and password fields are redacted from semantics-derived data by default. Semantic metadata such as bounds, role, actions, labels, test tags, and nearby non-sensitive text may still be exported.

Redaction applies to structured semantics data. It does not guarantee that pixels in screenshots are redacted.

## Screenshot Limitations

Screenshots are captured from the app window. They may include names, email addresses, account data, messages, payment details, or other sensitive information visible on screen.

PointPatch hides its own overlay hosts during capture when possible, but it does not perform automatic PII redaction on screenshot pixels. Review artifacts before sharing them outside the local development workflow.

Review screenshots before sharing exported Markdown or JSON, because those exports can reference local screenshot artifacts and include visible screen context.

## Cache Storage

Screenshots are stored in the debug app cache:

```text
context.cacheDir/pointpatch/<yyyy-MM-dd>/
```

CLI/MCP flows may copy screenshot artifacts into the project:

```text
.pointpatch/artifacts/<annotation-id>/
.pointpatch/feedback-sessions/<session-id>/
```

These files are local artifacts and are ignored by git. Treat them like screenshots from a debug device and delete them when no longer needed.

## MCP And ADB Bridge

`pointpatch mcp` runs on the desktop as a stdio JSON-RPC server. The MCP server talks to the Android sidekick through ADB port forwarding to a `localabstract:pointpatch_<packageName>` socket.

The sidekick writes a session token under app-private storage. The desktop CLI reads it with `adb shell run-as <package> cat files/pointpatch/session.json`, and every bridge request includes the token. A mismatched token is rejected by the bridge.

Feedback navigation actions are debug-only touch or key events dispatched inside the app process through the existing sidekick bridge. They do not make the Android app open a network service.

This bridge is intended for a trusted local development machine with ADB access to a debug app. Do not use it as a remote service boundary.
