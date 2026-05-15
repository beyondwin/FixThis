# Copy Prompt Edit Surface and Source Ranking Hardening Design

**Date:** 2026-05-15
**Status:** Proposed
**Related work:**
- `docs/superpowers/specs/2026-05-09-compact-handoff-prompt-v2-design.md`
- `docs/superpowers/plans/2026-05-15-copy-prompt-target-evidence.md`
- `docs/superpowers/plans/2026-05-15-target-reliability-handoff-v2.md`
- `docs/reference/feedback-console-contract.md`
- `docs/reference/output-schema.md`
- `docs/reference/mcp-tools.md`

**Primary modules:** `fixthis-compose-core` source matching, `fixthis-mcp` session enrichment and compact handoff rendering.

---

## Purpose

Real Copy Prompt output now includes target summaries, reliability warnings,
source candidates, screenshot paths, and claim/resolve protocol details. That
is enough for an agent to start work, but the latest sample handoff exposed one
remaining failure class: FixThis can identify the UI target correctly while
ranking the wrong source file as the most likely edit location.

The root issue is that `sourceCandidates` currently mix two distinct concepts:

1. **Data/source origin:** where selected text or nearby text appears.
2. **Edit surface:** where a requested style, layout, or component behavior
   change should probably be made.

For text-content edits those are often the same file. For visual feedback
(`background green`, `text blue`, `make text bigger`, `add bottom margin`) they
are usually different. The prompt needs to preserve source candidates while
also telling agents which rendering surface to inspect first.

## Concrete Evidence

Session `be03fb71-aefe-4346-bb47-69357400eff6`, package
`io.github.beyondwin.fixthis.sample`, screen `MainActivity`, showed six annotations:

| item | user request | target | current top source behavior | correct agent interpretation |
| --- | --- | --- | --- | --- |
| 1 | `여기 배경 초록색` | `comp:MetricCard:summary` card | `HomeScreen.kt:44`, `MetricCard.kt:17` high | inspect `MetricCard` container color, possibly call-site variant |
| 2 | `여기 글자 파란색` | text `Resolved this week` | `FixThisDemoData.kt:59` top | data line identifies the metric; style likely lives in `MetricCard.kt` label `Text` |
| 3 | `여기 텍스트 더크게` | text `Priority feedback` | `HomeScreen.kt:50` / data candidates | style likely lives in `SectionHeader.kt` title `Text` |
| 4 | `여기 글자 빨간색` | text `Open queue` | nearby-only data lines outrank selected text call site | selected text should beat nearby-only matches; style likely `SectionHeader.kt` action `Text` |
| 5 | `여기 아래 바텀마진 8dp더` | first `FeedbackCard` visible area | nearby-only data line top | spacing likely belongs to `HomeScreen.kt` list arrangement or `FeedbackCard` modifier |
| 6 | `여기 텍스트컬리 보라색` | `Resolved` state chip | enum/data candidates top | color mapping likely lives in `StateChip` / `StatusChip` |

The prompt was not wrong to include the data candidates. It was incomplete
because it did not separate "where the text came from" from "where style/layout
edits should happen".

## Goals

- Keep `sourceCandidates` as source-origin hints and continue warning agents
  that they are candidates, not instructions.
- Add an edit-surface layer for compact handoffs so visual feedback points to
  likely rendering files and component definitions.
- Prevent selected target evidence from being outranked by unrelated
  nearby-only matches.
- Enrich `target:` output with owner context, such as the nearest containing
  tagged composable, so list-rendered text nodes are easier to disambiguate.
- Make warnings actionable by explaining the specific conflict, not just saying
  "verify before editing".
- Add golden regression coverage based on the sample handoff failure modes.

## Non-Goals

- Do not remove `sourceCandidates` or change their existing JSON field name.
- Do not rename persisted compatibility fields listed in `AGENTS.md`.
- Do not add LLM-based intent parsing. Classification must be deterministic,
  local-first, and testable.
- Do not change the Android bridge protocol.
- Do not require app developers to add new tags before this feature works.
- Do not attempt perfect source-to-AST edit location inference. This feature
  should improve first inspection targets, not auto-edit code.
- Do not embed long source snippets in Copy Prompt.

## User-Facing Output

Compact Copy Prompt should keep the current shape but add two optional lines
when useful:

```text
[2] 여기 글자 파란색
  id: 420990de-fdcb-4b5a-ae87-7ecea21328cc
  target: text="Resolved this week"; inside tag="comp:MetricCard:summary"; instance 3/3
  editSurface: textColor -> components/MetricCard.kt:26  conf=medium  why=style-intent,target-owner
  model/FixThisDemoData.kt:59  conf=medium  margin=0.25  matched=[text, nearbyText, literal]
  note: source candidate identifies data text; editSurface identifies likely rendering code
  targetConfidence=medium
```

