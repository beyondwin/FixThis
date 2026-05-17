# v0.5 Trustworthy Onboarding Design

Date: 2026-05-17
Status: Ready for user review
Scope: README-first agent bootstrap, first-run readiness, external-project
onboarding, console handoff confidence, and release verification gates

## Summary

v0.5 should make FixThis trustworthy for a first-time external-project user.
The release should optimize for one claim:

> A user can paste the FixThis README into Claude Code or Codex, ask the agent
> to install FixThis in an external Jetpack Compose debug app, and get from
> setup to the first saved agent handoff with clear recovery guidance if any
> step fails.

This is not a broad feature expansion release. v0.5 connects existing product
surfaces into one verified onboarding program:

1. README-first agent bootstrap.
2. Shared first-run readiness vocabulary across CLI, doctor, console, and
   agent setup files.
3. A golden path from external project setup to `fixthis_read_feedback`.
4. Release gates that prove the onboarding claim before tagging.

The design keeps FixThis V1 intentionally narrow: Jetpack Compose debug builds,
local ADB and localhost transport, local MCP or Copy Prompt handoff, and
best-effort source candidates. It does not add production runtime support,
cloud sync, automatic code edits, AndroidView/WebView exact mapping, or a large
console redesign.

## Product Goal

FixThis already has public install paths, a browser console, MCP tools,
artifact packaging, release checks, and docs. The remaining product gap is that
the first external-project success path is spread across several surfaces.
Humans and agents can still hit unclear transitions:

- Which command should Claude Code or Codex run first?
- Did setup modify the Gradle project, MCP config, both, or neither?
- Should the agent restart after config writes?
- Is the app unsupported, not launched, blocked by device state, or simply not
  installed yet?
- Does the console failure require a user action, a CLI command, or a rebuild?
- Which checks prove that the README onboarding instructions still match the
  shipped CLI?

v0.5 makes these transitions explicit. The user-facing improvement is a faster
and more recoverable first successful handoff. The engineering improvement is
that the onboarding promise becomes a testable release contract.

## Success Criteria

- README contains a concise Claude Code / Codex agent bootstrap block that can
  be pasted into an agent chat with no additional context.
- The README-first path and `docs/getting-started/agent-install-snippet.md`
  agree on command order, expected outputs, restart guidance, and recovery
  behavior.
- `fixthis install-agent --project-dir . --target all` produces enough
  machine-readable and human-readable setup output for an agent to continue or
  recover.
- `fixthis doctor --project-dir . --json` is the canonical agent-readable
  readiness check after setup.
- CLI, `.fixthis/agent-setup.json`, `.fixthis/agent-setup.md`, and the console
  use the same first-run readiness state names for equivalent conditions.
- The feedback console can guide a first-time user from connection recovery to
  capture, annotation, and Save to MCP without inventing state names that
  conflict with `doctor`.
- Release verification includes a gate for README/CLI consistency, a golden
  path smoke or release-time equivalent, release package integrity, consumer
  fixture confidence, and connected/compatibility observation output.
- v0.5 release notes can truthfully claim that Claude Code and Codex can
  bootstrap FixThis from README instructions and reach the first handoff path.

## Non-Goals

- No production runtime support.
- No release-build sidekick behavior.
- No AndroidView, XML/View, WebView, Flutter, React Native, iOS, or cloud
  workflow expansion.
- No automatic code edits inside FixThis.
- No guaranteed exact source-line mapping.
- No broad source-matching redesign beyond any small fixes needed to make the
  first handoff honest.
- No major visual redesign of FixThis Studio.
- No rename of persisted MCP JSON compatibility fields such as `items`,
  `screens`, `itemId`, `screenId`, `targetEvidence`,
  `targetReliability`, or `sourceCandidates`.
- No bridge protocol bump unless implementation discovers an unavoidable
  additive capability needed for first-run readiness.
- No requirement that every expensive connected, package-manager, or
  performance check run on every PR.

## Current Baseline

The repository already contains the foundations v0.5 should build on:

