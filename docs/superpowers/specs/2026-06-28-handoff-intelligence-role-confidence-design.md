# Handoff Intelligence Role Confidence Design

Date: 2026-06-28

## Goal

Make FixThis handoffs tell agents where to edit with role-specific confidence,
clear evidence, and explicit caveats.

The current handoff pipeline already carries source candidates, target
reliability, edit-surface roles, and confidence basis text. The next gap is
consistency: each edit-surface role should apply the same confidence ceiling,
basis wording, and agent action guidance everywhere it is rendered. This design
turns those rules into a small contract that can be tested and reused by the
candidate builder and compact handoff renderer.

## Current Context

The relevant pipeline is:

```text
SourceMatcher
-> AnnotationDto.sourceCandidates
-> EditIntentAnalyzer
-> EditSurfaceRoleClassifier
-> EditSurfaceConfidencePolicy
-> EditSurfaceCandidateService
-> CompactHandoffRenderer
-> fixthis_read_feedback / Copy Prompt
```

Important existing boundaries:

- `fixthis-compose-core` computes source candidates, confidence, match reasons,
  and risks such as `SHARED_COMPONENT`.
- `fixthis-mcp` turns source evidence and annotation intent into edit-surface
  roles such as `CALL_SITE`, `COMPONENT_DEFINITION`, `COPY_OR_DATA`,
  `LAYOUT_OR_STYLE`, `VISUAL_AREA`, and `INTEROP_RISK`.
- `CompactHandoffRenderer` renders the agent-facing compact Markdown and must
  avoid wording that overclaims exact ownership.

The release-readiness tracker already has a v1.0 claim that each edit-surface
role reports role-specific confidence and an explainable basis. This work
focuses on that claim without adding new Android runtime proof steps.

## Scope

In scope:

- define a role confidence contract for every edit-surface role;
- apply the contract in `EditSurfaceConfidencePolicy`;
- keep source confidence and edit-surface confidence separate;
- keep shared-component definition guidance distinct from recommended call-site
  guidance;
- render role, confidence, basis, and action guidance consistently in compact
  handoff Markdown;
- add focused unit and fixture evidence for the role contract;
- update reference and release-readiness docs where the agent-facing contract
  changes.

Out of scope:

- XML/View/WebView exact source targeting;
- WebView DOM inspection;
- browser console redesign;
- MCP persisted field renames;
- bridge protocol changes;
- Android proof runner or release-gate orchestration changes;
- new package channels or publishing work.

## Architecture

The design keeps the existing module boundary.

`fixthis-compose-core` remains the source evidence owner. It should continue to
compute source candidates, confidence, evidence strength, match reasons, margins,
and risk flags. It should not know about agent action wording.

`fixthis-mcp` remains the handoff interpretation owner. The edit-surface package
should contain a compact role contract used by both candidate scoring and
handoff rendering. The contract can live as a new helper or as a focused
extension of `EditSurfaceConfidencePolicy`, but it must be a single source of
truth for role ceilings and action guidance.

`CompactHandoffRenderer` remains the final text owner. It should render the
contract result without deriving a second confidence policy from strings.

## Role Contract

Each role should define:

| Role | Confidence ceiling | Required guidance |
| --- | --- | --- |
| `CALL_SITE` | `HIGH` only when source evidence is high and direct. | Edit the matched call site, then verify the preview. |
| `COMPONENT_DEFINITION` | `HIGH` for single-owner strong evidence; `MEDIUM` for shared definitions; `LOW` when ambiguous. | Editing the definition can affect every call site; verify call-site impact. |
| `COPY_OR_DATA` | `HIGH` only for exact literal, selected string resource, or resolved string-resource evidence. | Confirm whether the change belongs in copy/data or the renderer. |
| `LAYOUT_OR_STYLE` | `MEDIUM` when call-site/layout evidence is strong; otherwise `LOW`. | Treat layout renderer context as an edit hint, not exact ownership. |
| `VISUAL_AREA` | `LOW`. | Source paths are hints because the user selected an area, not a precise semantics node. |
| `INTEROP_RISK` | `LOW`. | Verify runtime target and boundary context before editing; do not claim exact View/WebView ownership. |

The policy must cap edit-surface confidence by both source confidence and role
ceiling. A high source candidate cannot make `VISUAL_AREA` or `INTEROP_RISK`
high. A shared component definition cannot become high merely because one call
site is recommended.

## Candidate Semantics

`EditSurfaceCandidateService` should continue to return a bounded list of
candidate edit surfaces. The distinction to enforce is:

- source candidates describe possible files and lines;
- edit-surface candidates describe the most likely edit surface for the agent;
- shared component definitions and recommended call sites are related evidence,
  but they are not the same edit target;
