# Agent Install Snippet

Paste this into an Android app repository's `AGENTS.md` or `CLAUDE.md` so a
coding agent can install FixThis without following human-oriented docs.

## Install method decision tree (for agents)

Pick the first matching branch:

```bash
if command -v brew >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
    brew update
    brew upgrade beyondwin/tools/fixthis || brew install beyondwin/tools/fixthis
elif command -v npm >/dev/null 2>&1; then
    npm install -g @beyondwin/fixthis
else
    curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
      | bash -s -- --version v1.3.0
fi
```

If your agent needs to verify install success in the same shell session:

```bash
fixthis version --json | jq -r '.cliVersion'   # exits 0 with the version on stdout
```

## Snippet to paste into the target repo

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

   On macOS/Linux without a package manager, install from the GitHub Release
   CLI/MCP package:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
     | bash -s -- --version v1.3.0
   ```
3. Run `fixthis install-agent --project-dir . --target all --verify --json`.
   This patches the detected app module with Gradle plugin
   `io.github.beyondwin.fixthis.compose`, writes MCP config for Claude Code /
   Codex / Cursor, writes `.fixthis/project.json`, writes
   `.fixthis/agent-setup.*` handoff files, and runs doctor checks when setup
   was not a dry run. Pass `--package <applicationId>` if detection is
   ambiguous. Pass `--dry-run` before writing if the repo has unusual Gradle
   wiring; dry-run verification reports
   `verification.skippedReason=dry_run_no_side_effects`.
4. Treat the JSON `readiness.state` as the app-readiness source of truth and
   `actions[]` as the execution queue.
5. If `requiresUserAction` is true, stop and ask the user to complete that
   action. Do not open the console until `readyForMcpTooling` is true, or until
   the report's `agent_after_restart` action is reached after restarting the
   MCP client.
6. Open the console with MCP tool `fixthis_open_feedback_console`.

If doctor reports `NEEDS_INSTALL` or `.fixthis/project.json` is missing
generated metadata, fall back to the manual Gradle plugin path. Apply the
plugin to the app module:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "1.3.0"
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

`fixthis install-agent` cannot write a ChatGPT MCP config. Unlike Claude Code
(`.claude/settings.json`), Codex (`~/.codex/config.toml`), and Cursor
(`.cursor/mcp.json`), ChatGPT has **no writable, file-based MCP configuration**.
ChatGPT registers MCP servers as *connectors* through the ChatGPT settings UI
(Settings → Connectors → Advanced → enable Developer Mode → Create connector),
and a connector must point at a **public HTTPS `/mcp` endpoint** — it does not
accept a local `stdio` command. FixThis's MCP server runs locally over `stdio`,
so there is nothing for a CLI to write and no local endpoint ChatGPT could
reach without a tunnel.

Use **Copy Prompt** with ChatGPT instead:

1. Open FixThis Studio.
2. Click **Annotate** and select a UI element or drag a visual area.
3. Type the change request in the annotation detail.
4. Click **Copy Prompt**.
5. Paste the Markdown into ChatGPT.

No MCP config file, restart, or connector setup is required for Copy Prompt.
(Advanced users who self-host the FixThis MCP server behind a public HTTPS
tunnel can register it as a ChatGPT connector manually via the settings UI, but
that is outside the scope of `install-agent`.)
