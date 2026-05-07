# Project Improvement Stabilization Detailed Design

**Date:** 2026-05-08
**Status:** Implemented in `codex/project-improvement-stabilization`; Task 14 final verification recorded in the implementation plan
**Source proposal:** `docs/project-improvement-proposals-2026-05-07.md`
**Implementation plan:** `docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md`
**Primary modules:** `fixthis-mcp`, `fixthis-cli`, `fixthis-gradle-plugin`, `sample`, project docs and CI

## Purpose

This design turns the repository-wide improvement proposal into an actionable
technical design. The intent is to stabilize the current V1 product, not to
rewrite it. FixThis already has a working MCP-first feedback console, persisted
local sessions, Stable Target Evidence, debug-only sidekick bridge, and passing
local unit/build checks. The next work should make that system easier to trust,
ship, and extend.

Implementation result: the stabilization branch landed the planned contract,
verification, onboarding, smoke, MCP modularization, Source Index v2, sample
fixture, local mutation guard, artifact cleanup, release-readiness, and MCP
compatibility slices. External release is still blocked until a root `LICENSE`
is selected and external artifacts are published.

The design has four ordered outcomes:

1. Make the feedback console product contract explicit and consistent.
2. Make local and PR verification repeatable.
3. Reduce the MCP console implementation risk without changing public behavior.
4. Prepare evidence quality, onboarding, security, and release docs for wider
   adoption.

## Source-Of-Truth Order

When implementation details conflict, use this order:

1. Existing public compatibility guarantees in README, PRD, privacy docs, MCP
   docs, and ADRs.
2. Current runtime behavior in `fixthis-mcp`, `fixthis-cli`,
   `fixthis-compose-sidekick`, and `fixthis-gradle-plugin`.
3. This detailed design.
4. The improvement proposal summary document.

This design intentionally prefers small compatibility-preserving changes over
large rewrites. Existing MCP tool names, HTTP endpoint paths, persisted session
JSON fields, debug-only scope, and local-first privacy guarantees remain stable
unless a later ADR explicitly changes them.

## Goals

- Align console labels, docs, tests, and agent-facing language around one
  product contract.
- Add a minimal CI and local contribution baseline that mirrors the checks
  already proven during analysis.
- Remove noisy project test deprecation warnings so future test output carries
  useful signal.
- Make `fixthis setup` capable of writing deterministic MCP client config while
  keeping the current stdout JSON behavior for scripts.
- Add an explicit connected-device smoke path with clear skip/fail categories.
- Split the MCP session service, console server routes, and browser assets by
  responsibility while preserving external behavior.
- Improve Source Index confidence semantics without breaking existing source
  index JSON readers.
- Add operational controls for local artifacts and local browser mutation
  endpoints.
- Define the minimum release-readiness docs and compatibility matrix before
  describing FixThis as externally consumable.

## Non-Goals

- No frontend framework migration.
- No production feedback collection mode.
- No external AI API calls from the console.
- No required Kotlin compiler plugin.
- No broad support beyond Android Jetpack Compose debug builds.
- No SDK migration for MCP protocol solely for novelty.
- No required connected-device CI until a reliable device or emulator runner is
  available.

## Key Product Decision

The shipped Studio console vocabulary becomes the canonical user-facing
contract:

- Top bar prompt actions: `Copy Prompt`, `Send Agent`
- Canvas tools: `Select`, `Annotate`
- Inspector actions: `Clear Selection`, `Exit Annotate`, `Add annotation`,
  `Clear Draft`
- Device controls: `Refresh devices`, `Clear selection`
- Connection card actions: `Start`, `Capture screen`, `Open app`,
  `Reconnect`, `Choose device`, `Try again`

The older `Add`, `Add to Pending`, `Save`, `Copy`, and `Send` wording should be
treated as stale documentation unless a future product decision explicitly
returns to that vocabulary. This decision minimizes implementation churn because
it keeps the labels already present in `index.html`.

## High-Level Architecture

The architecture remains MCP-console-first:

```text
Codex / Claude Code / local user
        |
        | stdio MCP or local console command
        v
fixthis mcp / fixthis console
        |
        | localhost browser console + ADB bridge client
        v
debug app sidekick
        |
        | Compose semantics, screenshots, navigation, status
        v
running Android Compose debug app
```

Improvement work is split into independent but ordered workstreams:

