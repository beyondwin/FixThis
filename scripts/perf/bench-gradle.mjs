import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { parseGradleProfile } from "./parse-gradle-profile.mjs";

const PROFILE_DIR = "build/reports/profile";

export function runGradleScenario(scenario, { repoRoot = process.cwd(), log = console.error } = {}) {
  const samples_ms = [];
  for (let i = 0; i < scenario.iterations + 1; i += 1) {
    if (scenario.mode === "cold") {
      log(`[bench-gradle] iter ${i}: clean`);
      const clean = spawnSync("./gradlew", ["clean", "--quiet"], { cwd: repoRoot, stdio: "inherit" });
      if (clean.status !== 0) throw new Error(`clean failed: status=${clean.status}`);
    }
    log(`[bench-gradle] iter ${i}: ${scenario.command.join(" ")}`);
    const before = listProfileFiles(repoRoot);
    // Run with --no-daemon so we measure the same startup + first-task cost
    // contributors hit via scripts/verify-ci-local.sh and the pre-push hook.
    const args = [...scenario.command, "--profile", "--no-daemon"];
    if (scenario.mode === "cold") args.push("--rerun-tasks", "--no-build-cache");
    const run = spawnSync("./gradlew", args, { cwd: repoRoot, stdio: "inherit" });
    if (run.status !== 0) throw new Error(`gradle failed: status=${run.status}`);
    const after = listProfileFiles(repoRoot);
    const fresh = after.find((f) => !before.includes(f));
    if (!fresh) throw new Error("no fresh profile HTML produced");
    const html = fs.readFileSync(path.join(repoRoot, PROFILE_DIR, fresh), "utf8");
    const { totalMs } = parseGradleProfile(html);
    samples_ms.push(totalMs);
    log(`[bench-gradle] iter ${i}: ${totalMs} ms`);
  }
  return samples_ms;
}

function listProfileFiles(repoRoot) {
  const dir = path.join(repoRoot, PROFILE_DIR);
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir);
}
