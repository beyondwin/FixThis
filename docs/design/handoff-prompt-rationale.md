# Handoff Prompt Rationale

FixThis produces compact Markdown for agents and complete JSON for tools.
Grammar: [console contract](../reference/feedback-console-contract.md#compact-handoff-schema).

The prompt should answer four questions quickly:

1. What did the user ask?
2. Which UI target?
3. Which source files are likely?
4. How much should the agent trust those hints?

Rule:

```text
source hints are candidates; verify screenshot, target, and code before editing
```

## Why Markdown plus JSON

Markdown is for humans and chat agents. JSON is the durable tool contract:
IDs, screens, nodes, candidates, evidence, screenshot paths, batches. Agents
that need full fidelity should read JSON from `fixthis_read_feedback`.

## Why it starts with package, source root, and quality

- `Package` names the debug app.
- `Source root` trims repeated long paths.
- `Handoff quality` surfaces aggregate warnings first.

## Why screens are grouped

Annotations from one freeze share screenshot, viewport, and Activity.
`viewport:` lets an agent reason about pixel coordinates without opening the
image first.

## Why each item has an `id`

The visible marker is for humans. `id:` is for `fixthis_claim_feedback` and
`fixthis_resolve_feedback`. Without it, a paste can still guide edits, but
the console cannot move the item through sent / in_progress / resolved.

## Why `target:` and `box=` are separate

`target:` is a redaction-safe semantic summary. `box=(L,T)-(R,B)` is the
window-pixel target. Useful when the summary is weak, redacted, or missing.

## Why source candidates are ranked hints

Compose nodes often do not expose exact file and line. FixThis ranks up to
three candidates from the source index. Rank 1 may be the call site, rank 2
the reusable composable, rank 3 related copy or data. `margin=` and
`matched=[...]` show how decisive the ranking is.

## Why `editSurface:` exists

`sourceCandidates` answer where evidence came from. `editSurface:` answers
where a visual or style change is likely rendered. `role=` distinguishes
call site, component definition, copy/data, layout/style, visual area, and
interop risk.

## Why instance and duplicate signals exist

Repeated list rows can share a call site. `instance i/N`, overlap groups, and
`targetRisk=duplicate-of-marker-N` keep identical-looking markers distinct.

## Why confidence and warnings are explicit

Source confidence is “did a file match?” Target confidence is “is this UI
target reliable?” Warnings such as `VISUAL_AREA_ONLY`,
`POSSIBLE_VIEW_INTEROP`, and `SOURCE_INDEX_STALE` tell the agent to slow
down.

## Why one server renderer owns the prompt

Browser Copy Prompt and MCP `fixthis_read_feedback` used to drift. Kotlin
`CompactHandoffRenderer` is the source of truth. Both paths ask the server
to render the same prompt.

## Why some historical tokens were removed

v2 dropped noisy headers, `tag=(none)` placeholders, repeated path prefixes,
and size suffixes. Token table:
[v1 → v2](../reference/feedback-console-contract.md#v1--v2-token-migration).
