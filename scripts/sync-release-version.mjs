#!/usr/bin/env node
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import {
  currentReleaseFiles,
  readFixThisVersion,
  replaceReleaseVersionsInText,
  repoRoot,
} from "./release-version.mjs";

const checkOnly = process.argv.includes("--check");
let version;
try {
  version = readFixThisVersion();
} catch (error) {
  console.error(`[release-version] ${error.message}`);
  process.exit(1);
}

function updateTextFile(path, update) {
  const absolutePath = join(repoRoot, path);
  const before = readFileSync(absolutePath, "utf8");
  const after = update(before);
  if (before === after) return false;
  if (!checkOnly) writeFileSync(absolutePath, after);
  return true;
}

function replaceReleaseVersions(text) {
  return replaceReleaseVersionsInText(text, version);
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
