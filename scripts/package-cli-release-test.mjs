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

    const checksum = `${archive}.sha256`;
    assert.equal(existsSync(checksum), true);
    assert.match(
      readFileSync(checksum, "utf8"),
      /^[a-f0-9]{64}  fixthis-cli-mcp-v9\.8\.7\.tar\.gz\n$/,
    );

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

test("package-cli-release passes requested version into Gradle build", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-package-release-"));
  try {
    mkdirSync(join(root, "scripts"), { recursive: true });
    copyFileSync(
      join(repoRoot, "scripts/package-cli-release.sh"),
      join(root, "scripts/package-cli-release.sh"),
    );
    chmodSync(join(root, "scripts/package-cli-release.sh"), 0o755);

    writeExecutable(
      join(root, "gradlew"),
      [
        "#!/usr/bin/env bash",
        "printf '%s\\n' \"$@\" > gradle-args.txt",
        "mkdir -p fixthis-cli/build/install/fixthis/bin",
        "mkdir -p fixthis-mcp/build/install/fixthis-mcp/bin",
        "printf '#!/usr/bin/env bash\\n' > fixthis-cli/build/install/fixthis/bin/fixthis",
        "printf '#!/usr/bin/env bash\\n' > fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp",
        "chmod +x fixthis-cli/build/install/fixthis/bin/fixthis",
        "chmod +x fixthis-mcp/build/install/fixthis-mcp/bin/fixthis-mcp",
      ].join("\n") + "\n",
    );

    const result = spawnSync(
      "bash",
      ["scripts/package-cli-release.sh", "--version", "v9.8.7"],
      { cwd: root, encoding: "utf8" },
    );

    assert.equal(result.status, 0, result.stderr || result.stdout);
    assert.match(readFileSync(join(root, "gradle-args.txt"), "utf8"), /-PFIXTHIS_VERSION=9\.8\.7/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
