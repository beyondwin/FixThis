# Agent Install Snippet

Paste this into an Android app repository's `AGENTS.md` or `CLAUDE.md` so a
coding agent can install FixThis without following human-oriented docs.

```markdown
## FixThis Agent Setup

When asked to add FixThis to this Android app:

1. Find the Android application module and its `applicationId`.
2. Ensure the FixThis CLI is installed. On macOS, prefer Homebrew:

   ```bash
   brew install beyondwin/fixthis/fixthis
   ```

   With npm:

   ```bash
   npm install -g @beyondwin/fixthis
   ```

   On macOS/Linux without a package manager, install from the GitHub Release
   CLI/MCP package:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
     | bash -s -- --version v0.2.3
   ```
3. Run `fixthis install-agent --project-dir . --target all`. This patches the
   detected app module with Gradle plugin `io.github.beyondwin.fixthis.compose`,
   writes MCP config for Claude Code / Codex, and writes `.fixthis/agent-setup.*`
   handoff files. Pass `--package <applicationId>` if detection is ambiguous.
   Pass `--dry-run` before writing if the repo has unusual Gradle wiring.

   If editing manually instead, apply the plugin to the app module.
   Use the published plugin version when available:

   ```kotlin
   plugins {
       id("io.github.beyondwin.fixthis.compose") version "0.2.3"
   }
   ```

4. Run `./gradlew fixthisSetup` from the repository root. If the project has
   flavored debug variants, run the variant-specific task such as
   `./gradlew :app:fixthisSetupStagingDebug`.
5. Run `fixthis doctor --project-dir . --json` and fix any failed checks.
6. Open the console with MCP tool `fixthis_open_feedback_console`.

Never add FixThis to release builds. The sidekick is debug-only.
Do not commit `.fixthis/feedback-sessions/`, screenshots, or local artifacts.
```
