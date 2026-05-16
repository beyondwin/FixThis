# Changelog

All notable user-visible changes to FixThis are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## Versioning policy

- **MAJOR** (`X.y.z`) — breaking changes to the public Gradle plugin API,
  the persisted MCP JSON schema (`items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, `sourceCandidates`), or the CLI flag
  surface documented in [`docs/reference/cli.md`](docs/reference/cli.md).
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
- `fixthis --version` flag and `fixthis version [--json]` subcommand emit
  the CLI build version, git SHA, and bridge protocol version.
- Standardized CLI exit-code contract (0/1/2/3/4) documented in
  [`docs/reference/cli-exit-codes.md`](docs/reference/cli-exit-codes.md), with
  agent-surface failures rendered through a shared
  `<cause>` / `verify:` / `fix:` template so coding agents see a stable repair
  hint alongside every failure.

### Changed
- `fixthis doctor` text output now echoes the remediation hint
  (`  ↳ fix: <fix>`) under every FAIL check, matching the structured JSON
  output's `remediation` field.
- `fixthis setup --write --dry-run` now prints a privacy-preserving diff of
  only the added/changed entries within `mcpServers` instead of the full
  merged config, capped at a 4 KiB byte budget so unrelated surrounding
  config can't leak into agent logs. Pass `--full-diff` to disable the
  budget when an operator needs to inspect the complete planned write (the
  flag prints a warning that surrounding context may leak; avoid in agent
  logs).

### Documentation
- Updated release and getting-started docs to reflect the post-v0.2.3 channel
  state: Homebrew is available at `beyondwin/fixthis/fixthis`, the npm wrapper
  is available at `@beyondwin/fixthis`, and the MCP Registry entry is
  `io.github.beyondwin/fixthis`.

### Build / performance
- Console bundle minified via esbuild + topological-sort source resolution
  (`// @requires` directives). Raw 275 KiB → 160 KiB / gzip 55 KiB → 37 KiB.
  External source map; build aborts on size budget overflow or missing
  contract symbol.
- Added a reproducible build/test performance measurement harness under
  `scripts/perf/` (scenario-driven `bench.mjs` orchestrator, Gradle profile
  parser, aggregator, `compare-perf.mjs` regression gate, env fingerprinting)
  with a committed baseline at `docs/perf/baseline-2026-05-16.json`, nightly +
  manual `perf-report` workflow, and `CONTRIBUTING.md` documentation of the
  measurement loop. The first proof-of-value `-Xmx` tuning attempt was
  empirically rejected — see
  [`docs/perf/evidence/2026-05-16-xmx-tuning-negative.md`](docs/perf/evidence/2026-05-16-xmx-tuning-negative.md)
  for the negative-outcome evidence.

## [0.2.3] - 2026-05-15

### Fixed

- **Verified public install coordinates (release, 2026-05-15):**
  - Maven Central publication now includes the Gradle plugin implementation and
    plugin marker publication in addition to compose core and sidekick.
  - The Maven Central workflow now uses the Central Portal Publisher API bundle
    upload path and waits for the deployment to reach `PUBLISHED`.
  - Public install docs, CLI defaults, npm wrapper metadata, and MCP Registry
    metadata now point agents at `0.2.3`.

## [0.2.2] - 2026-05-15

### Fixed

- **Maven Central publish automation (ci, 2026-05-15):**
  - The Maven Central workflow now promotes the OSSRH staging compatibility
    repository to Central Portal automatically after uploading artifacts.
  - Added a manual staging-promotion workflow for recovery when uploads reach
    OSSRH staging but are not yet visible from Maven Central.

## [0.2.1] - 2026-05-15

### Fixed

- **Agent-first public install path (cli/gradle-plugin/docs, 2026-05-15):**
  - Updated generated `.fixthis/agent-setup.*` handoff files so agents continue
    with `./gradlew fixthisSetup`, `fixthis init --agent --project-dir .`, and
    `fixthis doctor --project-dir . --json` instead of re-running
    `fixthis install-agent`.
  - Updated the Gradle plugin's default sidekick runtime version and CLI
    installer default to the current public patch release.
  - Refreshed public docs from source/composite-build setup to the published
    GitHub Release + Gradle Plugin Portal + Maven Central flow.

### Added

- **Agent-first Gradle setup task (gradle-plugin/docs, 2026-05-15):**
  - The Compose Gradle plugin now registers `fixthisSetup` for the standard
    debug variant and variant-specific setup tasks for flavored debug builds.
    The task writes `.fixthis/project.json`, `.fixthis/agent-setup.md`, and
    `.fixthis/mcp.json.template` so agents can run `fixthis init --agent --project-dir .`
    and `fixthis doctor --project-dir .` without asking for the package name.
  - Added pasteable `AGENTS.md` / `CLAUDE.md` install instructions for external
    Android repos, centered on `./gradlew fixthisSetup` rather than a
    human-first README flow.

- **Agent-first CLI init (cli/docs, 2026-05-15):**
  - `fixthis init --agent` and the `fixthis install-agent` alias now write
    project-scoped agent handoff files under `.fixthis/`, including a
    machine-readable `agent-setup.json` and MCP template for agent discovery.
  - `fixthis install-agent` now patches the detected Android app module with
    `io.github.beyondwin.fixthis.compose` by default, supports `--dry-run` for preview,
    and offers `--skip-gradle-plugin` for repos that already applied the plugin.
  - `fixthis doctor --json` now emits stable check names, status, messages, and
    remediation hints so coding agents can parse failures and take the next
    repair step.

- **Console session/preview sync hardening — session-scoped SSE, explicit-session mutation events, partial draft handoff, and deleted-session cleanup (console/mcp, 2026-05-15):**
  - `session-updated` and `preview-ready` SSE payloads now carry top-level
    `sessionId`; the browser applies them to detail/preview state only when
    that session is active, while the initial `snapshot` event remains
    authoritative.
  - Legacy `POST /api/items` now emits the explicitly mutated session instead
    of the current session, so explicit-session item creation cannot refresh
    the wrong detail pane.
  - `Copy Prompt` / `Save to MCP` can persist the subset of pending draft
    annotations that have written comments. Residual pin-only annotations stay
    browser-local for Copy Prompt and are intentionally discarded for Save to
    MCP.
  - Deleting a feedback session clears its browser-local draft recovery,
    including schema-v2 DraftWorkspace entries and the legacy
    `fixthis.pending.<sessionId>` mirror.
  - Draft batch persistence is idempotent across retries. `POST /api/items/batch`
    deduplicates by browser `workspaceId` + `draftItemId`, treats full duplicate
    saves as a no-op without appending event-log entries, and reuses the
    existing evidence screen when a later retry contains a mix of already-saved
    and new draft items. Legacy saved items without client draft ids are still
    deduplicated on the same screen by target type, rounded bounds, node uid,
    and non-blank comment.
  - Documented in
    [`docs/architecture/console-state-sync-design.md`](docs/architecture/console-state-sync-design.md),
    [`docs/guides/feedback-console-tour.md`](docs/guides/feedback-console-tour.md),
    [`docs/guides/agents.md`](docs/guides/agents.md),
    [`docs/reference/mcp-tools.md`](docs/reference/mcp-tools.md),
    [`docs/reference/privacy.md`](docs/reference/privacy.md), and
    [`docs/reference/feedback-console-contract.md`](docs/reference/feedback-console-contract.md).

- **Agent-first setup command (cli/docs, 2026-05-15):**
  - New `fixthis init` command wraps `fixthis setup --write` with agent-first
    defaults for Claude Code and Codex MCP registration.
  - CLI package resolution now falls back from `.fixthis/project.json` to a
    unique Android `applicationId` found in Gradle `build.gradle(.kts)` files,
    so agents can configure MCP from a normal Android repo without asking the
    user for the package name first.

- **Agent-first CLI/MCP release package (scripts/ci/docs, 2026-05-15):**
  - Added `scripts/package-cli-release.sh` to bundle the desktop `fixthis` CLI
    and `fixthis-mcp` server into a sibling-layout GitHub Release tarball.
  - Added `scripts/install-fixthis.sh` so agents can install that package,
    link `fixthis` into a local bin directory, and optionally run
    `fixthis init` from an Android app repository.
  - Added a manual/tagged `Release CLI/MCP Package` workflow plus Node tests
    for the package and install scripts.

- **Target reliability handoffs — confidence + warning metadata for agents (core/mcp/console, 2026-05-15):**
  - Persisted feedback items can now carry optional `targetReliability`
    metadata alongside `targetEvidence`. The confidence level (`HIGH`,
    `MEDIUM`, `LOW`, or `UNKNOWN`) is derived from semantic coverage,
    source-candidate strength/margin, source-index staleness, save-time screen
    fingerprint state, and redaction constraints.
  - Compact Markdown handoffs now emit `targetConfidence=<level>` plus
    `warning:` lines when reliability metadata is present. Warnings cover
    visual-area-only selections, missing meaningful Compose targets, possible
    AndroidView/WebView interop, close source-candidate margins, stale source
    indexes, forced screen mismatches, missing fingerprints, and sensitive text
    redaction.
  - The browser console shows reliability badges on annotation rows and counts
    warnings in prompt readiness without blocking `Copy Prompt` or
    `Save to MCP`.
  - Documented in
    [`docs/guides/agents.md`](docs/guides/agents.md),
    [`docs/reference/mcp-tools.md`](docs/reference/mcp-tools.md),
    [`docs/reference/output-schema.md`](docs/reference/output-schema.md), and
    [`docs/reference/feedback-console-contract.md`](docs/reference/feedback-console-contract.md).

- **Console harness automation — shared fake-bridge fixture, scenario matrix driver, nightly CI (console, 2026-05-14):**
  - Extracted a shared `scripts/console-fixture/fakeBridgeServer.mjs` module
    exposing `startFakeBridge({scenario})` plus a scenario map (happy-path,
    network-outage, slow-handoff, multi-tab, blocked-welcome). Three new
    scenario modules under `scripts/console-fixture/scenarios/` add a
    first-class `runScenario({scenario, overrides})` config-injection API.
    Blocked and responsive-stress harnesses now delegate to the shared
    fixture; `scripts/console-blocked-harness.mjs` gains a
    `--non-interactive` flag (and `FIXTHIS_BLOCKED_NON_INTERACTIVE=1` env).
  - New `scripts/console-harness.mjs` matrix driver with pure
    `parseArgs` / `selectScenarios` / `emitJunit` helpers, JUnit XML output
    to `output/playwright/results.xml`, and a `console:harness` npm script.
    Network-outage and slow-handoff now run as real browser checks against the
    fake bridge fixture, using stable console `data-testid` selectors and
    route-level outage / delayed-handoff behavior instead of skipped
    `@blocked-pending-impl` placeholders.
  - New `.github/workflows/console-harness-nightly.yml` runs the matrix on
    `schedule` + `workflow_dispatch` only (no `pull_request` trigger, so
    PR latency is unchanged). The existing `console-js` CI job now also
    runs `fakeBridgeServer-test.mjs`, `scenarios-test.mjs`, and
    `console-harness.test.mjs`. Documented in `CONTRIBUTING.md`
    under "Console Harness".

- **Setup error diagnostics — categorized merge failures + `--verbose` (cli, 2026-05-14):**
  - `fixthis setup --write` now classifies merge failures into five
    categories (`MALFORMED_JSON`, `MALFORMED_MCPSERVERS_SHAPE`,
    `MALFORMED_TOML`, `FILESYSTEM_ERROR`, `UNKNOWN`) and prints a
    multi-line error including the full cause chain and a per-category
    `Fix:` recommendation.
  - New `--verbose` / `-v` flag on `fixthis setup` opts into a full
    Java stack trace (suppresses the terse `Re-run with --verbose` hint).
    Stack-trace output is routed through a new internal
    `SetupErrorRedactor` that masks API keys, tokens, secrets, bearer
    headers, and `/Users/<user>` / `/home/<user>` paths before printing.
  - Cause chain renderer is depth-limited (8) and cycle-safe
    (IdentityHashMap + self-reference guard).
  - Documented in
    [`docs/reference/cli.md`](docs/reference/cli.md) and
    [`docs/guides/troubleshooting.md`](docs/guides/troubleshooting.md);
    supersedes the cause-preservation change from the 2026-05-09 polish
    spec (which is still in effect for changes 2/3/4).
  - 16 new unit tests (`SetupErrorRedactorTest` ×7,
    `SetupErrorRenderingTest` ×8 — 1 RED-phase from Task 1 + 7 added in
    Task 3, `MainPrintTest` ×2) plus 2 SetupCommand integration tests.
    Module total: 78 tests passing, 0 failures.

- **DX documentation consistency — SSOT topic ownership, npm/runner unification, drift detector (docs, 2026-05-14):**
  - New `## Prerequisites` section in `CONTRIBUTING.md` documents JDK 21,
    Android SDK + ADB, Node.js 20.0.0 minimum (enforced via
    `package.json` `engines` + `.npmrc engine-strict=true`), and
    Playwright-bundled Chromium with its macOS 11+ / Ubuntu 20.04+ host
    floor.
  - New `## Console Inner Loop` section in `CONTRIBUTING.md` documents
    `scripts/restart-console.sh` (Kotlin server restart loop, port-free,
    `--with-app` / `--dry-run` / `--port` flags) and
    `scripts/fixthis-console-dev.sh` (JS-only hot-reload loop using
    `--console-assets-dir` and auto-opened browser). `CLAUDE.md`'s
    "Restart loop after Kotlin changes" pointer now links to the new
    anchor `CONTRIBUTING.md#console-inner-loop`.
  - `package.json` gains `engines.node: ">=20.0.0"`, five new scripts
    (`console:activity:test`, `console:preview:test`, `console:session:test`,
    `console:test:fast`, `console:test:all`) plus `github-slugger` devDep.
    Existing per-area `console:*:test` scripts now route through the new
    `scripts/run-console-tests.mjs` runner backed by the single-source-of-truth
    group catalog at `scripts/console-tests.json`. `console:smoke`,
    `console:harness`, `console:responsive:stress`, `console:fsm:test`,
    and `console:build:test` remain on their existing entry points.
  - `CONTRIBUTING.md` Focused Test Loops + Required Local Checks now use
    `npm run console:test:fast`, `npm run console:session:test`, and a
    single `node scripts/run-console-tests.mjs availability pending beforeunload undo activity preview draft session`
    invocation — eliminating the previous 15-file `node --test` mirror.
  - `README.md` gains a Node 20+ badge and a cross-link to `AGENTS.md` for
    MCP-aware agents. `AGENTS.md` gains a banner blockquote prompting
    sequential reads, a Node.js 20.0+ LTS bullet in Prerequisites linking
    to CONTRIBUTING's Prerequisites table, and a cross-link to README's
    Quick Start for human contributors.
  - New `scripts/check-doc-consistency.mjs` drift detector enforces six
    rules: (R1) every `console:*` script in `package.json` is referenced
    in `CONTRIBUTING.md`; (R2) every `npm run console:*` in CONTRIBUTING
    resolves to a real script; (R3) bidirectional README ↔ AGENTS
    cross-links; (R4) both contributor scripts named in CONTRIBUTING;
    (R5) `engines.node` present; (R6) every `*.md#anchor` link in DX
    docs resolves to a real heading via `github-slugger`. Exit 0 on
    pass, 1 with `FAIL Rx.…` on drift. Added to CONTRIBUTING's Required
    Local Checks block and as a required CI step in the `console-js`
    workflow job (with a new `npm ci` install step).
  - Plan + spec at
    `docs/superpowers/{plans,specs}/2026-05-14-dx-docs-consistency-*.md`;
    final verification: `node scripts/check-doc-consistency.mjs` PASS R1–R6,
    `npm run console:test:fast` 68/68, `npm run console:test:all` 107/107.

### Changed

- **Console state machines expanded — Connection, Preview, Polling, Tool-mode FSMs (console, 2026-05-14):**
  - Four new sub-state-machines extract ~30 module-level `let` declarations
    from `fixthis-mcp/src/main/console/state.js` into pure reducers + use
    cases over ports + browser adapters. Each FSM is a 3-layer triangle
    matching the already-shipped draft workspace FSM.
  - **Connection FSM** (`connectionFsm.js`): 5 lifecycle states
    (`DISCONNECTED` → `LAUNCHING` → `READY` → `BLOCKED` ⇄ `UNAVAILABLE`).
    Owns `state.connection.*`, `heartbeatTimer`, `heartbeatPolling`,
    `lastHeartbeatError`. Heartbeat failure ≥ 3 consecutive ticks
    degrades `READY` → `DISCONNECTED`. Added `STATUS_RECEIVED` /
    `POLLING_PAUSED_CHANGED` actions beyond the original spec so every
    `state.connection.*` write goes through the FSM.
  - **Preview FSM** (`previewFsm.js`): 5 lifecycle states (`IDLE` /
    `REQUESTING` / `READY` / `STALE` / `ERROR`). Owns the preview request
    generation counters and `previewZoom`. Race fence drops
    `REQUEST_SUCCEEDED` / `REQUEST_FAILED` when either generation diverges;
    zoom clamp [0.5, 2].
  - **Polling FSM** (`pollingFsm.js`): 4 lifecycle states (`STOPPED` /
    `POLLING_ACTIVE` / `POLLING_BACKOFF` / `POLLING_PAUSED`). Owns the
    sessions/session etags, mutation lock counters, and poll-failure
    counter. Top-level identifiers (`pollSessionsTick`,
    `startSessionsPolling`, `withMutationLock`,
    `MaxConsecutivePollFailures`) preserved as thin wrappers.
  - **Tool-mode FSM** (`toolModeFsm.js`): 3 modes (`SELECT` /
    `ANNOTATE_IDLE` / `ANNOTATE_DRAGGING`) — pure synchronous FSM, no
    browser adapter.
  - **`consoleApp.js`** boot factory aggregates all four FSMs; state.js
    module-level `let` count drops from ~35 to 7 (only draft-FSM holders
    remain). Wire protocol unchanged; all HTTP routes and persisted JSON
    shapes preserved. See FSM table in
    [`docs/reference/feedback-console-contract.md`](docs/reference/feedback-console-contract.md).

- **Console transport/session resilience (console/mcp, 2026-05-15):**
  - Browser request cancellation is now treated as a normal local-transport
    condition. Console routes route response writes through `ConsoleHttp`
    helpers so `connection reset`, `broken pipe`, closed streams, and
    fixed-length close errors close quietly instead of being logged as server
    defects.
  - Preview state is cleared only when the active session ownership boundary
    changes, such as server-driven current-session changes, user session
    switches, device switches, or draft context resets. Async preview
    completions must still match both `sessionId` and preview context
    generation before they render.
  - `/api/events` computes the initial SSE snapshot before streaming headers
    are committed. Snapshot failures remain normal HTTP errors; disconnects
    after the stream opens close the subscription quietly.
  - Documented in
    [`docs/architecture/console-state-sync-design.md`](docs/architecture/console-state-sync-design.md)
    and
    [`docs/superpowers/specs/2026-05-15-console-transport-session-resilience-detailed-spec.md`](docs/superpowers/specs/2026-05-15-console-transport-session-resilience-detailed-spec.md).

- **Bridge server concurrency model (sidekick, 2026-05-14):**
  - `BridgeServer.start()` / `stop()` are now `suspend fun` serialised by a
    `kotlinx.coroutines.sync.Mutex`. The two legacy `@Volatile` lifecycle
    fields (`serverSocket` / `resolvedName`) are replaced by a
    `MutableStateFlow<BridgeServerState>` exposed as a read-only
    `state: StateFlow<BridgeServerState>`. `resolvedSocketName()` becomes
    a derived read.
  - `FixThisBridgeRuntime.start(...)` now launches on
    `ProcessLifecycleOwner.lifecycleScope.launch(Dispatchers.IO)` so the
    main thread is never blocked on a mutex or socket bind. `runBlocking`
    is restricted to `stopForTest()`.
  - `stop()` `cancelAndJoin`s the accept loop and the scope's parent Job
    inside `withTimeoutOrNull(5.seconds)`; a hung handler logs and leaks
    instead of hanging shutdown. `BridgeServer` is single-use after
    `stop()`; restart requires a fresh instance.
  - `AndroidBridgeEnvironment.currentActivity` is atomicized as
    `StateFlow<WeakReference<Activity>?>` with compare-and-clear semantics.
  - Wire protocol unchanged (`BridgeProtocol.VERSION` stays `1.3`).
    See ADR
    [`docs/architecture/adr/2026-05-14-bridge-server-concurrency.md`](docs/architecture/adr/2026-05-14-bridge-server-concurrency.md).

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
    contenteditable). A 5-second toast with an Undo button appears after
    each delete.
