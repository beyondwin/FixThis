# Project Risk Closure Implementation Details

Status: Draft
Date: 2026-05-17
Baseline: whole-project risk audit from `main`/equivalent HEAD on 2026-05-17
Scope: local CI readiness, performance regression gate correctness, device and
compatibility gate promotion path, release asset integrity, static-analysis
baseline debt, and release/minify fixture assurance
Related plan: [`../plans/2026-05-17-project-risk-closure.md`](../plans/2026-05-17-project-risk-closure.md)

## Summary

The project is structurally healthy: local-first security boundaries are
present, `.fixthis/` and other generated artifacts are ignored, `npm audit`
reports no vulnerabilities, and the public docs consistently describe the
debug-only Compose support model.

The remaining risk is not one large architecture flaw. It is a set of release
confidence gaps:

- full local CI currently fails on detekt;
- the performance report workflow can mask a regression because it records the
  `tee` exit code instead of the comparator exit code;
- emulator-backed Android coverage and lower-bound compatibility are
  informational, not PR-gated;
- the GitHub Release CLI/MCP tarball is installed without checksum validation;
- large detekt baselines, especially MCP, hide grandfathered complexity debt;
- release/minify assurance is structural, not a real consumer fixture.

This document turns those findings into implementable slices. The companion
plan contains checkbox steps and exact verification commands.

## Current Verification Baseline

Commands observed during the 2026-05-17 audit:

| Command | Result |
| --- | --- |
| `npm run ci:local` | Fails during Gradle `detekt`. |
| `npm audit --omit=dev` | Passes with 0 vulnerabilities. |
| `npm audit` | Passes with 0 vulnerabilities. |
| `git status --short` | Clean at audit end. |

The `npm run ci:local` run passed the Node/doc/console/release package stages
before Gradle verification failed. The Gradle failures were:

| Module | File | Rule | Required correction |
| --- | --- | --- | --- |
| `:fixthis-cli` | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt` | `TooManyFunctions` | Split the catalog into smaller focused factories. |
| `:fixthis-cli` | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/AgentSetupFiles.kt` | `MaxLineLength` | Extract long user-facing recovery strings. |
| `:fixthis-cli` | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt` | `MaxLineLength` | Split readiness JSON insertion into a helper. |
| `:fixthis-cli` | `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt` | `MaxLineLength` | Split the long nested assertion. |
| `:fixthis-mcp` | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/PreviewRoutes.kt` | `TooManyFunctions` | Extract preview screenshot serving into a focused helper. |

## Goals

- `npm run ci:local` passes from a clean checkout.
- The performance workflow fails when `compare-perf.mjs` finds a regression,
  while still posting the markdown report and uploading JSON artifacts.
- Android connected test and lower-bound compatibility promotion are backed by
  an executable observation gate rather than only prose.
- Release tarball installers validate a SHA-256 digest for network-downloaded
  assets and keep local archive test flows explicit.
- The detekt baseline debt has a measured budget and a ratchet that blocks
  accidental growth.
- Release/minify coverage includes at least one real Android consumer fixture
  path, even if the full matrix remains outside PR-time CI.

## Non-Goals

- No release-build FixThis runtime support. The sidekick remains debug-only.
- No View-system, Flutter, remote sync, or external API support.
- No persisted MCP JSON field rename.
- No bridge protocol version bump.
- No forced Maven Central, Gradle Plugin Portal, npm, or MCP Registry publish.
- No broad rewrite of the console or session store.

## RC-1 - Restore Full Local CI

### Current State

CI runs `detekt` in `.github/workflows/ci.yml`. Local `npm run ci:local`
therefore mirrors a real merge blocker. The current failures are mechanical
except the two `TooManyFunctions` findings, which point at files that have
become mixed-responsibility.

### Design

Do not add detekt suppressions for these findings. The fixes are small enough
to reduce responsibility directly.

For `FirstRunReadiness.kt`, keep the serializable models in the same file, but
split factory ownership:

- `FirstRunReadinessCatalog` remains the common public catalog for normal setup
  and connection states.
- `FirstRunReadinessFailureCatalog` owns exceptional capture/session/unknown
  states.
- Call sites using `captureUnavailable`, `sessionMismatch`, or `unknown` move to
  the new object.

This avoids changing DTO fields and keeps factory names discoverable.

For `PreviewRoutes.kt`, extract screenshot-serving concerns into
`PreviewScreenshotResponder`. `PreviewRoutes` should own routing, capture, and
navigation dispatch. The helper should own:

- exact preview screenshot lookup by preview id;
- latest screenshot lookup for the current or explicit session;
- allowed roots and PNG artifact containment checks;
- file byte response.

The existing route tests already cover the behavior. Add one direct helper or
route regression test only if extraction leaves an untested branch.

### Acceptance Criteria

