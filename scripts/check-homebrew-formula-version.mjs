#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const defaultFormulaUrl =
  "https://raw.githubusercontent.com/beyondwin/homebrew-tools/HEAD/Formula/fixthis.rb";

function fixThisVersion() {
  const text = readFileSync(join(repoRoot, "gradle.properties"), "utf8");
  const version = text
    .split("\n")
    .find((line) => line.startsWith("FIXTHIS_VERSION="))
    ?.split("=")[1]
    ?.trim();
  if (!version) throw new Error("FIXTHIS_VERSION is missing from gradle.properties");
  return version;
}

function parseArgs(argv) {
  const args = { formula: null, formulaUrl: defaultFormulaUrl };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--formula") {
      args.formula = argv[++i];
    } else if (arg === "--formula-url") {
      args.formulaUrl = argv[++i];
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

async function readFormula(args) {
  if (args.formula) return readFileSync(args.formula, "utf8");
  const response = await fetch(args.formulaUrl);
  if (!response.ok) {
    throw new Error(`Could not read Homebrew formula: HTTP ${response.status} ${args.formulaUrl}`);
  }
  return response.text();
}

function formulaVersion(formulaText) {
  const match = formulaText.match(/releases\/download\/(v\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)/);
  return match?.[1] ?? null;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const expected = `v${fixThisVersion()}`;
  const actual = formulaVersion(await readFormula(args));
  if (!actual) {
    console.error("Homebrew formula does not contain a FixThis GitHub Release URL.");
    process.exit(1);
  }
  if (actual !== expected) {
    console.error(`Homebrew formula points at ${actual}; expected ${expected}.`);
    process.exit(1);
  }
  console.log(`Homebrew formula points at ${expected}.`);
}

main().catch((error) => {
  console.error(error.message || String(error));
  process.exit(1);
});
