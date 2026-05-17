#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function countBaselineIds(xmlText) {
  return [...xmlText.matchAll(/<ID>/g)].length;
}

export function evaluateBudgets(budgets, readFile) {
  return Object.entries(budgets).map(([file, budget]) => {
    const count = countBaselineIds(readFile(file));
    return {
      file,
      budget,
      count,
      status: count > budget ? "over" : count < budget ? "improved" : "equal",
    };
  });
}

function main() {
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
  const budgetPath = path.join(repoRoot, "config/detekt/baseline-budget.json");
  const budgets = JSON.parse(fs.readFileSync(budgetPath, "utf8"));
  const results = evaluateBudgets(
    budgets,
    (file) => fs.readFileSync(path.join(repoRoot, file), "utf8"),
  );
  let failed = false;
  for (const result of results) {
    const message = `${result.file}: ${result.count}/${result.budget}`;
    if (result.status === "over") {
      console.error(`[detekt-baseline] over budget: ${message}`);
      failed = true;
    } else if (result.status === "improved") {
      console.log(`[detekt-baseline] improved: ${message}`);
    } else {
      console.log(`[detekt-baseline] ok: ${message}`);
    }
  }
  if (failed) process.exit(1);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
