import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  buildReleaseGateReport,
  normalizeEvidenceStep,
  renderReleaseGateMarkdown,
  releaseGateSteps,
  runReleaseGate,
  writeReleaseGateReports,
} from './release-gate.mjs';

test('releaseGateSteps include release drift host contracts connected proof and console evidence', () => {
  const commands = releaseGateSteps().map((step) => step.command);
  assert.ok(commands.includes('npm run release:reality'));
  assert.ok(commands.includes('npm run release:drift -- --strict'));
  assert.ok(commands.includes('./gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon'));
  assert.ok(commands.includes('./gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon'));
  assert.ok(commands.includes('npm run source-matching:fixtures:test'));
  assert.ok(commands.includes('./gradlew :fixthis-cli:test --tests "*AgentSetupVerificationServiceTest" --tests "*InstallAgentJsonReportTest" --tests "*TwoPhaseConfigCommitTest" --no-daemon'));
  assert.ok(commands.includes('npm run agent-loop:smoke:test'));
  assert.ok(commands.includes('npm run android:proof -- --strict --continue'));
  assert.ok(!commands.includes('npm run source-matching:fixtures:runtime -- --strict'));
  assert.ok(!commands.includes('npm run agent-loop:smoke -- --strict'));
  assert.ok(!commands.includes('npm run real-copy-prompt:smoke -- --strict'));
  assert.ok(!commands.includes('npm run external-fixture:matrix -- --strict'));
  assert.ok(commands.includes('npm run handoff:eval:test'));
  assert.ok(commands.includes('npm run console:browser:reliability'));
  assert.ok(commands.includes('node scripts/check-doc-consistency.mjs'));
  assert.ok(commands.includes('node scripts/check-whitespace.mjs diff --check'));
});

test('normalizeEvidenceStep maps passed failed and deferred statuses', () => {
  assert.equal(normalizeEvidenceStep({ name: 'ok', command: 'true', status: 'passed' }).status, 'pass');
  assert.equal(normalizeEvidenceStep({ name: 'skip', command: 'android', status: 'deferred', reason: 'no emulator' }).status, 'deferred');
  assert.equal(normalizeEvidenceStep({ name: 'bad', command: 'false', status: 'failed', exitCode: 1 }).status, 'fail');
});

test('strict release gate fails deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: true,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'fail');
});

test('non-strict release gate passes with deferred evidence', () => {
  const report = buildReleaseGateReport({
    strict: false,
    steps: [
      normalizeEvidenceStep({ name: 'Release reality', command: 'npm run release:reality', status: 'pass' }),
      normalizeEvidenceStep({ name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', reason: 'Android SDK unavailable' }),
    ],
  });

  assert.equal(report.status, 'pass_with_deferred');
});

test('markdown report renders deferred reasons', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'pass_with_deferred',
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    steps: [{
      name: 'Runtime trust',
      command: 'npm run source-matching:fixtures:runtime -- --strict',
      status: 'deferred',
      durationMs: 0,
      reason: 'Android SDK unavailable',
    }],
  });

  assert.match(text, /# FixThis Release Gate Report/);
  assert.match(text, /\| Runtime trust \| deferred \| `npm run source-matching:fixtures:runtime -- --strict` \| 0ms \| Android SDK unavailable \|/);
});

