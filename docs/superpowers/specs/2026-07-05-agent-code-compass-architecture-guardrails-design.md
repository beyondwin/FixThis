# Agent Code Compass And Architecture Guardrails Design

Date: 2026-07-05
Status: Approved design - pending implementation plan

## Goal

Improve FixThis code quality and code structure for human maintainers and AI
coding agents by making the existing architecture easier to navigate and harder
to violate accidentally.

This is not a product-feature pass. The outcome is a clearer modification path:
an agent should be able to identify the right docs, source files, compatibility
contracts, package boundaries, and focused verification commands before editing
code. If the agent crosses a known boundary, the architecture tests should fail
with enough context to correct the mistake.

## Current Context

The repository already has a strong architecture foundation:

- `AGENTS.md` defines the agent read order and repository constraints.
- `docs/index.md` routes readers to maintained docs and reference contracts.
- `docs/guides/project-map.md` maps modules, work routes, first files, and
  focused checks.
- `docs/architecture/overview.md` explains runtime flow and module boundaries.
- `docs/architecture/adr/0001-use-clean-architecture-layering.md` and
  `docs/architecture/adr/0002-domain-models-live-in-compose-core.md` establish
  that `:fixthis-compose-core` owns pure domain rules while outer modules own
  adapters, DTOs, persistence, UI, bridge, CLI, and MCP concerns.
- `docs/architecture/adr/0008-session-package-decomposition.md` records the
  `fixthis-mcp/session` package decomposition and its remaining exceptions.
- `ModuleBoundaryTest`, `SessionPackageBoundaryTest`, and
  `ArchitectureHotspotBudgetTest` already enforce important architecture
  constraints.

The remaining gap is not a missing architecture direction. The gap is that the
direction is spread across several documents and tests. New agents can still
lose time in historical planning files, treat current exceptions as a normal
dependency pattern, or respond to a boundary-test failure without understanding
which rule they crossed.

## Scope

In scope:

- Add a thin `docs/architecture/agent-code-compass.md` navigation document for
  agent-facing code changes.
- Link the compass from `docs/guides/project-map.md` so the existing read order
  remains intact.
- Clarify ADR-0008's remaining `session` dependency exceptions and the path to
  retire them later.
- Improve architecture-test rule expression and failure messages without
  changing the external FixThis behavior.
- Preserve the current allowed ADR-0008 exceptions while making new exceptions
  explicit and difficult to add accidentally.
- Include only small behavior-preserving cleanup when it directly supports the
  guardrail work.

Out of scope:

- No MCP tool schema changes.
- No persisted feedback-session JSON field renames or migrations.
- No bridge protocol changes.
- No CLI command or flag behavior changes.
- No feedback console workflow or compact handoff format changes.
- No source-matching score policy or target-reliability policy changes.
- No broad package rewrite, Gradle module split, or product runtime behavior
  change.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  local fixture workspaces, or generated reports.

## Architecture

The improvement adds a navigation and governance layer around the existing
architecture. It does not introduce a new source of truth.

```text
AGENTS.md
  -> docs/index.md
  -> docs/guides/project-map.md
  -> docs/architecture/agent-code-compass.md
  -> task-specific docs/reference/*, ADRs, source, and tests
```

`agent-code-compass.md` is a router. Stable contracts remain in
`docs/reference/*`, architecture decisions remain in ADRs, and implementation
truth remains in Kotlin, JavaScript, Gradle, shell, and Markdown sources.

The architecture tests remain the executable governance layer:

- `ModuleBoundaryTest` enforces cross-module dependency direction.
- `SessionPackageBoundaryTest` enforces `fixthis-mcp/session` package direction.
- `ArchitectureHotspotBudgetTest` enforces known large-file budgets.

The tests should explain violations in the language of the architecture docs:
what boundary failed, why the direction is disallowed, which ADR or guide
explains it, and what action is expected.

## Components

### 1. Agent Code Compass

Create `docs/architecture/agent-code-compass.md`.

The compass should answer the questions an agent needs before editing:

- What task type am I doing?
- Which maintained docs should I read first?
- Which source files are the first entry points?
- Which boundaries must not be crossed?
- Which focused tests should I run before broader verification?

Initial task routes should cover:

- Source matching and target reliability.
- MCP session lifecycle, persistence, handoff, and feedback queue work.
- Browser console UI and state sync.
- CLI setup and external app installation.
- Android bridge and sidekick runtime.
- Gradle plugin source-index generation.
- Release readiness and evidence scripts.
- Architecture or guardrail changes.

The compass should explicitly warn that `docs/superpowers/*`, `docs/specs/*`,
and `docs/plans/*` are historical planning context unless a maintained guide,
reference page, ADR, or source file points to them.

### 2. Project Map Linkage

Update `docs/guides/project-map.md` to link to the compass from the existing
architecture/navigation path. The project map should remain the compact module
map and work-route table. The compass should provide the more execution-oriented
agent route.

The project map should add or refine a work route for architecture and
guardrail changes, pointing to:

