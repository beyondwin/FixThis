import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import path from "node:path";
import test from "node:test";

const repoRoot = path.resolve(import.meta.dirname, "..");
const script = path.join(repoRoot, "scripts/verify-ci-local.sh");

function runVerify(args, env = {}) {
  return spawnSync("bash", [script, ...args], {
    cwd: repoRoot,
    encoding: "utf8",
    env: { ...process.env, ...env },
  });
}

function commandLines(output) {
  return output
    .split("\n")
    .filter((line) => line.startsWith("RUN "))
    .map((line) => line.replace(/^RUN /, ""));
}

test("--fast lists the quick CI mirror without Gradle", () => {
  const result = runVerify(["--fast", "--list"], {
    FIXTHIS_VERIFY_BASE: "HEAD",
    FIXTHIS_VERIFY_CHANGED_FILES: "README.md\n",
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  const commands = commandLines(result.stdout);
  assert.deepEqual(commands, [
    "node scripts/check-doc-consistency.mjs",
    "node scripts/check-release-readiness.mjs",
    "node scripts/build-console-assets.mjs --check",
    "node --check fixthis-mcp/src/main/resources/console/app.js",
    "npm run console:test:all",
    "node --test scripts/fixthis-smoke-test.mjs",
    "git diff --check HEAD..HEAD",
    "git diff --check",
  ]);
});

test("--full lists quick checks plus the Gradle CI command", () => {
  const result = runVerify(["--full", "--list"], {
    FIXTHIS_VERIFY_BASE: "HEAD",
    FIXTHIS_VERIFY_CHANGED_FILES: "README.md\n",
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(
    result.stdout,
    /RUN \.\/gradlew spotlessCheck detekt :fixthis-compose-core:test :fixthis-cli:test :fixthis-mcp:test :fixthis-compose-sidekick:testDebugUnitTest :fixthis-gradle-plugin:test :app:assembleDebug :fixthis-cli:installDist :fixthis-mcp:installDist --no-daemon/,
  );
});

test("--changed-only skips Gradle for docs-only changes", () => {
  const result = runVerify(["--changed-only", "--list"], {
    FIXTHIS_VERIFY_BASE: "HEAD",
    FIXTHIS_VERIFY_CHANGED_FILES: "docs/contributing/release-readiness.md\n",
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.doesNotMatch(result.stdout, /RUN \.\/gradlew /);
});

test("--changed-only includes Gradle for Kotlin or Gradle changes", () => {
  const result = runVerify(["--changed-only", "--list"], {
    FIXTHIS_VERIFY_BASE: "HEAD",
    FIXTHIS_VERIFY_CHANGED_FILES: "fixthis-mcp/src/main/kotlin/io/beyondwin/Foo.kt\n",
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /RUN \.\/gradlew /);
});

test("unknown flags fail with usage", () => {
  const result = runVerify(["--not-a-mode"]);

  assert.equal(result.status, 2);
  assert.match(result.stderr, /unknown flag: --not-a-mode/);
  assert.match(result.stderr, /Usage: scripts\/verify-ci-local\.sh/);
});