- **Screen integrity fingerprinting — Phase B (SIF-1..SIF-6):**
  - `Snapshot` (core) and `SnapshotDto` (wire) gain nullable
    `orientation`, `widthPx`, `heightPx`, `densityDpi`, `windowMode`,
    `systemUiVisible`, `systemUiKind`, and `fingerprint` fields. Legacy
    payloads remain decodable — all new fields default `null`.
  - Bridge protocol bumps `1.2` → `1.3`, kept in lockstep across the four
    sync sites (`BridgeProtocol.VERSION`, console `MinimumSupportedProtocolVersion`,
    CLI `BridgeProtocolVersion`, MCP `ServerVersionRoutes`).
    `BridgeProtocolVersionSyncTest` enforces equality at CI time.
  - `SnapshotFingerprint.compute(snapshot)` derives a 16-char SHA-256
    prefix over `activityName|orientation|widthPx×heightPx@densityDpi|windowMode|systemUiKind`;
    null inputs short-circuit to `null` so empty captures do not produce
    a meaningless hash.
  - Sidekick `captureScreenSnapshot()` now records `orientation`,
    display metrics, `windowMode` (`PIP`/`SPLIT_SCREEN`/`FULLSCREEN`),
    and `systemUiKind` (`ime`/`permission_dialog`/`notification_shade`)
    on every capture. `SystemUiDetector` exposes pure
    `detectImeKind` / `detectFocusKind` helpers so the branch logic is
    unit-testable without Robolectric; the ADB sideband that supplies
    `currentFocusOutput` is wired in a later phase.
  - `FeedbackDraftService.savePreviewFeedbackItems()` accepts
    `frozenFingerprint`, `currentFingerprint`, and
    `forceMismatchOverride` (all default null/false). When both
    fingerprints are present and differ without override, the service
    throws `ScreenFingerprintMismatch` which the HTTP layer maps to
    **409 Conflict** with body `{"error":"screen_fingerprint_mismatch",
    "frozenFingerprint":"…","currentFingerprint":"…"}`. The console
    shows a three-way prompt (re-capture / force-save / cancel) and
    retries with `forceMismatchOverride: true` on force-save.
  - Preview staleness now reflects time and connection state: the new
    `previewStaleness.js` helper marks `state.preview.stale = true`
    when the freeze is >30s old or the bridge is not in the `connected`
    state. The existing stale banner picks this up automatically.
  - Activity drift detection: while a multi-pin pending flow is open,
    each new pin re-checks the foreground activity and surfaces an
    inline "Activity changed during freeze" warning with a "Start new
    freeze" action when the user has navigated away from the freeze's
    activity.