- `./gradlew :fixthis-cli:detekt :fixthis-mcp:detekt --no-daemon` passes.
- Focused tests pass:
  - `./gradlew :fixthis-cli:test --tests "*FirstRunReadinessTest" --tests "*InstallAgentJsonReportTest" --no-daemon`
  - `./gradlew :fixthis-mcp:test --tests "*ConsolePreviewRoutesTest" --tests "*ConsoleNavigationRoutesTest" --no-daemon`
- `npm run ci:local` passes.

## RC-2 - Fix the Performance Regression Gate

### Current State

`.github/workflows/perf-report.yml` pipes the comparator through `tee`:

```bash
node scripts/perf/compare-perf.mjs docs/perf/baseline-2026-05-16.json "$latest" | tee /tmp/perf-report.md
echo "exit=$?" >> "$GITHUB_OUTPUT"
```

With the default GitHub Actions shell for unspecified `run` steps on Linux,
the recorded status is the pipeline's final command status. That is usually
`tee`, not `compare-perf.mjs`. Since `compare-perf.mjs` exits with `1` when
`regressed.length > 0`, the workflow can report a regression in markdown but
still set `steps.compare.outputs.exit` to `0`.

### Design

Make the compare step explicit:

- use `shell: bash`;
- enable `set -o pipefail`;
- capture the comparator pipeline status into `compare_status`;
- write `exit=$compare_status` to `$GITHUB_OUTPUT`;
- keep the step `continue-on-error: true` so report publishing and artifact
  upload still run;
- let the existing "Fail on regression" step turn the output into a hard
  workflow failure.

Add a lightweight text contract test so future edits cannot reintroduce the
same bug without touching a failing test. This repo already uses Node tests for
script/workflow contracts, so use `node:test`.

### Acceptance Criteria

- `node --test scripts/perf/compare-perf-test.mjs scripts/perf/perf-workflow-contract-test.mjs` passes.
- Manual local comparator sanity still passes:
  - regressed fixture exits nonzero;
  - neutral fixture exits zero.
- The workflow file contains `shell: bash`, `set -o pipefail`, and writes the
  captured comparator status to `$GITHUB_OUTPUT`.

## RC-3 - Make Device and Compatibility Promotion Executable

### Current State

`connected-tests.yml` is explicitly nightly/dispatch-only and uses
`continue-on-error: true`. `nightly-compat.yml` is also informational. The docs
explain the promotion windows, but the gate is still manual:

- connected tests: 14 consecutive green nightly runs;
- lower-bound compatibility: one stable weekly window;
- branch-protection flips require maintainer admin rights.

This is acceptable during stabilization, but it is still a residual release
risk for an Android runtime tool.

### Design

Do not immediately make emulator or lower-bound jobs required on every PR.
Instead, harden the promotion path:

- extend `scripts/required-checks-observation.mjs` so it returns machine-readable
  JSON with workflow streaks, latest run URLs, and readiness verdicts;
- add a `--require-ready connected-tests,nightly-compat` mode that exits nonzero
  unless the configured windows are satisfied;
- document exactly when a maintainer may remove `continue-on-error` or add
  branch protection;
- add a manual workflow or documented command that maintainers run before a
  release tag.

The immediate release gate should be: before tagging, the maintainer must run
the observation command and paste the output into the release checklist. This
keeps PR latency stable while preventing the informational workflows from being
ignored indefinitely.

### Acceptance Criteria

- `npm run checks:observation -- --json` prints valid JSON without requiring
  network in unit tests.
- Unit tests cover streak counting, pending runs, failure breaks, connected
  14-run readiness, and compatibility readiness.
- `docs/contributing/required-checks.md`,
  `docs/contributing/connected-tests.md`, and `docs/reference/compatibility.md`
  point at the executable gate.
- Release readiness docs require a fresh observation output before tag creation.

## RC-4 - Verify Release Tarball Integrity

### Current State

The CLI/MCP release workflow uploads and attaches only:

```text
build/release/fixthis-cli-mcp-${VERSION}.tar.gz
```

Both installers download or accept the tarball, extract it, and validate that
the expected executables exist. They do not validate a checksum or signature.
Local archive installs are useful for tests and offline workflows, but network
downloads should verify a digest published by the same release workflow.

### Design

Implement SHA-256 verification as the first integrity layer:

- `scripts/package-cli-release.sh` writes
  `fixthis-cli-mcp-${VERSION}.tar.gz.sha256` next to the archive;
- `.github/workflows/release-cli-mcp.yml` uploads and attaches both files;
- `scripts/install-fixthis.sh` downloads the checksum file for network installs
  and validates the archive before extraction;
- `npm/fixthis/scripts/postinstall.js` downloads the checksum file and validates
  the archive before extraction;
- local archive flows require either a sibling `.sha256`, an explicit
  `--sha256 <digest>`, or an explicit local-only bypass flag used only by tests.

Keep this scoped to digest validation. Signing with Sigstore, GPG, or GitHub
artifact attestations can be a later hardening pass once release operations are
stable.

### Acceptance Criteria

