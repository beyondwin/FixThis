---
name: fixthis-feedback-loop
description: Read, claim, implement, verify, and resolve FixThis feedback items.
---

# FixThis Feedback Loop

Use when a user asks to handle FixThis feedback.

Rules:
- FixThis feedback is local, debug-only, and Jetpack Compose-only.
- Do not commit `.fixthis/`.
- Call `fixthis_claim_feedback` before making code changes when MCP queue items are available.
- Call `fixthis_resolve_feedback` after verification.
- Use `docs/guides/agents.md` and `docs/reference/mcp-tools.md` for canonical workflow and tool schemas.

Workflow:
1. Call `fixthis_list_feedback`.
2. Call `fixthis_read_feedback` for the item.
3. Call `fixthis_claim_feedback({sessionId, itemId})` before edits.
4. Make the smallest code change that satisfies the feedback.
5. Verify with the relevant project tests and, when possible, FixThis UI evidence.
6. Call `fixthis_resolve_feedback({sessionId, itemId, status: "resolved", summary})`.
