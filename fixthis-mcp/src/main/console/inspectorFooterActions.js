// @requires state.js, annotations.js, preview.js, inspectorFooter.js, editorState.js
// inspectorFooterActions.js - click handlers for the inspector footer and editor back target.

function handleInspectorFooterAction(action) {
  if (!action) return;
  if (action === 'cancel') {
    const editorState = deriveEditorState(currentDraftWorkspace(), draftSelection(), selectedSavedAnnotation());
    if (editorState === 'pendingTarget') clearSelection();
    else cancelAddItemsFlow();
    return;
  }
  if (action === 'addAnnotation') {
    if (draftSelection()) {
      try {
        createAnnotationFromSelection(draftSelection());
      } catch (cause) {
        showError(cause);
        return;
      }
    }
    navigateEditorToList();
    return;
  }
  if (action === 'overflowToggle') {
    showStatus('More actions are available from the annotation detail.');
  }
}

function navigateEditorToList() {
  setDraftFocusIndex(null);
  setDraftSelection(null);
  toolMode.focusSavedItem(null, null, null);
  comment.value = '';
  renderPreviewOnly();
  renderInspectorRegion();
}
