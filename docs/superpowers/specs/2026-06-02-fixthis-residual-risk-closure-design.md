# FixThis Residual Risk Closure - Design

**Date:** 2026-06-02
**Status:** Proposed
**Topic:** Close the remaining product and release risks identified after the local
`fast` and `trust` evidence profiles passed on `main`.

## Summary

The current local `main` is healthy: the fast evidence profile, trust evidence
profile, strict runtime source-trust fixtures, real Copy Prompt smoke, external
fixture matrix, and agent-loop smoke all passed. The remaining work is therefore
not a defect scramble. It is a residual-risk closure pass that makes the project
harder to misconfigure, easier to release honestly, and less expensive to
maintain.

This design closes four risk groups:

1. CLI Android environment detection must match the evidence runner's stronger
   SDK discovery so `fixthis doctor`, `fixthis run`, and strict evidence checks
   do not disagree in common local setups.
2. Required-check readiness must move from "documented intent" to a concrete
   observation artifact that can be used to promote gates when the evidence is
   green.
3. `fixthis-mcp` detekt baseline debt must be ratcheted down through targeted
   hotspot work instead of being left as a large permanent exception budget.
4. v1 evidence depth must finish the already-approved source matching, interop,
   SSE, and release-gate closure without widening V1 beyond local debug Compose.

## Current Evidence

The risk pass is grounded in these local commands:

```bash
npm run evidence:fast
npm run evidence:trust -- --continue
npm run release:gate:test
```

The `trust` profile passed all runtime-strict entries in this checkout:

- `npm run external-fixture:matrix -- --strict`
- `npm run source-matching:fixtures:runtime -- --strict`
- `npm run real-copy-prompt:smoke -- --strict`
- `npm run agent-loop:smoke -- --strict`

One environment mismatch remains observable: a plain shell `adb devices` can fail
when `adb` is not on `PATH`, while the evidence runner can still locate
`$HOME/Library/Android/sdk/platform-tools/adb` and patch `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, and `PATH` for runtime commands. The CLI currently checks
`ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `local.properties`, then falls back to
plain `adb`. That difference is the highest-leverage immediate fix.

## Goals

- Make CLI ADB discovery deterministic across `ANDROID_HOME`,
  `ANDROID_SDK_ROOT`, project `local.properties`, macOS default SDK, and Linux
  default SDK.
- Improve `doctor --json` failure clarity when ADB is not directly on `PATH` but
  an SDK is discoverable.
- Produce a release-check observation snapshot that cleanly maps workflow
  readiness to `docs/contributing/required-checks.md`.
- Reduce the `fixthis-mcp` detekt baseline budget through small, reviewable
  refactors and lower the budget file in the same change.
- Complete v1 evidence closure for source-matching depth, interop context, SSE
  fallback observability, and release-gate claims using additive data contracts.
- Keep strict runtime evidence honest: connected Android checks pass only when
  Android SDK and a ready emulator/device are truly available.

## Non-Goals

- Do not add PyPI, Docker, or any new package channel.
- Do not add first-class ChatGPT, Aider, or other agent config writers in this
  closure. Copy Prompt remains the supported path for agents without a verified
  file-based MCP config writer.
- Do not target release builds, non-Compose UI stacks, XML/View exact source
  mapping, WebView DOM inspection, Flutter, React Native, or iOS.
