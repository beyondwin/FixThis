# FixThis Architecture / SOLID Remediation Detailed Spec

Date: 2026-05-13
Status: Draft for review
Scope: repository-wide architecture, module boundaries, class responsibility, SOLID risk
Related implementation plan: [`../plans/2026-05-13-architecture-solid-remediation-implementation.md`](../plans/2026-05-13-architecture-solid-remediation-implementation.md)

## Summary

FixThis has a sound current module direction: `:fixthis-compose-core` stays pure,
the sidekick remains debug-only Android runtime, and the MCP console owns the
feedback workflow. The major architecture decisions in
`docs/architecture/adr/0001-use-clean-architecture-layering.md`,
`0002-domain-models-live-in-compose-core.md`, `0004-feedback-console-assets-as-resources.md`,
`0006-stable-target-evidence-open-source-compatibility.md`, and
`0007-feedback-console-connection-recovery.md` are still the right constraints.

The current code mostly honors dependency direction. I found no forbidden
`android`, `androidx`, MCP, CLI, Gradle, or sidekick imports in
`fixthis-compose-core/src/main`. The highest-value remaining work is not a
product rewrite; it is a set of focused splits where large orchestration classes
still carry too many reasons to change.

The strongest findings are:

1. `compose-core` domain ports and use cases exist, but the MCP session workflow
   still mutates DTOs through `FeedbackSessionStore` directly. Clean Architecture
   is partly established but not yet the active application path.
2. `FeedbackSessionStore.kt` is a 993-line state, persistence, event-log,
   replay, lifecycle, handoff, locking, and artifact-deletion class.
3. `BridgeServer.kt` is a 698-line mixture of socket server, request router,
   bridge DTOs, runtime singleton, Android environment, source-index loader, and
   screenshot reader.
4. `FixThisTools.kt` is an 869-line tool registry, tool dispatcher, resource
   dispatcher, console server lifecycle, bridge adapter, cache, argument parser,
   schema builder, package resolver, and freshness evaluator.
5. `SourceMatcher.kt` is a 578-line scoring engine with hard-coded weights,
   reason strings, confidence caps, risk flags, and duplicated caution text.
6. `GenerateFixThisSourceIndexTask.kt` mixes Gradle task IO with Kotlin regex
   scanning, XML parsing, DTO construction, and JSON writing.
7. The console JavaScript source is split, but `state.js`, `annotations.js`, and
   `rendering.js` still communicate through implicit globals, and
   `FeedbackConsoleServerTest.kt` has become a 4,736-line cross-domain test file.

## Analysis Method

Commands and checks used:

```bash
rg --files
sed -n '1,240p' docs/architecture/overview.md
sed -n '1,220p' docs/architecture/adr/*.md
rg --files fixthis-compose-core/src/main fixthis-compose-sidekick/src/main fixthis-cli/src/main fixthis-mcp/src/main fixthis-gradle-plugin/src/main sample/src/main -g '*.kt' -g '*.js' | xargs wc -l | sort -nr | head -60
rg -n "import (android|androidx|io\\.beyondwin\\.fixthis\\.(mcp|cli|gradle|compose\\.sidekick))" fixthis-compose-core/src/main -g '*.kt'
rg -n "import io\\.beyondwin\\.fixthis\\.mcp|import io\\.beyondwin\\.fixthis\\.cli" fixthis-compose-sidekick/src/main fixthis-compose-core/src/main fixthis-gradle-plugin/src/main sample/src/main -g '*.kt'
rg -n "import io\\.beyondwin\\.fixthis\\.cli" fixthis-mcp/src/main -g '*.kt'
for f in config/detekt/baseline-*.xml; do rg -o "<ID>[A-Za-z]+" "$f" | sed 's/<ID>//' | sort | uniq -c | sort -nr | head; done
```

## Current Architecture Baseline

### Intended module boundaries

Current docs define these durable boundaries:

