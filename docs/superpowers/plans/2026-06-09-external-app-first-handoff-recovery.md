# External App First Handoff Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the external Android app first handoff loop measurable and recovery-oriented from `install-agent` through `fixthis_read_feedback`.

**Architecture:** Extend existing evidence surfaces instead of adding a new onboarding subsystem. `scripts/agent-loop-smoke.mjs` owns first handoff proof and report shape, CLI JSON tests guard next-action agreement, and release-readiness docs/gates make the evidence visible to maintainers.

**Tech Stack:** Node.js 20 test runner, Playwright, Kotlin/JUnit CLI tests, existing FixThis MCP/CLI scripts, Markdown docs.

---

## File Structure

- Modify `scripts/agent-loop-smoke.mjs`: add first handoff failure taxonomy, readiness mapping helpers, first handoff report fields, and runtime integration.
- Modify `scripts/agent-loop-smoke-test.mjs`: add fast contract tests for failure-code mapping, deferral vs strict failure, report JSON concepts, and Markdown rendering.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`: add an agreement test that the device blocker next action matches the first handoff recovery wording.
- Modify `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`: add an agreement test that successful setup points at `doctor --json` before console use.
- Modify `docs/contributing/release-readiness.md`: add the External App First Handoff Recovery evidence section.
- Modify `scripts/check-release-readiness.mjs`: enforce the new release-readiness section and strict smoke command.
- Modify `scripts/release-gate.mjs`: add a first-handoff recovery claim mapped to existing `Agent loop smoke` evidence.
- Modify `scripts/release-gate-test.mjs`: assert the new claim exists and maps deferred strict smoke correctly.
- Modify `docs/reference/cli.md` and `docs/reference/mcp-tools.md` only if implementation changes user-visible JSON/report semantics beyond the release-readiness section. The default plan does not require these reference files.

## Task 1: First Handoff Taxonomy And Pure Report Helpers

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`

- [ ] **Step 1: Add failing taxonomy tests**

Append these tests to `scripts/agent-loop-smoke-test.mjs` after the `environment status distinguishes strict failure and non-strict deferral` test:

```js
test("first handoff maps Android environment deferral to readiness", () => {
  const handoff = firstHandoffForEnvironment({
    strict: false,
    androidReady: false,
    reason: "Android SDK is unavailable.",
  });

  assert.equal(handoff.status, "deferred");
  assert.equal(handoff.failureCode, "android_environment_unavailable");
  assert.equal(handoff.readiness.state, "ENV_BLOCKER");
  assert.equal(handoff.nextAction, "Install Android SDK platform-tools or fix ANDROID_HOME.");
  assert.equal(handoff.readiness.details.reason, "Android SDK is unavailable.");
});

test("first handoff maps strict missing runtime to failure", () => {
  const handoff = firstHandoffForEnvironment({
    strict: true,
    androidReady: false,
    reason: "Android SDK or ready emulator is unavailable.",
  });

  assert.equal(handoff.status, "fail");
  assert.equal(handoff.failureCode, "device_unavailable");
  assert.equal(handoff.readiness.state, "DEVICE_BLOCKED");
  assert.match(handoff.readiness.nextAction, /Start an emulator/);
});

test("first handoff failure catalog keeps stable recovery fields", () => {
  const handoff = firstHandoffFailure({
    failureCode: "read_feedback_missing_items",
    message: "read_feedback_missing_items: item-404",
    details: { itemIds: "item-404" },
  });

  assert.equal(handoff.status, "fail");
  assert.equal(handoff.failureCode, "read_feedback_missing_items");
  assert.equal(handoff.readiness.state, "SESSION_MISMATCH");
  assert.equal(handoff.nextAction, "Refresh session");
  assert.equal(handoff.readiness.details.itemIds, "item-404");
});

test("first handoff success carries saved and readable item counts", () => {
  assert.deepEqual(firstHandoffSuccess({
    savedItemCount: 3,
    readFeedbackItemCount: 4,
    readFeedbackSentCount: 3,
  }), {
    status: "pass",
    savedItemCount: 3,
    readFeedbackItemCount: 4,
    readFeedbackSentCount: 3,
  });
});
```

Also add the new imports at the top of `scripts/agent-loop-smoke-test.mjs`:

