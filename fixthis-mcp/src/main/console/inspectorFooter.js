// @requires (none)
// inspectorFooter.js — state-dependent renderer for the inspector footer.

function renderInspectorFooter(editorState, ctx = {}) {
  const root = document.getElementById('inspectorFooter');
  if (!root) return;
  root.dataset.editorState = editorState;

  if (editorState === 'none') {
    root.hidden = true;
    replaceFooterChildren(root);
    return;
  }

  root.hidden = false;
  switch (editorState) {
    case 'pendingTarget': {
      const cancel = makeInspectorFooterButton({ text: 'Cancel', action: 'cancel' });
      replaceFooterChildren(root, cancel);
      return;
    }
    case 'draft': {
      const cancel = makeInspectorFooterButton({ text: 'Cancel', action: 'cancel' });
      const addAnnotation = makeInspectorFooterButton({
        text: 'Add annotation',
        action: 'addAnnotation',
        className: 'primary',
      });
      addAnnotation.disabled = ctx.canAddAnnotation === false;
      replaceFooterChildren(root, cancel, addAnnotation);
      return;
    }
    case 'saved': {
      const del = makeInspectorFooterButton({
        text: 'Delete',
        action: 'delete',
        className: 'annotation-danger',
      });
      del.disabled = ctx.deletable === false;
      const done = makeInspectorFooterButton({
        text: 'Done',
        action: 'done',
        className: 'primary',
      });
      replaceFooterChildren(root, del, done);
      return;
    }
    default:
      root.hidden = true;
      replaceFooterChildren(root);
  }
}

function replaceFooterChildren(root, ...next) {
  const current = [...root.children];
  const sameActions = current.length === next.length &&
    next.every((button, index) => current[index]?.dataset?.action === button.dataset?.action);
  if (!sameActions) {
    root.replaceChildren(...next);
    return;
  }
  next.forEach((button, index) => {
    current[index].disabled = button.disabled;
  });
}

function makeInspectorFooterButton({ text, action, className }) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = text;
  button.dataset.action = action;
  if (className) button.className = className;
  return button;
}
