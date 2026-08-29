# Agent Install Snippet

Paste this into an Android app repo's `AGENTS.md` or `CLAUDE.md`.

## Install method

Pick the first matching branch:

```bash
if command -v brew >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
    brew update
    brew upgrade beyondwin/tools/fixthis || brew install beyondwin/tools/fixthis
elif command -v npm >/dev/null 2>&1; then
    npm install -g @beyondwin/fixthis
else
    curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
      | bash -s -- --version v1.5.0
fi
```

Verify in the same shell:

```bash
fixthis version --json | jq -r '.cliVersion'
```

## Snippet to paste

```markdown
## FixThis Agent Setup

When asked to add FixThis to this Android app:

1. Find the Android application module and its `applicationId`.
2. Ensure the FixThis CLI is installed. On macOS, prefer Homebrew:

   ```bash
   brew update
   brew upgrade beyondwin/tools/fixthis || brew install beyondwin/tools/fixthis
   ```

   With npm:

   ```bash
   npm install -g @beyondwin/fixthis
   ```

   On macOS/Linux without a package manager:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
     | bash -s -- --version v1.5.0
   ```

Use this order:
1. Run `fixthis install-agent --project-dir . --target all --verify --json`.
2. Use the JSON `readiness.state` and `actions[]` as the source of truth.
3. If `requiresUserAction` is true, tell me the exact blocking action.
4. Do not call `fixthis_open_feedback_console` until `readyForMcpTooling` is true, or until the report's `agent_after_restart` action is reached after restart.

Restart Claude Code or Codex if the report asks for it.

After the install-agent command returns, remember:

1. The command patches the detected app module with Gradle plugin
   `io.github.beyondwin.fixthis.compose`, writes MCP config for Claude Code /
   Codex / Cursor, writes `.fixthis/project.json`, writes
   `.fixthis/agent-setup.*` handoff files, and runs doctor checks when setup
   was not a dry run. Pass `--package <applicationId>` if detection is
   ambiguous. Pass `--dry-run` before writing if the repo has unusual Gradle
   wiring; dry-run verification reports
   `verification.skippedReason=dry_run_no_side_effects`.
2. For manual diagnostics, run `fixthis doctor --project-dir . --json`.
3. Open the console with MCP tool `fixthis_open_feedback_console` only after
   the verify report allows it.

If doctor reports `NEEDS_INSTALL` or `.fixthis/project.json` is missing
generated metadata, fall back to the manual Gradle plugin path. Apply the
plugin to the app module:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "1.5.0"
}
```

Then run `./gradlew fixthisSetup` from the repository root. If the project has
flavored debug variants, run the variant-specific task such as
`./gradlew :app:fixthisSetupStagingDebug`. Rerun
`fixthis install-agent --project-dir . --target all --verify --json`
afterwards.

Never add FixThis to release builds. The sidekick is debug-only.
Do not commit `.fixthis/feedback-sessions/`, screenshots, or local artifacts.
```

## ChatGPT

`fixthis install-agent` cannot write a ChatGPT MCP config. ChatGPT registers
connectors through Settings → Connectors, and a connector needs a public HTTPS
`/mcp` endpoint. FixThis MCP runs locally over `stdio`.

Use **Copy Prompt** instead:

1. Open FixThis Studio.
2. Click **Annotate** and select a UI element or drag an area.
3. Type the change.
4. Click **Copy Prompt**.
5. Paste into ChatGPT.

No MCP config, restart, or connector.
