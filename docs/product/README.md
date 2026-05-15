# Product Concept

FixThis is a debug-only sidekick for Jetpack Compose Android apps. It lets a
developer, designer, PM, or QA person point at the running UI, describe the
change they want, and hand an AI coding agent enough local context to edit the
right source code.

The product is intentionally narrow: it is not a general mobile automation
platform, an Android Studio inspector, a production feedback SDK, or a cloud
review service. It is a local bridge between a human-selected Compose UI target
and an agent that can change the app's source.

## Core Workflow

```text
Run a debug Compose app
-> open FixThis Studio in a desktop browser
-> navigate the live preview
-> freeze the screen with Annotate
-> select a Compose component or draw a visual area
-> write one or more comments
-> Copy Prompt or Save to MCP
-> the agent edits and resolves the feedback
```

The Android app shows only a small MCP connection status pill. Selection,
annotation, prompt generation, queue state, and agent handoff live in the
desktop browser console.

## Why This Exists

Plain screenshots and free-form chat are often ambiguous:

- The agent may not know which route, Activity, or repeated list item is shown.
- A label can appear in multiple files or be rendered by a shared composable.
- Dense UI makes "this button" or "the third card" hard to map to code.
- Screenshots show pixels, but not source candidates, semantics, or target
  confidence.

FixThis adds runtime evidence around the human selection:

- screenshot bounds and optional crop artifacts
- Compose semantics for the selected target and nearby context
- source candidates ranked from the Gradle source index
- target confidence and warning signals
- stable item IDs for MCP claim/resolve workflows
- batching across multiple annotations on one frozen screen

## Product Principles

- **Point first.** The human chooses the UI target directly on a running app.
- **Tell second.** The request stays attached to that selected target.
- **Context over certainty.** Source hints are candidates, not promises.
- **No required test tags.** `testTag` improves evidence but is optional.
- **Compose-only.** V1 focuses on Jetpack Compose instead of all Android UI
  stacks.
- **Debug-only.** The sidekick runs in debug builds and is not a production
  feedback feature.
- **Local-first.** FixThis does not upload screenshots, comments, source hints,
  or prompt text by default.
- **Agent-ready, not agent-owned.** FixThis provides context and queue state; it
  does not write code itself.

## Users

Android Compose developers use FixThis to give coding agents precise UI context
and then verify the result.

Agent power users use the MCP flow to open the console, read saved feedback,
claim items, edit code, and mark items resolved.

Designers, PMs, and QA users can annotate the desktop preview without knowing
ADB, MCP, source indexes, or package names. They can use Copy Prompt for any
chat-style agent, or Save to MCP when a configured agent will pick up the queue.

## Current Scope

In scope:

- Jetpack Compose debug apps
- local desktop console over ADB and localhost
- Compose semantics inspection
- screenshot and crop artifacts
- best-effort source candidates
- compact Markdown handoff and complete JSON session data
- MCP feedback queue with claim/resolve status

Out of scope for V1:

- XML/View source targeting
- WebView DOM inspection
- Flutter, React Native, iOS, or other app stacks
- production runtime usage
- AccessibilityService-based device-wide control
- external AI API calls from the console
- automatic code edits inside FixThis itself
- guaranteed exact source-line mapping

## Where To Read Next

- [Concept and handoff rationale](concept-and-handoff-rationale.md) is the
  self-contained explanation of the product concept, core decisions, and prompt
  design rationale.
- [Decision rationale](decision-rationale.md) explains the major product and
  technical trade-offs.
- [Roadmap](roadmap.md) tracks V1 scope, high-priority follow-up work, and
  explicitly deferred areas.
- [Architecture overview](../architecture/overview.md) explains the current
  module boundaries and runtime flow.
- [Handoff prompt rationale](../design/handoff-prompt-rationale.md) explains why
  the compact prompt is shaped the way it is.
- [Feedback console contract](../reference/feedback-console-contract.md) is the
  current prompt and browser-console contract.
