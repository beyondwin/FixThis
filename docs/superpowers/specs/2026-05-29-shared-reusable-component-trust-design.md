# Shared Reusable Component Trust Design

Date: 2026-05-29
Status: Ready for user review
Scope: source matcher trust for reusable Compose component definitions —
detect high fan-in component definitions at index time and prevent the matcher
from rendering them as precise high-confidence edit targets.

Related work:

- [Source Matching Trust Program](2026-05-24-source-matching-trust-program-design.md)
- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)
- [FixThis V1 Trust, Install, And Inner-Loop Hardening](2026-05-27-fixthis-v1-trust-install-inner-loop-hardening-design.md)

## Summary

FixThis source matching already distinguishes strong Compose targets from
visual-area, nearby-only, text-only, and interop-risk matches, and caps
confidence accordingly. One gap remains in the roadmap's "smarter source
matching" track: a reusable composable **definition** (for example
`FixThisButton`, `SectionHeader`, `PrimaryCard`) that is invoked at many call
sites can win matching through its owner-function, composable-symbol, or
test-tag-convention signals and be presented as a precise, high-confidence
edit target.

Editing that definition changes every call site. Presenting it as a precise
target is exactly the "false precision" the Source Matching Trust Program
forbids: trust language must not outrun evidence.

This design adds a conservative, index-time **call-site fan-in** count for
composable definitions and teaches the core matcher to flag a matched
definition with high fan-in as a shared reusable component: it adds a risk
flag, caps confidence at MEDIUM, and emits a caution telling the agent the
definition is shared and to verify the specific call site before editing.

The work is intentionally narrow. It keeps FixThis V1 inside debug-only
Jetpack Compose, local-only ADB/MCP workflows, and additive MCP/session output
compatibility. It does not re-rank candidates, change call-site resolution, or
add new transports.

## Product Goal

Give agents an honest signal when the best source candidate is a shared
component definition rather than a precise edit target, so the agent verifies
the call site instead of editing shared code that affects every usage.

After this change FixThis should answer:

- Is this top candidate a component definition used in many places?
- If so, the handoff must not read as a precise high-confidence target, and the
  caution must say editing it affects all usages.
- A single-use composable definition must be unaffected and may still report
  high confidence when evidence supports it.

## Non-Goals

- Do not re-rank candidates or promote call sites above the shared definition.
  (Behavior option A was chosen: flag + cap + caution only.)
- Do not resolve or render the specific call-site location. The caution directs
  the agent to verify; FixThis does not claim the call site.
- Do not support release builds, View/WebView/Flutter/iOS source targeting, or
  cloud behavior.
