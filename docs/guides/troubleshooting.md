# FixThis Troubleshooting

Start with:

```bash
fixthis doctor --package <applicationId>
```

If package metadata exists in `.fixthis/project.json`, `--package` can be omitted.

For this repository's sample app, use the Android Studio `app` configuration or Gradle project `:app`; the source files live under `sample/`:

```bash
./gradlew :app:installDebug
fixthis run --package io.github.beyondwin.fixthis.sample
```

For repeatable host and connected-device diagnostics, run the smoke harness:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample
```

For host-only validation without an attached device, run:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --host-only
```

Reports are written under ignored `.fixthis/smoke-reports/` as Markdown and JSON. Connected smoke can finish with an explicit skip category: `SKIPPED_HOST_ONLY`, `SKIPPED_ADB_NOT_FOUND`, `SKIPPED_NO_DEVICE`, `SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`, `SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`, or `SKIPPED_MULTIPLE_DEVICES`.

To inspect or remove local FixThis artifacts without touching project metadata, run:

```bash
fixthis clean --project-dir <projectRoot> --dry-run
fixthis clean --project-dir <projectRoot>
```

`fixthis clean` only targets `.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/`. It preserves `.fixthis/project.json` and unknown `.fixthis` files or directories. Use `--older-than-days <n>` to clean only artifact directories older than the cutoff.

## First-Run Readiness States

FixThis first-run surfaces use one readiness vocabulary across `doctor --json`,
`.fixthis/agent-setup.json`, and the feedback console.

| State | What it means | First action |
| --- | --- | --- |
| `READY` | Debug app and sidekick are connected. | Capture screen. |
| `NEEDS_INSTALL` | FixThis metadata or setup is missing. | Run `fixthis install-agent --project-dir . --target all`. |
| `NEEDS_APP_LAUNCH` | Device is available but the app bridge is not reachable. | Open the debug app. |
| `DEVICE_BLOCKED` | Device or app is connected but not interactable. | Resolve the specific device overlay. |
| `UNSUPPORTED_BUILD` | Release/non-debug build, missing sidekick, or `run-as` denied. | Install a debuggable build with FixThis enabled. |
| `CONFIG_RECOVERABLE` | MCP or project config can be regenerated. | Run the setup command with `--dry-run`, then rerun without it. |
| `ENV_BLOCKER` | ADB, SDK, device, JDK, Node, or repo root is missing. | Fix the prerequisite and rerun doctor. |
| `STALE_PREVIEW` | Frozen preview no longer matches the live app screen. | Recapture, force-save, or cancel. |
| `SESSION_MISMATCH` | Response or artifact belongs to another feedback session. | Refresh the session or return to the matching history item. |
| `UNKNOWN_ERROR` | FixThis could not classify the failure. | Open details and run doctor with JSON output. |

## ADB_NOT_FOUND

Symptom: `fixthis doctor` fails at `ADB found` or the CLI reports that it cannot run `adb`.

Fix:

- Set `ANDROID_HOME` to your Android SDK.
- Ensure `$ANDROID_HOME/platform-tools/adb` exists.
- Add `platform-tools` to `PATH` if `ANDROID_HOME` is not set.

## MULTIPLE_DEVICES

The feedback console can list multiple ADB devices and lets you select the active device. CLI commands that do not carry console selection may still need one usable `adb devices` target or an explicit package/device context.

Check:

```bash
adb devices
```

### NO_DEVICE

Connect a device or start an emulator, then run `adb devices`. In the feedback console, use the compact device control's refresh action and select a connected, authorized device.

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

### Connected test says no Compose hierarchies found

Jetpack Compose test APIs need the app window to be foregrounded and inspectable. If `adb devices` shows a physical device in `device` state but the device is still on a secure lockscreen, system Bouncer, or notification shade, instrumentation can launch the app and still fail with `No compose hierarchies found in the app`.

Fix: unlock the device manually or use an unlocked emulator, then rerun the connected test. ADB wake or dismiss-keyguard commands are not enough for secure lockscreen credentials.

## Screenshot Failures

FixThis records screenshot failures in `screenshot.captureFailedReason` and still exports the annotation.

Common causes:

- DecorView has no size yet.
- PixelCopy timed out.
- Canvas fallback failed.
- Cache directory creation or PNG encoding failed.

Retry after the screen is fully rendered. For CLI/MCP, confirm the Android screenshot path exists under `context.cacheDir/fixthis/` and that the bridge can read the current annotation screenshot.

### SCREEN_CAPTURE_FAILED

