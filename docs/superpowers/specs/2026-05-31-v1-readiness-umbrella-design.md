# v1.0-Readiness Umbrella — Design

**Date:** 2026-05-31
**Status:** Approved (design); implementation plan pending
**Topic:** Source-matching depth, interop awareness, console/SSE finish, distribution reach + release hardening

## Summary

A single umbrella spanning four tracks that together advance FixThis toward a
v1.0 external-release gate. Tracks A and B deepen the evidence FixThis produces;
Track C renders that new evidence and finishes the SSE state-sync cleanup; Track
D extends agent reach (ChatGPT writer) and hardens the release-readiness gate.
The release-hardening work runs in the final wave so the v1.0 gate verifies the
features that just landed.

This follows the established umbrella workflow (v0.6 / v0.8 / v0.9): one design
spec → one TDD multi-agent plan → multi-agent executor.

### Scope decisions made during brainstorming

- **Registry channels (PyPI / Docker) are explicitly out of scope.** The roadmap
  lists them as conditional ("only when they preserve the same agent-first setup
  flow") and there is no current demand evidence. Adding package channels only
  grows per-release validation/rollback burden. Revisit when demand appears.
- **Aider writer is out of scope** (works today through Copy Prompt).
- **Track A confidence policy:** a confidently-matched shared-component call site
  is promoted as the *recommended edit surface*, but the component *definition*
  stays capped at medium confidence. No blanket cap raise — explainability and
  the "editing the definition changes every usage" caution are preserved.

## Architecture context

- **`fixthis-compose-core`** — `source/SourceMatcher.kt` produces ranked source
  candidates, shared-component fan-in detection, `SHARED_COMPONENT` risk, and the
  call-site inventory with word-aware `mostLikely` ordering. Core has no
  MCP/CLI/Android dependency (architecture invariant).
- **`fixthis-mcp`** — `session/CompactHandoffRenderer.kt`,
  `session/TargetBoundaryContextFormatter.kt`, `session/TargetBoundaryGuidance.kt`,
  and the console routes render evidence and host SSE (`/api/events`) with
  fallback polling.
- **`fixthis-cli`** — `commands/*ConfigWriter.kt`
  (`ClaudeConfigWriter`, `CodexConfigWriter`, `CursorConfigWriter`,
  `AgentConfigWriter`, `AtomicConfigFileWriter`) implement agent setup writers and
  `--target` selection.
- **Release dashboard** — `docs/contributing/release-readiness.md` is the live
  source of truth for what is publishable vs. pending.

## Track A — Source-matching depth

**Goal:** expand the source patterns FixThis recognizes while keeping confidence
explainable.

1. **`Layout` / `SubcomposeLayout` wrapper recognition.** Recognize custom layout
   wrappers built on `Layout`/`SubcomposeLayout` as composable wrappers so a
   target rendered inside one maps to the right definition / call site instead of
   stalling at the wrapper boundary.
2. **More composable-name conventions.** Extend the name-convention matching used
   for evidence (additional idiomatic Compose naming patterns).
3. **Shared-component call-site disambiguation.** When exactly one call site in a
   shared-component inventory clearly matches the selected node's evidence (clears
   the next site by a decisive margin), surface that call site as the
   **recommended edit surface** with its own confidence. The component
   *definition* candidate remains capped at medium confidence and retains the
   `SHARED_COMPONENT` caution. This builds on the existing `mostLikely` ordering
   rather than replacing it.

**Out of scope:** raising the definition cap above medium; exact source-line
guarantees.

**Testing:** unit tests in `fixthis-compose-core` for each new pattern; a pinned
`source-index` fixture-lab case for the disambiguation path (extends the existing
`SHARED_COMPONENT` fixture).

## Track B — Interop awareness

**Goal:** richer subtree evidence for AndroidView/WebView-risk selections.

For a selection that may cross a View/WebView boundary, gather and present more of
the surrounding Compose subtree — the composable that hosts the interop boundary,
plus relevant ancestor/sibling context — so the agent has a clearer map of where
the boundary sits. FixThis keeps source candidates as verification context for
these selections.

**Out of scope:** XML/View exact source targeting and WebView DOM inspection
(V1 out-of-scope, unchanged).

**Testing:** unit tests for the subtree-evidence formatter; assert interop-risk
selections carry the richer context and that non-interop selections are
unchanged.

## Track C — Console / UX (handoff rendering + SSE finish)

**Goal:** render the new Track A/B evidence and complete the SSE state-sync
cleanup safely.

1. **Handoff/console rendering** for new evidence: a recommended-call-site row for
   Track A disambiguation, and the richer interop subtree context for Track B.
   Reuse existing Evidence-row patterns in `CompactHandoffRenderer` and the
   console.
2. **SSE cleanup, evidence-gated.** Add/confirm instrumentation showing that
   healthy `EventSource` sessions do not exercise fallback session/preview
   polling, then remove the now-redundant manual recovery code. **Gate:** if local
   evidence that the fallback path is unused cannot be produced, this item narrows
   to docs/no-op (as v0.9 Task 5 did when its dead-code gate fired) rather than
   removing code speculatively.

**Testing:** unit/route tests for new render rows; for SSE, evidence captured via
local console run (Playwright visual verification per project convention) before
any code removal.

## Track D — Distribution (ChatGPT writer + release hardening)

**Goal:** extend first-class agent reach and harden the path to a v1.0 gate.

1. **ChatGPT agent writer.** Add `ChatGptConfigWriter` following the
   `Claude`/`Codex`/`Cursor` writer pattern; wire it into `AgentConfigWriter`
   target selection, accept `--target chatgpt`, and document it.
   **Open item for the plan phase:** verify ChatGPT's MCP config location and file
   format before fixing the writer's target path; the writer task must begin by
   confirming this and adjust if ChatGPT does not expose a file-based MCP config.
2. **Release hardening → v1.0 gate.** Complete the release-readiness tracker toward
   an external v1.0 release: a v1.0 release claim manifest plus its check rule
   (extending the existing v0.8 manifest pattern), an evidence checklist for every
   advertised install path, and accurate rollback notes. Define what "v1.0 gate
   GREEN" means concretely.

**Testing:** CLI writer tests mirroring `AgentConfigWriterTest` /
`InstallAgentJsonReportTest` / `DoctorCommandTest`; a docs/claim check rule for the
v1.0 manifest (mirroring the v0.8 check rule).

## Sequencing

- **Wave 1 (parallel):** Track A, Track B, Track D ChatGPT writer — independent.
- **Wave 2:** Track C — renders A/B evidence, finishes SSE; depends on A/B.
- **Wave 3 (final):** Track D release hardening / v1.0 gate — depends on all
  feature tracks landing so the gate verifies the shipped feature set.

## Cross-cutting constraints

- Core stays free of MCP/CLI/Android dependencies.
- Persisted JSON field names are compatibility contracts — additive changes only
  (`sourceCandidates`, `callSites`, risk flags).
- Any bridge-protocol-visible change follows the four-site version-sync checklist;
  none is anticipated for these tracks.
- UI/console changes require Playwright visual verification, not just unit tests
  (`console:smoke` is known-broken).
- Pre-merge: full Gradle matrix + console asset/JS checks + `git diff --check`.

## Out of scope (this umbrella)

- PyPI / Docker registry channels.
- Aider first-class writer.
- XML/View exact source targeting, WebView DOM inspection.
- Raising the shared-component definition confidence cap above medium.
