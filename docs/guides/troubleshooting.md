# FixThis Troubleshooting

Start here:

```bash
fixthis doctor --package <applicationId>
```

If `.fixthis/project.json` exists, `--package` can be omitted.

Sample app:

```bash
./gradlew :app:installDebug
fixthis run --package io.github.beyondwin.fixthis.sample
```

Repeatable diagnostics:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --host-only
```

Reports land under ignored `.fixthis/smoke-reports/`. Connected smoke can
finish with `SKIPPED_HOST_ONLY`, `SKIPPED_ADB_NOT_FOUND`, `SKIPPED_NO_DEVICE`,
`SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`,
`SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`, or
`SKIPPED_MULTIPLE_DEVICES`.

Clean local artifacts without touching project metadata:

```bash
fixthis clean --project-dir <projectRoot> --dry-run
fixthis clean --project-dir <projectRoot>
```

`fixthis clean` removes `feedback-sessions/`, `preview-cache/`, `artifacts/`,
and `smoke-reports/`. It keeps `.fixthis/project.json` and unknown entries.
`--older-than-days <n>` limits the cleanup.

## First-Run Readiness States

Same vocabulary across `doctor --json`, `.fixthis/agent-setup.json`, and the
console.

| State | Meaning | First action |
| --- | --- | --- |
| `READY` | Debug app and sidekick connected | Capture screen |
| `NEEDS_INSTALL` | Metadata or setup missing | `fixthis install-agent --project-dir . --target all` |
| `NEEDS_APP_LAUNCH` | Device available, bridge not reachable | Open the debug app |
| `DEVICE_BLOCKED` | Connected but not interactable | Clear the overlay cause |
| `UNSUPPORTED_BUILD` | Release build, missing sidekick, or `run-as` denied | Install a debuggable build with FixThis |
| `CONFIG_RECOVERABLE` | MCP or project config can be regenerated | Setup `--dry-run`, then without it |
| `ENV_BLOCKER` | ADB, SDK, device, JDK, Node, or repo root missing | Fix the prerequisite |
| `STALE_PREVIEW` | Frozen preview no longer matches the live screen | Recapture, force-save, or cancel |
| `SESSION_MISMATCH` | Response belongs to another session | Refresh or return to the matching item |
| `UNKNOWN_ERROR` | Unclassified | Open details and rerun doctor `--json` |

## ADB_NOT_FOUND

`fixthis doctor` cannot run `adb`. Set `ANDROID_HOME`, confirm
`$ANDROID_HOME/platform-tools/adb`, and add `platform-tools` to `PATH`.

## MULTIPLE_DEVICES

The console can pick a device. CLI commands without that selection still need
one usable `adb devices` target.

```bash
adb devices
```

### NO_DEVICE

Connect a device or start an emulator. In the console, refresh devices and
pick a connected, authorized one.

## RUN_AS_FAILED

CLI cannot read `files/fixthis/session.json` with `adb shell run-as`. Common
causes: not a debug build, wrong package, app never launched, sidekick did
not start.

Install and launch the debug app, then rerun `fixthis doctor --package <applicationId>`.

`run-as: unknown package` means that package is not on the device ADB is
talking to. Install the debug APK on the target and keep one `adb devices`
entry active for V1.

## SIDEKICK_SESSION_NOT_FOUND

Confirm the debug dependency or plugin output, launch the app once, confirm
the process is debuggable, then rerun `fixthis status` or `fixthis doctor`.

### SIDEKICK_UNREACHABLE

Install and launch a debuggable build with the sidekick, then retry
`fixthis status`.

## No Compose Roots

`fixthis status` reports `roots: 0`, or inspection returns
`ROOT_DISCOVERY_FAILED` / `SEMANTICS_*`.

Usual causes: not a Compose screen, `setContent` not called yet, platform
view / WebView / XML screen, Activity transition.

Navigate to a Compose screen and retry. V1 is Jetpack Compose only. Empty
roots are `rootsCount=0`.

### Connected test says no Compose hierarchies found

Compose test APIs need a foreground, inspectable window. A physical device
can report `device` in ADB while a secure lockscreen still blocks hierarchy
discovery.

Unlock the device by hand, or use an unlocked emulator. ADB wake /
dismiss-keyguard is not enough for secure credentials.

## Screenshot Failures

Failures are recorded in `screenshot.captureFailedReason`. The annotation
still exports.

Causes: DecorView has no size, PixelCopy timeout, Canvas fallback failed,
cache or PNG encoding failed. Retry after the screen finishes drawing.

### SCREEN_CAPTURE_FAILED

Semantics may still show. Click `Capture screen` after the app finishes
drawing. Do not Copy Prompt or Save to MCP until the frozen preview has the
screenshot you want.

### Capture screen or Annotate does not work

Select a device. If the control shows `No device`, refresh with `↻`. If
`Unavailable`, fix unauthorized or offline state in `adb devices -l`.

### I clicked Annotate but do not see saved feedback

`Annotate` freezes the preview. It does not save. Select a target, write a
comment, then Copy Prompt or Save to MCP. Items from the same freeze share
one evidence snapshot and `screenId`.

### Pending marker numbers changed

Deleting a pending item renumbers the list so overlay markers stay matched.
Expected until save.

### Reopened console shows a pending recovery banner

Unsaved pending annotations were mirrored in
`localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`.

- **Recover** restores the freeze when the saved preview is complete.
- **Recapture** starts from a fresh preview.
- **Discard** removes the browser-local mirror.

v0.4 does not migrate older pending mirrors. Clear console origin storage
and run `fixthis clean --project-dir .` if stale recovery appears after
upgrade.

### Save warns that the screen changed

Frozen preview fingerprint ≠ current capture (`screen_fingerprint_mismatch`).
Usually rotate, window-mode change, system UI, or navigation after Annotate.

- **re-capture** if you want saved evidence to match the current screen.
- **force-save** only if you want the frozen preview anyway.
- **cancel** to keep pending annotations unsaved.

### I sent feedback but want to add more

After Save to MCP the items are a local handoff batch, not an external API
call. The session stays in History with a `working` pip. Click Annotate
again to freeze the current screen.

## Runtime Diagnostics

New sessions use Auto on Save to MCP. Legacy sessions without a saved policy
use Manual. Manual captures only from saved annotation detail. Off skips
Studio collection. Copy Prompt never starts collection.

```bash
npm run runtime-evidence:smoke -- --strict
npm run android:proof -- --strict
```

The aggregate `Runtime evidence product path` row must be `pass`. Deferred
is not connected proof.

### `device_changed`

Kept in the additive schema. The coordinator reports a selected-device serial
change as `context_changed` and refuses linkage. Keep the intended device
selected, confirm `device` in `adb devices -l`, reopen the session, retry.

### `permission_denied`

Authorize the host, confirm a debug build, and check
`fixthis doctor --package <applicationId>`. If policy still denies the
fixed ADB collector, switch to Manual or Off. Do not broaden the collector.

### `capture_timeout`

The 2,500 ms budget expired. Keep the debug app foregrounded, close other
ADB work, retry a focused preset. Auto Save to MCP still sends otherwise
valid feedback with a failed/partial record.

### `quota_exceeded`

`.fixthis/runtime-evidence/` would exceed 250 MiB. Stop MCP, archive only
unneeded capture directories, restart, retry. `fixthis clean` does not
remove runtime-evidence bundles.

```bash
mkdir -p ".fixthis/runtime-evidence-archive/<session-id>"
mv ".fixthis/runtime-evidence/<session-id>/<capture-id>" \
  ".fixthis/runtime-evidence-archive/<session-id>/"
