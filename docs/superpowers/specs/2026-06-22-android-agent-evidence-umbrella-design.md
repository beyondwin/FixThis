# Android Agent Evidence Umbrella - Design

**Date:** 2026-06-22
**Status:** Approved (design); implementation plan pending
**Topic:** Source-aware handoff guidance, optional runtime evidence attachments,
and Codex plugin packaging for Android agent workflows

## Summary

FixThis already gives agents a stronger Android UI handoff than a screenshot:
Compose semantics, screenshot bounds, source candidates, target confidence,
edit-surface hints, screen fingerprint checks, and a local MCP claim/resolve
queue. Recent Codex mobile and native-app workflows show a useful product
direction: agents should own more of the reproduce, inspect, gather evidence,
edit, and verify loop without pretending that weak UI evidence is exact source
ownership.

This umbrella strengthens that loop in three tracks:

- **Track A: Source-Aware Handoff Guidance.**
- **Track B: Runtime Evidence Attachments.**
- **Track C: Codex Plugin Packaging.**

The tracks are independently shippable, but they defend one product promise:
FixThis should let a coding agent start Android UI work from local, debug-only
evidence that is specific enough to be useful and honest enough to be trusted.

## Approved Direction

Use one umbrella spec and one umbrella implementation plan. Keep all work
additive and evidence-first:

1. Make the compact handoff clearer about how an agent should verify each item.
2. Attach optional runtime evidence summaries to feedback items without making
   logs or traces required for normal annotation.
3. Package the existing FixThis install, feedback, evidence, and release-smoke
   workflows as Codex plugin skills.

This direction avoids competing with generic emulator QA plugins on raw device
automation. FixThis should keep its sharper position: source-aware Compose
handoff plus local MCP lifecycle for agent work.

## Goals

- Make each handoff item say whether the agent should inspect source first,
  corroborate screenshot and semantics, treat source paths as hints, or verify
  manually.
- Preserve existing `targetConfidence`, `targetAction`, `sourceCandidates`, and
  `editSurface` output while adding a higher-level agent-facing verification
  summary.
- Let a user or agent attach scoped runtime evidence to a feedback item:
  logcat window, frame or jank summary, memory snapshot summary, and optional
  local trace artifact references.
- Keep large logs, trace files, and screenshots out of compact Markdown. The
  handoff should carry summaries, paths, and next actions, not raw blobs.
- Package FixThis workflows for Codex discovery: install-agent, feedback loop,
  Android evidence capture, and release smoke.
- Preserve all persisted MCP/session JSON compatibility contracts.
- Keep non-strict connected Android evidence deferrable with exact reasons, and
  keep strict connected evidence failing when prerequisites are absent.

## Non-Goals

