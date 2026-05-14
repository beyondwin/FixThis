// @requires domain/workspaceState.js
function normalizeSessions(sessions = []) {
  const sessionsById = {};
  const sessionOrder = [];
  for (const session of sessions || []) {
    if (!session?.sessionId) continue;
    sessionsById[session.sessionId] = cloneConsoleValue(session);
    if (!sessionOrder.includes(session.sessionId)) sessionOrder.push(session.sessionId);
  }
  return { sessionsById, sessionOrder };
}

function createInitialConsoleAppState(options = {}) {
  const normalized = normalizeSessions(options.sessions || []);
  return Object.freeze({
    activeSessionId: options.activeSessionId || null,
    sessionsById: Object.freeze(normalized.sessionsById),
    sessionOrder: Object.freeze(normalized.sessionOrder),
    workspace: options.workspace || emptyWorkspace(),
    tool: Object.freeze(options.tool || { mode: 'select' }),
    connection: Object.freeze(options.connection || { current: null }),
    polling: Object.freeze(options.polling || { pendingMutationCount: 0 }),
    pendingBoundary: options.pendingBoundary || null,
    promptAction: Object.freeze(options.promptAction || { inFlight: false }),
    effectsGeneration: Number(options.effectsGeneration || 1),
    status: options.status || null,
  });
}

function replaceConsoleState(state, patch) {
  return Object.freeze({ ...state, ...patch });
}

function replaceSessions(state, sessions) {
  const normalized = normalizeSessions(sessions);
  return replaceConsoleState(state, {
    sessionsById: Object.freeze(normalized.sessionsById),
    sessionOrder: Object.freeze(normalized.sessionOrder),
  });
}

function upsertSession(state, session) {
  if (!session?.sessionId) return state;
  const sessionsById = Object.freeze({
    ...state.sessionsById,
    [session.sessionId]: cloneConsoleValue(session),
  });
  const sessionOrder = state.sessionOrder.includes(session.sessionId)
    ? state.sessionOrder
    : Object.freeze([...state.sessionOrder, session.sessionId]);
  return replaceConsoleState(state, { sessionsById, sessionOrder });
}

function nextGeneration(state) {
  return state.effectsGeneration + 1;
}

function nextRequestId(prefix, generation) {
  return `${prefix}-${generation}`;
}
