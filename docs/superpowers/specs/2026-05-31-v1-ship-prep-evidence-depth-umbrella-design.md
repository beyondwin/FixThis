# v1.0 Ship-Prep + Evidence-Depth Umbrella — Design

**Date:** 2026-05-31
**Status:** Approved (design); implementation plan pending
**Topic:** Per-role confidence scoring, source-matching pattern breadth, and
v1.0 release preparation (no tag/publish)

## Summary

A single umbrella spanning three tracks that deepen FixThis's evidence and then
prepare — but do not cut — the v1.0 external release. Track B widens the source
patterns the matcher recognizes; Track A gives each edit-surface role its own
confidence rubric (consuming the new signals); Track C runs last so the release
evidence and notes cover what just landed.

This follows the established umbrella workflow (v0.6 / v0.8 / v0.9 / v1.0-readiness):
one design spec → one TDD multi-agent plan → multi-agent executor.

### Scope decisions made during brainstorming

- **Release track prepares only; a human tags and publishes.** Track C gathers
  v1.0 release-claim evidence, confirms the v1.0 gate is green, and drafts the
  changelog / release notes. It stops before `git tag` and before any push to
  npm / Homebrew / Maven. Tagging and publishing are irreversible, externally
  visible actions reserved for a human.
- **Agent writers are out of scope.** The Aider / Gemini first-class writer work
  was considered and explicitly dropped this round — those agents already work
  through Copy Prompt. Revisit when demand appears.
- **No confidence-cap raise.** Per-role scoring stays explainable and preserves
  the existing caution caps (`SHARED_COMPONENT` capped at medium, interop-risk
  at low). This is specialization, not loosening.
- **Registry channels (PyPI / Docker) remain out of scope** (unchanged from the
  roadmap; conditional on demand).

## Architecture context (verified against current code)

- **`fixthis-compose-core`** — `source/SourceMatcher.kt` (584 lines) produces
  ranked source candidates and a single `SelectionConfidence` via
  `baseConfidenceFor(profile, margin)`. Core has **no role concept** and no
  MCP/CLI/Android dependency (architecture invariant). `SourceConfidencePolicy.kt`
  maps confidence + risk flags to caution text.
- **`fixthis-mcp`** — `session/EditSurfaceRoleClassifier.kt` (86 lines)
  classifies the six edit-surface roles and currently assigns each a **static
  `confidenceCap`** (LOW or MEDIUM). There is no per-role scoring logic yet.
  `session/EditSurfaceCandidateService.kt` assembles edit-surface candidates;
  `CompactHandoffRenderer.kt` and the console routes render them.
- **Release dashboard** — `docs/contributing/release-readiness.md` is the live
  source of truth. Evidence checks live in `scripts/check-release-readiness.mjs`
  and the per-version evidence scripts (e.g. `check-v06-release-evidence.mjs`).

## Key design decision — where Track A lives

Two options were considered for per-role confidence:

- **(A1, chosen)** Keep the per-role rubric **inside `fixthis-mcp`** — extend
  `EditSurfaceRoleClassifier` (or add an `EditSurfaceConfidencePolicy`) that
  consumes core's `SelectionConfidence`, the candidate `matchReasons`, and the
  `EditIntent`, then applies role-specific signals and weights. Core stays
  role-agnostic (architecture invariant preserved); the change is localized to
  the module that already owns roles.
- **(A2, rejected)** Push role awareness into core so confidence is computed
  per-role at the source. This would violate the core architecture invariant
  (core gains a role concept) and widen the blast radius.

## Track A — Per-role confidence scoring (evidence depth, `fixthis-mcp`)

**Goal:** replace the static per-role `confidenceCap` with a per-role rubric so
each of the six edit-surface roles derives a role-appropriate confidence from its
own signal set, while preserving explainability and existing caution caps.

The six roles: `CALL_SITE`, `COMPONENT_DEFINITION`, `COPY_OR_DATA`,
`LAYOUT_OR_STYLE`, `VISUAL_AREA`, `INTEROP_RISK`.

1. Introduce a per-role scoring rubric (location per A1). Each role maps the
   inherited core `SelectionConfidence` plus role-relevant evidence (candidate
   `matchReasons`, owner composable, `comp:` test tags, interop warnings, intent
   kind, and the new Track B signals) to a role-appropriate confidence.
