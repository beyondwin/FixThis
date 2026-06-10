# FixThis Fullstack Tooling Handover Guide Design

Date: 2026-06-10
Status: Proposed
Scope: one Korean long-form onboarding guide for junior fullstack/tooling
developers taking over FixThis maintenance.

Related work:

- [Project README](../../../README.md)
- [Documentation Index](../../index.md)
- [Architecture Overview](../../architecture/overview.md)
- [Decision Rationale](../../product/decision-rationale.md)
- [MCP Tools](../../reference/mcp-tools.md)
- [Feedback Console Contract](../../reference/feedback-console-contract.md)
- [Output Schema](../../reference/output-schema.md)
- [Bridge Protocol](../../reference/bridge-protocol.md)
- [Contributing Guide](../../../CONTRIBUTING.md)

## Summary

Create a single, detailed Korean handover guide at
`docs/guides/fullstack-tooling-handover.md`. The guide is for a junior
fullstack/tooling developer who may know only part of the stack: Android
Compose, Gradle, CLI tooling, MCP, browser JavaScript, or local persistence.

The document should not replace the existing README, architecture overview, or
reference contracts. It should be the first practical reading path that explains
why FixThis is shaped the way it is and where to look in code when maintaining
it. The guide should connect product constraints, technology choices, module
boundaries, real request flows, data storage, compatibility contracts, and
verification commands in one coherent handover narrative.

## Goals

- Explain FixThis's end-to-end runtime flow:
  `Compose debug app -> sidekick -> ADB bridge -> CLI/MCP -> browser console -> .fixthis handoff`.
- Explain the major technology choices with trade-offs, not just a list of
  dependencies.
- Help a junior developer find the right module and file before changing code.
- Make compatibility boundaries visible before implementation work starts.
- Document the practical maintenance workflow for CLI, MCP, console, sidekick,
  source matching, Gradle plugin, and docs/release changes.
- Keep the guide grounded in current code paths and maintained reference docs.
- Add a discoverable link from `docs/index.md`.

## Non-Goals

- No Kotlin, JavaScript, Gradle, CLI, MCP, or Android behavior changes.
- No new tests or production code.
- No replacement of existing reference documents.
- No migration of persisted session JSON fields.
- No bridge protocol change.
- No release-build support, non-Compose support, cloud service, or
  AccessibilityService scope expansion.
- No committed `.fixthis/`, `graphify-out/`, Android build output, screenshots,
  local fixture workspaces, or generated reports.

## Audience

The primary audience is a junior fullstack/tooling developer taking over
maintenance. The guide should assume the reader can read code and run commands
but may not already understand:

- how Jetpack Compose semantics become agent-readable context;
- why FixThis is debug-only and local-first;
- how desktop MCP tools reach an Android app through ADB;
- how a browser console can be owned by a stdio MCP server;
- why `.fixthis/feedback-sessions/` is local handoff state, not source code;
- why source candidates are ranked hints rather than compiler-accurate mappings.

Use Korean prose for explanations. Keep module names, commands, file paths,
class names, protocol names, JSON field names, and public tool names in their
source form so the reader can search for them directly.

## Proposed Location

Create:

```text
docs/guides/fullstack-tooling-handover.md
```

Update:

```text
docs/index.md
```

The link should live in the guide-oriented start area, near agent workflow,
Graphify, feedback console, and troubleshooting links. The document is a guide,
not an architecture contract, so `docs/guides/` is a better fit than
`docs/architecture/` or `docs/reference/`.

## Document Structure

### 1. How To Read This Guide

Explain the guide's purpose and relationship to canonical docs:

- `README.md` for product overview and quick start;
- `docs/architecture/overview.md` for current module/runtime architecture;
- `docs/product/decision-rationale.md` for maintained decisions;
- `docs/reference/*` for compatibility contracts;
- `CONTRIBUTING.md` for authoritative verification commands.

State that current code and reference contracts win over older design records
when documents disagree.

### 2. Product Boundary In One Page