- Do not rename persisted JSON fields. Additive fields are allowed; existing
  fields such as `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, and `sourceCandidates` remain compatibility contracts.
- Do not remove SSE fallback polling until browser reliability evidence proves
  healthy EventSource sessions do not use it.

## Architecture

### Track A - Android Environment Normalization

`fixthis-cli` owns ADB command construction and should resolve the executable
through the same practical search order used by local evidence tooling:

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. `local.properties` `sdk.dir`
4. macOS default: `$HOME/Library/Android/sdk`
5. Linux default: `$HOME/Android/Sdk`
6. plain `adb`

This logic stays in `Adb.kt`. The CLI does not mutate the user's shell
environment; it only chooses the executable path it will invoke. Runtime
orchestration scripts may continue to patch env vars for child processes.

### Track B - Required-Check Readiness

`scripts/required-checks-observation.mjs` already summarizes workflow streaks
from GitHub Actions. This track adds a checked artifact path:

- `npm run checks:observation -- --json` produces the source data.
- A new Markdown snapshot under `docs/contributing/required-checks-observation.md`
  records the most recent observed status, command, date, and next action.
- `docs/contributing/required-checks.md` remains the policy tracker.
- `scripts/check-release-readiness.mjs` enforces that the observation snapshot
  exists and points maintainers at the command used to refresh it.

The snapshot is intentionally documentation, not a source of truth for branch
protection. The source of truth remains GitHub Actions plus the required-checks
tracker.

### Track C - Detekt Baseline Ratchet

The `fixthis-mcp` baseline is the largest static-analysis exception budget.
This closure reduces it without large behavioral refactors:

- Split or simplify one hotspot at a time.
- Run `./gradlew :fixthis-mcp:detekt --no-daemon`.
- Lower `config/detekt/baseline-budget.json` only after the actual baseline
  count drops.
- Keep `npm run detekt:baseline:check` as the guard against budget growth.

This is maintenance risk closure. It must not rewrite session persistence,
handoff rendering, or console routing beyond the narrow lint issue being
removed.

### Track D - v1 Evidence Depth And Gate Closure

This track executes the existing v1 readiness direction under a residual-risk
frame:

- `fixthis-compose-core` source matching gets the next safe set of explainable
  patterns: layout renderer wrappers, stronger composable-name evidence, and
  shared-component call-site recommendation while the shared definition remains
  medium-capped.
- `fixthis-mcp` renders richer interop boundary context and recommended edit
  sites in compact handoff output and the console.
- Console SSE instrumentation confirms whether healthy EventSource sessions use
  fallback polling. Fallback removal is allowed only when evidence is green.
- Release readiness gains a concrete v1 gate section that maps claims to
  commands, including strict runtime deferral semantics.

No bridge protocol bump is expected. If implementation discovers a
bridge-visible field change, it must follow `docs/reference/bridge-protocol.md`
before landing.

## Data Contracts

- Existing persisted fields remain stable.
- New source/evidence fields must be additive and nullable where read by older
  sessions.
- Handoff text can add new lines, but must keep the current rule that source
  hints are candidates and agents must verify before editing.
- Release evidence reports stay under `build/reports/` and are not committed.
- `.fixthis/`, `graphify-out/`, and generated build output remain untracked.

## Error Handling

- If no ADB executable is found, CLI commands should preserve the existing
  failure model but include the checked SDK locations in the diagnostic path
  where practical.
- If GitHub observation cannot run because `gh` is unavailable or unauthenticated,
  the readiness snapshot task records the blocker and does not mark workflows
  ready.
- If connected Android prerequisites are unavailable, non-strict evidence reports
  mark runtime checks as deferred with the exact reason. Strict runtime checks
  fail.
- If SSE fallback usage is observed during a healthy EventSource session, the
  implementation must keep fallback code and record a no-op closure instead of
  deleting recovery paths.

## Testing Strategy

### Focused Checks

```bash
./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon
npm run checks:observation:test
npm run detekt:baseline:check
./gradlew :fixthis-mcp:detekt --no-daemon
./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon
npm run source-matching:fixtures:test
npm run console:browser:reliability
npm run release:gate:test
```

### Integration Checks

```bash
npm run evidence:fast
npm run evidence:trust -- --continue
npm run evidence:release
node scripts/check-release-readiness.mjs
git diff --check
graphify update .
```

### Strict Runtime Checks

Run when Android SDK and an unlocked emulator/device are available:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run real-copy-prompt:smoke -- --strict
npm run agent-loop:smoke -- --strict
npm run external-fixture:matrix -- --strict
```

## Acceptance Criteria

- CLI ADB discovery test coverage includes env vars, `local.properties`, macOS
  default SDK, Linux default SDK, and final `adb` fallback.
- `npm run evidence:fast` and `npm run evidence:trust -- --continue` pass after
  changes, with strict runtime entries passed when the current machine has a
  ready emulator/device.
- Required-check observation documentation is present, refreshable, and checked
  by release-readiness validation.
- `fixthis-mcp` detekt baseline budget is lower than the starting budget, and
  `npm run detekt:baseline:check` passes.
- v1 source/interop/SSE/release-gate claims have command-backed evidence in
  `docs/contributing/release-readiness.md`.
- `graphify update .` runs after code/doc changes.
- No `.fixthis/`, `graphify-out/`, build reports, or generated Android build
  output are committed.

## Rollback Plan

- ADB discovery can be reverted independently by restoring `Adb.defaultAdbExecutable`
  and its tests.
- Required-check observation docs can be reverted without affecting runtime.
- Detekt ratchet commits must be reverted with their matching budget changes.
- v1 source/interop/SSE changes must be reverted per track. If SSE removal causes
  regressions, restore fallback code and keep the instrumentation.

## Spec Self-Review

- Placeholder scan: no placeholder tasks or unspecified acceptance gates remain.
- Scope check: the design spans four independent tracks, but each track is
  independently testable and can land separately.
- Compatibility check: persisted JSON field changes are additive only.
- Runtime honesty check: connected Android checks remain strict when requested
  and deferred only in non-strict evidence reports.
