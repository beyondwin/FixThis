# Agent Code Compass

This page is a routing guide for coding agents and maintainers changing FixThis
source code. It does not replace reference contracts, ADRs, or the current
implementation. Use it to choose the first files and checks for a task, then
verify behavior against source and reference docs.

## Source-Of-Truth Order

1. Current Kotlin, JavaScript, Gradle, shell, and Markdown implementation.
2. `docs/reference/*` for stable CLI, MCP, bridge, output schema, privacy,
   compatibility, and console contracts.
3. `docs/architecture/adr/*` for durable architecture decisions.
4. `docs/guides/project-map.md` and this compass for navigation.
5. `docs/superpowers/*`, `docs/specs/*`, and `docs/plans/*` only as historical
   planning context unless a maintained doc, ADR, or source file points to them.

## Global Boundaries

- `:fixthis-compose-core` must not depend on Android UI, MCP, CLI, Gradle plugin,
  sidekick runtime, browser DTOs, or `.fixthis/` paths.
- Persisted MCP JSON field names such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates` are
  compatibility contracts.
- Bridge protocol changes require `docs/reference/bridge-protocol.md` and the
  bridge/client implementation to move together.
- The Android app does not host MCP or HTTP; desktop `fixthis-mcp` owns the
  local console, MCP tools, session store, and feedback queue.
- Do not commit `.fixthis/`, `graphify-out/`, build outputs, generated fixture
  workspaces, screenshots, or reports unless a maintained doc explicitly says a
  checked-in artifact is required.

## Task Routes

| Work type | Read first | First source files | Boundaries | Focused checks |
| --- | --- | --- | --- | --- |
| Source matching and target reliability | `docs/reference/source-matching.md`, `docs/reference/output-schema.md` | `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt`, `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt` | Keep source scoring pure; do not move MCP DTOs or `.fixthis/` storage into `compose-core`. | `./gradlew :fixthis-compose-core:test --tests '*SourceMatcher*' --no-daemon`, `npm run source-matching:fixtures:test` |
| MCP session lifecycle, persistence, and queue | `docs/reference/output-schema.md`, `docs/reference/mcp-tools.md`, `docs/architecture/adr/0008-session-package-decomposition.md` | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt` | Preserve persisted JSON names and ADR-0008 package direction; new `session` dependencies need a rule and ADR update. | `./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon`, `./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon` |
| Compact handoff and agent prompt output | `docs/reference/feedback-console-contract.md`, `docs/design/handoff-prompt-rationale.md`, `docs/reference/output-schema.md` | `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/FeedbackQueueFormatter.kt`, `fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt` | Keep JSON complete and Markdown compact; do not rename persisted fields or split Copy Prompt and Save to MCP formats. | `npm run handoff:eval:test`, `./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon` |
| Browser console UI and state sync | `docs/reference/feedback-console-contract.md`, `docs/architecture/console-state-sync-design.md` | `fixthis-mcp/src/main/console/app.js`, `fixthis-mcp/src/main/console/events.js`, `fixthis-mcp/src/main/console/state.js`, `fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/FeedbackConsoleServer.kt` | Keep `/api/events` primary with fallback polling retained; browser DTO changes must match route tests and contract docs. | `npm run console:test:fast`, `node scripts/build-console-assets.mjs --check`, `./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon` |
| CLI setup and external app installation | `docs/reference/cli.md`, `docs/reference/agent-setup-schema.md`, `docs/getting-started/add-to-your-app.md` | `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentCommand.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InitCommand.kt` | Keep CLI help, docs, and install-agent JSON schema aligned; do not hide required user action in setup reports. | `./gradlew :fixthis-cli:test --no-daemon`, `bash scripts/check-docs-cli-surface.sh`, `npm run docs:agent-bootstrap:test` |
| Android bridge and sidekick runtime | `docs/reference/bridge-protocol.md`, `docs/architecture/overview.md`, `docs/guides/troubleshooting.md` | `fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt`, `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt` | Debug builds only; no release runtime support; bridge protocol changes are coordinated and additive when possible. | `./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon` |
| Gradle plugin and source-index generation | `docs/reference/source-matching.md`, `docs/reference/compatibility.md` | `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt`, `fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt` | Debug variant wiring only; generated assets stay under build output; no running-device state in the plugin. | `./gradlew :fixthis-gradle-plugin:test --no-daemon`, `npm run source-matching:fixtures:test` |
| Release readiness and evidence scripts | `docs/contributing/release-readiness.md`, `docs/contributing/required-checks.md`, `CONTRIBUTING.md` | `scripts/check-release-readiness.mjs`, `scripts/evidence-runner.mjs`, `scripts/release-gate.mjs`, `scripts/required-checks-observation.mjs`, `package.json` | Do not claim branch-protection admin changes from repo-only evidence; distinguish pass, deferred, fixture drift, and admin action pending. | `node scripts/check-release-readiness.mjs`, `npm run checks:observation -- --json`, `npm run release:check` |
| Architecture and guardrails | `docs/architecture/adr/README.md`, `docs/architecture/adr/0001-use-clean-architecture-layering.md`, `docs/architecture/adr/0008-session-package-decomposition.md` | `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt`, `fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ArchitectureHotspotBudgetTest.kt` | Tighten rules when cleanup removes exceptions; add ADR text before allowing new exception directions. | `./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon`, `git diff --check` |

## When A Boundary Test Fails

1. Read the failing assertion message and the cited ADR or guide.
2. Prefer removing the dependency, moving shared helpers to a lower package, or
   splitting a hotspot file.
3. If a new dependency direction is genuinely necessary, update the relevant ADR
   in the same change as the test rule.
4. Do not raise hotspot budgets silently. If a file shrinks, lower its budget in
   the same commit.
