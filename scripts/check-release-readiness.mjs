#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { execFileSync } from 'node:child_process';
import { escapedVersion, readFixThisVersion } from './release-version.mjs';

const root = process.cwd();
const failures = [];
const fixthisVersion = readFixThisVersion(root);

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
  new RegExp(
    `fixthis\\s+install-agent[\\s\\S]*io\\.github\\.beyondwin\\.fixthis\\.compose[\\s\\S]*version\\s+"${escapedVersion(fixthisVersion)}"`,
  ),
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
// Match the backticked bullet `npm run first-run:smoke` exactly so the rule does NOT
// pass on the related but distinct `npm run first-run:smoke:test` bullet. Dropping the
// bare release-time smoke variant must fail R2d even if the contract-test variant stays.
requireIncludes(
  'R2d.v05-first-run-smoke',
  'docs/contributing/release-readiness.md',
  '`npm run first-run:smoke`',
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

requireIncludes(
  'R22.v06-release-claim-manifest',
  'docs/contributing/release-readiness.md',
  '## v0.6 Release Claim Manifest',
);
requireIncludes(
  'R23.v06-release-evidence-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:v06:evidence:test`',
);
requireIncludes(
  'R23b.v08-release-claim-manifest',
  'docs/contributing/release-readiness.md',
  '## v0.8 Release Claim Manifest',
);
requireIncludes(
  'R24.trust-sync-release-hardening-section',
  'docs/contributing/release-readiness.md',
  '## Trust Sync Release Hardening Evidence',
);
requireIncludes(
  'R25.trust-sync-boundary-guidance-command',
  'docs/contributing/release-readiness.md',
  '`npm run handoff:eval:test`',
);
requireIncludes(
  'R26.trust-sync-sse-browser-proof-command',
  'docs/contributing/release-readiness.md',
  '`npm run console:browser:reliability`',
);
requireIncludes(
  'R27.trust-sync-event-diagnostics-command',
  'docs/contributing/release-readiness.md',
  '`./gradlew :fixthis-mcp:test --tests "*ConsoleEventBusTest" --tests "*ConsoleEventsRoutesTest" --no-daemon`',
);
requireIncludes(
  'R28.v1-trust-install-inner-loop-section',
  'docs/contributing/release-readiness.md',
  '## V1 Trust, Install, And Inner-Loop Evidence',
);
requireIncludes(
  'R29.v1-evidence-runner-fast-profile',
  'docs/contributing/release-readiness.md',
  '`npm run evidence:fast -- --dry-run`',
);
requireIncludes(
  'R30.v1-current-channel-only-claim',
  'docs/contributing/release-readiness.md',
  'This evidence pack does not add PyPI, Docker, or any new package channel.',
);
requireIncludes(
  'R31.v1-evidence-runner-contributing',
  'CONTRIBUTING.md',
  'npm run evidence:fast -- --dry-run',
);
requireIncludes(
  'R32.v1-0-release-claim-manifest',
  'docs/contributing/release-readiness.md',
  '## v1.0 Release Claim Manifest',
);
requireIncludes(
  'R33.v11-trust-loop-evidence-manifest',
  'docs/contributing/release-readiness.md',
  '## v1.1 Trust Loop Evidence',
);
requireIncludes(
  'R34.v11-release-reality-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:reality`',
);
requireIncludes(
  'R35.v11-agent-loop-command',
  'docs/contributing/release-readiness.md',
  '`npm run agent-loop:smoke -- --strict`',
);
requireIncludes(
  'R36.release-gate-interop-sse-section',
  'docs/contributing/release-readiness.md',
  '## Release Gate, Interop Evidence, And SSE Closure',
);
requireIncludes(
  'R37.release-gate-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:gate`',
);
requireIncludes(
  'R38.release-drift-command',
  'docs/contributing/release-readiness.md',
  '`npm run release:drift`',
);
requireIncludes(
  'R39.external-trust-matrix-section',
  'docs/contributing/release-readiness.md',
  '## External Trust Matrix And Release Drift Evidence',
);
requireIncludes(
  'R40.external-fixture-matrix-command',
  'docs/contributing/release-readiness.md',
  '`npm run external-fixture:matrix -- --strict`',
);
requireRegex(
  'R50.external-trust-matrix-v2-evidence',
  'docs/contributing/release-readiness.md',
  /## External App Trust Matrix v2 Evidence[\s\S]*npm run external-fixture:matrix:test[\s\S]*npm run external-fixture:matrix -- --strict[\s\S]*external-trust-matrix-v2/,
  'external trust matrix v2 evidence section',
);
requireRegex(
  'R51.external-trust-matrix-v2-caveats',
  'docs/contributing/release-readiness.md',
  /VISUAL_AREA_ONLY[\s\S]*POSSIBLE_VIEW_INTEROP[\s\S]*SHARED_COMPONENT/,
  'external trust matrix v2 caveat signals',
);
requireIncludes(
  'R41.handoff-correctness-command',
  'docs/contributing/release-readiness.md',
  '`npm run handoff:eval:test`',
);
requireIncludes(
  'R42.required-checks-observation-snapshot',
  'docs/contributing/required-checks-observation.md',
  'npm run checks:observation -- --json',
);
requireIncludes(
  'R43.external-first-handoff-recovery-section',
  'docs/contributing/release-readiness.md',
  '## External App First Handoff Recovery',
);
requireIncludes(
  'R44.external-first-handoff-strict-command',
  'docs/contributing/release-readiness.md',
  '`npm run agent-loop:smoke -- --strict`',
);
requireIncludes(
  'R45.external-first-handoff-deferral-rule',
  'docs/contributing/release-readiness.md',
  'Non-strict missing-runtime runs must be recorded as deferred with a recovery-oriented readiness object.',
);
requireIncludes(
  'R46.required-checks-observation-policy-link',
  'docs/contributing/required-checks-observation.md',
  'docs/contributing/required-checks.md',
);
requireIncludes(
  'R47.v1-residual-risk-closure-gate',
  'docs/contributing/release-readiness.md',
  '## v1 Residual Risk Closure Gate',
);
requireIncludes(
  'R48.v1-adb-discovery-evidence',
  'docs/contributing/release-readiness.md',
  '`./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon`',
);
requireIncludes(
  'R49.v1-sse-fallback-evidence',
  'docs/contributing/release-readiness.md',
  '`npm run console:browser:reliability`',
);
requireIncludes(
  'R52.android-agent-evidence-umbrella-section',
  'docs/contributing/release-readiness.md',
  'Android Agent Evidence Umbrella',
);
requireIncludes(
  'R53.android-agent-evidence-runtime-smoke',
  'docs/contributing/release-readiness.md',
  'npm run runtime-evidence:smoke',
);
requireIncludes(
  'R54.android-agent-evidence-plugin-contract',
  'docs/contributing/release-readiness.md',
  'npm run plugin:contract:test',
);
requireIncludes(
  'R55.android-agent-evidence-gate-claim',
  'scripts/release-gate.mjs',
  'android-agent-evidence-umbrella',
);

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure);
  }
  process.exit(1);
}

console.log('\nAll release-readiness rules pass.');
