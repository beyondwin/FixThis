export const CONNECTED_PROOF_COMMAND = "npm run android:proof -- --strict";
export const SAFE_FALLBACK_COMMAND = "npm run ci:local:changed";

export const ROUTES = Object.freeze([
  {
    id: "core-source",
    tasks: ["core", "source-matching", "target-reliability"],
    pathPrefixes: ["fixthis-compose-core/"],
    docs: [
      "docs/reference/source-matching.md",
      "docs/reference/output-schema.md",
    ],
    sources: [
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/source/SourceMatcher.kt",
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/target/TargetReliabilityCalculator.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-compose-core:test --no-daemon",
      "npm run source-matching:fixtures:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "mcp-session",
    tasks: ["mcp", "session", "queue"],
    pathPrefixes: ["fixthis-mcp/"],
    docs: [
      "docs/reference/output-schema.md",
      "docs/reference/mcp-tools.md",
      "docs/architecture/adr/0008-session-package-decomposition.md",
    ],
    sources: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/FeedbackSessionService.kt",
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/lifecycle/store/FeedbackSessionStore.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-mcp:test --tests '*session*' --no-daemon",
      "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "handoff",
    tasks: ["handoff", "prompt", "output-schema"],
    pathPrefixes: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/",
    ],
    docs: [
      "docs/reference/feedback-console-contract.md",
      "docs/design/handoff-prompt-rationale.md",
      "docs/reference/output-schema.md",
    ],
    sources: [
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/session/handoff/CompactHandoffRenderer.kt",
      "fixthis-compose-core/src/main/kotlin/io/github/beyondwin/fixthis/compose/core/format/FixThisMarkdownFormatter.kt",
    ],
    focusedChecks: [
      "npm run handoff:eval:test",
      "./gradlew :fixthis-mcp:test --tests '*Handoff*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "console",
    tasks: ["console", "browser", "sse"],
    pathPrefixes: [
      "fixthis-mcp/src/main/console/",
      "fixthis-mcp/src/main/resources/console/",
      "fixthis-mcp/src/main/kotlin/io/github/beyondwin/fixthis/mcp/console/",
      "scripts/console",
    ],
    docs: [
      "docs/reference/feedback-console-contract.md",
      "docs/architecture/console-state-sync-design.md",
    ],
    sources: [
      "fixthis-mcp/src/main/console/consoleApp.js",
      "fixthis-mcp/src/main/console/events.js",
    ],
    focusedChecks: [
      "npm run console:test:fast",
      "node scripts/build-console-assets.mjs --check",
      "./gradlew :fixthis-mcp:test --tests '*console*' --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "cli-setup",
    tasks: ["cli", "setup", "install-agent", "doctor"],
    pathPrefixes: ["fixthis-cli/"],
    docs: [
      "docs/reference/cli.md",
      "docs/reference/agent-setup-schema.md",
      "docs/getting-started/add-to-your-app.md",
    ],
    sources: [
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt",
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-cli:test --no-daemon",
      "bash scripts/check-docs-cli-surface.sh",
      "npm run docs:agent-bootstrap:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "android-runtime",
    tasks: ["android-runtime", "sidekick", "bridge", "connected-proof"],
    pathPrefixes: ["fixthis-compose-sidekick/"],
    docs: [
      "docs/reference/bridge-protocol.md",
      "docs/architecture/overview.md",
      "docs/guides/troubleshooting.md",
    ],
    sources: [
      "fixthis-compose-sidekick/src/main/kotlin/io/github/beyondwin/fixthis/compose/sidekick/bridge/BridgeServer.kt",
      "fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/BridgeClient.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-compose-sidekick:testDebugUnitTest :fixthis-cli:test --no-daemon",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: true,
  },
  {
    id: "gradle-plugin",
    tasks: ["gradle-plugin", "source-index"],
    pathPrefixes: ["fixthis-gradle-plugin/"],
    docs: [
      "docs/reference/source-matching.md",
      "docs/reference/compatibility.md",
    ],
    sources: [
      "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/FixThisGradlePlugin.kt",
      "fixthis-gradle-plugin/src/main/kotlin/io/github/beyondwin/fixthis/gradle/source/KotlinSourceScanner.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-gradle-plugin:test --no-daemon",
      "npm run source-matching:fixtures:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "release-docs",
    tasks: ["release-docs"],
    pathPrefixes: [
      "docs/contributing/release",
      "docs/releases/",
      "CHANGELOG.md",
    ],
    docs: [
      "docs/contributing/release-readiness.md",
      "docs/contributing/release-process.md",
      "CONTRIBUTING.md",
    ],
    sources: [
      "scripts/check-release-readiness.mjs",
      "scripts/release-drift-guard.mjs",
    ],
    focusedChecks: [
      "node scripts/check-release-readiness.mjs",
      "npm run release:drift",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "release",
    tasks: ["release", "publication", "compatibility"],
    pathPrefixes: [
      ".github/workflows/publish-",
      ".github/workflows/release-",
      "gradle.properties",
    ],
    docs: [
      "docs/contributing/release-readiness.md",
      "docs/contributing/release-process.md",
      "CONTRIBUTING.md",
    ],
    sources: [
      "scripts/check-release-readiness.mjs",
      "scripts/release-reality-check.mjs",
      "scripts/release-gate.mjs",
    ],
    focusedChecks: [
      "npm run release:reality",
      "npm run evidence:release",
    ],
    broadGate: "npm run release:check",
    connectedProof: true,
  },
  {
    id: "architecture",
    tasks: ["architecture", "boundary", "adr"],
    pathPrefixes: [
      "docs/architecture/",
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/",
    ],
    docs: [
      "docs/architecture/adr/README.md",
      "docs/architecture/agent-code-compass.md",
    ],
    sources: [
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/ModuleBoundaryTest.kt",
      "fixthis-mcp/src/test/kotlin/io/github/beyondwin/fixthis/mcp/architecture/SessionPackageBoundaryTest.kt",
    ],
    focusedChecks: [
      "./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon",
      "git diff --check",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "agent-guidance",
    tasks: ["agent", "agent-guidance", "agent-kit"],
    pathPrefixes: [
      ".agents/",
      ".codex-plugin/",
      "AGENTS.md",
      "fixthis-compose-core/AGENTS.md",
      "fixthis-compose-sidekick/AGENTS.md",
      "fixthis-mcp/AGENTS.md",
      "scripts/AGENTS.md",
      "scripts/agent-",
    ],
    docs: [
      "AGENTS.md",
      "docs/guides/project-map.md",
      "docs/architecture/agent-code-compass.md",
    ],
    sources: [
      "scripts/agent-route-registry.mjs",
      "scripts/agent-task-router.mjs",
      "scripts/agent-guidance-contract-test.mjs",
    ],
    focusedChecks: [
      "npm run agent:route:test",
      "npm run docs:agent-guidance:test",
      "npm run plugin:contract:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
  {
    id: "docs",
    tasks: ["docs", "documentation"],
    pathPrefixes: [
      "docs/",
      "README.md",
      "CLAUDE.md",
      "CONTRIBUTING.md",
      ".codex-plugin/",
    ],
    docs: [
      "docs/index.md",
      "docs/guides/project-map.md",
    ],
    sources: [
      "scripts/check-doc-consistency.mjs",
      "scripts/agent-bootstrap-contract-test.mjs",
      "scripts/fixthis-plugin-contract-test.mjs",
    ],
    focusedChecks: [
      "node scripts/check-doc-consistency.mjs",
      "npm run docs:agent-bootstrap:test",
      "npm run plugin:contract:test",
    ],
    broadGate: SAFE_FALLBACK_COMMAND,
    connectedProof: false,
  },
]);

export function orderedUnique(values) {
  return [...new Set(values)];
}

function routeMatchesFile(route, file) {
  const guidancePath = file === "AGENTS.md" || file.endsWith("/AGENTS.md");
  if (guidancePath) {
    return route.id === "agent-guidance" && route.pathPrefixes.some(
      (prefix) => file.startsWith(prefix),
    );
  }
  return route.pathPrefixes.some((prefix) => file.startsWith(prefix));
}

export function selectRoutes({ task = null, changedFiles = [] } = {}) {
  const normalizedTask = task?.trim().toLowerCase() || null;
  const normalizedFiles = orderedUnique(
    changedFiles.map((file) => file.trim()).filter(Boolean),
  );
  return ROUTES.filter((route) =>
    (normalizedTask && route.tasks.includes(normalizedTask)) ||
    normalizedFiles.some((file) => routeMatchesFile(route, file)),
  );
}
