export async function applyMultiTab(fixture) {
  await fixture.applyScenario('multi-tab');
  return {
    label: 'multi-tab',
  };
}