```

### `artifact_missing`

The session still references a capture, but the file is gone, not a regular
file, or crosses a path boundary. Retry collection. Inspect permissions and
symlinks under `.fixthis/runtime-evidence/`.

### `context_changed` or `process_restarted`

Device / install / package / session / item / screen drift fails linkage.
Reopen the exact session and screen, then recapture.

## MCP stdout Log Corruption

MCP clients fail to parse JSON-RPC when human logs leak into stdout.
`fixthis mcp` writes protocol to stdout only. Diagnostics go to stderr.

Do not wrap `fixthis mcp` in a script that prints to stdout. Use the setup
JSON from `fixthis setup` so command and args stay separate.

## Setup Write Warnings

`fixthis setup --write` completed but warned about Android SDK or
`fixthis-mcp`.

- Confirm ADB, package, and bridge with `fixthis doctor --package <applicationId> --project-dir <projectRoot>`.
- Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` if SDK was not found.
- Run `./gradlew :fixthis-mcp:installDist` if `fixthis-mcp` was not found.
- Inspect with `--dry-run` before writing.

### MCP_SESSION_CLOSED

Reopen the console from the agent or run `fixthis console --package <applicationId>`.

### Console API returns 403

Mutating `/api/*` needs the current `X-FixThis-Console-Token` and a localhost
Origin. Reload if the MCP process restarted. The token changes per instance.

### MCP status stays waiting

The in-app pill becomes `MCP connected` after an authorized MCP browser
heartbeat, not after every bridge call. Keep the console tab active.

## Browser Console Says Reconnect

The console reached the bridge before, then a later heartbeat, preview, or
navigation failed. Typical causes: app restart, reinstall, process death,
sleep, wireless ADB drop, app backgrounded.

Click `Reconnect`. Drafts and the last preview are kept.

### Connection paused while work is in progress

