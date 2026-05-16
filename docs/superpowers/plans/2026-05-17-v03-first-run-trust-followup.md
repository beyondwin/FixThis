# v0.3 First-Run Trust Program Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Tasks use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six items deferred by the v0.3 First-Run Trust Program — the five Edge Case Coverage Map → Deferred rows and the pre-existing `console:smoke` failure at line 768.

**Architecture:** Reuse the v0.3 readiness contract (FirstRunReadiness DTO, NotificationCenter policy layer, ConsoleRequestError) merged in `826277b4`. Each task is a single wiring step plus tests; no new architectural layers introduced.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization JSON, JUnit 4, Node 20 `node:test`, Playwright smoke harness, existing console `// @requires` bundler.

**Spec:** [`../specs/2026-05-17-v03-first-run-trust-followup-design.md`](../specs/2026-05-17-v03-first-run-trust-followup-design.md)

---

## Scope Check

Six independent tasks plus one docs task. All share the v0.3 readiness contract but each is independently testable and committable. Keep the plan as one unit because the test command and console-asset rebundling are common to all.

## File Structure

| Path | Responsibility |
| --- | --- |
| `fixthis-mcp/src/main/console/state.js` | Add `surfaceReloadConsoleNotice` helper that branches on `ConsoleRequestError.action === 'reload_console'` and fires NotificationCenter banner with reload primaryAction. |
| `fixthis-mcp/src/main/console/api.js` | Call `surfaceReloadConsoleNotice` at the requestJson catch boundary before falling back to legacy showError. |
| `scripts/reloadConsoleNotice-test.mjs` | Pin: 403 action plumbs through to banner notification with primaryAction. |
| `fixthis-mcp/src/main/console/clipboard.js` (and/or callers like `prompt.js`, `main.js`) | Migrate `copyTextToClipboard` catch to NotificationCenter warning banner with manual-copy detail. |
| `scripts/clipboardFallback-test.mjs` | Pin: clipboard failure routes through NotificationCenter (not showError). |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt` | Add `CAPTURE_UNAVAILABLE` state + `captureUnavailable(...)` canned entry. |
| `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt` | Pin CAPTURE_UNAVAILABLE in state catalog and canned action. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsolePreviewService.kt` | Emit `captureUnavailable` readiness when preview/screenshot endpoint returns 404 or semantics-only. Add `previewAvailable: false` to payload. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsolePreviewServiceTest.kt` | Pin server-side classification + payload shape. |
| `fixthis-mcp/src/main/console/preview.js` (or `connection.js`) | Render CAPTURE_UNAVAILABLE through existing readiness slot; surface "Retry capture" primary action. |
| `scripts/captureUnavailableRender-test.mjs` | Pin client surfacing. |
| `fixthis-mcp/src/main/console/sse.js` | Drop messages with `sessionId !== current session`. |
| `fixthis-mcp/src/main/console/previewPoll.js` | Drop responses with `sessionId !== current session`. |
| `scripts/sessionMismatchIgnore-test.mjs` | Pin: late SSE + late poll responses are silently dropped without state mutation. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/DraftSaveService.kt` (or equivalent) | Add ETag emission and If-Match enforcement; return 412 with `state=STALE_PREVIEW`, `serverDraft`, `readiness`. |
| `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/DraftSaveServiceTest.kt` | Pin etag round-trip and 412 body. |
| `fixthis-mcp/src/main/console/boundaryDialogVariants.js` | Add `staleDraftConflict` variant: primary "Keep mine (overwrite)", secondary "Use server's version", cancel. |
| `fixthis-mcp/src/main/console/draftSync.js` (or `main.js` save site) | On 412, open `staleDraftConflict`; route choice back to save with If-Match: * or to local discard + reload. |
| `scripts/boundaryDialogVariants-test.mjs` | Add render test for `staleDraftConflict`. |
| `scripts/staleDraftConflict-test.mjs` | Pin 412 → boundary → save outcome end-to-end (with fixture). |
| `fixthis-mcp/src/main/console/connection.js` OR `scripts/console-browser-smoke.mjs` | Fix previewStaleBadge transition gating at OPEN_APP. Implementer picks the simpler correct fix (see spec §6). |
| `docs/CHANGELOG.md`, `docs/reference/feedback-console-contract.md` | Document new CAPTURE_UNAVAILABLE state, 412 conflict policy, NotificationCenter reload/clipboard surfaces. |

## Execution Notes

- Each task commits separately. Use `feat:` / `test:` / `fix:` prefixes per existing convention.
- Rebuild `fixthis-mcp/src/main/resources/console/app.js` whenever any file under `fixthis-mcp/src/main/console/` changes (`node scripts/build-console-assets.mjs`).
- Run console JS tests via `node scripts/run-console-tests.mjs <suite>` (existing harness). For new suites, register in `scripts/console-tests.json`.
- Final verification (task_6) MUST include `npm run console:smoke` passing (task_5 prerequisite).

