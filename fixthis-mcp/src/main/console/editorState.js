// @requires (none)
// editorState.js - pure editor state derivation for annotation affordances.

function deriveEditorState(workspace, selection, savedItem) {
  const items = Array.isArray(workspace?.items) ? workspace.items : [];
  if (items.length > 0) return 'draft';
  if (savedItem && !workspace?.workspaceId) return 'saved';
  if (selection) return 'pendingTarget';
  return 'none';
}
