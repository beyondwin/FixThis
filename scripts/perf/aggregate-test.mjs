import { test } from "node:test";
import assert from "node:assert/strict";
import { aggregate } from "./aggregate.mjs";

test("aggregate drops first sample as warmup", () => {
  const r = aggregate([1000, 100, 110, 105, 115], { dropWarmup: true });
  assert.equal(r.dropped.includes("warmup:1000"), true);
  assert.ok(r.median_ms < 200, `median should ignore warmup, got ${r.median_ms}`);
});

test("aggregate drops min and max when N-1 >= 4", () => {
  const r = aggregate([100, 200, 300, 400, 500, 600], { dropWarmup: true });
  // After warmup drop: [200,300,400,500,600], N=5 → drop min(200) and max(600)
  // Remaining: [300,400,500] → median = 400
  assert.equal(r.median_ms, 400);
  assert.ok(r.dropped.some((d) => d.startsWith("min:")), `expected min drop in ${r.dropped}`);
  assert.ok(r.dropped.some((d) => d.startsWith("max:")), `expected max drop in ${r.dropped}`);
});

test("aggregate does not drop min/max when N-1 < 4", () => {
  const r = aggregate([1000, 100, 200, 300], { dropWarmup: true });
  // After warmup drop: [100,200,300], N=3 → no min/max drop
  assert.equal(r.median_ms, 200);
  assert.equal(r.dropped.filter((d) => d.startsWith("min:") || d.startsWith("max:")).length, 0);
});

test("aggregate reports p95 by linear interpolation", () => {
  const r = aggregate([100, 100, 100, 100, 100, 100], { dropWarmup: true });
  assert.equal(r.p95_ms, 100);
});

test("aggregate reports stddev with Bessel correction", () => {
  const r = aggregate([0, 100, 200, 300, 400], { dropWarmup: true });
  // After warmup drop: [100,200,300,400], N=4 → no min/max drop
  // mean = 250, stddev = sqrt((150^2 + 50^2 + 50^2 + 150^2) / 3) ≈ 129.10
  assert.ok(Math.abs(r.stddev_ms - 129.10) < 0.5, `stddev=${r.stddev_ms}`);
});

test("aggregate throws if no samples remain after drops", () => {
  assert.throws(() => aggregate([100], { dropWarmup: true }), /no samples/i);
});
