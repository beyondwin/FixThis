// @requires domain/consoleReducer.js
function createConsoleStore(options = {}) {
  let current = options.initialState || createInitialConsoleAppState();
  const render = typeof options.render === 'function' ? options.render : () => {};
  const onEffects = typeof options.onEffects === 'function' ? options.onEffects : () => {};
  function getState() {
    return current;
  }
  function dispatch(event) {
    const result = reduceConsoleAppState(current, event);
    current = result.state;
    render(current);
    onEffects(result.effects, current);
    return result.effects;
  }
  return Object.freeze({ getState, dispatch });
}
