# External App First Handoff Recovery - Design

Date: 2026-06-09
Status: Ready for user review
Scope: first external-app handoff success evidence and install/doctor recovery
alignment.

## Summary

FixThis v1.2.0 already has strong local trust evidence for source matching,
runtime handoff, release gates, public install channels, and MCP persistence.
The next highest-leverage product improvement is to make the external Android
app adoption loop measurable from the user's first successful outcome:

```text
fixthis install-agent
-> fixthis doctor --json
-> fixthis_open_feedback_console
-> Start
-> Annotate
-> Save to MCP
-> fixthis_read_feedback
```

The goal is not just "FixThis installed." The goal is "an MCP-aware agent can
read one sent feedback item from an external debug Compose app, and if that
loop fails, the report tells the user the concrete recovery action."

This design combines two approved improvement areas:

1. first handoff success rate, covering the browser console and MCP queue; and
2. install/doctor failure recovery, aligning `install-agent`, `doctor --json`,
   and smoke reports around the same readiness vocabulary.

The implementation should extend the existing CLI, MCP, browser-smoke, and
release-evidence surfaces rather than create a parallel onboarding framework.

## Goals

- Promote the first handoff loop to a named evidence surface for external app
  adoption.
- Ensure a failed first handoff report includes a stable failure code,
  `readiness`, `nextAction`, `verify`, `fix`, and useful diagnostics.
- Align `fixthis install-agent --json`, `fixthis doctor --json`, and
  `npm run agent-loop:smoke` so they do not contradict each other about the next
  user action.
- Keep non-strict runs honest by recording Android runtime blockers as
  `deferred` with exact reasons, while strict runs fail when connected evidence
  cannot run.
- Add focused contract tests for recovery mapping without requiring Android
  runtime for every development loop.
- Wire the first handoff evidence into release-readiness and release-gate
  documentation so future release claims are backed by commands.

## Non-Goals

- Do not add broad Gradle compatibility expansion in this pass. Multi-module,
  flavor-heavy, and unusual `applicationId` projects may be used only when they
  are needed to exercise the B/A recovery flow.
- Do not add PyPI, Docker, or any new package channel.
- Do not add new first-class agent config writers. Claude Code, Codex, and
  Cursor remain file-config targets; ChatGPT and similar agents remain
  Copy-Prompt flows unless a writable config surface is verified separately.
- Do not target release builds, XML/View exact source targeting, WebView DOM
  inspection, Flutter, React Native, iOS, or cloud review behavior.
- Do not rename persisted MCP JSON fields such as `items`, `screens`, `itemId`,
  `screenId`, `targetEvidence`, `targetReliability`, or `sourceCandidates`.
- Do not remove SSE fallback polling or manual recovery paths as part of this
  adoption pass.

## Current Anchors

The design uses existing implementation surfaces:

- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`
  renders `doctor --json` with top-level `readiness` and `nextAction`.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/readiness/FirstRunReadiness.kt`
  defines the shared readiness states and recovery wording.
- `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReport.kt`
  renders `install-agent --json` with applied/skipped/error entries,
  `readiness`, `nextAction`, and `restartRequired`.
- `scripts/agent-loop-smoke.mjs` already drives a real runtime fixture through
  `fixthis_open_feedback_console`, browser annotation, Copy Prompt,
  Save to MCP, `fixthis_read_feedback`, claim, resolve, persistence, and console
  reflection.
- `scripts/first-run-smoke.mjs` already exercises a fast fake-bridge browser
  loop from preview to Save to MCP.
- `scripts/release-gate.mjs`, `scripts/evidence-runner.mjs`, and
  `docs/contributing/release-readiness.md` already aggregate local evidence and
  deferred runtime reasons.

## Architecture

### Track A - First Handoff Evidence Surface

`scripts/agent-loop-smoke.mjs` becomes the canonical connected proof for the
external first handoff loop. It should keep its current end-to-end coverage but
make the report more useful for first-run adoption:

```text
runtime fixture install
-> MCP server launch
-> fixthis_open_feedback_console
-> browser Start and preview readiness
-> Annotate one or more targets
-> Save to MCP
-> fixthis_read_feedback
-> optional claim/resolve reflection
-> report.json and report.md
```

The report should explicitly distinguish:

- setup and package/config failures;
- Android environment and app launch failures;
- console capture or annotation failures;
- Save to MCP persistence failures;
- MCP queue read failures; and
- lifecycle claim/resolve failures.

The proof remains local-first and should continue writing reports under
`build/reports/fixthis-agent-loop/`. No `.fixthis/`, screenshots, generated
fixture workspaces, or report output is committed.