- No release-build support. FixThis remains debug-only.
- No XML/View exact source targeting and no WebView DOM inspection.
- No automatic app code edits during FixThis evidence capture.
- No cloud upload, external AI API call, or remote artifact storage.
- No bridge-protocol breaking change.
- No persisted field rename for `items`, `screens`, `itemId`, `screenId`,
  `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- No committed `.fixthis/`, `graphify-out/`, Android build output,
  screenshots, generated reports, log captures, traces, or local fixture
  workspaces.
- No requirement that all Android apps use runtime evidence attachments before
  using Copy Prompt or Save to MCP.

## Architecture Context

FixThis currently has the right layering for this umbrella:

- `:fixthis-compose-core` owns pure Kotlin models, target evidence,
  confidence, source interpretation, and target reliability. It must stay free
  of MCP, CLI, Android UI, and `.fixthis/` path dependencies.
- `:fixthis-compose-sidekick` owns the debug-only Android runtime bridge and
  Compose semantics capture.
- `:fixthis-gradle-plugin` owns source-index generation and debug dependency
  wiring.
- `:fixthis-cli` owns install-agent, doctor, run, local Android setup, and
  agent config writing.
- `:fixthis-mcp` owns feedback sessions, local HTTP console routes, compact and
  precise Markdown rendering, MCP tools, local persistence, and the browser
  feedback console.

The existing compact handoff already renders:

- package and optional source root;
- screenshot path, viewport, activity, and screen blocks;
- target summary, UI bounds, overlap and duplicate risks;
- edit-surface candidates;
- source candidates with confidence, owner, margin, matched terms, and stale
  markers;
- target confidence, target action, and warnings;
- `agent_protocol` with claim and resolve instructions.

This umbrella should extend those surfaces rather than replacing them.

## Track A: Source-Aware Handoff Guidance

### Problem

FixThis has the low-level evidence tokens an agent needs, but an agent still has
to infer the verification posture from several lines: `targetConfidence`,
`targetAction`, warnings, source-candidate confidence, stale markers,
`editSurface`, overlap groups, and duplicate markers. That is parseable, but it
is not as explicit as the emerging Codex native-app pattern: inspect the UI
tree, prefer stable identifiers, gather evidence, then edit only after the
target is clear.

The handoff needs one concise item-level summary that answers:

- What should the agent trust first?
- What must the agent verify before editing?
- Which evidence made this item risky or strong?
- Which command or MCP action should happen before and after work?

### Design

Add an optional item-level verification summary derived from existing evidence.
The summary must be additive and generated at render time where possible.
Persist it only if doing so materially helps JSON consumers; otherwise prefer a
renderer-only projection.

Proposed compact Markdown shape:

```text
  verify: source-first  because=strong-target,strong-source,clear-margin
  verifyBeforeEdit: inspect-source,compare-screenshot
