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

function writeExecutable(path, body) {
  writeFileSync(path, body);
  chmodSync(path, 0o755);
}

test("package-cli-release creates one sibling-layout CLI/MCP tarball", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-package-release-"));
  try {
    mkdirSync(join(root, "scripts"), { recursive: true });
    copyFileSync(
      join(repoRoot, "scripts/package-cli-release.sh"),
      join(root, "scripts/package-cli-release.sh"),
    );
    chmodSync(join(root, "scripts/package-cli-release.sh"), 0o755);

    mkdirSync(join(root, "fixthis-cli/build/install/fixthis/bin"), { recursive: true });
    mkdirSync(join(root, "fixthis-mcp/build/install/fixthis-mcp/bin"), { recursive: true });
    writeExecutable(join(root, "fixthis-cli/build/install/fixthis/bin/fixthis"), "#!/usr/bin/env bash\n");
    writeExecutable(
      join(root, "fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp"),
      "#!/usr/bin/env bash\n",
    );

    const result = spawnSync(
      "bash",
      ["scripts/package-cli-release.sh", "--version", "v9.8.7", "--skip-build"],
      { cwd: root, encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr || result.stdout);
    const archive = join(root, "build/release/fixthis-cli-mcp-v9.8.7.tar.gz");
    assert.equal(existsSync(archive), true);

    const listing = spawnSync("tar", ["-tzf", archive], { encoding: "utf8" });
    assert.equal(listing.status, 0, listing.stderr || listing.stdout);
    assert.match(listing.stdout, /fixthis-cli-mcp-v9\.8\.7\/fixthis\/bin\/fixthis/);
    assert.match(listing.stdout, /fixthis-cli-mcp-v9\.8\.7\/fixthis-mcp\/bin\/fixthis-mcp/);
    assert.match(listing.stdout, /fixthis-cli-mcp-v9\.8\.7\/VERSION/);

    const extractDir = join(root, "extract");
    mkdirSync(extractDir);
    const extract = spawnSync("tar", ["-xzf", archive, "-C", extractDir], { encoding: "utf8" });
    assert.equal(extract.status, 0, extract.stderr || extract.stdout);
    assert.equal(readFileSync(join(extractDir, "fixthis-cli-mcp-v9.8.7/VERSION"), "utf8"), "v9.8.7\n");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("package-cli-release rejects path-like version strings", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-package-release-"));
  try {
    mkdirSync(join(root, "scripts"), { recursive: true });
    copyFileSync(
      join(repoRoot, "scripts/package-cli-release.sh"),
      join(root, "scripts/package-cli-release.sh"),
    );
    chmodSync(join(root, "scripts/package-cli-release.sh"), 0o755);

    const result = spawnSync(
      "bash",
      ["scripts/package-cli-release.sh", "--version", "../bad", "--skip-build"],
      { cwd: root, encoding: "utf8" },
    );

    assert.equal(result.status, 2);
    assert.match(result.stderr, /invalid --version/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
