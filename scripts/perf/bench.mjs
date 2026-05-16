#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";
import { aggregate } from "./aggregate.mjs";
import { captureEnv } from "./env-fingerprint.mjs";
import { runGradleScenario } from "./bench-gradle.mjs";
import { runNodeScenario } from "./bench-node.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, "../..");
const manifest = JSON.parse(fs.readFileSync(path.join(here, "perf-scenarios.json"), "utf8"));

const args = parseArgs(process.argv.slice(2));
const wanted = args.scenario ? manifest.scenarios.filter((s) => s.key === args.scenario) : manifest.scenarios;
if (wanted.length === 0) {
  console.error(`no scenarios match ${args.scenario}`);
  process.exit(2);
}

const results = [];
for (const scenario of wanted) {
  const effective = args.iterations ? { ...scenario, iterations: Number(args.iterations) } : scenario;
  console.error(`\n=== ${effective.key} (${effective.iterations} iterations, mode=${effective.mode}) ===`);
  const samples = effective.kind === "gradle"
    ? runGradleScenario(effective, { repoRoot })
    : runNodeScenario(effective, { repoRoot });
  const agg = aggregate(samples, { dropWarmup: true });
  results.push({
    key: effective.key,
    regress_threshold_pct: effective.regress_threshold_pct,
    improve_threshold_pct: effective.improve_threshold_pct,
    ...agg,
  });
}

const outDir = path.join(repoRoot, "output/perf");
fs.mkdirSync(outDir, { recursive: true });
const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
const outPath = path.join(outDir, `run-${timestamp}.json`);
const payload = {
  schema: 1,
  started_at: new Date().toISOString(),
  git_sha: safeExec("git rev-parse --short HEAD") || "unknown",
  env: captureEnv(),
  results,
};
fs.writeFileSync(outPath, JSON.stringify(payload, null, 2) + "\n");
console.error(`\nwrote ${outPath}`);

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === "--scenario") out.scenario = argv[++i];
    else if (argv[i] === "--iterations") out.iterations = argv[++i];
  }
  return out;
}

function safeExec(cmd) {
  try { return execSync(cmd, { stdio: ["ignore", "pipe", "pipe"] }).toString().trim(); }
  catch { return null; }
}
