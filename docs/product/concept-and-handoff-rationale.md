# FixThis Product Concept and Handoff Rationale

This document is the canonical explanation for people who are new to
FixThis. It explains the product intent, the major product choices, and why
the agent handoff prompt has its current shape.

## One-line definition

FixThis is a tool for Jetpack Compose debug apps. A user points directly at the
UI they want changed, writes the desired change, and FixThis packages local
runtime evidence from the current screen so an AI coding agent can find and
edit the right source code.

```text
running Compose debug UI
-> human-selected target
-> local runtime evidence
-> compact agent handoff
-> code change and feedback resolution
```

## Problem

When a user tells an AI agent only "change this button", "change the color of
the third card", or "tighten this spacing", the agent usually does not know:

- which Activity, route, or state the current screen is in
- whether the same text appears on multiple screens or in multiple files
- which instance of a repeated list item is being referenced
- which composable corresponds to a screenshot pixel
- whether the edit belongs at a call site, reusable component definition, or
  data/copy source
- whether the current source index matches the installed debug APK

FixThis fills that gap with a human-selected UI target and evidence collected
from the running app.

## Current product flow

FixThis's current main workflow happens in a desktop browser console, not
inside the Android app.

```text
run the debug app
-> open the FixThis Studio console
-> use Start / Open app / Reconnect on the connection card
-> navigate the app in the live preview
-> freeze the current preview with Annotate
-> click a component or drag a visual area
-> write a comment
-> Add annotation
-> Copy Prompt or Save to MCP
-> the agent reads the item, claims it, and resolves it
```

Inside the Android app, FixThis shows only the `MCP waiting` / `MCP connected`
status pill. Selection, annotation, prompt generation, feedback queue state,
and handoff persistence are owned by the desktop console.

This structure keeps the app sidekick small and lets the desktop process manage
the local artifacts and queue state that agents actually read.

Handoff saves are retry-safe. The browser draft workspace assigns stable draft
item ids before persistence; the server stores those ids on saved feedback
items and treats duplicate save attempts as idempotent retries instead of new
agent work.

## Why Compose-only

FixThis's core job is to connect a running UI target to source candidates.
Supporting all Android UI stacks would require different inspection paths for
XML/View, Compose, WebView, Flutter, and React Native.

V1 deliberately narrows the scope to Jetpack Compose.

- Compose semantics provide a stable runtime signal.
- The product message stays simple.
- Installation and validation scope stays realistic.
- FixThis avoids large branching paths such as AccessibilityService support,
  WebView injection, and View hierarchy source mapping.

View/XML/WebView targets are therefore not guaranteed to map to exact source
targets. If interop is likely, the prompt surfaces that as a warning so the
agent knows to verify carefully.

## Why debug-only

FixThis handles screenshots, UI text, semantics, source hints, and user
comments. That data can be sensitive, so FixThis is a debug/dev utility rather
than a production feature.

The default rules are:

- Do not include FixThis in release builds.
- The sidekick verifies that the app is debuggable.
- Installation docs are based on `debugImplementation`.
- Do not commit local artifacts under `.fixthis/`.
- FixThis does not upload screenshots or prompts to an external AI API.

## Why a desktop console instead of in-app annotation

The initial idea was to let users tap the UI and type comments inside the app.
In the actual agent workflow, however, the following state already lives on the
desktop side:

- MCP server process
- local feedback session
- screenshot artifact promotion
- source index lookup
- Copy Prompt clipboard flow
- Save to MCP queue flow
- claim / resolve lifecycle
- connection recovery and stale preview handling

The current product is therefore desktop-console-first. The app provides
runtime evidence and shows connection status. Users inspect, select, and save
feedback in the desktop console.

## Why FixThis does not require `testTag` everywhere

`testTag` is a precise signal, but requiring tags across an existing app creates
too much adoption cost. FixThis must be useful before teams retrofit tags.

The default evidence combines:

- selected text
- content description
- role
- click / input action
- bounds
- nearby semantics context
- screenshot and crop
- source-index candidates

When present, `testTag` and the `comp:<ComposableName>:<variant>` convention are
strong signals. When absent, FixThis builds a best-effort handoff from the other
evidence.

## Why source candidates are candidates, not answers

A Compose runtime node cannot always be mapped to an exact source file and line.
A compiler plugin or Compose tooling internals might provide more precise data,
but that would increase Kotlin/Compose version compatibility risk and
maintenance cost.

V1 starts from the Gradle source index.

```text
semantics evidence
-> source index match
-> ranked source candidates
-> confidence, margin, matched reason tokens
```

This approach is stable and explainable, but it is not perfect runtime mapping.
That is why the prompt always preserves the rule that source hints are
candidates.

## What the prompt must communicate

The agent handoff prompt must answer four questions quickly:

1. What did the user request?
2. Which UI target did the user select?
3. Which source file/line candidates should the agent inspect first?
4. How much should the agent trust the target and source hint?