- Public install paths are documented: Homebrew, npm wrapper, GitHub Release
  package, Gradle Plugin Portal, Maven Central, and MCP Registry.
- `fixthis install-agent`, `fixthis init`, `fixthis doctor`, `fixthis setup`,
  `fixthis console`, and `fixthis mcp` exist.
- `.fixthis/agent-setup.*` files exist as setup handoff artifacts.
- README already describes external agent installation and the sample handoff
  path.
- `AGENTS.md` and `MCP.md` describe the agent and MCP workflow.
- The console has connection recovery states, device selection, preview
  capture, annotation draft, Copy Prompt, Save to MCP, and sent feedback queue
  behavior.
- First-run trust, project risk closure, and v0.4 risk burn-down specs already
  identify many of the same reliability concerns.
- Docs/CLI surface and docs consistency checks already exist and can be
  extended rather than replaced.
- Release readiness docs already separate PR-time checks from source release,
  artifact release, and registry release claims.

The v0.5 design should consolidate these into one explicit onboarding contract
instead of creating a parallel setup system.

## User And Agent Model

### Primary human user

The primary user is an Android Compose developer who already uses Claude Code,
Codex, Cursor, or another coding agent. They want to annotate a running UI and
give an agent more precise context than a screenshot alone.

They should not need to understand FixThis internals before trying it. They
need to know:

- FixThis is debug-only and Compose-only.
- It runs locally over ADB and localhost.
- Screenshots can contain sensitive pixels.
- The first command to run in their Android repo.
- What to do after MCP config is written.
- How to recover from setup, device, app, and console failures.

### Primary agent user

Claude Code or Codex may receive only the README or the README plus a short
user instruction such as:

```text
Install FixThis in this project and configure it for this agent.
```

The agent should be able to infer the intended flow without browsing the full
docs tree first:

1. Confirm the current project is an Android Compose repo when possible.
2. Install or use the `fixthis` CLI.
3. Run `fixthis install-agent --project-dir . --target all`.
4. Run `fixthis doctor --project-dir . --json`.
5. Interpret readiness.
6. Tell the user whether an agent restart is required.
7. Open or instruct use of the feedback console.
8. Use MCP queue tools once Save to MCP creates sent feedback.

The agent should not guess alternate command order, manually edit MCP config
when the CLI can do it, or claim setup success without running doctor.

## Milestone Shape

v0.5 is one program with four tracks. Each track can land in slices, but the
release claim requires all four to be coherent.

| Track | Purpose | Primary surfaces |
| --- | --- | --- |
| T1 - README-first agent bootstrap | Make README sufficient for Claude/Codex setup | README, agent snippet, docs checks |
| T2 - First-run readiness contract | Use one state vocabulary across setup and console | CLI, doctor, agent setup files, console API |
| T3 - Golden path smoke | Prove first setup-to-handoff path | fixtures, fake bridge, console harness, MCP tool output |
| T4 - Release confidence gates | Make release claims executable | release readiness docs, package tests, observation checks |

## Track 1 - README-first Agent Bootstrap

### Design

README should include a clearly marked agent bootstrap block near the external
project Quick Start. The block is written for Claude Code and Codex, not for a
human reading every paragraph.

The block should tell the agent:

- Use FixThis only for Jetpack Compose debug apps.
- Prefer the published CLI path: Homebrew or npm, with GitHub Release package
  fallback.
- Run `fixthis install-agent --project-dir . --target all`.
- Run `fixthis doctor --project-dir . --json`.
- Treat doctor JSON readiness as the source of truth.
- Restart the agent after MCP config writes when required by the client.
- Open the console through `fixthis_open_feedback_console` when MCP is active,
  or tell the user to run `fixthis console` for Copy Prompt workflows.
- Do not commit `.fixthis/`.
- Do not configure release builds.

The README should not duplicate all troubleshooting details. It should link to
the detailed docs after giving the agent the minimum executable path.

### Agent prompt block shape

The prompt block should be short enough to paste and specific enough to avoid
agent improvisation:

