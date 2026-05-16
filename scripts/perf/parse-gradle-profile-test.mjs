import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseGradleProfile } from "./parse-gradle-profile.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const fixture = fs.readFileSync(path.join(here, "__fixtures__/profile-2026-05-16-baseline.html"), "utf8");

test("parseGradleProfile returns total wall-clock ms", () => {
  const result = parseGradleProfile(fixture);
  assert.equal(typeof result.totalMs, "number");
  assert.ok(result.totalMs > 0, `totalMs should be > 0, got ${result.totalMs}`);
});

test("parseGradleProfile returns ISO start timestamp", () => {
  const result = parseGradleProfile(fixture);
  assert.match(result.startedAt, /^\d{4}-\d{2}-\d{2}T/);
});

test("parseGradleProfile throws on empty input", () => {
  assert.throws(() => parseGradleProfile(""), /empty|no profile|invalid/i);
});

test("parseGradleProfile throws when Total Build Time row missing", () => {
  assert.throws(
    () => parseGradleProfile("<html><body>no profile here</body></html>"),
    /total build time/i,
  );
});
