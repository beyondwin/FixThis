# Product And Architecture Decision Rationale

This document is the maintained, readable decision summary for FixThis.

For durable low-level architecture records, see the
[ADR index](../architecture/adr/README.md).

## Summary Of Major Decisions

FixThis is:

```text
MCP feedback console first
+ local AI context export
+ debug app sidekick for runtime evidence
```

The Android app-side runtime stays small. It supplies runtime evidence through a
debug-only sidekick and shows only MCP browser connection state. The desktop
console owns annotation, selection, persistence, and handoff.

## Compose-Only

**Decision:** V1 supports Jetpack Compose apps only.

**Why:** Compose gives FixThis a coherent runtime signal through semantics.
Trying to support XML Views, WebView DOM, Flutter, React Native, and Compose at
the same time would turn the product into a broad mobile automation platform
and dilute the core use case: "point at Compose UI and hand an agent source
context."

**Trade-off:** XML/View and WebView targets are not source-mapped in V1. FixThis
can warn when a target may cross an interop boundary, but it does not pretend a
Compose candidate definitely rendered those pixels.

## Debug-Only

**Decision:** FixThis is a debug/dev utility, not a production feedback feature.

**Why:** It handles screenshots, UI text, semantics trees, source hints, and
local handoff files. Those can contain sensitive information and should not be
available in release builds or exposed to end users.

**Guardrails:**

- install through `debugImplementation`
- verify the app is debuggable before starting the sidekick bridge
- omit release-build setup from public docs
- store handoff files locally under `.fixthis/`

## No Required `testTag`

**Decision:** `Modifier.testTag()` is an optional signal, not a prerequisite.

**Why:** Requiring every composable to have a tag would make adoption painful in
real apps. FixThis instead combines text, content descriptions, roles, actions,
bounds, nearby context, screenshots, and source-index matches. Tags are used
when available, especially the strict `comp:<ComposableName>:<variant>`
convention, but a project can start without adding tags everywhere.

**Trade-off:** Targeting is best-effort. The prompt includes confidence and
warnings so the agent knows when to verify instead of trusting a single hint.

## No AccessibilityService

**Decision:** FixThis does not use an Android `AccessibilityService`.

**Why:** The product is scoped to the debug app under development. An
AccessibilityService would look like device-wide control, require broader user
permissions, and weaken the privacy story. In-process Compose inspection is a
better match for a debug-only developer tool.

**Trade-off:** FixThis cannot inspect arbitrary apps or control the whole
device. That is intentional.

## Desktop MCP Console First

**Decision:** The browser feedback console is the primary UX. The app shows only
`MCP waiting` / `MCP connected`.

**Why:** Earlier designs explored in-app annotation. The shipped direction moved
annotation to the desktop console because the handoff, screenshots, session
state, and agent queue are desktop-owned. This keeps the app-side runtime small
and lets the console handle device selection, stale previews, recovery, history,
Copy Prompt, and Save to MCP in one place.

**Trade-off:** First setup requires a desktop CLI/MCP process, but the console
can guide the user through Start, Open app, Reconnect, Choose device, and Check
phone states.

## MCP Server Runs On Desktop

**Decision:** The MCP server is a desktop process, not an Android process.

**Why:** MCP clients normally launch local stdio servers. Running the server on
desktop avoids app-side HTTP servers, Android lifecycle coupling, extra network
exposure, and app permissions.

The runtime shape is:

```text
AI client
-> stdio MCP server on desktop
-> ADB/local bridge
-> Android debug sidekick
```

## Workflow-Level MCP Tools

**Decision:** MCP exposes workflow tools rather than many tiny automation
primitives.

**Why:** Agents and users need a clear queue-oriented flow more than a raw device
automation API. The important tools are:

- `fixthis_open_feedback_console`
- `fixthis_list_feedback_sessions`
- `fixthis_read_feedback`
- `fixthis_claim_feedback`
- `fixthis_resolve_feedback`
- `fixthis_get_current_screen`
- `fixthis_verify_ui_change`
- `fixthis_status`

Lower-level actions exist only where they support the feedback workflow, such as
debug-only navigation through the console.

## Source Index Before Compiler Plugin

**Decision:** V1 source candidates come from a Gradle source index. A Kotlin or
Compose compiler plugin is not part of the core capture path.

**Why:** Exact runtime-node-to-source mapping is hard and version-sensitive.
Compiler or Compose tooling internals can be powerful, but they create a
fragile compatibility surface for an open-source library. A source index is
stable, understandable, and useful enough for V1.

**Trade-off:** Source candidates are ranked hints. The prompt always tells
agents to verify the screenshot, target, and code before editing.

## Stable Target Evidence Is Additive

**Decision:** Target evidence and reliability metadata are nullable additive
fields.

**Why:** The core handoff should degrade gracefully. Evidence can be rich when
semantics, screenshots, source candidates, and identity hints are available, but
older sessions and weaker targets still need to render.

See [ADR-0006](../architecture/adr/0006-stable-target-evidence-open-source-compatibility.md).

## Always Produce Useful Context

**Decision:** FixThis should still create handoff context when some evidence is
missing.

Fallback order:

```text
selected Compose node
-> ambiguous node candidates
-> visual area or tap coordinate plus screenshot
-> coordinate plus nearby text context
-> user comment plus search hints
```

An imperfect handoff with explicit warnings is more useful than an empty result.

## Local-First Export

**Decision:** Copy Prompt writes to the clipboard, and Save to MCP writes local
session files. The console does not call an external AI API.

**Why:** Screenshots and UI text can be sensitive. Local-first behavior keeps
trust boundaries clear and makes `.fixthis/` the explicit artifact location.

## Idempotent Draft Handoff

**Decision:** Browser draft workspaces assign stable draft item ids, and the
server persists them as a retry key for `Copy Prompt` / `Save to MCP` saves.

**Why:** Local browser requests can be retried after reconnects, slow handoffs,
or tab lifecycle races. Duplicate saves should not create duplicate agent work,
extra event-log entries, or a second evidence screen for the same frozen draft.

**Trade-off:** Browser draft ids are storage internals, not agent workflow ids.
Agents should claim and resolve persisted `itemId` values from
`fixthis_read_feedback`.

## Current Source Of Truth

Use these documents first:

- [Product concept](README.md)
- [Architecture overview](../architecture/overview.md)
- [Feedback console contract](../reference/feedback-console-contract.md)
- [Output schema](../reference/output-schema.md)
- [ADR index](../architecture/adr/README.md)

Historical design records remain useful, but current reference docs, ADRs,
release notes, and tests win when documents disagree.