### Track B - Recovery Mapping

Recovery mapping should centralize around the existing `FirstRunReadiness`
model. The first handoff report should carry the same shape used by CLI JSON:

```json
{
  "state": "NEEDS_APP_LAUNCH",
  "cause": "The debug app is not connected to the FixThis bridge.",
  "verify": "fixthis doctor --project-dir . --json",
  "fix": "Launch the debug app so the FixThis sidekick can write its bridge session.",
  "nextAction": "Open app"
}
```

The implementation may add JavaScript-side helper functions that map smoke
failure codes to this shape, but the wording and state names must stay aligned
with `FirstRunReadiness.kt` and CLI tests. If a new readiness state is truly
needed, it must be additive, documented in `docs/reference/agent-setup-schema.md`,
and covered by CLI plus script tests. The preferred first pass is to reuse
existing states:

- `NEEDS_INSTALL`
- `NEEDS_APP_LAUNCH`
- `DEVICE_BLOCKED`
- `UNSUPPORTED_BUILD`
- `CONFIG_RECOVERABLE`
- `ENV_BLOCKER`
- `CAPTURE_UNAVAILABLE`
- `SESSION_MISMATCH`
- `UNKNOWN_ERROR`

### Track C - Install And Doctor Agreement

`install-agent --json` and `doctor --json` should agree on what the user does
next:

- If project metadata is absent, the next action is installing or rerunning
  setup, not opening the console.
- If package inference is ambiguous, the next action names `--package` or a
  dry-run setup command, not a generic retry.
- If ADB or SDK discovery fails, the next action is environment recovery.
- If no connected device is available, the next action is starting an emulator
  or connecting a device.
- If the app is not launched or bridge session is unavailable, the next action
  is opening the debug app.
- If the sidekick reports an unsupported release or non-debug build, the next
  action is installing a debuggable build with FixThis enabled.

Existing CLI test classes should be extended rather than replaced:

- `DoctorCommandTest`
- `InstallAgentJsonReportTest`
- `AgentSetupFilesTest`
- `InitAgentCommandTest`

### Track D - Fast Contract Coverage

Connected smoke should remain the high-confidence proof, but the development
loop needs fast checks that do not require Android runtime. The existing
fake-bridge and script tests should cover the new report and recovery contracts:

- `scripts/agent-loop-smoke-test.mjs` verifies report rendering, failure-code
  categorization, readiness mapping, deferred-vs-fail behavior, and queue
  expectations.
- `scripts/first-run-smoke.mjs` remains the browser-console fake-bridge check
  for Annotate and Save to MCP.
- `scripts/first-run-smoke-contract-test.mjs` should verify any new shape or
  wording that fake-bridge smoke emits.

## Failure Taxonomy

The first handoff report should expose a stable `failureCode` for the first
blocking failure. Codes should be specific enough for release evidence but not
so granular that they encode implementation details.

Recommended initial codes:

| Failure code | Readiness state | Typical next action |
| --- | --- | --- |
| `android_environment_unavailable` | `ENV_BLOCKER` | Install Android SDK or fix SDK env. |
| `device_unavailable` | `DEVICE_BLOCKED` | Start an emulator or connect a device. |
| `metadata_missing` | `NEEDS_INSTALL` | Run `fixthis install-agent --project-dir . --target all`. |
| `package_ambiguous` | `CONFIG_RECOVERABLE` | Pass `--package <applicationId>` or inspect dry-run. |
| `app_not_launched` | `NEEDS_APP_LAUNCH` | Open the debug app. |
| `unsupported_build` | `UNSUPPORTED_BUILD` | Install a debuggable build with FixThis enabled. |
| `preview_capture_unavailable` | `CAPTURE_UNAVAILABLE` | Reopen the app foreground and retry capture. |
| `annotation_unavailable` | `CAPTURE_UNAVAILABLE` | Recapture and retry annotation. |
| `save_to_mcp_failed` | `UNKNOWN_ERROR` | Open diagnostics and rerun with report artifacts. |
| `read_feedback_missing_items` | `SESSION_MISMATCH` | Refresh session or inspect saved handoff batch. |
| `mcp_transport_failure` | `UNKNOWN_ERROR` | Restart MCP server and rerun the smoke. |

Unknown failures remain possible, but they must retain raw diagnostics in the
report details without leaking screenshots or user content into committed files.

## Data Contracts

`build/reports/fixthis-agent-loop/report.json` should keep the existing
top-level fields and add first-run recovery fields in an additive way:

