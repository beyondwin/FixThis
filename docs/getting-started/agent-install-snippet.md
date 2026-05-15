# Agent Install Snippet

Paste this into an Android app repository's `AGENTS.md` or `CLAUDE.md` so a
coding agent can install FixThis without following human-oriented docs.

```markdown
## FixThis Agent Setup

When asked to add FixThis to this Android app:

1. Find the Android application module and its `applicationId`.
2. Apply Gradle plugin `io.beyondwin.fixthis.compose` to the app module.
   Use the published plugin version when available:

   ```kotlin
   plugins {
       id("io.beyondwin.fixthis.compose") version "0.2.0"
   }
   ```

3. Run `./gradlew fixthisSetup` from the repository root. If the project has
   flavored debug variants, run the variant-specific task such as
   `./gradlew :app:fixthisSetupStagingDebug`.
4. Ensure the FixThis CLI is installed. Prefer the published package manager
   path when available; otherwise install from the GitHub Release CLI/MCP
   package.
5. Run `fixthis init --agent --project-dir . --target all`
   or `fixthis install-agent --project-dir . --target all`.
6. Run `fixthis doctor --project-dir .`.
7. Open the console with MCP tool `fixthis_open_feedback_console`.

Never add FixThis to release builds. The sidekick is debug-only.
Do not commit `.fixthis/feedback-sessions/`, screenshots, or local artifacts.
```

Until Maven Central and the Gradle Plugin Portal publication are live, step 2
must use the source/composite-build wiring documented in
[`add-to-your-app.md`](add-to-your-app.md).