`Connection paused - draft preserved` means the bridge or foreground state
changed while local work still exists. Heartbeat retry, reconnect, preview
refresh, and polling resume are safe. Save from a stale preview, dirty-draft
session switch, stale force-save, claim, and resolve still need a current
context or explicit confirmation.

If a closed session blocks work with
`Reopen the session or create a new active session before changing feedback`,
open an active session from the history list or create a new session.

### I reopened the console and do not see my previous feedback

Call `fixthis_list_feedback_sessions` or reopen with the exact `sessionId`.
Closed sessions need `includeClosed`. Confirm `.fixthis/feedback-sessions/`
is under the same project root the MCP server uses.

### Live preview stopped updating

Live preview is push-first over `/api/events`. Polling is fallback while the
stream is down. It pauses when the tab is hidden or Annotate is active.
Switch back to the tab, persist pending annotations, exit annotate, or
Capture screen. Intervals: Manual, 1s, 2s, 5s (default 1s).

### Navigation worked but no new screen appeared

The navigation action can succeed while follow-up capture fails. Check
`captureError` or Capture screen after the app finishes drawing.

## Connected But Interaction Is Blocked

The chip can show `Connected` while selection is suppressed. The canvas
shows a per-cause overlay and auto-resumes when the cause clears.

| Sub-state | Cause | Fix |
| --- | --- | --- |
| Screen off | `screenInteractive=false` | Wake the device |
| Lock screen | `keyguardLocked=true` | Unlock by hand |
| App backgrounded | `appForeground=false` | Foreground the debug app |
| Picture-in-Picture | `pictureInPicture=true` | Restore fullscreen |
| Sample app unresponsive | Heartbeat timeout | Wait or Reconnect |
| No Compose UI | View / WebView / dialog | Navigate to a Compose screen. See [No Compose Roots](#no-compose-roots). |

## Browser Console Connection Card States

- `Connect to your app`: click `Start`.
- `Ready`: live capture and debug navigation allowed.
- `Open the app`: device is usable, bridge is not. Click `Open app`.
- `Reconnect`: later request failed. Click `Reconnect`.
- `Choose a device`: more than one ready device.
- `Check your phone`: no usable device, ADB failed, or selected device is
  offline / unauthorized. Check `adb devices -l`.
- `This build cannot connect`: not debuggable, `run-as` denied, or sidekick
  missing.

Open `Details` for `deviceState`, `bridgeState`, and `rawError`.

## Console Notifications

- Toasts: success, cancel, undo.
- Inline status: connection card, preview, inspector, save controls.
- Global banners: whole-workflow blocks.
- Sheets: discard drafts, forget a device, force-save a stale preview.
- Details panels: raw errors and JSON.

A mutating `403` means reload the page served by the current MCP process.

## "Source coordinates point to old code"

The debug APK on the device is older than the editor. Reinstall:

```bash
./gradlew :app:installDebug
```

Cold-launch once. `fixthis_status` should report `installStale: false`.

If `installStaleReason` says `projectRoot may be misconfigured`, run from the
repository root. Reinstall older APKs so the index includes `sourceRoot` and
`repoFile`. Confirm MCP `projectRoot` points at the repo that contains the
app sources.

Round-trip check: `./scripts/fixthis-smoke.sh --check-staleness`.

## Bridge Connection Failures

Desktop CLI could not connect through ADB to the sidekick socket. Check: one
authorized device, debug app running, `run-as <package>` works, sidekick
session exists, bridge protocol matches CLI/MCP. Then
`fixthis doctor --package <applicationId>`.

## Console Staleness Banner

Red banner when JS, MCP/console JAR, or sample APK is out of date.

- **Console JS is stale** — `bash scripts/restart-console.sh` and hard-reload.
- **Bridge protocol mismatch** — rebuild and reinstall the sample APK
  (`bash scripts/restart-console.sh --with-app`).
- **Sidekick build is older than the console** — reinstall the debug APK.

Dismissable. Features that need new protocol fields degrade until you
refresh. See [Bridge protocol](../reference/bridge-protocol.md).

## Setup failures

`fixthis setup --write` fails when an existing agent config cannot be parsed.

| Category | Meaning | Action |
|---|---|---|
| `MALFORMED_JSON` | `.claude/settings.json` is not valid JSON | Fix the syntax, or back up and delete, then rerun |
| `MALFORMED_MCPSERVERS_SHAPE` | `mcpServers` is not an object | Replace with `{}` or remove the key |
| `MALFORMED_TOML` | Codex config TOML error in `[mcp_servers.fixthis]` | Back up, remove that block, rerun |
| `FILESYSTEM_ERROR` | Cannot read the file | Fix ownership or permissions |
| `UNKNOWN` | Anything else | `--verbose` and file an issue |

Append `--verbose` for a full stack trace.