For selected text that currently loses to nearby-only source candidates:

```text
[4] 여기 글자 빨간색
  id: cbc4bd08-92f5-4e2b-a23c-94d9ddf96441
  target: text="Open queue"; inside composable="SectionHeader"
  editSurface: textColor -> components/SectionHeader.kt:29  conf=high  why=style-intent,selected-text-renderer
  screens/HomeScreen.kt:50  conf=medium  margin=0.16  matched=[text, literal]
  model/FixThisDemoData.kt:122  conf=low
  warning: nearby-only candidates were demoted below selected target matches
```

For visual-area spacing requests:

```text
[5] 여기 아래 바텀마진 8dp더
  id: b381de29-f717-44ff-a1f9-61f224e9b5f5
  target: semantics node; contains text="FX-1042"; inside composable="FeedbackCard"
  editSurface: spacing -> screens/HomeScreen.kt:51  conf=medium  why=layout-intent,list-item-spacing
  editSurface: spacing -> components/FeedbackCard.kt:36  conf=low  why=layout-intent,component-container
  warning: visual/parent target; verify screenshot and code before editing spacing
```

## Proposed Data Model

Add an optional MCP session field on `AnnotationDto`:

```kotlin
@Serializable
data class EditSurfaceCandidateDto(
    val kind: EditSurfaceKindDto,
    val file: String,
    val repoFile: String? = null,
    val line: Int? = null,
    val confidence: SelectionConfidence,
    val reasons: List<EditSurfaceReasonDto> = emptyList(),
    val note: String? = null,
)

@Serializable
enum class EditSurfaceKindDto {
    CONTAINER_COLOR,
    TEXT_COLOR,
    TYPOGRAPHY,
    SPACING,
    CHIP_COLOR,
    COMPONENT_RENDERER,
    UNKNOWN,
}

@Serializable
enum class EditSurfaceReasonDto {
    STYLE_INTENT,
    LAYOUT_INTENT,
    TYPOGRAPHY_INTENT,
    TARGET_OWNER,
    SELECTED_TEXT_RENDERER,
    COMPONENT_DEFINITION,
    CALL_SITE,
    LIST_ITEM_SPACING,
    COMPONENT_CONTAINER,
}
```

Then add:

```kotlin
val editSurfaceCandidates: List<EditSurfaceCandidateDto> = emptyList()
```

to `AnnotationDto`.

This is additive and optional. Existing sessions deserialize with an empty list.
The field is MCP/session-local; no bridge protocol change is required.

## Target Owner Context

Add a resolver that uses the persisted screen snapshot to derive the nearest
owner for an item:

- For a selected text node, find containing merged nodes whose bounds contain
  the selected node and whose path is a prefix of the selected path.
- Prefer owner nodes with `testTag`, especially `comp:<Composable>:<variant>`.
- Fall back to the nearest meaningful parent with text/contentDescription/role.
- For visual area selections, find the smallest overlapping meaningful node,
  then its tagged owner if present.
- Render owner context only when it adds information not already present on the
  selected node.

Example:

```text
target: text="Resolved this week"; inside tag="comp:MetricCard:summary"; instance 3/3
```

This turns bare text nodes into component-scoped targets without changing the
Android capture payload.

## Edit Intent Classification

The classifier is deterministic and keyword-based. It must support Korean and
English phrases because feedback can be natural language and short.

| Kind | Korean triggers | English triggers |
| --- | --- | --- |
| `CONTAINER_COLOR` | `배경`, `배경색`, `카드색` | `background`, `container`, `card color` |
| `TEXT_COLOR` | `글자`, `텍스트`, `컬러`, `색`, color words with text target | `text`, `label`, `color`, `blue`, `red`, `purple` |
| `TYPOGRAPHY` | `크게`, `작게`, `폰트`, `글씨`, `텍스트 더크게` | `bigger`, `smaller`, `font`, `type`, `text size` |
| `SPACING` | `마진`, `패딩`, `간격`, `바텀마진`, `아래` | `margin`, `padding`, `spacing`, `bottom` |
| `CHIP_COLOR` | `칩`, status/state text selected | `chip`, `pill`, `badge`, status/state text selected |

The classifier should not try to infer exact color values beyond identifying
style intent. Color values remain part of the user's free-text comment.

## Edit Surface Candidate Rules

The candidate builder combines intent, target owner, and existing source
candidates.