- **Responsive error and agent feedback UX:**
  - The console now has a dedicated global status surface for error,
    warning, success, and info messages instead of relying on a narrow
    inspector footer paragraph.
  - Agent-driven item states render distinctly in the browser console:
    `in_progress` shows the agent note, `needs_clarification` remains
    editable and asks the user to update/re-save, `wont_fix` shows the
    agent's reason, and `resolved` shows the completion summary.
  - A Playwright responsive stress harness covers long errors, staleness
    banners, activity-drift warnings, agent summaries, and 390px-wide
    layout regression cases.

### Changed

- Project license changed to MIT; README, NOTICE, package metadata, and
  release-readiness docs now reflect the MIT license.
- Architecture cleanup split large orchestration paths into smaller, tested
  boundaries. MCP tools now route through explicit registry/dispatcher/resource
  collaborators; feedback session replay, event journaling, reducers, and
  store persistence are separated; sidekick bridge runtime, screenshot reads,
  source-index reads, and Gradle source scanners are isolated behind focused
  files; architecture hotspot tests enforce those boundaries.
- CI now treats the generated feedback-console bundle and pure JavaScript
  harnesses as first-class checks. `scripts/build-console-assets.mjs --check`
  normalizes the dynamic build header before comparing `app.js`, so CI catches
  real stale bundles without failing only because the timestamp or git SHA
  changed.
