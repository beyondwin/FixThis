#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const failures = [];

function read(file) {
  return fs.readFileSync(path.join(root, file), 'utf8');
}

function pass(rule) {
  console.log(`PASS ${rule}`);
}

function fail(rule, message) {
  failures.push(`FAIL ${rule}: ${message}`);
}

function requireIncludes(rule, file, needle) {
  const text = read(file);
  if (text.includes(needle)) {
    pass(rule);
  } else {
    fail(rule, `${file} must include ${JSON.stringify(needle)}`);
  }
}

function requireRegex(rule, file, regex, description) {
  const text = read(file);
  if (regex.test(text)) {
    pass(rule);
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
requireRegex(
  'R8.gradle-version-source',
  'gradle.properties',
  /^fixthis\.version=.+$/m,
  'fixthis.version as the release version source of truth',
);
requireRegex(
  'R9.core-maven-publish',
  'fixthis-compose-core/build.gradle.kts',
  /id\("maven-publish"\)[\s\S]*artifactId = "fixthis-compose-core"/,
  'maven-publish configuration for io.beyondwin.fixthis:fixthis-compose-core',
);
requireRegex(
  'R10.sidekick-debug-maven-publish',
  'fixthis-compose-sidekick/build.gradle.kts',
  /id\("maven-publish"\)[\s\S]*singleVariant\("debug"\)[\s\S]*artifactId = "fixthis-compose-sidekick"/,
  'debug-variant maven-publish configuration for io.beyondwin.fixthis:fixthis-compose-sidekick',
);
requireRegex(
  'R11.gradle-plugin-portal-metadata',
  'fixthis-gradle-plugin/build.gradle.kts',
  /alias\(libs\.plugins\.gradle\.plugin\.publish\)[\s\S]*website\.set\([\s\S]*vcsUrl\.set\([\s\S]*tags\.set\(/,
  'Gradle Plugin Portal metadata',
);
requireRegex(
  'R12.plugin-runtime-version-not-hardcoded',
  'fixthis-gradle-plugin/src/main/kotlin/io/beyondwin/fixthis/gradle/FixThisExtension.kt',
  /FixThisPluginVersion\.defaultRuntimeVersion\(\)/,
  'runtimeVersion default derived from plugin build metadata',
);

for (const file of [
  'README.md',
  'docs/getting-started/add-to-your-app.md',
  'docs/getting-started/connect-your-agent.md',
  'docs/contributing/release-readiness.md',
  'docs/contributing/release-process.md',
]) {
  forbidPublishedGradleClaims(`R13.no-unqualified-published-claims:${file}`, file);
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(failure);
  }
  process.exit(1);
}

console.log('\nAll release-readiness rules pass.');
