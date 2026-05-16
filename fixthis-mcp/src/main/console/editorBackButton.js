// @requires boundaryTriggers.js, boundaryPolicy.js, toastCopy.js
// editorBackButton.js - "All annotations" back navigation for editor states.

function renderEditorBack(ctx = {}) {
  const element = document.getElementById('editorBack');
  if (!element) return;
  element.hidden = ctx.state === 'none';
}

function handleEditorBackClick(ctx = {}, ports = {}) {
  const verdict = boundaryPolicy(Trigger.EDITOR_BACK, ctx.state || 'none', ctx);
  switch (verdict) {
    case 'pass':
      ports.navigateToList?.(ctx);
      return verdict;
    case 'discardWithToast': {
      ports.silentDiscard?.(ctx);
      const text = toastTextForTrigger(Trigger.EDITOR_BACK);
      if (text) ports.showToast?.(text, { className: 'info', duration: 1500, trigger: Trigger.EDITOR_BACK });
      ports.navigateToList?.(ctx);
      return verdict;
    }
    case 'boundaryDialog':
    case 'discardConfirm':
      ports.openBoundaryDialog?.('editorBack', ctx);
      return verdict;
    default:
      ports.navigateToList?.(ctx);
      return verdict;
  }
}