```js
  firstHandoffFailure,
  firstHandoffForEnvironment,
  firstHandoffSuccess,
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: FAIL with an import or reference error naming `firstHandoffForEnvironment`, `firstHandoffFailure`, or `firstHandoffSuccess`.

- [ ] **Step 3: Implement pure first handoff helpers**

In `scripts/agent-loop-smoke.mjs`, add these exports after `statusForEnvironment`:

```js
const FirstHandoffFailureCatalog = Object.freeze({
  android_environment_unavailable: {
    state: "ENV_BLOCKER",
    cause: "Android SDK is unavailable.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Install Android SDK platform-tools or fix ANDROID_HOME.",
    nextAction: "Install Android SDK platform-tools or fix ANDROID_HOME.",
  },
  device_unavailable: {
    state: "DEVICE_BLOCKED",
    cause: "No connected Android device or emulator found.",
    verify: "adb devices",
    fix: "Start an emulator or connect a device, then run `adb devices`.",
    nextAction: "Start an emulator or connect a device, then run `adb devices`.",
  },
  metadata_missing: {
    state: "NEEDS_INSTALL",
    cause: "FixThis project metadata was not found.",
    verify: "./gradlew fixthisSetup",
    fix: "Run `fixthis install-agent --project-dir . --target all` from the Android project root.",
    nextAction: "Run `fixthis install-agent --project-dir . --target all`",
  },
  package_ambiguous: {
    state: "CONFIG_RECOVERABLE",
    cause: "FixThis could not choose a unique Android applicationId.",
    verify: "fixthis install-agent --project-dir . --target all --dry-run",
    fix: "Pass `--package com.example.app` or inspect the dry-run output before writing config.",
    nextAction: "Run `fixthis install-agent --project-dir . --target all --dry-run`",
  },
  app_not_launched: {
    state: "NEEDS_APP_LAUNCH",
    cause: "The debug app is not connected to the FixThis bridge.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Launch the debug app so the FixThis sidekick can write its bridge session.",
    nextAction: "Open app",
  },
  unsupported_build: {
    state: "UNSUPPORTED_BUILD",
    cause: "This build cannot expose the FixThis bridge.",
    verify: "adb shell run-as com.example.app ls files/fixthis/session.json",
    fix: "Install a debuggable build with the FixThis sidekick enabled.",
    nextAction: "Install a debuggable build with FixThis enabled.",
  },
  preview_capture_unavailable: {
    state: "CAPTURE_UNAVAILABLE",
    cause: "Screenshot bytes unavailable.",
    verify: "Open the app foreground and tap Capture, or open doctor for the bridge log.",
    fix: "Reopen the app foreground and tap Retry capture, or open doctor for the bridge log.",
    nextAction: "Retry capture",
  },
  annotation_unavailable: {
    state: "CAPTURE_UNAVAILABLE",
    cause: "Annotation target capture did not complete.",
    verify: "Recapture the screen and verify the frozen preview matches the app.",
    fix: "Recapture and retry annotation.",
    nextAction: "Retry capture",
  },
  save_to_mcp_failed: {
    state: "UNKNOWN_ERROR",
    cause: "Save to MCP did not complete.",
    verify: "Open diagnostic details and rerun the failed command with --verbose.",
    fix: "Open diagnostics and rerun with report artifacts.",
    nextAction: "Open diagnostic details",
  },
  read_feedback_missing_items: {
    state: "SESSION_MISMATCH",
    cause: "Saved feedback items were not readable through MCP.",
    verify: "Compare the response sessionId with the active feedback session.",
    fix: "Refresh the active session or inspect the saved handoff batch.",
    nextAction: "Refresh session",
  },
  mcp_transport_failure: {
    state: "UNKNOWN_ERROR",
    cause: "MCP transport failed during first handoff.",
    verify: "fixthis doctor --project-dir . --json",
    fix: "Restart the MCP server and rerun the smoke.",
    nextAction: "Restart MCP server",
  },
});

function readinessWithDetails(entry, message, details) {
  return {
    state: entry.state,
    cause: message || entry.cause,
    verify: entry.verify,
    fix: entry.fix,
    nextAction: entry.nextAction,
    details: { ...details },
  };
}