```

Allowed `verify` modes:

| Mode | Meaning |
| --- | --- |
| `source-first` | Strong target evidence and strong source candidate. Inspect the source candidate first, then compare with screenshot and target. |
| `corroborate` | Useful source and UI evidence, but the agent should cross-check screenshot, semantics, and code before editing. |
| `hint-only` | Source paths are useful search hints, not ownership claims. The agent should inspect nearby code and validate manually. |
| `manual` | Evidence is missing, stale, visual-only, sensitive, or interop-heavy enough that manual verification is required before editing. |

Allowed `verifyBeforeEdit` actions:

- `claim-feedback`
- `inspect-source`
- `compare-screenshot`
- `check-target-summary`
- `review-edit-surface`
- `check-logcat`
- `check-frame-summary`
- `check-memory-summary`
- `verify-manually`

The renderer derives these values from existing state:

- `targetReliability.confidence`
- `targetReliability.warnings`
- top source-candidate confidence, margin, stale marker, and risk flags
- target kind: node vs visual area
- overlap and duplicate marker detection
- edit-surface role
- runtime evidence attachments when present

### Data Model

Use one DTO if JSON exposure is needed:

```kotlin
@Serializable
data class AgentVerificationGuidance(
    val mode: AgentVerificationMode,
    val reasons: List<AgentVerificationReason> = emptyList(),
    val beforeEdit: List<AgentVerificationAction> = emptyList(),
)
```

If persisted, add it to `AnnotationDto` as:

```kotlin
val agentVerification: AgentVerificationGuidance? = null
```

This field is optional and additive. Older session JSON remains readable. If the
implementation can compute the same guidance deterministically from existing
fields, keep it renderer-only first and document that JSON clients should read
the rendered Markdown or derive their own posture from existing fields.

### UI And Docs

The browser console does not need a new primary control for Track A. At most,
saved annotation detail can show a small "Verification" row using the same
mode tokens. The compact handoff and `docs/reference/feedback-console-contract.md`
are the canonical surfaces.

### Acceptance Criteria

- Compact handoff includes a concise verification posture per item.
- Existing `targetConfidence` and `targetAction` lines remain unchanged.
- Items with stale source candidates, visual-area targets, forced fingerprint
  mismatch, sensitive redaction, or interop warning never render
  `verify: source-first`.
- Items with high target confidence, high source confidence, strong evidence,
  and clear margin can render `verify: source-first`.
- Full JSON compatibility is preserved.

## Track B: Runtime Evidence Attachments

### Problem

Generic Android emulator QA can collect logcat, UI tree, screenshots, frame
data, performance traces, and memory evidence. FixThis already owns the better
source-aware UI handoff, but it does not yet let an annotation carry scoped
runtime evidence. That means a visual bug report can identify a composable, but
cannot attach the nearby logcat window, jank summary, memory signal, or trace
reference that explains why the UI is wrong.

The first version should attach compact evidence summaries, not create a full
profiling product.

### Design

Add optional runtime evidence attachments to feedback sessions and items.
Evidence capture is explicitly user or agent initiated; normal annotation
remains fast and unchanged.

Evidence types:

| Type | Purpose | Stored content |
| --- | --- | --- |
| `logcat_window` | Show errors, warnings, or app logs around the annotation time. | Time range, filter metadata, summary, local artifact path. |
| `frame_summary` | Show visible jank or frame timing around an interaction. | Small stats summary from `dumpsys gfxinfo` or equivalent. |
| `memory_summary` | Show memory growth or high memory state. | `dumpsys meminfo` summary and optional heap artifact path. |
| `trace_artifact` | Point to a Perfetto/Simpleperf artifact captured outside compact handoff. | Artifact path, capture command, summary, retention warning. |

Proposed DTO:

```kotlin
@Serializable
data class RuntimeEvidenceAttachment(
    val evidenceId: String,
    val type: RuntimeEvidenceType,
    val capturedAtEpochMillis: Long,
    val deviceSerial: String? = null,
    val packageName: String,
    val timeRangeEpochMillis: EvidenceTimeRange? = null,
    val summary: String,
    val artifactPath: String? = null,
    val captureCommand: String? = null,
    val warnings: List<RuntimeEvidenceWarning> = emptyList(),
)
```

Attach evidence through IDs instead of embedding large payloads in every item:

```kotlin
val runtimeEvidenceIds: List<String> = emptyList()
```

A session-level collection holds the attachment objects:

```kotlin
val runtimeEvidence: List<RuntimeEvidenceAttachment> = emptyList()
```

Both fields are optional and additive. Runtime evidence uses the session-level
collection plus item-level IDs from the first implementation, so large
attachments are not duplicated across items and later session tools can list
evidence independently from a single annotation.

### Capture Surfaces

Add narrow MCP and local HTTP actions rather than broad profiler UI:

- `fixthis_capture_runtime_evidence`
  - arguments: `sessionId`, `itemId?`, `type`, `durationMillis?`, `filter?`
  - returns: attachment JSON and a compact summary
- Console saved-annotation action: "Attach evidence"
  - first version can expose only logcat and frame summary if trace capture is
    too heavy for the UI.
- CLI helper or script wrappers can provide stricter local release proof.

The implementation may defer trace capture behind a script first:

- `scripts/runtime-evidence-smoke.mjs`
- `scripts/android-frame-summary.mjs`
- `scripts/android-logcat-window.mjs`

### Compact Handoff Rendering

Compact Markdown should render summaries only:

```text
  runtimeEvidence:
    - logcat_window warning 12s around capture -> .fixthis/.../logcat.txt
      summary: 2 RuntimeException lines from com.example.MainActivity
    - frame_summary -> .fixthis/.../gfxinfo.json
      summary: 6 slow frames, 1 frozen frame candidate
