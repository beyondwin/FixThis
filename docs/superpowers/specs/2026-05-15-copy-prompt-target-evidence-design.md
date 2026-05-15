# Spec - Copy Prompt Target Evidence

Status: Design approved, pending implementation
Date: 2026-05-15
Scope: `:fixthis-mcp` compact handoff rendering, feedback console contract docs,
MCP reference docs, renderer tests

## Summary

FixThis compact handoffs already include screenshots, bounds, source
candidates, target reliability, overlap groups, and an inline claim/resolve
protocol. The remaining weakness is that the prompt is too source-candidate
first. When source candidates are low confidence, the agent can see file hints
before it can see what the user actually selected.

This design makes Copy Prompt and compact MCP handoff output target-first by
adding two small pieces of agent-facing evidence:

- A session-level `Handoff quality:` summary near the top of the prompt.
- A per-item `target:` summary line with redaction-safe semantic identity such
  as selected text, role, content description, test tag, or visual-area marker.

The change does not alter source matching, target reliability calculation,
session persistence, or the claim/resolve protocol. It only makes the compact
Markdown safer and easier to act on.

## Goals

- Let an agent understand the selected UI target before trusting source
  candidates.
- Summarize low-confidence targets, warnings, overlap groups, duplicate
  markers, visual-area selections, redacted targets, stale source candidates,
  and missing source candidates at the prompt level.
- Preserve the existing compact prompt structure so current consumers that
  parse `id:`, `box=`, `targetConfidence=`, candidate lines, and
  `agent_protocol:` keep working.
- Keep privacy guarantees intact. Password/editable/sensitive target text must
  not be emitted in the new target summary.
- Keep the implementation local to MCP handoff formatting and docs.
- Add focused renderer tests that lock down redaction behavior and summary
  counts.

## Non-Goals

- Changing source candidate scoring or source index generation.
- Adding sample-app `testTag` coverage.
- Creating per-item screenshot crops.
- Changing persisted JSON field names or required schema.
- Changing Copy Prompt from draft delivery semantics to Save-to-MCP semantics.
- Blocking Copy Prompt when the handoff quality summary contains warnings.
- Adding browser UI badges in this pass. The readiness UI can reuse the same
  quality data later, but this pass only changes copied/rendered Markdown.

## Current State

The current compact handoff begins with:

```text
# FixThis Feedback Handoff

Rule: source hints are candidates; verify screenshot, target, and code before editing.

- Package: `io.github.beyondwin.fixthis.sample`
- Source root: `sample/src/main/java/io/github/beyondwin/fixthis/sample/`

Screen 9196a35b: MainActivity
screenshot: /path/to/screen.png
viewport: 1080x2424
activity: io.github.beyondwin.fixthis.sample.MainActivity

[1] Increase contrast
  id: item-1
  box=(42.0,184.0)-(436.0,252.0)
  screens/ReviewScreen.kt:56  conf=low  margin=0.03  matched=[text]
  targetConfidence=low
```

This is structurally correct, but the first item-level semantic identity is a
file candidate. If the source candidate is ambiguous, the agent must open the
screenshot or full JSON to understand whether the user selected a title, a
button, a text field, or a visual area.

Target reliability warnings already exist and are rendered by
`CompactHandoffRenderer`, but they are distributed across item blocks. The
prompt lacks an up-front triage signal such as "this handoff has seven
low-confidence targets and one overlap group".

## Proposed Handoff Shape

The compact prompt should continue to start with the existing title, rule,
package, and optional source root. Immediately after the package/source-root
block, render a single quality summary line when there is at least one
interesting signal:

```text
Handoff quality: 7 low-confidence targets, 1 overlap group, 1 visual area, 1 redacted target
```

Then each item should include a redaction-safe `target:` line before the
existing `box=` line:

```text
[1] Increase contrast
  id: item-1
  target: text="Review request"
  box=(42.0,184.0)-(436.0,252.0)
  screens/ReviewScreen.kt:56  conf=low  margin=0.03  matched=[text]
  targetConfidence=low
```

For a button:

```text
  target: text="Severity: High"; role=Button
  role=Button  box=(42.0,1648.0)-(397.0,1753.0); targetRisk=overlap
```

For a tagged node:

```text
  target: tag="comp:ReviewScreen:submit"; text="Submit request"; role=Button
```

For a visual area with no selected node:

```text
  target: visual area
  box=(618.5629,1685.0824)-(921.8194,1945.9606)
```

For sensitive or password/editable content:

```text
  target: redacted sensitive target; role=TextField
  box=(42.0,1405.0)-(1038.0,1546.0)
  targetConfidence=medium
  warning: sensitive text was redacted from target evidence
```

## Compatibility

The existing target line must remain unchanged:

```text
  [role=Button  ][tag=...]box=(left,top)-(right,bottom)[  instance i/N][; targetRisk=...]
```

The new `target:` line is additive and appears between `id:` and the existing
box line. Existing parsers that look for `id:`, `box=`, candidate lines, or the
footer can ignore the new line.

The new `Handoff quality:` line is additive and appears in the header block
before screen blocks. Existing parsers that scan for `Screen ` keep working.

No persisted JSON fields are renamed or required. The implementation may add
private renderer helper types, but no new public session schema is required.

## Target Summary Rules

### Source Data

The target summary is derived only from existing `AnnotationDto` fields:

- `selectedNode.text`
- `selectedNode.contentDescription`
- `selectedNode.role`
- `selectedNode.testTag`
- `selectedNode.editableText`
- `selectedNode.isPassword`
- `selectedNode.isSensitive`
- `target`