export function firstHandoffFailure({ failureCode, message = null, details = {}, status = "fail" } = {}) {
  const entry = FirstHandoffFailureCatalog[failureCode] || FirstHandoffFailureCatalog.mcp_transport_failure;
  const readiness = readinessWithDetails(entry, message, details);
  return {
    status,
    failureCode: FirstHandoffFailureCatalog[failureCode] ? failureCode : "mcp_transport_failure",
    readiness,
    nextAction: readiness.nextAction,
  };
}

export function firstHandoffSuccess({
  savedItemCount = 0,
  readFeedbackItemCount = 0,
  readFeedbackSentCount = 0,
} = {}) {
  return {
    status: "pass",
    savedItemCount,
    readFeedbackItemCount,
    readFeedbackSentCount,
  };
}

export function firstHandoffForEnvironment({ strict, androidReady, reason }) {
  if (androidReady) return { status: "pending" };
  const failureCode = reason === "Android SDK is unavailable."
    ? "android_environment_unavailable"
    : "device_unavailable";
  return firstHandoffFailure({
    failureCode,
    status: strict ? "fail" : "deferred",
    message: reason,
    details: { reason },
  });
}
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: PASS for all tests in `scripts/agent-loop-smoke-test.mjs`.

- [ ] **Step 5: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs
git commit -m "test: add first handoff recovery taxonomy"
```

## Task 2: First Handoff Report Shape And Markdown Rendering

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`

- [ ] **Step 1: Add failing report shape tests**

Replace the existing test named `buildReport and markdown summarize lifecycle counts` in `scripts/agent-loop-smoke-test.mjs` with this expanded version:

```js
test("buildReport and markdown summarize first handoff and lifecycle counts", () => {
  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-06-09T00:00:00.000Z",
    finishedAt: "2026-06-09T00:01:00.000Z",
    firstHandoff: firstHandoffSuccess({
      savedItemCount: 3,
      readFeedbackItemCount: 3,
      readFeedbackSentCount: 3,
    }),
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "pass",
      savedItemCount: 3,
      readFeedbackItemCount: 3,
      readFeedbackSentCount: 3,
      resolved: 1,
      needsClarification: 1,
      wontFix: 1,
    },
  });

  assert.equal(report.status, "pass");
  assert.equal(report.firstHandoff.status, "pass");
  assert.equal(report.firstHandoff.savedItemCount, 3);
  const markdown = renderMarkdownReport(report);
  assert.match(markdown, /## First Handoff/);
  assert.match(markdown, /- Status: pass/);
  assert.match(markdown, /\| reply \| com\.example\.reply \| pass \| 3 \| 3 \| 3 \| 1 \| 1 \| 1 \|/);
});
```

Add this new test after `buildReport fails when lifecycle assertions fail`:

```js
test("markdown report renders first handoff recovery details", () => {
  const report = buildReport({
    strict: false,
    device: null,
    startedAt: "2026-06-09T00:00:00.000Z",
    finishedAt: "2026-06-09T00:01:00.000Z",
    firstHandoff: firstHandoffFailure({
      failureCode: "device_unavailable",
      message: "Android SDK or ready emulator is unavailable.",
      details: { reason: "Android SDK or ready emulator is unavailable." },
      status: "deferred",
    }),
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "deferred",
      failures: ["Android SDK or ready emulator is unavailable."],
    },
  });

  const markdown = renderMarkdownReport(report);
  assert.equal(report.status, "deferred");
  assert.equal(report.firstHandoff.failureCode, "device_unavailable");
  assert.match(markdown, /- Failure code: device_unavailable/);
  assert.match(markdown, /- Readiness: DEVICE_BLOCKED/);
  assert.match(markdown, /- Next action: Start an emulator or connect a device/);
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: FAIL because `buildReport` does not yet include `firstHandoff`, and Markdown lacks the `## First Handoff` section.

- [ ] **Step 3: Update report construction and Markdown**

Replace the current `buildReport` function in `scripts/agent-loop-smoke.mjs` with:

```js
export function buildReport({ strict, device = null, startedAt, finishedAt, fixture, firstHandoff = null }) {
  const status = fixture.status === "pass" ? "pass" : fixture.status === "deferred" ? "deferred" : "fail";
  return {
    status,
    strict,
    device,
    startedAt,
    finishedAt,
    firstHandoff: firstHandoff || (
      status === "pass"
        ? firstHandoffSuccess({
            savedItemCount: fixture.savedItemCount || 0,
            readFeedbackItemCount: fixture.readFeedbackItemCount || 0,
            readFeedbackSentCount: fixture.readFeedbackSentCount || 0,
          })
        : firstHandoffFailure({
            failureCode: "mcp_transport_failure",
            message: (fixture.failures || [])[0] || "First handoff failed.",
          })
    ),
    fixture,
    failures: fixture.failures || [],
  };
}
```

