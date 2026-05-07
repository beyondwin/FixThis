# FixThis Project Improvement Proposals

**Date:** 2026-05-07
**Branch analyzed:** `main` at `0d1d141`
**Scope:** repository-wide product, architecture, developer experience, testing, documentation, release readiness

## Executive Summary

FixThis has a strong V1 foundation: the module boundaries are mostly clean, the MCP-first feedback console is implemented end to end, local session persistence exists, Stable Target Evidence is additive, and the local JVM/Android unit test suite passes. The strongest next move is not a rewrite. The project should stabilize its current product contract, make local setup and verification repeatable, and reduce the concentration of risk in the MCP console surface.

Recommended improvement sequence:

1. Reconcile the shipped browser console UX with the public docs and tests.
2. Add a minimal CI/release-readiness baseline.
3. Implement zero-setup MCP configuration and clearer device preflight.
4. Split the MCP console/service hot spots into smaller, tested units.
5. Add browser-level and connected-device smoke automation.
6. Improve source-index precision and external-adoption readiness.

Detailed design: `docs/superpowers/specs/2026-05-08-project-improvement-stabilization-design.md`

Detailed implementation plan: `docs/superpowers/plans/2026-05-08-project-improvement-stabilization-implementation.md`

## Analysis Baseline

Observed repository shape:

- Kotlin files: 133
- Markdown files: 52
- Test files: 45
- Largest implementation surface: `fixthis-mcp`
- Largest browser assets:
  - `fixthis-mcp/src/main/resources/console/app.js`: 2,245 lines
  - `fixthis-mcp/src/main/resources/console/styles.css`: 1,266 lines
  - `fixthis-mcp/src/main/resources/console/index.html`: 131 lines
- Largest Kotlin implementation files:
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`: 823 lines
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisTools.kt`: 753 lines
  - `fixthis-compose-sidekick/src/main/kotlin/io/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`: 523 lines
  - `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt`: 509 lines
- Largest tests:
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/FeedbackConsoleServerTest.kt`: 2,596 lines
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt`: 1,350 lines
  - `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt`: 1,042 lines

