// toolModeUseCases.js — action dispatchers for the tool-mode FSM.
//
// Pure synchronous transitions: no DOM, fetch, timers, localStorage.
// No browser adapter — the tool-mode FSM is consumed directly by
// annotations.js / history.js / preview.js / rendering.js / main.js
// / connection.js via the toolModeUseCases instance constructed in
// state.js.
//
// Factory shape:
//   createToolModeUseCases({ onChange })
//     -> { getState,
//          enterSelect, enterAnnotate,
//          startDrag, updateDragPreview, dropCommit, dropDiscard,
//          setHoveredTarget, setSuppressNextClick,
//          toggleHistoryDrawer, setHistoryDrawer,
//          focusSavedItem,
//          setAddItemsFlowStarting, setNewHistoryAnnotateModeStarting,
//          nextAnnotationSeq, setAnnotationSequenceAtLeast,
//          isSelectMode, isAnnotateMode, isDragging }
//
// `nextAnnotationSeq` preserves the legacy `'draft-' + annotationSequence++`
// increment-and-return shape. Use `setAnnotationSequenceAtLeast` for the
// `annotationSequence = Math.max(annotationSequence, next)` pattern used
// when recovering items from persisted drafts.

function createToolModeUseCases(options = {}) {
  const onChange = typeof options.onChange === 'function' ? options.onChange : () => {};
  let current = options.initialState ?? createEmptyToolMode();
  if (!Object.isFrozen(current)) current = Object.freeze({ ...current });

  function dispatch(action) {
    const next = reduceToolMode(current, action);
    if (next !== current) {
      current = next;
      onChange(current);
    }
    return current;
  }

  function getState() {
    return current;
  }

  function nextAnnotationSeq() {
    const previous = current.annotationSequence;
    dispatch({ type: 'ADVANCE_ANNOTATION_SEQ' });
    return previous;
  }

  return {
    getState,
    enterSelect: () => dispatch({ type: 'ENTER_SELECT' }),
    enterAnnotate: () => dispatch({ type: 'ENTER_ANNOTATE' }),
    startDrag: (point) => dispatch({ type: 'START_DRAG', point }),
    updateDragPreview: (preview) => dispatch({ type: 'UPDATE_DRAG_PREVIEW', preview }),
    dropCommit: () => dispatch({ type: 'DROP_COMMIT' }),
    dropDiscard: () => dispatch({ type: 'DROP_DISCARD' }),
    setHoveredTarget: (target) => dispatch({ type: 'SET_HOVERED_TARGET', target }),
    setSuppressNextClick: (value) => dispatch({ type: 'SET_SUPPRESS_NEXT_CLICK', value }),
    toggleHistoryDrawer: () => dispatch({ type: 'TOGGLE_HISTORY_DRAWER' }),
    setHistoryDrawer: (open) => dispatch({ type: 'SET_HISTORY_DRAWER', open }),
    focusSavedItem: (itemId, sessionId) => dispatch({ type: 'FOCUS_SAVED_ITEM', itemId, sessionId }),
    setAddItemsFlowStarting: (value) => dispatch({ type: 'SET_ADD_ITEMS_FLOW_STARTING', value }),
    setNewHistoryAnnotateModeStarting: (value) => dispatch({ type: 'SET_NEW_HISTORY_ANNOTATE_MODE_STARTING', value }),
    nextAnnotationSeq,
    setAnnotationSequenceAtLeast: (value) => dispatch({ type: 'SET_ANNOTATION_SEQUENCE_AT_LEAST', value }),
    isSelectMode: () => current.mode === ToolMode.SELECT,
    isAnnotateMode: () => current.mode === ToolMode.ANNOTATE_IDLE || current.mode === ToolMode.ANNOTATE_DRAGGING,
    isDragging: () => current.mode === ToolMode.ANNOTATE_DRAGGING,
  };
}
