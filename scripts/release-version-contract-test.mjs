import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

function read(path) {
  return readFileSync(join(repoRoot, path), "utf8");
}

function fixThisVersion() {
  return read("gradle.properties")
    .split("\n")
    .find((line) => line.startsWith("FIXTHIS_VERSION="))
    ?.split("=")[1]
    ?.trim();
}

test("release helper scripts do not hardcode the current package version", () => {
  const version = fixThisVersion();
  assert.ok(version, "FIXTHIS_VERSION is missing from gradle.properties");

  const checkedScripts = [
    "scripts/check-release-readiness.mjs",
    "scripts/install-fixthis-test.mjs",
  ];

  const offenders = checkedScripts.filter((path) => {
    const text = read(path);
    const normalized = text.replaceAll("\\.", ".");
    return normalized.includes(version) || normalized.includes(`v${version}`);
  });

  assert.deepEqual(
    offenders,
    [],
    `Scripts should read FIXTHIS_VERSION instead of hardcoding ${version}`,
  );
});

test("Gradle version providers do not hardcode current release fallbacks", () => {
  const offenders = [
    "build.gradle.kts",
    "fixthis-gradle-plugin/build.gradle.kts",
    "fixthis-cli/build.gradle.kts",
  ].filter((path) => {
    const text = read(path);
    return /orElse\(\s*"0\.\d+\.\d+/.test(text) || /\?:\s*"0\.\d+\.\d+/.test(text);
  });

  assert.deepEqual(
    offenders,
    [],
    "Gradle builds should require FIXTHIS_VERSION or read gradle.properties, not carry a release fallback",
  );
});