In `renderMarkdownReport`, insert this block after the `Finished` line and before the fixture table:

```js
  const firstHandoff = report.firstHandoff || {};
  lines.push("", "## First Handoff", "");
  lines.push(`- Status: ${firstHandoff.status || "unknown"}`);
  if (firstHandoff.failureCode) lines.push(`- Failure code: ${firstHandoff.failureCode}`);
  if (firstHandoff.readiness?.state) lines.push(`- Readiness: ${firstHandoff.readiness.state}`);
  if (firstHandoff.nextAction) lines.push(`- Next action: ${firstHandoff.nextAction}`);
  if (Number.isFinite(firstHandoff.savedItemCount)) lines.push(`- Saved items: ${firstHandoff.savedItemCount}`);
  if (Number.isFinite(firstHandoff.readFeedbackItemCount)) lines.push(`- Read feedback items: ${firstHandoff.readFeedbackItemCount}`);
  if (Number.isFinite(firstHandoff.readFeedbackSentCount)) lines.push(`- Sent items read: ${firstHandoff.readFeedbackSentCount}`);
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs
git commit -m "feat: report first handoff recovery state"
```

## Task 3: Runtime Smoke Integration

**Files:**
- Modify: `scripts/agent-loop-smoke.mjs`
- Modify: `scripts/agent-loop-smoke-test.mjs`

- [ ] **Step 1: Add failing failure-code categorization tests**

Append this test to `scripts/agent-loop-smoke-test.mjs` after `categorizeFeedbackLifecycleFailure labels lifecycle gate failures`:

```js
test("categorizeFirstHandoffFailure separates capture save queue and transport failures", () => {
  assert.equal(
    categorizeFirstHandoffFailure(new Error("Snapshot image is not visible")),
    "preview_capture_unavailable",
  );
  assert.equal(
    categorizeFirstHandoffFailure(new Error("Annotation detail did not open after pin creation")),
    "annotation_unavailable",
  );
  assert.equal(
    categorizeFirstHandoffFailure(new Error("Save to MCP did not complete after Copy Prompt")),
    "save_to_mcp_failed",
  );
  assert.equal(
    categorizeFirstHandoffFailure(new Error("read_feedback_missing_items: item-404")),
    "read_feedback_missing_items",
  );
  assert.equal(
    categorizeFirstHandoffFailure(new Error("MCP process exited before initialize")),
    "mcp_transport_failure",
  );
});
```

Add `categorizeFirstHandoffFailure` to the import list.

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: FAIL because `categorizeFirstHandoffFailure` is not exported.

- [ ] **Step 3: Implement failure-code categorization**

Add this export after `categorizeFeedbackLifecycleFailure` in `scripts/agent-loop-smoke.mjs`:

```js
export function categorizeFirstHandoffFailure(error) {
  const message = String(error?.message || error || "").toLowerCase();
  if (message.includes("android sdk is unavailable")) return "android_environment_unavailable";
  if (message.includes("ready emulator") || message.includes("connected android device")) return "device_unavailable";
  if (message.includes("multiple android applicationid") || message.includes("pass --package")) return "package_ambiguous";
  if (message.includes("metadata") || message.includes("project.json")) return "metadata_missing";
  if (message.includes("not debuggable") || message.includes("unsupported build")) return "unsupported_build";
  if (message.includes("debug app is not connected") || message.includes("bridge") && message.includes("timed out")) return "app_not_launched";
  if (message.includes("snapshot image") || message.includes("preview") || message.includes("capture")) return "preview_capture_unavailable";
  if (message.includes("annotation detail") || message.includes("annotation target")) return "annotation_unavailable";
  if (message.includes("save to mcp") || message.includes("agent-handoffs")) return "save_to_mcp_failed";
  if (message.includes("read_feedback_missing_items")) return "read_feedback_missing_items";
  if (message.includes("mcp process") || message.includes("econnrefused") || message.includes("socket")) return "mcp_transport_failure";
  return "mcp_transport_failure";
}
```

