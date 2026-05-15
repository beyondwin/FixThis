# Project Stability Hardening Implementation Details

Status: Draft
Date: 2026-05-15
Baseline: local audit from `main` on 2026-05-15
Scope: release gates, Gradle publishing dry-run, MCP session persistence,
console harness, architecture guardrails, console request auth
Related plan: [`../plans/2026-05-15-project-stability-hardening.md`](../plans/2026-05-15-project-stability-hardening.md)

## Summary

The project is already much stronger than the first stabilization pass: fast
local CI is green, console JS tests are manifest-driven, event-log compaction
is wired, server-owned preview fingerprints are covered, draft workspace
cleanup is covered, harness skip reporting exists, and `npm audit` no longer
reports moderate issues.

The remaining stabilization work is about making the release signal harder to
misread:

- full Gradle verification must be green, not just fast JS checks;
- release docs must not require a Gradle publishing dry-run that cannot run;
- session mutation code should not hold the store monitor while doing slow
  compaction work;
- architecture hotspot budgets should cover the actual large files;
- the multi-tab harness scenario should assert real cross-tab draft recovery;
- branch-protection observation windows need a reproducible operator flow;
- deferred console request auth hardening should be implemented and documented.

## Current Verification Baseline

Commands confirmed on 2026-05-15:

| Command | Result |
| --- | --- |
| `npm run ci:local:fast` | Passes. |
| `./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon` | Fails with 4 detekt findings and one Gradle 10 deprecation warning. |
| `./gradlew publishToMavenLocal --dry-run --no-daemon` | Fails because `publishToMavenLocal` is not present in the root build or subprojects. |
| `npm audit --audit-level=moderate` | Passes with 0 vulnerabilities. |
| `./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon` | Passes in the previous audit and remains the documented plugin validation gate. |

The current detekt findings are:

