---
name: fixthis-install-agent
description: Install and verify FixThis in a debug Jetpack Compose Android app.
---

# FixThis Install Agent

Use when a user asks to install or bootstrap FixThis in an Android app.

Rules:
- FixThis is debug-only and Jetpack Compose-only.
- Do not configure release builds.
- Do not commit `.fixthis/`.
- Prefer `fixthis doctor --project-dir . --json` as the readiness source of truth.
- Use `docs/getting-started/connect-your-agent.md` and `docs/reference/cli.md` for canonical setup details.

Workflow:
1. Run `fixthis install-agent --project-dir . --target all`.
2. Run `fixthis doctor --project-dir . --json`.
3. If MCP config was written, tell the user to restart Codex or Claude Code before calling `fixthis_open_feedback_console`.
4. If doctor reports missing generated metadata, run `./gradlew fixthisSetup` and rerun doctor.
