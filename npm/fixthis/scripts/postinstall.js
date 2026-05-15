#!/usr/bin/env node
import { createWriteStream, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, cpSync } from "node:fs";
import { get } from "node:https";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const packageRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const manifest = JSON.parse(readFileSync(path.join(packageRoot, "package.json"), "utf8"));
const releaseVersion = `v${manifest.version}`;
const packageName = `fixthis-cli-mcp-${releaseVersion}`;
const vendorDir = path.join(packageRoot, "vendor");
const destination = path.join(vendorDir, packageName);

if (process.env.FIXTHIS_NPM_SKIP_DOWNLOAD === "1") {
  console.log("[fixthis] skipped GitHub Release download because FIXTHIS_NPM_SKIP_DOWNLOAD=1");
  process.exit(0);
}

function download(url, target, redirects = 0) {
  return new Promise((resolve, reject) => {
    get(url, (response) => {
      if ([301, 302, 303, 307, 308].includes(response.statusCode ?? 0)) {
        response.resume();
        if (!response.headers.location || redirects >= 5) {
          reject(new Error(`download redirect failed for ${url}`));
          return;
        }
        resolve(download(new URL(response.headers.location, url).toString(), target, redirects + 1));
        return;
      }
      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error(`download failed with HTTP ${response.statusCode}: ${url}`));
        return;
      }
      const output = createWriteStream(target, { mode: 0o600 });
      response.pipe(output);
      output.on("finish", () => output.close(resolve));
      output.on("error", reject);
    }).on("error", reject);
  });
}

function runTar(args) {
  const result = spawnSync("tar", args, { encoding: "utf8" });
  if (result.status !== 0) {
    throw new Error(result.stderr || result.stdout || `tar exited with ${result.status}`);
  }
}

async function main() {
  if (existsSync(path.join(destination, "fixthis/bin/fixthis"))) {
    writeFileSync(path.join(vendorDir, "current"), `${packageName}\n`);
    console.log(`[fixthis] ${packageName} already installed`);
    return;
  }

  const workDir = path.join(tmpdir(), `fixthis-npm-${process.pid}-${Date.now()}`);
  const archive = process.env.FIXTHIS_RELEASE_ARCHIVE || path.join(workDir, `${packageName}.tar.gz`);
  const extractDir = path.join(workDir, "extract");
  mkdirSync(extractDir, { recursive: true });

  try {
    if (!process.env.FIXTHIS_RELEASE_ARCHIVE) {
      mkdirSync(workDir, { recursive: true });
      const url = `https://github.com/beyondwin/FixThis/releases/download/${releaseVersion}/${packageName}.tar.gz`;
      console.log(`[fixthis] downloading ${url}`);
      await download(url, archive);
    }

    runTar(["-xzf", archive, "-C", extractDir]);
    const extracted = path.join(extractDir, packageName);
    if (!existsSync(path.join(extracted, "fixthis/bin/fixthis"))) {
      throw new Error("release archive is missing fixthis/bin/fixthis");
    }
    if (!existsSync(path.join(extracted, "fixthis-mcp/bin/fixthis-mcp"))) {
      throw new Error("release archive is missing fixthis-mcp/bin/fixthis-mcp");
    }

    rmSync(destination, { recursive: true, force: true });
    mkdirSync(vendorDir, { recursive: true });
    cpSync(extracted, destination, { recursive: true });
    writeFileSync(path.join(vendorDir, "current"), `${packageName}\n`);
    console.log(`[fixthis] installed ${packageName}`);
  } finally {
    rmSync(workDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`[fixthis] postinstall failed: ${error.message}`);
  process.exit(1);
});
