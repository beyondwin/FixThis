// @requires (none)
// toolModeFsm.js — pure reducer for the tool-mode lifecycle.
//
// Owns the tool-mode-related state previously held as module-level lets in
// state.js: toolMode, annotationSequence, hoveredAnnotationTarget,
// dragStart, dragPreview, suppressNextClick, addItemsFlowStarting,
// newHistoryAnnotateModeStarting, historyDrawerOpen, focusedSavedItemId,
// focusedSavedSessionId, focusedSavedScreenId. No DOM, fetch, timers, or
// globals here.
//
// Modes (per console-state-machine-expansion §3.4):
//   SELECT             — default; clicks tap-through, annotation tools off
//   ANNOTATE_IDLE      — annotate mode armed; hover preview enabled, no drag
//   ANNOTATE_DRAGGING  — pointerdown in annotate mode created a drag region
//
// Action set:
//   ENTER_SELECT                      — switch to SELECT, clear hover/drag
//   ENTER_ANNOTATE                    — switch to ANNOTATE_IDLE
//   START_DRAG                        — pointerdown in annotate → DRAGGING
//   UPDATE_DRAG_PREVIEW               — pointermove during drag
//   DROP_COMMIT                       — pointerup, commit (advances seq)
//   DROP_DISCARD                      — pointerup, discard (no seq advance)
//   SET_HOVERED_TARGET                — hover overlay target
//   SET_SUPPRESS_NEXT_CLICK           — suppress next image click
//   TOGGLE_HISTORY_DRAWER             — flip drawer open/closed
//   SET_HISTORY_DRAWER                — set drawer open/closed explicitly
//   FOCUS_SAVED_ITEM                  — focus saved evidence item by id
//   SET_ADD_ITEMS_FLOW_STARTING       — set add-items flow starting flag
//   SET_NEW_HISTORY_ANNOTATE_MODE_STARTING — set history-annotate start flag
//   ADVANCE_ANNOTATION_SEQ            — bump seq by 1 (legacy seq++ shape)
//   SET_ANNOTATION_SEQUENCE_AT_LEAST  — raise seq to max(current, value)

const ToolMode = Object.freeze({
  SELECT: 'SELECT',
  ANNOTATE_IDLE: 'ANNOTATE_IDLE',
  ANNOTATE_DRAGGING: 'ANNOTATE_DRAGGING',
});

function createEmptyToolMode() {
  return Object.freeze({
    mode: ToolMode.SELECT,
    annotationSequence: 1,
    hoveredTarget: null,
    drag: null,
    suppressNextClick: false,
    focusedSavedItemId: null,
    focusedSavedSessionId: null,
    focusedSavedScreenId: null,
    historyDrawerOpen: false,
    addItemsFlowStarting: false,
    newHistoryAnnotateModeStarting: false,
  });
}

function reduceToolMode(state, action) {
  if (!state) state = createEmptyToolMode();
  if (!action || typeof action.type !== 'string') return state;
  switch (action.type) {
    case 'ENTER_SELECT':
      return Object.freeze({
        ...state,
        mode: ToolMode.SELECT,
        hoveredTarget: null,
        drag: null,
      });
    case 'ENTER_ANNOTATE':
      return Object.freeze({
        ...state,
        mode: ToolMode.ANNOTATE_IDLE,
      });
    case 'START_DRAG': {
      if (state.mode !== ToolMode.ANNOTATE_IDLE && state.mode !== ToolMode.ANNOTATE_DRAGGING) {
        return state;
      }
      return Object.freeze({
        ...state,
        mode: ToolMode.ANNOTATE_DRAGGING,
        drag: Object.freeze({ start: action.point || null, preview: null }),
      });
    }
    case 'UPDATE_DRAG_PREVIEW': {
      if (state.mode !== ToolMode.ANNOTATE_DRAGGING || !state.drag) return state;
      return Object.freeze({
        ...state,
        drag: Object.freeze({
          start: state.drag.start,
          preview: action.preview || null,
        }),
      });
    }
    case 'DROP_COMMIT': {
      if (state.mode !== ToolMode.ANNOTATE_DRAGGING) return state;
      return Object.freeze({
        ...state,
        mode: ToolMode.ANNOTATE_IDLE,
        drag: null,
        annotationSequence: state.annotationSequence + 1,
      });
    }
    case 'DROP_DISCARD': {
      // Defensive: only transition out of ANNOTATE_DRAGGING. Calling
      // dropDiscard while not dragging is a no-op (preserves mode and
      // ensures drag is null).
      if (state.mode !== ToolMode.ANNOTATE_DRAGGING && !state.drag) return state;
      return Object.freeze({
        ...state,
        mode: state.mode === ToolMode.ANNOTATE_DRAGGING ? ToolMode.ANNOTATE_IDLE : state.mode,
        drag: null,
      });
    }
    case 'SET_HOVERED_TARGET':
      return Object.freeze({
        ...state,
        hoveredTarget: action.target ?? null,
      });
    case 'SET_SUPPRESS_NEXT_CLICK':
      return Object.freeze({
        ...state,
        suppressNextClick: Boolean(action.value),
      });
    case 'TOGGLE_HISTORY_DRAWER':
      return Object.freeze({
        ...state,
        historyDrawerOpen: !state.historyDrawerOpen,
      });
    case 'SET_HISTORY_DRAWER':
      return Object.freeze({
        ...state,
        historyDrawerOpen: Boolean(action.open),
      });
    case 'FOCUS_SAVED_ITEM':
      return Object.freeze({
        ...state,
        focusedSavedItemId: action.itemId ?? null,
        focusedSavedSessionId: action.sessionId ?? null,
        focusedSavedScreenId: action.screenId ?? null,
      });
    case 'SET_ADD_ITEMS_FLOW_STARTING':
      return Object.freeze({
        ...state,
        addItemsFlowStarting: Boolean(action.value),
      });
    case 'SET_NEW_HISTORY_ANNOTATE_MODE_STARTING':
      return Object.freeze({
        ...state,
        newHistoryAnnotateModeStarting: Boolean(action.value),
      });
    case 'ADVANCE_ANNOTATION_SEQ':
      return Object.freeze({
        ...state,
        annotationSequence: state.annotationSequence + 1,
      });
    case 'SET_ANNOTATION_SEQUENCE_AT_LEAST': {
      const target = Number(action.value);
      if (!Number.isFinite(target)) return state;
      if (target <= state.annotationSequence) return state;
      return Object.freeze({
        ...state,
        annotationSequence: target,
      });
    }
    default:
      return state;
  }
}
