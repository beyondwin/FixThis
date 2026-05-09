# Device Interaction Availability UX — Design

**Date:** 2026-05-09
**Status:** Approved (pending user spec review)
**Owner:** kws
**Related plan:** `docs/superpowers/plans/2026-05-09-device-interaction-availability-ux.md`

---

## 1. Problem

Today the FixThis web console treats the connected device as either available or unavailable. Several real-world states fall in between — the bridge is alive but the user cannot meaningfully interact with the device:

- **Screen off** — annotation clicks emit a misleading `No component found at that point. Drag to select a custom area.` because the live frame is stale and node hit-testing fails. The Top bar still says **Connected**.
- **App backgrounded** (Home pressed, switched to another app) — same symptom; preview frame becomes meaningless yet the UI offers no resume path.
- **Resume after the device wakes up** — the console does not auto-resume Annotate mode or refresh the preview; the user has to manually re-enter, often losing the frozen frame and pending pins.
- **Adjacent edge cases** — keyguard / lock screen, Picture-in-Picture, an unresponsive sample app, and screens with no Compose UI all show similar symptoms or unhelpful errors.

The current UX is wrong in two ways:

1. **Silent acceptance** — users see misleading errors instead of a clear "device isn't ready" state.
2. **No auto-recovery** — bringing the device back doesn't bring the workflow back.

## 2. Goals

- Surface a single, clearly-named **interaction-blocked sub-state** of *Connected* that covers screen-off, app-backgrounded, lock screen, PiP, unresponsive app, and "no Compose UI on this screen".
- Block misleading interactions while blocked (no spurious annotation errors, no half-broken clicks).
- Auto-resume the user's last toolMode, frozen preview, and pending pins the moment the underlying cause clears.
- Keep behavior unchanged when running against an older sidekick that doesn't yet emit the new signals (graceful, non-breaking).

## 3. Non-goals

- Changing how the bridge socket itself reconnects.
- Detecting system dialogs / permission prompts overlaying the activity (no reliable signal).
- Doze / battery-saver inference beyond the existing unresponsive-timeout heuristic.
- Multi-display, multi-window, or external-display behavior.
- Any change to the MCP protocol version or the persisted MCP JSON field names (`items`, `screens`, `itemId`, `screenId`).

## 4. Architecture

```
[Android sidekick]
  ├─ PowerManager.isInteractive       → screenInteractive
  ├─ KeyguardManager.isKeyguardLocked → keyguardLocked
  ├─ resumed-activity counter > 0     → appForeground
  └─ last resumed activity isInPip()  → pictureInPicture
                ↓ BridgeStatus { …existing, +4 nullable fields }
[fixthis-mcp /devices, /sessions/{id}/inspect]
                ↓ DTO pass-through (4 fields + rootsCount)
[Console state.connection]
                ↓ computeBlockedReason(status, isAnnotateMode)
       interactionBlockedReason: 'screenOff' | 'locked' | 'background'
                                | 'pictureInPicture' | 'unresponsive'
                                | 'noComposeUi' | null
                ↓
       Canvas overlay + pointer-event gate + auto-resume on null transition
```

**Layering principle:** the bridge transports **raw signals**, the console computes **the single derived reason** with priority rules. Sidekick stays simple; UX policy lives in one place (`computeBlockedReason`).

## 5. Sidekick changes

### 5.1 New fields on `BridgeStatus` (all `Boolean? = null`, JSON kotlinx.serialization defaults)

```kotlin
@Serializable
data class BridgeStatus(
    // existing
    val activity: String? = null,
    val rootsCount: Int,
    val sidekickVersion: String,
    val bridgeProtocolVersion: String,
    val sourceIndexAvailable: Boolean,
    val capabilities: BridgeCapabilities = BridgeCapabilities(),
    // new — all nullable for forward/backward compat
    val screenInteractive: Boolean? = null,
    val keyguardLocked: Boolean? = null,
    val appForeground: Boolean? = null,
    val pictureInPicture: Boolean? = null,
)
```

### 5.2 Signal sources

- `FixThisBridgeRuntime` holds an `Application` reference (already does for activity tracking). It evaluates lazily on every `status()` call:
  - `screenInteractive`: `(app.getSystemService(POWER_SERVICE) as PowerManager).isInteractive`
  - `keyguardLocked`: `(app.getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked`
- `FixThisActivityLifecycleCallbacks` increments/decrements a counter on `onActivityResumed`/`onActivityPaused`. It also stores a `WeakReference<Activity>` to the most recent resumed activity. `FixThisBridgeRuntime` reads:
  - `appForeground`: `resumedCounter.get() > 0`
  - `pictureInPicture`: `lastResumed?.get()?.isInPictureInPictureMode == true`

No `BroadcastReceiver` is registered — polling on `status()` is sufficient. Console polls `status` for the device chip already.

### 5.3 Why nullable, not boolean-default

- A future protocol revision (or a user shipping a fork) can opt out and emit `null` deliberately.
- A test environment without `Application` context (Robolectric edge cases) can return null safely instead of asserting context presence.
- Console treats `null` as "no signal" and falls back to legacy behavior — no spurious blocked overlays from a partially-upgraded sidekick.