- Contributor build loops are faster and more deterministic. The local Gradle
  build cache is enabled by default, source-index generation is cacheable,
  sidekick build metadata avoids unnecessary Kotlin recompilation on unchanged
  inputs, and `scripts/bootstrap-mcp.sh` uses build/configuration cache flags
  while building the local CLI/MCP distributions.
- CI now runs console JavaScript checks in a separate job from Gradle
  verification. Stale console assets fail before the heavier Android/Kotlin
  matrix finishes, and the compatibility matrix can exercise AGP, Kotlin, and
  Compose lower bounds through explicit version overrides.
- Detekt is applied only for build/check/detekt task requests. This keeps
  `./gradlew help --warning-mode all` free of the Gradle 10
  `ReportingExtension.file(String)` warning while preserving normal
  `detekt`, `check`, and build-time static analysis.

### Removed

### Fixed

- MCP production sessions now use the same append-only event log path that the
  annotation lifecycle design introduced. Feedback session replay is
  checkpoint-aware, compaction archives old events only after a durable
  checkpoint is written, and corrupt checkpoint state is skipped instead of
  silently replaying stale archived mutations.
- Snapshot integrity metadata now survives the full MCP boundary. Captured
  screens preserve orientation, dimensions, density, window mode, system UI
  state, and fingerprint fields through bridge payload parsing, DTO/domain
  mapping, persisted sessions, and feedback handoff reads.