| File | Line | Rule | Required correction |
| --- | ---: | --- | --- |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | 2142 | `LongMethod` | Extract the large fixture for handoff quality risk signals into a helper. |
| `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FormatterExtensions.kt` | 24 | `MagicNumber` | Replace the `3` truncation offset with a named suffix length. |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` | 693 | `MaxLineLength` | Split the assertion message. |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | 2080 | `MaxLineLength` | Split the expected target summary string. |

Gradle also reports:

```text
The ReportingExtension.file(String) method has been deprecated. This is
scheduled to be removed in Gradle 10. Please use the getBaseDirectory().file(String)
or getBaseDirectory().dir(String) method instead.
```

The stack points at `build.gradle.kts:83`, which is the conditional Detekt
plugin application. Treat this as a build-readiness blocker even if the
deprecated call is inside a third-party plugin.

## Goals

- `npm run ci:local` and the CI Gradle verification job pass without detekt
  findings.
- `./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon` has no Gradle
  deprecation warnings from local build configuration. If a third-party plugin
  is the source, the decision is documented with an owner and upgrade path.
- `./gradlew publishToMavenLocal --dry-run --no-daemon` is either a real green
  dry-run or is replaced in release docs by a named equivalent that is green.
- Session mutation lock scope no longer includes event-log compaction work.
- `ArchitectureHotspotBudgetTest` covers the actual source and test hotspots.
- `scripts/console-harness.mjs --matrix multi-tab` fails if the receiver tab
  does not observe a draft write from the writer tab.
- Required-check observation state can be reproduced from a script or a
  documented command, not only manual table edits.
- Console mutation auth checks `Origin`, `Host`, and token equality through one
  module.

## Non-Goals

- No release build support. FixThis remains debug-only.
- No View, Flutter, cloud sync, or external API support.
- No persisted MCP JSON field rename.
- No bridge protocol version bump.
- No remote publish to Maven Central, Gradle Plugin Portal, npm, PyPI, Docker,
  or the MCP Registry.
- No broad rewrite of the feedback console runtime.

## SH-1 - Full Local CI Readiness

### Current State

`npm run ci:local:fast` passes because it focuses on docs, console bundle,
console JS tests, smoke script tests, and whitespace. Full Gradle verification
still fails when detekt runs. CI uses `detekt`, so this is a merge-signal
blocker.

The four detekt findings are mechanical except the long
`renderAddsHandoffQualitySummaryForRiskSignals` test. That method is large
because it constructs a multi-item session inline. The test should stay
semantically intact, but the fixture should move to a helper.

### Required Changes

- In `CompactHandoffRendererTest`, extract a helper named
  `handoffQualityRiskSignalSession()` or equivalent. The test body should
  render the helper session and assert the expected summary tokens.
- Split the target summary assertion at line 2080 into a named `expectedTarget`
  value.
- In `FormatterExtensions.kt`, introduce a named suffix constant:

```kotlin
private const val CompactTruncationSuffix = "..."
```

Use `CompactTruncationSuffix.length` instead of the literal `3`.

- In `ConsoleAssetContractTest`, split the long assertion message into a local
  value or concatenated strings.
- Run detekt with `--warning-mode all --stacktrace` after the rule fixes. If
  the Gradle deprecation comes from a local script block, update the local API.
  If it comes from Detekt 1.23.7, run a compatibility spike for the next Detekt
  version and document the result in `docs/contributing/required-checks.md`.

### Acceptance Criteria

- `./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon` passes.
- The command above prints no Gradle 10 deprecation warning from local build
  scripts. A third-party warning may remain only with an explicit tracking note.
- `npm run ci:local` passes, or any remaining failure is outside the files
  touched by this task and recorded with exact command output.

## SH-2 - Artifact Publish Dry-Run Contract

### Current State

`docs/contributing/release-readiness.md` and
`docs/contributing/release-process.md` list
`./gradlew publishToMavenLocal --dry-run` as an artifact-release prerequisite.
That command currently fails because no publication task exists in the root
multi-project build. This is honest as a blocker, but it is not yet a useful
release rehearsal.

### Required Changes

Implement a safe local publication dry-run without enabling remote publishing.
The dry-run must cover:

- `io.github.beyondwin:fixthis-compose-core`;
- `io.github.beyondwin:fixthis-compose-sidekick`, published as the debug
  sidekick artifact because the AndroidX Startup initializer lives under
  `src/debug`;
- the Gradle plugin build through the included build at
  `fixthis-gradle-plugin`.

Use a single repository package version source, preferably in
`gradle.properties`, for example:

```properties
FIXTHIS_GROUP=io.beyondwin.fixthis
FIXTHIS_VERSION=0.2.0-SNAPSHOT
```

Root build configuration should assign `group` and `version` from those
properties. Android and JVM publications should use `maven-publish`; remote
repositories and credentials stay out of scope.

For the included Gradle plugin build, either document the separate command:

```bash
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
```

or add a clearly named root aggregate validation task that depends on the
included build task. The public release docs must name the exact green command.

### Acceptance Criteria

- `./gradlew publishToMavenLocal --dry-run --no-daemon` succeeds for root
  project artifacts or the docs replace it with an equivalent command that
  succeeds.
- `./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon`
  succeeds, or the root aggregate task covers it.
- `./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon` succeeds.
- No remote publishing task runs by default.
- README still says public Gradle artifacts are not published.

## SH-3 - Session Store Lock Scope

### Current State

`FeedbackSessionStoreDelegate` uses `synchronized(lock)` around public store
operations. Event-backed mutations call `appendEventThenMutate()`, which
currently performs:

1. event-log append;
2. in-memory and snapshot mutation;
3. event-log compaction.

The compaction call happens before the synchronized public method returns, so
slow archive or checkpoint I/O can hold the store monitor. That makes reads and
unrelated session operations wait behind cleanup work that is supposed to be
best effort.

### Required Changes

- Keep write-ahead event append before mutation. Do not weaken fail-stop
  semantics for the journal append.
- Keep snapshot save and in-memory update ordered exactly as they are today
  unless a dedicated transaction object is introduced with equivalent tests.
- Move event-log compaction outside the store monitor.
- Introduce a small injectable compaction port, such as:

```kotlin
internal fun interface EventLogCompactionTask {
    fun runOnce(threshold: Int)
}
```

`EventLogCompactor` should implement that interface. `FeedbackSessionStore`
should accept providers of the interface so tests can inject a blocking
compactor without subclassing a concrete class.

- Ensure only one compaction per session runs at a time. A per-session lock or
  in-flight guard is enough; this does not need a background scheduler unless
  tests show synchronous post-lock compaction still hurts user flows.

### Acceptance Criteria

- A test with a blocking compactor proves `getSession(sessionId)` can return
  while compaction is waiting.
- A compaction failure still records the diagnostic in
  `replaySkippedSessions` and does not roll back the successful mutation.
- Sigkill replay and compactor tests still pass.

## SH-4 - Architecture Hotspot Guardrails

### Current State

`ArchitectureHotspotBudgetTest` exists, but it does not protect the current
largest files consistently. Current hotspots include:

| File | Current lines |
| --- | ---: |
| `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreDelegate.kt` | 714 |
| `fixthis-mcp/src/main/console/annotations.js` | 635 |
| `fixthis-mcp/src/main/console/history.js` | 504 |
| `fixthis-mcp/src/main/console/presentation/annotationDetailView.js` | 493 |
| `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/tools/FixThisToolDispatcher.kt` | 496 |
| `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackDraftService.kt` | 467 |
| `fixthis-mcp/src/main/kotlin/io/beyondwin/fixthis/mcp/session/TargetEvidenceService.kt` | 417 |
| `fixthis-mcp/src/main/console/main.js` | 411 |
| `fixthis-mcp/src/main/console/state.js` | 410 |
| `fixthis-mcp/src/main/console/domain/consoleReducer.js` | 378 |

Large test files are also legitimate maintenance risks:

| File | Current lines |
| --- | ---: |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/CompactHandoffRendererTest.kt` | 2337 |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionServiceTest.kt` | 1696 |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/McpProtocolTest.kt` | 1629 |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/session/FeedbackSessionStoreTest.kt` | 1254 |
| `fixthis-mcp/src/test/kotlin/io/beyondwin/fixthis/mcp/console/ConsoleAssetContractTest.kt` | 1044 |

### Required Changes

- Expand `ArchitectureHotspotBudgetTest` to include actual hotspots with
  current-size plus small headroom budgets.
- Split production source budgets, console JS budgets, and test budgets into
  separate maps so a test fixture budget cannot justify production growth.
- Keep `FIXTHIS_STRICT_ARCH_BUDGETS=true` as the mechanism for remediation
  budgets that would currently fail.
- Add a short comment explaining that budgets are ratchets: when a file shrinks,
  lower the budget in the same commit.

### Acceptance Criteria

- `./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest" --no-daemon`
  passes.