- `:fixthis-compose-core`: pure Kotlin domain contracts, use cases, models,
  selection, formatting, and source matching.
- `:fixthis-compose-sidekick`: debug Android runtime and app-local bridge.
- `:fixthis-gradle-plugin`: debug dependency injection and source-index asset
  generation.
- `:fixthis-cli`: desktop CLI plus ADB bridge client.
- `:fixthis-mcp`: MCP server, feedback session persistence, and local browser
  console.
- `:app` / `sample`: validation sample app.

The critical invariant from ADR-0001 and ADR-0002 is that `compose-core` must not
know about MCP JSON, CLI, Android UI surfaces, or `.fixthis` layout. That
invariant currently holds by import scan.

### Current size hotspots

Main source line-count hotspots:

| File | Lines | Why it matters |
| --- | ---: | --- |
| `fixthis-mcp/src/main/resources/console/app.js` | 3,707 | Generated bundle; acceptable as output, but should not be hand-edited. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionStore.kt` | 993 | Real SRP risk; not generated. |
| `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/tools/FixThisTools.kt` | 869 | Real OCP/ISP risk; all tool changes touch one class. |
| `fixthis-mcp/src/main/console/rendering.js` | 766 | UI rendering still broad. |
| `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt` | 698 | Runtime, protocol, socket, DTO, and Android environment are together. |
| `fixthis-mcp/src/main/console/annotations.js` | 678 | Annotation flow is broad and stateful. |
| `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt` | 578 | Scoring policy and explanation generation are coupled. |
| `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt` | 531 | ADB forwarding, protocol framing, artifact copy, and package resolution are together. |
| `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/task/GenerateFixThisSourceIndexTask.kt` | 442 | Gradle task and source scanner are coupled. |

Detekt baseline confirms these are not cosmetic. `baseline-fixthis-mcp.xml`
contains 6 `LargeClass`, 10 `TooManyFunctions`, 23 `LongMethod`, 14
`LongParameterList`, and 7 `CyclomaticComplexMethod` findings. The highest
repeat offenders are `FeedbackConsoleServerTest.kt`, `FixThisTools.kt`,
`McpProtocolTest.kt`, `FeedbackSessionServiceTest.kt`, `FeedbackSessionService.kt`,
`FeedbackItemRoutes.kt`, and `FeedbackConsoleServer.kt`.

## Findings

### F1 — `compose-core` domain is present but not yet the active application path

Severity: P1

Evidence:

- `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/domain/*`
  defines `Annotation`, `Session`, `Snapshot`, repository ports, and use cases.
- `SessionDomainMappers.kt` maps `SessionDto` to domain models.
- The active MCP session path still flows through `FeedbackSessionStore`,
  `FeedbackDraftService`, and `AnnotationRepository` DTO methods.
- `rg` only finds `CreateAnnotationUseCase` and `SaveSnapshotUseCase` inside
  `compose-core` source and tests; production MCP code does not call them.

Impact:

- ADR-0001's inner-domain direction exists structurally, but application rules
  still live in MCP DTO services.
- Domain tests can pass while runtime behavior bypasses domain invariants.
- The MCP class named `AnnotationRepository` conflicts conceptually with the
  core `AnnotationRepository` port; it is actually an annotation workflow
  facade, not a repository implementation.

SOLID mapping:

- DIP is incomplete: outer module code does not yet depend on the core ports in
  the paths where annotations and snapshots are created.
- SRP is weakened because DTO services retain domain decision-making.

Recommended direction:

- Rename the MCP `AnnotationRepository` class to `AnnotationWorkflow`.
- Add MCP adapters that implement the core repository ports over
  `FeedbackSessionStore`.
- Route one narrow runtime path at a time through `CreateAnnotationUseCase` and
  `SaveSnapshotUseCase`, preserving persisted JSON field names.

### F2 — `FeedbackSessionStore` owns too many persistence and domain concerns

Severity: P1

Evidence:

- `FeedbackSessionStore.kt` is 993 lines and explicitly suppresses `LargeClass`.
- It manages current-session state, session list, session open/close, screen
  mutation, item mutation, handoff batches, write-ahead event append, replay,
  checkpoint handling, persistence read-through, and artifact deletion.
- Public methods use `synchronized(lock)` and call event log writer and
  persistence operations inside the lock.
- `deleteScreen` performs disk artifact deletion as part of the store mutation.

Impact:

- Small changes to lifecycle, replay, or item semantics risk touching the same
  file and lock scope.
- Lock-held file IO increases deadlock and latency risk once more operations
  become suspendable or run under MCP request concurrency.
- Replay code and live mutation code can drift because each event type has both
  writer payload construction and replay handling in the same class.

SOLID mapping:

- SRP violation: store, reducer, event log coordinator, replay engine, and
  artifact cleaner are separate reasons to change.
- OCP issue: adding a new event requires editing mutation code and replay
  switch in the same large class.

Recommended direction:

- Introduce pure `SessionMutation` / `SessionReducer` functions first.
- Move event sequence and append logic into `SessionEventJournal`.
- Move replay/checkpoint logic into `SessionReplayEngine`.
- Keep `FeedbackSessionStore` as a small facade that coordinates locks and
  delegates pure state transition work.

### F3 — Bridge code has protocol, runtime, and Android environment in one file

Severity: P1

Evidence:

- `BridgeServer.kt` contains `BridgeServer`, `BridgeEnvironment`, many
  `@Serializable` bridge DTOs, `FixThisBridgeRuntime`, `AndroidBridgeEnvironment`,
  source-index asset reading, screenshot PNG safety, orientation/window helpers,
  and extension functions.
- `BridgeProtocol.kt` in sidekick and `BridgeFrames` in CLI both implement
  length-prefixed frame read/write with the same 16 MB max.
- The bridge protocol version is mirrored across four files and guarded by
  `BridgeProtocolVersionSyncTest`.

Impact:

- A protocol DTO change requires opening a large Android runtime file.
- A socket-server behavior change risks accidental Android environment changes.
- Protocol framing duplicated across sidekick and CLI is protected by tests for
  version constants, but frame behavior itself has two implementations.

SOLID mapping:

- SRP violation: server, DTOs, runtime lifecycle, Android inspection, and file
  safety belong in separate units.
- DIP/OCP issue: clients cannot share a protocol package without depending on
  sidekick or duplicating code.

Recommended direction:

- First split `BridgeServer.kt` into sidekick-local files without adding a new
  module.
- Then consider a small pure JVM `:fixthis-bridge-protocol` module for frame
  encoding, request/response DTOs, `BridgeProtocolVersion`, and JSON settings.

### F4 — `FixThisTools` is a tool registry, dispatcher, resource handler, bridge cache, and console owner

Severity: P1

Evidence:

- `FixThisTools.kt` is 869 lines.
- One `when (name)` handles all MCP tools.
- The same class builds tool/resource schemas, manages latest screen/status
  caches, opens the browser console server, evaluates source freshness, parses
  JSON arguments, maps navigation inputs, wraps bridge errors, and declares the
  `FixThisBridge` interface plus CLI adapter.
- `FixThisBridge` has many methods, several default implementations, and a
  default `error("FixThis bridge does not support navigation")`, which signals
  an interface-segregation problem.

Impact:

- Adding or changing a tool modifies a central class and central test surface.
- Lightweight resource reads and expensive bridge calls are coupled through one
  cache and error wrapper.
- Tool-specific validation is hard to test without constructing the full
  `FixThisTools` object.

SOLID mapping:

- SRP violation: registry, dispatch, resources, console lifecycle, cache,
  bridge adapter, and schemas have independent reasons to change.
- OCP issue: adding a tool changes the dispatcher.
- ISP issue: fake bridges must satisfy a broad bridge interface even when a test
  only needs device listing or status.

Recommended direction:

- Create `McpToolRegistry`, `FixThisToolDispatcher`, `FixThisResourceDispatcher`,
  `BridgeResultCache`, and `ConsoleServerManager`.
- Split `FixThisBridge` into smaller ports such as `PackageResolver`,
  `DeviceBridge`, `ScreenBridge`, `NavigationBridge`, and `SourceIndexBridge`.
- Keep the public MCP tool names unchanged.

### F5 — `SourceMatcher` couples evidence extraction, scoring policy, confidence, and wording

Severity: P2

Evidence:

- `SourceMatcher.kt` has hard-coded numeric weights, string reason labels,
  confidence caps, ambiguity handling, risk flags, and caution messages.
- `SourceInterpretationFactory.kt` duplicates caution wording for risk flags.
- Reason strings such as `"selected text"`, `"legacy fallback"`, and
  `"selected testTag convention composable"` are de facto output contracts.

Impact:

- Tuning a single weight requires re-reading scoring, matching, and caution
  code.
- Adding a new evidence type requires edits across extraction, scoring,
  confidence, risk flags, markdown interpretation, and tests.
- Duplicated caution wording can drift between candidates and summaries.

SOLID mapping:

- SRP violation: scoring and presentation explanation are coupled.
- OCP issue: new evidence signals require broad edits.

Recommended direction:

- Introduce internal typed `SourceMatchReason` values with stable `wireLabel`.
- Move numeric weights to `SourceScoringPolicy`.
- Move confidence/risk/caution to `SourceConfidencePolicy`.
- Preserve `SourceCandidate.matchReasons` string output.

### F6 — Gradle source-index task mixes build integration and scanner logic

Severity: P2

Evidence:

- `GenerateFixThisSourceIndexTask.kt` combines Gradle task properties, file
  walking, Kotlin regex scanning, XML string parsing, asset DTOs, JSON encoding,
  and source-index build-info writing.
- Detekt baseline flags its `scanKotlinFile` method for complexity and length.

Impact:

- Source scanner behavior is hard to test independently of Gradle task setup.
- Scanner changes can accidentally affect Gradle incremental inputs/outputs.
- Future support for more Compose evidence will increase task complexity.

SOLID mapping:

- SRP violation between Gradle wiring and scanning.
- OCP issue for new scanner sources.

Recommended direction:

- Extract `SourceIndexGenerator`, `KotlinSourceScanner`, `XmlStringResourceScanner`,
  `KotlinStringLiteralDecoder`, and `SourceIndexAssetWriter`.
- Leave `GenerateFixThisSourceIndexTask` as Gradle property adapter and writer
  coordinator.

### F7 — Console JS is split but still uses implicit global coupling

Severity: P2

Evidence:

- Source modules live under `fixthis-mcp/src/main/console/*.js`, and
  `scripts/build-console-assets.mjs` concatenates them in a fixed order.
- `state.js` declares DOM references, global mutable state, formatting helpers,
  device constants, mutation lock logic, and error UI helpers.
- `annotations.js` and `rendering.js` are the largest source modules at 678 and
  766 lines.
- Prior docs already note order-sensitive globals; the bundler protects missing
  files but does not give import/export boundaries.

Impact:

- A module-order change can still alter runtime behavior.
- Rendering and mutation logic are hard to unit test unless functions are pure
  and parameterized.
- Browser behavior tests are largely string-inspection tests in Kotlin instead
  of small JS contract tests.

SOLID mapping:

- SRP issue in `state.js`, `annotations.js`, and `rendering.js`.
- DIP issue: logic depends directly on DOM globals instead of explicit context.

Recommended direction:

- Extract pure reducers/selectors for annotation drafts and inspector rendering
  inputs.
- Add Node tests for those pure functions.
- Keep the no-build-tool concatenation model unless a separate decision adopts
  ES modules or a bundler.

### F8 — `:fixthis-mcp` depends on `:fixthis-cli` as a host runtime library

Severity: P2

Evidence:

- `fixthis-mcp/build.gradle.kts` depends on `project(":fixthis-cli")`.
- MCP source imports `BridgeClient`, `AdbDevice`, and `fixThisJson` from CLI in
  multiple files.
- The CLI module also contains command classes and an application entrypoint.

Impact:

- MCP server depends on the CLI application module rather than a narrow host
  bridge library.
- CLI command concerns and shared desktop bridge concerns are packaged together.
- Reusing bridge logic from other tools would pull in CLI command dependencies.

SOLID mapping:

- ISP and DIP issue at module level: MCP needs a host bridge API, not the whole
  CLI application module.

Recommended direction:

- Extract a small `:fixthis-host-bridge` or `:fixthis-desktop-core` module for
  ADB facade, bridge client, frame protocol, `fixThisJson`, and device DTOs.
- Make `:fixthis-cli` and `:fixthis-mcp` depend on that shared module.

### F9 — Test files have become architecture debt

Severity: P2

Evidence:

- `FeedbackConsoleServerTest.kt` is 4,736 lines.
- It covers route dispatch, CSRF token behavior, asset loading, HTML contract
  labels, JS bundle equality, connection recovery, device selection, preview
  behavior, save conflict behavior, screenshot routes, handoff routes, and many
  test bridge fakes.
- `CompactHandoffRendererTest.kt` and `McpProtocolTest.kt` are also large.

Impact:

- Developers must load a very large test file for unrelated console route work.
- Fixture classes are repeated and deeply nested.
- Tests encode many string-inspection assertions against generated HTML/JS,
  making refactors noisy.

SOLID mapping:

- Test design mirrors production SRP problems. Large tests make it harder to
  split production classes safely.

Recommended direction:

- Split by route/contract: assets, connection, devices, preview, feedback item,
  session, handoff, security token, and HTML smoke.
- Move shared fakes to `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/fixtures/`.
- Move pure JS behavior assertions to Node tests where possible.

## What Is Not A Problem Right Now

- `compose-core` purity is intact.
- The Android app side is still status-only; the old in-app overlay module is
  retired and documented as superseded.
- Console assets are no longer a Kotlin raw string; `app.js` is generated from
  source modules and guarded by `node scripts/build-console-assets.mjs --check`.
- Event log production wiring and checkpoint-aware replay are now present in the
  post-v0.1.0 stabilization path.
- Persisted MCP field names such as `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, and `sourceCandidates` must not be renamed as part of this
  work.

## Prioritized Remediation Roadmap

### Phase 0 — Guardrails and measurement

Goal: make architecture drift visible before splitting code.

Actions:

- Add a test that asserts `compose-core` has no Android/MCP/CLI imports.
- Add a test or script that reports known source hotspots and fails only on new
  files crossing an agreed threshold.
- Add a short architecture note in `CONTRIBUTING.md` linking this spec and the
  implementation plan.

Exit criteria:

- The guard passes on the current codebase.
- New boundary violations fail locally and in CI.

### Phase 1 — Domain ports become active runtime dependencies

Goal: move from "domain exists" to "runtime uses domain ports".

Actions:

- Rename MCP `AnnotationRepository` to `AnnotationWorkflow`.
- Add MCP adapters implementing core repository ports.
- Route narrow annotation/snapshot creation paths through core use cases.
- Keep DTO wire names unchanged through `SessionDomainMappers`.

Exit criteria:

- Production MCP tests prove a saved preview round-trips through domain mappers.
- Core use case tests remain pure JVM tests.

### Phase 2 — Split `FeedbackSessionStore`

Goal: make session mutation, journaling, replay, and persistence independent.

Actions:

- Introduce pure session reducer functions.
- Move event append/sequence concerns to a journal class.
- Move replay/checkpoint logic to a replay engine.
- Move artifact deletion out of the state store.

Exit criteria:

- `FeedbackSessionStore.kt` drops below roughly 350 lines.
- Existing event-log and persistence tests pass.
- Lock scope excludes slow file deletion and most pure computation.

### Phase 3 — Split bridge runtime and protocol

Goal: isolate protocol DTOs and frame behavior from Android runtime logic.

Actions:

- Split sidekick-local `BridgeServer.kt` into models, server, runtime, Android
  environment, and screenshot/source-index helpers.
- Add a shared protocol module only after the local split is green.
- Migrate CLI frame logic to shared protocol code.

Exit criteria:

- `BridgeServer.kt` becomes a socket/request server only.
- Protocol DTO changes no longer require editing Android environment code.
- Frame read/write tests cover sidekick and CLI through one implementation.

### Phase 4 — Split MCP tools

Goal: make each MCP tool independently testable and extensible.

Actions:

- Create registry, dispatcher, resource dispatcher, bridge cache, and console
  manager classes.
- Split bridge interface into role-specific ports.
- Preserve all public tool names and response shapes.

Exit criteria:

- Adding a tool means adding one handler and registry entry, not editing a
  central `when`.
- Existing `McpProtocolTest` and tool-specific tests pass.

### Phase 5 — Split source matching policy

Goal: make source matching tuneable without changing output contracts.

Actions:

- Add typed internal reasons with stable wire labels.
- Extract scoring and confidence policies.
- Share caution text through one policy.

Exit criteria:

- `SourceMatcher.kt` becomes orchestration over smaller pure components.
- `SourceCandidate.matchReasons` strings are byte-equivalent in existing tests.

### Phase 6 — Split Gradle source-index scanner

Goal: make scanner logic testable without Gradle task wiring.

Actions:

- Extract pure scanner classes and asset writer.
- Keep task inputs/outputs unchanged.

Exit criteria:

- Gradle plugin tests pass.
- Scanner tests can instantiate scanners directly.

### Phase 7 — Console/test cleanup

Goal: reduce implicit globals and large Kotlin test coupling.

Actions:

- Extract pure console selectors/reducers.
- Move pure behavior tests to Node.
- Split `FeedbackConsoleServerTest.kt` by route and contract.

Exit criteria:

- Console JS harnesses pass.
- `FeedbackConsoleServerTest.kt` no longer acts as the only home for route,
  asset, DOM, and workflow tests.

## Validation Matrix

Run the following after any implementation slice that touches production Kotlin
or console JavaScript:

```bash
./gradlew \
  spotlessCheck \
  detekt \
  :fixthis-compose-core:test \
  :fixthis-cli:test \
  :fixthis-mcp:test \
  :fixthis-compose-sidekick:testDebugUnitTest \
  :fixthis-gradle-plugin:test \
  :app:assembleDebug \
  :fixthis-cli:installDist \
  :fixthis-mcp:installDist \
  --no-daemon

node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs

git diff --check
```

For bridge protocol changes, also run:

```bash
./gradlew :fixthis-mcp:test --tests "io.github.beyondwin.fixthis.mcp.console.BridgeProtocolVersionSyncTest"
```

For Gradle plugin scanner changes, also run:

```bash
./gradlew :fixthis-gradle-plugin:test :fixthis-gradle-plugin:functionalTest --no-daemon
```

## Non-Goals

- No release-build support.
- No View or Flutter target support.
- No cloud persistence.
- No public MCP JSON field rename.
- No public MCP tool name rename.
- No removal of the generated console bundle.
- No broad package rename.

## Implementation Decisions

1. Use `:fixthis-bridge-protocol` for a future shared wire-protocol module.
   Keep this behind the sidekick-local bridge file split so the first bridge
   slice is behavior-preserving.
2. Route annotation and snapshot creation through core use cases first. Broader
   session mutation rules move only after the MCP reducer is extracted and
   covered by store replay tests.
3. Keep console JavaScript concat-only for this remediation pass. Extract pure
   selector/reducer functions and Node tests without introducing a bundler.