- Saving a frozen preview now compares the frozen screen fingerprint with a
  lightweight live capture. If both fingerprints exist and differ, the console
  receives `409 screen_fingerprint_mismatch` and asks the user to re-capture,
  force-save, or cancel before persisted evidence is written.
- Browser recovery for unsaved pending annotations now stores a schema-v1
  envelope with `previewId`, frozen screen metadata, screenshot URL, frozen
  timestamp, and items. On reload or session reattach, the console shows an
  explicit Recover / Recapture / Discard choice instead of silently exposing
  stale pending rows.
- Browser recovery now routes those pending annotations through a schema-v2
  DraftWorkspace state machine. Drafts carry immutable freeze context,
  lifecycle, revision, and undo/redo history; legacy schema-v1 mirrors remain
  readable and migrate into the new recovery shape.
- Saved previews, screenshot artifact routes, draft mutations, and undo/redo
  history are now scoped to the feedback session that created them. Switching
  sessions while a save or preview request is in flight no longer leaks
  overlays, comments, or screenshot URLs across sessions, and persisted
  annotation numbers remain monotonic after deletes or reopens.
- The Studio console no longer forces horizontal overflow at narrow widths.
  Long connection details, stale-binary diagnostics, activity-drift messages,
  and agent summaries wrap inside their containers; the sample app rows and
  sidekick status pill also constrain text on small screens.