test('writeReleaseGateReports writes json and markdown artifacts', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-release-gate-'));
  try {
    const paths = writeReleaseGateReports({
      schemaVersion: '1.0',
      status: 'pass',
      strict: false,
      generatedAt: '2026-05-31T00:00:00.000Z',
      steps: [],
    }, root);
    assert.match(readFileSync(paths.json, 'utf8'), /"status": "pass"/);
    assert.match(readFileSync(paths.markdown, 'utf8'), /FixThis Release Gate Report/);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('runReleaseGate supports injected runner for tests', () => {
  const report = runReleaseGate({
    strict: false,
    runEvidenceProfile: () => ({
      steps: [
        { name: 'Release reality', command: 'npm run release:reality', status: 'passed', durationMs: 1 },
        { name: 'Runtime trust', command: 'npm run source-matching:fixtures:runtime -- --strict', status: 'deferred', durationMs: 0, reason: 'Android SDK unavailable' },
      ],
    }),
  });

  assert.equal(report.status, 'pass_with_deferred');
});

test('release gate report maps evidence steps to unlocked claims', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    steps: [
      { name: 'Release reality', command: 'npm run release:reality', status: 'pass' },
      { name: 'Release drift strict', command: 'npm run release:drift -- --strict', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
      { name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
      { name: 'Runtime trust boundary observations', command: 'npm run source-matching:fixtures:test', status: 'pass' },
      { name: 'Console browser reliability', command: 'npm run console:browser:reliability', status: 'pass' },
    ],
  });

  assert.deepEqual(report.claims, [
    {
      id: 'release-reality',
      status: 'pass',
      evidence: ['Release reality'],
    },
    {
      id: 'release-source-drift',
      status: 'pass',
      evidence: ['Release drift strict'],
    },
    {
      id: 'external-agent-loop',
      status: 'fail',
      evidence: ['Connected Android proof'],
      reason: 'missing evidence command: Agent loop smoke contracts',
    },
    {
      id: 'external-first-handoff-recovery',
      status: 'fail',
      evidence: ['Connected Android proof'],
      reason: 'missing evidence command: Agent loop smoke contracts',
    },
    {
      id: 'first-handoff-autopilot',
      status: 'fail',
      evidence: ['Connected Android proof'],
      reason: 'missing evidence commands: First handoff autopilot CLI contract, Agent loop smoke contracts',
    },
    {
      id: 'external-fixture-matrix',
      status: 'fail',
      evidence: ['Connected Android proof'],
      reason: 'missing evidence command: External fixture matrix contracts',
    },
    {
      id: 'external-trust-matrix-v2',
      status: 'fail',
      evidence: ['Connected Android proof'],
      reason: 'missing evidence command: External fixture matrix contracts',
    },
    {
      id: 'handoff-correctness-v2',
      status: 'pass',
      evidence: ['Handoff evaluation'],
    },
    {
      id: 'android-agent-evidence-umbrella',
      status: 'fail',
      evidence: ['Handoff evaluation'],
      reason: 'missing evidence commands: Runtime evidence attachment, Plugin contract',
    },
    {
      id: 'connected-android-proof',
      status: 'deferred',
      evidence: ['Connected Android proof'],
      reason: 'Android SDK unavailable',
    },
    {
      id: 'runtime-source-trust',
      status: 'deferred',
      evidence: ['Runtime trust boundary observations', 'Connected Android proof'],
      reason: 'Android SDK unavailable',
    },
    {
      id: 'console-sse-reliability',
      status: 'pass',
      evidence: ['Console browser reliability'],
    },
    {
      id: 'adb-discovery',
      status: 'fail',
      evidence: [],
      reason: 'missing evidence command',
    },
    {
      id: 'source-evidence-depth',
      status: 'pass',
      evidence: ['Runtime trust boundary observations'],
    },
    {
      id: 'interop-boundary-context',
      status: 'fail',
      evidence: [],
      reason: 'missing evidence command',
    },
    {
      id: 'sse-fallback-reliability',
      status: 'pass',
      evidence: ['Console browser reliability'],
    },
    {
      id: 'required-check-readiness',
      status: 'fail',
      evidence: [],
      reason: 'missing evidence command',
    },
  ]);
});

test('release gate maps external fixture matrix claim through connected proof plus contracts', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-01T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix contracts', command: 'npm run external-fixture:matrix:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-fixture-matrix'), {
    id: 'external-fixture-matrix',
    status: 'deferred',
    evidence: ['External fixture matrix contracts', 'Connected Android proof'],
    reason: 'Android SDK unavailable',
  });
});

test('release gate maps external trust matrix v2 claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-20T00:00:00.000Z',
    steps: [
      { name: 'External fixture matrix contracts', command: 'npm run external-fixture:matrix:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-trust-matrix-v2'), {
    id: 'external-trust-matrix-v2',
    status: 'deferred',
    evidence: ['External fixture matrix contracts', 'Connected Android proof'],
    reason: 'Android SDK unavailable',
  });
});