```json
{
  "status": "fail",
  "strict": true,
  "device": null,
  "startedAt": "2026-06-09T00:00:00.000Z",
  "finishedAt": "2026-06-09T00:01:00.000Z",
  "firstHandoff": {
    "status": "fail",
    "failureCode": "device_unavailable",
    "readiness": {
      "state": "DEVICE_BLOCKED",
      "cause": "No connected Android device or emulator found.",
      "verify": "Check the device state shown in the feedback console.",
      "fix": "Start an emulator or connect a device, then run `adb devices`.",
      "nextAction": "Start an emulator or connect a device, then run `adb devices`."
    }
  },
  "fixture": {
    "fixtureId": "reply",
    "packageName": "com.example.reply",
    "status": "fail"
  },
  "failures": [
    "No connected Android device or emulator found."
  ]
}
```

The implementation may choose exact nesting, but these concepts must be present
and test-covered:

- overall status;
- strict mode;
- device or deferred reason;
- fixture id and package name;
- first handoff status;
- failure code when failed or deferred;
- readiness object;
- next action;
- item counts when Save to MCP and `fixthis_read_feedback` succeed.

Markdown reports should render the same recovery fields in a compact section so
maintainers can paste the report into release notes or issue comments without
opening JSON.

## Error Handling

- Missing Android runtime is `deferred` in non-strict mode and `fail` in strict
  mode.
- If `installRuntimeFixture` fails before the app can launch, the report should
  record setup failure, fixture id, command, and recovery action where known.
- If the MCP server fails to start, classify as transport failure and include
  the command path, not a generic browser failure.
- If the browser cannot reach ready preview state, classify through capture or
  app-launch readiness depending on the available diagnostics.
- If Save to MCP succeeds but `fixthis_read_feedback` cannot find sent items,
  classify as a session or queue mismatch.
- If claim/resolve fails after the first sent item is readable, the first
  handoff is still partially successful; lifecycle failures should be separate
  from the first handoff outcome.
- Raw diagnostics must avoid committed `.fixthis/` artifacts and screenshots.

## Testing Strategy

### Fast Checks

```bash
npm run agent-loop:smoke:test
npm run first-run:smoke:test
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --tests "*AgentSetupFilesTest" --no-daemon
```

### Connected Evidence

Run when Android SDK and an unlocked emulator or device are available:

```bash
npm run agent-loop:smoke -- --strict
```

Non-strict mode may defer runtime checks but must write a report with the exact
reason:

```bash
npm run agent-loop:smoke
```

### Release Alignment

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
npm run evidence:trust -- --dry-run
```

### Documentation And Hygiene

```bash
node scripts/check-doc-consistency.mjs
git diff --check
graphify update .
```

`graphify update .` may update ignored graph artifacts only. Dirty
`graphify-out/` output is not part of the deliverable.

## Acceptance Criteria

- `agent-loop-smoke` report includes first handoff status, failure code,
  readiness, next action, fixture id, package name, and item counts when
  available.
- Non-strict missing-runtime runs are `deferred` with a recovery-oriented
  readiness object; strict missing-runtime runs fail.
- `install-agent --json`, `doctor --json`, and first handoff smoke do not emit
  contradictory `nextAction` values for the same blocker class.
- `Save to MCP` success is verified by `fixthis_read_feedback` seeing sent
  items, not only by a browser success message.
- Claim/resolve reflection remains tested, but it is reported separately from
  the first handoff threshold.
- Release-readiness docs identify the first handoff evidence command and the
  strict-runtime deferral rule.
- No persisted MCP JSON field is renamed and no generated `.fixthis/`,
  `graphify-out/`, report, screenshot, or fixture workspace artifact is
  committed.

## Rollout Plan

1. Add fast tests for first handoff report shape and failure-code/readiness
   mapping.
2. Extend `agent-loop-smoke` report generation with first handoff fields.
3. Align CLI JSON tests where `install-agent` or `doctor` next actions could
   contradict the smoke recovery action.
4. Update release-readiness and reference docs for the first handoff evidence
   command.
5. Run fast checks, connected smoke when available, release alignment checks,
   doc consistency, `git diff --check`, and `graphify update .`.

## Open Decisions

- The initial connected fixture remains `reply` unless implementation evidence
  shows another runtime fixture gives a more stable first-handoff signal.
- The exact JSON nesting for `firstHandoff` can be adjusted during
  implementation, but the required concepts in the Data Contracts section must
  remain present and tested.
- If implementation discovers a genuinely new blocker class, prefer mapping it
  to an existing readiness state first. Add a state only when the existing
  vocabulary would mislead users.
