# Contributing

## Prerequisites

| Tool | Minimum | Notes |
|---|---|---|
| JDK | 21 | Adoptium Temurin recommended. |
| Android SDK + ADB | API 30+ | Needed for `:app:assembleDebug` and connected smoke. |
| Node.js | 20.0.0 | Floor via `package.json` `engines` and `.npmrc engine-strict=true`. Node 20 reached upstream EOL on 2026-04-30; use Node 22 or 24 locally. CI keeps one Node 20 lane until the floor changes. |
| Chromium | Bundled by Playwright 1.59 | `npx playwright install chromium` after `npm install`. |

Run `npm install` and `npx playwright install chromium` once before any
`npm run console:*` script.

## Formatting

```bash
./gradlew spotlessApply   # format locally
./gradlew spotlessCheck   # what CI runs
```

Ignore the historical bulk-format commit in blame:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```

## Required PR checks

Canonical contract for PRs targeting `main`. Live context names:
[`docs/contributing/required-checks.md`](docs/contributing/required-checks.md).

| Check | Workflow | Status |
|---|---|---|
| `Gradle verification` | `.github/workflows/ci.yml` (`gradle-verification`) | Required |
| `Console JavaScript` | `.github/workflows/ci.yml` (`console-js`) | Required |
| `Analyze (java-kotlin)` | `.github/workflows/codeql.yml` | Required |
| `Analyze (javascript-typescript)` | `.github/workflows/codeql.yml` | Required |
| Nightly connected tests | `.github/workflows/connected-tests.yml` | Informational until 14 consecutive green |
| Compatibility matrix scheduled | `.github/workflows/nightly-compat.yml` | Informational until 1 week stable |

## Console Inner Loop

Console JS live-reloads. Kotlin server is pinned in the JAR.

### `scripts/restart-console.sh` — after Kotlin server changes

Kills the running console, frees the bookmarked port (default `9876`), and
starts a new one pointed at source-tree assets.

```bash
bash scripts/restart-console.sh                 # console only
bash scripts/restart-console.sh --with-app      # also reinstall sample APK
bash scripts/restart-console.sh --dry-run
bash scripts/restart-console.sh --port 9876
```

Port override: `FIXTHIS_CONSOLE_PORT` or `--port`. Also frees stray
`screen` sessions named `fixthis-console-*`.

### `scripts/fixthis-console-dev.sh` — JS-only hot reload

Launches `fixthis console` with `--console-assets-dir`, parses `consoleUrl`,
opens the browser.

```bash
scripts/fixthis-console-dev.sh
scripts/fixthis-console-dev.sh io.github.beyondwin.fixthis.sample
```

### Auto-rebundle

```bash
node scripts/build-console-assets.mjs --watch
```

Produces `app.js` (must be ≤ 247,000 B raw / 62,500 B gzip), `app.js.map`,
and `console-build-meta.json`. The console polls that sidecar and reloads
when the bundle hash changes. Reload is gated on `--console-assets-dir`.

The top bar chip shows server build SHA and reconnect state. Use it to
confirm `restart-console.sh` actually delivered a new JAR.

### Documentation consistency

After editing `package.json`, README, AGENTS.md, or this file:

```bash
node scripts/check-doc-consistency.mjs
```

After editing README.md, AGENTS.md, CLAUDE.md, MCP.md,
`docs/getting-started/agent-install-snippet.md`, or CLI command/flag surface:

```bash
bash scripts/check-docs-cli-surface.sh
```

### Repository Agent Kit

Read-only routing. Does not modify personal or project Codex config.

```bash
npm run agent:route -- --task console --json
npm run agent:route -- --changed --base origin/main
npm run agent:route:test
npm run docs:agent-guidance:test
npm run plugin:contract:test
```

After editing `AGENTS.md`, nested `AGENTS.md`, `.agents/skills`,
`.codex-plugin/skills`, or `scripts/agent-*`, run those three test commands.

## Required Local Checks

Local Gradle build cache is on. Configuration cache is still opt-in because
`spotlessCheck` does not reuse it reliably.

Architecture guardrails live in `:fixthis-mcp:test`. If a new dependency
direction is needed, record it in `docs/architecture/adr/` first.

### Local Evidence Profiles

```bash
npm run evidence:fast -- --dry-run
npm run evidence:trust
npm run evidence:console
npm run evidence:release
```

Android-connected trust checks report deferred when SDK or a ready emulator
is missing, unless run with `--strict-runtime`.

### Focused Test Loops

```bash
./gradlew :fixthis-mcp:test --tests '*eventlog*' --no-daemon
./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon
./gradlew :fixthis-compose-sidekick:testDebugUnitTest --no-daemon
npm run console:test:fast
npm run console:draft:test
npm run runtime-evidence:smoke:test
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
```

Named console harnesses:

```bash
npm run console:availability:test
npm run console:pending:test
npm run console:beforeunload:test
npm run console:undo:test
npm run console:activity:test
npm run console:preview:test
npm run console:browser:reliability
npm run console:harness:test
npm run console:fsm:test
npm run console:build:test
npm run console:build:watch:test
npm run console:devReload:test
npm run console:serverBuildChip:test
npm run console:innerloop:test
npm run console:session:test
npm run console:smoke
npm run console:responsive:stress
npm run console:reliability:test
npm run console:harness
npm run console:test:all
```

Before pushing routine work:

```bash
npm run prepush
```

That formats Kotlin/Gradle, rebuilds the console bundle, and runs fast push
hygiene. It does not run the full Gradle matrix.

Before a release PR:

```bash
npm run release:check
```

That mirrors required CI gates. `npm run ci:local` is the same full gate.
`npm run ci:local:fast` and `npm run ci:local:changed` are for targeted
debugging. Whitespace checks ignore Markdown under `docs/superpowers/`.

Runtime-evidence changes, before the full release gate:

```bash
./gradlew :fixthis-cli:test :fixthis-mcp:test --no-daemon
npm run runtime-evidence:smoke:test
npm run console:test:fast
npm run handoff:eval:test
./gradlew spotlessCheck detekt --no-daemon
node scripts/check-doc-consistency.mjs
bash scripts/check-docs-cli-surface.sh
npm run release:package:test
```

Install the tracked pre-push hook:

```bash
npm run hooks:install
```

Bypass only for an emergency: `FIXTHIS_SKIP_PRE_PUSH=1 git push`.

Full command set:

```bash
node scripts/check-doc-consistency.mjs
node scripts/check-release-readiness.mjs
npm run release:v06:evidence:test
npm run docs:agent-bootstrap:test
npm run evidence:test
npm run first-run:smoke:test
npm run detekt:baseline:check
npm run checks:observation:test
node scripts/build-console-assets.mjs --check
bash scripts/check-surface-zindex.sh
node --check fixthis-mcp/src/main/resources/console/app.js
npm run console:test:all
node --test scripts/fixthis-smoke-test.mjs
npm run release:package:test
npm run perf:test
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
node scripts/check-whitespace.mjs diff --check <base>..HEAD
node scripts/check-whitespace.mjs diff --check
```

If you edited `fixthis-mcp/src/main/console/`, rebundle first:

```bash
node scripts/build-console-assets.mjs
```

Console harness (nightly Playwright matrix against a fake bridge):

```bash
npm run console:harness
node scripts/console-harness.mjs --matrix network-outage
node scripts/console-harness.mjs --matrix slow-handoff --viewport mobile-390 --headed
```

Env: `FIXTHIS_HARNESS_MATRIX`, `FIXTHIS_HARNESS_VIEWPORTS`,
`FIXTHIS_HARNESS_HEADED`. Failures land under `output/playwright/`.

If you changed Gradle build logic:

```bash
./gradlew help --warning-mode all --no-daemon
```

That should print no Gradle deprecation warnings.

## Connected Device Checks

```bash
npm run android:proof -- --strict
npm run android:proof -- --strict --continue
```

Reports under `build/reports/fixthis-android-proof/`. Do not commit them.

Focused children:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run runtime-evidence:smoke -- --strict
npm run external-fixture:matrix -- --strict
```