The console may still show semantics without a screenshot. Click `Capture screen` after the app finishes drawing. If you are adding feedback, wait until the frozen preview has the screenshot you want before clicking `Copy Prompt` or `Save to MCP`; those actions persist written pending annotations when needed.

### Capture screen or Annotate does not work

Select a device in the compact console device control. If the control shows `No device`, refresh devices with `↻`. If it shows `Unavailable`, fix unauthorized or offline state in `adb devices -l` first.

### I clicked Annotate but do not see saved feedback

`Annotate` freezes the latest preview for targeting only; it does not save. Select a target or visual area to create a numbered pending marker, write a comment in the focused detail editor, then click `Copy Prompt` or `Save to MCP` to persist written pending annotations when needed. Persisted items from the same frozen preview share one evidence snapshot and `screenId`.

### Pending marker numbers changed

Deleting a pending item renumbers the pending list and overlay markers so they keep matching. This is expected before `Copy Prompt` or `Save to MCP` persists pending annotations.

### Reopened console shows a pending recovery banner

The browser found unsaved pending annotations in a DraftWorkspace recovery
mirror under `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`.
Choose:

- **Recover** to restore the frozen preview, screenshot, and comments when the
  saved preview context is complete.
- **Recapture** to start from a fresh preview before saving.
- **Discard** to remove the browser-local mirror.

FixThis v0.4 does not migrate pre-v0.4 browser pending mirrors. If stale local
recovery appears after upgrading, clear browser storage for the FixThis console
origin and run `fixthis clean --project-dir .` to remove old local artifacts.

### Save warns that the screen changed

FixThis compared the frozen preview fingerprint with a lightweight current
capture and received `screen_fingerprint_mismatch`. This usually means the app
rotated, changed window mode, showed a system UI surface, or navigated away
after you clicked **Annotate**.

Pick **re-capture** when you want the saved evidence to match the current
screen. Pick **force-save** only when you intentionally want to keep the frozen
preview even though the live screen fingerprint differs. Pick **cancel** to
return to the pending annotations without writing.

### I sent feedback but want to add more

After `Save to MCP`, the saved items are recorded in a local handoff batch for MCP tools. It is not an external AI API call. The session stays visible in the main History list with a `working` pip while the agent claims and resolves items. Click `Annotate` again to freeze the current visible screen and create another saved evidence snapshot when pending annotations are persisted, even if the app has not visibly changed.

## MCP stdout Log Corruption

Symptom: an MCP client fails to parse JSON-RPC messages, often after seeing human-readable logs mixed into stdout.

Rule: `fixthis mcp` writes JSON-RPC protocol messages to stdout only. Diagnostics and logs must go to stderr.

Fix:

- Do not wrap `fixthis mcp` in a script that prints banners or logs to stdout.
- Send wrapper logs to stderr.
- Use the setup JSON from `fixthis setup`; it passes command and args separately.

## Setup Write Warnings

Symptom: `fixthis setup --write` completes but prints a warning about Android SDK detection or the MCP executable.

Fix:

- Run `fixthis doctor --package <applicationId> --project-dir <projectRoot>` to confirm ADB, package discovery, and bridge readiness.
- If the warning says Android SDK was not found, set `ANDROID_HOME` or `ANDROID_SDK_ROOT`, or install the Android SDK in the platform default location.
- If the warning says `fixthis-mcp` was not found, run `./gradlew :fixthis-mcp:installDist` or make sure `fixthis` is available on PATH before restarting the MCP client.
- Use `fixthis setup --package <applicationId> --project-dir <projectRoot> --write --target codex --dry-run` to inspect the rendered config without modifying files.

### MCP_SESSION_CLOSED

Reopen the feedback console from the agent or run `fixthis console --package <applicationId>`.

### Console API returns 403

The browser console uses a per-server token for mutating localhost `/api/*` requests and rejects mutating requests with a non-localhost `Origin`. Reload the console page if the MCP process restarted, because the token changes per server instance. Direct scripts that call mutating console APIs must read the current token from the served console page and send it as `X-FixThis-Console-Token`.

### MCP status stays waiting

The in-app status pill changes to `MCP connected` only after an authorized MCP browser heartbeat, not after every bridge status or screen-capture request. Open the feedback console and keep the browser tab active long enough for heartbeat polling to run.

## Browser Console Says Reconnect

`Reconnect` means the console previously reached the FixThis sidekick bridge, but a later heartbeat, preview, or navigation request failed. Common causes are app restart, app reinstall, process death, device sleep, wireless debugging interruption, or the app being backgrounded.

