#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { readFixThisVersion } from "./release-version.mjs";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), "..");
const defaultReportDir = join(repoRoot, "build/reports/fixthis-release-reality");

function execText(command, args, options = {}) {
  try {
    return execFileSync(command, args, {
      cwd: options.cwd || repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
      timeout: options.timeoutMs || 20_000,
    }).trim();
  } catch {
    return null;
  }
}

function jsonFromUrl(url) {
  const text = execText("curl", ["-fsSL", url], { timeoutMs: 20_000 });
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function jsonFromCommand(command, args, options = {}) {
  const text = execText(command, args, options);
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export function verifyGithubReleaseAssets(archiveAsset, checksumAsset) {
  if (!archiveAsset?.browser_download_url || !checksumAsset?.browser_download_url) return false;
  const workspace = mkdtempSync(join(tmpdir(), "fixthis-release-assets-"));
  const archivePath = join(workspace, "release.tar.gz");
  const checksumPath = join(workspace, "release.tar.gz.sha256");
  try {
    for (const [url, output] of [
      [archiveAsset.browser_download_url, archivePath],
      [checksumAsset.browser_download_url, checksumPath],
    ]) {
      execFileSync("curl", ["--fail", "--silent", "--show-error", "--location", "--retry", "3", "--output", output, url], {
        cwd: workspace,
        stdio: ["ignore", "ignore", "ignore"],
        timeout: 60_000,
      });
    }
    const expected = readFileSync(checksumPath, "utf8").trim().split(/\s+/)[0];
    if (!/^[a-f0-9]{64}$/i.test(expected)) return false;
    const actual = createHash("sha256").update(readFileSync(archivePath)).digest("hex");
    return actual.toLowerCase() === expected.toLowerCase();
  } catch {
    return false;
  } finally {
    rmSync(workspace, { recursive: true, force: true });
  }
}

function probeGithubRelease(
  version,
  fetchJson = jsonFromUrl,
  execJson = jsonFromCommand,
  verifyAssets = verifyGithubReleaseAssets,
) {
  const path = `repos/beyondwin/FixThis/releases/tags/v${version}`;
  const json =
    execJson("gh", ["api", path], { timeoutMs: 20_000 }) ||
    fetchJson(`https://api.github.com/${path}`);
  if (!json) return null;
  if (json.draft || !json.published_at) return "draft";
  if (json.prerelease) return "prerelease";
  const archiveName = `fixthis-cli-mcp-v${version}.tar.gz`;
  const checksumName = `${archiveName}.sha256`;
  const requiredAssets = new Map([
    [archiveName, null],
    [checksumName, null],
  ]);
  for (const asset of json.assets || []) {
    if (requiredAssets.has(asset?.name) && Number(asset?.size || 0) > 0) {
      requiredAssets.set(asset.name, asset);
    }
  }
  if ([...requiredAssets.values()].some((asset) => !asset)) return "missing-assets";
  if (!verifyAssets(requiredAssets.get(archiveName), requiredAssets.get(checksumName))) return "checksum-mismatch";
  return json.tag_name || null;
}

export function readMcpServerName(root = repoRoot) {
  try {
    const server = JSON.parse(readFileSync(join(root, "server.json"), "utf8"));
    return typeof server.name === "string" && server.name.trim() ? server.name.trim() : null;
  } catch {
    return null;
  }
}

export function mcpRegistryVersionUrl(serverName, version) {
  return `https://registry.modelcontextprotocol.io/v0.1/servers/${encodeURIComponent(serverName)}/versions/${encodeURIComponent(version)}`;
}

function firstString(...values) {
  return values.find((value) => typeof value === "string" && value.trim()) || null;
}

export function probeMcpRegistryVersion(version, serverName = readMcpServerName(repoRoot), fetchJson = jsonFromUrl) {
  if (!serverName) return null;
  const json = fetchJson(mcpRegistryVersionUrl(serverName, version));
  if (!json) return null;
  return firstString(
    json.server?.version,
    json.version,
    json.server?.packages?.find((pkg) => typeof pkg?.version === "string")?.version,
    json.packages?.find((pkg) => typeof pkg?.version === "string")?.version,
  );
}

function urlStatusAndSize(url) {
  return execText("curl", ["-sSL", "-o", "/dev/null", "-w", "%{http_code} %{size_download}", url], {
    timeoutMs: 20_000,
  });
}

export function probePomArtifact(version, url, fetchStatus = urlStatusAndSize) {
  const result = fetchStatus(url);
  if (!result) return null;
  const [status, rawSize] = String(result).trim().split(/\s+/);
  const size = Number(rawSize || 0);
  if (status === "200" && size > 0) return version;
  if (status === "404" || (status === "200" && size === 0)) return "missing";
  return null;
}

export function classifySurface({ name, expected, actual, reason = null }) {
  if (actual === expected) {
    return { name, status: "verified", expected, actual };
  }
  if (actual === null || actual === undefined || actual === "") {
    return { name, status: "deferred", expected, actual: null, reason: reason || "surface unavailable" };
  }
  return {
    name,
    status: "mismatch",
    expected,
    actual,
    reason: reason || `expected ${expected} but observed ${actual}`,
  };
}

function gradlePluginMarkerPomUrl(version) {
  return `https://plugins.gradle.org/m2/io/github/beyondwin/fixthis/compose/io.github.beyondwin.fixthis.compose.gradle.plugin/${version}/io.github.beyondwin.fixthis.compose.gradle.plugin-${version}.pom`;
}

function mavenCentralPomUrl(artifact, version) {
  return `https://repo1.maven.org/maven2/io/github/beyondwin/${artifact}/${version}/${artifact}-${version}.pom`;
}

function mavenCentralPluginMarkerPomUrl(version) {
  return `https://repo1.maven.org/maven2/io/github/beyondwin/fixthis/compose/io.github.beyondwin.fixthis.compose.gradle.plugin/${version}/io.github.beyondwin.fixthis.compose.gradle.plugin-${version}.pom`;
}

function statusForSurfaces(surfaces, strict) {
  if (surfaces.some((surface) => surface.status === "mismatch")) return "fail";
  if (strict && surfaces.some((surface) => surface.status === "deferred")) return "fail";
  if (surfaces.some((surface) => surface.status === "deferred")) return "pass_with_deferred";
  return "pass";
}

export function buildReleaseRealityReport({
  strict = false,
  version,
  surfaces,
  generatedAt = new Date().toISOString(),
}) {
  return {
    schemaVersion: "1.0",
    status: statusForSurfaces(surfaces, strict),
    strict,
    version,
    generatedAt,
    surfaces,
  };
}

export function defaultProbes(root = repoRoot, dependencies = {}) {
  const fetchJson = dependencies.fetchJson || jsonFromUrl;
  const execJson = dependencies.execJson || jsonFromCommand;
  const verifyGithubAssets = dependencies.verifyGithubAssets || verifyGithubReleaseAssets;
  return {
    gitTag: (version) => execText("git", ["tag", "--list", `v${version}`]) || "missing",
    githubRelease: (version) => probeGithubRelease(version, fetchJson, execJson, verifyGithubAssets),
    homebrew: () => execText("bash", ["-lc", "brew info --json=v2 beyondwin/tools/fixthis 2>/dev/null | node -e 'let s=\"\";process.stdin.on(\"data\",d=>s+=d);process.stdin.on(\"end\",()=>{const j=JSON.parse(s);console.log(j.formulae?.[0]?.versions?.stable||\"\")})'"]) || null,
    npm: () => execText("npm", ["view", "@beyondwin/fixthis", "version"]) || null,
    mcpRegistry: (version) => probeMcpRegistryVersion(version, readMcpServerName(root), fetchJson),
    gradlePluginPortal: (version) => probePomArtifact(version, gradlePluginMarkerPomUrl(version)),
    mavenCentralSidekick: (version) => probePomArtifact(version, mavenCentralPomUrl("fixthis-compose-sidekick", version)),
    mavenCentralCore: (version) => probePomArtifact(version, mavenCentralPomUrl("fixthis-compose-core", version)),
    mavenCentralPluginImplementation: (version) => probePomArtifact(version, mavenCentralPomUrl("fixthis-gradle-plugin", version)),
    mavenCentralPluginMarker: (version) => probePomArtifact(version, mavenCentralPluginMarkerPomUrl(version)),
  };
}

function runArtifactProbe(primary, fallback, version) {
  const probe = primary || fallback;
  return probe ? probe(version) : null;
}

export function runReleaseRealityCheck({
  strict = false,
  version = readFixThisVersion(repoRoot),
  probes = defaultProbes(),
} = {}) {
  const surfaces = [
    classifySurface({ name: "git-tag", expected: `v${version}`, actual: probes.gitTag(version) }),
    classifySurface({
      name: "github-release",
      expected: `v${version}`,
      actual: probes.githubRelease(version),
      reason: "GitHub release API unavailable or release not published",
    }),
    classifySurface({
      name: "homebrew",
      expected: version,
      actual: probes.homebrew(version),
      reason: "Homebrew formula unavailable",
    }),
    classifySurface({
      name: "npm",
      expected: version,
      actual: probes.npm(version),
      reason: "npm package unavailable",
    }),
    classifySurface({
      name: "mcp-registry",
      expected: version,
      actual: probes.mcpRegistry(version),
      reason: "MCP registry metadata unavailable",
    }),
    classifySurface({
      name: "gradle-plugin-portal",
      expected: version,
      actual: probes.gradlePluginPortal(version),
      reason: "Gradle Plugin Portal metadata unavailable",
    }),
    classifySurface({
      name: "maven-central-sidekick",
      expected: version,
      actual: runArtifactProbe(probes.mavenCentralSidekick, probes.mavenCentral, version),
      reason: "Maven Central sidekick POM unavailable",
    }),
    classifySurface({
      name: "maven-central-core",
      expected: version,
      actual: runArtifactProbe(probes.mavenCentralCore, probes.mavenCentral, version),
      reason: "Maven Central core POM unavailable",
    }),
    classifySurface({
      name: "maven-central-plugin-implementation",
      expected: version,
      actual: runArtifactProbe(probes.mavenCentralPluginImplementation, probes.mavenCentral, version),
      reason: "Maven Central Gradle plugin implementation POM unavailable",
    }),
    classifySurface({
      name: "maven-central-plugin-marker",
      expected: version,
      actual: runArtifactProbe(probes.mavenCentralPluginMarker, probes.mavenCentral, version),
      reason: "Maven Central Gradle plugin marker POM unavailable",
    }),
  ];
  return buildReleaseRealityReport({ strict, version, surfaces });
}

function cell(value) {
  return value === null || value === undefined || value === "" ? "-" : String(value).replaceAll("|", "\\|");
}

export function renderMarkdownReport(report) {
  const lines = [
    "# FixThis Release Reality Report",
    "",
    `- Status: ${report.status}`,
    `- Strict: ${report.strict}`,
    `- Version: ${report.version}`,
    `- Generated: ${report.generatedAt}`,
    "",
    "| Surface | Status | Expected | Actual | Reason |",
    "| --- | --- | --- | --- | --- |",
  ];
  for (const surface of report.surfaces || []) {
    lines.push(`| ${cell(surface.name)} | ${cell(surface.status)} | ${cell(surface.expected)} | ${cell(surface.actual)} | ${cell(surface.reason)} |`);
  }
  return `${lines.join("\n")}\n`;
}

export function writeReports(report, reportDir = defaultReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, "report.json");
  const markdown = join(reportDir, "report.md");
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderMarkdownReport(report));
  return { json, markdown };
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === "--strict") {
      args.strict = true;
    } else if (arg === "-h" || arg === "--help") {
      console.log("Usage: node scripts/release-reality-check.mjs [--strict]");
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  const args = parseArgs(process.argv.slice(2));
  const report = runReleaseRealityCheck(args);
  const paths = writeReports(report);
  console.log(`Release reality: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === "fail") process.exit(1);
}
