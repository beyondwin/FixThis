import assert from "node:assert/strict";
import test from "node:test";
import {
  annotationDragPoints,
  assertCopiedPromptProtocol,
  assertLifecycleSessionState,
  assertReadFeedbackQueueContains,
  assertVerifyReportReadyForMcpTooling,
  assertVerifyReportAutopilotContract,
  autopilotEvidenceForVerifyReport,
  buildReport,
  categorizeFeedbackLifecycleFailure,
  categorizeFirstHandoffFailure,
  combineVisibleAnnotationStatusText,
  expectedResolutionPlan,
  firstHandoffFailure,
  firstHandoffForEnvironment,
  firstHandoffSuccess,
  handoffLifecycleActionOrder,
  parseArgs,
  renderMarkdownReport,
  selectResolutionItemIds,
  selectAgentLoopFixture,
  statusForEnvironment,
} from "./agent-loop-smoke.mjs";

const manifest = {
  fixtures: [
    {
      id: "reply",
      applicationId: "com.example.reply",
      cases: [{ id: "reply-runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
    },
    {
      id: "fixthis-sample",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{ id: "sample-runtime", mode: "runtime-trust", runtimeTarget: { text: "FixThis" } }],
    },
  ],
};

test("parseArgs supports strict fixture report dir and headed mode", () => {
  assert.deepEqual(parseArgs(["--strict", "--fixture", "fixthis-sample", "--report-dir", "out/agent-loop", "--headed"]), {
    strict: true,
    fixtureId: "fixthis-sample",
    reportDir: "out/agent-loop",
    headed: true,
  });
});

test("selectAgentLoopFixture defaults to Reply and rejects source-only fixtures", () => {
  assert.equal(selectAgentLoopFixture(manifest, null).id, "reply");
  assert.equal(selectAgentLoopFixture(manifest, "fixthis-sample").id, "fixthis-sample");
  assert.throws(() => selectAgentLoopFixture({ fixtures: [{ id: "source-only", cases: [{ mode: "source-index" }] }] }, "source-only"), /not an installable runtime agent-loop fixture/);
});

test("expectedResolutionPlan covers terminal and clarification statuses", () => {
  assert.deepEqual(expectedResolutionPlan(["item-1", "item-2", "item-3"]), [
    { itemId: "item-1", status: "resolved", summary: "Agent loop smoke resolved item-1" },
    { itemId: "item-2", status: "needs_clarification", summary: "Agent loop smoke needs clarification for item-2" },
    { itemId: "item-3", status: "wont_fix", summary: "Agent loop smoke will not fix item-3" },
  ]);
});

test("selectResolutionItemIds resolves saved session items from noisy prompt ids", () => {
  assert.deepEqual(
    selectResolutionItemIds(["source-candidate-1", "item-2", "item-3"], {
      items: [{ itemId: "item-1" }, { itemId: "item-2" }, { itemId: "item-3" }],
    }),
    ["item-2", "item-3"],
  );
  assert.deepEqual(
    selectResolutionItemIds(["source-candidate-1"], {
      items: [{ itemId: "item-1" }, { itemId: "item-2" }, { itemId: "item-3" }],
    }),
    ["item-1", "item-2", "item-3"],
  );
});


test("annotationDragPoints uses stable anchors and clamps drag endpoints", () => {
  const box = { x: 10, y: 20, width: 100, height: 80 };

  assert.deepEqual(annotationDragPoints(box, 0), {
    startX: 30,
    startY: 37.6,
    endX: 56,
    endY: 52,
  });
  assert.deepEqual(annotationDragPoints(box, 1), {
    startX: 65,
    startY: 69.6,
    endX: 91,
    endY: 84,
  });
  assert.deepEqual(annotationDragPoints({ x: 0, y: 0, width: 100, height: 100 }, 99), {
    startX: 25,
    startY: 25,
    endX: 51,
    endY: 43,
  });
});

test("handoff lifecycle copies prompt before saving to MCP", () => {
  assert.deepEqual(handoffLifecycleActionOrder(), ["copy-prompt", "save-to-mcp"]);
});

test("combineVisibleAnnotationStatusText reads rendered saved and pending containers", () => {
  const text = combineVisibleAnnotationStatusText([
    { id: "pendingItems", hidden: true, textContent: "stale pending text" },
    { id: "draftItems", hidden: false, textContent: "Resolved Needs Clarification Won't Fix" },
    { id: "pendingItems", hidden: false, textContent: "Open annotation" },
  ]);

  assert.equal(text, "Resolved Needs Clarification Won't Fix Open annotation");
});

test("assertCopiedPromptProtocol requires session id item ids and agent protocol", () => {
  const prompt = [
    "session_id: session-1",
    "- id: item-1",
    "- id: item-2",
    "agent_protocol: fixthis-feedback/v1",
  ].join("\n");

  assert.deepEqual(assertCopiedPromptProtocol(prompt), {
    sessionId: "session-1",
    itemIds: ["item-1", "item-2"],
  });

  assert.throws(() => assertCopiedPromptProtocol("id: item-1"), /missing session_id.*missing agent_protocol/s);
});

test("assertLifecycleSessionState validates item statuses and summaries", () => {
  const session = {
    items: [
      { itemId: "item-1", status: "resolved", agentSummary: "Agent loop smoke resolved item-1" },
      { itemId: "item-2", status: "needs_clarification", agentSummary: "Agent loop smoke needs clarification for item-2" },
      { itemId: "item-3", status: "wont_fix", agentSummary: "Agent loop smoke will not fix item-3" },
    ],
  };
  const plan = expectedResolutionPlan(["item-1", "item-2", "item-3"]);

  assert.deepEqual(assertLifecycleSessionState(session, plan), {
    resolved: 1,
    needsClarification: 1,
    wontFix: 1,
  });
});

test("assertReadFeedbackQueueContains verifies saved MCP item ids before claim", () => {
  const queue = {
    items: [
      { itemId: "item-1", delivery: "sent", status: "open" },
      { itemId: "item-2", delivery: "sent", status: "open" },
    ],
  };

  assertReadFeedbackQueueContains(queue, ["item-1", "item-2"]);
  assert.throws(
    () => assertReadFeedbackQueueContains(queue, ["item-1", "item-404"]),
    /read_feedback_missing_items: item-404/,
  );
});

test("environment status distinguishes strict failure and non-strict deferral", () => {
  assert.deepEqual(statusForEnvironment({ strict: false, androidReady: false, reason: "No Android device" }), {
    status: "deferred",
    exitCode: 0,
    failures: ["No Android device"],
  });
  assert.deepEqual(statusForEnvironment({ strict: true, androidReady: false, reason: "No Android device" }), {
    status: "fail",
    exitCode: 1,
    failures: ["No Android device"],
  });
});

test("verify report autopilot contract allows MCP only when ready", () => {
  const report = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: false,
    readyForMcpTooling: true,
    actions: [
      {
        id: "open-feedback-console",
        actor: "agent",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  assert.deepEqual(assertVerifyReportAutopilotContract(report), {
    readyForMcpTooling: true,
    blockedByUser: false,
    openConsoleActor: "agent",
    runnableCommandCount: 0,
  });
  assert.deepEqual(autopilotEvidenceForVerifyReport(report), {
    status: "pass",
    readyForMcpTooling: true,
    requiresUserAction: false,
    openConsoleActor: "agent",
    actionCount: 1,
  });
});

test("verify report autopilot contract blocks current MCP call after restart-required setup", () => {
  const report = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: true,
    readyForMcpTooling: false,
    actions: [
      {
        id: "restart-agent",
        actor: "user",
        kind: "manual",
        reason: "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
        blocksProgress: true,
      },
      {
        id: "open-feedback-console",
        actor: "agent_after_restart",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  const summary = assertVerifyReportAutopilotContract(report);
  assert.equal(summary.readyForMcpTooling, false);
  assert.equal(summary.blockedByUser, true);
  assert.equal(summary.openConsoleActor, "agent_after_restart");
});

test("verify report autopilot contract rejects unsafe console action", () => {
  assert.throws(
    () => assertVerifyReportAutopilotContract({
      schemaVersion: "1.1",
      readiness: { state: "READY" },
      requiresUserAction: false,
      readyForMcpTooling: false,
      actions: [
        {
          id: "open-feedback-console",
          actor: "agent",
          kind: "mcp_tool",
          tool: "fixthis_open_feedback_console",
          reason: "Open FixThis Studio.",
          blocksProgress: false,
        },
      ],
    }),
    /readyForMcpTooling=false.*agent console action/,
  );
});

test("verify report console gate requires MCP tooling readiness", () => {
  const readyReport = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: false,
    readyForMcpTooling: true,
    actions: [
      {
        id: "open-feedback-console",
        actor: "agent",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio.",
        blocksProgress: false,
      },
    ],
  };
  const restartRequiredReport = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: true,
    readyForMcpTooling: false,
    actions: [
      {
        id: "restart-agent",
        actor: "user",
        kind: "manual",
        reason: "Restart Claude Code or Codex so the new FixThis MCP config is loaded.",
        blocksProgress: true,
      },
      {
        id: "open-feedback-console",
        actor: "agent_after_restart",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  assert.deepEqual(assertVerifyReportReadyForMcpTooling(readyReport), {
    readyForMcpTooling: true,
    blockedByUser: false,
    openConsoleActor: "agent",
    runnableCommandCount: 0,
  });
  assert.throws(
    () => assertVerifyReportReadyForMcpTooling(restartRequiredReport),
    /readyForMcpTooling=false.*fixthis_open_feedback_console/,
  );
});

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
    autopilot: {
      status: "pass",
      readyForMcpTooling: true,
      requiresUserAction: false,
      openConsoleActor: "agent",
      actionCount: 1,
    },
  }), {
    status: "pass",
    savedItemCount: 3,
    readFeedbackItemCount: 4,
    readFeedbackSentCount: 3,
    autopilot: {
      status: "pass",
      readyForMcpTooling: true,
      requiresUserAction: false,
      openConsoleActor: "agent",
      actionCount: 1,
    },
  });
});

test("categorizeFeedbackLifecycleFailure labels lifecycle gate failures", () => {
  assert.equal(
    categorizeFeedbackLifecycleFailure("claim", new Error("Unknown feedback item: item-404")),
    "claim_item_not_found",
  );
  assert.equal(
    categorizeFeedbackLifecycleFailure("claim", new Error("ITEM_ALREADY_RESOLVED: Cannot claim resolved feedback item")),
    "claim_item_already_resolved",
  );
  assert.equal(
    categorizeFeedbackLifecycleFailure("resolve", new Error("Unsupported feedback resolution status: closed")),
    "resolve_invalid_status",
  );
  assert.equal(
    categorizeFeedbackLifecycleFailure("resolve", new Error("Unknown feedback session: session-old")),
    "resolve_stale_session",
  );
  assert.equal(
    categorizeFeedbackLifecycleFailure("resolve", new Error("Bridge request timed out while waiting for response")),
    "resolve_transport_failure",
  );
});

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
    categorizeFirstHandoffFailure(new Error("Expected at least 2 annotation pins after drag")),
    "annotation_unavailable",
  );
  assert.equal(
    categorizeFirstHandoffFailure(new Error("Pending annotation comment did not persist for Agent loop smoke")),
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
      autopilot: {
        status: "pass",
        readyForMcpTooling: true,
        requiresUserAction: false,
        openConsoleActor: "agent",
        actionCount: 1,
      },
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
  assert.match(markdown, /- Autopilot: pass/);
  assert.match(markdown, /- Autopilot readyForMcpTooling: true/);
  assert.match(markdown, /\| reply \| com\.example\.reply \| pass \| 3 \| 3 \| 3 \| 1 \| 1 \| 1 \|/);
});

