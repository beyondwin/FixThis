# Edit Intent Analyzer v1 Design

**Date:** 2026-05-15
**Status:** Proposed
**Scope:** Deterministic Android/Compose edit-surface intent hints for Korean and English feedback comments.

## Purpose

The current `EditIntentClassifier` is useful only as a small keyword gate. It
can detect phrases such as "글자 파란색" or "bottom margin", but it does not
use the Android/Compose evidence that FixThis already captures. If it remains a
comment-only natural language classifier, it has limited value because the
coding agent can read the same comment.

The valuable part is different: FixThis can combine the user's short comment
with structured local evidence that the agent does not naturally have in a
stable form, such as selected semantics node, containing `comp:*` test tag,
visual-area target, and source candidate type. The analyzer should normalize
that evidence into conservative `editSurfaceCandidates` so agents are less
likely to edit data/source-origin files when the request is really visual,
style, typography, or spacing work.

## Goals

- Replace comment-only intent classification with a small analyzer that uses
  both comment text and Compose target evidence.
- Support only Korean and English trigger phrases in v1.
- Improve edit-surface hints for high-signal style/layout cases:
  text color, container/background color, typography, spacing, and chip color.
- Preserve the original user comment unchanged in handoff output.
- Keep behavior deterministic, local-first, and testable.
- Prefer no `editSurfaceCandidates` over misleading candidates when intent is
  ambiguous.

## Non-Goals

- Do not add an LLM or external API call to preprocess comments.
- Do not attempt full natural language understanding.
- Do not support languages other than Korean and English in v1.
- Do not infer exact requested color values beyond detecting style intent.
- Do not auto-edit source code or claim that an edit-surface candidate is the
  correct file.
- Do not rename persisted JSON fields such as `items`, `screens`,
  `sourceCandidates`, `editSurfaceCandidates`, `targetEvidence`, or
  `targetReliability`.
- Do not change the Android bridge protocol.

## Design

Introduce two internal layers under `fixthis-mcp` session enrichment.

`EditIntentLexicon` is a pure text classifier. It accepts `comment: String` and
returns a set of raw intent signals. It knows only Korean and English keyword
sets. It does not inspect Android nodes, source candidates, or snapshots.

`EditIntentAnalyzer` accepts `AnnotationDto` and `SnapshotDto?`. It calls the
lexicon, then combines the raw signals with the selected node, target owner,
test tags, visual-area target shape, and source candidates. It returns the
existing `EditIntent` shape, with a more accurate `primaryKind` and derivation
reasons.

`EditSurfaceCandidateService` keeps responsibility for selecting candidate
files. Its input changes from:

```kotlin
EditIntentClassifier.classify(item.comment)
```

to:

```kotlin
EditIntentAnalyzer.analyze(item, screen)
```

The persisted DTO shape remains unchanged.

## Raw Intent Rules

The lexicon should use closed, explicit Korean and English triggers.

Color/style signals:
- Korean: `색`, `색상`, `컬러`, `글자색`, `텍스트색`, `배경`, `배경색`,
  `카드색`, `파란`, `빨간`, `초록`, `보라`
- English: `color`, `text color`, `label color`, `background`,
  `card color`, `blue`, `red`, `green`, `purple`

Typography signals:
- Korean: `크게`, `작게`, `폰트`, `글씨`, `글자 크기`, `텍스트 크기`,
  `더크게`, `더 크게`
- English: `font`, `type`, `text size`, `bigger`, `smaller`, `larger`

Spacing signals:
- Korean: `마진`, `패딩`, `간격`, `바텀마진`, `아래`, `여백`, `dp`
- English: `margin`, `padding`, `spacing`, `gap`, `bottom`, `top`, `dp`

Content-only signals:
- Korean: `문구`, `텍스트를`, `이름`, `바꿔`, `변경`
- English: `rename`, `change text`, `copy`, `label to`, `text to`

Content-only signals suppress edit-surface generation unless a stronger
style/layout signal is also present.

## Compose Evidence Rules

Analyzer rules are intentionally small.

1. If the only raw signal is content-only, return `UNKNOWN`.
2. If raw intent is spacing, return `SPACING`.
3. If raw intent is typography and the selected target is a text-like node,
   return `TYPOGRAPHY`.
