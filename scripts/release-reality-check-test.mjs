import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildReleaseRealityReport,
  classifySurface,
  defaultProbes,
  mcpRegistryVersionUrl,
  probeMcpRegistryVersion,
  probePomArtifact,
  readMcpServerName,
  renderMarkdownReport,
  runReleaseRealityCheck,
  writeReports,
} from "./release-reality-check.mjs";

test("classifySurface distinguishes verified deferred and mismatch", () => {
  assert.deepEqual(
    classifySurface({ name: "npm", expected: "1.0.0", actual: "1.0.0" }),
    { name: "npm", status: "verified", expected: "1.0.0", actual: "1.0.0" },
  );
  assert.deepEqual(
    classifySurface({ name: "homebrew", expected: "1.0.0", actual: null, reason: "network unavailable" }),
    { name: "homebrew", status: "deferred", expected: "1.0.0", actual: null, reason: "network unavailable" },
  );
  assert.deepEqual(
    classifySurface({ name: "tag", expected: "v1.0.0", actual: "missing" }),
    { name: "tag", status: "mismatch", expected: "v1.0.0", actual: "missing", reason: "expected v1.0.0 but observed missing" },
  );
});

test("strict report fails deferred and mismatch while non-strict fails mismatch only", () => {
  const surfaces = [
    { name: "tag", status: "verified" },
    { name: "npm", status: "deferred" },
    { name: "mcp-registry", status: "mismatch" },
  ];

  assert.equal(buildReleaseRealityReport({ strict: false, surfaces }).status, "fail");
  assert.equal(buildReleaseRealityReport({ strict: true, surfaces: surfaces.slice(0, 2) }).status, "fail");
  assert.equal(buildReleaseRealityReport({ strict: false, surfaces: surfaces.slice(0, 2) }).status, "pass_with_deferred");
});

test("runReleaseRealityCheck uses injected probes and current version", () => {
  const report = runReleaseRealityCheck({
    strict: false,
    version: "1.0.0",
    probes: {
      gitTag: () => "v1.0.0",
      githubRelease: () => null,
      homebrew: () => "1.0.0",
      npm: () => "1.0.0",
      mcpRegistry: () => "1.0.0",
      gradlePluginPortal: () => "1.0.0",
      mavenCentral: () => "1.0.0",
    },
  });

  assert.equal(report.status, "pass_with_deferred");
  assert.equal(report.surfaces.find((surface) => surface.name === "github-release").status, "deferred");
});

