import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const repoRoot = path.resolve(import.meta.dirname, "..");
const sourceScript = path.join(repoRoot, "scripts/bootstrap-mcp.sh");

function makeFixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "fixthis-bootstrap-"));
  fs.mkdirSync(path.join(root, "scripts"), { recursive: true });
  fs.mkdirSync(path.join(root, "fixthis-cli/build/install/fixthis/bin"), { recursive: true });
  fs.copyFileSync(sourceScript, path.join(root, "scripts/bootstrap-mcp.sh"));
  fs.chmodSync(path.join(root, "scripts/bootstrap-mcp.sh"), 0o755);
  fs.writeFileSync(
    path.join(root, "gradlew"),
    "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > gradle-args.txt\n",
    { mode: 0o755 },
  );
  fs.writeFileSync(
    path.join(root, "fixthis-cli/build/install/fixthis/bin/fixthis"),
    "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > fixthis-args.txt\n",
    { mode: 0o755 },
  );
  return root;
}

function runBootstrap(root, args) {
  return spawnSync("bash", ["scripts/bootstrap-mcp.sh", ...args], {
    cwd: root,
    encoding: "utf8",
  });
}

test("--sample bootstraps the bundled sample application id", () => {
  const root = makeFixture();
  const result = runBootstrap(root, ["--sample", "--target", "codex", "--dry-run"]);

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(
    fs.readFileSync(path.join(root, "fixthis-args.txt"), "utf8"),
    /setup\n--package\nio\.beyondwin\.fixthis\.sample\n--write\n--target\ncodex\n--dry-run\n/,
  );
});

test("explicit --package still overrides the sample shortcut", () => {
  const root = makeFixture();
  const result = runBootstrap(root, [
    "--sample",
    "--package",
    "com.example.debug",
    "--target",
    "claude",
  ]);

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(
    fs.readFileSync(path.join(root, "fixthis-args.txt"), "utf8"),
    /setup\n--package\ncom\.example\.debug\n--write\n--target\nclaude\n/,
  );
});