- [ ] **Step 4: Integrate first handoff status into `runSmoke`**

In the early Android environment return path in `runSmoke`, change the `buildReport` call to:

```js
    const report = buildReport({
      strict: options.strict,
      device: null,
      startedAt,
      finishedAt: new Date().toISOString(),
      firstHandoff: firstHandoffForEnvironment({
        strict: options.strict,
        androidReady: environment.ready,
        reason: environment.reason,
      }),
      fixture: {
        fixtureId: options.fixtureId || defaultFixtureId,
        status: preflight.status,
        failures: preflight.failures,
      },
    });
```

Inside the successful lifecycle block, after `const counts = assertLifecycleSessionState(reflected.session, plan);`, add:

```js
    const firstHandoff = firstHandoffSuccess({
      savedItemCount: protocol.itemIds.length,
      readFeedbackItemCount: queueStats.itemCount,
      readFeedbackSentCount: queueStats.sentCount,
    });
```

Then include `firstHandoff` in `fixtureResult`:

```js
    Object.assign(fixtureResult, {
      status: "pass",
      savedItemCount: protocol.itemIds.length,
      readFeedbackItemCount: queueStats.itemCount,
      readFeedbackSentCount: queueStats.sentCount,
      firstHandoff,
      ...counts,
    });
```

In the `catch (error)` block, replace the single push with:

```js
    const failureCode = categorizeFirstHandoffFailure(error);
    fixtureResult.firstHandoff = firstHandoffFailure({
      failureCode,
      message: error.message,
      details: { fixtureId: fixture.id },
    });
    fixtureResult.failures.push(error.message);
```

In the final `buildReport` call, pass through the fixture first handoff:

```js
    firstHandoff: fixtureResult.firstHandoff,
```

- [ ] **Step 5: Run tests to verify pass**

Run:

```bash
npm run agent-loop:smoke:test
```

Expected: PASS.

- [ ] **Step 6: Run non-strict smoke without requiring Android success**

Run:

```bash
npm run agent-loop:smoke
```

Expected on machines without Android runtime: command exits 0, report status is `deferred`, and `build/reports/fixthis-agent-loop/report.json` includes `firstHandoff.failureCode`.

Expected on machines with Android runtime: command may pass or fail on real app/runtime state; if it fails, the report includes `firstHandoff.failureCode`, `readiness`, and `nextAction`.

- [ ] **Step 7: Commit**

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs
git commit -m "feat: classify first handoff smoke failures"
```

## Task 4: CLI Next-Action Agreement Tests

**Files:**
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt`
- Modify: `fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt`
- Modify if tests fail: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt`
- Modify if tests fail: `fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt`

- [ ] **Step 1: Add failing or confirming CLI agreement tests**

Append this test to `DoctorCommandTest.kt`:

```kotlin
    @Test
    fun doctorDeviceBlockedNextActionMatchesFirstHandoffRecoveryWording() {
        val readiness = readinessForDoctorCheck(
            name = "device_connected",
            message = "No connected Android device or emulator found",
            fix = "Start an emulator or connect a device, then run `adb devices`.",
        )

        assertEquals(FirstRunReadinessState.DEVICE_BLOCKED, readiness.state)
        assertEquals(
            "Start an emulator or connect a device, then run `adb devices`.",
            readiness.nextAction,
        )
        assertEquals(readiness.fix, readiness.nextAction)
    }
```

Append this test to `InstallAgentJsonReportTest.kt`:

```kotlin
    @Test
    fun successfulInstallAgentJsonPointsAtDoctorBeforeConsole() {
        val rendered = InstallAgentJsonReport.render(
            applied = listOf(
                InstallAgentJsonReport.Applied("claude", "/repo/.claude/settings.json", "project-local"),
                InstallAgentJsonReport.Applied("gradle-plugin", "/repo/app/build.gradle.kts", "project-local"),
            ),
            skipped = emptyList(),
            errors = emptyList(),
            next = listOf(
                "fixthis doctor --project-dir /repo --json",
                "# Restart Claude Code / Codex to reload MCP config",
                "fixthis_open_feedback_console",
            ),
            readiness = FirstRunReadinessCatalog.configRecoverable(
                cause = "FixThis setup completed; verify the debug app before opening the console.",
            ).copy(
                verify = "fixthis doctor --project-dir /repo --json",
                fix = "Run doctor, restart Claude Code or Codex if MCP config changed, then open the feedback console.",
                nextAction = "fixthis doctor --project-dir /repo --json",
            ),
            restartRequired = true,
        )

        val obj = Json.parseToJsonElement(rendered).jsonObject
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("nextAction").jsonPrimitive.content,
        )
        assertEquals(
            "fixthis doctor --project-dir /repo --json",
            obj.getValue("readiness").jsonObject
                .getValue("nextAction").jsonPrimitive.content,
        )
    }
