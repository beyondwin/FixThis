# Real Copy Prompt Evidence Design

Date: 2026-05-27
Status: Ready for implementation planning
Scope: local-only real Android sample app evidence for feedback console
annotation and Copy Prompt behavior across Reply, Jetsnack, and the bundled
FixThis sample app.

Related work:

- [Source Matching Runtime Trust Fixtures](2026-05-24-source-matching-runtime-trust-fixtures-design.md)
- [Trust Program Phase 2 - Handoff Rendering](2026-05-25-trust-program-phase2-handoff-rendering-design.md)
- [Console Inner Loop Tightening](2026-05-25-console-inner-loop-tightening-design.md)

## Summary

FixThis already has two useful but incomplete validation paths around feedback
handoff behavior:

1. fake-browser console smoke tests that prove the desktop console UI contract
   without launching real Android apps; and
2. runtime source-matching fixtures that install real sample apps and validate
   runtime trust evidence without driving the browser feedback console.

The missing evidence is the full user-facing loop on real apps: launch a debug
sample app, open a real feedback console session, create multiple annotations
from the browser preview, click Copy Prompt, and prove that the copied Markdown
and persisted draft session state match the agent-facing contract.

This design adds a local-only connected-device smoke that runs that flow across
the three actual runtime sample apps:

- `reply`
- `jetsnack`
- `fixthis-sample`

The smoke becomes an optional, deferrable step in the `evidence:trust` profile.
It does not become a CI or release gate.

## Goals

- Make the real 3-app Copy Prompt validation reproducible by one command.
- Exercise the browser feedback console against installed debug Android sample
  apps, not fake HTTP routes.
- Create at least two written annotations per app and validate that Copy Prompt
  contains both comments.
- Validate the compact Markdown prompt shape expected by chat-style agent
  handoff:
  - annotation `id:` lines
  - `Handoff quality:`
  - `agent_protocol:`
  - deterministic user comments
- Validate Copy Prompt persistence semantics through session state:
  - written pending annotations are persisted as items
  - copied items receive `lastHandedOffAtEpochMillis`
  - `delivery=sent` is not required for Copy Prompt
- Reuse existing runtime fixture preparation and install paths where possible.
- Add stable JSON and Markdown reports under `build/reports`.
- Document when to run the smoke and how to interpret deferred results.

## Non-Goals

- Do not replace `scripts/console-browser-smoke.mjs`; fake bridge console
  tests remain the fast console contract path.
- Do not replace `source-matching:fixtures:runtime`; runtime trust fixtures
  remain the source-matching and reliability contract path.
- Do not include `nowinandroid`; it is source-index-only for this purpose and
  is not part of the real app Copy Prompt smoke.
- Do not make this a CI, release, or package-publish gate.
- Do not require `delivery=sent` after Copy Prompt.
- Do not support release builds, non-Compose targets, Flutter, WebView DOM
  inspection, iOS, cloud execution, or remote device farms.
- Do not commit `.fixthis/`, runtime fixture workspaces, screenshots,
  `graphify-out/`, or build reports.

## Current Problem

The ad hoc connected-device validation has already proven that the current
latest commit can pass the desired real-app loop:

- Reply: two annotations copied and marked handed off.
- Jetsnack: two annotations copied and marked handed off.
- Bundled FixThis sample: two annotations copied and marked handed off.

However, that proof currently exists only as a one-off local Playwright script
and local artifacts. It is not a reusable project command, not part of any
evidence profile, and not documented as a local connected-device check.

That leaves two gaps:

1. regressions in the real Copy Prompt browser flow can slip through if fake
   route tests stay green; and
2. contributors can misunderstand Copy Prompt as the same state transition as
   Save to MCP, especially if tests assert `delivery=sent`.

## Design Principles

1. Real-app evidence should reuse the same fixture setup path as runtime trust
   evidence, so the installed apps are not a parallel test universe.
2. Environment absence and product regressions must be reported differently.
3. Copy Prompt is a chat-paste handoff path, not the same operation as Save to
   MCP queue delivery.
4. Browser automation should wait on concrete console state instead of fixed
   sleeps.
5. Reports should be durable enough for local evidence review but ignored by
   git.
6. The smoke should fail after collecting all app results rather than stopping
   at the first app.

## Architecture

Add a new connected-device smoke orchestrator:

```text
scripts/real-copy-prompt-smoke.mjs
```

The script owns the app-by-app browser workflow and report generation. It
should import or share small helper functions with existing scripts instead of
hardcoding fixture paths and Android package IDs.

Expected file boundaries:

- `scripts/real-copy-prompt-smoke.mjs`
  - command-line parsing
  - Android environment preflight
  - runtime fixture selection
  - app launch
  - MCP stdio process management
  - Playwright browser interaction
  - Copy Prompt and session-state assertions
  - JSON/Markdown report writing
