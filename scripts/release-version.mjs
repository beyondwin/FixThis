import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = fileURLToPath(new URL("..", import.meta.url));

export const currentReleaseFiles = [
  "README.md",
  "MCP.md",
  "docs/getting-started/add-to-your-app.md",
  "docs/getting-started/agent-install-snippet.md",
  "docs/getting-started/connect-your-agent.md",
  "docs/reference/cli.md",
  "docs/contributing/release-readiness.md",
  "docs/architecture/overview.md",
  "docs/releases/unreleased.md",
];

export const releaseVersionPattern = /(?<![0-9.])v?0\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?/g;

export function readFixThisVersion(root = repoRoot) {
  const version = readFileSync(join(root, "gradle.properties"), "utf8")
    .split("\n")
    .find((line) => line.startsWith("FIXTHIS_VERSION="))
    ?.split("=")[1]
    ?.trim();

  if (!version) {
    throw new Error("FIXTHIS_VERSION is missing from gradle.properties");
  }

  return version;
}

export function escapedVersion(version) {
  return version.replaceAll(".", "\\.");
}