Verification run during this analysis:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
fixthis-cli/build/install/fixthis/bin/fixthis setup --package io.beyondwin.fixthis.sample --project-dir "$PWD"
ANDROID_HOME="$HOME/Library/Android/sdk" PATH="$HOME/Library/Android/sdk/platform-tools:$PATH" fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample --project-dir "$PWD"
```

Results:

- Unit tests: passed.
- Sample debug APK assemble: passed.
- CLI/MCP distribution install: passed.
- Console JavaScript syntax check: passed.
- `fixthis setup`: prints JSON only.
- `fixthis doctor`: found project metadata and ADB, but failed connected-device checks because no Android device/emulator was connected during this analysis.
- Warnings: MCP tests emit many deprecation warnings for Kotlin `createTempDir` and Java `URL(String)`.

## Priority 0: Align The Product Contract

### 1. Reconcile Console Docs, Labels, And Tests

Current public docs describe a flow with `Add`, `Save`, `Copy`, and `Send`. The shipped HTML shows `Copy Prompt`, `Send Agent`, `Select`, `Annotate`, `Exit Annotate`, and `Add annotation` instead. The test suite also asserts that older top-bar labels such as `Save snapshot` are absent.

Evidence:

- Shipped UI labels: `fixthis-mcp/src/main/resources/console/index.html`
  - lines 40-46: `Copy Prompt`, `Send Agent`
  - lines 86-92: `Select`, `Annotate`
  - lines 119-123: `Exit Annotate`, `Add annotation`, `Clear Draft`
- Docs still say:
  - `docs/mcp.md` lines 63-68: `Add`, `Add to Pending`, `Save`, `Copy`, `Send`
  - `README.md` lines 118-123: same public flow language

Why it matters:

The product has recently moved from a simple Add/Save workflow toward an Annotate/Prompt workflow, but docs and some design-status language still describe the old contract. This is the highest-impact issue because it affects users, tests, agent prompts, and future implementation plans. If the team keeps building on stale language, future changes will optimize the wrong workflow.

Recommendation:

- Pick one vocabulary as the product contract:
  - Option A: keep the shipped `Annotate`, `Copy Prompt`, `Send Agent` vocabulary and update README/MCP/UX docs.
  - Option B: revert UI labels toward `Add`, `Save`, `Copy`, `Send` if that remains the intended non-developer workflow.
- Add one short `docs/feedback-console-contract.md` file that lists current labels, mode states, primary user actions, and persistence behavior.
- Convert brittle HTML string assertions into contract assertions around that file's vocabulary and DOM IDs.

Done when:

- README, MCP, UX status, PRD, and console tests all describe the same flow.
- A contributor can answer whether persistence happens on explicit Save, Copy Prompt, Send Agent, or session transition without reading `app.js`.

## Priority 1: Make The Project Reliably Shippable

### 2. Add Minimal CI And Quality Gates

No GitHub Actions, CI config, license, changelog, contributing guide, dependency update config, formatter, or static analysis config was found in the repository root. Gradle tests pass locally, but nothing currently records the official pre-merge bar.

Recommended baseline:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

Add later:

- Android connected smoke job as optional/manual because it requires an unlocked device/emulator.
- Dependency update automation after the release policy is decided.
- A `CONTRIBUTING.md` with exact local verification commands.
- A `CHANGELOG.md` and versioning policy before publishing.
- `LICENSE` before describing the project as open source.

Done when:

- Every PR gets the baseline checks.
- The README points to the same checks.
- Warnings are either fixed or explicitly accepted in a release-readiness note.

### 3. Remove Test Deprecation Warnings

The test suite passes, but warnings are noisy enough to hide future signal:

- `createTempDir(...)` is deprecated in MCP tests.
- Java `URL(String)` is deprecated and appears heavily in `FeedbackConsoleServerTest`.

Recommendation:

- Replace Kotlin `createTempDir` with `kotlin.io.path.createTempDirectory`.
- Replace `URL(server.url).readText()` helpers with a tiny test HTTP client helper based on `URI(...).toURL()` or `HttpURLConnection`.
- Centralize console test request helpers in `FeedbackConsoleServerTest` before splitting the file.

Done when:

- The same Gradle test command passes with no deprecation warnings from project test code.

## Priority 1: Reduce MCP Console Risk

### 4. Split `FeedbackSessionService` By Workflow Boundary

`FeedbackSessionService` now owns session lifecycle, device selection, connection diagnosis, app launch recovery, preview capture, navigation, save/promotion, source index lookup, target evidence derivation, and validation. It already delegates preview cache/source registry/artifact promotion, but it remains the main risk concentrator.

Evidence:

- Constructor and shared lock/state: `FeedbackSessionService.kt` lines 35-45.
- Session lifecycle/device/control methods: lines 47-109.
- Connection recovery begins at line 109.
- Save/promotion, evidence, and source candidate logic continue later in the same file.

Recommendation:

Extract behind package-private collaborators:

- `ConsoleConnectionService`: device enumeration, selected-device validation, `connectionStatus`, `launchAppForCurrentSession`.
- `PreviewCaptureService`: live preview capture, screenshot artifact lookup, stale/cached preview policy.
- `FeedbackDraftService`: pending item validation, frozen preview promotion, item construction.
- `TargetEvidenceService`: area/node evidence selection, source candidate matching, target evidence derivation.

Keep `FeedbackSessionService` as a thin orchestration facade so existing tools and console endpoints do not churn.

Done when:

- `FeedbackSessionService` is under roughly 300 lines.
- Each extracted collaborator has focused tests that do not require constructing the full console service.
- Existing public DTOs and persisted JSON stay unchanged.

### 5. Split `FeedbackConsoleServer` Into Routes And HTTP Utilities

`FeedbackConsoleServer` owns routing, request decoding, response serialization, screenshot file serving, path parsing, exception mapping, and server lifecycle.

Evidence:

- Route switch starts at `FeedbackConsoleServer.kt` line 69.
- Connection/device/session/preview/navigation/item routes all live in one method from lines 71 onward.

Recommendation:

- Introduce a small route table:
  - `SessionRoutes`
  - `DeviceRoutes`
  - `ConnectionRoutes`
  - `PreviewRoutes`
  - `FeedbackItemRoutes`
  - `ArtifactRoutes`
- Keep the Java `HttpServer` if avoiding dependencies is still a goal.
- Move decoding helpers and response helpers into `ConsoleHttp.kt`.
- Keep endpoint paths stable.

Done when:

- Adding a new `/api/*` endpoint no longer requires editing a 100+ line `when` block.
- Route tests can target one route family without loading every HTML contract assertion.

### 6. Modularize Console Assets Without Changing The Packaged Resource Contract

The current packaged resource strategy is good: `index.html`, `styles.css`, and `app.js` live under `fixthis-mcp/src/main/resources/console` and can be loaded from source with `--console-assets-dir`. The problem is maintainability, not packaging.

Evidence:

- `app.js`: 2,245 lines and about 449 function/variable declaration matches.
- `styles.css`: 1,266 lines.
- Current browser behavior tests are mostly string-contract tests in a 2,596-line Kotlin test file.

Recommendation:

- Keep the runtime dependency-free asset model for now.
- Split source files under `fixthis-mcp/src/main/resources/console/src/` or `fixthis-mcp/src/main/console/`:
  - `state.js`
  - `api.js`
  - `connection.js`
  - `preview.js`
  - `annotations.js`
  - `history.js`
  - `prompt.js`
  - `rendering.js`
- Add a tiny build script that concatenates to the current `app.js` resource, or serve module files only in development while packaging a single bundle.
- Add `node --check` and a browser smoke test to CI before introducing a larger JS toolchain.

Done when:

- `app.js` generated or source files are each under roughly 400 lines.
- Console behavior can be tested through Playwright-level flows, not mostly `html.contains(...)`.
- `--console-assets-dir` still works for local iteration.

## Priority 1: Improve Onboarding And Device Reliability

### 7. Implement Zero-Setup MCP Configuration

`fixthis setup` currently prints JSON with `"command": "fixthis"` and args, but it does not write Codex/Claude config and does not include explicit Android SDK environment.

Evidence:

- `SetupCommand.kt` lines 20-25 resolves package and prints JSON.
- `buildMcpClientConfig` lines 29-44 emits command/args/package/project only.
- `fixthis doctor` during this analysis passed project metadata and ADB but failed because no device was connected.
- There is already a proposal doc in `docs/design-zero-setup.md`.

Recommendation:

- Implement `fixthis setup --write --target codex|claude|all --dry-run`.
- Add Android SDK detection and explicit `env` blocks for MCP clients.
- Keep stdout JSON as the default for scripts.
- Add `fixthis setup --doctor` or append a post-setup preflight summary: SDK found, adb found, device status, package metadata source.

Done when:

- A new local user can run one command and get a usable MCP config without shell wrappers.
- The generated config is deterministic and covered by tests.

### 8. Add A First-Class Connected Smoke Harness

The project depends on real ADB/device behavior, but connected smoke verification is currently procedural and environment-sensitive. Recent verification was blocked by secure lockscreen and wireless debugging rediscovery behavior. Troubleshooting covers this, but automation should make the state explicit.

Recommendation:

- Add `scripts/fixthis-smoke.sh` or a Gradle task wrapper that:
  - checks `ANDROID_HOME`/ADB,
  - records `adb devices -l`,
  - installs the sample app,
  - starts the console with source assets,
  - prints the console URL,
  - optionally runs browser smoke if a device is ready and unlocked,
  - exits with "skipped connected smoke" when no usable device is present.
- Add a small JSON or Markdown smoke report under ignored `.fixthis/smoke-reports/`.
- Keep connected smoke out of required CI until an emulator/device runner is available.

Done when:

- "No device", "locked device", and "wireless debugging lost" are explicit skip/fail categories.
- Manual QA produces comparable output across sessions.

## Priority 2: Improve Evidence Quality

### 9. Build Source Index v2 Around Intentional Signals

The current source indexer is pragmatic and useful, but it is regex-based and indexes all quoted Kotlin strings as text. This can inflate matches and make source candidates look more confident than they are.

Evidence:

- `GenerateFixThisSourceIndexTask.kt` lines 94-132 reads the whole file, scans line text, then applies regexes for `@Composable`, function names, quoted strings, `Text(...)`, `stringResource`, `testTag`, and `contentDescription`.

Recommendation:

- Keep v1 as fallback.
- Add explicit fields for:
  - composable function name,
  - convention testTag parsed as `comp:<ComposableName>:<variant>`,
  - source string category: UI text, test tag, content description, arbitrary string literal,
  - resource references and XML string values,
  - package/class context when cheap to infer.
- Consider Kotlin PSI or a lightweight parser only for indexing, not runtime.
- Lower confidence for arbitrary quoted strings.

Done when:

- Source candidate confidence explains whether a hit came from UI text, testTag convention, contentDescription, or arbitrary literal.
- Stable Target Evidence can point to convention tags without overclaiming exact runtime source mapping.

### 10. Add Evidence Quality Fixtures

The sample app is valuable, but the intended evidence coverage should be machine-readable.

Recommendation:

- Add a `sample/fixthis-coverage.md` or JSON fixture that lists expected coverage scenes:
  - repeated cards,
  - form inputs,
  - dropdown/menu,
  - dialog,
  - canvas/visual area,
  - disabled controls,
  - long text,
  - weak semantics fallback,
  - strict `comp:` tags.
- Add tests that check the sample app still contains the expected test tags/text and that the generated source index includes the intended entries.

Done when:

- Redesigning the sample app cannot accidentally remove a core FixThis validation case.

## Priority 2: Harden Local Security And Privacy Boundaries

### 11. Add Console Session Token Or Origin Guard

The console is bound to localhost and uses an ephemeral port, which is a good V1 default. Still, local browser endpoints can launch the debug app, navigate, capture previews, and serve screenshot artifacts. Treat this as a local trusted surface, but make accidental cross-origin interaction harder.

Recommendation:

- Generate a per-console token and require it in a header or query param for mutating `/api/*` routes.
- Reject unexpected `Origin` headers for mutating routes.
- Keep `127.0.0.1` as the default host and document any future non-loopback host as unsupported.
- Avoid adding CORS.

Done when:

- A random webpage cannot trigger console mutation endpoints even if it guesses the port.
- The browser console still works without extra setup.

### 12. Tighten Artifact Retention Controls

The docs correctly warn that screenshots may contain sensitive data. The project should add operational controls to make cleanup easy.

Recommendation:

- Add `fixthis clean` or `fixthis sessions prune --older-than <duration>`.
- Add console UI action for clearing local preview cache and closed sessions.
- Document exactly which `.fixthis` directories are safe to delete.

Done when:

- Users can remove local screenshot/session artifacts without manually inspecting `.fixthis`.

## Priority 2: Prepare For External Adoption

### 13. Define Release And Publishing Readiness

Docs mention future published coordinates, and the root build declares the Gradle plugin publish plugin alias, but there is no root release process, license, changelog, or public compatibility matrix.

Recommendation:

- Add `LICENSE`, `CHANGELOG.md`, `CONTRIBUTING.md`, and `SECURITY.md`.
- Add a version source of truth.
- Add `publishToMavenLocal` verification for CLI docs and Gradle plugin docs.
- Define supported matrix:
  - AGP,
  - Kotlin,
  - Compose BOM,
  - min/target/compile SDK,
  - macOS/Linux/Windows CLI support.
- Avoid publishing until setup/onboarding and CI are in place.

Done when:

- An external app can follow the README without relying on private repo wiring.
- Release notes can explain compatibility and known limitations.

### 14. Decide Whether The Custom MCP Protocol Layer Is Strategic

The hand-rolled JSON-RPC/MCP implementation is small and well tested, and keeping dependencies low is reasonable. The risk is long-term MCP compatibility drift.

Recommendation:

- Do not switch to an SDK just for novelty.
- Add a protocol compatibility fixture suite:
  - initialize,
  - notifications,
  - tools/list,
  - tools/call,
  - resources/list,
  - cancellation,
  - EOF behavior,
  - invalid message recovery.
- Revisit SDK adoption only if the MCP feature surface grows beyond tools/resources.

Done when:

- MCP compatibility risk is tracked by fixtures, not only implementation-specific unit tests.

## Suggested Roadmap

### Phase 1: Contract And Verification Stabilization

Goal: make the current product understandable and safe to change.

Tasks:

- Decide current console vocabulary: `Add/Save/Copy/Send` vs `Annotate/Copy Prompt/Send Agent`.
- Update docs/tests/UI to one contract.
- Add CI baseline for unit tests, sample assemble, distributions, `node --check`, and `git diff --check`.
- Remove test deprecation warnings.
- Add `CONTRIBUTING.md` with exact verification commands.

Validation:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
git diff --check
```

### Phase 2: Onboarding And Smoke Reliability

Goal: make first use and connected-device debugging repeatable.

Tasks:

- Implement `fixthis setup --write`.
- Add Android SDK locator and generated MCP `env` blocks.
- Add connected smoke harness with explicit skip/fail categories.
- Add session/artifact cleanup command.

Validation:

- Fresh clone can generate Codex/Claude MCP config without shell wrappers.
- Smoke script reports useful diagnostics with no device, locked device, and ready device.

### Phase 3: MCP Console Maintainability

Goal: reduce the blast radius of console changes.

Tasks:

- Extract connection/preview/draft/evidence collaborators from `FeedbackSessionService`.
- Split console routes from `FeedbackConsoleServer`.
- Split browser JS source while preserving packaged resource behavior.
- Add Playwright smoke for browser-only flows against fake service data or local console fixtures.

Validation:

- Existing tests pass.
- New route/service tests are smaller and more focused.
- Browser smoke exercises connection card, annotate flow, prompt copy/send, stale preview, and session switching.

### Phase 4: Evidence And Release Readiness

Goal: improve agent handoff quality and prepare public adoption.

Tasks:

- Source index v2 with typed match reasons.
- Sample coverage fixture and source-index assertions.
- Compatibility matrix.
- License/changelog/security/release docs.
- Optional Maven local/publishing pipeline.

Validation:

- `fixthis_read_feedback` evidence explains confidence sources clearly.
- External sample project can consume the plugin through documented wiring or local Maven.

## What Not To Do Yet

- Do not rewrite the MCP console into a frontend framework before the product vocabulary is settled.
- Do not add a Kotlin compiler plugin for source mapping in the default path.
- Do not turn FixThis into production feedback collection.
- Do not add external AI API calls to the console.
- Do not broaden beyond Compose until setup, CI, and source evidence quality are stable.

## Open Decisions

1. Should the user-facing workflow be `Annotate -> Copy Prompt/Send Agent`, or should it return to the documented `Add -> Save -> Copy/Send` flow?
2. Is external open-source release a near-term goal, or should the next milestone focus only on internal/local developer use?
3. Should browser automation become a required CI check with fake bridge data, or remain a local QA tool until the console is modularized?
4. Should source-index v2 use Kotlin PSI now, or first improve typed regex extraction and confidence labeling?

## Appendix: Commands And Observations

The following checks passed:

```bash
./gradlew :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test
./gradlew :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist
node --check fixthis-mcp/src/main/resources/console/app.js
```

The following diagnostic failed for an expected environment reason:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" PATH="$HOME/Library/Android/sdk/platform-tools:$PATH" fixthis-cli/build/install/fixthis/bin/fixthis doctor --package io.beyondwin.fixthis.sample --project-dir "$PWD"
```

Observed output:

```text
OK   Android project found
OK   FixThis project metadata found
OK   ADB found
FAIL device connected: No connected Android device or emulator found
FAIL sidekick session found: No connected Android device or emulator found
2 doctor check(s) failed
```

This should not block repository analysis, but it reinforces the need for a first-class connected smoke harness with clear skip/fail categories.