## [0.1.0] - 2026-05-11

### Added

- CI hardening: documented the required PR checks contract in `CONTRIBUTING.md` (table mapping each check → workflow → source task → status) and added `docs/contributing/required-checks.md` as a readiness tracker with one row per workflow and a "green for 7 days?" column. **Follow-up (deferred):** the actual branch-protection flip in GitHub repo settings is a maintainer admin action and is gated on each "Pending" row meeting its observation window (7 consecutive green for PR-time checks, 14 consecutive green for the nightly connected-tests workflow, 1 week stable for the nightly compatibility matrix). (CI-5 from ci-cd-hardening.)
- CI hardening: nightly connected (instrumented) Android-tests workflow lands at `.github/workflows/connected-tests.yml`. It runs on `schedule: '0 4 * * *'` and `workflow_dispatch` only — intentionally **not** on `pull_request` — and uses `reactivecircus/android-emulator-runner@v2` to boot an emulator and execute `./gradlew connectedDebugAndroidTest --no-daemon`. The test step is `continue-on-error: true` so a red nightly is informational and does not gate other workflows. Flake-triage process and the (currently empty) table of temporarily-disabled tests are documented in `docs/contributing/connected-tests.md`. **Follow-up (deferred):** promotion of the nightly to a required PR check once 14 consecutive green runs are observed and the flake-triage process has stabilised the suite. (CI-4 from ci-cd-hardening.)
- CI hardening: Dependabot config (`.github/dependabot.yml`) covering `gradle`, `github-actions`, and `npm` ecosystems on a weekly cadence with minor+patch update grouping; CodeQL analysis workflow (`.github/workflows/codeql.yml`) for `java-kotlin` and `javascript-typescript` running on push/PR + weekly cron; gradle-version-check workflow (`.github/workflows/gradle-version-check.yml`) running the `com.github.ben-manes.versions` plugin (`dependencyUpdates` task) weekly and uploading the report as an artifact. **Follow-ups (deferred):** (a) observe Dependabot opens its first PR within 7 days, (b) observe CodeQL results land in the GitHub Security tab, (c) wire `gradle-version-check` to auto-open an issue or fail on a non-test dependency ≥ 1 minor version behind (today the report is artifact-only). (CI-3 from ci-cd-hardening.)
- Nightly compatibility matrix workflow lands (`.github/workflows/nightly-compat.yml`) and the supported AGP/Kotlin/Compose window is documented in `docs/reference/compatibility.md`. Promotion of the nightly to a required CI check is a follow-up after 1 week of stable runs. (BR-4 from build-release-hardening.)
- Build hardening: sidekick now ships `consumer-rules.pro` keeping the Compose reflection entry points (`RootForTest`, `SemanticsOwner`/`SemanticsOwnerKt`, `AndroidComposeView`) plus the public bridge surface and `FixThisInitializer`. A new `:fixthis-gradle-plugin:functionalTest` task structurally validates the rules. Consumer release builds with R8 minify will no longer strip the symbols the sidekick reflects on. (BR-2 from build-release-hardening.)
- Build hardening: the sidekick's `androidx.startup` initializer entry now lives in `src/debug/AndroidManifest.xml` instead of `src/main/AndroidManifest.xml`. A consumer app that accidentally promotes the sidekick to `implementation` will no longer have the bridge attached at process start in release builds. The runtime `FLAG_DEBUGGABLE` early-return is preserved as defence-in-depth and now logs a single `Log.w("FixThisInitializer", …)` warning if reached in a non-debuggable build. A new `ReleaseGuardTest` structurally asserts the manifest split + log presence. (BR-1 from build-release-hardening.)
- Security: published `docs/reference/threat-model.md` documenting FixThis's assets, trust boundaries, in/out-of-scope adversaries, current mitigations (debug-only manifest, FLAG_DEBUGGABLE runtime guard, LocalSocket transport, 127.0.0.1 + Origin + X-FixThis-Console-Token on the console), and open gaps. Linked from README, `docs/index.md`, and `SECURITY.md`. (SEC-1 from security-hardening.)
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