test('release gate maps connected Android proof claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-26T00:00:00.000Z',
    steps: [
      {
        name: 'Connected Android proof',
        command: 'npm run android:proof -- --strict --continue',
        status: 'deferred',
        reason: 'Android SDK platform-tools could not be resolved.',
      },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'connected-android-proof'), {
    id: 'connected-android-proof',
    status: 'deferred',
    evidence: ['Connected Android proof'],
    reason: 'Android SDK platform-tools could not be resolved.',
  });
});

test('release gate profile produces connected Android proof input', () => {
  const proof = releaseGateSteps().find((step) => step.name === 'Connected Android proof');
  assert.equal(proof.command, 'npm run android:proof -- --strict --continue');
  assert.equal(proof.deferrable, true);
  assert.equal(proof.requiresAndroid, true);
});

test('release gate maps external first handoff recovery claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-09T00:00:00.000Z',
    steps: [
      { name: 'Agent loop smoke contracts', command: 'npm run agent-loop:smoke:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-first-handoff-recovery'), {
    id: 'external-first-handoff-recovery',
    status: 'deferred',
    evidence: ['Agent loop smoke contracts', 'Connected Android proof'],
    reason: 'Android SDK unavailable',
  });
});

test('release gate maps first handoff autopilot claim', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-25T00:00:00.000Z',
    steps: [
      {
        name: 'First handoff autopilot CLI contract',
        command: './gradlew :fixthis-cli:test --tests "*AgentSetupVerificationServiceTest" --tests "*InstallAgentJsonReportTest" --tests "*TwoPhaseConfigCommitTest" --no-daemon',
        status: 'pass',
      },
      { name: 'Agent loop smoke contracts', command: 'npm run agent-loop:smoke:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'first-handoff-autopilot'), {
    id: 'first-handoff-autopilot',
    status: 'deferred',
    evidence: ['First handoff autopilot CLI contract', 'Agent loop smoke contracts', 'Connected Android proof'],
    reason: 'Android SDK unavailable',
  });
});

test('release gate fails first handoff autopilot when CLI unit evidence is missing', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-25T00:00:00.000Z',
    steps: [
      { name: 'Agent loop smoke contracts', command: 'npm run agent-loop:smoke:test', status: 'pass' },
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'first-handoff-autopilot'), {
    id: 'first-handoff-autopilot',
    status: 'fail',
    evidence: ['Agent loop smoke contracts', 'Connected Android proof'],
    reason: 'missing evidence command: First handoff autopilot CLI contract',
  });
});

test('release gate maps Android agent evidence umbrella claim', () => {
  const report = buildReleaseGateReport({
    steps: [
      { name: 'Handoff evaluation', command: 'npm run handoff:eval:test', status: 'pass' },
      { name: 'Runtime evidence attachment', command: 'npm run runtime-evidence:smoke', status: 'pass' },
      { name: 'Plugin contract', command: 'npm run plugin:contract:test', status: 'pass' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'android-agent-evidence-umbrella'), {
    id: 'android-agent-evidence-umbrella',
    status: 'pass',
    evidence: ['Handoff evaluation', 'Runtime evidence attachment', 'Plugin contract'],
  });
});

test('release gate profile produces Android agent evidence umbrella inputs', () => {
  const stepNames = releaseGateSteps().map((step) => step.name);

  assert.ok(stepNames.includes('Handoff evaluation'));
  assert.ok(stepNames.includes('Runtime evidence attachment'));
  assert.ok(stepNames.includes('Plugin contract'));
});

test('release gate fails external first handoff recovery when contract evidence is missing', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-09T00:00:00.000Z',
    steps: [
      { name: 'Connected Android proof', command: 'npm run android:proof -- --strict --continue', status: 'deferred', reason: 'Android SDK unavailable' },
    ],
  });

  assert.deepEqual(report.claims.find((claim) => claim.id === 'external-first-handoff-recovery'), {
    id: 'external-first-handoff-recovery',
    status: 'fail',
    evidence: ['Connected Android proof'],
    reason: 'missing evidence command: Agent loop smoke contracts',
  });
});

