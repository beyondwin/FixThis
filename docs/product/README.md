# Product

FixThis is a debug-only sidekick for Jetpack Compose. Point at the running
UI, say what to change, and hand a coding agent enough local context to edit
the right source.

It is not a mobile automation platform, an Android Studio inspector, a
production feedback SDK, or a cloud review service.

## Workflow

```text
Run a debug Compose app
-> open FixThis Studio
-> navigate the live preview
-> freeze with Annotate
-> click a component or drag an area
-> write comments
-> Copy Prompt, or Save to MCP
-> the agent edits and resolves
```

The Android app shows a small MCP status pill. Selection, annotation, and
handoff live in the desktop console.

## Why it exists

A screenshot often is not enough: repeated list items, shared composables,
dense screens, labels that appear in many files. FixThis attaches runtime
evidence to the human selection: bounds, semantics, ranked source candidates,
edit-surface hints, confidence, item IDs, and optional bounded diagnostics.

## Principles

- Point first. Tell second.
- Source hints are candidates, not promises.
- `testTag` helps but is not required.
- Compose-only, debug-only, local-first.
- FixThis hands off context. It does not write code.

## Users

Compose developers, agent power users on MCP, and designers / PMs / QA who
can annotate the desktop preview without knowing ADB.

## V1 scope

In: Compose debug apps, local ADB console, semantics, screenshots,
best-effort source candidates, compact Markdown plus complete JSON, MCP
claim/resolve, Auto / Manual / Off runtime evidence on Save to MCP.

Out: XML/View source targeting, WebView DOM, Flutter / RN / iOS, production
runtime, AccessibilityService, external AI API calls, automatic code edits,
guaranteed exact source lines.

## Next

- [Decisions](decision-rationale.md)
- [Roadmap](roadmap.md)
- [Handoff prompt](../design/handoff-prompt-rationale.md)
- [Architecture](../architecture/overview.md)
- [Console contract](../reference/feedback-console-contract.md)