4. If raw intent is color/style:
   - If selected node or owner tag contains `Chip`, `StatusChip`, `Badge`, or
     `Pill`, return `CHIP_COLOR`.
   - If the comment includes background/container/card triggers, return
     `CONTAINER_COLOR`.
   - If selected node has text or the comment includes text/label triggers,
     return `TEXT_COLOR`.
5. If none of the above rules match, return `UNKNOWN`.

When a screen is available, the analyzer uses
`TargetOwnerResolver.resolve(item, screen)` to inspect the nearest owner. It
should treat `comp:<Composable>:<variant>` tags as strong component identity
signals, but it must not require app developers to add new tags for basic
behavior.

## Confidence And Candidate Behavior

The analyzer should not overstate certainty.

- Strong owner/testTag match plus matching intent can produce medium
  confidence through existing candidate service rules.
- Missing screen or missing owner can still produce a generic kind when the
  comment is clear, but candidate confidence should not be upgraded because of
  text alone.
- Ambiguous or unsupported comments produce no `editSurfaceCandidates`.
- The original `sourceCandidates` remain available and unchanged.
- Existing notes such as "source candidate identifies data text; editSurface
  identifies likely rendering code" remain appropriate for text style and
  typography hints.

## Examples

Text color request with data source candidate:

```text
comment: 여기 글자 파란색
selected text: Resolved this week
owner tag: comp:MetricCard:summary
source candidate: FixThisDemoData.kt
result: TEXT_COLOR, because the request is text style and the selected target
is text-like inside a known component owner.
```

Chip color request:

```text
comment: 여기 보라색
selected text: Resolved
selected/owner tag: comp:StatusChip:resolved
result: CHIP_COLOR, because the color request targets a chip-like component.
```

Spacing request on a visual area:

```text
comment: 여기 아래 바텀마진 8dp 더
target: visual area or card node
owner tag: comp:FeedbackCard:priority
result: SPACING, because the comment has spacing and dp triggers. Candidate
selection can then prefer list/call-site or container surfaces.
```

Content change:

```text
comment: Rename this to Checkout
selected text: Continue
result: UNKNOWN for edit-surface purposes. The original comment remains in the
handoff, but no visual/style edit surface is invented.
```

## Error Handling

- Empty comments return `UNKNOWN`.
- Unsupported languages return `UNKNOWN` unless they also contain one of the
  Korean or English triggers.
- Missing `SnapshotDto` is allowed; analyzer falls back to comment and
  `AnnotationDto` fields.
- Missing source candidates are allowed; analyzer can still classify intent,
  but candidate service may return no edit surfaces.
- Conflicting signals prefer the more specific Compose context. For example,
  a color request inside `StatusChip` becomes `CHIP_COLOR`, not generic
  `TEXT_COLOR`.

## Testing

Add focused tests rather than broad NLP fixtures.

`EditIntentLexiconTest`:
- Korean and English color/style triggers.
- Korean and English typography triggers.
- Korean and English spacing triggers.
- Korean and English content-only requests.
- Korean compound forms such as `텍스트컬러`, `바텀마진`, and `더크게`.

`EditIntentAnalyzerTest`:
- `comp:StatusChip:resolved` plus color request returns `CHIP_COLOR`.
- Text node plus "글자 파란색" returns `TEXT_COLOR`.
- Owner `comp:MetricCard:summary` plus background request returns
  `CONTAINER_COLOR`.
- `dp` or margin request returns `SPACING`.
- Content-only rename returns `UNKNOWN`.

`EditSurfaceCandidateService` tests:
- The service calls analyzer with `item` and `screen`.
- Content-only feedback produces no edit-surface candidates.
- Existing compact handoff renderer behavior remains compatible with
  `editSurfaceCandidates`.

## Rollout

This is an internal refactor of MCP session enrichment. It should be shipped as
an additive behavior change:

1. Add lexicon tests.
2. Add analyzer tests.
3. Route candidate service through analyzer.
4. Keep DTO field names and Markdown grammar stable.
5. Update reference docs only if emitted behavior or reason lists change.

No Android runtime, bridge, CLI, or console UI changes are required for v1.