test("readMcpServerName reads only the local MCP server identity", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-release-local-mcp-"));
  try {
    writeFileSync(join(root, "server.json"), JSON.stringify({
      name: "io.github.beyondwin/fixthis",
      version: "9.9.9",
      packages: [{ version: "9.9.9" }],
    }));

    assert.equal(readMcpServerName(root), "io.github.beyondwin/fixthis");
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("default MCP registry probe verifies public registry response instead of local package metadata", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-release-local-mcp-"));
  try {
    writeFileSync(join(root, "server.json"), JSON.stringify({
      name: "io.github.beyondwin/fixthis",
      version: "1.0.0",
      packages: [{ version: "1.0.0" }],
    }));
    let requestedUrl = null;
    const probes = defaultProbes(root, {
      fetchJson: (url) => {
        requestedUrl = url;
        return {
          server: {
            name: "io.github.beyondwin/fixthis",
            version: "2.0.0",
            packages: [{ version: "8.8.8" }],
          },
        };
      },
    });

    assert.equal(probes.mcpRegistry("2.0.0"), "2.0.0");
    assert.equal(
      requestedUrl,
      "https://registry.modelcontextprotocol.io/v0.1/servers/io.github.beyondwin%2Ffixthis/versions/2.0.0",
    );
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("default GitHub release probe prefers authenticated gh api before public curl", () => {
  const probes = defaultProbes(undefined, {
    execJson: (command, args) => {
      assert.equal(command, "gh");
      assert.deepEqual(args, ["api", "repos/beyondwin/FixThis/releases/tags/v1.0.0"]);
      return { tag_name: "v1.0.0" };
    },
    fetchJson: () => {
      throw new Error("public GitHub API fallback should not be used when gh api succeeds");
    },
  });

  assert.equal(probes.githubRelease("1.0.0"), "v1.0.0");
});

test("default GitHub release probe falls back to public API when gh api is unavailable", () => {
  let requestedUrl = null;
  const probes = defaultProbes(undefined, {
    execJson: () => null,
    fetchJson: (url) => {
      requestedUrl = url;
      return { tag_name: "v1.0.0" };
    },
  });

  assert.equal(probes.githubRelease("1.0.0"), "v1.0.0");
  assert.equal(requestedUrl, "https://api.github.com/repos/beyondwin/FixThis/releases/tags/v1.0.0");
});

test("probeMcpRegistryVersion extracts version from registry response shapes", () => {
  assert.equal(
    mcpRegistryVersionUrl("io.github.beyondwin/fixthis", "1.0.0"),
    "https://registry.modelcontextprotocol.io/v0.1/servers/io.github.beyondwin%2Ffixthis/versions/1.0.0",
  );
  assert.equal(
    probeMcpRegistryVersion("1.0.0", "io.github.beyondwin/fixthis", () => ({ server: { version: "1.0.0" } })),
    "1.0.0",
  );
  assert.equal(
    probeMcpRegistryVersion("1.0.0", "io.github.beyondwin/fixthis", () => ({ version: "1.0.0" })),
    "1.0.0",
  );
  assert.equal(
    probeMcpRegistryVersion(
      "1.0.0",
      "io.github.beyondwin/fixthis",
      () => ({ server: { packages: [{ version: "1.0.0" }] } }),
    ),
    "1.0.0",
  );
  assert.equal(probeMcpRegistryVersion("1.0.0", null, () => ({ server: { version: "1.0.0" } })), null);
  assert.equal(probeMcpRegistryVersion("1.0.0", "io.github.beyondwin/fixthis", () => null), null);
});

test("explicit missing artifact probe results fail in non-strict mode", () => {
  const report = runReleaseRealityCheck({
    strict: false,
    version: "1.0.0",
    probes: {
      gitTag: () => "v1.0.0",
      githubRelease: () => "v1.0.0",
      homebrew: () => "1.0.0",
      npm: () => "1.0.0",
      mcpRegistry: () => "1.0.0",
      gradlePluginPortal: () => "missing",
      mavenCentralSidekick: () => "1.0.0",
      mavenCentralCore: () => "missing",
    },
  });

  assert.equal(report.status, "fail");
  assert.equal(report.surfaces.find((surface) => surface.name === "gradle-plugin-portal").status, "mismatch");
  assert.equal(report.surfaces.find((surface) => surface.name === "maven-central-core").status, "mismatch");
});

test("deferred artifact probe results still pass with deferred in non-strict mode", () => {
  const report = runReleaseRealityCheck({
    strict: false,
    version: "1.0.0",
    probes: {
      gitTag: () => "v1.0.0",
      githubRelease: () => "v1.0.0",
      homebrew: () => "1.0.0",
      npm: () => "1.0.0",
      mcpRegistry: () => "1.0.0",
      gradlePluginPortal: () => null,
      mavenCentralSidekick: () => "1.0.0",
      mavenCentralCore: () => null,
    },
  });

  assert.equal(report.status, "pass_with_deferred");
  assert.equal(report.surfaces.find((surface) => surface.name === "gradle-plugin-portal").status, "deferred");
  assert.equal(report.surfaces.find((surface) => surface.name === "maven-central-core").status, "deferred");
});

test("probePomArtifact maps HTTP existence to version missing or deferred", () => {
  assert.equal(probePomArtifact("1.0.0", "https://example.test/present.pom", () => "200 128"), "1.0.0");
  assert.equal(probePomArtifact("1.0.0", "https://example.test/missing.pom", () => "404 0"), "missing");
  assert.equal(probePomArtifact("1.0.0", "https://example.test/empty.pom", () => "200 0"), "missing");
  assert.equal(probePomArtifact("1.0.0", "https://example.test/unavailable.pom", () => null), null);
});

test("markdown report renders every release surface", () => {
  const text = renderMarkdownReport({
    status: "pass_with_deferred",
    strict: false,
    version: "1.0.0",
    generatedAt: "2026-05-31T00:00:00.000Z",
    surfaces: [
      { name: "git-tag", status: "verified", expected: "v1.0.0", actual: "v1.0.0" },
      { name: "github-release", status: "deferred", expected: "v1.0.0", actual: null, reason: "network unavailable" },
    ],
  });

  assert.match(text, /# FixThis Release Reality Report/);
  assert.match(text, /\| git-tag \| verified \| v1\.0\.0 \| v1\.0\.0 \| - \|/);
  assert.match(text, /\| github-release \| deferred \| v1\.0\.0 \| - \| network unavailable \|/);
});

test("writeReports writes json and markdown artifacts", () => {
  const root = mkdtempSync(join(tmpdir(), "fixthis-release-reality-"));
  try {
    const paths = writeReports({
      status: "pass",
      strict: false,
      version: "1.0.0",
      generatedAt: "2026-05-31T00:00:00.000Z",
      surfaces: [],
    }, root);
    assert.match(readFileSync(paths.json, "utf8"), /"version": "1.0.0"/);
    assert.match(readFileSync(paths.markdown, "utf8"), /FixThis Release Reality Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
