# Handoff Prompt Rationale

FixThis produces a compact Markdown handoff for agents and a complete JSON
session model for tools. The Markdown is intentionally small enough to paste
into chat-style agents, but structured enough that MCP-aware agents can claim,
edit, and resolve specific feedback items.

The current grammar is defined in the
[feedback console contract](../reference/feedback-console-contract.md#compact-handoff-schema).
This document explains why the prompt is shaped that way.

## Design Goal

The prompt should answer four questions quickly:

1. What did the user ask?
2. Which UI target did they mean?
3. Which source files are likely relevant?
4. How much should the agent trust the target and source hints?

It should not pretend to be an exact compiler mapping. Every handoff keeps the
rule:

```text
source hints are candidates; verify screenshot, target, and code before editing
```

## Why Markdown Plus JSON

Markdown is optimized for human and chat-agent consumption. It carries the
request, visible target, candidate source lines, screenshots, confidence, and
warnings in a compact sequence.

JSON is the durable tool contract. It preserves IDs, screen records, selected
nodes, nearby nodes, source candidates, target evidence, screenshot paths, and
handoff batches. Agents that need full fidelity should read JSON from
`fixthis_read_feedback` rather than scraping the Markdown.

## Why The Prompt Starts With Package, Source Root, And Quality

- `Package` confirms which debug app the feedback came from.
- `Source root` trims repeated long paths when multiple candidates share a
  directory prefix.
- `Handoff quality` surfaces aggregate warnings early, before the agent reads
  individual items.

This keeps the prompt readable in monorepos and makes low-confidence sessions
obvious.

## Why Each Screen Is Grouped

Feedback is saved from frozen preview evidence. Multiple annotations can share
one screen, screenshot, viewport, and Activity. Grouping by screen lets the
agent interpret bounds once and then handle each marker.

The `viewport:` line exists so an agent can reason about pixel coordinates
without opening the screenshot first.

## Why Each Item Has An `id`

The visible marker number is for humans. The `id:` line is for tools.

MCP-aware agents use the item ID to call:

- `fixthis_claim_feedback`
- `fixthis_resolve_feedback`

Without the ID, a pasted prompt can still guide code edits, but the console
cannot reliably move the item through `sent`, `in_progress`, and `resolved`
states.

## Why `target:` And `box=` Are Separate

`target:` is a redaction-safe semantic summary: tag, text, content description,
role, or `visual area`.

`box=(L,T)-(R,B)` is the physical window-pixel target. It remains useful when
the semantic summary is weak, redacted, or missing.

Separating the two helps agents distinguish "what the user meant" from "where
the user clicked or dragged."

## Why Source Candidates Are Ranked Hints

Compose runtime nodes do not always expose exact source file and line data.
FixThis ranks candidates from the source index using semantics evidence such as
selected text, content description, test tag, role, nearby labels, Activity, and
string resources.

The prompt renders up to three candidates because runner-up files often matter:

- rank 1 may be the call site
- rank 2 may be the reusable composable definition
- rank 3 may contain related copy or data

The first candidate includes `margin=` and `matched=[...]` so agents can judge
how decisive the ranking is. A medium-confidence result with a small margin
should be inspected more carefully than a high-confidence result with direct tag
and text matches.

## Why `editSurface:` Exists

`sourceCandidates` answer "where did the selected or nearby evidence come
from?"

`editSurface:` answers "where is a visual, layout, or style change likely
rendered?"

Those are related but not identical. For example, a selected card title may map
to a string source, while the requested spacing change belongs in the card
container composable. `editSurface:` is an inspection hint, not an automatic
edit instruction.

## Why Instance And Duplicate Signals Exist

Repeated UI is common in Compose: list rows, metric cards, tabs, and reusable
buttons can all map to the same call site. Without extra signals, a prompt with
three markers can look like three identical source hints.

FixThis adds:

- `instance i/N` when multiple markers share a source/tag grouping but point at
  distinct runtime instances
- overlap groups when targets visually collide
- `targetRisk=duplicate-of-marker-N` when a later marker appears to be the same
  target as an earlier one

These signals reduce accidental double-fixes and help the agent resolve one
marker at a time.

## Why Confidence And Warnings Are Explicit

Target confidence is separate from source confidence.

- Source confidence describes whether a file candidate matched the available
  evidence.
- Target confidence describes whether the selected UI target itself is reliable.

Warnings such as `VISUAL_AREA_ONLY`, `POSSIBLE_VIEW_INTEROP`,
`SOURCE_INDEX_STALE`, `SCREEN_FINGERPRINT_MISMATCH_FORCED`, and
`SENSITIVE_TEXT_REDACTED` tell the agent when to slow down and verify.

## Why One Server Renderer Owns The Prompt

The browser console and MCP tools previously had parallel renderers: JavaScript
for Copy Prompt and Kotlin for `fixthis_read_feedback`. That drifted. The
browser prompt missed fields that the MCP prompt had, including item IDs,
session IDs, claim/resolve protocol guidance, crop paths, and staleness signals.

The current design makes the Kotlin `CompactHandoffRenderer` the source of
truth. Browser Copy Prompt and Save to MCP ask the local server to render the
same prompt that MCP tools read. Future prompt fields now have one production
renderer and one main test surface.

## Why Some Historical Tokens Were Removed

The v2 prompt removed several noisy tokens after real agent use:

- item count headers were dropped because marker numbering already shows count
- `ui:` and `candidates:` headers were removed when indentation was enough
- `tag=(none)` placeholders were dropped
- repeated path prefixes moved into `Source root`
- size suffixes were dropped because they are derivable from `box=`

The result is shorter but not less informative. The migration table in the
[feedback console contract](../reference/feedback-console-contract.md#v1--v2-token-migration)
documents exact token changes.
