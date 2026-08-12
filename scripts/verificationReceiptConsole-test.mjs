import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

const verificationSource = readFileSync('fixthis-mcp/src/main/console/verificationReceipt.js', 'utf8');
const detailSource = readFileSync(
  'fixthis-mcp/src/main/console/presentation/annotationDetailView.js',
  'utf8',
);
const stylesSource = readFileSync('fixthis-mcp/src/main/resources/console/styles.css', 'utf8');

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#39;',
  })[character]);
}

const verification = loadConsoleSymbols({
  modules: ['verificationReceipt.js'],
  symbols: [
    'receiptForItem',
    'verificationBadgeModel',
    'verificationBadgeHtml',
    'verificationReceiptSectionHtml',
  ],
  args: ['escapeHtml', 'formatTime'],
  values: [escapeHtml, value => `time:${String(value)}`],
});

function receipt({
  receiptId = 'receipt-1',
  itemId = 'item-1',
  baselineScreenId = 'baseline-screen',
  createdAtEpochMillis = 100,
  verdict = 'pass',
  checks = [{ kind: 'TARGET_PRESENT', outcome: 'passed', message: 'Target matched' }],
  assertions = [{ kind: 'target_present', value: null, role: null }],
  afterScreenshot = { desktopFullPath: '/tmp/after.png' },
} = {}) {
  return {
    receiptId,
    itemId,
    baselineScreenId,
    createdAtEpochMillis,
    verdict,
    checks,
    assertions,
    afterScreenshot,
  };
}

function session(receipts = [], overrides = {}) {
  return {
    sessionId: 'session-1',
    verificationReceipts: receipts,
    screens: [{
      screenId: 'baseline-screen',
      screenshot: { desktopFullPath: '/tmp/before.png' },
    }],
    ...overrides,
  };
}

function item(overrides = {}) {
  return { itemId: 'item-1', status: 'resolved', resolutionVerificationReceiptId: null, ...overrides };
}

test('derives all four text-labeled badge states', () => {
  assert.deepEqual(
    verification.verificationBadgeModel(
      session([receipt({ receiptId: 'pass-linked', verdict: 'pass' })]),
      item({ resolutionVerificationReceiptId: 'pass-linked' }),
    ),
    { tone: 'success', label: 'verified' },
  );
  assert.deepEqual(
    verification.verificationBadgeModel(
      session([receipt({ receiptId: 'warn-linked', verdict: 'warn' })]),
      item({ resolutionVerificationReceiptId: 'warn-linked' }),
    ),
    { tone: 'warning', label: 'warning' },
  );
  assert.deepEqual(
    verification.verificationBadgeModel(
      session([receipt({ receiptId: 'fail-latest', verdict: 'fail' })]),
      item({ status: 'in_progress' }),
    ),
    { tone: 'danger', label: 'verification failed' },
  );
  assert.deepEqual(
    verification.verificationBadgeModel(session(), item()),
    { tone: 'neutral', label: 'unverified' },
  );

  const badge = verification.verificationBadgeHtml(
    session([receipt({ receiptId: 'pass-linked' })]),
    item({ resolutionVerificationReceiptId: 'pass-linked' }),
  );
  assert.match(badge, />verified</);
  assert.match(badge, /aria-label="Verification: verified"/);
});

test('resolved items select only their linked receipt while unresolved items use latest deterministic receipt', () => {
  const receipts = [
    receipt({ receiptId: 'receipt-linked', createdAtEpochMillis: 1, verdict: 'warn' }),
    receipt({ receiptId: 'receipt-a', createdAtEpochMillis: 9, verdict: 'fail' }),
    receipt({ receiptId: 'receipt-z', createdAtEpochMillis: 9, verdict: 'pass' }),
    receipt({ receiptId: 'other-item', itemId: 'item-2', createdAtEpochMillis: 99 }),
  ];
  assert.equal(
    verification.receiptForItem(
      session(receipts),
      item({ resolutionVerificationReceiptId: 'receipt-linked' }),
    ).receiptId,
    'receipt-linked',
  );
  assert.equal(
    verification.receiptForItem(session(receipts), item({ status: 'in_progress' })).receiptId,
    'receipt-z',
  );
});

