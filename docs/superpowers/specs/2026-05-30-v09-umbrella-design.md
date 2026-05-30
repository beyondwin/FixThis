# v0.9 Umbrella Design — Source-Trust Precision, SSE Debt Cleanup, Agent Reach, Release Prep

**Date:** 2026-05-30
**Status:** Approved (brainstorming)
**Predecessors:** v0.8 finalization (Track B source-matcher expansion + Track C SSE
fallback-only polling), shared-component call-site ranking.

## Goal

Bundle four independently shippable tracks under one umbrella spec, following the
v0.6–v0.8 pattern (one design + per-track commit/ship boundaries):

- **Track A** — sharpen shared-component call-site evidence without raising the
  medium confidence cap.
- **Track B** — remove SSE manual-recovery / polling debt only after local
  instrumentation proves it is unused on the healthy path.
- **Track C** — add a first-class Cursor MCP config writer.
- **Track D** — prepare the v0.8 release evidence pack and draft release notes
  (no tag push).

## Architecture & Invariants

- Persisted MCP JSON shape is unchanged. All work is additive or cleanup.
- Core has no MCP/CLI/Android dependency; track boundaries respect existing
  module layout.
- Source-trust never overstates: shared-component candidates stay capped at
  **medium** confidence and keep the "editing the definition changes every
  usage" caveat.
- Tracks A and C are independent and parallelizable. Track B is sequential
  (instrument → prove → remove). Track D collects evidence after A/B/C merge.

---

## Track A — Shared-Component Call-Site Evidence Strengthening

### Decision

Disambiguation **improves the evidence/ranking accuracy** of the call-site
inventory; it does **not** raise confidence above medium. The `mostLikely` flag
and the "Shared component used at" console row remain verification context only.

### Current behavior (baseline to preserve)

`SharedComponentCallSiteRanking.rankSharedComponentCallSites` scores each call
site by static context overlap with selection tokens:

- literal-argument match → weight 2.0
- enclosing-function-name match → weight 1.0
- `mostLikely` is marked only when the top score clears the second by a fixed
  margin (`CALL_SITE_MOST_LIKELY_MARGIN = 1.0`).
- Ties and zero-evidence selections preserve the original static `file:line`
  order. Confidence stays capped at medium.

### Changes

1. **Widen and sharpen selection-evidence tokens** in `selectionTokensFor` /
   `callSiteScore` so ranking accuracy improves while keeping the same weighting
   model and the medium cap. Token sources already include `text`,
   `editableText`, `contentDescription`, `role`, and activity name; the change
   tightens normalization and partial-match quality rather than adding new
   confidence signals.
2. **Keep margin/tie semantics explicit and tested** — the boundary cases
   (exact tie, just-below-margin, just-at-margin, zero-evidence) must each have
   a guarding test so future edits cannot silently promote a `mostLikely` flag.
3. **Pinned-repo fixture-lab regression case** (roadmap follow-up): add a
   source-index fixture-lab case asserting a known reused component definition
   emits the `SHARED_COMPONENT` signal and is capped at medium. Reuse an
   existing pinned upstream fixture (Reply / Jetsnack / NIA) with a clearly
   reused component, or the in-repo `fixthis-sample` local-project case.

### Files

- `fixthis-compose-core/.../source/SharedComponentCallSiteRanking.kt` — ranking
  precision.
- `fixthis-compose-core/.../source/SourceMatcher.kt` — wiring (no cap change).
- `fixthis-compose-core/src/test/.../source/*Test.kt` — ranking + cap guards.
- `fixtures/source-matching/manifest.json` — pinned reused-component case.
- `scripts/source-matching-fixtures-test.mjs` — fixture schema/expectation lock.

### Success criteria

- Ranking accuracy demonstrably improves on the fixture case (most plausible
  usage ranked first).
- No path raises a shared-component candidate above medium.
- `mostLikely` margin/tie behavior is fully guarded by tests.

---

## Track B — SSE Manual-Recovery / Polling Debt Removal (Evidence-Gated)

### Decision

Full removal of dead polling/recovery code, but **only after** instrumentation
proves zero polling on a healthy SSE session. Fallback (SSE-disconnected)
polling is retained.

### Phase 1 — Instrument

