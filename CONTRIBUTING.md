# Contributing

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
| Console JS harnesses | `node --test scripts/*-test.mjs` subset in ci.yml | post-v0.1 stabilization | Pending — promote with baseline job |
| CodeQL | `.github/workflows/codeql.yml` | CI-3 | Pending — promote after first analysis lands |
| Nightly connected tests | `.github/workflows/connected-tests.yml` | CI-4 | Informational only — promote after 14 consecutive green |
| Compatibility matrix scheduled | `.github/workflows/nightly-compat.yml` | BR-4 | Informational only — promote after 1 week stable |

The branch-protection flip itself is gated on the "Pending" rows above turning green for the stated observation window. Maintainers update the readiness tracker as windows complete.

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
node --test \
  scripts/console-availability-test.mjs \
  scripts/pendingItemRecovery-test.mjs \
  scripts/beforeunloadGuard-test.mjs \
  scripts/undoRedo-test.mjs \
  scripts/undoKeymatch-test.mjs \
  scripts/activityDrift-test.mjs \
  scripts/previewStaleness-test.mjs

# Draft workspace state-machine changes
npm run console:draft:test
```

Run these before opening a pull request:

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
  scripts/previewStaleness-test.mjs \
  scripts/draftWorkspace-test.mjs \
  scripts/draftWorkspaceHistory-test.mjs \
  scripts/draftStorageAdapter-test.mjs \
  scripts/draftApiAdapter-test.mjs \
  scripts/draftUseCases-test.mjs \
  scripts/draftCommandQueue-test.mjs \
  scripts/draftPresentationContract-test.mjs \
  scripts/draftWorkflowInvariant-test.mjs
git diff --check
```

When touching feedback-session switching, saved overlays, pending recovery, or
undo/redo context, also run the focused session-scope harnesses:

```bash
node --test \
  scripts/pendingBoundaryGuard-test.mjs \
  scripts/sessionScopedRequests-test.mjs \
  scripts/savedOverlayScope-test.mjs \
  scripts/undoRedoContext-test.mjs
```

If you edited any console JS module under `fixthis-mcp/src/main/console/`, rebundle the served asset before running `installDist` and the syntax check:

```bash
node scripts/build-console-assets.mjs
```

### Console bundle

`node scripts/build-console-assets.mjs` produces three files under
`fixthis-mcp/src/main/resources/console/`:

- `app.js` — minified bundle (must be ≤ 170 KiB raw / ≤ 40 KiB gzipped; the build aborts otherwise).
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
node scripts/console-blocked-harness.mjs # blocked-overlay rendering harness
```

Run the responsive stress harness whenever you touch console layout, global
status messages, activity-drift warnings, or agent-state rendering.

## Connected Device Checks

Connected-device verification is manual until the project has a reliable device or emulator runner. Run the smoke harness when validating device behavior:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample
```

For host-only validation, use:

```bash
scripts/fixthis-smoke.sh --package io.beyondwin.fixthis.sample --host-only
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

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`, `.fixthis/artifacts/`, and `.fixthis/smoke-reports/` can contain screenshots or local feedback. Do not commit or share them casually.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names are unchanged unless the pull request explains a migration.
- CLI commands keep their current flags and output shape unless the pull request explains the break.
- Existing Compose public APIs keep source compatibility or the pull request explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
