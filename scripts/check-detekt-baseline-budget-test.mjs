import { test } from "node:test";
import assert from "node:assert/strict";
import { countBaselineIds, evaluateBudgets } from "./check-detekt-baseline-budget.mjs";

test("countBaselineIds counts detekt ID entries", () => {
  assert.equal(countBaselineIds("<SmellBaseline><ID>a</ID><ID>b</ID></SmellBaseline>"), 2);
});

test("evaluateBudgets marks growth as over budget", () => {
  const results = evaluateBudgets(
    { "baseline.xml": 1 },
    () => "<SmellBaseline><ID>a</ID><ID>b</ID></SmellBaseline>",
  );
  assert.deepEqual(results[0], {
    file: "baseline.xml",
    budget: 1,
    count: 2,
    status: "over",
  });
});

test("evaluateBudgets marks lower counts as improved", () => {
  const results = evaluateBudgets(
    { "baseline.xml": 2 },
    () => "<SmellBaseline><ID>a</ID></SmellBaseline>",
  );
  assert.equal(results[0].status, "improved");
});