Click `Reconnect`. The console will try to open the app and refresh the bridge session. Draft annotations and the last preview are kept while reconnecting.

Open `Details` for raw diagnostics such as `Bridge closed before sending a response`.

### Connection paused while work is in progress

If Studio says `Connection paused - draft preserved`, the bridge or foreground
app state changed while local feedback work still exists. FixThis keeps the
draft, sent handoff batches, claim state, resolve state, and persisted evidence
intact.

Safe automatic recovery includes heartbeat retry, reconnect, preview refresh,
and polling resume. Durable mutations still require a current context or an
explicit confirmation: Save to MCP from a stale preview, dirty-draft session
switch, stale force-save, claim, and resolve.

If a closed session blocks work with
`Reopen the session or create a new active session before changing feedback`,
open an active session from the history list or create a new session before
adding, claiming, resolving, or handing off feedback.

### I reopened the console and do not see my previous feedback

Run `fixthis_list_feedback_sessions` or reopen the console with the exact `sessionId`. If the session was closed, pass `includeClosed` when listing sessions. Verify `.fixthis/feedback-sessions/` exists under the same project root used by the MCP server.

### Live preview stopped updating

Preview polling pauses when the browser tab is hidden and while the `Annotate` frozen-preview flow is active. Switch back to the tab, click `Copy Prompt` or `Save to MCP` to persist pending annotations, exit annotate mode, or use `Capture screen`. The preview interval options are Manual, 1s, 2s, and 5s, with 1s as the default.

### Navigation worked but no new screen appeared

The navigation action can succeed while follow-up capture fails. Check the `captureError` field or click `Capture screen` manually after the app finishes drawing.

## Connected But Interaction Is Blocked

The compact device chip can show `Connected` while still suppressing canvas selection and capture-driven actions. In that case the canvas renders a per-cause overlay and FixThis auto-resumes the prior tool mode, frozen preview, and pending pins when the cause clears. Causes are reported by the sidekick via the `BridgeStatus` availability fields (`screenInteractive`, `keyguardLocked`, `appForeground`, `pictureInPicture`) and resolved by priority on the desktop.