```

- [ ] **Step 2: Run focused CLI tests**

Run:

```bash
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --tests "*AgentSetupFilesTest" --no-daemon
```

Expected: PASS. If a test fails because current production wording differs, update only the production readiness copy in `SetupCommand.kt` or `DoctorCommand.kt` to match the recovery wording in the tests.

- [ ] **Step 3: Commit**

```bash
git add fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/SetupCommand.kt fixthis-cli/src/main/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommand.kt
git commit -m "test: guard first handoff next actions"
```

If `SetupCommand.kt` and `DoctorCommand.kt` were not modified, leave them out of `git add`.

## Task 5: Release Readiness And Gate Wiring

**Files:**
- Modify: `docs/contributing/release-readiness.md`
- Modify: `scripts/check-release-readiness.mjs`
- Modify: `scripts/release-gate.mjs`
- Modify: `scripts/release-gate-test.mjs`

- [ ] **Step 1: Add failing release-readiness tests**

In `scripts/check-release-readiness.mjs`, after rule `R42.required-checks-observation-snapshot`, add:

```js
requireIncludes(
  'R43.external-first-handoff-recovery-section',
  'docs/contributing/release-readiness.md',
  '## External App First Handoff Recovery',
);
requireIncludes(
  'R44.external-first-handoff-strict-command',
  'docs/contributing/release-readiness.md',
  '`npm run agent-loop:smoke -- --strict`',
);
requireIncludes(
  'R45.external-first-handoff-deferral-rule',
  'docs/contributing/release-readiness.md',
  'Non-strict missing-runtime runs must be recorded as deferred with a recovery-oriented readiness object.',
);
```

In `scripts/release-gate-test.mjs`, add this assertion to the first test `releaseGateSteps include Trust Loop source agent release drift and console evidence`:

```js
  assert.ok(commands.includes('npm run agent-loop:smoke:test'));
