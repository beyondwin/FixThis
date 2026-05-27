import assert from "node:assert/strict";
import test from "node:test";
import {
  assertCopiedPrompt,
  buildReport,
  parseArgs,
  renderMarkdownReport,
  selectRuntimeFixtures,
  statusForEnvironment,
  summarizeApps,
} from "./real-copy-prompt-smoke.mjs";

const manifest = {
  fixtures: [
    {
      id: "reply",
      applicationId: "com.example.reply",
      cases: [{ id: "reply-runtime", mode: "runtime-trust", runtimeTarget: { text: "Inbox" } }],
    },
    {
      id: "jetsnack",
      applicationId: "com.example.jetsnack",
      cases: [{ id: "jetsnack-runtime", mode: "runtime-trust", runtimeTarget: { text: "Home" } }],
    },
    {
      id: "fixthis-sample",
      applicationId: "io.github.beyondwin.fixthis.sample",
      cases: [{ id: "sample-runtime", mode: "runtime-trust", runtimeTarget: { text: "FixThis" } }],
    },
    {
      id: "nowinandroid",
      applicationId: "com.google.samples.apps.nowinandroid",
      cases: [{ id: "nia-source", mode: "source-index", expectedTop1PathContains: "Topic" }],
    },
  ],
};

test("parseArgs supports strict, fixture subset, report dir, and headed mode", () => {
  assert.deepEqual(
    parseArgs(["--fixtures", "reply,jetsnack", "--strict", "--report-dir", "out/report", "--headed"]),
    {
      strict: true,
      fixtureIds: ["reply", "jetsnack"],
      reportDir: "out/report",
      headed: true,
    },
  );
});

test("selectRuntimeFixtures defaults to the three real copy-prompt apps", () => {
  const selected = selectRuntimeFixtures(manifest, []);
  assert.deepEqual(selected.map((fixture) => fixture.id), ["reply", "jetsnack", "fixthis-sample"]);
});

test("selectRuntimeFixtures rejects source-index-only and unknown ids", () => {
  assert.throws(
    () => selectRuntimeFixtures(manifest, ["nowinandroid"]),
    /nowinandroid is not an installable runtime Copy Prompt fixture/,
  );
  assert.throws(() => selectRuntimeFixtures(manifest, ["missing"]), /Unknown fixture id: missing/);
});

test("assertCopiedPrompt checks comments, id lines, quality, and agent protocol", () => {
  const markdown = [
    "agent_protocol: fixthis-feedback/v1",
    "Handoff quality: high",
    "- id: ann-1",
    "  comment: Reply copy prompt smoke annotation 1",
    "- id: ann-2",
    "  comment: Reply copy prompt smoke annotation 2",
  ].join("\n");

  const result = assertCopiedPrompt(markdown, [
    "Reply copy prompt smoke annotation 1",
    "Reply copy prompt smoke annotation 2",
  ]);

  assert.equal(result.idLineCount, 2);
  assert.equal(result.promptChars, markdown.length);
});

test("assertCopiedPrompt reports every missing marker", () => {
  assert.throws(
    () => assertCopiedPrompt("comment only", ["expected comment"]),
    /missing comment: expected comment.*expected at least 2 id lines.*missing Handoff quality.*missing agent_protocol/s,
  );
});

test("summarizeApps and buildReport aggregate pass fail and deferred rows", () => {
  const apps = [
    { fixtureId: "reply", status: "pass" },
    { fixtureId: "jetsnack", status: "fail", failures: ["preview_timeout"] },
    { fixtureId: "fixthis-sample", status: "deferred" },
  ];

  assert.deepEqual(summarizeApps(apps), {
    totalApps: 3,
    passedApps: 1,
    failedApps: 1,
    deferredApps: 1,
  });

  const report = buildReport({
    strict: false,
    device: "emulator-5554",
    startedAt: "2026-05-27T00:00:00.000Z",
    finishedAt: "2026-05-27T00:01:00.000Z",
    apps,
  });

  assert.equal(report.status, "fail");
  assert.equal(report.failures[0], "jetsnack: preview_timeout");
});

test("statusForEnvironment distinguishes strict failure from non-strict deferral", () => {
  assert.deepEqual(statusForEnvironment({ strict: false, androidReady: false, reason: "No connected Android device." }), {
    status: "deferred",
    exitCode: 0,
    failures: ["No connected Android device."],
  });
  assert.deepEqual(statusForEnvironment({ strict: true, androidReady: false, reason: "No connected Android device." }), {
    status: "fail",
    exitCode: 1,
    failures: ["No connected Android device."],
  });
});

test("renderMarkdownReport includes app evidence rows", () => {
  const text = renderMarkdownReport({
    status: "pass",
    startedAt: "2026-05-27T00:00:00.000Z",
    finishedAt: "2026-05-27T00:01:00.000Z",
    strict: true,
    device: "emulator-5554",
    summary: { totalApps: 1, passedApps: 1, failedApps: 0, deferredApps: 0 },
    apps: [
      {
        fixtureId: "reply",
        packageName: "com.example.reply",
        status: "pass",
        itemCount: 2,
        idLineCount: 2,
        handedOffCount: 2,
        promptPath: "artifacts/reply-prompt.md",
      },
    ],
    failures: [],
  });

  assert.match(text, /# FixThis Real Copy Prompt Smoke/);
  assert.match(text, /\| reply \| com\.example\.reply \| pass \| 2 \| 2 \| 2 \| artifacts\/reply-prompt\.md \|/);
});
