#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';

const root = process.cwd();
const failures = [];

function read(file) {
  const absolutePath = path.join(root, file);
  return fs.existsSync(absolutePath) ? fs.readFileSync(absolutePath, 'utf8') : null;
}

function pass(rule) {
  console.log(`PASS ${rule}`);
}

function fail(rule, message) {
  failures.push(`FAIL ${rule}: ${message}`);
}

function requireIncludes(rule, file, needle) {
  const text = read(file);
  if (text?.includes(needle)) {
    pass(rule);
  } else if (text === null) {
    fail(rule, `${file} must exist`);
  } else {
    fail(rule, `${file} must include ${JSON.stringify(needle)}`);
  }
}

function requireRegex(rule, file, regex, description) {
  const text = read(file);
  if (regex.test(text ?? '')) {
    pass(rule);
  } else if (text === null) {
    fail(rule, `${file} must exist`);
  } else {
    fail(rule, `${file} must include ${description}`);
  }
}

function forbidStalePrePublicationClaims(rule, file) {
  const text = read(file);
  const stalePatterns = [
    /pre-publication status/i,
    /not\s+yet\s+on\s+Maven\s+Central\s+or\s+the\s+Gradle\s+Plugin\s+Portal/i,
    /Maven\s+Central\s+and\s+Gradle\s+Plugin\s+Portal\s+coordinates\s+are\s+not\s+published\s+yet/i,
    /External\s+Gradle\s+artifacts\s+are\s+not\s+published\s+yet/i,
    /until\s+artifacts\s+are\s+released/i,
    /source\/composite-build\s+wiring\s+until\s+registry\s+verification\s+passes/i,
  ];
  const matched = stalePatterns.find((pattern) => pattern.test(text ?? ''));
  if (!matched) {
    pass(rule);
  } else {
    fail(rule, `${file} still contains stale pre-publication wording matching ${matched}`);
  }
}

function listTrackedTextFiles(directory = root) {
  const ignoredExtensions = new Set([
    '.jar',
    '.png',
    '.webp',
  ]);
  return execFileSync('git', ['ls-files'], { cwd: root, encoding: 'utf8' })
    .split('\n')
    .filter(Boolean)
    .filter((file) => !ignoredExtensions.has(path.extname(file)));
}

function forbidTextInRepository(rule, forbiddenText) {
  const matches = listTrackedTextFiles()
    .filter((file) => read(file)?.includes(forbiddenText));
  if (matches.length === 0) {
    pass(rule);
  } else {
    fail(rule, `repository must not include ${JSON.stringify(forbiddenText)}; found in ${matches.join(', ')}`);
  }
}

requireRegex(
  'R1.readme-agent-first-install',
  'README.md',
  /fixthis\s+install-agent[\s\S]*io\.github\.beyondwin\.fixthis\.compose[\s\S]*version\s+"0\.3\.0"/,
  'the published agent-first install path and Gradle Plugin Portal id',
);
requireIncludes(
  'R2.readiness-release-process',
  'docs/contributing/release-readiness.md',
  '[Release process](release-process.md)',
);
requireIncludes(
  'R2b.v05-trustworthy-onboarding-claim',
  'docs/contributing/release-readiness.md',
  'v0.5 Trustworthy Onboarding',
);
requireIncludes(
  'R2c.v05-readme-first-agent-bootstrap',
  'docs/contributing/release-readiness.md',
  'README-first Claude Code / Codex bootstrap',
);
requireIncludes(
  'R2d.v05-first-run-smoke',
  'docs/contributing/release-readiness.md',
  'npm run first-run:smoke',
);
requireIncludes(
  'R3.readiness-security',
  'docs/contributing/release-readiness.md',
  '[Security model](../../SECURITY.md)',
);
requireIncludes(
  'R4.readiness-privacy',
  'docs/contributing/release-readiness.md',
  '[Privacy](../reference/privacy.md)',
);
requireIncludes(
  'R5.readiness-compatibility',
  'docs/contributing/release-readiness.md',
  '[Compatibility](../reference/compatibility.md)',
);
requireRegex(
  'R6.release-process-types',
  'docs/contributing/release-process.md',
  /GitHub release[\s\S]*Artifact release[\s\S]*MCP Registry release/,
  'separate GitHub release, Artifact release, and MCP Registry release rows',
);
requireIncludes(
  'R7.unreleased-warning',
  'docs/releases/unreleased.md',
  'It is not a tagged release',
);
requireIncludes(
  'R8.maven-publish-workflow',
  '.github/workflows/publish-maven-central.yml',
  'https://central.sonatype.com/api/v1/publisher/upload',
);
requireIncludes(
  'R9.maven-central-repository',
  'build.gradle.kts',
  'https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/',
);
requireIncludes(
  'R10.maven-signing-secrets',
  'build.gradle.kts',
  'SIGNING_KEY',
);
requireIncludes(
  'R11.maven-group',
  'gradle.properties',
  'FIXTHIS_GROUP=io.github.beyondwin',
);
requireIncludes(
  'R12.gradle-plugin-portal-workflow',
  '.github/workflows/publish-gradle-plugin.yml',
  'publishPlugins',
);
requireIncludes(
  'R13.gradle-plugin-publish-plugin',
  'fixthis-gradle-plugin/build.gradle.kts',
  'alias(libs.plugins.gradle.plugin.publish)',
);
requireIncludes(
  'R15.npm-wrapper-package',
  'npm/fixthis/package.json',
  '"mcpName": "io.github.beyondwin/fixthis"',
);
requireIncludes(
  'R16.mcp-registry-metadata',
  'server.json',
  '"name": "io.github.beyondwin/fixthis"',
);
requireIncludes(
  'R17.npm-mcp-publish-workflow',
  '.github/workflows/publish-npm-mcp.yml',
  'mcp-publisher login github-oidc',
);
requireIncludes(
  'R18.npm-wrapper-test-script',
  'package.json',
  '"release:npm:test"',
);
forbidTextInRepository(
  'R19.github-namespace',
  'io.beyondwin' + '.fixthis',
);
forbidTextInRepository(
  'R20.github-namespace-paths',
  'io/beyondwin' + '/fixthis',
);

for (const file of [
  'README.md',
  'docs/getting-started/add-to-your-app.md',
  'docs/getting-started/connect-your-agent.md',
  'docs/contributing/release-readiness.md',
  'docs/contributing/release-process.md',
]) {
  forbidStalePrePublicationClaims(`R21.no-stale-prepublication-claims:${file}`, file);
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure);
  }
  process.exit(1);
}

console.log('\nAll release-readiness rules pass.');