```text
You are in an Android Jetpack Compose app repo. Install FixThis for this repo
and configure it for Claude Code and Codex if possible.

Run:
1. fixthis install-agent --project-dir . --target all
2. fixthis doctor --project-dir . --json

Use the doctor JSON readiness result as the source of truth. If MCP config was
written, tell me to restart the agent before calling fixthis_open_feedback_console.
Do not configure release builds and do not commit .fixthis/.
```

Implementation may adjust wording, but the command order and constraints are
part of the contract.

### Documentation ownership

- README owns the shortest README-first bootstrap.
- `docs/getting-started/agent-install-snippet.md` owns the pasteable snippet
  for another repo's `AGENTS.md` or `CLAUDE.md`.
- `docs/getting-started/connect-your-agent.md` owns deeper Claude Code, Codex,
  Cursor, and chat-agent workflows.
- `docs/reference/cli.md` owns full command and flag reference.
- `docs/guides/troubleshooting.md` owns detailed recovery explanations.

### Acceptance

- A reader can find the agent bootstrap path from the README first screen or
  first external-project Quick Start section.
- README and agent snippet contain the same canonical command order.
- Docs checks fail if README or snippet references a removed subcommand or flag.
- The README does not imply that FixThis supports release builds, non-Compose
  targets, cloud upload, or exact source-line guarantees.

## Track 2 - First-run Readiness Contract

### Design

First-run readiness is the shared vocabulary for installation, diagnosis, and
console recovery. It should be represented as a stable machine-readable shape
where possible and rendered into human copy at the edges.

The core fields are:

| Field | Meaning |
| --- | --- |
| `state` | Stable enum, such as `READY` or `NEEDS_APP_LAUNCH` |
| `cause` | Short user-facing reason |
| `verify` | Command, UI condition, or check that confirms the state |
| `fix` | Corrective action |
| `nextAction` | One recommended next action |
| `details` | Optional raw diagnostic data |

The canonical states for v0.5 are:

| State | Meaning | Typical next action |
| --- | --- | --- |
| `READY` | Setup, selected device, debug app, sidekick, and console can proceed | Open console or capture |
| `NEEDS_INSTALL` | FixThis is not configured or required metadata is missing | Run `fixthis install-agent --project-dir . --target all` |
| `NEEDS_APP_LAUNCH` | ADB is usable but app or sidekick bridge is not reachable | Launch app or click Start/Open app |
| `DEVICE_BLOCKED` | Device/app exists but is not interactable | Unlock, authorize, foreground, or leave PiP |
| `UNSUPPORTED_BUILD` | Release/non-debug app, missing sidekick, or `run-as` denied | Install a debuggable build with FixThis enabled |
| `CONFIG_RECOVERABLE` | Config can be regenerated or clarified | Re-run setup or choose package/module |
| `ENV_BLOCKER` | Android SDK, ADB, JDK, Node, repo root, or permission issue | Fix prerequisite, then run doctor |
| `STALE_PREVIEW` | Frozen preview does not match live screen | Recapture, force-save, or cancel |
| `SESSION_MISMATCH` | Response/artifact belongs to another feedback session | Refresh or return to active session |
| `UNKNOWN_ERROR` | Failure is not classified | Show diagnostics and a safe verification command |

### CLI and setup files

`install-agent` should write or update `.fixthis/agent-setup.json` and
`.fixthis/agent-setup.md` with:

- detected project root;
- detected or selected package;
- Gradle files changed or skipped;
- MCP targets written or skipped;
- global-write guard status;
- whether agent restart is required;
- readiness state or setup result;
- the next command to run;
- recovery table keyed by readiness state.

Failures should still write partial setup information when safe, so an agent
can continue with `doctor` instead of starting over.

`doctor --json` remains the canonical post-setup check. It should classify
known failures into readiness states before exposing raw exceptions. Raw
details stay in diagnostic fields.

### Console