```

The renderer must cap summary length and number of attachments per item. Raw
log lines are not emitted by default. Precise or full detail mode can include
more attachment metadata, but still should not dump long logs.

### Privacy And Retention

Runtime evidence can contain sensitive app logs or user-visible text. The
feature must:

- store artifacts under ignored local directories;
- never commit artifacts;
- mark compact handoff summaries as local paths;
- warn when logcat capture may contain sensitive values;
- let users delete evidence with the session or item;
- avoid copying raw logs to clipboard unless explicitly requested.

### Failure Handling

- Missing `adb`, missing device, locked device, missing package, or unsupported
  evidence type returns a structured deferred or failed result.
- Non-strict evidence reports can mark runtime capture as `deferred`.
- Strict release evidence fails when a required connected capture cannot run.
- Capture timeout leaves the feedback item unchanged and reports the failed
  evidence attempt separately.

### Acceptance Criteria

- A feedback item can reference at least one runtime evidence attachment.
- Compact handoff renders a bounded summary and local artifact path.
- Existing Copy Prompt and Save to MCP still work when no runtime evidence is
  present.
- Evidence capture failures do not corrupt feedback sessions or draft state.
- Runtime evidence artifacts stay out of git.

## Track C: Codex Plugin Packaging

### Problem

FixThis has agent-first install docs and MCP tools, but Codex users still need
to know which command to run and which workflow to follow. Codex's plugin model
is a good fit for packaging repeatable workflows: install FixThis, open the
feedback console, claim and resolve work, capture Android evidence, and run a
strict local smoke.

The plugin should package workflow knowledge, not duplicate the FixThis CLI or
MCP server.

### Design

Create a Codex plugin bundle that installs skills and, where supported,
declares the FixThis MCP server setup path. The plugin remains a wrapper around
published FixThis artifacts:

- Homebrew: `brew install beyondwin/tools/fixthis`
- npm: `npm install -g @beyondwin/fixthis`
- GitHub Release install script
- project-local `fixthis install-agent`
- existing `fixthis-mcp` server

Proposed skills:

| Skill | Trigger | Responsibility |
| --- | --- | --- |
| `fixthis-install-agent` | User asks to install or bootstrap FixThis in an Android app. | Run install-agent, doctor JSON, explain restart and MCP setup. |
| `fixthis-feedback-loop` | User asks to handle FixThis feedback. | Open console if needed, read queue, claim, edit, verify, resolve. |
| `fixthis-android-evidence` | User asks to attach logs, performance, or runtime proof. | Capture scoped runtime evidence and summarize it for handoff. |
| `fixthis-release-smoke` | Maintainer asks to validate release readiness. | Run strict or non-strict smoke commands and report pass/deferred/fail. |

The plugin should include conservative instructions:

- debug builds only;
- Jetpack Compose only;
- no release configuration;
- no `.fixthis/` commit;
- prefer `fixthis doctor --json` as readiness source of truth;
- use MCP claim/resolve when available;
- use Copy Prompt for chat-style agents;
- separate strict connected Android proof from non-strict deferred evidence.

### Plugin Boundaries

The plugin does not:

- implement Android capture itself;
- vendor the CLI distribution;
- bypass Codex approval, sandbox, or MCP setup;
- promise release-build support;
- claim XML/View or WebView ownership.

It may include templates for prompts, docs snippets, and verification scripts.

### Acceptance Criteria

- The plugin manifest names FixThis clearly and exposes the four workflow
  skills.
- Skill docs point to the canonical repository docs instead of restating every
  MCP schema detail.
- The install skill can guide a Codex session from a clean Android app repo to
  `fixthis doctor --json`.
- The feedback-loop skill requires `fixthis_claim_feedback` before code edits
  when MCP queue items are available.
- The release-smoke skill preserves strict vs non-strict evidence semantics.

## Data Flow

### Normal Handoff

```text
Compose debug app
-> sidekick bridge capture
-> feedback console annotation
-> targetEvidence/sourceCandidates/targetReliability/editSurface
-> verification guidance projection
-> Copy Prompt or Save to MCP
-> agent claim
-> code inspection and verification
-> resolve feedback
```

### Runtime Evidence Handoff

```text
feedback item
-> user or agent requests evidence capture
-> MCP/CLI captures bounded Android evidence
-> artifact saved under ignored local storage
-> attachment summary stored in session
-> compact handoff renders summary and local path
-> agent uses evidence during verification
```

### Codex Plugin Flow

```text
Codex plugin skill
-> published FixThis install path
-> fixthis install-agent / doctor
-> MCP server restart or setup instructions
-> feedback loop skill reads and claims work
-> optional Android evidence skill
-> release-smoke skill for maintainer proof
```

## Compatibility

All persisted schema changes must be optional and additive. Existing sessions
without new fields must read and render exactly as before.

Compatibility contracts:

- Do not rename `items`, `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, `sourceCandidates`, or existing handoff batch fields.
- Do not change `Copy Prompt` or `Save to MCP` semantics.
- Do not make runtime evidence required for handoff.
- Do not add MCP or CLI dependencies to `:fixthis-compose-core`.
- Do not change bridge protocol unless the implementation plan includes the
  coordinated bridge-protocol checklist.