test("buildReport attaches autopilot evidence from runtime verify report", () => {
  const verifyReport = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: false,
    readyForMcpTooling: true,
    actions: [
      {
        id: "open-feedback-console",
        actor: "agent",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-06-09T00:00:00.000Z",
    finishedAt: "2026-06-09T00:01:00.000Z",
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "pass",
      savedItemCount: 3,
      readFeedbackItemCount: 3,
      readFeedbackSentCount: 3,
      verifyReport,
    },
  });

  assert.deepEqual(report.firstHandoff.autopilot, autopilotEvidenceForVerifyReport(verifyReport));
  assert.match(renderMarkdownReport(report), /- Autopilot open console actor: agent/);
});

test("buildReport keeps autopilot evidence when explicit first handoff is a failure", () => {
  const verifyReport = {
    schemaVersion: "1.1",
    readiness: { state: "READY" },
    requiresUserAction: false,
    readyForMcpTooling: true,
    actions: [
      {
        id: "open-feedback-console",
        actor: "agent",
        kind: "mcp_tool",
        tool: "fixthis_open_feedback_console",
        reason: "Open FixThis Studio after setup verification succeeds.",
        blocksProgress: false,
      },
    ],
  };

  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-06-09T00:00:00.000Z",
    finishedAt: "2026-06-09T00:01:00.000Z",
    firstHandoff: firstHandoffFailure({
      failureCode: "preview_capture_unavailable",
      message: "Snapshot image is not visible",
      details: { fixtureId: "reply" },
    }),
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "fail",
      failures: ["Snapshot image is not visible"],
      verifyReport,
    },
  });

  assert.equal(report.firstHandoff.status, "fail");
  assert.equal(report.firstHandoff.failureCode, "preview_capture_unavailable");
  assert.deepEqual(report.firstHandoff.autopilot, autopilotEvidenceForVerifyReport(verifyReport));
  assert.match(renderMarkdownReport(report), /- Autopilot open console actor: agent/);
});

test("buildReport fails when lifecycle assertions fail", () => {
  const report = buildReport({
    strict: true,
    device: "emulator-5554",
    startedAt: "2026-05-31T00:00:00.000Z",
    finishedAt: "2026-05-31T00:01:00.000Z",
    fixture: {
      fixtureId: "reply",
      packageName: "com.example.reply",
      status: "fail",
      failures: ["item-1 status expected resolved but was in_progress"],
    },
  });

  assert.equal(report.status, "fail");
  assert.match(renderMarkdownReport(report), /item-1 status expected resolved/);
});

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
