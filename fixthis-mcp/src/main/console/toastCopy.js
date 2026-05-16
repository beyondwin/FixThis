// @requires boundaryTriggers.js
// toastCopy.js - trigger to toast text for pending-target discard transitions.

const ToastCopy = Object.freeze({
  [Trigger.SESSION_SWITCH]: 'Switched - 1 empty annotation discarded',
  [Trigger.SESSION_CREATE]: 'New session - 1 empty annotation discarded',
  [Trigger.SESSION_DELETE]: 'Session deleted - 1 empty annotation discarded',
  [Trigger.SCREEN_SWITCH]: 'Different screen - 1 empty annotation discarded',
  [Trigger.NEW_CAPTURE]: 'New screen - 1 empty annotation discarded',
  [Trigger.ESCAPE_KEY]: 'Cancelled',
  [Trigger.ROUTE_CHANGE]: 'Navigated - 1 empty annotation discarded',
  [Trigger.BRIDGE_MISMATCH]: 'Bridge upgraded - 1 empty annotation discarded',
  [Trigger.EDITOR_BACK]: 'Cancelled',
});

function toastTextForTrigger(trigger) {
  return ToastCopy[trigger] || null;
}