Explain the four core constraints:

- debug-only;
- Jetpack Compose-only;
- local-first;
- MCP/browser-console-first.

This section should explicitly explain why FixThis does not run in release
builds, does not use an AccessibilityService, does not source-map arbitrary
View/WebView/Flutter/React Native targets in V1, and does not call an external
AI API.

### 3. End-To-End System Flow

Include a Mermaid diagram and a step-by-step explanation for:

```text
debug app starts
-> AndroidX Startup runs FixThisInitializer
-> FixThis.install registers lifecycle callbacks
-> BridgeRuntime starts BridgeServer
-> CLI/MCP reads the sidekick token with adb run-as
-> adb forward connects desktop tcp to Android local socket
-> bridge method inspects semantics / screenshots / source index / navigation
-> MCP console captures preview and annotations
-> Copy Prompt or Save to MCP persists local handoff state
-> agent reads, claims, and resolves feedback
```

The section should distinguish the Android app side from the desktop process.
The app does not host MCP or HTTP; the MCP server and console run on desktop.

### 4. Project Modules And Ownership

Cover each module in a consistent format:

```text
### <module>

- Responsibility:
- Must not depend on:
- First files to open:
- Important tests:
- Common change types:
```

Modules to cover:

- `:app` / `sample/`
- `:fixthis-compose-core`
- `:fixthis-compose-sidekick`
- `fixthis-gradle-plugin/`
- `:fixthis-cli`
- `:fixthis-mcp`

The guide should emphasize the boundary invariant that
`:fixthis-compose-core` has no dependency on MCP, CLI, Android UI surfaces, or
`.fixthis/` paths.

### 5. Technology Choices, Pros, Cons, And Maintenance Notes

Use this exact per-technology shape:

```text
### <technology>

- FixThis에서 하는 역할:
- 왜 이 기술을 선택했나:
- 장점:
- 단점/한계:
- 변경하거나 확장할 때 고려할 점:
- 관련 코드/문서:
```

Cover at least:

- Jetpack Compose semantics;
- `debugImplementation` and debuggable guards;
- AndroidX Startup;
- Android local socket and ADB forward;
- MCP stdio JSON-RPC;
- Kotlin/JVM and `kotlinx.serialization`;
- Clikt CLI;
- Gradle plugin and source index;
- plain browser JavaScript console;
- SSE with polling fallback;
- local file persistence and event log;
- JUnit, Robolectric, connected Android tests, and console JS tests;
- Spotless, detekt, release/trust gates.

The section should include operational costs and risks, not just benefits.

### 6. Real Logic Walkthroughs

Use this exact per-flow shape:

```text
### <flow>

- 사용자가 보는 동작:
- 시작 명령/도구:
- 핵심 코드 경로:
- 데이터가 이동하는 방식:
- 실패할 수 있는 지점:
- 수정할 때 먼저 볼 테스트:
```

Cover at least:

- external app setup with `fixthis install-agent`;
- readiness diagnosis with `fixthis doctor --json`;
- sample app launch with `fixthis run`;
- sidekick startup;
- bridge request handling;
- Compose screen inspection;
- source matching;
- feedback console startup;
- live preview and annotation freeze;
- `Copy Prompt` and `Save to MCP`;
- agent queue processing with read/claim/resolve;
- `fixthis_verify_ui_change`.

Use actual file paths such as:

- `fixthis-compose-sidekick/src/main/kotlin/.../FixThisInitializer.kt`
- `fixthis-compose-sidekick/src/main/kotlin/.../BridgeServer.kt`
- `fixthis-compose-core/src/main/kotlin/.../SourceMatcher.kt`
- `fixthis-cli/src/main/kotlin/.../commands/DoctorCommand.kt`
- `fixthis-mcp/src/main/kotlin/.../FeedbackSessionService.kt`
- `fixthis-mcp/src/main/console/`
- `fixthis-gradle-plugin/src/main/kotlin/.../FixThisGradlePlugin.kt`

### 7. Data And Storage Model

