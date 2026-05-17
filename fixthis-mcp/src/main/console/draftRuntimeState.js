// @requires draftWorkspace.js
// draftRuntimeState.js - owns draft compatibility holders while callers migrate
// from state.js helpers to canonical store/selectors.

function createDraftRuntimeState({ persistCurrentDraftWorkspaceIfNeeded } = {}) {
  let draftFlowState = null;
  let draftPinsState = [];
  let draftFocusIndexState = null;
  let draftSelectionState = null;
  let undoRedoHistory = createHistory();
  let draftWorkspace = createEmptyDraftWorkspace();
  let draftCommandQueue = null;

  function currentDraftWorkspace() {
    return draftWorkspace;
  }

  function draftFlow() {
    return draftFlowState;
  }

  function draftItemList() {
    return draftPinsState;
  }

  function draftFocusIndex() {
    return draftFocusIndexState;
  }

  function setDraftFocusIndex(index) {
    draftFocusIndexState = index;
  }

  function draftSelection() {
    return draftSelectionState;
  }

  function setDraftSelection(selection) {
    draftSelectionState = selection;
  }

  function currentUndoRedoHistory() {
    return undoRedoHistory;
  }

  function replaceUndoRedoHistory(nextHistory) {
    undoRedoHistory = nextHistory || createHistory();
  }

  function currentDraftCommandQueue() {
    return draftCommandQueue;
  }

  function replaceDraftCommandQueue(nextQueue) {
    draftCommandQueue = nextQueue || null;
  }

  function replaceDraftWorkspace(nextWorkspace) {
    draftWorkspace = nextWorkspace || createEmptyDraftWorkspace();
    syncDraftWorkspaceCompatibility();
    if (typeof persistCurrentDraftWorkspaceIfNeeded === 'function') {
      persistCurrentDraftWorkspaceIfNeeded();
    }
  }

  function syncDraftWorkspaceCompatibility() {
    if (draftWorkspace.lifecycle === DraftLifecycle.EMPTY) {
      draftFlowState = null;
      draftPinsState = [];
      draftFocusIndexState = null;
      draftSelectionState = null;
      replaceUndoRedoHistory();
      return;
    }
    draftFlowState = {
      context: draftWorkspace.context,
      previewId: draftWorkspace.context?.previewId || null,
      screen: draftWorkspace.screen,
      screenshotUrl: draftWorkspace.screenshotUrl,
      frozenAtEpochMillis: draftWorkspace.context?.frozenAtEpochMillis || null,
      activity: draftWorkspace.context?.activityName || null,
      activityDriftWarning: draftWorkspace.activityDriftWarning || null,
    };
    draftPinsState = draftWorkspace.items || [];
    draftFocusIndexState = draftWorkspace.focusedItemId
      ? draftPinsState.findIndex((item) => item.draftItemId === draftWorkspace.focusedItemId)
      : null;
    if (draftFocusIndexState < 0) draftFocusIndexState = null;
    draftSelectionState = draftWorkspace.currentSelection;
    replaceUndoRedoHistory(draftWorkspace.history || createHistory(draftWorkspace.context));
  }

  return {
    currentDraftWorkspace,
    draftFlow,
    draftItemList,
    draftFocusIndex,
    setDraftFocusIndex,
    draftSelection,
    setDraftSelection,
    currentUndoRedoHistory,
    replaceUndoRedoHistory,
    currentDraftCommandQueue,
    replaceDraftCommandQueue,
    replaceDraftWorkspace,
    syncDraftWorkspaceCompatibility,
  };
}