## 6. MCP / DTO pass-through

- `fixthis-mcp/.../console/FeedbackConsoleDeviceModels.kt` device DTOs gain the same four nullable boolean fields plus a derived `rootsCount` (already present on `BridgeStatus`).
- `fixthis-mcp/.../console/DeviceRoutes.kt` populates them from the upstream `BridgeStatus` payload.
- Inspect responses (`/sessions/{id}/inspect` or wherever the latest screen is delivered to the console) include the same four fields and `rootsCount`.
- No new endpoint, no new polling cadence — reuse existing device/inspect refresh.

## 7. Console changes

### 7.1 Single source of truth: `computeBlockedReason`

Lives in `fixthis-mcp/src/main/console/connection.js` (or a new `availability.js` if connection.js is already heavy). Pure function over the device status snapshot plus a tool-mode flag.

```js
function computeBlockedReason(status, isAnnotateMode) {
  if (!status) return null;
  if (status.screenInteractive === false) return 'screenOff';
  if (status.keyguardLocked === true) return 'locked';
  if (status.appForeground === false) return 'background';
  if (status.pictureInPicture === true) return 'pictureInPicture';
  if (status.unresponsive === true) return 'unresponsive'; // computed below
  if (isAnnotateMode && (status.rootsCount ?? 1) === 0) return 'noComposeUi';
  return null;
}
```

**Priority order (top wins when multiple are true):**

1. `screenOff`
2. `locked`
3. `background`
4. `pictureInPicture`
5. `unresponsive`
6. `noComposeUi`

Rationale: list the resolution the user must perform first.

### 7.2 `unresponsive` heuristic (console-only)

- `state.connection.statusFailureStreak` increments on each `status` poll that times out (>2s) or returns a network error and resets to 0 on success.
- `unresponsive = statusFailureStreak >= 3`.
- On `unresponsive` the polling cadence backs off: 1s → 2s → 5s → 10s → 30s (cap).
- A successful poll returns to the normal cadence and clears the flag.
- The sidekick emits no new field for this — it is purely a console signal.

### 7.3 Debounce

- A reason becoming `null` (resolution) is applied immediately.
- A reason becoming non-null (newly blocked) is debounced **300 ms** before applying. This absorbs orientation changes and brief activity-recreation gaps where `appForeground=false` flickers.

### 7.4 Canvas UX

- The frozen / last preview image stays rendered.
- A semi-transparent overlay layers on top with: lock icon, headline, message, and (for `unresponsive`) a `Retry now` button.
- Click and drag handlers in `annotations.js` / `rendering.js` early-return when `state.connection.interactionBlockedReason !== null`.
- The `showError(new Error('No component found at that point. …'))` call site is gated by the same check, so misleading messages no longer surface.
- For Select mode while blocked, navigation clicks are also no-ops (clicks would fail at the bridge anyway).

Messages:

| Reason | Headline | Detail |
|--------|----------|--------|
| `screenOff` | Device screen is off | Wake the device to continue. |
| `locked` | Device is locked | Unlock the device to continue. |
| `background` | Sample app is in the background | Bring the sample app to the foreground. |
| `pictureInPicture` | Sample app is in Picture-in-Picture | Exit Picture-in-Picture to continue. |
| `unresponsive` | Sample app is unresponsive | Retrying… (button: **Retry now**) |
| `noComposeUi` | No Compose UI on this screen | Switch to a screen with Compose content to annotate. |

### 7.5 Top bar / device chip

- The active-device chip appends a compact suffix when blocked: `Connected · Screen off`, `Connected · Locked`, etc.
- Inactive devices keep their existing label. Per-device availability fan-out is out of scope for v1 because the server only probes the selected device's bridge today; surfacing inactive-device blocked reasons would require fanning out `bridge.status` calls and is deferred.
- Existing `Connected` / `Connecting` / `Unavailable` strings remain otherwise unchanged.

### 7.6 Auto-resume / state preservation

**Preserved across blocked → unblocked while connection holds:**

- `toolMode` (select / annotate)
- `state.preview` (frozen previewId, screen, screenshotUrl) when in Annotate mode
- `pendingFeedbackItems` (unsaved pins)

**Cleared / not preserved:**

- `currentSelection`, `hoveredAnnotationTarget` (recomputed on next interaction)

**Across Connected → Unavailable → Connected (bridge actually drops):**

- The console retains `toolMode`, `state.preview`, and `pendingFeedbackItems` on Unavailable transition.
- On reconnect, if the new `applicationId` and `activity` match the frozen preview's, restore them silently.
- If they do not match, mark the frozen frame as stale; show a single `Use latest frame` button (and keep the pending pins disabled until the user resolves).

### 7.7 Multi-device

`interactionBlockedReason` is computed per device. The canvas overlay reflects the *active* device's reason. Inactive devices show their reason only via the chip suffix.

## 8. Compatibility

