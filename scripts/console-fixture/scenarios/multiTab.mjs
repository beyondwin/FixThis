export async function applyMultiTab(fixture) {
  await fixture.applyScenario('multi-tab');
  return {
    label: 'multi-tab',
  };
}

export async function rapidSessionSwitchCancelsOldPreview({ page, server }) {
  await page.goto(server.consoleUrl);
  await page.getByRole('button', { name: 'Start' }).click();
  await server.createSession({ sessionId: 'session-a', title: 'Session A' });
  await server.createSession({ sessionId: 'session-b', title: 'Session B' });
  await server.delayPreviewForSession('session-a', 250);

  await page.getByText('Session 2').click();
  await page.getByText('Session 3').click();

  await page.waitForFunction(() => window.FixThisConsoleDebug?.getState?.().session?.sessionId === 'session-b');
  const preview = await page.evaluate(() => window.FixThisConsoleDebug?.getState?.().preview);
  if (preview && preview.sessionId === 'session-a') {
    throw new Error('stale session-a preview remained visible after switching to session-b');
  }
}