The formatter must not depend on live app state, source index files, browser
state, or screenshot parsing.

### Privacy

Text-like values must be redacted when any of these are true:

- `selectedNode.isPassword == true`
- `selectedNode.isSensitive == true`
- `selectedNode.editableText` is not null or not blank
- `TargetReliabilityWarning.SENSITIVE_TEXT_REDACTED` is present

When redacted, do not emit `text=`, `editableText=`, or
`contentDescription=` values. Role and test tag may still be emitted because
they are non-user-text metadata.

### Field Ordering

When not redacted, render summary parts in this order:

1. `tag="..."`
2. `text="..."`
3. `contentDescription="..."`
4. `role=...`

For a visual-area selection with no selected node, render `target: visual area`.

For a node with no safe semantic identity, render `target: semantics node`.

For a redacted node with no role/tag, render `target: redacted sensitive target`.

### Length Limits

Keep the prompt compact:

- Render at most one text value, using `selectedNode.text.first()`.
- Render at most one content description value, using
  `selectedNode.contentDescription.first()`.
- Limit each quoted value to 80 characters after newline normalization.
- Replace backticks with single quotes through the existing `inlineSafe()`
  helper.
- Escape double quotes inside quoted values as `'` to keep the line readable.

## Handoff Quality Summary Rules

The quality summary is computed from the filtered session passed to
`CompactHandoffRenderer.render(session, itemIds)`. When `itemIds` is supplied,
counts must reflect only those items.

Render no quality line when all counts are zero. Otherwise render:

```text
Handoff quality: <count label>, <count label>, ...
```

Labels appear in this order:

1. `<n> low-confidence target(s)`
2. `<n> warning target(s)`
3. `<n> overlap group(s)`
4. `<n> duplicate marker(s)`
5. `<n> visual area(s)`
6. `<n> redacted target(s)`
7. `<n> stale source candidate(s)`
8. `<n> item(s) without source candidates`

Definitions:

- Low-confidence target: `item.targetReliability?.confidence == LOW`.
- Warning target: `item.targetReliability?.warnings` is not empty.
- Overlap group: a detected overlap group with `size > 1`.
- Duplicate marker: an item that `DuplicateMarkerDetector` maps to an earlier
  marker.
- Visual area: `item.target is AnnotationTargetDto.Area`.
- Redacted target: target summary redaction applies or the reliability warning
  list contains `SENSITIVE_TEXT_REDACTED`.
- Stale source candidate: any rendered item's top candidate or runner-up has
  `stale == true`. Count items, not individual candidates.
- Item without source candidates: `item.sourceCandidates.isEmpty()`.

Pluralization should be human-readable:

```text
1 low-confidence target
2 low-confidence targets
1 overlap group
2 overlap groups
```

## Error Handling

The renderer must remain total: it should not throw because a node field is
missing, a list is empty, a target is visual-area-only, or reliability metadata
is absent. Legacy sessions without `targetReliability` should render target
summary from selected node metadata and omit confidence counts.

If a selected node exists but all fields are blank, render
`target: semantics node`.

## Testing Strategy

Add renderer tests in `CompactHandoffRendererTest` for:

- Non-sensitive text target summary appears before the existing box line.
- Button role and text summary render together.
- Visual area summary renders as `target: visual area`.
- Sensitive/editable/password target text is not emitted.
- Handoff quality summary counts low-confidence targets, warnings, overlap
  groups, visual areas, redacted targets, stale candidates, and missing source
  candidates.
- Filtered rendering with `itemIds` computes quality counts from the filtered
  item set.
- Existing contract tokens remain present: `id:`, `box=`,
  `targetConfidence=`, candidate lines, `agent_protocol:`, and `session_id:`.

Update docs tests if they assert the exact compact contract grammar.

## Documentation Updates

Update `docs/reference/feedback-console-contract.md`:

- Add `quality_line` to the grammar.
- Add `target_summary_line` to `item_block`.
- Document redaction rules and summary count definitions.
- State that `target:` is an additive line and the existing box line remains
  the coordinate contract.

Update `docs/reference/mcp-tools.md`:

- Explain that compact Markdown now starts with an optional handoff quality
  summary.
- Explain that each item includes a redaction-safe target summary before source
  candidates.

## Rollout

This is safe to ship as a single MCP-side change:

1. Add tests for the new compact Markdown shape.
2. Add formatter helpers and render the new lines.
3. Update contract docs.
4. Run targeted MCP tests.
5. Run broader MCP tests if targeted tests reveal fixture churn.

No Android app reinstall, bridge protocol bump, source index regeneration, or
sample app change is required.

## Acceptance Criteria

- Copy Prompt compact Markdown includes a `target:` summary for every item.
- Sensitive/editable/password text is never emitted in target summaries.
- Compact Markdown includes `Handoff quality:` when at least one quality signal
  exists.
- Compact Markdown omits `Handoff quality:` for clean sessions with no quality
  signals.
- Existing item numbering, `id:`, `box=`, candidate lines, reliability lines,
  overlap groups, and protocol footer remain present.
- Tests cover both full-session and `itemIds` filtered rendering.
- Documentation describes the new prompt grammar and agent interpretation.

## Spec Self-Review

- Placeholder scan: no unresolved placeholders remain.
- Scope check: this is a single MCP compact-rendering improvement, not a
  source-matching or console UI redesign.
- Compatibility check: all changes are additive Markdown lines; persisted JSON
  contracts remain unchanged.
- Privacy check: redaction rules are explicit and conservative for editable,
  password, sensitive, and already-redacted targets.
