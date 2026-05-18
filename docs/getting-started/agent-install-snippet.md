# Agent Install Snippet

Paste this into an Android app repository's `AGENTS.md` or `CLAUDE.md` so a
coding agent can install FixThis without following human-oriented docs.

## Install method decision tree (for agents)

Pick the first matching branch:

```bash
if command -v brew >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
    brew install beyondwin/tools/fixthis
elif command -v npm >/dev/null 2>&1; then
    npm install -g @beyondwin/fixthis
else
    curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
      | bash -s -- --version v0.3.0
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
   brew install beyondwin/tools/fixthis
   ```

   With npm:

   ```bash
   npm install -g @beyondwin/fixthis
   ```

   On macOS/Linux without a package manager, install from the GitHub Release
   CLI/MCP package:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \
     | bash -s -- --version v0.3.0
   ```
3. Run `fixthis install-agent --project-dir . --target all`. This patches the
   detected app module with Gradle plugin `io.github.beyondwin.fixthis.compose`,
   writes MCP config for Claude Code / Codex, writes `.fixthis/project.json`,
   and writes `.fixthis/agent-setup.*` handoff files. Pass
   `--package <applicationId>` if detection is ambiguous. Pass `--dry-run`
   before writing if the repo has unusual Gradle wiring.
4. Run `fixthis doctor --project-dir . --json`. Treat the JSON readiness
   result as the source of truth.
5. If MCP config was written: Restart Claude Code or Codex so the client reloads it.
6. Open the console with MCP tool `fixthis_open_feedback_console`.

If doctor reports `NEEDS_INSTALL` or `.fixthis/project.json` is missing
generated metadata, fall back to the manual Gradle plugin path. Apply the
plugin to the app module:

```kotlin
plugins {
    id("io.github.beyondwin.fixthis.compose") version "0.3.0"
}
```

Then run `./gradlew fixthisSetup` from the repository root. If the project has
flavored debug variants, run the variant-specific task such as
`./gradlew :app:fixthisSetupStagingDebug`. Rerun
`fixthis doctor --project-dir . --json` afterwards.

Never add FixThis to release builds. The sidekick is debug-only.
Do not commit `.fixthis/feedback-sessions/`, screenshots, or local artifacts.
```