```text
Contract stabilization
        |
        v
Verification + onboarding baseline
        |
        v
MCP service/server/browser modularization
        |
        v
Evidence quality + security + release readiness
```

Each workstream must leave existing public behavior stable and add focused tests
before relying on broader refactors.

## Workstream 1: Feedback Console Product Contract

### Problem

Current docs describe an `Add` / `Save` / `Copy` / `Send` flow, while the
shipped browser console exposes `Annotate`, `Add annotation`, `Copy Prompt`, and
`Send Agent`. This makes docs, tests, and implementation plans optimize
different workflows.

### Design

Add `docs/feedback-console-contract.md` as the single human-readable contract
for the console. It should be short and normative, not a design history.

Required sections:

- Canonical labels and DOM IDs.
- Mode states: connection card, select mode, annotate mode, stale preview,
  draft/history view, sent history.
- Persistence semantics:
  - `Annotate` enters feedback targeting mode and freezes the latest preview.
  - `Add annotation` creates a browser-side pending annotation.
  - `Copy Prompt` persists written pending annotations when needed, then copies
    compact agent-facing prompt text.
  - `Send Agent` persists written pending annotations when needed, then creates
    a local handoff batch for MCP tools.
  - `Clear Draft` removes unsent local draft feedback after confirmation.
  - Live preview frames remain transient; persisted session screens are evidence
    snapshots.
- Device semantics:
  - `Clear selection` only clears FixThis selection and owned bridge resources.
  - It must not run `adb disconnect`.
- Privacy semantics:
  - Send remains local persistence.
  - No screenshot, prompt, or feedback leaves the local machine by default.

### Files

- Add `docs/feedback-console-contract.md`.
- Update `README.md`, `docs/mcp.md`, `docs/design-feedback-console-ux.md`, and
  relevant PRD language to link to the contract and use the canonical labels.
- Update tests in `FeedbackConsoleServerTest.kt` so string assertions check
  current contract labels and stable DOM IDs, not obsolete labels.

### Done When

- README, MCP docs, UX status doc, PRD, and console tests use one vocabulary.
- A contributor can explain when pending annotations are persisted without
  reading `app.js`.
- Tests assert canonical labels: `Copy Prompt`, `Send Agent`, `Select`,
  `Annotate`, `Add annotation`, `Clear Draft`, `Clear selection`.

### Validation

