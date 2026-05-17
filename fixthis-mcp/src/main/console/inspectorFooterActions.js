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
  if (action === 'delete') {
    const item = selectedSavedAnnotation();
    if (!item) return;
    const sessionId = toolMode.getState().focusedSavedSessionId || state.session?.sessionId || null;
    deleteSavedEvidenceItem(item.itemId, sessionId).catch(showError);
    return;
  }
  if (action === 'done') {
    const item = selectedSavedAnnotation();
    if (!item) return;
    const sessionId = toolMode.getState().focusedSavedSessionId || state.session?.sessionId || null;
    const phase = lifecyclePhase(item);
    const editable = phase === 'draft' || phase === 'sent' || phase === 'sent_modified' || phase === 'needs_clarification';
    toolMode.focusSavedItem(null, sessionId, item.screenId || null);
    renderPreviewOnly();
    renderInspectorRegion();
    if (editable) {
      persistSavedEvidenceItem(item, sessionId).catch(showError);
    }
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