Release-decision extras:

```bash
npm run release:drift
npm run release:drift:test
npm run external-fixture:matrix:test
npm run release:gate
npm run release:gate:test
```

Host-only:

```bash
scripts/fixthis-smoke.sh --package io.github.beyondwin.fixthis.sample --host-only
```

When connected smoke is skipped, record one of:
`SKIPPED_HOST_ONLY`, `SKIPPED_ADB_NOT_FOUND`, `SKIPPED_NO_DEVICE`,
`SKIPPED_UNAUTHORIZED_DEVICE`, `SKIPPED_OFFLINE_DEVICE`,
`SKIPPED_LOCKED_DEVICE`, `SKIPPED_WIRELESS_ADB_LOST`,
`SKIPPED_MULTIPLE_DEVICES`.

## Performance Measurement

See [`scripts/perf/README.md`](scripts/perf/README.md). Active baseline:
`docs/perf/baseline-2026-05-18-linux.json` (GitHub Ubuntu runner). Local Mac
numbers are informational.

## Local Artifacts

`.fixthis/feedback-sessions/`, `.fixthis/preview-cache/`,
`.fixthis/artifacts/`, `.fixthis/smoke-reports/`, and
`.fixthis/runtime-evidence/` can contain screenshots or redacted-but-still-
sensitive diagnostics. Do not commit them.

## Compatibility Checklist

- Existing persisted sessions still decode.
- MCP JSON field names stay unless the PR explains a migration.
- CLI flags and output shape stay unless the PR explains the break.
- Compose public APIs stay source-compatible unless the PR explains the break.
- New coroutine code does not hold monitor locks around disk or bridge I/O.