- Increasing any listed hotspot by more than the configured headroom fails the
  architecture test.
- `FixThisToolDispatcher.kt`, `FeedbackSessionStoreDelegate.kt`, and the large
  console JS modules are all registered.

## SH-5 - Multi-Tab Harness Assertion

### Current State

`scripts/console-harness.mjs` has a `multi-tab` branch that opens a second
page but does not yet perform the core assertion. The current inline comment
states that the writer page should trigger a draft write and the second page
should observe the change through a storage event or polling fallback.

The scenario is therefore passing without proving the behavior described by
`docs/architecture/console-state-sync-design.md`.

### Required Changes

- Add cross-tab recovery wiring in the console runtime if it is missing:
  listen for `storage` changes on `fixthis.workspace.*` keys and refresh the
  current session's recovery banner/history counts.
- In the harness, make the first page write a schema v2 draft workspace for
  `session-1` and assert the second page observes it.
- The receiver must be the second page. The writer page should never be used
  as the assertion target because browser `storage` events do not fire in the
  same document that performed the write.

### Acceptance Criteria

- `node scripts/console-harness.mjs --matrix multi-tab --viewport desktop-1280`
  fails before the runtime assertion path is implemented.
- The same command passes after implementation.
- `npm run console:harness:test` covers the scenario selection and any helper
  that builds the schema v2 envelope.
- `npm run console:test:all` remains green.

## SH-6 - Required Check Observation Flow

### Current State

`docs/contributing/required-checks.md` tracks observation windows manually.
That is acceptable for branch protection, which is a maintainer admin action,
but the table has no reproducible way to compute the latest green streaks.

### Required Changes

- Add a script or documented maintainer command that queries GitHub Actions
  history and prints a proposed update for the table.
- Keep it out of required CI unless it can run without credentials and without
  network access.
- Preserve the distinction between PR-required checks and informational
  scheduled jobs. `connected-tests.yml` and `nightly-compat.yml` should stay
  informational until their observation windows are complete.

### Acceptance Criteria

- Maintainers can run one command to see first green run and consecutive green
  counts for the tracked workflows.
- The command fails clearly when `gh` or GitHub auth is unavailable.
- `docs/contributing/required-checks.md` documents how to update the tracker
  and when to flip branch protection.

## SH-7 - Console Mutation Auth Hardening

### Current State

`FeedbackConsoleServer` protects mutating `/api/` requests by checking local
`Origin` values and the `X-FixThis-Console-Token` header. The threat model
still defers host-header pinning, a dedicated auth module, and contract docs.

### Required Changes

- Create a dedicated console auth module, for example
  `ConsoleRequestAuth.kt`.
- Keep all mutation-guard checks behind one call from `FeedbackConsoleServer`.
- Check:
  - HTTP method is mutating and path starts with `/api/`;
  - `Origin`, when present, is a local console origin with the running port;
  - `Host`, when present, matches the running local host and port, allowing
    loopback aliases;
  - `X-FixThis-Console-Token` is present and compared in constant time.
- Document the contract in `docs/reference/feedback-console-contract.md` or
  `docs/reference/threat-model.md`.

### Acceptance Criteria

- Mutating requests with a foreign `Origin` fail with 403.
- Mutating requests with a foreign `Host` fail with 403.
- Mutating requests without the console token fail with 403.
- Mutating requests with a valid local `Origin`, local `Host`, and token pass.
- Read-only `GET` routes are not forced to send the token.

## Execution Order

1. SH-1 first. A red full local CI gate makes every later stabilization change
   harder to trust.
2. SH-2 next. Release docs already name the dry-run as a prerequisite, so the
   command should become real or the docs should name a real equivalent.
3. SH-3 and SH-4 can proceed independently after SH-1.
4. SH-5 is isolated to console runtime/harness and can proceed in parallel with
   SH-3.
5. SH-6 and SH-7 are lower urgency but close long-lived release/security
   follow-ups before a public artifact release.

## Final Verification

Run these after all selected tasks:

```bash
npm run ci:local
./gradlew :fixthis-mcp:detekt --warning-mode all --no-daemon
./gradlew publishToMavenLocal --dry-run --no-daemon
./gradlew -p fixthis-gradle-plugin publishToMavenLocal --dry-run --no-daemon
./gradlew :fixthis-gradle-plugin:validatePlugins --no-daemon
./gradlew :fixthis-mcp:test --tests "*ArchitectureHotspotBudgetTest" --no-daemon
npm run console:test:all
node scripts/console-harness.mjs --matrix multi-tab --viewport desktop-1280
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
git diff --check
```
