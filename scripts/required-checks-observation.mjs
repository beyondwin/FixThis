#!/usr/bin/env node
import { execFileSync } from 'node:child_process';

const workflows = [
  '.github/workflows/ci.yml',
  '.github/workflows/codeql.yml',
  '.github/workflows/connected-tests.yml',
  '.github/workflows/nightly-compat.yml',
];

function ghJson(args) {
  try {
    return JSON.parse(execFileSync('gh', args, { encoding: 'utf8' }));
  } catch (_) {
    console.error('error: failed to query GitHub Actions with gh.');
    console.error('Run `gh auth status` and retry from a checkout with repository access.');
    process.exitCode = 2;
    return null;
  }
}

function consecutiveSuccesses(runs) {
  let count = 0;
  for (const run of runs) {
    if (run.status !== 'completed') continue;
    if (run.conclusion === 'success') count += 1;
    else break;
  }
  return count;
}

for (const workflow of workflows) {
  const runs = ghJson([
    'run',
    'list',
    '--workflow',
    workflow,
    '--branch',
    'main',
    '--limit',
    '20',
    '--json',
    'conclusion,createdAt,databaseId,status,url',
  ]);
  if (!runs) continue;
  const completedSuccesses = runs.filter((run) => run.status === 'completed' && run.conclusion === 'success');
  const firstGreen = completedSuccesses.at(-1)?.createdAt || 'none in last 20 runs';
  const streak = consecutiveSuccesses(runs);
  console.log(`${workflow}`);
  console.log(`  first green in sample: ${firstGreen}`);
  console.log(`  consecutive green completed runs from latest: ${streak}`);
  console.log(`  latest: ${runs[0]?.url || 'no runs'}`);
}
