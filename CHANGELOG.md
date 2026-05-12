# Changelog

All notable user-visible changes to FixThis are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Versioning policy

- **MAJOR** (`X.y.z`) — breaking changes to the public Gradle plugin API,
  the persisted MCP JSON schema (`items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `sourceCandidates`), or the CLI flag surface documented in
  [`docs/reference/cli.md`](docs/reference/cli.md).
- **MINOR** (`x.Y.z`) — additive features, new MCP tools, new CLI flags with
  safe defaults, or wire-protocol bumps that remain backward-compatible at
  the persisted-JSON layer.
- **PATCH** (`x.y.Z`) — bug fixes, internal refactors, doc updates,
  console-only UI improvements with no contract change.

The bridge protocol (`BridgeProtocol.VERSION`) carries its own version
independent of the package version. Wire-visible bridge changes follow the
checklist in
[`docs/reference/bridge-protocol.md`](docs/reference/bridge-protocol.md).

Until the first `1.0.0` external release, breaking changes may land under
minor / patch labels — see [release-readiness](docs/contributing/release-readiness.md).

## Unreleased

### Added

- **Annotation lifecycle hardening — Phase A (ALH-1..ALH-4):**
  - Per-session append-only event log at `.fixthis/feedback-sessions/<sessionId>/events/`.
    Every spec'd mutation (`addItem`, `addScreen`, `addScreenWithItems`,
    `deleteScreen`, `updateDraftItem`, `deleteDraftItem`, `sendDraftToAgent`)
    is fsync'd to disk **before** the in-memory store is updated (write-ahead).
    On boot the events are replayed to reconstruct state — a SIGKILL between
    fsync and the snapshot save no longer loses work.
  - Event-log writes use atomic rename (`<name>.tmp` → `<name>.jsonl`) and
    `EventLogException` fail-stop semantics; partial writes never reach the
    final filename.
  - Background `EventLogCompactor` archives the oldest events to
    `events/archive/` once the per-session log exceeds 1000 entries, while
    refreshing the snapshot in `state.json`.
  - Console `pendingFeedbackItems` are now mirrored to
    `localStorage["fixthis.pending.<sessionId>"]` on every append/update/delete
    and auto-restored when the same session is re-attached after an adb USB
    disconnect, browser refresh, or tab close.
  - `beforeunload` confirm prompt fires when the user tries to close the tab
    while pending annotations are unsaved.
  - Undo/redo for pending annotation deletes: `Cmd+Z` / `Ctrl+Z` undoes,
    `Cmd+Shift+Z` redoes (depth 50; ignored inside `<input>`/`<textarea>`/
    contenteditable). A 5-second toast with a 되돌리기 button appears after
    each delete.

### Changed

### Removed

### Fixed

## [0.1.0] - 2026-05-11

### Added

- CI hardening: documented the required PR checks contract in `CONTRIBUTING.md` (table mapping each check → workflow → source task → status) and added `docs/contributing/required-checks.md` as a readiness tracker with one row per workflow and a "green for 7 days?" column. **Follow-up (deferred):** the actual branch-protection flip in GitHub repo settings is a maintainer admin action and is gated on each "Pending" row meeting its observation window (7 consecutive green for PR-time checks, 14 consecutive green for the nightly connected-tests workflow, 1 week stable for the nightly compatibility matrix). (CI-5 from ci-cd-hardening.)
- CI hardening: nightly connected (instrumented) Android-tests workflow lands at `.github/workflows/connected-tests.yml`. It runs on `schedule: '0 4 * * *'` and `workflow_dispatch` only — intentionally **not** on `pull_request` — and uses `reactivecircus/android-emulator-runner@v2` to boot an emulator and execute `./gradlew connectedDebugAndroidTest --no-daemon`. The test step is `continue-on-error: true` so a red nightly is informational and does not gate other workflows. Flake-triage process and the (currently empty) table of temporarily-disabled tests are documented in `docs/contributing/connected-tests.md`. **Follow-up (deferred):** promotion of the nightly to a required PR check once 14 consecutive green runs are observed and the flake-triage process has stabilised the suite. (CI-4 from ci-cd-hardening.)
- CI hardening: Dependabot config (`.github/dependabot.yml`) covering `gradle`, `github-actions`, and `npm` ecosystems on a weekly cadence with minor+patch update grouping; CodeQL analysis workflow (`.github/workflows/codeql.yml`) for `java-kotlin` and `javascript-typescript` running on push/PR + weekly cron; gradle-version-check workflow (`.github/workflows/gradle-version-check.yml`) running the `com.github.ben-manes.versions` plugin (`dependencyUpdates` task) weekly and uploading the report as an artifact. **Follow-ups (deferred):** (a) observe Dependabot opens its first PR within 7 days, (b) observe CodeQL results land in the GitHub Security tab, (c) wire `gradle-version-check` to auto-open an issue or fail on a non-test dependency ≥ 1 minor version behind (today the report is artifact-only). (CI-3 from ci-cd-hardening.)
- Nightly compatibility matrix workflow lands (`.github/workflows/nightly-compat.yml`) and the supported AGP/Kotlin/Compose window is documented in `docs/reference/compatibility.md`. Promotion of the nightly to a required CI check is a follow-up after 1 week of stable runs. (BR-4 from build-release-hardening.)
- Build hardening: sidekick now ships `consumer-rules.pro` keeping the Compose reflection entry points (`RootForTest`, `SemanticsOwner`/`SemanticsOwnerKt`, `AndroidComposeView`) plus the public bridge surface and `FixThisInitializer`. A new `:fixthis-gradle-plugin:functionalTest` task structurally validates the rules. Consumer release builds with R8 minify will no longer strip the symbols the sidekick reflects on. (BR-2 from build-release-hardening.)
- Build hardening: the sidekick's `androidx.startup` initializer entry now lives in `src/debug/AndroidManifest.xml` instead of `src/main/AndroidManifest.xml`. A consumer app that accidentally promotes the sidekick to `implementation` will no longer have the bridge attached at process start in release builds. The runtime `FLAG_DEBUGGABLE` early-return is preserved as defence-in-depth and now logs a single `Log.w("FixThisInitializer", …)` warning if reached in a non-debuggable build. A new `ReleaseGuardTest` structurally asserts the manifest split + log presence. (BR-1 from build-release-hardening.)
- Security: published `docs/reference/threat-model.md` documenting FixThis's assets, trust boundaries, in/out-of-scope adversaries, current mitigations (debug-only manifest, FLAG_DEBUGGABLE runtime guard, LocalSocket transport, 127.0.0.1 + Origin + X-FixThis-Token on the console), and open gaps. Linked from README, `docs/index.md`, and `SECURITY.md`. (SEC-1 from security-hardening.)
- Security: extracted screenshot-path containment check into `PathSafety.isUnder(child, parent)` co-located with the bridge package; the cache-directory resolution and containment check now both happen inside the same `withContext(ioDispatcher)` block in `BridgeServer.readScreenshot`, shrinking the TOCTOU window. New `PathSafetyTest` (8 cases) and `BridgeServerScreenshotPathTest` (3 cases) cover `../../etc/passwd`, `./../foo`, symlinks pointing outside the cache, trailing-slash parents, repeated separators, and sibling-prefix attacks. (SEC-2 from security-hardening.)
- Security: `BridgeServer.start()` now retries up to 3 attempts with suffix fallback (`<name>`, `<name>-1`, `<name>-2`) via `BridgeSocketNameNegotiator`, recovering from stale abstract-namespace socket bindings left behind by a prior process. The resolved name is written back to `session.json` post-bind and exposed via the handshake (`BridgeStatus.socketName`). `BridgeClient` (CLI) mirrors the suffix-fallback on its connect path. **Bridge protocol bumped 1.1 → 1.2**; all four mirror sites (`BridgeProtocol.kt`, `BridgeClient.kt`, `ServerVersionRoutes.kt`, `staleness.js`) updated and `BridgeProtocolVersionSyncTest` continues to enforce sync. (SEC-3 from security-hardening.)
- Annotation lifecycle is now visualized in 4 phases — Draft / Sent / In Progress / Resolved — with distinct badge colors and a left-border stripe per row. Sent items remain editable; modifying one after Save (or Copy Prompt) raises a "⚠ Modified after Save" banner with a Re-save button so the agent gets the up-to-date version. In Progress and Resolved items are locked, with the agent's resolution summary surfaced inline. Tracked via the new `lastHandedOffAtEpochMillis` field on each annotation and the derived `staleAfterHandoff` flag on every list / read response.
- `./scripts/bootstrap-mcp.sh --package <applicationId>` — single command that builds `:fixthis-cli` and `:fixthis-mcp` installDist and registers the MCP server with Claude Code and Codex. Replaces the two-step manual flow in `AGENTS.md`. Manual setup remains documented for Windows users.
- `fixthis_claim_feedback` MCP tool — agents call it before starting work on an item; status moves to `in_progress` and the browser console reflects the change within ~2 seconds.
- ETag-based polling on `/api/sessions` and `/api/session` (304 when unchanged); the console polls every 2 seconds and refreshes status badges live.
- `inProgressItemsCount` in session summaries, surfaced as a `working` pip on each History row.
- `agent_protocol:` footer plus per-item `id:` token in the compact handoff prompt so the Copy Prompt route is self-describing.
- `includeAll` parameter on `fixthis_list_feedback` and `fixthis_read_feedback`.

### Changed

- Console staleness banner now distinguishes "sample app sidekick is older than console" vs "this console is older than sample app sidekick" via numeric component-wise compare on the bridge protocol version, replacing the previous symmetric string equality. Banner copy and dismiss-hash include the direction. (R1 from bridge-protocol-safety-net.)
- New `BridgeProtocolVersionSyncTest` unit test in `:fixthis-mcp:test` enforces that all four bridge-protocol-version mirror sites (`BridgeProtocol.kt`, `BridgeClient.kt`, `ServerVersionRoutes.kt`, `staleness.js`) hold the same string. A forgotten bump now fails standard CI with a diagnostic naming each file and its observed value. (R2 from bridge-protocol-safety-net.)
- console: documented `<pre id="connectionDetailsBody">` + `white-space: pre-wrap`
  dependency in `connection.js` for the Reconnecting sub-line. Defensive comment
  only, no behavior change. (H1, prevents silent visual regression on HTML/CSS
  refactor.)
- build: `scripts/build-console-assets.mjs` now asserts every `.js` file in the
  console source directory is declared in the ordered `sources` array. Catches
  the case where a contributor adds a module file but forgets to register it.
  (H2, prevents silent absence from the bundle.)
- `fixthis_list_feedback` and `fixthis_read_feedback` now default to returning only `delivery: sent` items that are not resolved. Pass `includeAll: true` to restore the previous behavior.
- `fixthis_resolve_feedback` description now mentions the claim/resolve pairing.
- "Save to MCP" toast now reads `Saved to MCP ✓ — agent will pick up`.
- Internal / refactor: `:fixthis-cli` `RunCommand.waitForStatus` now uses `delay` with a `200/400/800/1500` ms capped backoff instead of `Thread.sleep(500)`. Cancellation of the parent coroutine returns within one scheduler tick instead of up to 500 ms. (Code hardening CH-2.)
- Internal / refactor: removed `!!` operators from `:fixthis-mcp` session sources (`TargetEvidenceService`, `InstanceGroupingHelper`, `FeedbackDraftService`); replaced with `requireNotNull` / `checkNotNull` carrying upstream-contract messages, so unmet invariants surface as `IllegalStateException` with diagnostics instead of `NullPointerException`. (Code hardening CH-1.)
- Internal / refactor: extracted MCP in-flight request bookkeeping from `McpServer` raw `synchronized` blocks into a dedicated `InFlightRegistry` backed by a single `kotlinx.coroutines.sync.Mutex`. `consumeAll()` snapshots + clears in one critical section; `cancelAndJoin` runs outside the lock. (Code hardening CH-3.)
- Internal / refactor: split `FeedbackSessionService` (~304 lines) into three single-responsibility collaborators: `FeedbackSessionRegistry` (lifecycle), `AnnotationRepository` (annotation CRUD + status), and `EvidenceCoordinator` (screenshot / preview / navigate). The original class is now a thin façade preserving its public API for the 10 production callers. Each new class has dedicated unit tests independent of HTTP / MCP plumbing. (Code hardening CH-4.)
- Build: routed the last two stray Maven coordinates in `fixthis-compose-sidekick/build.gradle.kts` (`androidx.test:core`, `org.robolectric:robolectric`) through `gradle/libs.versions.toml`. No more hard-coded `group:artifact:version` literals in module build scripts. (BR-3 from build-release-hardening.)
- Build: `:fixthis-compose-sidekick:generateBuildInfo` is now cache-safe — `buildEpoch` reads from `git log -1 --format=%ct` on clean trees (falling back to `currentTimeMillis()` only when the working tree is dirty), and `gitSha` gets a `-dirty` suffix instead of recomputing every invocation. Two consecutive runs on the same SHA now report `UP-TO-DATE`. (BR-5 from build-release-hardening.)

### Removed

- Sent History drawer in the browser console. Sessions stay in the main History list; the per-row `×` button still closes a session.
- `points` pip on History rows (replaced by `working`).

### Fixed

- Connected smoke now force-stops an existing sample app process before install
  and retries `fixthis doctor` briefly after launch, avoiding a false failure
  when a stale bridge process or startup race reports an older protocol during
  release validation.
- Fixed: "Copy Prompt" / "Save to MCP" output now includes `id:` / `session_id:` / `agent_protocol:` / `crop:` / `⚠ stale:`, restoring agent-side `fixthis_claim_feedback` and `fixthis_resolve_feedback` after a handoff. The browser no longer renders the prompt itself — both buttons route through the new server endpoint `POST /api/sessions/{sid}/handoff-preview` (or, for Save to MCP, `POST /api/agent-handoffs` with `{itemIds:[...]}`). Eliminates ~500 LoC of duplicated rendering.
- Canvas `blocked-reason` overlay now renders even before the first screenshot arrives, so screen-off / locked / backgrounded states are communicated during initial connection instead of staying invisible until the first preview lands.
- Connection-status changes now re-render the preview region, so the canvas overlay and input gating refresh immediately when blocked-reason transitions occur (previously the overlay could lag behind the chip until the next preview frame).
- Removed a disruptive browser alert dialog that interrupted the workflow when copying the prompt; copying now works silently without a popup.
- "Copy Prompt" and "Send Agent" buttons are now correctly disabled while a request is in progress, preventing duplicate submissions.
- Switching focus between annotation entries no longer discards in-progress comment text; any pending comment is saved before the focus change takes effect.
- Annotation comment text entered in an input field is now persisted when the field loses focus, so drafts are no longer silently dropped.
- Status bar correctly clears a previous success indicator when a new error message is shown.
- Repeated heartbeat/bridge connectivity errors are now suppressed in the status bar; only the first occurrence of an identical error is shown until the next successful heartbeat resets the dedup state, eliminating noisy repeated alerts when the bridge is offline.
- Removed a duplicate static `#selectionOverlay` element from the preview region; the overlay is now created and managed exclusively by the JS preview frame, preventing rare double-overlay rendering and stale DOM state.
- Empty preview stage now shows context-appropriate guidance instead of a single generic message: "Connect a device to get started." when no session is active, "Waiting for first capture from device…" when connected without a screenshot yet, and "No screenshot artifact for this preview." when a screen exists but its image is missing.

- Fixed annotation screen mismatch in the feedback console: after saving annotations on one screen and navigating the device to another, the preview now shows the live device screen instead of staying stuck on the previous screenshot. Clicking a saved annotation swaps the preview to that annotation's saved screenshot and shows only that screen's annotation pins; deselecting returns to the live preview. Cross-screen pin overlap (screen 1 pins appearing on a screen 2 preview) is also eliminated.
- `fixthis setup --write` now includes the underlying parse/validation error in the merge-failure message instead of swallowing it.
- `fixthis setup --write` now produces an actionable error when an existing `.claude/settings.json` has `mcpServers` set to a non-object value (e.g., array or string), instead of leaking a cryptic `JsonElement is not a JsonObject` message.
- The "`fixthis-mcp` executable not found" warning during `fixthis setup --write` now explains the consequence (MCP clients will fail to start FixThis) and the fix command (`./gradlew :fixthis-mcp:installDist`).
- Annotation pins now stay visible across live preview polls within the same screen and disappear naturally when the device navigates to a different screen, instead of vanishing each second and only reappearing after a session re-open. Pin visibility is judged per-annotation by anchor (semantics-node uid) on the visible screen, with screenId equality kept as a fallback for area-only annotations.

### Changed

- `BridgeProtocol.VERSION` "1.0" → "1.1" (sidekickBuildEpochMs 필드 추가에 따른 minor bump). 옛 sample APK 가 새 콘솔에 연결되면 빨간 banner 로 reinstall 안내.
- `fixthis setup --write` output now labels each written config with its scope: `(project-local)` for Claude (project's `.claude/settings.json`) and `(global)` for Codex (user's `~/.codex/config.toml`). Affects both the success line and the `--dry-run` `Target:` line.

### Added

- `scripts/restart-console.sh` 헬퍼. stale fixthis-mcp/cli 콘솔 프로세스를 종료하고 incremental gradle 빌드 + 재시작을 단일 커맨드로 묶음. `--with-app` 으로 샘플 APK 도 같이 재설치, `--dry-run` 으로 동작 미리보기.
- 콘솔 boot 시 자동 staleness 감지 (staleness banner). `fixthis-mcp` JAR 또는 sidekick 빌드가 5분 이상 차이 나면 dismissable banner 로 알림. 새 endpoint `/api/server-version` (server build epoch + git sha + bridge protocol version) + `BridgeStatus.sidekickBuildEpochMs` 필드 추가.
- Console now models a `Connected` sub-state for screen-off, app-backgrounded, lock screen, Picture-in-Picture, unresponsive sample app, and "no Compose UI on this screen", with a canvas overlay, canvas-input gating, top-bar chip suffix, and automatic resume of the prior tool mode, frozen preview, and pending pins when the cause clears. Sidekick exposes `screenInteractive`, `keyguardLocked`, `appForeground`, and `pictureInPicture` on `BridgeStatus`.
- "Copy Prompt" button now briefly displays a "Copied ✓" confirmation after copying to the clipboard, giving clear visual feedback.
- "Send Agent" action now shows a success status message in the console status bar after the request completes successfully.
- Severity and status segmented buttons in both pending and saved annotation panels now expose `aria-pressed` state, so screen readers correctly announce which option is currently selected.
- Keyboard-only users now see a clear focus ring (2px accent-color outline) on tool, zoom, annotation back, and segmented buttons; the ring uses `:focus-visible` so it does not appear on mouse clicks.

- Added: `fixthis_status` now reports `installStale` plus an `installStaleHint` when host source files are newer than the installed APK; each `SourceCandidate` carries `stale`/`staleReason` so AI agents can detect coordinates that no longer match host source.
- Improved: `fixthis_status` distinguishes a likely `projectRoot` misconfiguration ("0 of N indexed files exist on host") from genuine staleness. Compact and queue handoff markdown now mark stale source candidates with `⚠ stale: <reason>` so AI agents notice without inspecting raw JSON. `scripts/fixthis-smoke.sh --check-staleness` runs an end-to-end round-trip against a connected device.
- SourceMatcher confidence is now margin- and evidence-aware. HIGH is reserved for strong evidence with a clear top-vs-next margin. Visual-area, text-only, nearby-only, activity-only, arbitrary-literal, and legacy-fallback matches carry explicit risk caps and caution text.
- SourceCandidate gains optional ranking, scoreMargin, evidenceStrength, riskFlags, and caution metadata. Older persisted sessions remain compatible.
- The console "Copy Prompt" / "Send Agent" output and DetailMode.COMPACT Markdown switch to a compact `src? file:line confidence; why=tokens; risk=token` shape with a single top-level verification rule and screen-level screenshot context. PRECISE/FULL output is unchanged.
- Same-screen annotation targets that overlap (visual-area intersection, IoSA >= 0.25, or weak-label center distance <= 24dp at default density) are split into explicit overlap groups in compact handoff.
- Added project stabilization planning and documentation for the console contract, CI, onboarding, MCP maintainability, evidence quality, local security, and release readiness.
- Added CI-oriented verification scripts, contributor guidance, zero-setup MCP configuration helpers, and connected smoke diagnostics for local development.
- Improved MCP feedback console maintainability through route, session service, and JavaScript module splits while preserving existing MCP tool and console behavior.
- Added Stable Target Evidence/source-index signals and sample coverage fixtures for repeatable evidence quality checks.
- Hardened local console mutation endpoints with browser token and origin checks, and added cleanup support for known local `.fixthis` artifacts.
- Documented current release blockers, including the missing root `LICENSE` and unpublished external artifact coordinates.
- Added AGENTS.md and CLAUDE.md at repo root and a "Use with Claude Code or Codex" section in README so Claude Code and Codex users can discover the existing fixthis setup --write workflow without digging through source. Added an English translation of docs/project-overview.md as docs/project-overview.en.md.

### Changed

- Compact feedback handoff prompt v2 — replaced single-line `src?` hint with a multi-candidate `candidates:` block, added `viewport:`, `activity:`, `instance i/N`, collision and duplicate-marker notes; matcher now populates `scoreMargin`. PRECISE/FULL detail modes and JSON wire format unchanged.
- Sessions polling now silently absorbs up to 5 consecutive failures, then pauses and surfaces a "Reconnecting feedback updates…" sub-line on the connection card. Polling resumes automatically when the tab becomes visible again or the user takes any successful mutating action.
- Bulk status changes (≥6 items in a single polling tick) skip the per-item highlight effect to avoid visual noise; single-item updates highlight as before.

### Docs

- `docs/superpowers/plans/2026-05-10-mcp-handoff-workflow-completion.md` and `docs/superpowers/specs/2026-05-10-mcp-handoff-workflow-completion-design.md` amended in-place with correction notes for T21 (`delivery !== 'sent'` filter removal) — the original 3-site instruction targeted code that had already been refactored; only `preview.js:75` was actually modified at execution time.
- `CLAUDE.md` 에 "After Code Changes — Restart Console Stack" 섹션 추가. fixthis-mcp 코드 변경 후 재시작 의무 명문화. (stale-binary-detection feature)
- `CLAUDE.md` 에 "Bridge Protocol Compatibility" 섹션 추가. BridgeStatus / BridgeProtocol 시그니처 변경 PR 의 VERSION bump 규칙 명시. (BridgeProtocol.VERSION, sidekickBuildEpochMs 포함)
