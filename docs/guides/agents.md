# Working with AI Agents

First-time setup: [Connect your agent](../getting-started/connect-your-agent.md).
This page is the queue and confidence details.

| Agent | Mode | Setup |
| --- | --- | --- |
| Claude Code | **Save to MCP** | `./scripts/bootstrap-mcp.sh --package <applicationId> --target claude` |
| Codex | **Save to MCP** | `./scripts/bootstrap-mcp.sh --package <applicationId> --target codex` |
| Cursor / ChatGPT / other chat | **Copy Prompt** | None. Paste from the clipboard. |

Both modes share the same Markdown and JSON. Save to MCP skips the paste step.

New sessions use **Auto** runtime evidence on Save to MCP. The session can
switch to **Manual** or **Off**. Copy Prompt never starts collection.

## Claude Code

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target claude
```

Restart Claude Code, then:

```
fixthis_open_feedback_console
```

After saving:

> Read the latest FixThis handoff and start fixing.

The agent calls `fixthis_read_feedback`, then edits. Claim before editing:

```
fixthis_claim_feedback
```

That marks the item `in_progress`. Pass `agentNote` if useful.

When done:

> Mark all FixThis items in that batch as resolved.

`fixthis_resolve_feedback` takes `resolved`, `needs_clarification`, or
`wont_fix`.

Targeted diagnostics: `fixthis_collect_runtime_evidence` with one preset
(`baseline`, `logs`, `memory`, `performance`). No arbitrary host commands.

## Codex

```bash
./scripts/bootstrap-mcp.sh --package <applicationId> --target codex
```

Restart Codex. Same tools as Claude Code. Codex also reads root `AGENTS.md`.

## Cursor, ChatGPT, and other chat agents

1. Annotate in the console.
2. Click **Copy Prompt**.
3. Paste. The Markdown has comments, target evidence, top-3 source candidates,
   optional `editSurface` hints, and severity.

No MCP setup.

## Shared behavior

- Both modes are local. Save to MCP writes `.fixthis/feedback-sessions/<id>/`.
  Runtime evidence stays under `.fixthis/runtime-evidence/`. Do not commit it.
- JSON is complete. Markdown is compact but includes item and session IDs.
  See [output schema](../reference/output-schema.md) and
  [handoff rationale](../design/handoff-prompt-rationale.md).
- Written annotations on one frozen preview share one batch. Pin-only leftovers
  stay local for Copy Prompt and are dropped for Save to MCP.
- Draft saves reuse browser draft ids, so retries do not duplicate items. Work
  from `itemId`, not `draftItemId`.
- For visual or style requests, read `editSurface` / `role=` before assuming
  the top source candidate is the edit site.
- If the frozen preview fingerprint no longer matches the live screen, the
  console asks to re-capture, force-save, or cancel.

### Target reliability warnings

`targetReliability` / `targetConfidence=` is confidence, not priority.

| Signal | Meaning |
| --- | --- |
| `HIGH` | Strong start. Still verify screenshot and code. |
| `MEDIUM` | Inspect listed candidates. The right call site may be nearby. |
| `LOW` | Use screenshot, bounds, comment, nearby labels first. |
| `VISUAL_AREA_ONLY` | User drew an area, not a Compose node. |
| `NO_MEANINGFUL_COMPOSE_TARGET` | No useful Compose node covered the pixels. |
| `POSSIBLE_VIEW_INTEROP` | May be AndroidView / WebView. Do not assume Compose rendered it. |
| `LOW_SOURCE_CANDIDATE_MARGIN` | Top candidates are close. Inspect runners-up. |
| `SOURCE_INDEX_STALE` | Reinstall the debug APK before trusting file/line. |
| `SCREEN_FINGERPRINT_MISMATCH_FORCED` | User force-saved after the screen changed. |
| `SCREEN_FINGERPRINT_UNAVAILABLE` | Mismatch check was skipped. |
| `SENSITIVE_TEXT_REDACTED` | Sensitive text was withheld. |

## Next

- [Console tour](feedback-console-tour.md)
- [MCP tools](../reference/mcp-tools.md)
- [CLI](../reference/cli.md)