- `docs/architecture/agent-code-compass.md`
- `docs/architecture/adr/README.md`
- `ModuleBoundaryTest`
- `SessionPackageBoundaryTest`
- `ArchitectureHotspotBudgetTest`

### 3. ADR-0008 Exception Register

ADR-0008 already lists exceptions E1-E4. The implementation should make the
exception register more actionable:

- Name the allowed import direction for each exception.
- State why the exception is currently tolerated.
- State the cleanup that would retire the exception.
- State that new exceptions require an ADR update and a matching test-rule
  update.

The current intent remains unchanged: `session` sub-packages are responsibility
boundaries, not optional naming folders. Exceptions are tolerated because they
reflect real pre-existing shared model or orchestration coupling, not because
new cross-package dependencies are acceptable by default.

### 4. Architecture Test Improvements

Keep the tests lightweight and repository-local. Do not add a new architecture
framework dependency.

`SessionPackageBoundaryTest` should move toward a table-driven shape so the
rules are readable as data. A violation should name:

- the source package,
- the forbidden package or import,
- the related ADR-0008 rule or exception,
- the expected fix.

`ModuleBoundaryTest` should make cross-module failures clear. For example, a
`compose-core` import violation should tell the reader that pure domain code
cannot depend on Android, MCP, CLI, Gradle plugin, or sidekick surfaces.

`ArchitectureHotspotBudgetTest` should keep the ratchet behavior but make the
failure more instructive. The message should explain that budgets are ratchets:
when a file shrinks, lower the budget in the same commit; when a file grows,
prefer splitting or documenting a justified architecture decision instead of
raising a budget silently.

### 5. Small Cleanup

Small cleanup is allowed only when it is behavior-preserving and directly
supports the architecture-governance goal.

An example candidate is simplifying duplicate construction paths where the
callee already accepts nullable collaborators, such as the `FeedbackSessionStore`
delegate construction around `compactionFailureSink`. This kind of cleanup is
acceptable because it reduces an unnecessary branch without changing session
storage, event-log, or compaction semantics.

Large code moves, score-policy changes, DTO migrations, route rewrites, and
package reshuffles are not part of this pass.

## Data Flow

### Agent Navigation Flow

```text
agent receives a code task
  -> reads AGENTS.md and docs/index.md
  -> opens docs/guides/project-map.md
  -> follows docs/architecture/agent-code-compass.md for the task route
  -> opens task-specific reference docs and first source files
  -> edits within the documented boundary
  -> runs focused tests from the compass/project map
```

### Guardrail Failure Flow

```text
agent introduces a forbidden import or grows a guarded hotspot
  -> focused :fixthis-mcp:test architecture test fails
  -> failure message names the violated rule and relevant ADR/doc
  -> agent removes the dependency, splits the file, or writes an explicit ADR
     update for a genuinely justified boundary change
```

### Exception Retirement Flow

```text
future cleanup moves shared models or helpers to a lower package
  -> ADR-0008 exception text is updated or removed
  -> SessionPackageBoundaryTest rule is tightened
  -> focused architecture test proves the exception is no longer allowed
```

## Error Handling And Risks

- **Compass drift.** The compass may become stale if it duplicates contracts.
  Mitigation: keep it as a router and link to reference docs, ADRs, source, and
  tests instead of restating detailed schemas or CLI flags.
- **Over-strict architecture tests.** A rule can block a legitimate change.
  Mitigation: preserve current ADR-0008 exceptions and require a matching ADR
  update before adding new exceptions.
- **Vague failure messages.** A test can fail without teaching the agent what
  to do next. Mitigation: include rule, rationale, doc pointer, and expected
  correction in architecture-test failure output.
- **Message noise.** Failure messages can become too long. Mitigation: keep the
  happy path quiet and put detailed guidance only in assertion failure output.
- **Cleanup regression.** Even small code cleanup can alter behavior. Mitigation:
  keep cleanup isolated, behavior-preserving, and covered by `:fixthis-mcp:test`.

## Testing

Focused verification for the implementation:

```bash
./gradlew :fixthis-mcp:test --tests '*architecture*' --no-daemon
./gradlew :fixthis-mcp:test --no-daemon
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

If the implementation changes docs that mention CLI commands or flags, also run:

```bash
bash scripts/check-docs-cli-surface.sh
```

Android connected tests are not required for this pass unless the eventual
implementation unexpectedly changes sidekick runtime or sample-app behavior.

## Completion Criteria

- `docs/architecture/agent-code-compass.md` exists and routes common FixThis
  code-change tasks to maintained docs, first source files, forbidden
  boundaries, and focused checks.
- `docs/guides/project-map.md` links to the compass and includes a route for
  architecture or guardrail changes.
- ADR-0008 clearly describes current exceptions, allowed directions, retirement
  paths, and the rule for adding future exceptions.
- Architecture tests produce actionable failure messages for module-boundary,
  session-package, and hotspot-budget violations.
- Existing external contracts remain unchanged: CLI, MCP tools, persisted JSON,
  bridge protocol, console behavior, compact handoff, source matching, and
  target reliability.
- Verification commands pass, with generated local artifacts left uncommitted.
