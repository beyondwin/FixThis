import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const repoRoot = fileURLToPath(new URL("..", import.meta.url));

function writeExecutable(path, body) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

test("install-fixthis installs a local release archive and can run init", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-install-"));
  try {
    mkdirSync(join(root, "scripts"), { recursive: true });
    copyFileSync(join(repoRoot, "scripts/install-fixthis.sh"), join(root, "scripts/install-fixthis.sh"));
    chmodSync(join(root, "scripts/install-fixthis.sh"), 0o755);

    const packageRoot = join(root, "fixthis-cli-mcp-v9.8.7");
    mkdirSync(join(packageRoot, "fixthis/bin"), { recursive: true });
    mkdirSync(join(packageRoot, "fixthis-mcp/bin"), { recursive: true });
    writeExecutable(
      join(packageRoot, "fixthis/bin/fixthis"),
      "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > \"$FIXTHIS_TEST_ARGS_FILE\"\n",
    );
    writeExecutable(join(packageRoot, "fixthis-mcp/bin/fixthis-mcp"), "#!/usr/bin/env bash\n");
    const archive = join(root, "fixthis-cli-mcp-v9.8.7.tar.gz");
    const tar = spawnSync("tar", ["-czf", archive, "-C", root, "fixthis-cli-mcp-v9.8.7"], {
      encoding: "utf8",
    });
    assert.equal(tar.status, 0, tar.stderr || tar.stdout);

    const appRoot = join(root, "android-app");
    mkdirSync(appRoot);
    const argsFile = join(root, "init-args.txt");
    const result = spawnSync(
      "bash",
      [
        "scripts/install-fixthis.sh",
        "--archive",
        archive,
        "--install-dir",
        join(root, "install"),
        "--bin-dir",
        join(root, "bin"),
        "--init",
        "--target",
        "codex",
        "--package",
        "com.example.debug",
        "--project-dir",
        appRoot,
      ],
      {
        cwd: root,
        env: { ...process.env, FIXTHIS_TEST_ARGS_FILE: argsFile },
        encoding: "utf8",
      },
    );

    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.equal(existsSync(join(root, "bin/fixthis")), true);
    assert.equal(lstatSync(join(root, "bin/fixthis")).isSymbolicLink(), true);
    assert.match(
      readFileSync(argsFile, "utf8"),
      /init\n--target\ncodex\n--project-dir\n.*android-app\n--package\ncom\.example\.debug\n/,
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