- `scripts/source-matching-fixtures.mjs`
  - export minimal runtime fixture helpers needed to avoid duplicate manifest,
    worktree, Gradle, and install logic
- `scripts/evidence-runner.mjs`
  - add an optional deferrable Android step to the `trust` profile
- `package.json`
  - add a manual script such as `real-copy-prompt:smoke`
- `CONTRIBUTING.md`
  - document the command in local evidence and connected-device sections
- `docs/reference/feedback-console-contract.md`
  - clarify Copy Prompt persistence semantics
- `docs/reference/mcp-tools.md`
  - clarify Copy Prompt versus Save to MCP wording

### Runtime Fixture Selection

The smoke selects only fixtures with runtime-trust capability and an installable
debug app. The default set is:

```text
reply,jetsnack,fixthis-sample
```

`nowinandroid` remains excluded because this evidence path needs a running app
and feedback console preview, not only source-index generation.

The command may accept an explicit fixture subset:

```bash
npm run real-copy-prompt:smoke -- --fixtures reply,jetsnack --strict
```

Unknown fixture IDs should fail in strict mode and produce a clear command-line
error.

### Fixture Preparation

The smoke should use the same preparation assumptions as runtime trust
fixtures:

1. create or reuse the local fixture work copy;
2. enable debug runtime injection;
3. generate runtime-compatible source index metadata;
4. build and install the debug variant;
5. launch the selected app package.

The implementation can refactor `scripts/source-matching-fixtures.mjs` to
export a small helper for these operations. The helper should be narrow and
local-script-oriented; it must not create a public API promise.

## Command Contract

Primary strict command:

```bash
npm run real-copy-prompt:smoke -- --strict
```

Optional subset command:

```bash
npm run real-copy-prompt:smoke -- --fixtures reply,jetsnack,fixthis-sample --strict
```

Suggested package script:

```json
{
  "scripts": {
    "real-copy-prompt:smoke": "node scripts/real-copy-prompt-smoke.mjs"
  }
}
```

The script should support at least:

- `--strict`
- `--fixtures <comma-separated-fixture-ids>`
- `--report-dir <path>` for local troubleshooting if needed
- `--headed` as an optional browser debugging aid if it fits existing script
  conventions

## App Execution Flow

For each selected fixture:

1. Ensure the fixture app is built and installed.
2. Launch the package with ADB.
3. Start a repository-local MCP stdio process for that package/project.
4. Call `fixthis_open_feedback_console` with `newSession: true`.
5. Open the returned local console URL with Playwright.
6. Wait for the feedback console preview to be ready.
7. Click `Annotate`.
8. Create two visual-area annotations on stable preview coordinates.
9. Fill deterministic comments, for example:
   - `Reply copy prompt smoke annotation 1`
   - `Reply copy prompt smoke annotation 2`
10. Click Copy Prompt.
11. Capture clipboard Markdown through a browser-controlled clipboard shim.
12. Query session state through console APIs or MCP/session endpoints.
13. Assert prompt shape and persisted item state.
14. Save prompt and screenshot artifacts.
15. Close browser and MCP process before continuing to the next app.

The app workflow should not depend on a particular app screen beyond the first
ready preview. The annotation targets are visual areas, so the smoke validates
the console handoff loop rather than app-specific navigation semantics.

## Copy Prompt Assertions

The copied Markdown must include:

- both deterministic annotation comments;
- at least two `id:` occurrences;
- `Handoff quality:`;
- `agent_protocol:`.

The persisted session state must include:

- at least two items for the written annotations;
- `lastHandedOffAtEpochMillis` on the items included in the copied prompt.

The smoke must not assert:

- `delivery=sent`;
- Save to MCP batch creation;
- feedback queue claim or resolve state.

This distinction is the core behavior the test protects. Copy Prompt prepares
and copies compact Markdown for a chat-style handoff while preserving a local
draft/session record. Save to MCP is the queue-oriented local handoff path.

## Reports

Default report directory:

```text
build/reports/fixthis-real-copy-prompt/
```

Required files:

```text
build/reports/fixthis-real-copy-prompt/report.json
build/reports/fixthis-real-copy-prompt/report.md
```

Debug artifacts:

```text
build/reports/fixthis-real-copy-prompt/artifacts/
```

`report.json` should include:

```json
{
  "status": "pass | fail | deferred",
  "startedAt": "2026-05-27T00:00:00.000Z",
  "finishedAt": "2026-05-27T00:00:00.000Z",
  "strict": true,
  "device": "emulator-5554",
  "summary": {
    "totalApps": 3,
    "passedApps": 3,
    "failedApps": 0,
    "deferredApps": 0
  },
  "apps": [
    {
      "fixtureId": "reply",
      "packageName": "com.example.reply",
      "sessionId": "session-id",
      "itemCount": 2,
      "idLineCount": 2,
      "handedOffCount": 2,
      "promptChars": 2324,
      "promptPath": "artifacts/reply-prompt.md",
      "screenshotPath": "artifacts/reply-after-copy.png",
      "status": "pass",
      "failures": []
    }
  ],
  "failures": []
}
```

`report.md` should render a compact table:

```text
| Fixture | Package | Status | Items | IDs | Handed off | Prompt |
```

The report should collect all app outcomes before determining the process exit
code. A single failed app makes the overall status `fail`.

## Evidence Profile Integration

Add the smoke to the `trust` evidence profile as an optional connected-device
step.

Expected behavior:

- `requiresAndroid: true`
- `deferrable: true`
- runs after or near `source-matching:fixtures:runtime -- --strict`
- absent Android environment is deferred in normal local evidence mode
- strict runtime evidence exposes the failure instead of silently passing

This keeps local evidence honest without making every contributor or CI runner
own an emulator/device.

## Error Handling

Environment errors:

- Missing Android SDK, missing `adb`, or no connected device/emulator:
  - non-strict: write `status: deferred`, exit `0`
  - strict: write `status: fail`, exit non-zero

Fixture and product errors:

- Gradle setup, build, or install failure should fail when the smoke is
  actually running.
- App launch failure should fail the app row and continue to the next app.
- MCP startup or console URL failure should fail the app row.
- Preview timeout should fail the app row and capture available diagnostics.
- Annotation creation failure should fail the app row.
- Clipboard assertion failure should fail the app row and save the copied text
  if any.
- Session state assertion failure should fail the app row and save available
  state JSON if possible.

Cleanup:

- Playwright browser instances must close in `finally`.
- MCP child processes must be terminated in `finally`.
- Partial report data should still be written after failures.

## Flake Controls

The browser flow should wait for concrete UI or API state:

- console page loaded;
- preview image available;
- Annotate button enabled;
- annotation detail/input visible;
- Copy Prompt command complete;
- clipboard text available;
- session state updated.

Avoid fixed sleeps except as short polling intervals inside bounded wait loops.

Each app should have an explicit timeout. The timeout should be long enough for
fixture app startup and bridge attachment on a local emulator, but not
unbounded.

## Tests

Add a Node test for pure script logic:

```text
scripts/real-copy-prompt-smoke-test.mjs
```

Test coverage should include:

- default fixture selection excludes source-index-only fixtures;
- `--fixtures` parsing and unknown fixture handling;
- copied Markdown assertion success and failure cases;
- report summary aggregation;
- strict versus non-strict environment status calculation;
- report Markdown rendering.

Update existing evidence-runner tests, if present, to prove the new command is
registered as a deferrable Android trust-profile step.

Connected-device verification commands:

```bash
npm run source-matching:fixtures:runtime -- --strict
npm run real-copy-prompt:smoke -- --strict
npm run evidence:trust -- --strict-runtime
```

General repo verification after implementation:

```bash
npm test
graphify update .
git status --short
```

## Acceptance Criteria

- `npm run real-copy-prompt:smoke -- --strict` runs Reply, Jetsnack, and the
  bundled FixThis sample by default.
- Each app creates at least two annotations, copies a compact prompt, and
  validates prompt comments plus trust/protocol markers.
- Each app validates persisted item state through `lastHandedOffAtEpochMillis`.
- The smoke does not require `delivery=sent` for Copy Prompt.
- The command writes `report.json`, `report.md`, prompt artifacts, and
  screenshots under `build/reports/fixthis-real-copy-prompt/`.
- The `trust` evidence profile includes the smoke as a deferrable
  connected-device step.
- Docs explain when to run the command and how Copy Prompt differs from Save to
  MCP.
- Unit tests cover the script's pure logic and evidence-runner registration.
- No `.fixthis/`, fixture workspace, `graphify-out/`, screenshot, or build
  report artifact is committed.

## Open Risks

- Browser element selectors may drift if the console UI is redesigned. The
  implementation should prefer stable IDs or accessible labels already used by
  existing console smoke tests.
- Fixture install time may make `evidence:trust` noticeably slower on local
  machines. Keeping the step deferrable and connected-device-only limits that
  cost.
- Visual-area annotation coordinates can be density-sensitive. The script
  should compute coordinates relative to the preview element bounds rather than
  using absolute page coordinates.
- Clipboard APIs can vary in headless browser contexts. The script should use
  the same clipboard interception style proven by the ad hoc validation and
  fail with captured diagnostics when clipboard text is unavailable.