test('release gate markdown renders claim statuses', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'pass_with_deferred',
    strict: false,
    generatedAt: '2026-05-31T00:00:00.000Z',
    claims: [{
      id: 'external-agent-loop',
      status: 'deferred',
      evidence: ['Connected Android proof'],
      reason: 'Android SDK unavailable',
    }],
    steps: [],
  });

  assert.match(text, /## Release Claims/);
  assert.match(text, /\| external-agent-loop \| deferred \| Connected Android proof \| Android SDK unavailable \|/);
});

test('release gate enriches connected Android proof with child failures', () => {
  const root = mkdtempSync(join(tmpdir(), 'fixthis-proof-report-'));
  try {
    const reportDir = join(root, 'build/reports/fixthis-android-proof');
    mkdirSync(reportDir, { recursive: true });
    writeFileSync(join(reportDir, 'report.json'), `${JSON.stringify({
      schemaVersion: '1.0',
      status: 'fail',
      strict: true,
      failures: [{
        scope: 'step',
        step: 'Agent loop smoke',
        failureCode: 'agent_loop_failed',
        reason: 'Save to MCP through MCP failed.',
        nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      }],
    }, null, 2)}\n`);

    const normalized = normalizeEvidenceStep({
      name: 'Connected Android proof',
      command: 'npm run android:proof -- --strict --continue',
      status: 'failed',
      reportPath: 'build/reports/fixthis-android-proof/report.json',
    }, { root });

    assert.equal(normalized.detailReportPath, 'build/reports/fixthis-android-proof/report.json');
    assert.deepEqual(normalized.childFailures, [{
      scope: 'step',
      step: 'Agent loop smoke',
      failureCode: 'agent_loop_failed',
      reason: 'Save to MCP through MCP failed.',
      nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
    }]);
    assert.equal(normalized.reason, 'agent_loop_failed: Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test('release gate markdown renders connected proof child failures', () => {
  const text = renderReleaseGateMarkdown({
    schemaVersion: '1.0',
    status: 'fail',
    strict: true,
    generatedAt: '2026-06-27T00:00:00.000Z',
    claims: [],
    steps: [{
      name: 'Connected Android proof',
      command: 'npm run android:proof -- --strict --continue',
      status: 'fail',
      durationMs: 10,
      reason: 'agent_loop_failed: Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      childFailures: [{
        scope: 'step',
        step: 'Agent loop smoke',
        failureCode: 'agent_loop_failed',
        reason: 'Save to MCP through MCP failed.',
        nextAction: 'Inspect the agent-loop smoke report and rerun `npm run agent-loop:smoke -- --strict`.',
      }],
    }],
  });

  assert.match(text, /## Connected Android Proof Details/);
  assert.match(text, /\| Connected Android proof \| Agent loop smoke \| agent_loop_failed \| Inspect the agent-loop smoke report/);
});

test('release gate maps residual risk closure claims', () => {
  const report = buildReleaseGateReport({
    strict: false,
    generatedAt: '2026-06-02T00:00:00.000Z',
    steps: [
      { name: 'ADB discovery tests', command: './gradlew :fixthis-cli:test --tests "*AdbTest" --no-daemon', status: 'pass' },
      { name: 'Compose core source trust', command: './gradlew :fixthis-compose-core:test --tests "*SourceMatcherTest" --no-daemon', status: 'pass' },
      { name: 'Runtime trust boundary observations', command: 'npm run source-matching:fixtures:test', status: 'pass' },
      { name: 'Interop boundary contracts', command: './gradlew :fixthis-mcp:test --tests "*TargetBoundaryContextFormatterTest" --tests "*CompactHandoffRendererTest" --no-daemon', status: 'pass' },
      { name: 'Console reliability contracts', command: 'node --test scripts/console-reliability-report-test.mjs', status: 'pass' },
      { name: 'Console browser reliability', command: 'npm run console:browser:reliability', status: 'pass' },
      { name: 'Release readiness', command: 'node scripts/check-release-readiness.mjs', status: 'pass' },
    ],
  });

  const ids = report.claims.map((claim) => claim.id);
  assert.ok(ids.includes('adb-discovery'));
  assert.ok(ids.includes('source-evidence-depth'));
  assert.ok(ids.includes('interop-boundary-context'));
  assert.ok(ids.includes('sse-fallback-reliability'));
  assert.ok(ids.includes('required-check-readiness'));
});
