import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = readFileSync(resolve(root, 'fixthis-mcp/src/main/console/availability.js'), 'utf8');

// The console bundle is hoisted into a function scope. Evaluate with `new Function`
// and return the public surface for testing.
const factory = new Function(`${source}; return { computeBlockedReason, createBlockedReasonDebouncer, createUnresponsiveTracker };`);
const { computeBlockedReason, createBlockedReasonDebouncer, createUnresponsiveTracker } = factory();

test('priority order: screenOff beats everything', () => {
    const status = { screenInteractive: false, keyguardLocked: true, appForeground: false, pictureInPicture: true, unresponsive: true, rootsCount: 0 };
    assert.equal(computeBlockedReason(status, true), 'screenOff');
});

test('priority order: locked beats background and below', () => {
    assert.equal(
        computeBlockedReason({ screenInteractive: true, keyguardLocked: true, appForeground: false }, true),
        'locked'
    );
});

test('priority order: background beats pip and below', () => {
    assert.equal(
        computeBlockedReason({ screenInteractive: true, keyguardLocked: false, appForeground: false, pictureInPicture: true }, true),
        'background'
    );
});

test('priority order: pictureInPicture beats unresponsive', () => {
    assert.equal(
        computeBlockedReason({ screenInteractive: true, appForeground: true, pictureInPicture: true, unresponsive: true }, true),
        'pictureInPicture'
    );
});

test('unresponsive beats noComposeUi', () => {
    assert.equal(
        computeBlockedReason({ screenInteractive: true, appForeground: true, unresponsive: true, rootsCount: 0 }, true),
        'unresponsive'
    );
});

test('noComposeUi only applies in annotate mode', () => {
    const status = { screenInteractive: true, appForeground: true, rootsCount: 0 };
    assert.equal(computeBlockedReason(status, true), 'noComposeUi');
    assert.equal(computeBlockedReason(status, false), null);
});

test('null status returns null', () => {
    assert.equal(computeBlockedReason(null, true), null);
});

test('all-clear returns null', () => {
    assert.equal(
        computeBlockedReason({ screenInteractive: true, keyguardLocked: false, appForeground: true, pictureInPicture: false, rootsCount: 1 }, true),
        null
    );
});

test('legacy status with all nullable fields null returns null', () => {
    assert.equal(computeBlockedReason({ rootsCount: 1 }, true), null);
});

test('debouncer applies blocked transitions after delay', async () => {
    let now = 0;
    const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
    assert.equal(debouncer.observe('background'), null); // first observation, not yet committed
    now = 200;
    assert.equal(debouncer.observe('background'), null); // still under threshold
    now = 350;
    assert.equal(debouncer.observe('background'), 'background'); // committed
});

test('debouncer applies unblocked transitions immediately', () => {
    let now = 0;
    const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
    now = 350;
    debouncer.observe('background'); // commit blocked
    now = 360;
    assert.equal(debouncer.observe(null), null); // immediate clear
});

test('debouncer cancels pending block when observation resets to null', () => {
    let now = 0;
    const debouncer = createBlockedReasonDebouncer({ delayMs: 300, now: () => now });
    debouncer.observe('background');
    now = 100;
    assert.equal(debouncer.observe(null), null);
    now = 500;
    assert.equal(debouncer.observe(null), null);
});

test('unresponsiveTracker flips after 3 consecutive failures', () => {
    const tracker = createUnresponsiveTracker({ threshold: 3 });
    assert.equal(tracker.observeFailure(), false);
    assert.equal(tracker.observeFailure(), false);
    assert.equal(tracker.observeFailure(), true);
});

test('unresponsiveTracker resets on success', () => {
    const tracker = createUnresponsiveTracker({ threshold: 3 });
    tracker.observeFailure();
    tracker.observeFailure();
    tracker.observeSuccess();
    assert.equal(tracker.observeFailure(), false); // streak restarted
});

test('unresponsiveTracker backoff schedule', () => {
    const tracker = createUnresponsiveTracker({ threshold: 3 });
    assert.equal(tracker.nextBackoffMs(), 1000);
    tracker.observeFailure();
    assert.equal(tracker.nextBackoffMs(), 2000);
    tracker.observeFailure();
    assert.equal(tracker.nextBackoffMs(), 5000);
    tracker.observeFailure();
    assert.equal(tracker.nextBackoffMs(), 10000);
    tracker.observeFailure();
    assert.equal(tracker.nextBackoffMs(), 30000);
    tracker.observeFailure();
    assert.equal(tracker.nextBackoffMs(), 30000); // capped
});
