export async function applySlowHandoff(fixture, options = {}) {
  const delayMs = options.delayMs ?? 6000;
  await fixture.runScenario({
    scenario: 'slow-handoff',
    overrides: { handoffDelayMs: delayMs },
  });
  return {
    label: 'slow-handoff',
    delayMs,
  };
}
