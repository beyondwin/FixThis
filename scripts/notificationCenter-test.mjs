import assert from 'node:assert/strict';
import { test } from 'node:test';
import { loadConsoleSymbols } from './console-test-loader.mjs';

function createRegistry() {
  const calls = [];
  return {
    calls,
    hide(id) {
      calls.push({ kind: 'hide', id });
    },
    show(id, opts) {
      calls.push({ kind: 'show', id, opts });
    },
  };
}

test('NotificationCenter routes recoverable errors to banner, not toast', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({
    severity: 'error',
    surface: 'toast',
    title: 'Save failed',
    message: 'Draft preserved locally.',
    primaryAction: 'Retry save',
    dedupeKey: 'save-failed',
  });

  const show = registry.calls.find((call) => call.kind === 'show');
  assert.equal(show.id, 'save-failed');
  assert.equal(show.opts.surfaceClass, 'banner');
  assert.equal(show.opts.content.headline, 'Save failed');
  assert.equal(show.opts.content.detail, 'Draft preserved locally.');
});

test('NotificationCenter dedupes repeated polling failures', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling' });
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling' });

  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 1);
});

test('NotificationCenter keeps success as ttl toast', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'success', surface: 'toast', message: 'Saved', ttlMs: 1200 });

  const show = registry.calls.find((call) => call.kind === 'show');
  assert.equal(show.opts.surfaceClass, 'toast');
  assert.equal(show.opts.autoDismissMs, 1200);
});

test('NotificationCenter releases dedupe key after ttl so a later notify re-displays', () => {
  const registry = createRegistry();
  const timers = [];
  const fakeSetTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry, setTimeoutImpl: fakeSetTimeout });

  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  // While active, repeated notify is suppressed.
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 1);

  // After ttl, dedupe entry is released; a follow-up notify re-displays.
  assert.equal(timers.length, 1);
  timers[0].fn();
  center.notify({ severity: 'warning', surface: 'toast', message: 'Polling paused', dedupeKey: 'polling', ttlMs: 1000 });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 2);
});

test('NotificationCenter auto-promotes severity:error off toast unless explicitly allowed', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'error', surface: 'toast', message: 'Network blip', dedupeKey: 'e1' });
  assert.equal(registry.calls.find((c) => c.kind === 'show' && c.id === 'e1').opts.surfaceClass, 'banner');

  // Opt-in keeps the toast surface, e.g. transient retry-succeeded-but-warn case.
  center.notify({ severity: 'error', surface: 'toast', message: 'Retried', dedupeKey: 'e2', allowErrorToast: true });
  assert.equal(registry.calls.find((c) => c.kind === 'show' && c.id === 'e2').opts.surfaceClass, 'toast');
});

test('state.js global-status path keeps stable dedupeKey for hide coordination', () => {
  const registry = createRegistry();
  const { createNotificationCenter } = loadConsoleSymbols({
    modules: ['notificationCenter.js'],
    symbols: ['createNotificationCenter'],
  });
  const center = createNotificationCenter({ registry });

  center.notify({ severity: 'error', surface: 'banner', message: 'boom', dedupeKey: 'global-error' });
  center.hide('global-error');
  const hide = registry.calls.find((call) => call.kind === 'hide');
  assert.equal(hide.id, 'global-error');
  // Re-notify after hide must succeed (not deduped).
  center.notify({ severity: 'error', surface: 'banner', message: 'boom again', dedupeKey: 'global-error' });
  assert.equal(registry.calls.filter((call) => call.kind === 'show').length, 2);
});