- `npm run release:package:test` covers checksum generation and checksum
  failure for the shell installer.
- `npm run release:npm:test` covers checksum verification and checksum failure
  for the npm postinstall path.
- The release workflow attaches both `.tar.gz` and `.tar.gz.sha256`.
- Installer docs mention that downloaded release archives are SHA-256 checked.

## RC-5 - Ratchet Detekt Baseline Debt

### Current State

Current baseline entry counts:

| Baseline | `<ID>` count |
| --- | ---: |
| `config/detekt/baseline-app.xml` | 54 |
| `config/detekt/baseline-fixthis-cli.xml` | 70 |
| `config/detekt/baseline-fixthis-compose-core.xml` | 57 |
| `config/detekt/baseline-fixthis-compose-sidekick.xml` | 86 |
| `config/detekt/baseline-fixthis-gradle-plugin.xml` | 9 |
| `config/detekt/baseline-fixthis-mcp.xml` | 419 |

The baselines are not themselves a bug, but the MCP baseline size is large
enough that new complexity can hide among old debt unless growth is blocked.

### Design

Add a baseline budget file and checker:

- `config/detekt/baseline-budget.json` records the current count for every
  baseline file;
- `scripts/check-detekt-baseline-budget.mjs` parses XML text and counts `<ID>`;
- the script fails if any count increases above budget;
- the script prints improvements so debt removal is visible;
- `npm run ci:local` and CI run the checker before Gradle verification.

This is a ratchet, not a cleanup project. It blocks new baseline debt while
allowing teams to reduce debt opportunistically.

### Acceptance Criteria

- `node scripts/check-detekt-baseline-budget.mjs` passes at current counts.
- A unit test verifies that count growth fails and count reduction passes.
- `package.json` exposes `detekt:baseline:check`.
- `scripts/verify-ci-local.sh` runs the checker in every mode (it is a cheap
  Node script and is added before the `--full`-only Gradle block).
- CI runs the checker in the console/Node job or an equivalent cheap job before
  Gradle verification.

## RC-6 - Add Real Release/Minify Fixture Assurance

### Current State

`ReleaseGuardTest` and `SidekickConsumerRulesTest` are structural by design.
Their comments explicitly explain why full `assembleRelease` fixture coverage
was downgraded. That was a reasonable stabilization decision, but the residual
risk remains: a downstream release consumer may fail only after manifest merge,
R8, or dependency resolution.

### Design

Add one real consumer fixture path outside the hot PR lane:

- create a minimal Gradle fixture under
  `fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture`;
- include a tiny Android app that depends on the local sidekick artifact;
- run `assembleRelease` with minification enabled;
- inspect the merged release manifest to verify no FixThis startup provider is
  present;
- keep the fixture behind a dedicated Gradle task or nightly workflow at first;
- document the command in release readiness.

The fixture should use local project substitution or a local Maven publication
from the current checkout. It must not download unpublished artifacts or require
publishing credentials.

### Acceptance Criteria

- A local command builds the fixture release variant and fails if the startup
  provider appears in the merged release manifest.
- The command is documented in release readiness as a pre-tag requirement.
- Existing structural tests remain as cheap PR-time coverage.
- If the fixture is too slow for PR-time CI, it runs in a nightly or manual
  workflow with release checklist evidence.

## Rollout Order

1. Restore local CI first. No other work should ship while `npm run ci:local`
   is red.
2. Fix the performance workflow gate. This is low blast radius and protects a
   currently misleading signal.
3. Add checksum generation and verification. This changes user-facing install
   behavior, so tests must land before implementation.
4. Add the detekt baseline ratchet. This prevents the debt from growing while
   cleanup is deferred.
5. Make connected/compat promotion executable. This is mostly script/docs and
   does not affect PR latency.
6. Add the real release/minify fixture. This is the heaviest work and can land
   behind a manual or nightly gate first.

## Verification Matrix

Minimum verification before closing the whole effort:

```bash
npm run ci:local
npm audit
npm audit --omit=dev
npm run release:package:test
npm run release:npm:test
npm run checks:observation -- --help
node scripts/check-detekt-baseline-budget.mjs
./gradlew :fixthis-gradle-plugin:functionalTest --no-daemon
```

If no emulator is available locally, record that connected release/minify
fixture verification was deferred to GitHub Actions or a manual maintainer
machine with Android SDK access.

## Open Decisions

1. **Checksum-only vs signed provenance:** SHA-256 is the immediate fix.
   Signing/attestations should be planned after release operations stabilize.
2. **Connected tests PR gating:** do not promote until the 14-run green streak
   is observed. The executable observation gate makes the decision auditable.
3. **Compatibility PR gating:** the weekly cadence makes branch protection less
   natural than a pre-release gate. Promote only after one stable week and a
   maintainer decision.
4. **Detekt cleanup target:** the first target is no growth. Separate cleanup
   plans can reduce the MCP baseline in focused modules.
