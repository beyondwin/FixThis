import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

export function runNodeScenario(scenario, { repoRoot = process.cwd(), log = console.error } = {}) {
  const groups = JSON.parse(fs.readFileSync(path.join(repoRoot, "scripts/console-tests.json"), "utf8"));
  const files = [];
  for (const g of scenario.groups) {
    if (!(g in groups)) throw new Error(`unknown console group: ${g}`);
    files.push(...groups[g]);
  }
  const samples_ms = [];
  for (let i = 0; i < scenario.iterations + 1; i += 1) {
    log(`[bench-node] iter ${i}: node --test (${files.length} files)`);
    const start = process.hrtime.bigint();
    const run = spawnSync(process.execPath, ["--test", ...files], { cwd: repoRoot, stdio: "inherit" });
    const elapsed = Number((process.hrtime.bigint() - start) / 1_000_000n);
    if (run.status !== 0) throw new Error(`node --test failed: status=${run.status}`);
    samples_ms.push(elapsed);
    log(`[bench-node] iter ${i}: ${elapsed} ms`);
  }
  return samples_ms;
}