| Sub-state | Cause | Fix |
| --- | --- | --- |
| Screen off | Device display is off (`screenInteractive=false`) | Wake the device. |
| Lock screen | `keyguardLocked=true` over a foregrounded app | Unlock the device manually. ADB wake/dismiss is not enough on secure lockscreens. |
| App backgrounded | `appForeground=false` | Bring the debug app back to foreground (relaunch, recents, or `Open app`). |
| Picture-in-Picture | `pictureInPicture=true` | Restore the app to fullscreen. |
| Sample app unresponsive | Heartbeat or status calls timing out | Wait for the app to recover, or relaunch via `Reconnect`. |
| No Compose UI | Foreground screen is not Compose (View/WebView/dialog) | Navigate to a Compose screen. See [No Compose Roots](#no-compose-roots). |

Browser-side draft work and the last preview remain visible while blocked. Selection and `Capture screen` resume automatically once the cause clears; you do not need to re-enter `Annotate` or re-add pending pins.

## Browser Console Connection Card States

The connection card is the first place to look when capture, preview, or navigation is unavailable.

- `Connect to your app`: click `Start`. The console will use the selected or only ready device, launch the debug app when possible, and check the sidekick bridge.
- `Ready`: live capture and debug navigation are allowed. Click `Capture screen` when you want a fresh preview immediately.
- `Open the app`: ADB sees a usable device, but the sidekick bridge is not reachable. Click `Open app` to launch the active package.
- `Reconnect`: the console reached the bridge before, but a later heartbeat, preview, or navigation request failed. Click `Reconnect` to reopen the app and refresh the bridge session.
- `Choose a device`: more than one ready device is connected. Pick one from the compact device control.
- `Check your phone`: no usable device is available, ADB failed, or the selected device is offline/unauthorized/missing. Check `adb devices -l`, unlock/authorize the device, then click `Try again`.
- `This build cannot connect`: the package is not debuggable, `run-as` is denied, the sidekick is missing, or the build cannot expose the FixThis bridge. Install a debuggable build with the sidekick enabled.

Open `Details` for raw `deviceState`, `bridgeState`, and `rawError`. These details are diagnostic; the normal action button is the supported recovery path.

## Console Notifications

The console uses different surfaces for different recovery work:

- Toasts are for success, cancel, and undo messages that can disappear.
- Inline status belongs to the connection card, preview, inspector, or save controls.
- Global banners stay visible when the whole workflow is blocked.
- In-app sheets handle destructive choices such as discarding drafts, clearing saved drafts, forgetting a device, or force-saving a stale preview.
- Details panels hold raw errors, JSON, and diagnostic commands.

If a mutating request returns `403`, reload the console page served by the
current MCP process. The console token changes when the server restarts.

## "Source coordinates point to old code"

Symptom: After editing app code, `fixthis_read_feedback` returns paths/lines that don't match what you see in the editor.

Cause: The debug APK on the device was built before your changes. The on-device `fixthis-source-index.json` still references old line numbers.

Fix:

```bash
./gradlew :app:installDebug
```

Cold-launch the app once so the sidekick re-reads the new index. `fixthis_status` will then report `installStale: false`. Per-candidate `stale: true` flags also clear.

If `fixthis_status` reports `installStaleReason: "projectRoot may be misconfigured: 0 of <N> indexed files exist on host"`, FixThis could not resolve any indexed source file against the MCP project root. Re-run from the repository root first. If you are using an older debug APK, reinstall so the source index includes `sourceRoot` and `repoFile` metadata for module paths such as `sample/src/main/...`. If the warning remains after reinstall, check that the MCP `projectRoot` points at the repository containing the app sources.

Per-candidate `staleReason` values are more specific for host path problems: `file not found on host; sourceRoot unresolved` means root metadata was present but did not resolve to a file, and `file not found on host; multiple suffix matches` means the compatibility fallback found more than one possible file.

To verify the round-trip end-to-end on a connected device, run `./scripts/fixthis-smoke.sh --check-staleness`. The smoke script asserts `installStale: false` after install, bumps a tracked source file's mtime to assert `installStale: true` (with `newerSourceFiles` referencing the touched file), then reinstalls and asserts `installStale: false` again. The original mtime is restored before the reinstall step and again via an exit `trap` so failures do not leave the workspace dirty.

## Bridge Connection Failures

Bridge failures usually mean the desktop CLI could not connect through ADB to the sidekick local socket.

Check:

- One device is connected and authorized.
- The debug app is installed and running.
- `run-as <package>` works.
- The sidekick session exists.
- The bridge protocol version matches the CLI/MCP version.

Retry with `fixthis doctor --package <applicationId>` to see the failing stage.

## Console Staleness Banner

The browser console shows a red banner at the top when it detects that the JS, the running MCP/console JAR, or the connected sample APK is out of date. The same banner is reused for three causes:

- **Console JS is stale** — the bundled JS build epoch is older than `/api/server-version` reports for the running server. Re-run `bash scripts/restart-console.sh` (or `node scripts/build-console-assets.mjs && ./gradlew :fixthis-mcp:installDist` and restart) and hard-reload the browser tab.
- **Bridge protocol mismatch** — the sample APK’s sidekick reports a `bridgeProtocolVersion` older than the console’s `MinimumSupportedProtocolVersion`. Rebuild and reinstall the debug sample APK (e.g. `bash scripts/restart-console.sh --with-app`).
- **Sidekick build is older than the console** — the sample APK was built before the running console JAR was built. Reinstall the debug APK as above so its sidekick matches.

The banner is dismissable; it does not block usage, but recovery features that depend on the new protocol fields will degrade until you refresh the stale side. See [Bridge protocol](../reference/bridge-protocol.md) for the contract that governs when `BridgeProtocol.VERSION` and the console's minimum must change in lockstep.

## Setup failures

`fixthis setup --write` fails when an existing agent config file cannot be parsed. The error message classifies the cause and recommends an action.

| Category | Meaning | Recommended action |
|---|---|---|
| `MALFORMED_JSON` | `.claude/settings.json` is not valid JSON. | Fix the JSON syntax shown in the `caused by` line, or back up + delete the file and re-run. |
| `MALFORMED_MCPSERVERS_SHAPE` | The `mcpServers` key exists but is not a JSON object. | Replace its value with `{}` or remove the key; re-run. |
| `MALFORMED_TOML` | `~/.codex/config.toml` has a TOML syntax error in the `[mcp_servers.fixthis]` section. | Back up the file, remove the `[mcp_servers.fixthis]` block, and re-run. |
| `FILESYSTEM_ERROR` | The config file cannot be read (permissions, missing directory). | Check `ls -l <path>`; fix ownership or permissions with `chmod`/`chown`. |
| `UNKNOWN` | Anything else. | Re-run with `--verbose` for a stack trace and file an issue. |

For maximum detail, append `--verbose` to any failing `fixthis setup` invocation. The same categorized message is printed, followed by a full Java stack trace including nested causes.
