import { test } from "node:test";
import assert from "node:assert/strict";
import { consecutiveSuccesses, summarizeWorkflow } from "./required-checks-observation.mjs";

const success = (createdAt = "2026-05-17T00:00:00Z") => ({
  status: "completed",
  conclusion: "success",
  createdAt,
  url: "https://example.test/success",
});
const failure = () => ({
  status: "completed",
  conclusion: "failure",
  createdAt: "2026-05-16T00:00:00Z",
  url: "https://example.test/failure",
});
const pending = () => ({
  status: "in_progress",
  conclusion: null,
  createdAt: "2026-05-18T00:00:00Z",
  url: "https://example.test/pending",
});

test("consecutiveSuccesses ignores pending latest runs and stops at failure", () => {
  assert.equal(consecutiveSuccesses([pending(), success(), success(), failure(), success()]), 2);
});

test("connected tests require fourteen consecutive green completed runs", () => {
  const runs = Array.from({ length: 14 }, (_, index) => success(`2026-05-${17 - index}T00:00:00Z`));
  const summary = summarizeWorkflow(
    ".github/workflows/connected-tests.yml",
    runs,
    { requiredSuccesses: 14 },
  );
  assert.equal(summary.ready, true);
  assert.equal(summary.consecutiveGreenCompletedRunsFromLatest, 14);
});

test("compatibility is not ready before its required window", () => {
  const summary = summarizeWorkflow(
    ".github/workflows/nightly-compat.yml",
    [failure(), success()],
    { requiredSuccesses: 1 },
  );
  assert.equal(summary.ready, false);
});