The console should render readiness states instead of creating unrelated state
names for the same condition. Existing console states such as `WELCOME`,
`READY`, `OPEN_APP`, `STARTING`, `RECONNECT`, `CHOOSE_DEVICE`, `CHECK_PHONE`,
and `UNSUPPORTED_BUILD` may remain as UI states, but they should map cleanly to
the readiness contract where they describe first-run blockers.

Examples:

- App not launched -> `NEEDS_APP_LAUNCH`.
- Device unauthorized or locked -> `DEVICE_BLOCKED`.
- Release/non-debug build -> `UNSUPPORTED_BUILD`.
- Console token/origin problem -> `CONFIG_RECOVERABLE` or `UNKNOWN_ERROR`
  depending on recoverability.
- Frozen preview mismatch -> `STALE_PREVIEW`.

### Error handling policy

- Prefer one primary recovery action.
- Keep raw stack traces and JSON in details, not primary copy.
- Do not silently ignore setup writes, console save failures, or MCP config
  collisions.
- If a step cannot safely modify global config, report a partial result with a
  clear manual next action.
- If the agent must restart to load MCP config, say so explicitly.

### Acceptance

- Equivalent setup, doctor, and console failures use the same readiness state.
- `install-agent` and `doctor --json` tests assert machine-readable readiness
  shape, not only human copy.
- Agent setup markdown and JSON are internally consistent.
- Console connection and blocked-state tests assert readiness mapping for core
  first-run states.

## Track 3 - Golden Path Smoke

### Design

v0.5 needs an executable proof that the README-first path reaches a usable
handoff. The smoke should be deterministic and cheap enough to run at release
time, with a path to promote stable pieces into PR-time checks.

The smoke does not need to perform a network package-manager installation on
every run. It can use local distributions, fixture projects, fake bridge
servers, and the existing console harness direction.

### Minimum covered flow

The golden path smoke should prove:

1. README-declared commands are valid CLI commands.
2. A fixture external Compose app can be recognized by setup logic.
3. `install-agent --project-dir <fixture> --target all` produces expected
   project metadata and agent setup artifacts in dry-run or isolated write
   mode.
4. `doctor --project-dir <fixture> --json` returns a valid readiness object.
5. The console can reach a ready or recoverable first-run state using fake
   bridge/device inputs.
6. The annotation flow can create a draft item.
7. Copy Prompt or Save to MCP can persist written feedback.
8. `fixthis_read_feedback` returns agent-usable Markdown and JSON with item id,
   screen id, target evidence, reliability where available, and source
   candidates where available.

### Fixture strategy

Use focused fixtures instead of a fragile full external app:

- A minimal Gradle Android Compose fixture for setup detection and plugin patch
  behavior.
- Existing release/minify consumer fixture for artifact resolution confidence.
- Fake bridge/console scenarios for device, app, preview, and annotation
  states.
- Existing parity fixtures for handoff Markdown and persisted JSON contracts.

### PR-time versus release-time ownership

The full setup-to-console-to-handoff smoke is release-time required for v0.5.
Stable subchecks should move into PR-time fast gates as they become cheap:

- README/CLI command validity should be PR-time.
- `install-agent` JSON shape should be PR-time.
- `doctor` readiness classification should be PR-time.
- Full fixture setup-to-console-to-handoff smoke remains release-time until it
  is proven stable enough for PR-time execution.

### Acceptance

- The smoke has a single documented command.
- The command output is suitable for attaching to a release issue.
- The smoke fails if README command order drifts from CLI reality.
- The smoke fails if no agent-readable readiness result is produced.
- The smoke fails if the handoff queue cannot produce readable feedback.

## Track 4 - Release Confidence Gates

### Design

v0.5 should separate fast PR confidence from release confidence. The release
claim is stronger than a normal PR claim, so release-time checks may be heavier.

### PR-time gates

These checks should remain fast enough for regular development:

- `npm run ci:local:fast`
- docs consistency check
- docs/CLI surface check
- console asset bundle check
- focused readiness unit tests
- focused console state/presenter tests
- release wrapper tests that do not require network publication
- detekt baseline ratchet
- source formatting and static analysis according to existing CI policy

### Release-time gates