## Security And Privacy

- Runtime evidence is local-first and stored only on the developer machine.
- Logcat and trace summaries may include sensitive data; compact handoff should
  prefer summaries and local paths over raw content.
- Browser console mutation routes keep localhost and token protections.
- The plugin must not instruct agents to upload `.fixthis/` artifacts.
- The plugin must not recommend release-build instrumentation.

## Testing Strategy

### Unit And Contract Tests

- Kotlin tests for verification-guidance derivation.
- Compact handoff renderer tests for each verify mode.
- JSON serialization tests for optional runtime evidence fields.
- Console route tests for evidence attachment success and failure.
- MCP tool tests for runtime evidence capture argument validation.
- Plugin manifest and skill contract validation.

### Runtime And Script Tests

- Non-connected tests for schema migration and renderer behavior.
- Connected smoke for one logcat attachment on the sample app.
- Connected smoke for one frame summary when Android SDK and emulator are ready.
- Non-strict evidence runner output must mark missing Android prerequisites as
  `deferred` with exact reasons.
- Strict connected profile must fail when required evidence capture cannot run.

### Regression Checks

- Existing handoff fixture tests continue to pass with no runtime evidence.
- Existing compact handoff grammar remains parseable.
- Existing MCP queue lifecycle remains claim/resolve compatible.
- `git diff --check`.
- `graphify update .` after code changes in implementation.

## Rollout Plan

### Phase 0: Baseline And Contract Inventory

Inventory existing handoff grammar, DTOs, MCP tools, console routes, ignored
artifact paths, and release evidence scripts. No production behavior changes.

### Phase 1: Track A

Implement verification guidance as a renderer projection or optional DTO. Add
compact handoff lines and contract docs. Keep UI changes minimal.

### Phase 2: Track B Minimal Evidence

Add runtime evidence models and one narrow capture path for logcat window.
Render bounded summaries in compact handoff. Prove no-evidence sessions still
render as before.

### Phase 3: Track B Expanded Evidence

Add frame summary and memory summary. Add trace artifact references only if
local capture and retention behavior are reliable enough.

### Phase 4: Track C Plugin

Create the Codex plugin package with four skills. Validate manifest, skill
contracts, install guidance, and MCP workflow instructions.

### Phase 5: Evidence Aggregation And Docs

Wire runtime evidence and plugin validation into release-readiness docs and
non-strict/strict evidence profiles. Update README and reference docs only with
claims backed by evidence.

## Open Risks

- **Runtime evidence scope creep:** Perfetto and Simpleperf can become a full
  profiling product. Keep v1 focused on summaries and artifact references.
- **Log privacy:** Logcat may include secrets. Default compact handoff must not
  dump raw logs.
- **Plugin drift:** Plugin instructions can drift from CLI docs. Reference
  canonical docs and add contract checks for command snippets.
- **Schema growth:** Additive fields are safe, but too many item-level fields
  can make JSON noisy. Prefer session-level evidence references where practical.
- **Connected evidence flakiness:** Android emulator state is unstable. Preserve
  strict vs non-strict behavior and exact deferred reasons.

## Acceptance Criteria

- Track A, B, and C each have a bounded implementation path in the companion
  plan.
- Compact handoff gives explicit verification posture without removing existing
  target/source confidence lines.
- Runtime evidence attachments are optional, local, bounded, and ignored by git.
- Codex plugin packaging improves workflow discovery without duplicating the
  FixThis CLI or MCP implementation.
- Existing public guarantees remain true: debug-only, Jetpack Compose-only,
  local-first, MCP-compatible, and no release-build support.