2. Preserve existing caps and cautions: `SHARED_COMPONENT` stays capped at
   medium with its caution; `INTEROP_RISK` and `VISUAL_AREA` stay low; the
   `COMPONENT_DEFINITION` "editing the definition changes every usage" caution is
   retained.
3. Each edit-surface candidate carries its role-specific confidence plus a short
   "why" string derived from the contributing signals, so agents and users can
   verify the basis of the rating.

**Out of scope:** raising any role above its current cap; exact source-line
guarantees.

**Testing:** unit tests in `fixthis-mcp` asserting each role produces the
expected confidence from representative evidence, and that existing caps/cautions
are preserved. If the console / handoff rendering of the per-role confidence
changes, capture Playwright visual verification per project convention.

## Track B — Source-matching pattern expansion (breadth, `fixthis-compose-core`)

**Goal:** widen the source patterns `SourceMatcher.kt` (plus supporting policy /
index) recognizes, keeping confidence explainable. Each pattern is unit-tested;
selected paths get a pinned `source-index` fixture-lab case.

1. **Lazy list item lambdas.** Recognize `items{}` / `itemsIndexed{}` inside
   `LazyColumn` / `LazyRow` / `LazyVerticalGrid` so a selection inside the item
   lambda maps to the item-content composable rather than stalling at the list.
2. **Navigation destinations.** Recognize `NavHost` / `composable(route){}` so a
   selection in a destination maps to the destination composable.
3. **More layout wrappers.** Recognize custom `Box` / `Row` / `Column`-based
   wrappers and `Scaffold` slots (`topBar` / `bottomBar` / `content`) as
   composable boundaries, extending the existing wrapper recognition from the
   v1.0-readiness Track A.
4. **Modifier-chain signals.** Extract meaningful identifiers from modifier
   chains (`clickable`, `testTag`, `semantics`) and use them as evidence signals
   (new `matchReasons`).

These new `matchReasons` become inputs that Track A's per-role rubric consumes.

**Out of scope:** raising the confidence cap; XML/View exact source targeting;
WebView DOM inspection.

**Testing:** unit tests in `fixthis-compose-core` for each of the four patterns;
a pinned `source-index` fixture-lab case for at least the Lazy-list and
Navigation paths.

## Track C — v1.0 release preparation (no tag/publish)

**Goal:** prepare a publishable v1.0 release that includes the Track A/B work,
stopping before any irreversible action.

1. Gather the v1.0 release-claim evidence by running the required checks
   (`scripts/check-release-readiness.mjs`, the full Gradle matrix, console asset
   + JS syntax + harness checks per `CONTRIBUTING.md`).
2. Confirm the v1.0 release gate / claim manifest is green.
3. Draft the `CHANGELOG.md` entry and release notes covering the Track A/B
   features that just landed; update `docs/contributing/release-readiness.md`.
4. **Stop before `git tag` and before any registry publish.** A human cuts the
   tag and pushes to npm / Homebrew / Maven.

**Out of scope:** creating the git tag; publishing to any registry; advertising
PyPI/Docker.

**Testing / evidence:** record the required check-script and Gradle outputs in
the release manifest / issue. No code removal in this track.

## Wave plan and dependencies

- **Wave 1 — Track B** (core signals land first).
- **Wave 2 — Track A** (per-role rubric, consuming both existing and the new
  Track B signals).
- **Wave 3 — Track C** (release evidence and notes reflect Tracks A and B).

Track A depends on Track B's new `matchReasons`; Track C depends on both A and B
landing. The ordering is therefore sequential B → A → C.

## Architecture invariants honored

- Core (`fixthis-compose-core`, Track B) keeps no MCP/CLI/Android dependency and
  no role concept.
- Per-role confidence (Track A) lives in `fixthis-mcp` where roles already live.
- Persisted JSON field names are a compatibility contract — any additions are
  additive, no renames or removals.

## Explicitly out of scope

- First-class agent writers (Aider / Gemini / others) — dropped this round.
- PyPI / Docker package channels.
- Raising the `COMPONENT_DEFINITION` cap above medium.
- Guaranteed exact source-line mapping.
- Creating the git tag or publishing the v1.0 release.