Before tagging v0.5, the release checklist should require:

- `npm run ci:local`
- release tarball package test with SHA-256 sidecar validation
- clean consumer fixture resolving the Gradle plugin and sidekick/core
  artifacts from the intended public sources or a documented local equivalent
  for pre-publication validation
- golden path smoke output
- connected smoke on an unlocked emulator or physical device, or release notes
  that explicitly state why it was not run
- `npm run checks:observation -- --json` output captured for connected and
  compatibility workflows
- README, agent snippet, CLI reference, release notes, and changelog
  consistency checks

### Release readiness docs

`docs/contributing/release-readiness.md` should list the v0.5 onboarding claim
and the checks that support it. It should distinguish:

- checks required before any PR merge;
- checks required before source tag;
- checks required before external artifact publication;
- checks required before registry/package-manager promotion.

### Acceptance

- A maintainer can tell which command proves each release claim.
- The release checklist does not rely only on prose for README-first agent
  bootstrap.
- Expensive checks are not silently dropped. They are either PR-time,
  release-time, nightly, manual, or explicitly deferred with a reason.

## End-to-end Data Flow

```text
README agent block
-> agent runs fixthis install-agent --project-dir . --target all
-> setup writes Gradle/MCP changes when safe
-> setup writes .fixthis/project.json and .fixthis/agent-setup.*
-> agent runs fixthis doctor --project-dir . --json
-> doctor emits readiness state and next action
-> agent asks user to restart if MCP config changed
-> user/agent opens FixThis Studio
-> console maps bridge/device/app state to readiness-backed UI
-> user captures preview and adds annotations
-> user clicks Save to MCP
-> MCP session persists sent items and evidence snapshots
-> agent calls fixthis_list_feedback / fixthis_read_feedback
-> agent claims, edits app code, verifies, and resolves feedback
```

## Edge Cases

### Setup and repository detection

- Not running in an Android repository root maps to `ENV_BLOCKER`.
- No app module maps to `CONFIG_RECOVERABLE` if the user can point at a module,
  otherwise `ENV_BLOCKER`.
- Multiple app modules maps to `CONFIG_RECOVERABLE` with package/module
  selection guidance.
- Missing or ambiguous `applicationId` maps to `CONFIG_RECOVERABLE`.
- Release-only or non-debug configuration maps to `UNSUPPORTED_BUILD`.
- Gradle plugin patch failure maps to `CONFIG_RECOVERABLE` when manual patch is
  possible, otherwise `UNKNOWN_ERROR` with details.
- MCP config collision maps to `CONFIG_RECOVERABLE`.
- Global config write blocked by policy produces a partial result and explicit
  manual next action.
- `.fixthis/agent-setup.*` from another project root should be treated as stale
  setup context and regenerated.

### Environment and device

- Missing Android SDK or ADB maps to `ENV_BLOCKER`.
- No connected devices maps to `DEVICE_BLOCKED` if setup is otherwise valid.
- Unauthorized or offline device maps to `DEVICE_BLOCKED`.
- Locked screen, backgrounded app, PiP, or non-interactive screen maps to
  `DEVICE_BLOCKED`.
- App not installed or not launched maps to `NEEDS_APP_LAUNCH` or
  `NEEDS_INSTALL` depending on setup state.
- `run-as` denied or non-debug app maps to `UNSUPPORTED_BUILD`.

### Console and handoff

- Console cannot reach the bridge maps to `NEEDS_APP_LAUNCH`, `DEVICE_BLOCKED`,
  or `UNKNOWN_ERROR` based on diagnostics.
- Stale preview conflict maps to `STALE_PREVIEW`.
- Session artifact mismatch maps to `SESSION_MISMATCH`.
- Browser token/origin mutation failure maps to `CONFIG_RECOVERABLE` when reload
  or served URL recovery is enough.
- Save to MCP failure must preserve the draft where possible and show a clear
  retry or recovery action.
- Copy Prompt clipboard failure must expose manual-copy fallback.

## Contract Boundaries

