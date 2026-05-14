// @requires domain/consoleSelectors.js
function createBrowserRenderer(bindings) {
  function render(state) {
    const model = {
      history: selectHistoryModel(state),
      canvas: selectCanvasModel(state),
      inspector: selectInspectorModel(state),
      prompt: selectPromptReadiness(state),
      boundary: selectBoundarySheet(state),
    };
    bindings.renderHistory?.(model.history, state);
    bindings.renderCanvas?.(model.canvas, state);
    bindings.renderInspector?.(model.inspector, state);
    bindings.renderPrompt?.(model.prompt, state);
    bindings.renderBoundary?.(model.boundary, state);
  }
  return Object.freeze({ render });
}
