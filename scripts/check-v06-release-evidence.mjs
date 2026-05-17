#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const root = process.cwd();
const failures = [];

function read(file) {
  return fs.readFileSync(path.join(root, file), 'utf8');
}

function requireIncludes(rule, file, needle) {
  const text = read(file);
  if (!text.includes(needle)) {
    failures.push(`FAIL ${rule}: ${file} must include ${JSON.stringify(needle)}`);
  } else {
    console.log(`PASS ${rule}`);
  }
}

function requireRegex(rule, file, regex, description) {
  const text = read(file);
  if (!regex.test(text)) {
    failures.push(`FAIL ${rule}: ${file} must include ${description}`);
  } else {
    console.log(`PASS ${rule}`);
  }
}

requireIncludes(
  'V06.R1.handoff-eval-script',
  'package.json',
  '"handoff:eval:test"',
);
requireIncludes(
  'V06.R2.console-reliability-script',
  'package.json',
  '"console:reliability:test"',
);
requireIncludes(
  'V06.R3.release-evidence-script',
  'package.json',
  '"release:v06:evidence:test"',
);
requireIncludes(
  'V06.R4.protected-output-fields',
  'docs/reference/output-schema.md',
  'sourceCandidates',
);
requireIncludes(
  'V06.R5.mcp-tools-reference',
  'docs/reference/mcp-tools.md',
  'fixthis_read_feedback',
);
for (const field of [
  'items',
  'screens',
  'itemId',
  'screenId',
  'targetEvidence',
  'targetReliability',
  'sourceCandidates',
]) {
  requireIncludes(
    `V06.R5.schema-field.${field}`,
    'docs/reference/output-schema.md',
    field,
  );
}
for (const tool of [
  'fixthis_read_feedback',
  'fixthis_claim_feedback',
  'fixthis_resolve_feedback',
  'fixthis_open_feedback_console',
]) {
  requireIncludes(
    `V06.R5.mcp-tool.${tool}`,
    'docs/reference/mcp-tools.md',
    tool,
  );
}
requireRegex(
  'V06.R6.release-readiness-claim-table',
  'docs/contributing/release-readiness.md',
  /Handoff Intelligence[\s\S]*Studio Reliability[\s\S]*Release Grade/,
  'v0.6 claim table in Handoff -> Studio -> Release order',
);
requireRegex(
  'V06.R7.observation-policy',
  'docs/contributing/release-readiness.md',
  /If evidence is missing, narrow or remove[\s\S]*checks:observation -- --json/,
  'observation evidence and claim narrowing policy',
);
requireRegex(
  'V06.R8.unreleased-caution',
  'docs/releases/unreleased.md',
  /v0\.6[\s\S]*(evidence|claim|release)/i,
  'a v0.6 unreleased note with evidence or claim wording',
);

if (failures.length > 0) {
  for (const failure of failures) console.error(failure);
  process.exit(1);
}

console.log('\nAll v0.6 release evidence rules pass.');
