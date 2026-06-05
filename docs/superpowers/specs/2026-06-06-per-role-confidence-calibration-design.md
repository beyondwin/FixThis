# Per-Role Edit-Surface Confidence Calibration — Design

Date: 2026-06-06

## Background

`EditSurfaceConfidencePolicy.score()` maps each edit-surface role to a
confidence ceiling and passes the source candidate's confidence through that
ceiling via `cap()`. K1 raised the `CALL_SITE` ceiling to HIGH and K2 let a
confident single shared-component call site keep HIGH on the core matcher side.
The remaining source-backed roles still use flat ceilings that ignore the rich
evidence already present on `SourceCandidate`:

- `COMPONENT_DEFINITION` → capped at MEDIUM
- `COPY_OR_DATA` → capped at MEDIUM
- `LAYOUT_OR_STYLE` → capped at LOW

The roadmap (`docs/product/roadmap.md`, "Smarter source matching") lists richer
per-role confidence scoring for these roles as ongoing follow-up to K1.

`VISUAL_AREA` and `INTEROP_RISK` are intentionally fixed at LOW because they
have no precise source node; raising them would undercut their "verify the
runtime target before editing" warning. They are **out of scope** for this
iteration.

## Goal

Make the three source-backed roles respond to evidence — promote above the
default ceiling under strong, unambiguous evidence and demote below it under
ambiguity or weak anchoring — while keeping confidence explainable and changing
no wire schema.

## Approach

Separate **evidence interpretation** from **ceiling decision**:

- A small `EditSurfaceEvidence` value object computes named boolean signals once
  from a `SourceCandidate`. This keeps brittle `matchReasons` string matching and
  `riskFlags` inspection in one independently testable place.
- `EditSurfaceConfidencePolicy` reads those booleans in each role branch and
  applies explicit promotion/demotion rules. The per-role rules stay inline in
  the policy for readability (only three roles change; a rule-table abstraction
  would be premature).

No new abstraction beyond the evidence value object. No wire/serialization
change — every signal derives from existing `SourceCandidate` fields, so the
core ⇄ mcp ⇄ gradle boundaries are untouched.

## Evidence signals

Derived from `SourceCandidate` (`confidence`, `matchReasons`, `riskFlags`,
`evidenceStrength`, `ownerComposable`):

| Signal | Definition |
|---|---|
| `strong` | `evidenceStrength == STRONG` |
| `exactCopyMatch` | `matchReasons` contains any of SELECTED_TEXT, SELECTED_STRING_RESOURCE, SELECTED_RESOLVED_STRING_RESOURCE, SELECTED_CONTENT_DESCRIPTION (nearby reasons excluded) |
| `ambiguous` | `riskFlags` contains AMBIGUOUS |
| `proximityOnly` | `riskFlags` contains NEARBY_ONLY or TEXT_ONLY |
| `shared` | `riskFlags` contains SHARED_COMPONENT |
| `confidentCallSite` | `confidence == HIGH` and `ownerComposable != null` and not `ambiguous` |

`matchReasons` carries wire labels (e.g. `"selected text"`); the evidence object
matches against `SourceMatchReason` wire labels, not free text.

## Per-role rules

Default ceilings are unchanged from today. Demotion is evaluated before
promotion (safety first). When no condition fires, behavior is the current
`cap(source, defaultCeiling)` — a weak source still degrades naturally.

| Role | Default | Promote → | Promote when | Demote → | Demote when |
|---|---|---|---|---|---|
| `COPY_OR_DATA` | MEDIUM | HIGH | `strong && exactCopyMatch && !ambiguous` | LOW | `ambiguous \|\| proximityOnly` |
| `COMPONENT_DEFINITION` | MEDIUM | HIGH | `!shared && strong && !ambiguous` | LOW | `ambiguous` |
| `LAYOUT_OR_STYLE` | LOW | MEDIUM | `confidentCallSite && !ambiguous` | — | (LOW is already the floor) |

The promoted/demoted ceiling is still applied with `cap(source, ceiling)`, so
the source candidate's own confidence remains an upper bound — promotion raises
the ceiling but cannot invent confidence the source did not have.

### Rationale

- **COPY_OR_DATA → HIGH**: pinning an exact literal or string resource makes
  editing that copy precise and safe. A nearby/text-only weak anchor instead
  demotes to LOW.
- **COMPONENT_DEFINITION → HIGH**: a non-shared (single-owner), unambiguous
  definition is safe to edit. Shared definitions stay MEDIUM because editing one
  changes every call site. This is the definition surface; K2's HIGH handling is
  on the `CALL_SITE` role, so there is no conflict.
- **LAYOUT_OR_STYLE → MEDIUM**: layout/style edits apply at the call site, so a
  confidently matched call site justifies MEDIUM. HIGH stays unreachable because
  the exact modifier line is not pinned.

## Basis strings

Each role keeps a human-readable `basis` explaining the confidence. Promotion and
demotion append a short qualifier so the handoff stays explainable, e.g.
`"matched copy/data: exact literal"` (promoted) or
`"matched copy/data: nearby only — verify"` (demoted). Exact wording finalized
during implementation; the requirement is that promotion/demotion is visible in
the basis, not silent.

## Testing

Mirrors the K1/K2 TDD + corpus-gate pattern.

1. **`EditSurfaceEvidence` unit tests** — each signal in isolation, true/false
   cases, including wire-label matching for `exactCopyMatch`.
2. **`EditSurfaceConfidencePolicyTest` extension** — role × condition matrix:
   - COPY_OR_DATA: exact+strong → HIGH / ambiguous → LOW / proximityOnly → LOW / plain → MEDIUM
   - COMPONENT_DEFINITION: non-shared+strong → HIGH / shared → MEDIUM / ambiguous → LOW
   - LAYOUT_OR_STYLE: confidentCallSite → MEDIUM / plain → LOW
   - demotion-before-promotion precedence (ambiguous + exactCopy simultaneously → LOW)
3. **`HandoffEvaluationCorpusTest` (`v06-corpus.json`)** — add representative
   end-to-end cases (`strong-copy-high`, `single-owner-component-high`,
   `confident-layout-medium`) like K1's `strong-call-site-high`. Re-check the
   handoff markdown budget (currently 7200 chars).
4. **Regression guard** — existing corpus cases (shared-component MEDIUM cap,
   ambiguous) keep their confidence; the HIGH-confidence precision gate (HIGH
   must pin the correct file) still passes.

## Out of scope

- `VISUAL_AREA` / `INTEROP_RISK` calibration (stay fixed LOW).
- Score-based continuous scoring redesign (kept as capped ceilings).
- Any wire schema, serialization, or cross-module boundary change.

## Affected files

- `fixthis-mcp/.../session/EditSurfaceConfidencePolicy.kt` (rules)
- new `fixthis-mcp/.../session/EditSurfaceEvidence.kt` (signal extraction)
- `fixthis-mcp/.../session/EditSurfaceConfidencePolicyTest.kt`
- new `EditSurfaceEvidenceTest.kt`
- `fixthis-mcp/.../session/HandoffEvaluationCorpusTest.kt`
- `fixthis-mcp/src/test/resources/handoff-eval/v06-corpus.json`
