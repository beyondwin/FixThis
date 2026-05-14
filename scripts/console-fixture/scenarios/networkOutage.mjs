export async function applyNetworkOutage(fixture, options = {}) {
  await fixture.applyScenario('network-outage');
  return {
    label: 'network-outage',
    recoveryDelayMs: options.recoveryDelayMs ?? 3000,
    restore: async () => fixture.applyScenario('happy-path'),
  };
}
