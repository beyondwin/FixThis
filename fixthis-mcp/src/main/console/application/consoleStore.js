// @requires domain/consoleReducer.js, domain/consoleInvariants.js
function createConsoleStore(options = {}) {
  let current = options.initialState || createInitialConsoleAppState();
  const render = typeof options.render === 'function' ? options.render : () => {};
  const onEffects = typeof options.onEffects === 'function' ? options.onEffects : () => {};
  assertConsoleInvariants(current);
  function getState() {
    return current;
  }
  function dispatch(event) {
    const result = reduceConsoleAppState(current, event);
    assertConsoleInvariants(result.state);
    current = result.state;
    render(current);
    onEffects(result.effects, current);
    return result.effects;
  }
  render(current);
  return Object.freeze({ getState, dispatch });
}