```

Append this new test near the other claim mapping tests:

```js
test('release gate maps external first handoff recovery claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-09T00:00:00.000Z',
    steps: [
      { name: 'Agent loop smoke contracts', command: 'npm run agent-loop:smoke:test', status: 'pass' },
      { name: 'Agent loop smoke', command: 'npm run agent-loop:smoke -- --strict', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-first-handoff-recovery'), {
    id: 'external-first-handoff-recovery',
    status: 'deferred',
    evidence: ['Agent loop smoke contracts', 'Agent loop smoke'],
    reason: 'Android SDK unavailable',
  });
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
node scripts/check-release-readiness.mjs
npm run release:gate:test
```

Expected: `check-release-readiness` fails on missing R43/R44/R45, and `release:gate:test` fails because `external-first-handoff-recovery` is not in `releaseClaimDefinitions`.

- [ ] **Step 3: Add release-readiness docs**

In `docs/contributing/release-readiness.md`, add this section after `## v1.1 Trust Loop Evidence` or before the residual-risk closure sections:

```markdown
## External App First Handoff Recovery

The first external-app handoff recovery line may be claimed only when the
release commit has evidence that an external debug Compose app can move from
setup through one agent-readable sent feedback item, or that the report records
the exact recovery action when runtime prerequisites are missing.

| Claim | Required evidence |
| --- | --- |
| External app first handoff reaches one MCP-readable sent item, and failure reports carry `failureCode`, `readiness`, `nextAction`, `verify`, and `fix`. | `npm run agent-loop:smoke:test` and `npm run agent-loop:smoke -- --strict`. |
| Install and doctor JSON do not contradict the first handoff recovery path. | `./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --tests "*AgentSetupFilesTest" --no-daemon`. |

Non-strict missing-runtime runs must be recorded as deferred with a
recovery-oriented readiness object. Strict connected smoke must fail when
Android SDK, ADB, a ready emulator/device, or the launched debug app is
unavailable.
```

- [ ] **Step 4: Add release gate claim**

In `scripts/release-gate.mjs`, add this object after the `external-agent-loop` claim:

```js
  { id: 'external-first-handoff-recovery', evidenceNames: ['Agent loop smoke contracts', 'Agent loop smoke'] },
```

- [ ] **Step 5: Run release tests to verify pass**

Run:

```bash
node scripts/check-release-readiness.mjs
npm run release:gate:test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add docs/contributing/release-readiness.md scripts/check-release-readiness.mjs scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "docs: wire first handoff recovery evidence"
```

## Task 6: Final Verification And Graph Refresh

**Files:**
- No code files should be edited in this task unless verification exposes a defect introduced by Tasks 1-5.

- [ ] **Step 1: Run fast script and CLI checks**

Run:

```bash
npm run agent-loop:smoke:test
npm run first-run:smoke:test
./gradlew :fixthis-cli:test --tests "*DoctorCommandTest" --tests "*InstallAgentJsonReportTest" --tests "*AgentSetupFilesTest" --no-daemon
```

Expected: all commands PASS.

- [ ] **Step 2: Run release alignment checks**

Run:

```bash
npm run release:gate:test
node scripts/check-release-readiness.mjs
npm run evidence:trust -- --dry-run
```

Expected: all commands PASS. The dry-run prints planned trust commands without running connected Android evidence.

- [ ] **Step 3: Run non-strict smoke and inspect report shape**

Run:

```bash
npm run agent-loop:smoke
node -e 'const r=require("./build/reports/fixthis-agent-loop/report.json"); if (!r.firstHandoff || !r.firstHandoff.status) process.exit(1); console.log(r.status, r.firstHandoff.status, r.firstHandoff.failureCode || "no-failure");'
```

Expected without Android runtime: first command exits 0, the Node command prints a line such as `deferred deferred device_unavailable`.

Expected with Android runtime: first command may print `pass pass no-failure` when the connected fixture succeeds. If it fails for a real runtime reason, the report must still contain `firstHandoff.status`, `failureCode`, `readiness`, and `nextAction`.

- [ ] **Step 4: Run docs and whitespace checks**

Run:

```bash
node scripts/check-doc-consistency.mjs
git diff --check
```

Expected: both PASS.

- [ ] **Step 5: Refresh Graphify**

Run:

```bash
graphify update .
```

Expected: graph refresh completes. Only ignored `graphify-out/` files should change.

- [ ] **Step 6: Inspect git status**

Run:

```bash
git status --short
```

Expected: only intentional tracked files from Tasks 1-5 are modified, and no `.fixthis/`, `graphify-out/`, `build/`, screenshots, reports, fixture workspaces, or Android build outputs are staged.

- [ ] **Step 7: Commit verification fixes if needed**

If Step 1-6 exposed a small fix, apply the fix, rerun the failed command, then commit:

```bash
git add scripts/agent-loop-smoke.mjs scripts/agent-loop-smoke-test.mjs fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/DoctorCommandTest.kt fixthis-cli/src/test/kotlin/io/github/beyondwin/fixthis/cli/commands/InstallAgentJsonReportTest.kt docs/contributing/release-readiness.md scripts/check-release-readiness.mjs scripts/release-gate.mjs scripts/release-gate-test.mjs
git commit -m "fix: close first handoff recovery verification gaps"
```

Do not create this commit when no verification fix was needed.

## Self-Review Checklist

- Spec coverage: Tasks 1-3 implement first handoff report status, failure codes, readiness, next action, strict/non-strict behavior, Save to MCP plus `fixthis_read_feedback` proof, and lifecycle separation. Task 4 covers CLI next-action agreement. Task 5 wires release-readiness and release-gate documentation. Task 6 covers final verification, docs hygiene, and graph refresh.
- Red-flag scan: this plan contains no unresolved fill slot, no deferred implementation slot, and no angle-bracket command token.
- Type consistency: JavaScript helpers use `firstHandoff`, `failureCode`, `readiness`, `nextAction`, `savedItemCount`, `readFeedbackItemCount`, and `readFeedbackSentCount` consistently across tests, report building, and runtime integration.
