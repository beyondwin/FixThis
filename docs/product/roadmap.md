# Roadmap

V1 stays narrow: local, debug-only Compose handoff. Broader Android UI stacks
and extra package channels wait.

## V1

- Jetpack Compose only. Views / XML / WebView are not source-targeted.
- Debug builds only.
- Local ADB and localhost. No upload.
- No required `testTag`s.
- No AccessibilityService.
- MCP-first console. The app shows a status pill.
- Best-effort source candidates and edit-surface hints.
- Pixel screenshots. Semantics text can be redacted; pixels can still be
  sensitive.
- Bounded runtime diagnostics on Save to MCP. Copy Prompt never starts
  collection.

## Shipped

**Feedback verification receipts.** MCP verifies a claimed item against the
persisted baseline with `text_present`, `text_absent`, and `target_present`.
Receipts are `PASS` / `WARN` / `FAIL`. Resolution can stay receipt-free.
Bridge protocol `1.3` is unchanged.

**Runtime evidence autopilot.** Host CLI/MCP collects allowlisted logcat,
memory, and frame evidence with a 2,500 ms deadline, redaction, and quotas.
Presets: `baseline`, `logs`, `memory`, `performance`.

## Still in play

- Keep public artifact coordinates honest:
  [release readiness](../contributing/release-readiness.md).
- Deeper AndroidView / WebView boundary context, not XML source targeting.
- Observe SSE fallback until it is unused:
  [console sync](../architecture/console-state-sync-design.md).
- Smarter source matching for layouts, shared components, and edit-surface
  roles.
- First-class MCP writers for more agents. Copy Prompt already works.

## After V1

XML/View exact source targeting, WebView DOM, Flutter / RN / iOS, production
runtime, cloud review, automatic code edits inside FixThis, guaranteed exact
source lines.

Vote or contribute through [CONTRIBUTING](../../CONTRIBUTING.md).
