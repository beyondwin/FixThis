#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = resolve(dirname(scriptPath), '..');
export const defaultReleaseDriftReportDir = join(repoRoot, 'build/reports/fixthis-release-drift');

const knownCommandPrefixes = [
  './gradlew',
  'bash ',
  'node ',
  'git ',
];

function readRepoFile(file, root = repoRoot) {
  const path = join(root, file);
  return existsSync(path) ? readFileSync(path, 'utf8') : null;
}

function latestSemverTag(root = repoRoot) {
  try {
    return execFileSync('git', ['describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*'], {
      cwd: root,
      encoding: 'utf8',
    }).trim();
  } catch (_error) {
    return null;
  }
}

function commitsSince(tag, root = repoRoot) {
  if (!tag) return [];
  try {
    return execFileSync('git', ['log', '--oneline', `${tag}..HEAD`], {
      cwd: root,
      encoding: 'utf8',
    }).split('\n').filter(Boolean);
  } catch (_error) {
    return [];
  }
}

function sectionText(text, heading) {
  if (!text) return '';
  const escapedHeading = heading.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const start = text.match(new RegExp(`^##\\s+${escapedHeading}\\s*$`, 'im'));
  if (!start) return '';
  const after = text.slice((start.index ?? 0) + start[0].length);
  const next = after.search(/^##\s+/m);
  return (next >= 0 ? after.slice(0, next) : after).trim();
}

function hasNoUnreleasedChanges(text) {
  return /No unreleased changes/i.test(text || '') || /No unreleased changes have landed/i.test(text || '');
}

function hasMeaningfulUnreleasedContent(text) {
  const stripped = String(text || '')
    .replace(/No unreleased changes[^\n]*/gi, '')
    .replace(/See\s+\[[^\]]+\]\([^)]+\)[^\n]*/gi, '')
    .trim();
  return /[-*]\s+\S/.test(stripped) || /###\s+\S/.test(stripped);
}