---

## Task 0: Wire 403 reload CTA consumer

**Risk:** mid

**Files:**
- fixthis-mcp/src/main/console/state.js
- fixthis-mcp/src/main/console/api.js
- scripts/reloadConsoleNotice-test.mjs
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt)

**Spec Refs:** Design §1

### Acceptance Criteria

```bash
node --test scripts/reloadConsoleNotice-test.mjs
node scripts/build-console-assets.mjs --check
```

### Steps

- [ ] Red: write `scripts/reloadConsoleNotice-test.mjs` asserting that handing a `ConsoleRequestError({action:'reload_console'})` to `surfaceReloadConsoleNotice` fires `notificationCenter.notify({severity:'error', surface:'banner', dedupeKey:'reload_console_403', primaryAction:{label:'Reload console', ...}})`. Confirm RED.
- [ ] Add `surfaceReloadConsoleNotice(err)` in `state.js` (loadable by console-test-loader); it branches on `err.action === 'reload_console'` and otherwise returns false.
- [ ] In `api.js` requestJson catch path, call `surfaceReloadConsoleNotice(err)`; if it returned true, do NOT also call `showError`.
- [ ] Register the new test in `scripts/console-tests.json`.
- [ ] Rebuild console assets and run `--check`.

---

## Task 1: Clipboard fallback through NotificationCenter

**Risk:** mid

**Files:**
- fixthis-mcp/src/main/console/clipboard.js (or wherever `copyTextToClipboard` lives — Implementer locate)
- fixthis-mcp/src/main/console/prompt.js (and any other caller)
- scripts/clipboardFallback-test.mjs
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt)

**Spec Refs:** Design §2

### Acceptance Criteria

```bash
node --test scripts/clipboardFallback-test.mjs
node scripts/build-console-assets.mjs --check
```

### Steps

- [ ] Red: write `scripts/clipboardFallback-test.mjs` asserting that when `copyTextToClipboard` rejects, the caller invokes `notificationCenter.notify` with `severity:'warning', surface:'banner', dedupeKey:'clipboard_fallback', detail:/manually/` and does NOT call legacy `showError`. Confirm RED.
- [ ] Locate every `copyTextToClipboard(...).catch(...)` call site via `grep -rn copyTextToClipboard fixthis-mcp/src/main/console/`.
- [ ] Replace each catch with the NotificationCenter notify pattern from spec §2.
- [ ] Remove unused `showError` imports introduced for clipboard.
- [ ] Rebuild console assets and run `--check`.

---

## Task 2: CAPTURE_UNAVAILABLE readiness state (server + client)

**Risk:** high

**Files:**
- fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt
- fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadinessTest.kt
- fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsolePreviewService.kt (or correct preview endpoint owner — Implementer locate)
- fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/ConsolePreviewServiceTest.kt
- fixthis-mcp/src/main/console/preview.js (or connection.js if that's where readiness rendering lives)
- scripts/captureUnavailableRender-test.mjs
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt)

**Spec Refs:** Design §3

### Acceptance Criteria

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon \
  --tests "*FirstRunReadinessTest*" --tests "*ConsolePreviewService*"