1. If the target or owner has a `comp:<Composable>:<variant>` tag:
   - Prefer source candidates whose file basename or symbol matches the
     composable name for component renderer changes.
   - Keep call-site candidates as secondary when the variant may need
     call-site-specific styling.
2. If the selected text appears in a call site and the intent is style/layout:
   - Keep the data/source candidate, but mark it as source-origin.
   - Add likely renderer candidates from nearby composable names when present.
3. For section/header/action text:
   - Prefer reusable component files (`SectionHeader.kt`) over data files when
     the selected text is passed as a literal to a shared component.
4. For chip/status text:
   - Prefer chip renderer/color mapping files (`StatusChip.kt`) when the target
     text equals an enum/state label or appears inside a chip-sized node.
5. For spacing:
   - Prefer the list/call-site file when spacing is between repeated items.
   - Prefer component container files when spacing is inside a card.

## Source Ranking Guardrail

Update source candidate ordering so evidence tier is considered before raw
score when tiers are meaningfully different.

Proposed tier order:

1. Strict selected identity: selected testTag or selected comp-tag convention.
2. Selected semantic text/contentDescription/string resource/role.
3. Owner/ancestor semantic identity from target owner context.
4. Nearby semantic context.
5. Activity-only, arbitrary literal, legacy fallback.

Within the same tier, keep current normalized score and deterministic file/line
tie-breaks.

This prevents many weak nearby hits from outranking the selected target itself,
while preserving nearby context as a useful disambiguator for generic targets.

## Warning Policy

Warnings should tell agents what to do next.

Replace generic notes such as:

```text
Nearby-only match; confirm against screenshot and code.
```

with context-specific notes:

```text
warning: rank-1 source is nearby-only; exact selected text appears in rank 3. Prefer target/editSurface before editing rank 1.
```

```text
note: source candidate identifies the text data; editSurface identifies likely rendering code for this style request.
```

```text
warning: selected target is a parent/visual area; verify whether spacing belongs to the list call site or the component container.
```

## Contract Updates

Update `docs/reference/feedback-console-contract.md`:

- Add `edit_surface_line` to compact grammar.
- Define `editSurface:` fields.
- State that `sourceCandidates` remain source-origin hints.
- State that `editSurface` is an inspection hint, not an auto-edit instruction.

Update `docs/reference/output-schema.md`:

- Document optional `editSurfaceCandidates` on feedback items.
- Mark it additive and absent on legacy sessions.

Update `docs/reference/mcp-tools.md`:

- Explain how agents should interpret source vs edit surface.
- Clarify that style/layout tasks should verify `editSurface` before editing a
  source-origin data candidate.

## Test Strategy

Add focused unit tests in three layers:

1. `SourceMatcherTest`
   - Selected exact text must outrank nearby-only aggregate matches.
   - Selected comp-tag must remain high-confidence.
   - Nearby context still disambiguates generic selected text within the same
     tier.
2. `TargetSummaryFormatterTest` or `CompactHandoffRendererTest`
   - Bare selected text renders owner context when a containing tagged node
     exists.
   - Visual/parent targets render useful contained text.
3. `CompactHandoffRendererTest`
   - Style intent renders `editSurface:` before source candidate lines.
   - Source/data candidate remains present.
   - Warnings explain demotion or data-vs-renderer split.

Add one golden fixture based on the six-item sample handoff shape. It should be
small and synthetic, not dependent on `.fixthis/` artifacts.

## Acceptance Criteria

- The six observed handoff cases produce actionable compact output:
  - MetricCard background points at `MetricCard.kt` and/or the tagged call site.
  - `Resolved this week` text color keeps data source but adds MetricCard label
    renderer as edit surface.
  - `Priority feedback` typography points at `SectionHeader` title renderer.
  - `Open queue` selected text is no longer outranked by nearby-only data lines.
  - First `FeedbackCard` spacing exposes list spacing vs component container
    alternatives.
  - `Resolved` chip color points at state/chip color mapping.
- Existing persisted sessions without `editSurfaceCandidates` still render.
- Compact prompts remain concise: at most two `editSurface:` lines per item.
- No bridge protocol change is required.
- `./gradlew :fixthis-compose-core:test :fixthis-mcp:test --no-daemon` passes.

## Open Risks

- Component renderer inference from source candidates is heuristic. The
  warnings must keep it framed as an inspection hint.
- Owner context depends on persisted screen roots; legacy sessions may lack the
  data needed to derive a meaningful owner.
- Korean natural-language intent matching can be broad. Keep triggers small and
  tests explicit; do not add fuzzy NLP in this iteration.