- `:fixthis-compose-core` remains independent of CLI, MCP, Android UI, and
  `.fixthis/` paths.
- The sidekick remains debug-only and should not learn about desktop
  onboarding flows.
- CLI and MCP may share readiness models only through a module boundary that
  does not couple Android runtime code to desktop UI.
- Persisted MCP JSON field names remain stable.
- README and docs may describe command order, but CLI reference remains the
  source of full flag documentation.
- Release gates should call existing scripts where possible instead of adding
  one-off shell fragments.

## Documentation Changes

Expected docs touched by implementation:

- `README.md`
  - Add or tighten the Claude/Codex README-first agent bootstrap block.
  - Clarify command order and restart guidance.
  - Keep support limits visible.
- `docs/getting-started/agent-install-snippet.md`
  - Align with README block.
  - Make it suitable for another repo's `AGENTS.md` or `CLAUDE.md`.
- `docs/getting-started/connect-your-agent.md`
  - Ensure Claude Code and Codex setup/resume behavior matches v0.5.
- `docs/reference/cli.md`
  - Confirm install/init/doctor/setup flag references are current.
- `docs/guides/troubleshooting.md`
  - Align major first-run failures with readiness vocabulary.
- `docs/contributing/release-readiness.md`
  - Add v0.5 onboarding release claim and supporting gates.
- `MCP.md` and `AGENTS.md`
  - Keep concise agent workflow notes in sync with README.

## Verification Matrix

| Surface | Required verification |
| --- | --- |
| README agent block | docs/CLI surface check plus snippet consistency test |
| Agent setup files | CLI tests for JSON/Markdown output and restart guidance |
| Doctor readiness | CLI tests for state classification and JSON shape |
| Console readiness | console route/FSM/presenter tests for mapped states |
| Handoff queue | MCP session and compact handoff tests |
| Golden path | documented smoke command over fixture/fake bridge |
| Release package | package wrapper tests plus checksum validation |
| Consumer install | release/minify or clean external fixture |
| Connected behavior | connected smoke or documented release exception |
| Observation gates | `npm run checks:observation -- --json` captured before tag |

## Rollout Plan

The implementation plan should keep slices independently verifiable:

1. Lock README/snippet command contract.
2. Tighten setup and doctor readiness output.
3. Align console first-run states to readiness vocabulary.
4. Add or extend golden path smoke.
5. Update release readiness and docs checks.
6. Run final release-confidence verification.

Each slice should preserve the current user workflow. The only intended user
visible changes are clearer instructions, clearer recovery output, and stronger
verification around the first handoff path.

## Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| README becomes too agent-heavy for humans | Keep a short agent block and link to deeper docs |
| Readiness model duplicates existing console states | Map UI states to readiness instead of replacing all UI state names |
| Golden path smoke becomes flaky | Start release-time with fake bridge/fixtures, then promote stable parts |
| Release gates slow normal PR work | Keep expensive checks release-time or observation-owned |
| Agent setup writes global config unexpectedly | Preserve dry-run, target filtering, global-write guard, and clear partial results |
| Docs drift from CLI flags | Extend existing docs/CLI surface checks |
| v0.5 scope expands into source matching or interop | Keep exact mapping and interop expansion out of scope |

## Acceptance Checklist

- README contains the canonical Claude/Codex bootstrap block.
- Agent install snippet matches README command order.
- `install-agent` setup artifacts tell an agent what happened and what to run
  next.
- `doctor --json` returns readiness that an agent can use without parsing
  prose.
- Console first-run blockers map to the readiness vocabulary.
- Golden path smoke or release-time equivalent proves setup-to-handoff.
- Release readiness docs list the v0.5 onboarding claim and gates.
- No persisted MCP JSON field names are renamed.
- No release-build support is implied or added.
- No `.fixthis/` artifacts are committed.

## Implementation Planning Boundary

This design is ready for implementation planning after written spec review.
The implementation plan should be a separate document under
`docs/superpowers/plans/` and should decompose the work into small,
verification-backed slices. The plan should not add product scope beyond this
design without a follow-up design review.