Explain app-private and project-local artifacts:

- `files/fixthis/session.json`;
- Android screenshot cache under app cache;
- `.fixthis/project.json`;
- `.fixthis/feedback-sessions/<session-id>/`;
- `.fixthis/feedback-sessions/<session-id>/events/`;
- `.fixthis/preview-cache/<session-id>/`;
- browser `localStorage["fixthis.workspace.<sessionId>.<workspaceId>"]`.

Explain that `.fixthis/feedback-sessions/<session-id>/session.json` is the
authoritative persisted session state, while
`.fixthis/feedback-sessions/index.json` is a derived cache. Also state that
`.fixthis/` is local-only and should not be committed.

### 8. Compatibility Contracts And Forbidden Moves

List the contracts a maintainer must not break accidentally:

- debug-only scope;
- Compose-only V1 scope;
- no committed `.fixthis/` or `graphify-out/`;
- persisted JSON field names including `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, and `sourceCandidates`;
- MCP tool names and queue semantics;
- bridge protocol compatibility and additive capability handling;
- source index as best-effort ranked hints;
- screenshot privacy and redaction limits;
- stdio JSON-RPC stdout/stderr rules;
- `:fixthis-compose-core` dependency boundary.

### 9. Change-Type Runbook

Give practical starting points and verification focus for:

- adding or changing a CLI command;
- changing an MCP tool;
- changing a console HTTP route;
- changing browser console behavior;
- changing sidekick bridge behavior;
- changing source matching or target reliability;
- changing the Gradle plugin/source index;
- changing docs or release claims.

The runbook should direct readers to focused source paths and tests rather than
duplicating all of `CONTRIBUTING.md`.

### 10. Verification Commands And Failure Reading

Summarize verification levels:

- fast docs-only checks;
- focused Kotlin/JVM tests;
- sidekick unit/Robolectric tests;
- console JS tests;
- connected Android tests;
- strict evidence/trust checks when release or runtime confidence is affected.

For this docs-only work, the expected verification is:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

The guide itself should refer to `CONTRIBUTING.md` as the command source of
truth.

### 11. First Three Days Onboarding Route

Give a concrete route:

- Day 1: read README, this handover guide, architecture overview, and run the
  sample quick start.
- Day 2: trace console feedback from preview capture through Save to MCP and
  read/claim/resolve.
- Day 3: make a tiny docs or test-only change, run focused checks, and inspect
  how source matching/target reliability affect handoff confidence.

This route should be practical rather than aspirational.

## Evidence Requirements

Before writing the guide, verify the current source/doc anchors for:

- module map;
- CLI command names;
- MCP tool names;
- persisted field names;
- bridge method/capability names;
- `.fixthis/` storage paths;
- source index generation and source matching;
- console SSE/polling behavior;
- canonical verification commands.

Graphify may be used for navigation, but actual claims must be checked against
source files and maintained docs before publication.

## Documentation Quality Bar

The guide is complete when:

- a junior fullstack/tooling developer can explain FixThis's runtime shape
  without reading every source file;
- each major technology entry includes benefits, limitations, and maintenance
  considerations;
- each real-flow walkthrough points to concrete files;
- compatibility contracts are visible before change runbooks;
- the document links to canonical reference docs instead of copying contract
  details in a way that will drift silently;
- `docs/index.md` exposes the guide.

## Validation Plan

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

If the final guide adds links or command examples that are not covered by the
doc consistency script, add targeted `rg` checks while reviewing. Ignore dirty
`graphify-out/` artifacts after `graphify update .`; they are generated and not
part of the committed docs.

## Open Decisions

The user has approved these decisions:

- target reader: junior fullstack/tooling developer;
- output shape: one long guide;
- language: Korean prose with source identifiers preserved;
- primary location: `docs/guides/fullstack-tooling-handover.md`;
- approach: flow-based handover guide rather than isolated technology catalog
  or scenario-only runbook.

No unresolved product or implementation decisions remain for the guide design.