- `BridgeProtocol.VERSION` "1.0" → "1.1" (minor bump for the added `sidekickBuildEpochMs` field). When an old sample APK connects to the new console, a red banner asks the user to reinstall.
- `fixthis setup --write` output now labels each written config with its scope: `(project-local)` for Claude (project's `.claude/settings.json`) and `(global)` for Codex (user's `~/.codex/config.toml`). Affects both the success line and the `--dry-run` `Target:` line.

### Added

- `scripts/restart-console.sh` helper. Stops stale `fixthis-mcp`/CLI console processes and wraps the incremental Gradle build plus restart into one command. `--with-app` also reinstalls the sample APK; `--dry-run` previews the actions.
- Automatic staleness detection at console boot (staleness banner). If the `fixthis-mcp` JAR and sidekick build differ by more than 5 minutes, the console shows a dismissible banner. Adds the `/api/server-version` endpoint (server build epoch + git sha + bridge protocol version) and the `BridgeStatus.sidekickBuildEpochMs` field.
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
- Added the "After Code Changes — Restart Console Stack" section to `CLAUDE.md`, making restarts mandatory after `fixthis-mcp` code changes. (stale-binary-detection feature)
- Added the "Bridge Protocol Compatibility" section to `CLAUDE.md`, documenting the VERSION bump rule for PRs that change `BridgeStatus` / `BridgeProtocol` signatures. (Includes `BridgeProtocol.VERSION` and `sidekickBuildEpochMs`.)