export function commandTokensFromReleaseReadiness(text) {
  const commands = [];
  const regex = /`([^`]+)`/g;
  let match;
  while ((match = regex.exec(text || '')) !== null) {
    const value = match[1].trim();
    if (
      value.startsWith('npm run ') ||
      value.startsWith('./gradlew') ||
      value.startsWith('node ') ||
      value.startsWith('bash ')
    ) {
      commands.push(value);
    }
  }
  return [...new Set(commands)];
}

function packageScripts(files) {
  try {
    return JSON.parse(files['package.json'] || '{}').scripts || {};
  } catch (_error) {
    return {};
  }
}

function npmScriptName(command) {
  const match = command.match(/^npm run ([^ ]+)/);
  return match?.[1] || null;
}

function commandExists(command, scripts) {
  const npmScript = npmScriptName(command);
  if (npmScript) return Object.prototype.hasOwnProperty.call(scripts, npmScript);
  return knownCommandPrefixes.some((prefix) => command.startsWith(prefix));
}

function finding(id, severity, message, files = []) {
  return { id, severity, message, files };
}

export function analyzeReleaseDrift({ files, git, strict = false } = {}) {
  const loadedFiles = files || {
    'CHANGELOG.md': readRepoFile('CHANGELOG.md'),
    'docs/releases/unreleased.md': readRepoFile('docs/releases/unreleased.md'),
    'docs/contributing/release-readiness.md': readRepoFile('docs/contributing/release-readiness.md'),
    'package.json': readRepoFile('package.json'),
  };
  const latestTag = git?.latestTag ?? latestSemverTag();
  const commits = git?.commitsSinceTag ?? commitsSince(latestTag);
  const changelogUnreleased = sectionText(loadedFiles['CHANGELOG.md'], 'Unreleased');
  const releaseNotes = loadedFiles['docs/releases/unreleased.md'] || '';
  const findings = [];

  if (!loadedFiles['CHANGELOG.md']) {
    findings.push(finding('missing-changelog', 'fail', 'CHANGELOG.md must exist.', ['CHANGELOG.md']));
  }
  if (!loadedFiles['docs/releases/unreleased.md']) {
    findings.push(finding('missing-unreleased-notes', 'fail', 'docs/releases/unreleased.md must exist.', ['docs/releases/unreleased.md']));
  }

  if (latestTag && commits.length > 0 && hasNoUnreleasedChanges(changelogUnreleased) && hasNoUnreleasedChanges(releaseNotes)) {
    findings.push(finding(
      'tag-distance-drift',
      strict ? 'fail' : 'warning',
      `${commits.length} commit exists after ${latestTag}, but CHANGELOG.md and docs/releases/unreleased.md still claim no unreleased changes.`,
      ['CHANGELOG.md', 'docs/releases/unreleased.md'],
    ));
  }

  if (hasMeaningfulUnreleasedContent(changelogUnreleased) && hasNoUnreleasedChanges(releaseNotes)) {
    findings.push(finding(
      'unreleased-notes-drift',
      strict ? 'fail' : 'warning',
      'CHANGELOG.md has unreleased content but docs/releases/unreleased.md still says no unreleased changes landed.',
      ['CHANGELOG.md', 'docs/releases/unreleased.md'],
    ));
  }

  const scripts = packageScripts(loadedFiles);
  const commandTokens = commandTokensFromReleaseReadiness(loadedFiles['docs/contributing/release-readiness.md']);
  const missingCommands = commandTokens.filter((command) => !commandExists(command, scripts));
  if (missingCommands.length > 0) {
    findings.push(finding(
      'release-readiness-command-drift',
      'fail',
      `Release readiness references missing command(s): ${missingCommands.join(', ')}`,
      ['docs/contributing/release-readiness.md', 'package.json'],
    ));
  }

  return {
    schemaVersion: '1.0',
    latestTag,
    commitsSinceTag: commits,
    strict,
    findings,
  };
}

export function releaseDriftStatus(findings, strict = false) {
  if ((findings || []).some((entry) => entry.severity === 'fail')) return 'fail';
  if (strict && (findings || []).some((entry) => entry.severity === 'warning')) return 'fail';
  return 'pass';
}

export function buildReleaseDriftReport(findings, { strict = false, generatedAt = new Date().toISOString() } = {}) {
  return {
    schemaVersion: '1.0',
    status: releaseDriftStatus(findings, strict),
    strict,
    generatedAt,
    findings: findings || [],
  };
}

function cell(value) {
  if (value === null || value === undefined || value === '') return '-';
  return String(value).replaceAll('|', '\\|');
}

export function renderReleaseDriftMarkdown(report) {
  const lines = [
    '# FixThis Release Drift Report',
    '',
    '| Status | Strict | Generated |',
    '| --- | --- | --- |',
    `| ${cell(report.status)} | ${cell(report.strict)} | ${cell(report.generatedAt)} |`,
    '',
    '## Findings',
    '',
    '| Id | Severity | Message | Files |',
    '| --- | --- | --- | --- |',
  ];
  if ((report.findings || []).length === 0) {
    lines.push('| - | pass | No release drift detected. | - |');
  } else {
    for (const item of report.findings || []) {
      lines.push(`| ${cell(item.id)} | ${cell(item.severity)} | ${cell(item.message)} | ${cell((item.files || []).join(', '))} |`);
    }
  }
  return `${lines.join('\n')}\n`;
}

export function writeReleaseDriftReports(report, reportDir = defaultReleaseDriftReportDir) {
  mkdirSync(reportDir, { recursive: true });
  const json = join(reportDir, 'report.json');
  const markdown = join(reportDir, 'report.md');
  writeFileSync(json, `${JSON.stringify(report, null, 2)}\n`);
  writeFileSync(markdown, renderReleaseDriftMarkdown(report));
  return { json, markdown };
}

function parseArgs(argv) {
  const args = { strict: false };
  for (const arg of argv) {
    if (arg === '--strict') args.strict = true;
    else if (arg === '-h' || arg === '--help') {
      console.log('Usage: node scripts/release-drift-guard.mjs [--strict]');
      process.exit(0);
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return args;
}

function main() {
  const args = parseArgs(process.argv.slice(2));
  const analysis = analyzeReleaseDrift(args);
  const report = buildReleaseDriftReport(analysis.findings, args);
  const paths = writeReleaseDriftReports(report);
  console.log(`Release drift: ${report.status}`);
  console.log(`JSON: ${paths.json}`);
  console.log(`Markdown: ${paths.markdown}`);
  if (report.status === 'fail') process.exit(1);
}

if (process.argv[1] && resolve(process.argv[1]) === scriptPath) {
  main();
}