- Do not rename persisted MCP JSON fields (`items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, `sourceCandidates`,
  `editSurfaceCandidates`).
- Do not make `:fixthis-compose-core` depend on MCP, CLI, Android UI, local
  artifact paths, or `.fixthis/`.
- Do not make the fixture lab a CI, release, or public install gate in this
  iteration.
- Do not vendor external sample repos, fixture working copies, reports, or
  screenshots.

## Current State

- The Gradle plugin's `KotlinSemanticSignalScanner` produces typed source-index
  signals (`UI_TEXT`, `STRING_RESOURCE`, `CONTENT_DESCRIPTION`, `TEST_TAG`,
  `STRICT_COMP_TEST_TAG`, `COMPOSABLE_SYMBOL`, `LAMBDA_OWNER_FUNCTION`, `ROLE`,
  `ACTIVITY_NAME`, `LAYOUT_RENDERER`, `ARBITRARY_STRING_LITERAL`) on
  `SourceIndexEntry` instances.
- `SourceMatcher` (in `:fixthis-compose-core`) scores entries against the
  selected and nearby nodes, normalizes scores, computes an `EvidenceProfile`,
  and calls `SourceRiskClassifier.applyCaps` plus ambiguity downgrades to
  produce a `SourceCandidate` with `confidence`, `riskFlags`, and `caution`.
- `SourceCandidateRisk` currently has `AMBIGUOUS`, `AREA_SELECTION`,
  `TEXT_ONLY`, `NEARBY_ONLY`, `ARBITRARY_LITERAL`, `ACTIVITY_ONLY`, and
  `UNTYPED_FALLBACK` (serialized as `LEGACY_FALLBACK`).
- `SourceConfidencePolicy.cautionFor` maps the highest-precedence risk flag (or
  bare LOW/NONE confidence) to a caution string.
- `SourceCandidateRiskPrecedence.orderedHighestFirst` defines precedence:
  `AMBIGUOUS > AREA_SELECTION > TEXT_ONLY > NEARBY_ONLY > ARBITRARY_LITERAL >
  ACTIVITY_ONLY > UNTYPED_FALLBACK`.
- `SelectionConfidence` is ordered highest-first (`HIGH < MEDIUM < LOW < NONE`
  by ordinal), and `SourceRiskClassifier.capAt` lowers a confidence to a ceiling
  without raising it.
- There is no notion of how many call sites reference a given composable
  definition, so a heavily reused definition is indistinguishable from a
  single-use one.

## Design Principles

1. Trust language must not outrun evidence: a shared definition is a real but
   imprecise target and must read as such.
2. Public output contracts stay additive: new enum members only, no renames.
3. Detection happens at index time (Gradle), classification happens in core.
   Core only reads a plain field; it gains no new dependency.
4. New patterns must be covered by fixture-lab trust cases before confidence is
   allowed to rise.

## Architecture

The change spans three layers and adds fixture-lab coverage.

### Layer 1 — Index generation (Gradle plugin)

In the source-index builder used by `KotlinSemanticSignalScanner`:

- Add a composable **call-site fan-in** pass. For each composable definition
  symbol `Foo` (a `@Composable`-owned function name already captured as a
  composable/owner symbol), count the number of **distinct call sites** that
  invoke `Foo(...)` across the scanned module sources.
  - A call site is a `Foo(` invocation that is **not** a declaration
    (`fun Foo(`), and not inside a string literal or comment. Reuse the
    existing ignored-range helpers (`layoutRendererIgnoredRanges`,
    `commentRanges`, quoted-string ranges) already used by the layout-renderer
    pass.
  - "Distinct call sites" are counted by source location (file + offset) so the
    same call written once counts once. The definition's own declaration is
    excluded.
- When a definition's fan-in count `>= SHARED_COMPONENT_FANIN_THRESHOLD`
  (constant = **2**), attach a typed signal `SHARED_COMPONENT` to that
  definition's `SourceIndexEntry`, with the signal `value` carrying the count
  as a string (consistent with existing string-valued signals). Confidence
  weight follows the existing signal-weight convention; this signal is a
  classification marker, not a positive match term, so it carries a base match
  weight of `0.0` (it never adds to `rawScore`).

Add `SHARED_COMPONENT` to `SourceSignalKind` (additive). Its `baseMatchWeight`
is `0.0` so it cannot inflate scores; it exists purely so core can read the
fan-in classification.

### Layer 2 — Core matcher classification

In `SourceMatcher`:

- Add `SourceMatchReason.SHARED_COMPONENT_DEFINITION`
  (wire label: `shared component definition`) for explainability.
- In `score(...)`, after the existing `LAYOUT_RENDERER_CONTEXT` post-processing,
  emit `SHARED_COMPONENT_DEFINITION` when:
  - the matched entry carries a `SHARED_COMPONENT` signal, **and**
  - the match reasons indicate the candidate matched **as a composable
    definition** — i.e. one of `SELECTED_OWNER_FUNCTION`,
    `SELECTED_TEST_TAG_CONVENTION_COMPOSABLE`, or a composable-symbol match is
    present. (This prevents flagging an entry that only matched on a literal
    text term that happens to live in a reused file.)
- In `toCandidate(...)`, when `SHARED_COMPONENT_DEFINITION` is present:
  - add `SourceCandidateRisk.SHARED_COMPONENT` to the flags (passed through
    `SourceCandidateRiskPrecedence.ordered`),
  - cap confidence at `MEDIUM` via `SourceRiskClassifier` (so a shared
    definition never reads as HIGH).

The cap is applied inside `SourceRiskClassifier.applyCaps` by adding a branch
that fires when the profile reports a shared-component definition. To keep the
classifier's single-`when` cap precedence intact, the shared-component cap is
applied as an independent ceiling **after** the existing `when` block (it can
co-exist with TEXT_ONLY etc.), capping at MEDIUM. `EvidenceProfile` gains a
`hasSharedComponentDefinition` boolean derived from the match reasons, mirroring
how `hasSelectedOwnerFunction` is already derived.

### Layer 3 — Risk precedence and caution

- Insert `SHARED_COMPONENT` into `SourceCandidateRiskPrecedence.orderedHighestFirst`
  immediately **after `AMBIGUOUS` and before `AREA_SELECTION`**:
  `AMBIGUOUS > SHARED_COMPONENT > AREA_SELECTION > TEXT_ONLY > NEARBY_ONLY >
  ARBITRARY_LITERAL > ACTIVITY_ONLY > UNTYPED_FALLBACK`.
- Add the `SHARED_COMPONENT` branch to `SourceConfidencePolicy.cautionFor`:
  > "Shared component definition (used in multiple places); editing it changes
  > every usage. Verify the specific call site before editing."

  The count is available on the entry's signal; the caution text stays
  count-agnostic to avoid implying a precise inventory, consistent with the
  existing count-free caution style.

### Layer 4 — Edit-surface role (light touch)

`EditSurfaceRoleClassifier` already routes style/component edits with a
component signal to `COMPONENT_DEFINITION` (MEDIUM cap). No re-ranking is added.
The only change: ensure a shared-component candidate keeps producing a
`COMPONENT_DEFINITION` role so the role note and the new caution stay
consistent. No new edit-surface enum value is needed.

## Trust Evaluation

Following the Source Matching Trust Program discipline, add fixture-lab cases
under the existing source-matching fixtures (`fixtures/source-matching/`,
`scripts/source-matching-fixtures.mjs`) **before** allowing any confidence
change to ship:

- **Shared definition case:** a fixture where a composable definition is invoked
  at >= 2 call sites and is selected. Expect: not HIGH confidence,
  `SHARED_COMPONENT` risk flag present, caution token present.
- **Single-use definition case:** a composable definition invoked exactly once.
  Expect: no `SHARED_COMPONENT` flag, confidence allowed to be HIGH when other
  evidence supports it.
- **Non-definition match guard:** a literal-text match into a file that also
  contains a reused definition. Expect: no `SHARED_COMPONENT` flag (the match
  was not a definition match).
- **Regression guard:** existing fixture expectations stay green (no new flags
  on previously-HIGH single-use targets).

These run through the existing trust evaluation report
(`build/reports/fixthis-source-matching/`) and local strict gate.

## Contracts And Compatibility

- **Additive enums only:** `SourceSignalKind.SHARED_COMPONENT`,
  `SourceCandidateRisk.SHARED_COMPONENT`, and
  `SourceMatchReason.SHARED_COMPONENT_DEFINITION`. No persisted-field renames.
- **`:fixthis-compose-core` purity:** the fan-in count reaches core only as a
  typed signal already serialized on `SourceIndexEntry`. Core reads it; it gains
  no MCP/CLI/Android/`.fixthis/` dependency. The architecture ratchet test must
  stay green.
- **Console / bridge tolerance:** `sourceCandidates[].riskFlags` already flows
  to MCP and the feedback console, and the console renders the `caution` string.
  A new risk-flag enum member must be tolerated by the console without error.
  **Verification item:** confirm the console renders the new caution and ignores
  an unknown `riskFlags` member; confirm whether `BridgeProtocol.VERSION` needs a
  bump (expected: no, because `riskFlags` is an existing field and the addition
  is additive, but this must be checked against
  `docs/reference/bridge-protocol.md` before merge).
- **MCP tool docs:** update `docs/reference/mcp-tools.md` and the feedback
  console contract to mention the shared-component caution as best-effort
  guidance, not a precise target claim.

## Testing

- **Unit (Gradle plugin):** call-site fan-in counting — declarations excluded,
  strings/comments ignored, distinct-by-location counting, threshold boundary
  (1 call site → no signal, 2 → signal).
- **Unit (core):** matcher emits `SHARED_COMPONENT_DEFINITION` only on
  definition matches with the signal; `toCandidate` adds the flag and caps at
  MEDIUM; `SourceConfidencePolicy` returns the new caution; precedence ordering
  places `SHARED_COMPONENT` correctly; `EvidenceProfile.hasSharedComponentDefinition`.
- **Fixture lab:** the four trust cases above.
- **Architecture:** clean-architecture boundary ratchet stays green.
- **Full local matrix** per `CONTRIBUTING.md` required checks before PR.

## Open Questions

None blocking. The bridge-protocol bump question is resolved during
implementation as a verification step, not a design fork.