- Extend the existing `console:browser:reliability` proof (and/or add counters)
  to record session, preview, device, and connection polling invocations under
  a healthy, connected EventSource.
- Assert the count is **zero** on the healthy path; the fallback path still
  polls when disconnected and an interval is configured.

### Phase 2 — Remove (gated on Phase 1 green)

- Delete the manual-recovery / healthy-path polling code paths that the proof
  shows are unused. Keep the disconnected-fallback path and the shared
  preview-application path intact.
- Update the feedback-console contract doc to describe the reduced surface.

### Files

- Console JS: session/preview polling arming logic and manual-recovery helpers.
- Proof harness(es) under the console browser-reliability suite.
- `docs/reference/feedback-console-contract.md` — reduced-surface description.
- `CHANGELOG.md` / `docs/releases/unreleased.md` — cleanup note.

### Success criteria

- Proof asserts zero healthy-path polling before any deletion.
- Disconnected fallback still recovers (covered by an existing/added proof).
- Removed code is gone, not merely flag-gated.

---

## Track C — First-Class Cursor MCP Writer

### Decision

Add Cursor only this cycle, modeled on the existing `ClaudeConfigWriter` JSON
pattern. Defer Aider and a shared-writer abstraction.

### Changes

- New `CursorConfigWriter` implementing `AgentConfigWriter`:
  - `name = "cursor"`, project-scoped.
  - `configFile` → project-local `.cursor/mcp.json`.
  - `merge` writes/updates the `mcpServers.<serverName>` entry idempotently,
    following Claude's JSON merge semantics (preserve unrelated servers,
    replace the target entry).
- Register `cursor` in the `install-agent` target set and `--target all`.
- Reflect the Cursor path in `AgentSetupFiles` output and
  `InstallAgentJsonReport` (generated setup files + JSON report).

### Files

- `fixthis-cli/.../commands/CursorConfigWriter.kt` — new.
- `fixthis-cli/.../commands/{AgentSetupFiles,InstallAgentJsonReport}.kt` and the
  target registry — register cursor.
- `fixthis-cli/src/test/.../commands/*Test.kt` — merge/idempotency/scope tests
  mirroring `ClaudeConfigWriter` coverage; `AgentSetupFilesTest` keeps the
  doctor-first path.
- `docs/getting-started/agent-install-snippet.md`, `docs/reference/cli.md` —
  document `--target cursor`.

### Success criteria

- `fixthis install-agent --target cursor` writes a valid `.cursor/mcp.json`.
- Re-running is idempotent and preserves unrelated MCP servers.
- `--target all` includes cursor; doctor-first agent path is unchanged.

---

## Track D — v0.8 Release Evidence Pack + Notes (No Tag Push)

### Decision

Prepare everything needed to tag v0.8 — evidence manifest, changelog/unreleased
tidy, draft release notes — but **do not push a git tag**. Tagging is a separate,
user-approved step.

### Changes

- `docs/contributing/release-readiness.md` — add a **v0.8 release claim
  manifest** mapping each user-facing claim to its required evidence command(s),
  in the same form as the v0.5/v0.6 sections.
- Run the evidence commands and record results; narrow or drop any claim lacking
  evidence.
- `CHANGELOG.md` / `docs/releases/unreleased.md` — finalize the v0.8 section and
  draft release notes text.
- `scripts/check-release-readiness.mjs` — add rules enforcing the new v0.8
  evidence section and current-channel-only claims.

### Files

- `docs/contributing/release-readiness.md`
- `CHANGELOG.md`, `docs/releases/unreleased.md`
- `scripts/check-release-readiness.mjs`

### Success criteria

- Every v0.8 claim has a passing evidence command listed.
- `check-release-readiness.mjs` enforces the section.
- A reviewable release-notes draft exists. No tag is created.

---

## Cross-Cutting

- No persisted-JSON or bridge-protocol changes.
- Track order: A and C in parallel; B sequential (instrument → prove → remove);
  D after A/B/C merge so its evidence reflects shipped state.
- Final integration pass refreshes any architecture graph/docs touched.

## Out of Scope

- Raising shared-component confidence above medium.
- Aider / generic shared-writer abstraction (future cycle).
- Removing the SSE disconnected-fallback path.
- Actually pushing a git tag or publishing artifacts.
