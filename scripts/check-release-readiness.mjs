#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

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

function forbidPublishedGradleClaims(rule, file) {
  const text = read(file);
  const riskyPatterns = [
    /published\s+to\s+Maven\s+Central/i,
    /available\s+on\s+Maven\s+Central/i,
    /available\s+from\s+the\s+Gradle\s+Plugin\s+Portal/i,
    /install\s+from\s+Maven\s+Central/i,
  ];
  const allowedContext =
    /not\s+published/i.test(text) ||
    /future/i.test(text) ||
    /not\s+yet/i.test(text) ||
    /do\s+not/i.test(text);
  const matched = riskyPatterns.find((pattern) => pattern.test(text));
  if (!matched || allowedContext) {
    pass(rule);
  } else {
    fail(rule, `${file} contains an unqualified published-artifact claim matching ${matched}`);
  }
}

requireRegex(
  'R1.readme-not-published',
  'README.md',
  /not\s+yet[\s>]+on\s+Maven\s+Central\s+or\s+the\s+Gradle\s+Plugin\s+Portal/i,
  'an explicit not-yet-published Maven Central and Gradle Plugin Portal status',
);
requireIncludes(
  'R2.readiness-release-process',
  'docs/contributing/release-readiness.md',
  '[Release process](release-process.md)',
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
  /Source release[\s\S]*Artifact release[\s\S]*MCP Registry release/,
  'separate Source release, Artifact release, and MCP Registry release rows',
);
requireIncludes(
  'R7.unreleased-warning',
  'docs/releases/unreleased.md',
  'It is not a tagged release',
);
requireIncludes(
  'R8.maven-publish-workflow',
  '.github/workflows/publish-maven-central.yml',
  'publishMavenPublicationToCentralPortalRepository',
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

for (const file of [
  'README.md',
  'docs/getting-started/add-to-your-app.md',
  'docs/getting-started/connect-your-agent.md',
  'docs/contributing/release-readiness.md',
  'docs/contributing/release-process.md',
]) {
  forbidPublishedGradleClaims(`R12.no-unqualified-published-claims:${file}`, file);
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure);
  }
  process.exit(1);
}

console.log('\nAll release-readiness rules pass.');