```bash
./gradlew :fixthis-mcp:test
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

## Workstream 2: CI And Contribution Baseline

### Problem

The repository has a PR template but no required CI workflow, root
`CONTRIBUTING.md`, root `CHANGELOG.md`, root `LICENSE`, or release-readiness
checklist. Local tests pass, but the official pre-merge bar is not captured in
automation.

### Design

Add a minimal CI workflow that mirrors the analysis commands and keeps connected
device checks out of the required lane.

Required checks:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

The workflow should run on pull requests and pushes to `main`. Use Java 21 to
match `gradle/gradle-daemon-jvm.properties`. Use Android SDK setup suitable for
unit tests and `:app:assembleDebug`; do not require a device.

Add `CONTRIBUTING.md` with:

- Required local checks.
- Connected-device checks marked optional/manual.
- How to report skipped checks.
- Compatibility checklist copied from `.github/pull_request_template.md`.
- A note that `.fixthis/*` artifacts may contain screenshots and should not be
  committed or shared casually.

Add `CHANGELOG.md`, `LICENSE`, and `SECURITY.md` as separate release-readiness
tasks. If the project owner has not chosen a license, add a release blocker note
instead of guessing a license.

### Files

- `.github/workflows/ci.yml`
- `CONTRIBUTING.md`
- `.github/pull_request_template.md`
- Later release docs: `CHANGELOG.md`, `LICENSE`, `SECURITY.md`

### Done When

- Every PR runs the baseline checks.
- `CONTRIBUTING.md` and the PR template list the same verification language.
- CI failures preserve raw Gradle, Node, and diff-check output.
- Connected-device checks are documented as manual or optional, not silently
  omitted.

### Validation

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

## Workstream 3: Test Warning Cleanup

### Problem

The test suite emits project-owned deprecation warnings, mostly Kotlin
`createTempDir` and Java `URL(String)` calls. These warnings make it easier to
miss new signal.

### Design

Remove warnings through small test-helper changes before larger test splits.

For temp directories:

- Replace `createTempDir(...)` with `kotlin.io.path.createTempDirectory(...)`.
- Add small local helpers only where they reduce duplication.
- Preserve `deleteOnExit()` behavior where existing tests depend on it.

For HTTP tests:

- Add a tiny helper inside or near `FeedbackConsoleServerTest.kt`, for example
  `ConsoleHttpTestClient`.
- Build URLs through `URI(...).toURL()` or `HttpURLConnection` from `URI`.
- Centralize `GET`, `POST`, `DELETE`, JSON body, status-code, and response-body
  reads before splitting tests into route families.

### Files

- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/*Test.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`
- `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`

### Done When

- The targeted Gradle test command passes.
- Project-owned deprecation warnings for temp directories and `URL(String)` are
  gone.
- No test behavior or assertion strength is weakened.

### Validation

```bash
./gradlew :fixthis-mcp:test
```

Use the full baseline after this is combined with CI work.

## Workstream 4: Zero-Setup Agent Configuration

### Problem

`fixthis setup` currently prints JSON only. Users still need to locate MCP
client config files, add environment variables manually, and often wrap commands
in a shell so `ANDROID_HOME` is visible to MCP processes.

### Design

Build on `docs/design-zero-setup.md`, with one compatibility constraint:
running `fixthis setup` without `--write` keeps the current stdout JSON shape.

New CLI shape:

```text
fixthis setup [--package <id>] [--project-dir <path>]
              [--write]
              [--target codex|claude|all]
              [--dry-run]
              [--server-name <name>]
```

Behavior:

- Without `--write`, print the current JSON config for script users.
- With `--write --dry-run`, print the planned target files and rendered entries
  without writing.
- With `--write`, merge only the FixThis MCP server entry in each target file.
- Resolve package name through existing project metadata logic.
- Detect Android SDK from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and OS defaults.
- Write explicit MCP `env` when SDK is known.
- Do not write `ANDROID_SERIAL` by default because wireless ADB serials can be
  unstable.
- If no SDK is found, write the config without SDK env and print a warning plus
  the next `fixthis doctor` command.

Internal model:

```kotlin
data class McpConfigEntry(
    val serverName: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
)
```

Writers:

- `CodexConfigWriter`: line-based TOML section merge for
  `~/.codex/config.toml`.
- `ClaudeConfigWriter`: JSON object merge for `.claude/settings.json`.
- Both writers expose pure merge functions so tests do not need real user home
  config files.

### Files

- `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- New writer/locator files under `fixthis-cli/src/main/kotlin/io/beyondwin/fixthis/cli/commands/`
- CLI tests under `fixthis-cli/src/test/kotlin/io/beyondwin/fixthis/cli/`
- `README.md`, `docs/mcp.md`, `docs/design-zero-setup.md`,
  `docs/troubleshooting.md`

### Done When

- `fixthis setup` remains backward-compatible.
- `fixthis setup --write --target codex --dry-run` is deterministic.
- Existing non-FixThis MCP config entries are preserved.
- Generated config can run without `/bin/zsh -lc` just to expose
  `ANDROID_HOME`.
- Missing SDK/device is a clear warning, not a config-write failure.

### Validation

```bash
./gradlew :fixthis-cli:test
./gradlew :fixthis-cli:installDist
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD"
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD" --write --target codex --dry-run
git diff --check
```

## Workstream 5: Connected Smoke Harness

### Problem

Connected-device behavior is important but environment-sensitive. Recent manual
verification was blocked by secure lockscreen and wireless debugging discovery.
The project needs a repeatable smoke report that distinguishes project failures
from local device state.

### Design

Add a script or Gradle wrapper task that produces a local smoke report and exits
with explicit categories.

Recommended command:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
```

Smoke phases:

1. Record host, OS, Java, Gradle, Android SDK, and ADB path.
2. Build `:app:assembleDebug`, `:fixthis-cli:installDist`, and
   `:fixthis-mcp:installDist` unless `--no-build` is passed.
3. Run `adb devices -l`.
4. If no usable device exists, write `SKIPPED_NO_DEVICE`.
5. If device is unauthorized/offline/locked, write the precise category when it
   can be detected.
6. Install and launch the sample app when a ready device exists.
7. Run `fixthis doctor`.
8. Optionally open the console and perform browser-only smoke against localhost
   when browser tooling is available.

Report output:

```text
.fixthis/smoke-reports/<timestamp>.json
.fixthis/smoke-reports/<timestamp>.md
```

The `.fixthis` directory is already ignored, so reports stay local.

### Done When

- No device, unauthorized device, offline device, likely locked device, and lost
  wireless debugging are distinct outcomes.
- The script never runs broad process cleanup such as `killall node`.
- Manual QA reports can be compared across sessions.
- CI can run the script in `--dry-run` or `--host-only` mode without a device.

### Validation

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
git diff --check
```

The second command may legitimately return a skipped connected-device result
when no device is attached.

## Workstream 6: MCP Session Service Split

### Problem

`FeedbackSessionService` currently owns session lifecycle, device selection,
connection diagnosis, app launch recovery, preview capture, navigation, draft
save, artifact promotion, source index lookup, target evidence derivation, and
validation. It is the main risk concentrator in `fixthis-mcp`.

### Design

Split by workflow boundary behind a stable facade. Keep
`FeedbackSessionService` as the public orchestration surface used by MCP tools
and console routes.

Collaborators:

- `ConsoleConnectionService`
  - Device list, selected serial, selection validation, clear selection,
    connection status, app launch recovery.
- `PreviewCaptureService`
  - Live preview capture, preview cache writes, screenshot file lookup, stale
    preview policy.
- `FeedbackDraftService`
  - Pending annotation validation, preview promotion, draft item construction,
    clear draft.
- `TargetEvidenceService`
  - Selected node lookup, area evidence, source candidate matching, target
    evidence derivation, confidence warnings.

Boundary rules:

- DTOs and persisted JSON remain unchanged.
- Store and bridge are passed to collaborators; do not introduce global state.
- Avoid holding `sessionLock` around disk or bridge I/O when extracting code.
- Extract tests before or with each collaborator so behavior stays pinned.

### Done When

- `FeedbackSessionService` becomes a thin facade.
- Each collaborator has focused unit tests with fake bridge/store inputs.
- Existing MCP tool and console endpoint tests still pass.
- Persisted sessions created before the split still decode.

### Validation

```bash
./gradlew :fixthis-mcp:test
git diff --check
```

Run the full baseline after the final extraction step.

## Workstream 7: Console Server Route Split

### Problem

`FeedbackConsoleServer` mixes server lifecycle, route dispatch, request
decoding, response serialization, artifact serving, exception mapping, and path
parsing. Adding endpoints requires editing a large `when` block.

### Design

Keep Java `HttpServer` and endpoint paths stable, but split route ownership.

Proposed structure:

```text
fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/
  FeedbackConsoleServer.kt       // lifecycle, context registration
  ConsoleHttp.kt                 // response, decoding, method helpers
  ConsoleRoutes.kt               // route interface and route table
  SessionRoutes.kt
  DeviceRoutes.kt
  ConnectionRoutes.kt
  PreviewRoutes.kt
  FeedbackItemRoutes.kt
  ArtifactRoutes.kt
```

Route interface:

```kotlin
internal interface ConsoleRoute {
    fun matches(path: String): Boolean
    fun handle(exchange: HttpExchange)
}
```

Route ordering must keep specific artifact paths before broader fallback
patterns. Exception mapping should live in one place and preserve current status
codes.

### Done When

- Adding a new `/api/*` endpoint does not require editing a long central
  `when`.
- Route family tests can exercise one route family without asserting the entire
  HTML contract.
- Screenshot path safety checks remain intact.

### Validation

```bash
./gradlew :fixthis-mcp:test --tests '*FeedbackConsoleServerTest'
./gradlew :fixthis-mcp:test
```

## Workstream 8: Browser Console Asset Modularization

### Problem

The console asset contract is good, but `app.js` and `styles.css` are too large
for safe iteration. Most browser behavior is tested indirectly through Kotlin
HTML/string assertions.

### Design

Preserve the packaged resource contract:

```text
fixthis-mcp/src/main/resources/console/index.html
fixthis-mcp/src/main/resources/console/styles.css
fixthis-mcp/src/main/resources/console/app.js
```

Introduce source modules without requiring a frontend framework:

```text
fixthis-mcp/src/main/console/
  state.js
  api.js
  connection.js
  devices.js
  preview.js
  annotations.js
  history.js
  prompt.js
  rendering.js
  shortcuts.js
  main.js
```

Add a small deterministic build script:

```text
scripts/build-console-assets.mjs
```

The script concatenates source modules in an explicit order into the existing
resource `app.js`. The generated resource remains committed until the project
has a stronger JS build convention. `--console-assets-dir` continues to point at
`fixthis-mcp/src/main/resources/console`.

Testing:

- `node --check` for every source JS file and generated `app.js`.
- Add a browser smoke test after the route split, preferably with fake service
  data or a local console test fixture. The smoke should cover connection card,
  select/annotate flow, prompt copy/send, stale preview, and session switching.

### Done When

- Source JS modules are each small enough to review locally.
- Generated `app.js` matches the committed build output.
- `--console-assets-dir` still works.
- Browser behavior has at least one end-to-end smoke path beyond static HTML
  assertions.

### Validation

```bash
node scripts/build-console-assets.mjs --check
node --check fixthis-mcp/src/main/resources/console/app.js
./gradlew :fixthis-mcp:test
```

## Workstream 9: Source Index v2 And Evidence Fixtures

### Problem

The current source index is useful but broad. It scans all quoted Kotlin strings
as `text`, which can make source candidates look more precise than they are.

### Design

Add optional v2 fields while keeping v1 readers safe:

```kotlin
@Serializable
data class SourceIndexEntry(
    val file: String,
    val line: Int? = null,
    val symbols: List<String> = emptyList(),
    val text: List<String> = emptyList(),
    val contentDescriptions: List<String> = emptyList(),
    val testTags: List<String> = emptyList(),
    val stringResources: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val activityNames: List<String> = emptyList(),
    val excerpt: String? = null,
    val signals: List<SourceSignal> = emptyList(),
    val packageName: String? = null,
    val className: String? = null,
)

@Serializable
data class SourceSignal(
    val kind: SourceSignalKind,
    val value: String,
    val confidenceWeight: Double,
)

enum class SourceSignalKind {
    COMPOSABLE_SYMBOL,
    UI_TEXT,
    STRING_RESOURCE,
    TEST_TAG,
    STRICT_COMP_TEST_TAG,
    CONTENT_DESCRIPTION,
    ROLE,
    ACTIVITY_NAME,
    ARBITRARY_STRING_LITERAL
}
```

Implementation approach:

- Keep regex extraction as v1 fallback.
- Reclassify `Text("...")` and XML strings as UI text.
- Reclassify plain quoted strings as `ARBITRARY_STRING_LITERAL` with lower
  confidence.
- Detect `comp:<ComposableName>:<variant>` tags as strict convention tags.
- Add package/class context when cheaply inferable from file text.
- Do not add Kotlin PSI until typed regex extraction proves insufficient.

Evidence fixtures:

- Add `sample/fixthis-coverage.json` or `sample/fixthis-coverage.md`.
- List expected coverage scenes: repeated cards, form inputs, dropdown/menu,
  dialog, canvas/visual area, disabled controls, long text, weak semantics, and
  strict `comp:` tags.
- Add tests that assert sample tags/text remain present and generated source
  index contains expected typed signals.

### Done When

- Source candidate output can explain whether a match came from UI text,
  testTag, contentDescription, strict `comp:` tag, or arbitrary literal.
- Arbitrary literal matches no longer appear as high-confidence UI evidence by
  default.
- Sample app changes cannot accidentally remove core FixThis validation scenes.

### Validation

```bash
./gradlew :fixthis-gradle-plugin:test :app:assembleDebug
./gradlew :fixthis-compose-core:test :fixthis-mcp:test
```

## Workstream 10: Local Security And Artifact Retention

### Problem

The console is localhost-bound and the Android bridge already uses a session
token, but browser-local mutation endpoints can still launch the app, navigate,
capture previews, and mutate local sessions. Artifacts can contain screenshots
and need easier cleanup.

### Design

Console request hardening:

- Generate a per-console browser token at server start.
- Include the token in the served HTML as a non-secret local session value.
- Require token on mutating `/api/*` routes through a header such as
  `X-FixThis-Console-Token`.
- Reject unexpected `Origin` headers on mutating routes.
- Keep `127.0.0.1` default host.
- Do not add CORS.

Artifact retention:

- Add `fixthis clean` or `fixthis sessions prune --older-than <duration>`.
- First version should support dry-run and clear categories:
  - feedback sessions
  - preview cache
  - legacy artifacts
  - smoke reports
- Document exactly which `.fixthis` directories are safe to delete.
- Keep `.fixthis/project.json` handling explicit because some teams may want to
  keep package metadata.

### Done When

- A random webpage cannot mutate console endpoints by guessing localhost port.
- Console UX still works without extra user setup.
- Users can preview and delete local screenshot/session artifacts without manual
  directory inspection.

### Validation

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test
node --check fixthis-mcp/src/main/resources/console/app.js
```

## Workstream 11: Release And MCP Compatibility Readiness

### Problem

Docs mention future published coordinates, but release process, license,
compatibility matrix, and MCP compatibility fixtures are incomplete.

### Design

Release readiness docs:

- `LICENSE`: project owner must choose before external release.
- `CHANGELOG.md`: begin with unreleased changes and known limitations.
- `SECURITY.md`: describe local-first debug scope, vulnerability reporting path,
  and non-goals.
- Compatibility matrix in README or a dedicated doc:
  - Android Gradle Plugin
  - Kotlin
  - Compose BOM
  - Java/Gradle
  - min/target/compile SDK
  - macOS/Linux/Windows CLI status
  - supported MCP clients

MCP compatibility fixtures:

- Keep the hand-rolled MCP protocol implementation for now.
- Add fixture tests for:
  - initialize
  - notifications
  - `tools/list`
  - `tools/call`
  - `resources/list`
  - cancellation
  - EOF behavior
  - invalid message recovery

### Done When

- README no longer implies external artifact consumption before release docs
  are ready.
- Release notes can explain compatibility and known limitations.
- MCP compatibility risk is covered by fixtures, not only implementation-specific
  tests.

### Validation

```bash
./gradlew :fixthis-mcp:test :fixthis-cli:test :fixthis-gradle-plugin:test
git diff --check
```

## Implementation Order

Recommended sequence:

1. Contract stabilization: add console contract doc, align README/MCP/UX/PRD,
   and update console label tests.
2. Verification baseline: add CI, CONTRIBUTING, PR template alignment, and test
   warning cleanup.
3. Onboarding reliability: implement zero-setup writer and smoke harness.
4. MCP maintainability: split session service, route families, and JS sources.
5. Evidence and release readiness: Source Index v2, sample coverage fixtures,
   token/origin guard, artifact cleanup, release docs, MCP compatibility
   fixtures.

Do not start the larger MCP splits before the console product contract and
targeted tests are stable. Otherwise refactors will preserve behavior that the
docs may still describe incorrectly.

## Cross-Cutting Verification Matrix

| Change type | Minimum verification |
| --- | --- |
| Docs-only | `git diff --check` |
| Console HTML/JS/CSS | `node --check fixthis-mcp/src/main/resources/console/app.js`, `./gradlew :fixthis-mcp:test` |
| CLI setup/clean | `./gradlew :fixthis-cli:test :fixthis-cli:installDist` |
| MCP session/server | `./gradlew :fixthis-mcp:test` |
| Source index | `./gradlew :fixthis-gradle-plugin:test :fixthis-compose-core:test` |
| Cross-module/high-risk | Full baseline from Workstream 2 |
| Connected device | Smoke harness; allowed to report explicit skipped category |

Failed verification must be root-caused before weakening tests or changing the
design. If a connected-device check is skipped, record the exact skip category
and residual risk.

## Migration And Compatibility

- Existing feedback sessions must continue to decode.
- Existing MCP JSON field names remain stable.
- Existing HTTP endpoint paths remain stable.
- Existing CLI commands keep current output unless new flags are used.
- `fixthis setup` stdout JSON remains compatible without `--write`.
- `--console-assets-dir` remains supported for local console iteration.
- Source Index v2 fields are additive; v1 fields remain readable.

## Risks

- Contract alignment may reveal product wording that still needs owner approval.
  This design resolves that by choosing the shipped Studio vocabulary.
- CI may expose environment drift around Android SDK setup. Keep connected
  device smoke optional until a known runner exists.
- Service and route splits can create accidental behavior drift. Extract with
  focused tests and keep DTOs stable.
- Token/origin guard can break browser requests if applied before all mutating
  calls go through one API helper. Centralize JS request handling first.
- Source Index v2 can overfit sample patterns. Keep v1 fallback and confidence
  explanations visible.

## Open Decisions

1. Which license should the project use before external release?
2. Should Codex config writes target only `~/.codex/config.toml`, or should a
   project-local Codex config be supported if Codex adds one?
3. Should browser smoke become required CI after fake bridge fixtures exist, or
   remain a local QA gate?
4. Should Source Index v2 stop at typed regex extraction, or move to Kotlin PSI
   after the first typed-signal iteration?