The Markdown prompt is a compact view for agents and humans. The JSON session is
the full-fidelity record for tooling. Markdown is the quick work order; JSON is
the contract that preserves IDs, paths, screen data, selected node data, nearby
nodes, source candidates, target evidence, and screenshot artifacts.

## Prompt fields and rationale

### `Package`

Identifies which debug app produced the feedback. This reduces mistakes when a
user moves between several Android apps or the bundled sample.

### `Source root`

Repeated long candidate-path prefixes waste prompt tokens. The prompt lifts the
common prefix once and renders candidate lines in a shorter, relative-looking
form.

### `Handoff quality`

Summarizes risk signals near the top of the prompt: low target confidence,
visual-area-only selection, overlap, duplicate markers, stale source index, or
missing source candidates. This lets the agent choose the right verification
level from the start.

### `Screen`, `screenshot`, `viewport`, `activity`

Annotations that come from the same frozen preview are grouped under one screen
block.

- `screenshot` lets the agent inspect the actual pixels.
- `viewport` lets the agent interpret screen coordinates.
- `activity` provides route/context evidence when the display name and Android
  Activity name differ.

### `[N] title`

`N` is the human-facing number shown by the console overlay marker. It lets a
person refer to "marker 2" during review.

### `id`

`id` is the MCP feedback item id. Agents use it to claim the item before work
and resolve it after work.

```text
fixthis_claim_feedback({sessionId, itemId})
fixthis_resolve_feedback({sessionId, itemId, status, summary})
```

The marker number is for UI understanding; the item id is for the tool
contract.

### `target`

Summarizes the selected UI in a redaction-safe way. It can include semantics
such as tag, text, contentDescription, and role, while removing sensitive values
from editable/password fields.

For visual-area selection, the prompt states `target: visual area` so the agent
knows there was no semantic node.

### `box=(L,T)-(R,B)`

Records the selected target's window pixel bounds. Even if the semantic summary
is weak or redacted, the agent can still locate the target in the screenshot.

### `editSurface`

`sourceCandidates` answers "which source matched the selected text/tag/nearby
evidence?"

`editSurface` hints at the code surface most likely to affect visual, style, or
layout changes.

For example, if the user clicks text inside a card but asks to reduce the card
spacing, the container composable may be the better edit surface than the text
source.

### Source candidate lines

The prompt shows up to three candidates. Showing only one makes it harder for an
agent to distinguish between a call site, component definition, and data/copy
source.

Rank 1 also includes:

- `conf`: high / medium / low / none
- `margin`: score gap between rank 1 and rank 2
- `matched`: tokens describing which evidence matched

A small `margin` means rank 1 is ambiguous. The agent should inspect the runner
up too.

### `instance i/N`

Repeated cards or LazyColumn rows can share the same call site and tag across
multiple markers. Without `instance 2/3`, the agent cannot tell whether the
markers refer to different rendered instances or duplicate pins on the same
target.

`instance` distinguishes repeated rendered targets.

### `targetRisk=duplicate-of-marker-N`

When a later marker points to the same source, tag, bounds, and runtime path as
an earlier marker, it is likely a duplicate pin. The prompt exposes duplicate
risk so the agent does not perform the same work twice.

### Overlap group

Overlapping targets are easy to mis-fix if handled together. An overlap group is
a work-unit hint: resolve one marker at a time.

### `targetConfidence` and `warning`

Source candidate confidence and target confidence are different.

- Source confidence: how well the source-file candidate matches the evidence
- Target confidence: how much FixThis trusts the selected UI target itself

For example, a source candidate can exist while the target is only a visual area
or might sit at a WebView boundary. In that case target confidence should be
low.

Warnings tell the agent when to stop and verify.

- visual area only
- no meaningful Compose target
- possible AndroidView/WebView interop
- stale source index
- forced screen mismatch
- missing fingerprint
- sensitive text redacted

## Why prompt rendering is server-owned

Copy Prompt runs in the browser, while `fixthis_read_feedback` runs in the MCP
server. If each side rendered prompts independently, fields would drift easily.

That drift risk grows as the prompt adds fields such as item id, session id,
claim/resolve protocol, crop path, and stale warnings.

The current direction is for Kotlin `CompactHandoffRenderer` to be the single
source of truth for handoff Markdown.

```text
Copy Prompt
-> server-rendered compact handoff
-> clipboard

Save to MCP
-> server-rendered compact handoff
-> local handoff batch

fixthis_read_feedback
-> same compact handoff + complete JSON
```

With that structure, prompt format changes are managed in one place, and Copy
Prompt and MCP read flows share the same shape.

## Reading order

A first-time reader should be able to understand the product intent and prompt
design rationale from this document alone. Read these additional references only
when exact field grammar or JSON contracts are needed.

- [Feedback console contract](../reference/feedback-console-contract.md)
- [Output schema](../reference/output-schema.md)
- [MCP tools reference](../reference/mcp-tools.md)
- [Architecture overview](../architecture/overview.md)
