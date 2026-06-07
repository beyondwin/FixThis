import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { replaceReleaseVersionsInText } from "./release-version.mjs";

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

test("release helper files do not hardcode the current package version", () => {
  const version = fixThisVersion();
  assert.ok(version, "FIXTHIS_VERSION is missing from gradle.properties");

  const checkedScripts = [
    "fixthis-gradle-plugin/src/functionalTest/resources/release-consumer-fixture/app/build.gradle.kts",
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
    `Release helper files should read FIXTHIS_VERSION instead of hardcoding ${version}`,
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

test("release version replacement updates v1 FixThis contexts without rewriting dependency versions", () => {
  const text = [
    "curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh | bash -s -- --version v1.1.0",
    "curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \\",
    "  | bash -s -- --version v1.1.0",
    'id("io.github.beyondwin.fixthis.compose") version "1.1.0"',
    'debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:1.1.0")',
    "# fixthis 1.1.0 (bridge protocol v1.3)",
    '# {"cliVersion":"1.1.0","bridgeProtocolVersion":"1.3"}',
    "The current source tree uses Compose UI test artifacts `1.7.8`.",
  ].join("\n");

  assert.equal(
    replaceReleaseVersionsInText(text, "1.2.0"),
    [
      "curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh | bash -s -- --version v1.2.0",
      "curl -fsSL https://raw.githubusercontent.com/beyondwin/FixThis/main/scripts/install-fixthis.sh \\",
      "  | bash -s -- --version v1.2.0",
      'id("io.github.beyondwin.fixthis.compose") version "1.2.0"',
      'debugImplementation("io.github.beyondwin:fixthis-compose-sidekick:1.2.0")',
      "# fixthis 1.2.0 (bridge protocol v1.3)",
      '# {"cliVersion":"1.2.0","bridgeProtocolVersion":"1.3"}',
      "The current source tree uses Compose UI test artifacts `1.7.8`.",
    ].join("\n"),
  );
});

test("Homebrew formula version check fails when formula points at an older release", () => {
  const dir = mkdtempSync(join(tmpdir(), "fixthis-homebrew-version-"));
  const formula = join(dir, "fixthis.rb");
  writeFileSync(
    formula,
    'url "https://github.com/beyondwin/FixThis/releases/download/v0.6.0/fixthis-cli-mcp-v0.6.0.tar.gz"\n',
  );

  const result = spawnSync(
    process.execPath,
    ["scripts/check-homebrew-formula-version.mjs", "--formula", formula],
    {
      cwd: repoRoot,
      encoding: "utf8",
    },
  );

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Homebrew formula points at v0\.6\.0/);
});

test("Homebrew formula version check passes when formula points at the current release", () => {
  const version = fixThisVersion();
  const dir = mkdtempSync(join(tmpdir(), "fixthis-homebrew-version-"));
  const formula = join(dir, "fixthis.rb");
  writeFileSync(
    formula,
    `url "https://github.com/beyondwin/FixThis/releases/download/v${version}/fixthis-cli-mcp-v${version}.tar.gz"\n`,
  );

  const result = spawnSync(
    process.execPath,
    ["scripts/check-homebrew-formula-version.mjs", "--formula", formula],
    {
      cwd: repoRoot,
      encoding: "utf8",
    },
  );

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, new RegExp(`Homebrew formula points at v${version.replaceAll(".", "\\.")}`));
});