- Adding nullable fields to `BridgeStatus` is non-breaking under kotlinx.serialization defaults.
- `bridgeProtocolVersion` does **not** bump — additive nullable fields are within the current contract.
- Older sidekick → all four signals null → `computeBlockedReason` returns null → existing behavior.
- Newer sidekick + older console → console ignores unknown fields → existing behavior.
- Persisted MCP JSON DTO field names remain untouched (the new fields are runtime-only on the device DTO; nothing about saved sessions changes).

## 9. Testing strategy

### 9.1 Sidekick (Robolectric / unit)

- `BridgeStatusTest`: stub `PowerManager` and `KeyguardManager` shadows, verify `screenInteractive` and `keyguardLocked` reflect them.
- `FixThisActivityLifecycleCallbacksTest`: simulate resumed → paused → resumed activity sequences, assert `appForeground` counter and `lastResumed` weak reference.
- `BridgeRuntimePiPTest`: stub `Activity.isInPictureInPictureMode`, verify `pictureInPicture` mirrors it; null when no resumed activity.

### 9.2 MCP (Kotlin unit)

- `FeedbackConsoleServerTest`: device-list endpoint passes through all four fields and `rootsCount`. Inspect endpoint includes them on the latest screen payload.
- Round-trip JSON test: an old sidekick payload (no fields) deserializes safely with all-null fields; new payload deserializes with values.

### 9.3 Console (vanilla JS)

The repo has no JS unit-test framework today, only `scripts/console-browser-smoke.mjs` (Playwright). We add a minimal **pure-function harness** using Node's built-in test runner (`node --test`) for logic that doesn't touch the DOM:

- `scripts/console-availability-test.mjs` (new): imports `computeBlockedReason` and the debounce helper from a new pure module `fixthis-mcp/src/main/console/availability.js`, exercises the priority matrix, debounce timing, and `unresponsive` failure-streak transitions.
- DOM-touching behavior (overlay rendering, click/drag gate, auto-resume preservation, `Use latest frame` button) extends `scripts/console-browser-smoke.mjs` with new fake-fixture flows.
- Run via `node scripts/console-availability-test.mjs` (added to `package.json` scripts as `console:availability:test`) and `npm run console:smoke`.

Manual cases that resist automation (real device timing, rotation flicker) are listed under §9.4.

### 9.4 Manual verification (post-build, on a connected device)

Each scenario starts with the console open in Annotate mode with one or two pins placed on a frozen frame:

1. Press the power button (screen off) → overlay shows `screenOff` → press power again → overlay clears, frozen frame and pins intact.
2. Press Home → overlay shows `background` → return to the sample app → overlay clears, state intact.
3. Lock the device → overlay shows `locked` (or `background` if keyguard already implies pause) → unlock → overlay clears.
4. Open the app's PiP mode (if applicable) → overlay shows `pictureInPicture` → exit PiP → overlay clears.
5. Force an ANR (e.g., `kill -STOP $(pidof <pkg>)`) → after ~3 seconds overlay shows `unresponsive` with `Retry now` → `kill -CONT` → overlay clears on next successful poll.
6. Navigate to a non-Compose activity in the sample app while in Annotate mode → overlay shows `noComposeUi` → return to a Compose screen → overlay clears.
7. Pull USB / kill the bridge while Annotated → device shows `Unavailable` (existing behavior) but `state.preview` and pins persist → reconnect with the same activity → frozen preview and pins restored automatically; reconnect onto a different activity → `Use latest frame` button appears.
8. Rotate the device twice rapidly → no overlay flicker (debounce holds).

## 10. Risks and open questions

- **`LocalSocket` survival under screen-off:** unverified whether `LocalServerSocket` and the connected client socket survive doze / screen-off in all OEMs. Design covers both paths (signal-driven vs. drop-then-reconnect), so the worst case is still better than today.
- **`isInPictureInPictureMode` API levels:** available since API 24. The sample app's minSdk should be ≥ 24; if not, gate the call.
- **`KeyguardManager.isKeyguardLocked` semantics:** returns true even when the device is "locked but unlock-not-required". For our purposes (the activity is gated behind keyguard) this is fine; we only block input, not navigation.
- **Concurrent `screenOff` and `background`:** both can be true. Priority order (`screenOff` first) mirrors what the user must resolve first.
- **`unresponsive` while `screenOff`:** during screen-off the sidekick may still respond (process alive). Our priority gives `screenOff` precedence — once the user wakes the device the heuristic clears naturally.

## 11. Acceptance criteria

- [ ] All Annotate-mode click/drag attempts during a blocked state produce **no** error message and **no** selection; the overlay communicates the cause.
- [ ] Returning the device to a normal state (any of the six causes resolved) auto-restores the prior `toolMode`, frozen preview (when Annotate), and pending pins, without user action.
- [ ] An older sidekick that does not emit the four new fields produces unchanged console behavior.
- [ ] No regressions in `:fixthis-compose-core`, `:fixthis-cli`, `:fixthis-mcp`, `:fixthis-compose-sidekick`, or `:fixthis-gradle-plugin` test suites.
- [ ] Manual checklist (§9.4 1–8) passes on at least one physical Android device.
