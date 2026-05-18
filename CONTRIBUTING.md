# Contributing

## Prerequisites

| Tool | Minimum | Notes |
|---|---|---|
| JDK | 21 | Adoptium Temurin recommended. |
| Android SDK + ADB | API 30+ | Required for `:app:assembleDebug` and connected smoke. |
| Node.js | 20.0.0 | Enforced via `package.json` `engines` and `.npmrc engine-strict=true`. Node 20.x LTS is supported through 2026-04; 22.x LTS also supported. Node 18.x is EOL. |
| Chromium | Bundled by Playwright 1.59 | `npx playwright install chromium` after `npm install`. macOS 11+ / Ubuntu 20.04+ required by Playwright's bundled Chromium. |

Run `npm install` and `npx playwright install chromium` once before running any `npm run console:*` script.

## Formatting

Kotlin sources and Gradle Kotlin DSL scripts are formatted by [Spotless](https://github.com/diffplug/spotless)
with the [ktlint](https://github.com/pinterest/ktlint) formatter.

```bash
./gradlew spotlessApply   # format the codebase locally
./gradlew spotlessCheck   # verify formatting (this is what CI runs)
```

CI runs `./gradlew spotlessCheck` before unit tests and fails on unformatted files.
The historical bulk reformatting commit is recorded in
[`.git-blame-ignore-revs`](.git-blame-ignore-revs); configure git to ignore it for
`git blame` with:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Required PR checks

The following table is the canonical contract for which workflows are (or will become) required status checks on pull requests targeting `main`. The actual GitHub branch-protection flip is a maintainer admin action and is deferred until each "Pending" row meets its observation window; readiness is tracked in [`docs/contributing/required-checks.md`](docs/contributing/required-checks.md).

| Check | Workflow | Source task | Status |
|---|---|---|---|
| Build + unit tests | `.github/workflows/ci.yml` (baseline job) | pre-existing | Required (already enforced) |
| Kotlin formatting | `./gradlew spotlessCheck` in ci.yml | CI-1 | Pending — promote after 7 days green |
| Static analysis | `./gradlew detekt` in ci.yml | CI-2 | Pending — promote after 7 days green |
| Console asset bundle | `node scripts/build-console-assets.mjs --check` in ci.yml | post-v0.1 stabilization | Pending — promote with baseline job |
| Console JS harnesses | `npm run console:test:all` in ci.yml | post-v0.1 stabilization | Pending — promote with baseline job |
| CodeQL | `.github/workflows/codeql.yml` | CI-3 | Pending — promote after first analysis lands |
| Nightly connected tests | `.github/workflows/connected-tests.yml` | CI-4 | Informational only — promote after 14 consecutive green |
| Compatibility matrix scheduled | `.github/workflows/nightly-compat.yml` | BR-4 | Informational only — promote after 1 week stable |

The branch-protection flip itself is gated on the "Pending" rows above turning green for the stated observation window. Maintainers update the readiness tracker as windows complete.

## Console Inner Loop

The console JS is live-reloaded; the Kotlin server is pinned in the JAR. Two helper scripts cover the common inner-loop cases.

### `scripts/restart-console.sh` — restart after Kotlin server changes

Use after any change to `:fixthis-mcp` server code. The script kills any running console process, frees the bookmarked port (default `9876`), and starts a new console pointed at the source-tree assets.

```bash
bash scripts/restart-console.sh                 # restart console only
bash scripts/restart-console.sh --with-app      # also reinstall the sample APK
bash scripts/restart-console.sh --dry-run       # preview commands without executing
bash scripts/restart-console.sh --port 9876     # use a non-default port
```

Override the port with `FIXTHIS_CONSOLE_PORT` or `--port`. The script also frees stray `screen` sessions named `fixthis-console-*`.

### `scripts/fixthis-console-dev.sh` — JS-only hot-reload loop

Use after edits to `fixthis-mcp/src/main/console/*` that you rebundle with `node scripts/build-console-assets.mjs`. The script launches `fixthis console` with `--console-assets-dir` (so the source-tree JS is served live), parses the `consoleUrl` from CLI output, and opens it in the default browser.

```bash
scripts/fixthis-console-dev.sh                                # default package
scripts/fixthis-console-dev.sh io.github.beyondwin.fixthis.sample    # explicit package
```

Stop with Ctrl-C; re-running kills any stale `fixthis console` process before starting a new one.

### Documentation consistency check (required)

After editing `package.json`, README, AGENTS.md, or this file, run:

```bash
node scripts/check-doc-consistency.mjs
```

The script verifies that npm scripts and CONTRIBUTING.md agree, that README ↔ AGENTS cross-links exist, that the contributor scripts are documented here, and that every `*.md#anchor` link resolves to a real heading (via `github-slugger`). It exits non-zero with a `FAIL Rx.…` line if any rule breaks.

### Docs ↔ CLI surface check

After editing README.md, AGENTS.md, CLAUDE.md, MCP.md, `docs/getting-started/agent-install-snippet.md`, or any `fixthis-cli/**` source that changes the CLI's command/flag surface, run:

```bash
bash scripts/check-docs-cli-surface.sh
```

The script invokes the installed CLI (building `:fixthis-cli:installDist` first if needed), then scans the five DX docs for `fixthis <subcommand>` and `fixthis <subcommand> --flag` references. Each subcommand must appear in `fixthis --help`, and each flag must appear in the corresponding `fixthis <subcommand> --help`. The same check runs in CI via `.github/workflows/docs-cli-surface.yml` on PRs that touch the docs or the CLI module.

## Required Local Checks

The root build enables the local Gradle build cache by default. Configuration
cache is intentionally still opt-in because `spotlessCheck` does not reliably
reuse it yet; use `--configuration-cache` on focused loops such as
`:app:assembleDebug` or `:fixthis-mcp:installDist` after verifying the command
stores and reuses a cache entry.

Architecture guardrails are part of `:fixthis-mcp:test`. They assert that
`:fixthis-compose-core` stays free of Android/MCP/CLI imports and that known
large handwritten files stay within their hotspot budgets while they are being
split. If a legitimate architecture change needs a new dependency direction,
record the decision in `docs/architecture/adr/` before changing the guard.
Prefer fixing new detekt findings over expanding a baseline; remove stale
baseline entries when a refactor makes them disappear.

### Focused Test Loops

Use focused loops while iterating, then run the full local checklist before
opening or updating a pull request.

```bash
# MCP event-log changes
./gradlew :fixthis-mcp:test --tests '*eventlog*' --no-daemon

# MCP console/server route changes
./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon

# Sidekick Android unit changes
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon

# Pure console JavaScript changes
npm run console:test:fast

# Draft workspace state-machine changes
npm run console:draft:test
```

Per-feature focused harnesses are also available as named npm scripts (each
delegates to `scripts/run-console-tests.mjs` or its dedicated runner):

```bash
npm run console:availability:test   # availability/blocked-state harness
npm run console:pending:test        # pending-item recovery harness
npm run console:beforeunload:test   # beforeunload guard harness
npm run console:undo:test           # undo/redo harness
npm run console:activity:test       # activity-drift harness
npm run console:preview:test        # preview staleness harness
npm run console:browser:reliability # browser reliability proof
npm run console:harness:test        # scenario matrix harness unit tests
npm run console:fsm:test            # connection FSM harness
npm run console:build:test          # build-console-assets unit tests
```

> As of 2026-05-14, `ConsoleFeedbackItemRoutesTest.kt` was split into seven
> focused files. If you previously pinned that class in your IDE run
> configurations, switch to the package filter
> `io.github.beyondwin.fixthis.mcp.console.*`.

Run this before pushing routine work:

```bash
npm run prepush
```

`npm run prepush` is the one-shot preparation command. It applies
Kotlin/Gradle formatting, rebuilds the checked-in console asset bundle with
reproducible metadata, then runs fast push hygiene. It does not run Gradle
static analysis, unit tests, sample assemble, installDist, perf, package, or
release-evidence checks.

Run this before opening a release pull request or cutting a release:

```bash
npm run release:check
```

`npm run release:check` mirrors the full required CI gates locally. It includes
the release-readiness, release-evidence, package, perf, console, Gradle test,
sample assemble, and installDist checks. `npm run ci:local` is kept as the same
full gate, while `npm run ci:local:fast` and `npm run ci:local:changed` remain
available for targeted debugging. Whitespace checks intentionally ignore
Markdown files under `docs/superpowers/`, because those files are historical
planning artifacts; all other files remain enforced.

To install the tracked pre-push hook for this checkout:

```bash
npm run hooks:install
```

The hook runs `npm run prepush` before each push. To bypass it for an
intentional emergency push, run `FIXTHIS_SKIP_PRE_PUSH=1 git push` and follow up
with the CI result immediately.

The full command set is:

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
bash scripts/check-surface-zindex.sh
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
node --check fixthis-mcp/src/main/resources/console/app.js
npm run release:package:test
# All console JS tests (single source of truth is scripts/console-tests.json).
node scripts/run-console-tests.mjs availability canonical pending beforeunload undo activity preview draft session harness
# Equivalent to `npm run console:test:all`; edit the JSON, not this command line.
node scripts/check-whitespace.mjs diff --check
```

`check-release-readiness.mjs` protects public release docs from accidentally
claiming Maven Central or Gradle Plugin Portal publication before artifacts are
actually visible.

When touching feedback-session switching, saved overlays, pending recovery, or
undo/redo context, also run the focused session-scope harnesses:

```bash
npm run console:session:test
```

If you edited any console JS module under `fixthis-mcp/src/main/console/`, rebundle the served asset before running `installDist` and the syntax check:

```bash
node scripts/build-console-assets.mjs
```

### Console Harness

The nightly `Console harness` workflow runs the full Playwright matrix against a
fake bridge fixture. Run it locally before pushing changes that touch
`scripts/console-*` or `fixthis-mcp/src/main/resources/console/**`.

```bash
# Full matrix (all scenarios × all viewports):
npm run console:harness

# Single scenario across all viewports:
node scripts/console-harness.mjs --matrix network-outage

# Single scenario at one viewport (great for debugging):
node scripts/console-harness.mjs --matrix slow-handoff --viewport mobile-390 --headed
```

Environment knobs:

| Env var | Effect |
| --- | --- |
| `FIXTHIS_HARNESS_MATRIX` | CSV of scenario keys; default `all`. |
| `FIXTHIS_HARNESS_VIEWPORTS` | CSV of viewport keys; default `all`. |
| `FIXTHIS_HARNESS_HEADED` | `1` to launch headed Chromium for debugging. |

Failure artifacts (screenshots, traces, console logs) land under
`output/playwright/` and upload to GitHub Actions on nightly failures.

### Console bundle

`node scripts/build-console-assets.mjs` produces three files under
`fixthis-mcp/src/main/resources/console/`:

- `app.js` — minified bundle (must be ≤ 225 KiB raw / ≤ 58 KiB gzipped; the build aborts otherwise).
- `app.js.map` — external source map; DevTools picks it up via the
  `//# sourceMappingURL=app.js.map` trailer when the console is served
  with `--console-assets-dir`. The map is excluded from the packaged JAR.
- `console-build-meta.json` — sidecar with `buildEpochMs` and `gitSha`,
  inlined into `window.FixThisConsoleConfig.buildMeta` by
  `FeedbackConsoleAssets.kt` at serve time.

`node scripts/build-console-assets.mjs --check` verifies all three artifacts
are byte-equivalent to a fresh regeneration under `FIXTHIS_BUNDLE_REPRODUCIBLE=1`.
CI runs this check.

Module load order is a topological sort over `// @requires` directives at
the top of each `fixthis-mcp/src/main/console/*.js` file. The build aborts
if a non-entry-point module lacks a `// @requires` header.

If the build aborts with "esbuild dropped contract symbol", a JS function
the asset contract tests rely on was inlined or renamed by the minifier;
audit the change or extend the `CONTRACT_SYMBOLS` list in
`scripts/build-console-assets.mjs`.

If you changed Gradle build logic, also run:

```bash
./gradlew help --warning-mode all --no-daemon
```

The help task should not print Gradle deprecation warnings. Detekt still runs
for `detekt`, `check`, and build tasks; it is intentionally skipped for
configuration-only help invocations.

Optional console smoke harnesses (require Node + a recent Chromium via Playwright) live under `scripts/`:

```bash
npm run console:smoke                  # end-to-end console smoke
npm run console:responsive:stress      # narrow-width error/agent-state stress test
npm run console:availability:test      # availability/blocked-state harness
npm run console:draft:test             # DraftWorkspace reducer/storage/API/use-case harnesses
npm run console:reliability:test       # Studio reliability: reducer + use-case + polling + idempotency contract tests
npm run console:browser:reliability    # browser proof for SSE sync, stale previews, and closed-session fences
node scripts/console-blocked-harness.mjs # blocked-overlay rendering harness
```

Run the responsive stress harness whenever you touch console layout, global
status messages, activity-drift warnings, or agent-state rendering.

## Connected Device Checks

Connected-device verification is manual until the project has a reliable device or emulator runner. Run the smoke harness when validating device behavior:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample
```

For host-only validation, use:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --host-only
```

Smoke reports are written under ignored `.fixthis/smoke-reports/` as Markdown and JSON. When connected smoke is skipped, record one of these categories in the pull request:

- `SKIPPED_HOST_ONLY`
- `SKIPPED_ADB_NOT_FOUND`
- `SKIPPED_NO_DEVICE`
- `SKIPPED_UNAUTHORIZED_DEVICE`
- `SKIPPED_OFFLINE_DEVICE`
- `SKIPPED_LOCKED_DEVICE`
- `SKIPPED_WIRELESS_ADB_LOST`
- `SKIPPED_MULTIPLE_DEVICES`

## Performance Measurement

The `scripts/perf/` harness measures Gradle and console JS scenario
wall-clock times and compares them against a committed baseline. See
[`scripts/perf/README.md`](scripts/perf/README.md) for details and
[`docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md`](docs/superpowers/specs/2026-05-16-build-test-performance-measurement-design.md)
for the design.

Typical loop when proposing a build/test config change:

```bash
# 1. Confirm main is clean on your machine (no rogue REGRESS/IMPROVE).
node scripts/perf/bench.mjs
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$(ls -t output/perf/run-*.json | head -1)"

# 2. Apply your change, then re-measure.
node scripts/perf/bench.mjs
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$(ls -t output/perf/run-*.json | head -1)"

# 3. Attach the comparator output to the PR description.
```

Re-baseline (`docs/perf/baseline-2026-05-16.json`) only when adopting a
deliberate, reviewed change. Hardware variance between contributors is
expected and the comparator warns rather than fails on environment
mismatch — CI uses its own nightly run as the authoritative baseline.

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/` can contain screenshots or local feedback. Do not commit or share them casually.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names are unchanged unless the pull request explains a migration.
- CLI commands keep their current flags and output shape unless the pull request explains the break.
- Existing Compose public APIs keep source compatibility or the pull request explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
