// @requires (none)
async function runConsoleEffect(effect, environment) {
  const dispatch = environment.dispatch;
  const ports = environment.ports || {};
  try {
    switch (effect.kind) {
      case 'openSession': {
        const session = await ports.sessionApi.openSession(effect.sessionId);
        dispatch({
          type: 'SESSION_OPEN_SUCCEEDED',
          requestId: effect.requestId,
          sessionId: effect.sessionId,
          generation: effect.generation,
          session,
        });
        return;
      }
      case 'capturePreview': {
        const preview = await ports.previewApi.capturePreview(effect.sessionId);
        dispatch({
          type: 'PREVIEW_CAPTURE_SUCCEEDED',
          requestId: effect.requestId,
          sessionId: effect.sessionId,
          generation: effect.generation,
          preview,
        });
        return;
      }
      case 'saveDraft': {
        const result = await ports.sessionApi.saveDraft(effect.sessionId, effect.items || []);
        dispatch({
          type: 'DRAFT_SAVE_SUCCEEDED',
          requestId: effect.requestId,
          sessionId: effect.sessionId,
          targetSessionId: effect.targetSessionId,
          workspaceId: effect.workspaceId,
          generation: effect.generation,
          session: result?.session || result,
        });
        return;
      }
      case 'persistRecovery':
        await ports.draftStorage.saveRecovery(effect.sessionId, effect.workspace);
        dispatch({ type: 'RECOVERY_PERSISTED', sessionId: effect.sessionId });
        return;
      default:
        dispatch({ type: 'CONSOLE_EFFECT_FAILED', effect, error: 'Unknown effect kind: ' + effect.kind });
    }
  } catch (cause) {
    dispatch({ type: 'CONSOLE_EFFECT_FAILED', effect, error: cause && cause.message ? cause.message : String(cause) });
  }
}
