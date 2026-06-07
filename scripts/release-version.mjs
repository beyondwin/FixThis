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
  "scripts/install-fixthis.sh",
];

export const releaseVersionPattern = /(?<![0-9.])v?(?:0|[1-9]\d?)\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?/g;

const fixThisReleaseContextPattern =
  /FixThis|fixthis|FIXTHIS|io\.github\.beyondwin|@beyondwin\/fixthis|--version|--plugin-version|runtimeVersion\.set|cliVersion|scripts\/install-fixthis/;

export function replaceReleaseVersionsInText(text, version) {
  return text.replaceAll(releaseVersionPattern, (match, offset) => {
    const lineStart = text.lastIndexOf("\n", offset) + 1;
    const nextLineBreak = text.indexOf("\n", offset);
    const lineEnd = nextLineBreak === -1 ? text.length : nextLineBreak;
    const line = text.slice(lineStart, lineEnd);

    if (!fixThisReleaseContextPattern.test(line)) return match;
    return match.startsWith("v") ? `v${version}` : version;
  });
}

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
