import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  existsSync,
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
const packageRoot = join(repoRoot, "npm/fixthis");

function writeExecutable(path, body) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

test("npm wrapper package exposes FixThis metadata for public install and MCP registry verification", () => {
  const manifest = JSON.parse(readFileSync(join(packageRoot, "package.json"), "utf8"));

  assert.equal(manifest.name, "@beyondwin/fixthis");
  assert.equal(manifest.version, "0.2.3");
  assert.equal(manifest.private, undefined);
  assert.equal(manifest.mcpName, "io.github.beyondwin/fixthis");
  assert.deepEqual(manifest.bin, {
    fixthis: "bin/fixthis.js",
    "fixthis-mcp": "bin/fixthis-mcp.js",
  });
});

test("npm wrapper delegates fixthis and fixthis-mcp bins to the vendored release package", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-npm-wrapper-"));
  try {
    const fakePackage = join(root, "package");
    mkdirSync(join(fakePackage, "bin"), { recursive: true });
    mkdirSync(join(fakePackage, "scripts"), { recursive: true });
    mkdirSync(join(fakePackage, "vendor/fixthis-cli-mcp-v0.2.0/fixthis/bin"), { recursive: true });
    mkdirSync(join(fakePackage, "vendor/fixthis-cli-mcp-v0.2.0/fixthis-mcp/bin"), { recursive: true });
    writeFileSync(
      join(fakePackage, "package.json"),
      JSON.stringify({ name: "fixthis", version: "0.2.0", type: "module" }),
    );
    copyFileSync(join(packageRoot, "bin/fixthis.js"), join(fakePackage, "bin/fixthis.js"));
    copyFileSync(join(packageRoot, "bin/fixthis-mcp.js"), join(fakePackage, "bin/fixthis-mcp.js"));
    copyFileSync(
      join(packageRoot, "scripts/run-vendored-command.js"),
      join(fakePackage, "scripts/run-vendored-command.js"),
    );
    writeFileSync(join(fakePackage, "vendor/current"), "fixthis-cli-mcp-v0.2.0\n");
    writeExecutable(
      join(fakePackage, "vendor/fixthis-cli-mcp-v0.2.0/fixthis/bin/fixthis"),
      "#!/usr/bin/env bash\nprintf 'fixthis:%s\\n' \"$*\" > \"$FIXTHIS_TEST_ARGS_FILE\"\n",
    );
    writeExecutable(
      join(fakePackage, "vendor/fixthis-cli-mcp-v0.2.0/fixthis-mcp/bin/fixthis-mcp"),
      "#!/usr/bin/env bash\nprintf 'fixthis-mcp:%s\\n' \"$*\" > \"$FIXTHIS_TEST_ARGS_FILE\"\n",
    );

    const argsFile = join(root, "args.txt");
    const cli = spawnSync("node", [join(fakePackage, "bin/fixthis.js"), "doctor", "--json"], {
      env: { ...process.env, FIXTHIS_TEST_ARGS_FILE: argsFile },
      encoding: "utf8",
    });
    assert.equal(cli.status, 0, cli.stderr || cli.stdout);
    assert.equal(readFileSync(argsFile, "utf8"), "fixthis:doctor --json\n");

    const mcp = spawnSync("node", [join(fakePackage, "bin/fixthis-mcp.js"), "--stdio"], {
      env: { ...process.env, FIXTHIS_TEST_ARGS_FILE: argsFile },
      encoding: "utf8",
    });
    assert.equal(mcp.status, 0, mcp.stderr || mcp.stdout);
    assert.equal(readFileSync(argsFile, "utf8"), "fixthis-mcp:--stdio\n");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("npm postinstall can install a local GitHub release archive into vendor", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-npm-postinstall-"));
  try {
    const fakePackage = join(root, "package");
    const packageArchiveRoot = join(root, "fixthis-cli-mcp-v0.2.0");
    mkdirSync(join(fakePackage, "scripts"), { recursive: true });
    mkdirSync(join(packageArchiveRoot, "fixthis/bin"), { recursive: true });
    mkdirSync(join(packageArchiveRoot, "fixthis-mcp/bin"), { recursive: true });
    writeFileSync(
      join(fakePackage, "package.json"),
      JSON.stringify({ name: "fixthis", version: "0.2.0" }),
    );
    copyFileSync(join(packageRoot, "scripts/postinstall.js"), join(fakePackage, "scripts/postinstall.js"));
    writeExecutable(join(packageArchiveRoot, "fixthis/bin/fixthis"), "#!/usr/bin/env bash\n");
    writeExecutable(join(packageArchiveRoot, "fixthis-mcp/bin/fixthis-mcp"), "#!/usr/bin/env bash\n");

    const archive = join(root, "fixthis-cli-mcp-v0.2.0.tar.gz");
    const tar = spawnSync("tar", ["-czf", archive, "-C", root, "fixthis-cli-mcp-v0.2.0"], {
      encoding: "utf8",
    });
    assert.equal(tar.status, 0, tar.stderr || tar.stdout);

    const install = spawnSync("node", [join(fakePackage, "scripts/postinstall.js")], {
      cwd: fakePackage,
      env: { ...process.env, FIXTHIS_RELEASE_ARCHIVE: archive },
      encoding: "utf8",
    });
    assert.equal(install.status, 0, install.stderr || install.stdout);
    assert.equal(existsSync(join(fakePackage, "vendor/fixthis-cli-mcp-v0.2.0/fixthis/bin/fixthis")), true);
    assert.equal(readFileSync(join(fakePackage, "vendor/current"), "utf8"), "fixthis-cli-mcp-v0.2.0\n");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