node --test scripts/captureUnavailableRender-test.mjs
```

### Steps

- [ ] Red: extend `FirstRunReadinessTest.kt` with a test for `captureUnavailable(...)` canned action; confirm RED (state does not yet exist).
- [ ] Add `CAPTURE_UNAVAILABLE` to `FirstRunReadinessState` enum + canned `captureUnavailable(...)` constructor.
- [ ] Red: add a server-side test in `ConsolePreviewServiceTest.kt` (or appropriate test) asserting that a 404 / no-image response yields `payload.previewAvailable === false` and `payload.readiness.state == "CAPTURE_UNAVAILABLE"`.
- [ ] Wire server emission per the test.
- [ ] Red: write `scripts/captureUnavailableRender-test.mjs` asserting that a connection/preview status payload with the new readiness renders the readiness slot with "Retry capture" primary action.
- [ ] Wire client rendering.
- [ ] Rebuild console assets and run `--check`.

---

## Task 3: Late SSE / SESSION_MISMATCH ignore

**Risk:** mid

**Files:**
- fixthis-mcp/src/main/console/sse.js
- fixthis-mcp/src/main/console/previewPoll.js
- scripts/sessionMismatchIgnore-test.mjs
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt)

**Spec Refs:** Design §4

### Acceptance Criteria

```bash
node --test scripts/sessionMismatchIgnore-test.mjs
```

### Steps

- [ ] Red: write tests covering both consumers: a message/response with `sessionId !== state.session.sessionId` MUST NOT mutate state and MUST NOT trigger a NotificationCenter notification (silent drop). Confirm RED.
- [ ] Add the sessionId-equality gate at each consumer entry point.
- [ ] Rebuild console assets and run `--check`.

---

## Task 4: Older save / newer edits conflict policy (etag + boundary)

**Risk:** high

**Files:**
- fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/DraftSaveService.kt (Implementer locate exact owner of draft-save endpoint)
- fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/session/DraftSaveServiceTest.kt
- fixthis-mcp/src/main/console/boundaryDialogVariants.js
- fixthis-mcp/src/main/console/draftSync.js (or main.js save call site)
- scripts/boundaryDialogVariants-test.mjs
- scripts/staleDraftConflict-test.mjs
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt)

**Spec Refs:** Design §5

**Resource Key:** draft-save-service

### Acceptance Criteria

```bash
./gradlew :fixthis-mcp:test --no-daemon --tests "*DraftSaveService*"
node --test scripts/boundaryDialogVariants-test.mjs scripts/staleDraftConflict-test.mjs
```

### Steps

- [ ] Red: extend `DraftSaveServiceTest.kt` with: (a) successful save returns `ETag: "<rev>"`, (b) second save without `If-Match` returns 412, (c) second save with stale `If-Match` returns 412 with body `{state:"STALE_PREVIEW", readiness: ..., serverDraft: ...}`, (d) `If-Match: *` overrides and saves. Confirm RED.
- [ ] Implement ETag emission and If-Match enforcement.
- [ ] Red: extend `boundaryDialogVariants-test.mjs` with render test for `staleDraftConflict` (labels: Cancel, "Use server's version", "Keep mine (overwrite)").
- [ ] Add the variant to `boundaryDialogVariants.js`.
- [ ] Red: write `scripts/staleDraftConflict-test.mjs` against the fake bridge fixture: simulate 412 response, assert boundary dialog opens, choosing "Keep mine" resubmits with `If-Match: *`, choosing "Use server's version" loads `serverDraft` into local state. Confirm RED.
- [ ] Implement client handling in `draftSync.js` (or the save site).
- [ ] Rebuild console assets and run `--check`.

---

## Task 5: Fix console:smoke previewStaleBadge transition (line 768)

**Risk:** mid

**Files:**
- fixthis-mcp/src/main/console/connection.js (likely)
- scripts/console-browser-smoke.mjs (if smoke seeding is the right fix)
- scripts/markPreviewStale-test.mjs (new unit test pinning the gate)
- scripts/console-tests.json
- fixthis-mcp/src/main/resources/console/app.js (rebuilt, if connection.js changed)

**Spec Refs:** Design §6

### Acceptance Criteria

```bash
node --test scripts/markPreviewStale-test.mjs
npm run console:smoke
```

### Steps

- [ ] Red: write `scripts/markPreviewStale-test.mjs` asserting that when `viewState !== 'ready'` AND `hasEverConnected === true`, `markPreviewStale(true)` is called and `statusSurfaceRegistry.show('previewStaleBadge')` fires — even if `state.preview` is null at the moment of transition. Confirm RED.
- [ ] Decide fix path (per spec §6): production fix in connection.js OR smoke seeding fix. Implementer must justify choice in commit message.
- [ ] Apply fix.
- [ ] Run `npm run console:smoke` — must exit 0.
- [ ] Rebuild console assets and run `--check` (if connection.js changed).

---

## Task 6: Docs + Final verification

**Risk:** low

**Files:**
- docs/CHANGELOG.md
- docs/reference/feedback-console-contract.md

**Spec Refs:** entire design

### Acceptance Criteria

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node scripts/run-console-tests.mjs reloadConsoleNotice clipboardFallback captureUnavailableRender sessionMismatchIgnore staleDraftConflict markPreviewStale
npm run console:smoke
npm run first-run:smoke
node scripts/check-doc-consistency.mjs
git diff --check
```

### Steps

- [ ] Update CHANGELOG.md with a "v0.3 follow-up" section enumerating the six closures.
- [ ] Update `docs/reference/feedback-console-contract.md` to document: new `CAPTURE_UNAVAILABLE` readiness state, ETag/If-Match draft save contract, `staleDraftConflict` boundary variant, reload-console banner surface, clipboard-fallback banner surface, SESSION_MISMATCH silent-drop policy.
- [ ] Run all acceptance criteria commands above. Every command MUST exit 0. If any test fails, STOP and ESCALATE — do NOT mark this task complete.
