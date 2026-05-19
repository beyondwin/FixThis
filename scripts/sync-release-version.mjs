#!/usr/bin/env node
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));
const checkOnly = process.argv.includes("--check");
const version = readFileSync(join(repoRoot, "gradle.properties"), "utf8")
  .split("\n")
  .find((line) => line.startsWith("FIXTHIS_VERSION="))
  ?.split("=")[1]
  ?.trim();

if (!version) {
  console.error("[release-version] FIXTHIS_VERSION is missing from gradle.properties");
  process.exit(1);
}

const currentReleaseFiles = [
  "README.md",
  "MCP.md",
  "docs/getting-started/add-to-your-app.md",
  "docs/getting-started/agent-install-snippet.md",
  "docs/getting-started/connect-your-agent.md",
  "docs/reference/cli.md",
  "docs/contributing/release-readiness.md",
  "docs/architecture/overview.md",
  "docs/releases/unreleased.md",
];

const releaseVersionPattern = /(?<![0-9.])v?0\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?/g;

function updateTextFile(path, update) {
  const absolutePath = join(repoRoot, path);
  const before = readFileSync(absolutePath, "utf8");
  const after = update(before);
  if (before === after) return false;
  if (!checkOnly) writeFileSync(absolutePath, after);
  return true;
}

function replaceReleaseVersions(text) {
  return text.replaceAll(releaseVersionPattern, (match) => {
    return match.startsWith("v") ? `v${version}` : version;
  });
}

function updateJsonFile(path, update) {
  return updateTextFile(path, (text) => JSON.stringify(update(JSON.parse(text)), null, 2) + "\n");
}

const changed = [];

for (const path of currentReleaseFiles) {
  if (updateTextFile(path, replaceReleaseVersions)) changed.push(path);
}

if (
  updateJsonFile("npm/fixthis/package.json", (manifest) => ({
    ...manifest,
    version,
  }))
) {
  changed.push("npm/fixthis/package.json");
}

if (
  updateJsonFile("server.json", (server) => ({
    ...server,
    version,
    packages: server.packages.map((pkg) => ({ ...pkg, version })),
  }))
) {
  changed.push("server.json");
}

if (checkOnly && changed.length > 0) {
  console.error(`[release-version] ${changed.length} file(s) are not synced to ${version}:`);
  for (const path of changed) console.error(`- ${path}`);
  console.error("Run `npm run release:version:sync` after editing FIXTHIS_VERSION.");
  process.exit(1);
}

if (changed.length > 0) {
  console.log(`[release-version] synced ${changed.length} file(s) to ${version}:`);
  for (const path of changed) console.log(`- ${path}`);
} else {
  console.log(`[release-version] already synced to ${version}`);
}
