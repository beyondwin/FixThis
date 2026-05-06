# FixThis Troubleshooting

Start with:

```bash
fixthis doctor --package <applicationId>
```

If package metadata exists in `.fixthis/project.json`, `--package` can be omitted.

For this repository's sample app, use the Android Studio `app` configuration or Gradle project `:app`; the source files live under `sample/`:

```bash
./gradlew :app:installDebug
fixthis run --package io.beyondwin.fixthis.sample
```

## ADB_NOT_FOUND

Symptom: `fixthis doctor` fails at `ADB found` or the CLI reports that it cannot run `adb`.

Fix:

- Set `ANDROID_HOME` to your Android SDK.
- Ensure `$ANDROID_HOME/platform-tools/adb` exists.
- Add `platform-tools` to `PATH` if `ANDROID_HOME` is not set.

## MULTIPLE_DEVICES

V1 expects a single active `adb devices` target. If more than one emulator or device is connected, disconnect extra devices or make only one show as `device`.

Check:

```bash
adb devices
```

### NO_DEVICE

Connect a device or start an emulator, then run `adb devices`.

## RUN_AS_FAILED

Symptom: CLI cannot read `files/fixthis/session.json` with `adb shell run-as`.

Common causes:

- The app is not a debug build.
- The package name is wrong.
- The app has not been launched since FixThis was added.
- The sidekick did not start.

Fix: install and launch the debug app, then rerun `fixthis doctor --package <applicationId>`.

If the error says `run-as: unknown package`, ADB is talking to a device where that package is not installed. This often happens after switching devices or when both an emulator and a physical device are connected. Install the debug APK on the target device and make sure only one `adb devices` entry is active for V1.

## SIDEKICK_SESSION_NOT_FOUND

Symptom: the CLI cannot find the sidekick session file.

Fix:

- Confirm the app includes the FixThis debug dependency or Gradle plugin output.
- Launch the app once so AndroidX Startup can initialize the sidekick.
- Confirm the process is debuggable.
- Rerun `fixthis status` or `fixthis doctor`.

### SIDEKICK_UNREACHABLE

Install and launch a debuggable build with FixThis sidekick enabled, then retry `fixthis status`.

## No Compose Roots

Symptom: `fixthis status` reports `roots: 0`, inspection returns no Compose nodes, or inspection returns a root-discovery/semantics error such as `ROOT_DISCOVERY_FAILED` or `SEMANTICS_*`.

Common causes:

- The current screen is not Jetpack Compose.
- The Activity has not called `setContent` yet.
- The app is displaying a platform view, WebView, or XML/View screen.
- The screen is between Activity transitions.

Fix: navigate to a Compose screen and retry. FixThis V1 is Android Jetpack Compose only. Empty roots are reported as `rootsCount=0`; V1 does not emit a separate no-roots error code for that case.

## Screenshot Failures

FixThis records screenshot failures in `screenshot.captureFailedReason` and still exports the annotation.

Common causes:

- DecorView has no size yet.
- PixelCopy timed out.
- Canvas fallback failed.
- Cache directory creation or PNG encoding failed.

Retry after the screen is fully rendered. For CLI/MCP, confirm the Android screenshot path exists under `context.cacheDir/fixthis/` and that the bridge can read the current annotation screenshot.

### SCREEN_CAPTURE_FAILED

The console may still show semantics without a screenshot. Click Refresh after the app finishes drawing. If you are adding feedback, Save only after the frozen preview has the screenshot you want to persist as evidence.

### Refresh or Add does not work

Select a device in the compact console device control. If the control shows `No device`, refresh devices with `↻`. If it shows `Unavailable`, fix unauthorized or offline state in `adb devices -l` first.

### I clicked Add but do not see saved feedback

Add freezes the latest preview for targeting only; it does not save. Add one or more comments, review the numbered pending markers, then click Save once to create one persisted evidence snapshot and store all pending items on it.

### Pending marker numbers changed

Deleting a pending item renumbers the pending list and overlay markers so they keep matching. This is expected before Save.

### I sent feedback but want to add more

After Send, the saved items are recorded in a local handoff batch for MCP tools. It is not an external AI API call. Click Add again to freeze the current visible screen and create another saved evidence snapshot, even if the app has not visibly changed.

## MCP stdout Log Corruption

Symptom: an MCP client fails to parse JSON-RPC messages, often after seeing human-readable logs mixed into stdout.

Rule: `fixthis mcp` writes JSON-RPC protocol messages to stdout only. Diagnostics and logs must go to stderr.

Fix:

- Do not wrap `fixthis mcp` in a script that prints banners or logs to stdout.
- Send wrapper logs to stderr.
- Use the setup JSON from `fixthis setup`; it passes command and args separately.

### MCP_SESSION_CLOSED

Reopen the feedback console from the agent or run `fixthis console --package <applicationId>`.

### I reopened the console and do not see my previous feedback

Run `fixthis_list_feedback_sessions` or reopen the console with the exact `sessionId`. If the session was closed, pass `includeClosed` when listing sessions. Verify `.fixthis/feedback-sessions/` exists under the same project root used by the MCP server.

### Live preview stopped updating

Preview polling pauses when the browser tab is hidden and while the Add/frozen-preview flow is active. Switch back to the tab, Save or cancel the pending flow, or use Refresh. The preview interval options are Manual, 1s, 2s, and 5s, with 1s as the default.

### Navigation worked but no new screen appeared

The navigation action can succeed while follow-up capture fails. Check the `captureError` field or click Refresh manually after the app finishes drawing.

## Bridge Connection Failures

Bridge failures usually mean the desktop CLI could not connect through ADB to the sidekick local socket.

Check:

- One device is connected and authorized.
- The debug app is installed and running.
- `run-as <package>` works.
- The sidekick session exists.
- The bridge protocol version matches the CLI/MCP version.

Retry with `fixthis doctor --package <applicationId>` to see the failing stage.
