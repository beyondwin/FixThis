// @requires (none)
// inspectorFooter.js — state-dependent renderer for the inspector footer.

function renderInspectorFooter(editorState, ctx = {}) {
  const root = document.getElementById('inspectorFooter');
  if (!root) return;
  root.dataset.editorState = editorState;

  if (editorState === 'none') {
    root.hidden = true;
    root.replaceChildren();
    return;
  }

  const cancel = makeInspectorFooterButton({
    text: 'Cancel',
    action: 'cancel',
  });
  const addAnnotation = makeInspectorFooterButton({
    text: 'Add annotation',
    action: 'addAnnotation',
    className: 'primary',
  });
  addAnnotation.disabled = ctx.canAddAnnotation === false;
  const overflow = makeInspectorFooterButton({
    text: '...',
    action: 'overflowToggle',
    className: 'overflow-toggle',
    ariaLabel: 'More actions',
  });

  root.hidden = false;
  switch (editorState) {
    case 'pendingTarget':
      root.replaceChildren(cancel);
      return;
    case 'draft':
      root.replaceChildren(cancel, addAnnotation);
      return;
    case 'saved':
      root.replaceChildren(overflow);
      return;
    default:
      root.hidden = true;
      root.replaceChildren();
  }
}

function makeInspectorFooterButton({ text, action, className, ariaLabel }) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = text;
  button.dataset.action = action;
  if (className) button.className = className;
  if (ariaLabel) button.setAttribute('aria-label', ariaLabel);
  return button;
}
