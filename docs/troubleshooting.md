# PointPatch Troubleshooting

Start with:

```bash
pointpatch doctor --package <applicationId>
```

If package metadata exists in `.pointpatch/project.json`, `--package` can be omitted.

## ADB_NOT_FOUND

Symptom: `pointpatch doctor` fails at `ADB found` or the CLI reports that it cannot run `adb`.

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

## RUN_AS_FAILED

Symptom: CLI cannot read `files/pointpatch/session.json` with `adb shell run-as`.

Common causes:

- The app is not a debug build.
- The package name is wrong.
- The app has not been launched since PointPatch was added.
- The sidekick did not start.

Fix: install and launch the debug app, then rerun `pointpatch doctor --package <applicationId>`.

## SIDEKICK_SESSION_NOT_FOUND

Symptom: the CLI cannot find the sidekick session file.

Fix:

- Confirm the app includes the PointPatch debug dependency or Gradle plugin output.
- Launch the app once so AndroidX Startup can initialize the sidekick.
- Confirm the process is debuggable.
- Rerun `pointpatch status` or `pointpatch doctor`.

## No Compose Roots

Symptom: `pointpatch status` reports `roots: 0`, inspection returns no Compose nodes, or inspection returns a root-discovery/semantics error such as `ROOT_DISCOVERY_FAILED` or `SEMANTICS_*`.

Common causes:

- The current screen is not Jetpack Compose.
- The Activity has not called `setContent` yet.
- The app is displaying a platform view, WebView, or XML/View screen.
- The screen is between Activity transitions.

Fix: navigate to a Compose screen and retry. PointPatch V1 is Android Jetpack Compose only. Empty roots are reported as `rootsCount=0`; V1 does not emit a separate no-roots error code for that case.

## Screenshot Failures

PointPatch records screenshot failures in `screenshot.captureFailedReason` and still exports the annotation.

Common causes:

- DecorView has no size yet.
- PixelCopy timed out.
- Canvas fallback failed.
- Cache directory creation or PNG encoding failed.

Retry after the screen is fully rendered. For CLI/MCP, confirm the Android screenshot path exists under `context.cacheDir/pointpatch/` and that the bridge can read the current annotation screenshot.

## MCP stdout Log Corruption

Symptom: an MCP client fails to parse JSON-RPC messages, often after seeing human-readable logs mixed into stdout.

Rule: `pointpatch mcp` writes JSON-RPC protocol messages to stdout only. Diagnostics and logs must go to stderr.

Fix:

- Do not wrap `pointpatch mcp` in a script that prints banners or logs to stdout.
- Send wrapper logs to stderr.
- Use the setup JSON from `pointpatch setup`; it passes command and args separately.

## Bridge Connection Failures

Bridge failures usually mean the desktop CLI could not connect through ADB to the sidekick local socket.

Check:

- One device is connected and authorized.
- The debug app is installed and running.
- `run-as <package>` works.
- The sidekick session exists.
- The bridge protocol version matches the CLI/MCP version.

Retry with `pointpatch doctor --package <applicationId>` to see the failing stage.
