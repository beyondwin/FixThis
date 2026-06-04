# Source Matching Refinement — Design

Date: 2026-06-04
Status: Approved (brainstorming) — pending spec review before plan

## Goal

Improve the trustworthiness of source candidates without widening V1 scope.
The "pin to source" handoff is FixThis's core differentiator; this iteration
sharpens two outcomes:

1. **Calibration / per-role confidence** — make `confidence` reflect evidence
   strength and edit-surface role, so agents trust correct candidates and doubt
   weak ones.
2. **Coverage** — recognize project-defined `testTag` conventions that the
   current closed `comp:`/`screen:` set misses, the most common real-app
   adoption snag.

A measurement foundation is built **first** so every calibration change is
guarded by a regression gate rather than tuned by intuition.

## Background (current state)

The matcher is mature. Confirmed already implemented (roadmap text is partly
stale):

- `Layout`/`SubcomposeLayout` recognition via `LAYOUT_RENDERER` signal
  (`KotlinSemanticSignalScanner`).
- Shared-component call-site disambiguation via `SharedComponentCallSiteRanking`
  (`mostLikely` / `recommendedEditSite` margin heuristic).
- `SHARED_COMPONENT` MEDIUM cap with sibling-entry inheritance.

Key components touched:

- `fixthis-compose-core` — `SourceMatcher`, `SourceScoringPolicy`,
  `SourceRiskClassifier`, `EvidenceProfile`, `TestTagConvention`, `SourceIndex`.
- `fixthis-mcp` — `EditSurfaceRoleClassifier`, `HandoffEvaluationCorpusTest`
  and `handoff-eval/v06-corpus.json`.
- `fixthis-gradle-plugin` — `FixThisExtension`, `KotlinSourceScanner`,
  `SourceIndexAssets`, `strictCompTestTagRegex`.

Architecture invariant: **core has no gradle/Android dependency.** Convention
config must reach the matcher through the serialized source-index JSON, not a
gradle type.

## Identified gaps (from analysis)

Coverage:

- **C1 — testTag convention rigidity.** `TestTagConvention` accepts only
  `comp:`/`screen:` with `:`/`.` delimiters (closed set, kept in sync with the
  gradle `strictCompTestTagRegex`). Project schemes like `MyScreen_button` get
  no owner signal.

Calibration:

- **K1 — role confidence is cap-only.** `EditSurfaceRoleClassifier` lowers a
  ceiling per role but never lets role refine the score; `CALL_SITE` is capped
  at MEDIUM even with strong, unambiguous evidence.
- **K2 — confident call-sites still MEDIUM.** `SourceRiskClassifier` caps every
  shared-component candidate at MEDIUM, even when call-site ranking marks a
  single `mostLikely` site with a clear margin.

Measurement:

- The corpus is 9 hand-authored cases with no per-confidence-bucket precision
  metric, so K1/K2 cannot be tuned against data. The roadmap's pinned
  `SHARED_COMPONENT` fixture-lab case is also still open.

## Scope (this spec)

In order: **Measurement → C1 → K1 → K2.** Measurement lands first so the
calibration changes (K1, K2) are validated by gates, not intuition.

### 1. Measurement foundation

- Extend `HandoffEvaluationCorpus` (currently `schemaVersion 2`) with a
  per-case `expectedConfidence` band and a **precision gate**: when a case
  declares `expectedConfidence == HIGH`, its top-1 candidate file must equal the
  expected file. The existing "weak case must not be HIGH" gate is retained.
- Add new static corpus cases covering: a custom-convention tag (C1), a
  confident shared-component call-site (K2), and per-role scenarios (K1).
- Add the **`SHARED_COMPONENT` fixture-lab case**: a pinned static source-index
  fixture asserting the `SHARED_COMPONENT` signal is emitted for a known reused
  component definition.
- Everything stays **static (no device)** so it runs in CI. Runtime
  device-backed fixtures remain the existing strict/connected layer, unchanged.

### 2. C1 — configurable testTag conventions (approach A+C)

- **DSL:** add `testTagConventions` to `FixThisExtension`. Default presets
  remain the current `comp`/`screen` `:`/`.` forms (no behavior change when
  unset). Built-in named presets (e.g. `underscore`, `colon`, `dot`) cover
  common schemes safely. Advanced custom patterns are accepted only in a
  validated form: anchored (`^...$`), bounded length, no unbounded
  backtracking-prone constructs.
- **Serialization (approach A):** bake the resolved convention set into the
  generated source-index JSON as a new top-level header field; bump source-index
  `schemaVersion` `1.2 → 1.3` (additive; older assets without the field fall
  back to the built-in default set).
- **Scan side:** `strictCompTestTagRegex` / scanner emit `STRICT_COMP_TEST_TAG`
  for the configured conventions, so custom schemes get the strong signal — not
  only the weaker convention-composable fallback.
- **Match side:** `TestTagConvention` becomes config-driven, reading the
  convention set from the source-index header instead of a hardcoded list.
  Scan and match share one source of truth.
- **Safety:** custom patterns validated at config-resolution time; invalid
  patterns fail the gradle task with a clear message rather than silently
  degrading.

### 3. K1 — per-role confidence refinement

- Reframe `EditSurfaceRoleClassifier.confidenceCap` from "uniform downgrade" to
  a **per-role ceiling**:
  - `CALL_SITE` ceiling MEDIUM → **HIGH** (strong selected evidence + clear
    margin may keep HIGH instead of being capped).
  - `COMPONENT_DEFINITION`, `COPY_OR_DATA` → MEDIUM (unchanged).
  - `VISUAL_AREA`, `INTEROP_RISK` → LOW (unchanged).
- Document the rationale per role inline so the ceiling choices are auditable.
- Guarded by the new precision gate from the measurement foundation.

### 4. K2 — confident call-site relaxes the shared-component cap

- `SourceRiskClassifier` keeps the MEDIUM shared-component cap **except** when
  all hold: the candidate has a single `mostLikely`/`recommendedEditSite` from
  call-site ranking, the ranking margin is clear, there is exactly one such
  site, and a selected owner/tag locator is present. In that case the cap rises
  to HIGH.
- The `mostLikely` decision already computed in `SharedComponentCallSiteRanking`
  is threaded into the cap decision (today the cap never sees it).
- Guarded by a dedicated corpus case (confident vs. ambiguous shared-component).

## Out of scope

- Runtime/device-backed eval changes (existing strict layer stays as-is).
- New app stacks, XML/View source targeting, WebView DOM (V1 boundary).
- Free-form user regex without validation (rejected for backtracking risk).
- Slot/content-lambda owner attribution (C2) and formatted/plural string
  resources (C3) — deferred; not selected for this iteration.

## Testing strategy

- Core unit tests for `TestTagConvention` config-driven parsing and pattern
  validation.
- Gradle scanner tests asserting `STRICT_COMP_TEST_TAG` emission for configured
  conventions and `schemaVersion 1.3` header round-trip.
- `HandoffEvaluationCorpusTest`: new precision gate + new cases (custom
  convention, confident call-site, per-role).
- `SourceRiskClassifier` / `SourceMatcher` unit tests for the K1 ceilings and
  K2 cap relaxation, including the ambiguous-case negative.
- Backward compatibility: source-index assets without the convention header
  resolve to the built-in default set (no regression for existing projects).

## Sequencing rationale

Measurement first establishes the baseline and the precision gate. C1 next
because it adds a recognizable evidence kind that later cases can exercise. K1
and K2 last because they are confidence reweightings that must be proven safe
against the now-extended corpus.
