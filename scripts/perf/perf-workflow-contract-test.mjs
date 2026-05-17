import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const workflow = fs.readFileSync(path.join(repoRoot, ".github/workflows/perf-report.yml"), "utf8");

test("perf compare step preserves compare-perf exit code through tee", () => {
  const compareStep = workflow.match(/- name: Compare against committed baseline[\s\S]*?(?=\n      - name:)/);
  assert.ok(compareStep, "Compare against committed baseline step not found");
  const body = compareStep[0];

  assert.match(body, /shell:\s*bash/);
  assert.match(body, /set -o pipefail/);
  assert.match(body, /compare_status=0/);
  assert.match(body, /compare_status=\$\?$/m);
  assert.match(body, /echo "exit=\$compare_status" >> "\$GITHUB_OUTPUT"/);
  assert.match(body, /continue-on-error:\s*true/);
});