- visual-area and interop-risk handoffs may include source context, but the
  action remains verification-first.

When no source candidate exists, the service should still produce a low or none
confidence fallback for visual-area or unknown edit surfaces when that is more
helpful than omitting the role entirely.

## Handoff Rendering

Compact Markdown should expose enough structure for agents to act without
scraping prose:

```text
editSurface: spacing role=layout-or-style confidence=low
  basis: layout/style edit applies at the call site
  action: treat source paths as hints and verify before editing
```

The exact wording can follow existing renderer style, but every role should
surface the same concepts:

- `editSurface`
- `role`
- `confidence`
- `basis`
- `action` or equivalent guidance

Warnings and notes should remain separate from confidence enum lines so existing
parsers can keep treating confidence as a simple value.

## Compatibility

Do not rename persisted MCP JSON fields. Existing fields such as
`editSurfaceCandidates`, `role`, `confidence`, `confidenceBasis`, `note`,
`reasons`, `riskFlags`, `callSites`, and `recommendedEditSite` are sufficient
for the first implementation.

If an additive field becomes necessary, it must be optional, absent by default
for older persisted sessions, and documented in the output schema. The preferred
first pass is to reuse `confidenceBasis` and `note` rather than add a field.

## Error Handling

Rules:

- Missing source candidates render low-confidence or unknown fallback guidance
  instead of inventing a precise file.
- Stale or unresolved source paths keep existing caution behavior and add
  verify-before-edit guidance.
- Interop warnings prohibit exact ownership wording.
- Visual-area selections prohibit high-confidence target wording.
- Layout renderer evidence cannot become an exact source-line claim by itself.
- Shared component call-site ranking may recommend a call site, but the shared
  definition remains capped and caveated.

The renderer should degrade gracefully for older sessions that have no
edit-surface candidates or no confidence basis.

## Testing

Add or update tests in `EditSurfaceConfidencePolicyTest`:

- `VISUAL_AREA` and `INTEROP_RISK` stay `LOW` even with high source confidence.
- shared `COMPONENT_DEFINITION` stays at most `MEDIUM`.
- ambiguous component definitions become `LOW`.
- `COPY_OR_DATA` reaches `HIGH` only with exact literal or string-resource
  evidence.
- `LAYOUT_OR_STYLE` does not reach `HIGH` from layout renderer context alone.
- `CALL_SITE` can reach `HIGH` only through strong direct evidence.

Add or update tests in `EditSurfaceCandidateServiceTest`:

- shared component definition and recommended call site are represented as
  distinct guidance.
- missing source evidence still produces useful low-confidence fallback for
  visual-area or interop-risk handoffs.
- role-specific action guidance is available to the renderer without
  duplicating confidence policy.

Add or update tests in `CompactHandoffRendererTest`:

- every role can render role, confidence, basis, and action guidance.
- interop-risk and visual-area output does not read as exact source ownership.
- shared-component output tells the agent to verify call-site impact.
- confidence lines remain parseable enum values.

Add fixture or evaluation evidence:

- extend `fixtures/source-matching/manifest.json` or the handoff evaluation
  corpus with role-specific expectations where existing sample screens already
  provide the right evidence;
- keep the fixture focused on contract behavior, not new Android runtime
  coverage.

## Verification

Expected local verification for the implementation plan:

```bash
./gradlew :fixthis-mcp:test --tests "*EditSurfaceConfidencePolicyTest" --tests "*EditSurfaceCandidateServiceTest" --tests "*CompactHandoffRendererTest" --no-daemon
npm run source-matching:fixtures:test
npm run handoff:eval:test
node scripts/check-release-readiness.mjs
```

If implementation touches core source matching or source-index generation, also
run the relevant `:fixthis-compose-core:test` or `:fixthis-gradle-plugin:test`
focused tests.

## Acceptance Criteria

- Every edit-surface role has a documented confidence ceiling and basis.
- Compact handoff output exposes role, confidence, basis, and action guidance
  for role-bearing candidates.
- High confidence appears only for direct, strong evidence allowed by the role.
- Visual-area, interop-risk, and shared-component cases avoid exact ownership
  claims.
- Existing persisted sessions and MCP output schemas remain compatible.
- The release-readiness claim for role-specific edit-surface confidence has
  fresh local evidence.

## Risks

The main risk is making handoff text too verbose. The mitigation is to keep
role guidance compact and structured, then rely on existing compact handoff
limits.

The second risk is duplicating policy between scoring and rendering. The
mitigation is to keep confidence ceilings and action wording in one contract and
have the renderer display the result.

The third risk is overcorrecting confidence so agents ignore useful source
context. The mitigation is to separate low confidence from no guidance: low
confidence handoffs should still explain what to verify and where to start.