test('missing linked receipt does not silently substitute unrelated latest evidence', () => {
  const savedSession = session([receipt({ receiptId: 'latest-receipt', verdict: 'pass' })]);
  const resolved = item({ resolutionVerificationReceiptId: 'missing-receipt' });
  assert.equal(verification.receiptForItem(savedSession, resolved), null);
  assert.equal(verification.verificationBadgeModel(savedSession, resolved), null);
  assert.equal(verification.verificationReceiptSectionHtml(savedSession, resolved), '');
});

test('resolved item without a receipt link stays unverified and renders no substituted receipt card', () => {
  for (const verdict of ['pass', 'warn']) {
    const savedSession = session([
      receipt({ receiptId: `unlinked-${verdict}`, verdict, createdAtEpochMillis: 999 }),
    ]);
    const resolved = item({ resolutionVerificationReceiptId: null });

    assert.equal(verification.receiptForItem(savedSession, resolved), null);
    assert.deepEqual(
      verification.verificationBadgeModel(savedSession, resolved),
      { tone: 'neutral', label: 'unverified' },
    );
    assert.match(verification.verificationBadgeHtml(savedSession, resolved), />unverified</);
    assert.equal(verification.verificationReceiptSectionHtml(savedSession, resolved), '');
  }
});

test('renders semantic receipt details, escaped text, and encoded before/after URLs', () => {
  const unsafe = '<script dir="rtl">& خطر 길</script>';
  const savedReceipt = receipt({
    receiptId: 'receipt/one',
    baselineScreenId: 'baseline/screen',
    verdict: `warn${unsafe}`,
    checks: [{ kind: `CHECK${unsafe}`, outcome: `warning${unsafe}`, message: unsafe }],
    assertions: [{ kind: `text_present${unsafe}`, value: unsafe, role: `Button${unsafe}` }],
  });
  const savedSession = session([savedReceipt], {
    sessionId: 'session/one',
    screens: [{
      screenId: 'baseline/screen',
      screenshot: { desktopFullPath: '/tmp/before.png' },
    }],
  });
  const html = verification.verificationReceiptSectionHtml(
    savedSession,
    item({ status: 'in_progress' }),
  );

  assert.match(html, /^<section\b/);
  assert.match(html, /<h3>Verification receipt<\/h3>/);
  assert.match(html, /<dl\b/);
  assert.match(html, /<ul\b/);
  assert.match(html, /<figure\b/);
  assert.match(html, /alt="Baseline screenshot captured before the change"/);
  assert.match(html, /alt="Screenshot captured for this verification receipt"/);
  assert.match(
    html,
    /\/api\/screens\/baseline%2Fscreen\/screenshot\/full\?sessionId=session%2Fone/,
  );
  assert.match(
    html,
    /\/api\/verification-receipts\/receipt%2Fone\/screenshot\/after\?sessionId=session%2Fone/,
  );
  assert.doesNotMatch(html, /<script/);
  assert.match(html, /&lt;script dir=&quot;rtl&quot;&gt;&amp; خطر 길&lt;\/script&gt;/);
  assert.match(html, /dir="auto"/);
});

test('keeps long CJK and RTL receipt content visible and wrap-safe', () => {
  const longMessage = `${'검증메시지'.repeat(90)} ${'مرحبا'.repeat(90)}`;
  const savedReceipt = receipt({
    checks: [{ kind: 'LONG_CHECK', outcome: 'warning', message: longMessage }],
    assertions: [{ kind: 'text_present', value: longMessage, role: 'زر' }],
  });
  const html = verification.verificationReceiptSectionHtml(
    session([savedReceipt]),
    item({ status: 'in_progress' }),
  );
  assert.ok(html.includes(longMessage));
  assert.match(stylesSource, /\.verification-[^{]+\{[^}]*min-width:\s*0/s);
  assert.match(stylesSource, /\.verification-[^{]+\{[^}]*overflow-wrap:\s*anywhere/s);
  assert.match(stylesSource, /@media\s*\(max-width:\s*900px\)[\s\S]*\.verification-/);
});

test('uses refreshed session state at both call sites and introduces no polling or timers', () => {
  assert.match(detailSource, /verificationBadgeHtml\(state\.session, item\)/);
  assert.match(detailSource, /verificationReceiptSectionHtml\(state\.session, item\)/);
  assert.doesNotMatch(verificationSource, /setInterval|setTimeout|\bpoll(?:ing)?\b/i);
  assert.match(verificationSource, /^\/\/ @requires state\.js$/m);
});
