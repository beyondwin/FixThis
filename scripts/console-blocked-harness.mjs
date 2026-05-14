#!/usr/bin/env node
// Tiny harness that reuses the shared fake bridge fixture so I can drive the
// console with Playwright MCP from the outside. Prints the URL and keeps
// running until the user interrupts (or exits immediately under
// --non-interactive for CI smoke).
import { startFakeBridge } from './console-fixture/fakeBridgeServer.mjs';

const nonInteractive =
  process.argv.includes('--non-interactive') ||
  process.env.FIXTHIS_BLOCKED_NON_INTERACTIVE === '1';

const fixture = await startFakeBridge({ scenario: 'blocked-welcome' });
console.log(`HARNESS_URL=${fixture.url}/`);
console.log(`open ${fixture.url} to drive the console`);

if (nonInteractive) {
  await fixture.close();
  process.exit(0);
}

process.on('SIGINT', async () => {
  await fixture.close();
  process.exit(0);
});
