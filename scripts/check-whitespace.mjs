#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const HISTORICAL_SUPERPOWERS_DOC = /^docs\/superpowers\/.*\.md$/;
const violationLine = /^([^:]+):\d+:/;

export function filterWhitespaceOutput(output) {
  const kept = [];
  let ignoredCount = 0;
  let skipContinuation = false;

  for (const line of output.split(/\r?\n/)) {
    if (line.length === 0) continue;

    const match = line.match(violationLine);
    if (match) {
      const path = match[1];
      if (HISTORICAL_SUPERPOWERS_DOC.test(path)) {
        ignoredCount += 1;
        skipContinuation = true;
        continue;
      }

      skipContinuation = false;
      kept.push(line);
      continue;
    }

    if (skipContinuation) {
      skipContinuation = false;
      continue;
    }

    kept.push(line);
  }

  return { output: kept.join("\n"), ignoredCount };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const args = process.argv.slice(2);
  if (args.length === 0) {
    console.error("Usage: node scripts/check-whitespace.mjs <git args...>");
    process.exit(2);
  }

  const result = spawnSync("git", args, { encoding: "utf8" });
  const combined = [result.stdout, result.stderr].filter(Boolean).join("");
  const filtered = filterWhitespaceOutput(combined);

  if (filtered.output) {
    console.error(filtered.output);
    process.exit(result.status ?? 1);
  }

  if ((result.status ?? 0) !== 0 && filtered.ignoredCount === 0) {
    process.exit(result.status ?? 1);
  }

  if (filtered.ignoredCount > 0) {
    console.error(
      `[check-whitespace] ignored ${filtered.ignoredCount} docs/superpowers whitespace warning(s).`,
    );
  }

  process.exit(0);
}
